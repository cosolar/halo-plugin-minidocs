package cn.minims.minidocs.endpoint;

import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListResult;
import run.halo.app.plugin.ReactiveSettingFetcher;
import cn.minims.minidocs.extension.KnowledgeBase;
import cn.minims.minidocs.extension.KnowledgeBaseDoc;
import cn.minims.minidocs.service.KnowledgeBaseDocService;
import cn.minims.minidocs.service.KnowledgeBaseDocService.DocTreeNode;
import cn.minims.minidocs.service.KnowledgeBaseService;
import cn.minims.minidocs.setting.BasicSetting;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * 知识库公开端点（供主题/第三方消费）。
 *
 * <p>前缀：/apis/api.minidocs.halo.run/v1alpha1
 * <p>可见性规则（与 Console / Finder 保持一致）：私有知识库仅「创建者、
 * spec.members 成员、知识库拥有管理权限者」可访问，其余一律 404。
 * 未登录访问公开库还受 allowAnonymousRead 设置约束（RBAC 匿名聚合 + 服务层二次校验）。
 * 已登录且有权限的用户可读取私有库内容（含草稿）；公开库对所有人仅暴露已发布文档。
 *
 * @author Cosolar
 */
@Component
@RequiredArgsConstructor
public class MinidocsPublicEndpoint implements CustomEndpoint {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseDocService docService;
    private final ReactiveSettingFetcher settingFetcher;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
            .GET("/knowledgebases", this::listKnowledgeBases)
            .GET("/knowledgebases/{kbSlug}", this::getKnowledgeBase)
            .GET("/knowledgebases/{kbSlug}/tree", this::getDocTree)
            .GET("/knowledgebases/{kbSlug}/docs", this::listDocs)
            .GET("/knowledgebases/{kbSlug}/docs/{docSlug}", this::getDoc)
            .GET("/docs/{docSlug}", this::getDocBySlug)
            .GET("/knowledgebases/{kbSlug}/stats", this::getStats)
            .POST("/knowledgebases/{kbSlug}/like", this::toggleLike)
            .GET("/share/{shareToken}/stats", this::getShareStats)
            .POST("/share/{shareToken}/like", this::toggleShareLike)
            .build();
    }

    private Mono<ServerResponse> listKnowledgeBases(ServerRequest request) {
        return enforcePublicRead()
            .then(Mono.defer(() -> {
                var keyword = request.queryParam("keyword").orElse(null);
                var page = request.queryParam("page").map(Integer::parseInt).orElse(1);
                var size = request.queryParam("size").map(Integer::parseInt).orElse(20);
                // 未登录：仅公开库；已登录：额外包含自己有权限访问的私有库（创建者/成员/管理）
                return knowledgeBaseService.currentUsername()
                    .flatMap(username -> {
                        Mono<ListResult<KnowledgeBase>> result = StringUtils.hasText(username)
                            ? knowledgeBaseService.listAccessible(keyword, null, "updateTime",
                                page, size)
                            : knowledgeBaseService.listPublic(keyword, page, size);
                        return result.flatMap(r -> ServerResponse.ok().bodyValue(r));
                    });
            }));
    }

    private Mono<ServerResponse> getKnowledgeBase(ServerRequest request) {
        var kbKey = request.pathVariable("kbSlug");
        return enforcePublicRead()
            .then(resolveAccessible(kbKey)
                .flatMap(kb -> ServerResponse.ok().bodyValue(kb)));
    }

    private Mono<ServerResponse> getDocTree(ServerRequest request) {
        var kbKey = request.pathVariable("kbSlug");
        return enforcePublicRead()
            .then(resolveAccessible(kbKey)
                .flatMap(kb -> docService.buildTree(kb.getMetadata().getName(), docPhaseFor(kb)))
                .flatMap(tree -> ServerResponse.ok().bodyValue(tree)));
    }

    private Mono<ServerResponse> listDocs(ServerRequest request) {
        var kbKey = request.pathVariable("kbSlug");
        return enforcePublicRead()
            .then(resolveAccessible(kbKey)
                .flatMap(kb -> {
                    var keyword = request.queryParam("keyword").orElse(null);
                    var page = request.queryParam("page").map(Integer::parseInt).orElse(1);
                    var size = request.queryParam("size").map(Integer::parseInt).orElse(20);
                    return docService.list(kb.getMetadata().getName(), keyword, docPhaseFor(kb),
                            page, size)
                        .flatMap(result -> ServerResponse.ok().bodyValue(result));
                }));
    }

    private Mono<ServerResponse> getDoc(ServerRequest request) {
        var kbKey = request.pathVariable("kbSlug");
        var docKey = request.pathVariable("docSlug");
        return enforcePublicRead()
            .then(resolveAccessible(kbKey))
            .flatMap(kb -> {
                var kbName = kb.getMetadata().getName();
                // 已通过资源级权限校验：私有库仅创建者/成员/管理可见，可读取草稿与已发布，
                // 文档标识支持 metadata.name 或 spec.slug；公开库对所有人仅暴露已发布文档。
                Mono<KnowledgeBaseDoc> doc = Boolean.TRUE.equals(kb.getSpec().getPublicVisible())
                    ? docService.getPublishedDocBySlug(kbName, docKey)
                    : docService.getByNameOrSlug(kbName, docKey);
                return doc.flatMap(d -> ServerResponse.ok().bodyValue(d));
            });
    }

    private Mono<ServerResponse> getDocBySlug(ServerRequest request) {
        var docKey = request.pathVariable("docSlug");
        return enforcePublicRead()
            .then(findAccessibleDocGlobal(docKey)
                .flatMap(doc -> ServerResponse.ok().bodyValue(doc)));
    }

    /**
     * 该知识库对外暴露的文档阶段：公开库仅已发布；私有库（已通过权限校验）含草稿。
     */
    private String docPhaseFor(KnowledgeBase kb) {
        return Boolean.TRUE.equals(kb.getSpec().getPublicVisible())
            ? KnowledgeBaseDocService.PHASE_PUBLISHED : null;
    }

    /**
     * 全局按 name 或 slug 解析一篇当前用户可访问的文档。
     * <p>文档所属知识库须为公开库，或当前登录用户对它有权限（创建者/成员/管理）；
     * 否则返回 404，避免越权读取私有库内容。
     */
    private Mono<KnowledgeBaseDoc> findAccessibleDocGlobal(String docKey) {
        return docService.getByNameOrSlugGlobal(docKey)
            .flatMap(doc -> {
                var kbName = doc.getSpec().getKnowledgeBaseName();
                return knowledgeBaseService.get(kbName).flatMap(kb ->
                    knowledgeBaseService.currentAccess().flatMap(access -> {
                        var isPublic = Boolean.TRUE.equals(kb.getSpec().getPublicVisible());
                        // 公开库：任何人可读但仅限已发布；私有库：仅权限者可见且任意阶段
                        var ok = KnowledgeBaseService.canAccess(kb, access.username(),
                                access.manage())
                            && (!isPublic || isPublished(doc));
                        if (ok) {
                            return Mono.just(doc);
                        }
                        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "文档不存在: " + docKey));
                    }));
            });
    }

    private boolean isPublished(KnowledgeBaseDoc doc) {
        return KnowledgeBaseDocService.PHASE_PUBLISHED.equals(doc.getSpec().getPhase());
    }

    /**
     * 知识库访问量 / 点赞量统计（公开知识库或当前用户有权限访问的私有知识库）。
     */
    private Mono<ServerResponse> getStats(ServerRequest request) {
        var kbSlug = request.pathVariable("kbSlug");
        return resolveAccessible(kbSlug)
            .flatMap(kb -> knowledgeBaseService.currentUsername()
                .map(username -> {
                    var spec = kb.getSpec();
                    var likedUsers = spec.getLikedUsers();
                    var liked = StringUtils.hasText(username) && likedUsers != null
                        && likedUsers.contains(username);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("accessCount", spec.getAccessCount() == null ? 0L
                        : spec.getAccessCount());
                    body.put("likeCount", spec.getLikeCount() == null ? 0L
                        : spec.getLikeCount());
                    body.put("liked", liked);
                    return body;
                })
                .flatMap(body -> ServerResponse.ok().bodyValue(body)));
    }

    /**
     * 知识库点赞（一次性、幂等，匿名用户也可点赞）。
     * <p>已登录用户由 likedUsers 记录去重；匿名用户由前端 localStorage 缓存防止重复点赞。
     */
    private Mono<ServerResponse> toggleLike(ServerRequest request) {
        var kbSlug = request.pathVariable("kbSlug");
        return resolveAccessible(kbSlug)
            .flatMap(kb -> knowledgeBaseService.currentUsername()
                .flatMap(username ->
                    knowledgeBaseService.likeOnce(kb.getMetadata().getName(), username)
                        .flatMap(body -> ServerResponse.ok().bodyValue(body))));
    }

    /**
     * 分享链路访问量/点赞统计（仅需有效外链，不要求公开或登录）。
     */
    private Mono<ServerResponse> getShareStats(ServerRequest request) {
        var token = request.pathVariable("shareToken");
        return resolveShareAccess(token, request)
            .flatMap(kb -> knowledgeBaseService.currentUsername()
                .map(username -> {
                    var spec = kb.getSpec();
                    var liked = StringUtils.hasText(username) && spec.getLikedUsers() != null
                        && spec.getLikedUsers().contains(username);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("accessCount", spec.getAccessCount() == null ? 0L
                        : spec.getAccessCount());
                    body.put("likeCount", spec.getLikeCount() == null ? 0L
                        : spec.getLikeCount());
                    body.put("liked", liked);
                    return body;
                })
                .flatMap(body -> ServerResponse.ok().bodyValue(body)));
    }

    /**
     * 分享链路点赞（一次性、幂等，无需登录）。
     */
    private Mono<ServerResponse> toggleShareLike(ServerRequest request) {
        var token = request.pathVariable("shareToken");
        return resolveShareAccess(token, request)
            .flatMap(kb -> knowledgeBaseService.currentUsername()
                .flatMap(username ->
                    knowledgeBaseService.likeOnce(kb.getMetadata().getName(), username)
                        .flatMap(body -> ServerResponse.ok().bodyValue(body))));
    }

    /**
     * 按分享 token 解析知识库并做分享级校验：开启且未过期，且（若设置了密码）请求需携带
     * 匹配的访问 cookie。不要求知识库公开、不要求登录——仅需有效外链。
     */
    private Mono<KnowledgeBase> resolveShareAccess(String shareToken, ServerRequest request) {
        return knowledgeBaseService.findByShareToken(shareToken)
            .flatMap(kb -> {
                var state = KnowledgeBaseService.shareState(kb);
                if (!state.enabled() || state.expired()) {
                    return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "分享链接不存在或已失效"));
                }
                if (state.passwordRequired()) {
                    var list = request.cookies().get(KnowledgeBaseService.shareCookieName(
                        shareToken));
                    var value = list == null || list.isEmpty() ? null : list.get(0).getValue();
                    if (!KnowledgeBaseService.shareCookieMatches(kb, value)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "需要访问密码"));
                    }
                }
                return Mono.just(kb);
            });
    }

    /**
     * 解析知识库并按当前用户做资源级可见性校验：公开知识库放行；
     * 私有知识库仅创建者/成员/管理权限者可见，其余 404。
     */
    private Mono<KnowledgeBase> resolveAccessible(String kbSlug) {
        return knowledgeBaseService.getBySlugOrName(kbSlug)
            .flatMap(kb -> knowledgeBaseService.currentAccess()
                .flatMap(access -> {
                    if (KnowledgeBaseService.canAccess(kb, access.username(),
                        access.manage())) {
                        return Mono.just(kb);
                    }
                    return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "知识库不存在或无访问权限: " + kbSlug));
                }));
    }

    /**
     * 公开读控制：allowAnonymousRead=false 时，未登录用户一律 403。
     */
    private Mono<Void> enforcePublicRead() {
        return settingFetcher.fetch("basic", BasicSetting.class)
            .map(BasicSetting::anonymousReadEnabled)
            .defaultIfEmpty(true)
            .flatMap(enabled -> {
                if (enabled) {
                    return Mono.empty();
                }
                return isAuthenticated().flatMap(authenticated -> authenticated
                    ? Mono.empty()
                    : Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "站点未开放匿名阅读，请登录后访问")));
            });
    }

    private Mono<Boolean> isAuthenticated() {
        return ReactiveSecurityContextHolder.getContext()
            .map(SecurityContext::getAuthentication)
            .filter(auth -> !(auth instanceof AnonymousAuthenticationToken))
            .hasElement();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.minidocs.halo.run", "v1alpha1");
    }
}
