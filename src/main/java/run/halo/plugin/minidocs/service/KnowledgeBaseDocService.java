package run.halo.plugin.minidocs.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.MetadataUtil;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.plugin.minidocs.extension.KnowledgeBaseDoc;
import run.halo.plugin.minidocs.infra.MarkdownRenderer;
import run.halo.plugin.minidocs.setting.BasicSetting;

import static org.springframework.data.domain.Sort.Order;
import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.contains;
import static run.halo.app.extension.index.query.Queries.equal;

/**
 * 知识库文档业务服务：CRUD、文档树、slug、发布流程、移动、导出。
 *
 * @author Cosolar
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseDocService {

    public static final String PHASE_DRAFT = "draft";
    public static final String PHASE_PUBLISHED = "published";
    public static final String PHASE_ARCHIVED = "archived";

    private final ReactiveExtensionClient client;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ReactiveSettingFetcher settingFetcher;
    private final MarkdownRenderer markdownRenderer;

    /**
     * 分页查询知识库下的文档，支持 phase 与标题关键字过滤。
     */
    public Mono<ListResult<KnowledgeBaseDoc>> list(String kbName, String keyword, String phase,
        int page, int size) {
        var builder = ListOptions.builder();
        builder.fieldQuery(equal("spec.knowledgeBaseName", kbName));
        if (StringUtils.hasText(phase)) {
            builder.andQuery(equal("spec.phase", phase));
        }
        if (StringUtils.hasText(keyword)) {
            builder.andQuery(contains("spec.title", keyword));
        }
        var sort = Sort.by(Order.asc("spec.priority"), Order.asc("metadata.name"));
        return client.listBy(KnowledgeBaseDoc.class, builder.build(),
            PageRequestImpl.of(page, size, sort));
    }

    /**
     * 列出知识库下全部文档（供文档树构建）。
     */
    public Flux<KnowledgeBaseDoc> listAll(String kbName, String phase) {
        var builder = ListOptions.builder();
        builder.fieldQuery(equal("spec.knowledgeBaseName", kbName));
        if (StringUtils.hasText(phase)) {
            builder.andQuery(equal("spec.phase", phase));
        }
        return client.listAll(KnowledgeBaseDoc.class, builder.build(),
            Sort.by(Order.asc("spec.priority"), Order.asc("metadata.name")));
    }

    /**
     * 获取文档，并校验其属于指定知识库。
     */
    public Mono<KnowledgeBaseDoc> get(String kbName, String docName) {
        return client.fetch(KnowledgeBaseDoc.class, docName)
            .filter(doc -> kbName.equals(doc.getSpec().getKnowledgeBaseName()))
            .switchIfEmpty(Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "文档不存在: " + docName)));
    }

    /**
     * 创建文档：自动填充知识库名、slug（唯一）与标签。
     */
    public Mono<KnowledgeBaseDoc> create(String kbName, KnowledgeBaseDoc doc) {
        return knowledgeBaseService.get(kbName)
            .flatMap(kb -> {
                var spec = doc.getSpec();
                if (spec == null || !StringUtils.hasText(spec.getTitle())) {
                    return Mono.error(
                        new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档标题不能为空"));
                }
                spec.setKnowledgeBaseName(kbName);
                if (!StringUtils.hasText(spec.getPhase())) {
                    spec.setPhase(PHASE_DRAFT);
                }
                return uniqueSlug(kbName, spec.getSlug(), spec.getTitle(), 0)
                    .flatMap(slug -> {
                        spec.setSlug(slug);
                        renderContentHtml(spec);
                        doc.setSpec(spec);
                        if (doc.getMetadata() != null) {
                            MetadataUtil.nullSafeLabels(doc)
                                .put(KnowledgeBaseService.KNOWLEDGE_BASE_LABEL, kbName);
                        }
                        return client.create(doc);
                    });
            });
    }

    /**
     * 更新文档（整体替换 spec）；状态变为 published 且无发布时间时自动补写。
     */
    public Mono<KnowledgeBaseDoc> update(String kbName, String docName, KnowledgeBaseDoc update) {
        return get(kbName, docName).flatMap(existing -> {
            var spec = update.getSpec();
            if (spec == null || !StringUtils.hasText(spec.getTitle())) {
                return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档标题不能为空"));
            }
            renderContentHtml(spec);
            existing.setSpec(spec);
            if (PHASE_PUBLISHED.equals(spec.getPhase()) && spec.getPublishTime() == null) {
                spec.setPublishTime(Instant.now());
            }
            return client.update(existing);
        });
    }

    /**
     * 发布文档：phase 置为 published 并刷新 publishTime。
     */
    public Mono<Void> publish(String kbName, String docName) {
        return get(kbName, docName).flatMap(doc -> {
            doc.getSpec().setPhase(PHASE_PUBLISHED);
            doc.getSpec().setPublishTime(Instant.now());
            return client.update(doc).then();
        });
    }

    /**
     * 删除文档（级联删除其子树），并刷新所属知识库统计。
     */
    public Mono<Void> delete(String kbName, String docName) {
        return get(kbName, docName)
            .flatMap(doc -> deleteRecursively(docName)
                .then(knowledgeBaseService.refreshStats(kbName)));
    }

    private Mono<Void> deleteRecursively(String docName) {
        return client.fetch(KnowledgeBaseDoc.class, docName)
            .flatMap(doc -> client.listAll(KnowledgeBaseDoc.class,
                    ListOptions.builder()
                        .fieldQuery(equal("spec.parentName", docName))
                        .build(),
                    Sort.unsorted())
                .flatMap(child -> deleteRecursively(child.getMetadata().getName()))
                .then(client.delete(doc).then()));
    }

    /**
     * 移动文档：修改 parentName 与 priority，并阻止移动到自身或其子文档下。
     */
    public Mono<Void> move(String kbName, String docName, String parentName, Integer priority) {
        return get(kbName, docName)
            .flatMap(doc -> wouldCreateCycle(docName, parentName)
                .flatMap(cycle -> {
                    if (cycle) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "不能移动到自身或其子文档下"));
                    }
                    doc.getSpec().setParentName(
                        StringUtils.hasText(parentName) ? parentName : null);
                    if (priority != null) {
                        doc.getSpec().setPriority(priority);
                    }
                    return client.update(doc).then();
                }));
    }

    /**
     * 构建文档树。phase 为空表示包含全部状态（管理端）；传 published 表示仅已发布（公开端）。
     */
    public Mono<List<DocTreeNode>> buildTree(String kbName, String phase) {
        return listAll(kbName, phase).collectList().map(docs -> {
            Map<String, List<DocTreeNode>> byParent = docs.stream()
                .map(DocTreeNode::from)
                .collect(Collectors.groupingBy(
                    node -> node.getParentName() == null ? "" : node.getParentName(),
                    LinkedHashMap::new, Collectors.toList()));
            byParent.values().forEach(nodes -> nodes.sort(Comparator
                .comparing(DocTreeNode::getPriority, Comparator.nullsLast(Integer::compareTo))
                .thenComparing(DocTreeNode::getName)));
            return byParent.getOrDefault("", List.of()).stream()
                .map(node -> attachChildren(node, byParent))
                .toList();
        });
    }

    private DocTreeNode attachChildren(DocTreeNode node,
        Map<String, List<DocTreeNode>> byParent) {
        var children = new ArrayList<>(byParent.getOrDefault(node.getName(), List.of()));
        children.forEach(child -> attachChildren(child, byParent));
        node.setChildren(children);
        return node;
    }

    /**
     * 获取已发布文档（公开接口使用）。
     */
    public Mono<KnowledgeBaseDoc> getPublishedDoc(String kbName, String docName) {
        return get(kbName, docName)
            .filter(doc -> PHASE_PUBLISHED.equals(doc.getSpec().getPhase()));
    }

    /**
     * 按 slug 查询已发布文档（公开接口使用）。
     */
    public Mono<KnowledgeBaseDoc> getPublishedDocBySlug(String slug) {
        return client.listBy(KnowledgeBaseDoc.class,
                ListOptions.builder().fieldQuery(and(
                    equal("spec.slug", slug),
                    equal("spec.phase", PHASE_PUBLISHED))).build(),
                PageRequestImpl.of(1, 1))
            .flatMap(result -> result.getItems().stream().findFirst()
                .map(Mono::just)
                .orElseGet(() -> Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "文档不存在: " + slug))));
    }

    /**
     * 导出文档 Markdown，受 allowDocExport 设置约束。
     */
    public Mono<String> exportMarkdown(String kbName, String docName) {
        return settingFetcher.fetch("basic", BasicSetting.class)
            .map(BasicSetting::docExportEnabled)
            .defaultIfEmpty(true)
            .flatMap(enabled -> {
                if (!enabled) {
                    return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "文档导出已被管理员禁用"));
                }
                return get(kbName, docName)
                    .map(doc -> doc.getSpec().getContent() == null ? ""
                        : doc.getSpec().getContent());
            });
    }

    /**
     * 渲染 contentHtml：content 为 Markdown 时用 flexmark 渲染；已是 HTML（旧数据）则直接复用。
     */
    private void renderContentHtml(KnowledgeBaseDoc.Spec spec) {
        String content = spec.getContent();
        if (!StringUtils.hasText(content)) {
            spec.setContentHtml("");
            return;
        }
        if (content.stripLeading().startsWith("<")) {
            spec.setContentHtml(content);
            return;
        }
        spec.setContentHtml(markdownRenderer.render(content));
    }

    private Mono<Boolean> wouldCreateCycle(String docName, String parentName) {
        if (!StringUtils.hasText(parentName)) {
            return Mono.just(false);
        }
        if (docName.equals(parentName)) {
            return Mono.just(true);
        }
        return client.fetch(KnowledgeBaseDoc.class, parentName)
            .flatMap(parent -> wouldCreateCycle(docName, parent.getSpec().getParentName()))
            .defaultIfEmpty(true);
    }

    /**
     * 生成知识库内唯一的 slug：优先使用传入 slug 或由标题生成，冲突时追加随机后缀。
     */
    private Mono<String> uniqueSlug(String kbName, String slug, String title, int attempt) {
        var base = StringUtils.hasText(slug) ? slug : slugify(title);
        var candidate = attempt == 0 ? base : base + "-" + randomSuffix();
        return client.listBy(KnowledgeBaseDoc.class,
                ListOptions.builder().fieldQuery(and(
                    equal("spec.knowledgeBaseName", kbName),
                    equal("spec.slug", candidate))).build(),
                PageRequestImpl.of(1, 1))
            .flatMap(result -> result.getItems().isEmpty()
                ? Mono.just(candidate)
                : uniqueSlug(kbName, base, title, attempt + 1));
    }

    private String slugify(String input) {
        if (!StringUtils.hasText(input)) {
            return "doc-" + randomSuffix();
        }
        var slug = input.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "-")
            .replaceAll("(^-+|-+$)", "");
        return StringUtils.hasText(slug) ? slug : "doc-" + randomSuffix();
    }

    private String randomSuffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    /**
     * 文档树节点 DTO。
     */
    @Data
    @Builder
    public static class DocTreeNode {

        private String name;
        private String title;
        private String slug;
        private String phase;
        private Instant publishTime;
        private Integer priority;
        private String parentName;
        private List<DocTreeNode> children;

        public static DocTreeNode from(KnowledgeBaseDoc doc) {
            var spec = doc.getSpec();
            return DocTreeNode.builder()
                .name(doc.getMetadata().getName())
                .title(spec.getTitle())
                .slug(spec.getSlug())
                .phase(spec.getPhase())
                .publishTime(spec.getPublishTime())
                .priority(spec.getPriority())
                .parentName(spec.getParentName())
                .children(new ArrayList<>())
                .build();
        }
    }
}
