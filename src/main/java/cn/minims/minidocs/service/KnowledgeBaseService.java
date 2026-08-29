package cn.minims.minidocs.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.extension.AbstractExtension;
import run.halo.app.extension.ListOptions;
import run.halo.app.extension.ListResult;
import run.halo.app.extension.PageRequestImpl;
import run.halo.app.extension.ReactiveExtensionClient;
import cn.minims.minidocs.endpoint.KnowledgeBaseStatsDto;
import cn.minims.minidocs.extension.KnowledgeBase;
import cn.minims.minidocs.extension.KnowledgeBaseDoc;

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

    /**
     * 本插件知识库管理权限前缀（与 Console 前端路由 meta.permissions 一致）。
     * 仅命中该前缀的权限点（含管理角色与权限明细）才视为“知识库管理员”，
     * 不能被误当作管理豁免来源。
     */
    private static final String MANAGE_PERMISSION_PREFIX =
        "plugin:halo-plugin-minidocs:knowledgebase";

    /**
     * Halo 超级管理员角色 authority 前缀（小写比较）。
     * 超级管理员（admin 用户绑定 {@code super-role} 角色）的 authorities 为
     * {@code ROLE_super-role}，见 Halo 认证文档。该前缀在 Halo 中仅用于超级管理员，
     * 不会误伤其它用户。
     */
    private static final String SUPER_ADMIN_ROLE_PREFIX = "role_super-";

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
     * 分页返回当前用户可访问的知识库（按 sortBy 排序）：
     * 公开 + 当前用户是创建者/成员的私有库 + 具备管理权限时可见的全部。
     * <p>先做资源级访问过滤，再排序、分页，保证分页 {@code total} 为“过滤后的全量”，
     * 避免普通用户 / 管理员分页数据失真、分页组件因 total 恒等于当前页条数而不显示。
     */
    public Mono<ListResult<KnowledgeBase>> listAccessible(String keyword, Boolean publicVisible,
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
        return currentAccess().flatMap(access ->
            client.listAll(KnowledgeBase.class, options.build(), resolveSort(sortBy))
                .filter(kb -> canAccess(kb, access.username(), access.manage()))
                .collectList()
                .map(all -> {
                    all.sort(comparator);
                    var items = ListResult.subList(all, page, size);
                    // total 取过滤后的全量条数，保证分页组件按真实数据量显示
                    return new ListResult<>(page, size, all.size(), items);
                }));
    }

    /**
     * 分页返回当前用户可访问的知识库（默认按最近更新倒序）。
     */
    public Mono<ListResult<KnowledgeBase>> listAccessible(int page, int size) {
        return listAccessible(null, null, "updateTime", page, size);
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
     * 访问量 +1（每次打开阅读页调用）。
     */
    public Mono<KnowledgeBase> incrementAccess(String kbName) {
        return client.fetch(KnowledgeBase.class, kbName)
            .flatMap(kb -> {
                var spec = kb.getSpec();
                spec.setAccessCount(
                    (spec.getAccessCount() == null ? 0L : spec.getAccessCount()) + 1L);
                return client.update(kb)
                    .retryWhen(Retry.backoff(3, Duration.ofMillis(120)).maxBackoff(
                        Duration.ofSeconds(1)));
            });
    }

    /**
     * 一次性点赞（幂等）：每个登录用户对同一知识库仅能点赞一次，点赞后不可取消。
     * <p>匿名用户（username 为空）不写入 likedUsers（服务端无法区分匿名者），
     * 其去重交给前端 localStorage 缓存记录；服务端仅做幂等保护防止重复请求重复 +1。
     *
     * @return 结果 Map，包含 {@code likeCount}（最新点赞数）与 {@code liked}（是否已点过赞）。
     */
    public Mono<Map<String, Object>> likeOnce(String kbName, String username) {
        return client.fetch(KnowledgeBase.class, kbName)
            .flatMap(kb -> {
                var spec = kb.getSpec();
                var likedUsers = spec.getLikedUsers() != null
                    ? new ArrayList<>(spec.getLikedUsers()) : new ArrayList<String>();
                boolean already = StringUtils.hasText(username) && likedUsers.contains(username);
                if (already) {
                    // 登录用户已点过赞：幂等返回当前状态，不重复 +1、不取消
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("likeCount", max0(spec.getLikeCount()));
                    result.put("liked", true);
                    result.put("newLike", false);
                    return Mono.just(result);
                }
                if (StringUtils.hasText(username)) {
                    likedUsers.add(username);
                    spec.setLikedUsers(likedUsers);
                }
                spec.setLikeCount((spec.getLikeCount() == null ? 0L : spec.getLikeCount()) + 1);
                return client.update(kb)
                    .retryWhen(Retry.backoff(3, Duration.ofMillis(120)).maxBackoff(
                        Duration.ofSeconds(1)))
                    .map(updated -> {
                        var specAfter = updated.getSpec();
                        Map<String, Object> result = new LinkedHashMap<>();
                        result.put("likeCount", specAfter.getLikeCount() == null ? 0L
                            : specAfter.getLikeCount());
                        result.put("liked", true);
                        result.put("newLike", true);
                        return result;
                    });
            });
    }

    private static long max0(Long value) {
        return value == null || value < 0 ? 0L : value;
    }

    /**
     * 当前登录用户名；未登录/匿名时返回空字符串。
     * <p>在不同调用上下文（如主题 Finder 渲染）中 SecurityContext 可能不可用，
     * 此时一律按匿名处理。
     */
    public Mono<String> currentUsername() {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(auth -> !(auth instanceof AnonymousAuthenticationToken))
            .map(auth -> auth.getName() == null ? "" : auth.getName().trim())
            .filter(StringUtils::hasText)
            .defaultIfEmpty("");
    }

    /**
     * 当前用户是否具备知识库管理权限（用于私有知识库的“管理豁免”）。
     * <p>仅以下两种情况判定为管理权限：
     * <ol>
     *   <li>用户拥有 {@code role_super-admin}（超级管理员）角色：对所有知识库全权限豁免；</li>
     *   <li>用户拥有本插件前缀 {@code plugin:halo-plugin-minidocs:knowledgebase:*} 权限：
     *       即为知识库的管理者，具备管理豁免；</li>
     * </ol>
     * 避免对仅拥有其它模块（附件、文章、评论等） xxx-manage 权限的普通用户开放管理豁免，
     * 杜绝“具备某权限的普通用户读取所有私有知识库”的越权漏洞。
     */
    public Mono<Boolean> hasManagePermission() {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .flatMapMany(auth -> Flux.fromIterable(auth.getAuthorities()))
            .map(granted -> granted.getAuthority() == null ? "" : granted.getAuthority())
            .any(authority -> {
                var lower = authority.toLowerCase();
                // 超级管理员必须允许：Halo 超级管理员角色 authority 为 ROLE_super-role
                if (lower.startsWith(SUPER_ADMIN_ROLE_PREFIX)) {
                    return true;
                }
                // 仅本插件前缀权限点才判定为知识库管理权限，不能宽泛地任何以 -manage 结尾就豁免
                return lower.startsWith(MANAGE_PERMISSION_PREFIX)
                    || lower.contains(MANAGE_PERMISSION_PREFIX);
            })
            .defaultIfEmpty(false)
            .onErrorResume(e -> Mono.just(false));
    }

    /**
     * 纯判定：用户名 {@code username} 是否可访问知识库 {@code kb}（资源级可见性）。
     * <p>规则：公开知识库任何人都可读；私有知识库仅创建者、成员或具备管理权限者可读。
     * 不涉及“匿名阅读开关”（公开知识库的匿名开关由调用方结合设置单独控制）。
     */
    public static boolean canAccess(KnowledgeBase kb, String username, boolean manage) {
        if (Boolean.TRUE.equals(kb.getSpec().getPublicVisible())) {
            return true;
        }
        if (manage) {
            return true;
        }
        if (!StringUtils.hasText(username)) {
            return false;
        }
        var spec = kb.getSpec();
        if (username.equals(spec.getCreatorName())) {
            return true;
        }
        return spec.getMembers() != null && spec.getMembers().contains(username);
    }

    /**
     * 解析当前登录用户的权限上下文，返回 [username, manage] 二元组。
     */
    public Mono<UserAccess> currentAccess() {
        return Mono.zip(currentUsername(), hasManagePermission())
            .map(tuple -> new UserAccess(tuple.getT1(), tuple.getT2()));
    }

    /**
     * 当前用户权限上下文（登录用户名 + 是否管理）。
     */
    public record UserAccess(String username, boolean manage) {
    }

    /**
     * 校验当前用户对指定知识库的读取/管理权限（私有知识库须为创建者/成员/管理）。
     * 无权限时以 403 拒绝，防止越权读取不属于自己的私有知识库。
     */
    public Mono<KnowledgeBase> requireAccess(KnowledgeBase kb) {
        return currentAccess()
            .map(access -> canAccess(kb, access.username(), access.manage()))
            .flatMap(ok -> ok ? Mono.just(kb)
                : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "无权访问该私有知识库")));
    }

    /**
     * 按名称获取知识库并校验当前用户可访问。
     */
    public Mono<KnowledgeBase> requireAccessByName(String name) {
        return get(name).flatMap(this::requireAccess);
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

    /** 分享 token 随机字符表（URL 安全）。 */
    private static final char[] SHARE_TOKEN_ALPHABET =
        "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final SecureRandom SHARE_RANDOM = new SecureRandom();

    /**
     * 生成外链分享 token（12 位 URL 安全随机串），不可猜测、不与 slug 冲突。
     */
    public static String generateShareToken() {
        StringBuilder sb = new StringBuilder(12);
        for (int i = 0; i < 12; i++) {
            sb.append(SHARE_TOKEN_ALPHABET[SHARE_RANDOM.nextInt(SHARE_TOKEN_ALPHABET.length)]);
        }
        return sb.toString();
    }

    /**
     * 按分享 token 解析知识库，不存在时返回 404。
     */
    public Mono<KnowledgeBase> findByShareToken(String shareToken) {
        if (!StringUtils.hasText(shareToken)) {
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                "分享链接不存在"));
        }
        var options = ListOptions.builder()
            .fieldQuery(equal("spec.shareToken", shareToken))
            .build();
        return client.listBy(KnowledgeBase.class, options, PageRequestImpl.of(1, 1))
            .flatMap(result -> result.getItems().isEmpty()
                ? Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "分享链接不存在"))
                : Mono.just(result.getItems().getFirst()));
    }

    /**
     * 分享访问状态：是否开启、是否过期、是否需要密码。
     */
    public record ShareState(boolean enabled, boolean expired, boolean passwordRequired) {
    }

    /**
     * 计算知识库分享状态：关闭返回 disabled；开启但已过期返回 expired；否则返回是否需要密码。
     */
    public static ShareState shareState(KnowledgeBase kb) {
        var spec = kb.getSpec();
        if (!Boolean.TRUE.equals(spec.getShareEnabled())) {
            return new ShareState(false, false, false);
        }
        boolean expired = spec.getShareExpiresAt() != null
            && !Instant.now().isBefore(spec.getShareExpiresAt());
        boolean needPwd = StringUtils.hasText(spec.getSharePassword());
        return new ShareState(true, expired, needPwd);
    }

    /**
     * 校验分享访问 cookie 值是否与知识库当前分享密码一致。
     * <p>cookie 值 = URL-safe Base64(密码)；空密码视为无需密码，直接放行。
     */
    public static boolean shareCookieMatches(KnowledgeBase kb, String cookieValue) {
        var pwd = kb.getSpec().getSharePassword();
        if (!StringUtils.hasText(pwd)) {
            return true;
        }
        if (!StringUtils.hasText(cookieValue)) {
            return false;
        }
        try {
            var decoded = new String(Base64.getUrlDecoder().decode(cookieValue),
                StandardCharsets.UTF_8);
            return constantTimeEquals(decoded, pwd);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * 校验用户输入的分享密码是否与当前密码一致，用于密码门提交。
     */
    public static boolean verifySharePassword(KnowledgeBase kb, String password) {
        var pwd = kb.getSpec().getSharePassword();
        if (!StringUtils.hasText(pwd)) {
            return true;
        }
        if (!StringUtils.hasText(password)) {
            return false;
        }
        return constantTimeEquals(password, pwd);
    }

    /**
     * 分享访问 cookie 名称（按 token 区分）。
     */
    public static String shareCookieName(String shareToken) {
        return "mdshare_" + shareToken;
    }

    /**
     * 把分享密码编码为适合放进 cookie 的值（URL-safe Base64）。
     */
    public static String encodeShareCookie(String password) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(password.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
            a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 同步分享字段：开启且 token 为空时自动生成；关闭时仅置 enabled=false，保留其余字段
     * 以便日后重新开启，避免重复生成外链。
     */
    private void syncShare(KnowledgeBase.Spec spec) {
        if (spec == null) {
            return;
        }
        if (Boolean.TRUE.equals(spec.getShareEnabled())) {
            if (!StringUtils.hasText(spec.getShareToken())) {
                spec.setShareToken(generateShareToken());
            }
        } else {
            spec.setShareEnabled(false);
        }
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
        // 公开知识库无需成员列表，清空避免残留私有成员数据
        if (Boolean.TRUE.equals(kb.getSpec().getPublicVisible())) {
            kb.getSpec().setMembers(null);
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
        syncShare(kb.getSpec());
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
     * 更新知识库（字段级合并，保留系统字段与统计数据），并刷新更新时间。
     * <p>只覆盖用户可编辑字段，保留访问量/点赞量等统计与创建时间等系统字段，
     * 避免前端表单未提交这些字段时被整体替换清零。
     */
    public Mono<KnowledgeBase> update(String name, KnowledgeBase update) {
        return get(name).flatMap(kb -> {
            if (update.getSpec() == null) {
                return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识库内容不能为空"));
            }
            var old = kb.getSpec();
            var patch = update.getSpec();
            if (!StringUtils.hasText(patch.getDisplayName())) {
                return Mono.error(
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "知识库名称不能为空"));
            }
            old.setDisplayName(patch.getDisplayName());
            old.setSlug(patch.getSlug());
            old.setDescription(patch.getDescription());
            old.setLogo(patch.getLogo());
            old.setCover(patch.getCover());
            old.setPriority(patch.getPriority());
            old.setTags(patch.getTags());
            old.setMembers(patch.getMembers());
            old.setPublicVisible(patch.getPublicVisible());
            // 外链分享设置：显式提交开关时才更新（开启以提交值为准，token 缺省沿用旧值）；
            // 普通编辑（未提交分享字段）完全不动分享设置，避免静默关闭已开启的外链。
            // 关闭时仅记录开关，保留 token/密码/有效期，重新开启后沿用原外链。
            Boolean shareEnabled = patch.getShareEnabled();
            if (Boolean.TRUE.equals(shareEnabled)) {
                old.setShareEnabled(true);
                if (StringUtils.hasText(patch.getShareToken())) {
                    old.setShareToken(patch.getShareToken());
                }
                old.setSharePassword(patch.getSharePassword());
                old.setShareExpiresAt(patch.getShareExpiresAt());
            } else if (Boolean.FALSE.equals(shareEnabled)) {
                old.setShareEnabled(false);
            }
            // 公开知识库无需成员列表，清空避免残留私有成员数据
            if (Boolean.TRUE.equals(old.getPublicVisible())) {
                old.setMembers(null);
            }
            old.setUpdateTime(Instant.now());
            // slug：用户清空则重新生成；校验唯一（排除自身）
            if (!StringUtils.hasText(old.getSlug())) {
                old.setSlug(generateSlug());
            }
            return slugExists(old.getSlug(), name)
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "知识库链接别名已存在: " + old.getSlug()));
                    }
                    syncShare(old);
                    kb.setSpec(old);
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
     * 聚合统计当前用户可访问的知识库与文档数量，并计算月度环比。
     * <p>仅统计当前用户有权限访问的资源（公开知识库，以及自己创建/是成员的私有知识库及其文档），
     * 避免向普通用户泄露全站私有知识库/文档的聚合数量。
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

        return currentAccess().flatMap(access -> client.listAll(KnowledgeBase.class,
                ListOptions.builder().build(), Sort.unsorted())
            .filter(kb -> canAccess(kb, access.username(), access.manage()))
            .collectList()
            .flatMap(kbs -> {
                var accessibleNames = Set.copyOf(kbs.stream()
                    .map(kb -> kb.getMetadata().getName())
                    .toList());
                return client.listAll(KnowledgeBaseDoc.class, ListOptions.builder().build(),
                        Sort.unsorted())
                    .filter(doc -> accessibleNames.contains(doc.getSpec().getKnowledgeBaseName()))
                    .collectList()
                    .map(docs -> buildStats(kbs, docs, thisMonthStart, lastMonthStart));
            }));
    }

    /**
     * 由可访问的知识库与文档列表构造统计 DTO。
     */
    private KnowledgeBaseStatsDto buildStats(List<KnowledgeBase> kbs, List<KnowledgeBaseDoc> docs,
        Instant thisMonthStart, Instant lastMonthStart) {
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
