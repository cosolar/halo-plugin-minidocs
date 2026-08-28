package run.halo.plugin.minidocs.service;

import java.time.Instant;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.extension.AbstractExtension;
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
     * 分页查询知识库，关键字匹配名称或 metadata.name，支持按可见性筛选与后端排序。
     * <p>排序与分页在后端内存中进行（全量获取后按 sortBy 排序再分页），保证全局排序正确，
     * 不依赖 Halo 对 spec 字段的索引排序。
     */
    public Mono<ListResult<KnowledgeBase>> list(String keyword, Boolean publicVisible,
        String sortBy, int page, int size) {
        var options = ListOptions.builder();
        if (StringUtils.hasText(keyword)) {
            options.fieldQuery(or(
                contains("spec.displayName", keyword),
                contains("metadata.name", keyword)));
        }
        if (publicVisible != null) {
            options.andQuery(equal("spec.publicVisible", publicVisible));
        }
        var comparator = resolveComparator(sortBy);
        return client.listAll(KnowledgeBase.class, options.build(), resolveSort(sortBy))
            .collectList()
            .map(all -> {
                all.sort(comparator);
                var items = ListResult.subList(all, page, size);
                return new ListResult<>(page, size, all.size(), items);
            });
    }

    /**
     * Halo 存储层排序（与 resolveComparator 一致，双保险）。
     */
    private Sort resolveSort(String sortBy) {
        return switch (sortBy == null ? "updateTime" : sortBy) {
            case "name" -> Sort.by(Order.asc("spec.displayName"), Order.asc("metadata.name"));
            case "priority" -> Sort.by(Order.asc("spec.priority"), Order.asc("metadata.name"));
            case "createTime" -> Sort.by(Order.desc("spec.creationTime"),
                Order.asc("metadata.name"));
            case "docCount" -> Sort.by(Order.desc("status.docCount"), Order.asc("metadata.name"));
            default -> Sort.by(Order.desc("spec.updateTime"), Order.asc("metadata.name"));
        };
    }

    private Comparator<KnowledgeBase> resolveComparator(String sortBy) {
        Comparator<KnowledgeBase> primary;
        switch (sortBy == null ? "updateTime" : sortBy) {
            case "name" -> primary = Comparator.comparing(
                (KnowledgeBase kb) -> kb.getSpec() == null || kb.getSpec().getDisplayName() == null
                    ? "" : kb.getSpec().getDisplayName(), String.CASE_INSENSITIVE_ORDER);
            case "priority" -> primary = Comparator.comparingLong(kb ->
                kb.getSpec() == null || kb.getSpec().getPriority() == null
                    ? Long.MAX_VALUE : kb.getSpec().getPriority());
            case "createTime" -> primary = Comparator.comparingLong(
                (KnowledgeBase kb) -> timeMillis(kb.getSpec() == null ? null
                    : kb.getSpec().getCreationTime()))
                .reversed();
            case "docCount" -> primary = Comparator.comparingLong(
                (KnowledgeBase kb) -> kb.getStatus() == null
                    || kb.getStatus().getDocCount() == null ? 0L
                    : kb.getStatus().getDocCount()).reversed();
            default -> primary = Comparator.comparingLong(
                (KnowledgeBase kb) -> timeMillis(kb.getSpec() == null ? null
                    : kb.getSpec().getUpdateTime()))
                .reversed();
        }
        return primary.thenComparing(kb -> kb.getMetadata().getName());
    }

    private long timeMillis(Instant t) {
        return t == null ? Long.MIN_VALUE : t.toEpochMilli();
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
     * 按知识库链接别名（spec.slug）或 metadata.name 解析知识库，不存在时返回 404。
     * <p>前台路由（/docs/view/{kbSlug} 等）使用此方法，使 URL 既支持用户自定义 slug，
     * 也兼容历史以 metadata.name（UUID）直接访问的链接。
     */
    public Mono<KnowledgeBase> getBySlugOrName(String kbSlug) {
        return findBySlug(kbSlug)
            .switchIfEmpty(client.fetch(KnowledgeBase.class, kbSlug))
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                "知识库不存在: " + kbSlug)));
    }

    private Mono<KnowledgeBase> findBySlug(String slug) {
        if (!StringUtils.hasText(slug)) {
            return Mono.empty();
        }
        var options = ListOptions.builder().fieldQuery(equal("spec.slug", slug)).build();
        return client.listBy(KnowledgeBase.class, options, PageRequestImpl.of(1, 1))
            .flatMap(result -> result.getItems().isEmpty()
                ? Mono.empty()
                : Mono.justOrEmpty(result.getItems().getFirst()));
    }

    /**
     * 判断指定 slug 是否已被其它知识库占用（用于创建/更新时的唯一性校验）。
     *
     * @param slug        待校验的 slug
     * @param excludeName 当前知识库的 metadata.name（更新自身时排除），可为 null
     */
    public Mono<Boolean> slugExists(String slug, String excludeName) {
        if (!StringUtils.hasText(slug)) {
            return Mono.just(false);
        }
        var options = ListOptions.builder().fieldQuery(equal("spec.slug", slug)).build();
        return client.listBy(KnowledgeBase.class, options, PageRequestImpl.of(1, 100))
            .map(result -> result.getItems().stream()
                .anyMatch(kb -> excludeName == null
                    || !excludeName.equals(kb.getMetadata().getName())));
    }

    /**
     * 生成知识库默认 slug：KB + yyyyMMddHHmmssSSS + 6 位随机数字，
     * 例如 KB2026082815301234567890。
     */
    public static String generateSlug() {
        var time = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
            .format(java.time.LocalDateTime.now());
        var rand = String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
        return "KB" + time + rand;
    }

    /**
     * 刷新知识库更新时间：在知识库下任一文档发生增删改等内容变动后调用，
     * 使“最近更新”排序能准确反映知识库内容的实际变更时间。
     */
    public Mono<Void> touch(String name) {
        return Mono.defer(() -> get(name).flatMap(kb -> {
                kb.getSpec().setUpdateTime(Instant.now());
                return client.update(kb).then();
            }))
            // 批量导入等多文档连续变更时，与 Reconciler 异步刷新知识库统计并发更新同一对象，
            // 会产生 409 版本冲突（Halo 以 conflict problem 形式抛出）。touch 只是刷新
            // “最近更新”时间，属非关键写操作：稍作重试后任何失败都静默降级，不阻断主流程。
            .retryWhen(Retry.max(2))
            .onErrorResume(e -> Mono.empty());
    }

    /**
     * 按显示名称查找首个知识库（用于导入时判断是否已存在同名知识库）。
     */
    public Mono<KnowledgeBase> findExistingByName(String displayName) {
        var options = ListOptions.builder()
            .fieldQuery(equal("spec.displayName", displayName))
            .build();
        return client.listBy(KnowledgeBase.class, options,
                PageRequestImpl.of(1, 1))
            .flatMap(result -> result.getItems().isEmpty()
                ? Mono.empty()
                : Mono.justOrEmpty(result.getItems().get(0)));
    }

    /**
     * 创建知识库（校验名称必填，publicVisible 默认 false，自动填充创建人与更新时间）。
     */
    public Mono<KnowledgeBase> create(KnowledgeBase kb) {
        return create(kb, null);
    }

    /**
     * 创建知识库，可指定创建人（通常为当前登录用户）。
     */
    public Mono<KnowledgeBase> create(KnowledgeBase kb, String creatorName) {
        if (kb.getSpec() == null || !StringUtils.hasText(kb.getSpec().getDisplayName())) {
            return Mono.error(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识库名称不能为空"));
        }
        if (kb.getSpec().getPublicVisible() == null) {
            kb.getSpec().setPublicVisible(false);
        }
        // 创建知识库时默认写入创建时间与更新时间
        var now = Instant.now();
        if (kb.getSpec().getCreationTime() == null) {
            kb.getSpec().setCreationTime(now);
        }
        kb.getSpec().setUpdateTime(now);
        if (!StringUtils.hasText(kb.getSpec().getCreatorName())) {
            kb.getSpec().setCreatorName(StringUtils.hasText(creatorName) ? creatorName : "unknown");
        }
        // slug：用户未填则自动生成；随后校验全局唯一
        if (!StringUtils.hasText(kb.getSpec().getSlug())) {
            kb.getSpec().setSlug(generateSlug());
        }
        return slugExists(kb.getSpec().getSlug(), null)
            .flatMap(exists -> {
                if (exists) {
                    return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "知识库链接别名已存在: " + kb.getSpec().getSlug()));
                }
                return client.create(kb);
            });
    }

    /**
     * 更新知识库（整体替换 spec），并刷新更新时间。
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
            // 整体替换 spec，保留原始创建人
            if (!StringUtils.hasText(update.getSpec().getCreatorName())
                && StringUtils.hasText(kb.getSpec().getCreatorName())) {
                update.getSpec().setCreatorName(kb.getSpec().getCreatorName());
            }
            update.getSpec().setUpdateTime(Instant.now());
            // slug：用户清空则重新生成；校验唯一（排除自身）
            if (!StringUtils.hasText(update.getSpec().getSlug())) {
                update.getSpec().setSlug(generateSlug());
            }
            return slugExists(update.getSpec().getSlug(), name)
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "知识库链接别名已存在: " + update.getSpec().getSlug()));
                    }
                    kb.setSpec(update.getSpec());
                    return client.update(kb);
                });
        });
    }

    /**
     * 删除知识库并级联删除其下所有文档。
     *
     * <p>Halo 扩展删除为异步最终一致：删除请求返回后索引尚未同步，立即查询可能仍读到
     * 旧数据。因此删除每个文档/知识库后都轮询其从索引移除（awaitIndexRemoved），
     * 接口返回时删除已同步生效；超时兜底不阻塞主流程。
     */
    public Mono<Void> delete(String name) {
        return get(name)
            .flatMap(kb -> client.listAll(KnowledgeBaseDoc.class,
                    ListOptions.builder()
                        .fieldQuery(equal("spec.knowledgeBaseName", name))
                        .build(),
                    Sort.unsorted())
                .concatMap(doc -> client.delete(doc)
                    .then(awaitIndexRemoved(KnowledgeBaseDoc.class,
                        doc.getMetadata().getName())))
                .then(deleteKbRetryable(name)))
            .then(awaitIndexRemoved(KnowledgeBase.class, name));
    }

    /**
     * 删除知识库本体。级联删除文档会触发 Reconciler 异步刷新知识库统计（docCount 等），
     * 从而并发递增知识库的 metadata.version；若用删除前抓取的旧实例 delete 会因版本不匹配
     * 抛 409 conflict。因此删除前总是重新抓取最新版本，并对瞬时版本冲突重试。
     */
    private Mono<Void> deleteKbRetryable(String name) {
        return Mono.defer(() -> client.fetch(KnowledgeBase.class, name)
                .flatMap(kb -> client.delete(kb).then()))
            .retryWhen(Retry.max(3));
    }

    /**
     * 轮询等待扩展从索引移除。删除自身是瞬时的，索引同步经异步 store-watcher 完成：
     * 删除返回后立即查询仍可能读到旧数据，此方法轮询直到资源不再出现（带超时兜底）。
     */
    private <E extends AbstractExtension> Mono<Void> awaitIndexRemoved(Class<E> type,
        String name) {
        return Mono.defer(() -> client.fetch(type, name)
                .flatMap(res -> Mono.delay(Duration.ofMillis(120))
                    .then(awaitIndexRemoved(type, name))))
            .timeout(Duration.ofSeconds(5))
            .onErrorResume(e -> Mono.empty());
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
                .map(doc -> Optional.ofNullable(doc.getSpec().getPublishTime()))
                .defaultIfEmpty(Optional.empty())
                .zipWith(client.fetch(KnowledgeBase.class, kbName))
                .flatMap(tuple -> {
                    var kb = tuple.getT2();
                    if (kb == null) {
                        return Mono.empty();
                    }
                    var status = kb.getStatus() == null
                        ? new KnowledgeBase.Status() : kb.getStatus();
                    status.setDocCount(Math.toIntExact(count));
                    status.setLastPublishTime(tuple.getT1().orElse(null));
                    kb.setStatus(status);
                    // 删除等变更顺带刷新“最近更新”，避免与 refreshStats 重复更新知识库
                    kb.getSpec().setUpdateTime(Instant.now());
                    return client.update(kb).then();
                }));
    }
}
