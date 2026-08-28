package run.halo.plugin.minidocs.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import tools.jackson.databind.JsonNode;
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
import run.halo.app.extension.Metadata;
import run.halo.app.extension.MetadataUtil;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import tools.jackson.databind.json.JsonMapper;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.plugin.minidocs.extension.KnowledgeBase;
import run.halo.plugin.minidocs.extension.KnowledgeBaseDoc;
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

    private final ReactiveExtensionClient client;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ReactiveSettingFetcher settingFetcher;

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
     * 创建文档：自动填充知识库名、slug（唯一）、作者（当前用户）与更新时间。
     */
    public Mono<KnowledgeBaseDoc> create(String kbName, KnowledgeBaseDoc doc) {
        return create(kbName, doc, null);
    }

    /**
     * 创建文档，可指定作者（通常为当前登录用户）；作者为空时回退到 "unknown"。
     */
    public Mono<KnowledgeBaseDoc> create(String kbName, KnowledgeBaseDoc doc, String author) {
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
                // 创建文档时默认写入创建时间与更新时间
                var now = Instant.now();
                if (spec.getCreationTime() == null) {
                    spec.setCreationTime(now);
                }
                spec.setUpdateTime(now);
                var authorMono = StringUtils.hasText(spec.getAuthor())
                    ? Mono.just(spec.getAuthor())
                    : Mono.justOrEmpty(author).defaultIfEmpty("unknown");
                return authorMono.flatMap(name -> {
                    spec.setAuthor(name);
                    return uniqueSlug(kbName, spec.getSlug(), spec.getTitle(), 0);
                }).flatMap(slug -> {
                    spec.setSlug(slug);
                    doc.setSpec(spec);
                    MetadataUtil.nullSafeLabels(doc)
                        .put(KnowledgeBaseService.KNOWLEDGE_BASE_LABEL, kbName);
                    return client.create(doc)
                        .flatMap(created -> knowledgeBaseService.touch(kbName)
                            .thenReturn(created));
                });
            });
    }

    /**
     * 更新文档（整体替换 spec）：刷新更新时间；状态变为 published 且无发布时间时自动补写。
     */
    public Mono<KnowledgeBaseDoc> update(String kbName, String docName, KnowledgeBaseDoc update) {
        return get(kbName, docName).flatMap(existing -> {
            var spec = update.getSpec();
            if (spec == null || !StringUtils.hasText(spec.getTitle())) {
                return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "文档标题不能为空"));
            }
            // 创建人（author）与创建时间由系统维护：更新时若未显式提供则保留原有值，避免被清空
            if (!StringUtils.hasText(spec.getAuthor())) {
                spec.setAuthor(existing.getSpec().getAuthor());
            }
            spec.setCreationTime(existing.getSpec().getCreationTime());
            existing.setSpec(spec);
            spec.setUpdateTime(Instant.now());
            if (PHASE_PUBLISHED.equals(spec.getPhase()) && spec.getPublishTime() == null) {
                spec.setPublishTime(Instant.now());
            }
            return client.update(existing)
                .flatMap(updated -> knowledgeBaseService.touch(kbName).thenReturn(updated));
        });
    }

    /**
     * 发布文档：phase 置为 published 并刷新 publishTime 与 updateTime。
     */
    public Mono<Void> publish(String kbName, String docName) {
        return get(kbName, docName).flatMap(doc -> {
            doc.getSpec().setPhase(PHASE_PUBLISHED);
            doc.getSpec().setPublishTime(Instant.now());
            doc.getSpec().setUpdateTime(Instant.now());
            return client.update(doc)
                .then(knowledgeBaseService.touch(kbName));
        });
    }

    /**
     * 删除文档（级联删除其子树），并刷新所属知识库统计（含“最近更新”时间）。
     */
    public Mono<Void> delete(String kbName, String docName) {
        return get(kbName, docName)
            .flatMap(doc -> deleteRecursively(docName)
                // 等待根文档从索引中移除，保证删除返回后目录树查询不再返回该节点
                .then(awaitIndexRemoved(docName))
                .then(knowledgeBaseService.refreshStats(kbName)));
    }

    /**
     * 轮询等待索引删除完成。Halo 的扩展删除通过异步 store-watcher 同步索引，删除返回后立即
     * 用 listAll 查询可能仍读到旧数据；此方法带超时与容错，索引未及时删除也不阻塞主流程。
     */
    private Mono<Void> awaitIndexRemoved(String name) {
        return Mono.defer(() -> client.fetch(KnowledgeBaseDoc.class, name)
                .switchIfEmpty(Mono.empty())
                .flatMap(doc -> Mono.delay(Duration.ofMillis(120))
                    .then(awaitIndexRemoved(name))))
            .timeout(Duration.ofSeconds(5))
            .onErrorResume(e -> Mono.empty());
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
     *
     * <p>当仅传 parentName / priority 时保持原有行为（简单移动）；当传 beforeName 或
     * afterName 时，在目标节点的同级中执行排序（插入到其之前或之后），并重排同组兄弟节点的
     * priority 为连续整数，保证顺序与显示一致。
     */
    public Mono<Void> move(String kbName, String docName, String parentName, Integer priority,
        String beforeName, String afterName) {
        return get(kbName, docName)
            .flatMap(moved -> wouldCreateCycle(docName, parentName)
                .flatMap(cycle -> {
                    if (cycle) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "不能移动到自身或其子文档下"));
                    }
                    String parent = StringUtils.hasText(parentName) ? parentName : null;
                    moved.getSpec().setParentName(parent);
                    // 同级排序：插入到目标之前/之后，并重排兄弟节点
                    if (StringUtils.hasText(beforeName) || StringUtils.hasText(afterName)) {
                        return reorderSiblings(kbName, parent, beforeName, afterName, docName,
                            moved)
                            .then(client.update(moved)
                                .then(knowledgeBaseService.touch(kbName)));
                    }
                    if (priority != null) {
                        moved.getSpec().setPriority(priority);
                    }
                    return client.update(moved)
                        .then(knowledgeBaseService.touch(kbName));
                }));
    }

    /**
     * 将 moved 节点插入到目标节点（target = beforeName 或 afterName）之前/之后，
     * 并把目标父级下的整组兄弟节点 priority 重排为 0..n-1。
     */
    private Mono<Void> reorderSiblings(String kbName, String parentName, String beforeName,
        String afterName, String docName, KnowledgeBaseDoc moved) {
        String targetName = StringUtils.hasText(beforeName) ? beforeName : afterName;
        boolean isAfter = !StringUtils.hasText(beforeName);
        String parent = StringUtils.hasText(parentName) ? parentName : null;
        return client.listAll(KnowledgeBaseDoc.class,
                ListOptions.builder()
                    .fieldQuery(equal("spec.knowledgeBaseName", kbName))
                    .build(),
                Sort.by(Order.asc("spec.priority"), Order.asc("metadata.name")))
            .collectList()
            .flatMap(all -> {
                List<KnowledgeBaseDoc> docs = all.stream()
                    .filter(d -> Objects.equals(d.getSpec().getParentName(), parent))
                    .collect(Collectors.toCollection(ArrayList::new));
                docs.removeIf(d -> d.getMetadata().getName().equals(docName));
                int idx;
                if (!StringUtils.hasText(targetName)) {
                    idx = isAfter ? docs.size() : 0;
                } else {
                    idx = indexOfDoc(docs, targetName);
                    if (idx < 0) {
                        idx = isAfter ? docs.size() : 0;
                    } else if (isAfter) {
                        idx += 1;
                    }
                }
                docs.add(idx, moved);
                Mono<Void> chain = Mono.empty();
                for (int i = 0; i < docs.size(); i++) {
                    KnowledgeBaseDoc d = docs.get(i);
                    if (d.getMetadata().getName().equals(docName)) {
                        moved.getSpec().setPriority(i);
                        continue;
                    }
                    if (!Objects.equals(d.getSpec().getPriority(), i)) {
                        d.getSpec().setPriority(i);
                        chain = chain.then(client.update(d).then());
                    }
                }
                return chain;
            });
    }

    private int indexOfDoc(List<KnowledgeBaseDoc> docs, String name) {
        for (int i = 0; i < docs.size(); i++) {
            if (docs.get(i).getMetadata().getName().equals(name)) {
                return i;
            }
        }
        return -1;
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
     * 生成知识库内唯一的 slug：优先使用传入 slug；留空时按
     * {@code DOC + yyyyMMddHHmmss + 6位随机数} 自动生成，冲突时追加随机后缀。
     */
    private Mono<String> uniqueSlug(String kbName, String slug, String title, int attempt) {
        var base = StringUtils.hasText(slug) ? slug : autoSlug();
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

    private String autoSlug() {
        var ts = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
            .format(LocalDateTime.now());
        return "DOC" + ts + randomSuffix();
    }

    private String randomSuffix() {
        return UUID.randomUUID().toString().substring(0, 6);
    }

    // ==================== 知识库导入 / 导出 ====================

    /**
     * 将若干知识库打包导出为 zip 字节流。
     * <p>每个知识库一个顶层文件夹，内含 config.json（描述层级与元数据）与 docs/*.md。
     */
    public Mono<byte[]> exportZip(List<String> names) {
        return Flux.fromIterable(names)
            .concatMap(this::exportOneKnowledgeBase)
            .collectList()
            .map(this::buildZip);
    }

    private Mono<List<ExportEntry>> exportOneKnowledgeBase(String name) {
        return client.fetch(KnowledgeBase.class, name)
            .flatMap(kb -> listAll(name, null).collectList().map(docs -> {
                var dirName = sanitizeDirName(kb.getSpec().getDisplayName());
                List<ExportEntry> entries = new ArrayList<>();
                var config = buildExportConfig(kb.getSpec(), docs);
                entries.add(new ExportEntry(dirName + "/config.json", writeJson(config)));
                for (var doc : docs) {
                    var slug = resolveSlug(doc);
                    var content = doc.getSpec().getContent() == null
                        ? "" : doc.getSpec().getContent();
                    entries.add(new ExportEntry(dirName + "/docs/" + slug + ".md",
                        content.getBytes(StandardCharsets.UTF_8)));
                }
                return entries;
            }))
            .defaultIfEmpty(List.<ExportEntry>of());
    }

    private Map<String, Object> buildExportConfig(KnowledgeBase.Spec kbSpec,
        List<KnowledgeBaseDoc> docs) {
        var cfg = new LinkedHashMap<String, Object>();
        cfg.put("version", 1);
        var kb = new LinkedHashMap<String, Object>();
        kb.put("displayName", nullSafe(kbSpec.getDisplayName()));
        kb.put("description", nullSafe(kbSpec.getDescription()));
        kb.put("cover", nullSafe(kbSpec.getCover()));
        kb.put("logo", nullSafe(kbSpec.getLogo()));
        kb.put("tags", kbSpec.getTags() == null ? List.of() : kbSpec.getTags());
        kb.put("publicVisible", Boolean.TRUE.equals(kbSpec.getPublicVisible()));
        cfg.put("knowledgeBase", kb);

        Map<String, String> slugByName = new HashMap<>();
        for (var doc : docs) {
            slugByName.put(doc.getMetadata().getName(), resolveSlug(doc));
        }
        var docsArr = new ArrayList<Map<String, Object>>();
        for (var doc : docs) {
            var spec = doc.getSpec();
            var m = new LinkedHashMap<String, Object>();
            m.put("slug", slugByName.get(doc.getMetadata().getName()));
            m.put("title", nullSafe(spec.getTitle()));
            var parentName = spec.getParentName();
            m.put("parent", parentName == null ? "" : slugByName.getOrDefault(parentName, ""));
            m.put("phase", spec.getPhase() == null ? PHASE_DRAFT : spec.getPhase());
            m.put("priority", spec.getPriority());
            m.put("summary", nullSafe(spec.getSummary()));
            m.put("cover", nullSafe(spec.getCover()));
            m.put("author", nullSafe(spec.getAuthor()));
            m.put("creationTime", spec.getCreationTime() == null ? null
                : spec.getCreationTime().toString());
            m.put("publishTime", spec.getPublishTime() == null ? null
                : spec.getPublishTime().toString());
            m.put("file", "docs/" + slugByName.get(doc.getMetadata().getName()) + ".md");
            docsArr.add(m);
        }
        cfg.put("documents", docsArr);
        return cfg;
    }

    private String resolveSlug(KnowledgeBaseDoc doc) {
        var slug = doc.getSpec().getSlug();
        return StringUtils.hasText(slug) ? slug : "doc-" + doc.getMetadata().getName();
    }

    private byte[] writeJson(Map<String, Object> map) {
        try {
            return JsonMapper.builder().build().writeValueAsBytes(map);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化 config.json 失败", ex);
        }
    }

    private byte[] buildZip(List<List<ExportEntry>> groups) {
        var baos = new ByteArrayOutputStream();
        try (var zos = new ZipOutputStream(baos)) {
            for (var group : groups) {
                for (var e : group) {
                    zos.putNextEntry(new ZipEntry(e.path()));
                    zos.write(e.data());
                    zos.closeEntry();
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("导出 zip 失败", ex);
        }
        return baos.toByteArray();
    }

    /**
     * 解析 zip，返回其中知识库清单（含是否已存在同名知识库）。
     */
    public Mono<List<ImportPreviewItem>> previewImport(byte[] zip) {
        var infos = parseZip(zip);
        return Flux.fromIterable(infos)
            .concatMap(info -> knowledgeBaseService.findExistingByName(info.kbConfig.displayName())
                .map(kb -> true)
                .defaultIfEmpty(false)
                .map(exists -> new ImportPreviewItem(info.kbConfig.displayName(),
                    info.documents().size(), exists)))
            .collectList();
    }

    /**
     * 执行导入：strategy 为 overwrite（覆盖同名）或 skip（跳过同名）。
     */
    public Mono<List<ImportResultItem>> importFromZip(byte[] zip, String strategy,
        String creator) {
        var infos = parseZip(zip);
        return Flux.fromIterable(infos)
            .concatMap(info -> importOne(info, strategy, creator))
            .collectList();
    }

    private Mono<ImportResultItem> importOne(ImportInfo info, String strategy, String creator) {
        return knowledgeBaseService.findExistingByName(info.kbConfig.displayName())
            .flatMap(existing -> {
                if ("skip".equalsIgnoreCase(strategy)) {
                    return Mono.just(new ImportResultItem(info.kbConfig.displayName(), false,
                        "已存在同名知识库，已跳过"));
                }
                return knowledgeBaseService.delete(existing.getMetadata().getName())
                    .then(doImportKnowledgeBase(info, creator))
                    .thenReturn(new ImportResultItem(info.kbConfig.displayName(), true, "已覆盖导入"));
            })
            .switchIfEmpty(Mono.defer(() -> doImportKnowledgeBase(info, creator)
                .thenReturn(new ImportResultItem(info.kbConfig.displayName(), true, "导入成功"))));
    }

    private Mono<Void> doImportKnowledgeBase(ImportInfo info, String creator) {
        var kb = new KnowledgeBase();
        var kbMd = new Metadata();
        kbMd.setName(UUID.randomUUID().toString());
        kb.setMetadata(kbMd);
        var spec = new KnowledgeBase.Spec();
        var c = info.kbConfig;
        spec.setDisplayName(c.displayName());
        spec.setSlug(nullSafe(c.slug()));
        spec.setDescription(nullSafe(c.description()));
        spec.setCover(nullSafe(c.cover()));
        spec.setLogo(nullSafe(c.logo()));
        spec.setTags(c.tags());
        spec.setPublicVisible(c.publicVisible());
        kb.setSpec(spec);
        return knowledgeBaseService.create(kb, creator).flatMap(created -> {
            var kbName = created.getMetadata().getName();
            return Flux.fromIterable(info.documents())
                .concatMap(d -> createImportedDoc(kbName, d, info.contentBySlug(), creator))
                .collectMap(ImportedDocument::slug, ImportedDocument::name, HashMap::new)
                .flatMap(nameBySlug -> Flux.fromIterable(info.documents())
                    .filter(d -> StringUtils.hasText(d.parent()))
                    .concatMap(d -> {
                        var parentName = nameBySlug.get(d.parent());
                        if (parentName == null) {
                            return Mono.empty();
                        }
                        return client.fetch(KnowledgeBaseDoc.class, nameBySlug.get(d.slug()))
                            .flatMap(pDoc -> {
                                pDoc.getSpec().setParentName(parentName);
                                return client.update(pDoc).then();
                            });
                    })
                    .then(knowledgeBaseService.refreshStats(kbName)));
        });
    }

    private Mono<ImportedDocument> createImportedDoc(String kbName, ImportedDocInfo d,
        Map<String, byte[]> contentBySlug, String creator) {
        var doc = new KnowledgeBaseDoc();
        var md = new Metadata();
        md.setName(UUID.randomUUID().toString());
        doc.setMetadata(md);
        var spec = new KnowledgeBaseDoc.Spec();
        spec.setKnowledgeBaseName(kbName);
        spec.setTitle(nullSafe(d.title()));
        spec.setSlug(d.slug());
        spec.setPhase(d.phase() == null ? PHASE_DRAFT : d.phase());
        spec.setPriority(d.priority());
        spec.setSummary(nullSafe(d.summary()));
        spec.setCover(nullSafe(d.cover()));
        if (StringUtils.hasText(d.author())) {
            spec.setAuthor(d.author());
        }
        if (d.creationTime() != null) {
            spec.setCreationTime(d.creationTime());
        }
        if (PHASE_PUBLISHED.equals(d.phase()) && d.publishTime() != null) {
            spec.setPublishTime(d.publishTime());
        }
        var content = contentBySlug.get(d.slug());
        spec.setContent(content == null ? ""
            : new String(content, StandardCharsets.UTF_8));
        doc.setSpec(spec);
        return create(kbName, doc, StringUtils.hasText(d.author()) ? d.author() : creator)
            .map(created -> new ImportedDocument(d.slug(), created.getMetadata().getName()));
    }

    private List<ImportInfo> parseZip(byte[] data) {
        var byDir = new LinkedHashMap<String, LinkedHashMap<String, byte[]>>();
        try (var zin = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                var name = entry.getName();
                var idx = name.indexOf('/');
                var dir = idx < 0 ? "root" : name.substring(0, idx);
                var rel = idx < 0 ? name : name.substring(idx + 1);
                if (rel.isEmpty()) {
                    continue;
                }
                var baos = new ByteArrayOutputStream();
                zin.transferTo(baos);
                byDir.computeIfAbsent(dir, k -> new LinkedHashMap<>()).put(rel,
                    baos.toByteArray());
                zin.closeEntry();
            }
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "zip 解析失败: " + ex.getMessage());
        }

        var infos = new ArrayList<ImportInfo>();
        for (var dir : byDir.keySet()) {
            var files = byDir.get(dir);
            var cfgBytes = files.get("config.json");
            if (cfgBytes == null) {
                continue;
            }
            JsonNode cfg;
            try {
                cfg = JsonMapper.builder().build().readTree(cfgBytes);
            } catch (Exception ex) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "config.json 解析失败");
            }
            var kbNode = cfg.path("knowledgeBase");
            var docsNode = cfg.path("documents");
            var tags = new ArrayList<String>();
            if (kbNode.path("tags").isArray()) {
                kbNode.path("tags").forEach(t -> tags.add(t.asString()));
            }
            var documents = new ArrayList<ImportedDocInfo>();
            var contentBySlug = new HashMap<String, byte[]>();
            for (var node : docsNode) {
                var slug = node.path("slug").asString("");
                if (slug.isEmpty()) {
                    continue;
                }
                var file = node.path("file").asString("");
                var content = !file.isEmpty() ? files.get(file) : null;
                if (content != null) {
                    contentBySlug.put(slug, content);
                }
                documents.add(new ImportedDocInfo(
                    slug,
                    node.path("title").asString(slug),
                    node.path("parent").asString(""),
                    node.path("phase").asString((String) null),
                    node.path("priority").isNull() ? null : node.path("priority").asInt(),
                    node.path("summary").asString(""),
                    node.path("cover").asString(""),
                    node.path("author").asString(""),
                    parseInstantOrNull(node.path("creationTime").asString((String) null)),
                    parseInstantOrNull(node.path("publishTime").asString((String) null))
                ));
            }
            infos.add(new ImportInfo(dir,
                new ImportedKbConfig(
                    kbNode.path("displayName").asString(dir),
                    kbNode.path("slug").asString(""),
                    kbNode.path("description").asString(""),
                    kbNode.path("cover").asString(""),
                    kbNode.path("logo").asString(""),
                    tags,
                    kbNode.path("publicVisible").asBoolean(false)
                ),
                documents,
                contentBySlug));
        }
        return infos;
    }

    private Instant parseInstantOrNull(String s) {
        if (!StringUtils.hasText(s)) {
            return null;
        }
        try {
            return Instant.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private String sanitizeDirName(String s) {
        var r = nullSafe(s).replaceAll("[\\\\/:*?\"<>|\\s]+", "_").replaceAll("_+", "_");
        return r.isBlank() ? "knowledge-base" : r;
    }

    private record ExportEntry(String path, byte[] data) {
    }

    private record ImportInfo(String dir, ImportedKbConfig kbConfig,
        List<ImportedDocInfo> documents, Map<String, byte[]> contentBySlug) {
    }

    private record ImportedKbConfig(String displayName, String slug, String description,
        String cover, String logo, List<String> tags, boolean publicVisible) {
    }

    private record ImportedDocInfo(String slug, String title, String parent, String phase,
        Integer priority, String summary, String cover, String author, Instant creationTime,
        Instant publishTime) {
    }

    private record ImportedDocument(String slug, String name) {
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

    /**
     * 导入预览项：单个知识库的名称、文档数与是否已存在。
     */
    public record ImportPreviewItem(String displayName, int docCount, boolean exists) {
    }

    /**
     * 导入结果项：单个知识库的导入结果。
     */
    public record ImportResultItem(String displayName, boolean imported, String message) {
    }
}
