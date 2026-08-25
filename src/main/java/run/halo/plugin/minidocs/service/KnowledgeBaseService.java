package run.halo.plugin.minidocs.service;

import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.plugin.minidocs.endpoint.KnowledgeBaseStatsDto;
import run.halo.plugin.minidocs.extension.KnowledgeBase;
import run.halo.plugin.minidocs.extension.KnowledgeBaseDoc;

import static org.springframework.data.domain.Sort.Order;
import static run.halo.app.extension.index.query.Queries.and;
import static run.halo.app.extension.index.query.Queries.contains;
import static run.halo.app.extension.index.query.Queries.equal;
import static run.halo.app.extension.index.query.Queries.or;

/**
 * 知识库业务服务。
 *
 * @author Cosolar
 */
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService {

    /**
     * 文档上用于标记所属知识库的标签键。
     */
    public static final String KNOWLEDGE_BASE_LABEL = "minidocs.halo.run/knowledge-base";

    private final ReactiveExtensionClient client;

    /**
     * 分页查询知识库，关键字匹配名称或 metadata.name，支持按可见性筛选。
     */
    public Mono<ListResult<KnowledgeBase>> list(String keyword, Boolean publicVisible, int page,
        int size) {
        var options = ListOptions.builder();
        if (StringUtils.hasText(keyword)) {
            options.fieldQuery(or(
                contains("spec.displayName", keyword),
                contains("metadata.name", keyword)));
        }
        if (publicVisible != null) {
            options.andQuery(equal("spec.publicVisible", publicVisible));
        }
        var sort = Sort.by(Order.asc("spec.priority"), Order.asc("metadata.name"));
        return client.listBy(KnowledgeBase.class, options.build(),
            PageRequestImpl.of(page, size, sort));
    }

    /**
     * 分页查询公开知识库（仅 publicVisible=true），供公开接口使用。
     */
    public Mono<ListResult<KnowledgeBase>> listPublic(String keyword, int page, int size) {
        var builder = ListOptions.builder();
        builder.fieldQuery(equal("spec.publicVisible", true));
        if (StringUtils.hasText(keyword)) {
            builder.andQuery(or(
                contains("spec.displayName", keyword),
                contains("metadata.name", keyword)));
        }
        var sort = Sort.by(Order.asc("spec.priority"), Order.asc("metadata.name"));
        return client.listBy(KnowledgeBase.class, builder.build(),
            PageRequestImpl.of(page, size, sort));
    }

    /**
     * 按名称获取知识库，不存在时返回 404。
     */
    public Mono<KnowledgeBase> get(String name) {
        return client.fetch(KnowledgeBase.class, name)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                "知识库不存在: " + name)));
    }

    /**
     * 创建知识库（校验名称必填，publicVisible 默认 false）。
     */
    public Mono<KnowledgeBase> create(KnowledgeBase kb) {
        if (kb.getSpec() == null || !StringUtils.hasText(kb.getSpec().getDisplayName())) {
            return Mono.error(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识库名称不能为空"));
        }
        if (kb.getSpec().getPublicVisible() == null) {
            kb.getSpec().setPublicVisible(false);
        }
        return client.create(kb);
    }

    /**
     * 更新知识库（整体替换 spec）。
     */
    public Mono<KnowledgeBase> update(String name, KnowledgeBase update) {
        return get(name).flatMap(kb -> {
            if (update.getSpec() == null) {
                return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识库内容不能为空"));
            }
            if (!StringUtils.hasText(update.getSpec().getDisplayName())) {
                return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识库名称不能为空"));
            }
            kb.setSpec(update.getSpec());
            return client.update(kb);
        });
    }

    /**
     * 删除知识库并级联删除其下所有文档。
     */
    public Mono<Void> delete(String name) {
        return get(name)
            .flatMap(kb -> client.listAll(KnowledgeBaseDoc.class,
                    ListOptions.builder()
                        .fieldQuery(equal("spec.knowledgeBaseName", name))
                        .build(),
                    Sort.unsorted())
                .flatMap(doc -> client.delete(doc))
                .then(client.delete(kb).then()));
    }

    /**
     * 聚合统计知识库与文档数量，并计算月度环比。
     */
    public Mono<KnowledgeBaseStatsDto> stats() {
        var now = Instant.now();
        var thisMonthStart = now.atOffset(java.time.ZoneOffset.UTC)
            .withDayOfMonth(1)
            .withHour(0)
            .withMinute(0)
            .withSecond(0)
            .withNano(0)
            .toInstant();
        var lastMonthStart = thisMonthStart.atOffset(java.time.ZoneOffset.UTC)
            .minus(1, java.time.temporal.ChronoUnit.MONTHS)
            .toInstant();

        var allKbs = client.listAll(KnowledgeBase.class, ListOptions.builder().build(),
            Sort.unsorted()).collectList();
        var allDocs = client.listAll(KnowledgeBaseDoc.class, ListOptions.builder().build(),
            Sort.unsorted()).collectList();

        return Mono.zip(allKbs, allDocs).map(tuple -> {
            var kbs = tuple.getT1();
            var docs = tuple.getT2();

            int total = kbs.size();
            int publicCount = (int) kbs.stream()
                .filter(kb -> Boolean.TRUE.equals(kb.getSpec().getPublicVisible()))
                .count();
            int privateCount = total - publicCount;
            int docCount = docs.size();

            int kbGrowth = (int) kbs.stream()
                .filter(kb -> isAfter(kb.getMetadata().getCreationTimestamp(), lastMonthStart)
                    && isBefore(kb.getMetadata().getCreationTimestamp(), thisMonthStart))
                .count();
            // 环比 = 本月 - 上月，保持与设计图一致（较上月 +N）
            int thisMonthKbs = (int) kbs.stream()
                .filter(kb -> isAfter(kb.getMetadata().getCreationTimestamp(), thisMonthStart))
                .count();
            kbGrowth = thisMonthKbs - kbGrowth;

            int docGrowth = (int) docs.stream()
                .filter(doc -> isAfter(doc.getMetadata().getCreationTimestamp(), lastMonthStart)
                    && isBefore(doc.getMetadata().getCreationTimestamp(), thisMonthStart))
                .count();
            int thisMonthDocs = (int) docs.stream()
                .filter(doc -> isAfter(doc.getMetadata().getCreationTimestamp(), thisMonthStart))
                .count();
            docGrowth = thisMonthDocs - docGrowth;

            String publicRatio = total == 0 ? "0%" : (publicCount * 100 / total) + "%";

            return KnowledgeBaseStatsDto.builder()
                .total(total)
                .publicCount(publicCount)
                .privateCount(privateCount)
                .docCount(docCount)
                .kbGrowth(kbGrowth)
                .docGrowth(docGrowth)
                .publicRatio(publicRatio)
                .build();
        });
    }

    private boolean isAfter(Instant time, Instant start) {
        return time != null && !time.isBefore(start);
    }

    private boolean isBefore(Instant time, Instant end) {
        return time != null && time.isBefore(end);
    }

    /**
     * 刷新知识库统计状态（docCount、lastPublishTime）。
     * <p>文档删除路径需要同步刷新（Reconciler 无法感知已删除资源的父级）；其余变更由
     * {@code KnowledgeBaseStatsReconciler} 异步维护。
     */
    public Mono<Void> refreshStats(String kbName) {
        var allDocs = ListOptions.builder()
            .fieldQuery(equal("spec.knowledgeBaseName", kbName))
            .build();
        var publishedDocs = ListOptions.builder()
            .fieldQuery(and(
                equal("spec.knowledgeBaseName", kbName),
                equal("spec.phase", "published")))
            .build();
        return client.countBy(KnowledgeBaseDoc.class, allDocs)
            .flatMap(count -> client.listAll(KnowledgeBaseDoc.class, publishedDocs,
                    Sort.by(Order.desc("spec.publishTime")))
                .next()
                .map(doc -> doc.getSpec().getPublishTime())
                .defaultIfEmpty(null)
                .zipWith(client.fetch(KnowledgeBase.class, kbName))
                .flatMap(tuple -> {
                    var kb = tuple.getT2();
                    if (kb == null) {
                        return Mono.empty();
                    }
                    var status = kb.getStatus() == null
                        ? new KnowledgeBase.Status() : kb.getStatus();
                    status.setDocCount(Math.toIntExact(count));
                    status.setLastPublishTime(tuple.getT1());
                    kb.setStatus(status);
                    return client.update(kb).then();
                }));
    }
}
