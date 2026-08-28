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
import run.halo.app.plugin.ReactiveSettingFetcher;
import cn.minims.minidocs.extension.KnowledgeBase;
import cn.minims.minidocs.extension.KnowledgeBaseDoc;
import cn.minims.minidocs.service.KnowledgeBaseDocService;
import cn.minims.minidocs.service.KnowledgeBaseDocService.DocTreeNode;
import cn.minims.minidocs.service.KnowledgeBaseService;
import cn.minims.minidocs.setting.BasicSetting;

import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * 知识库公开只读端点（供主题/第三方消费）。
 *
 * <p>前缀：/apis/api.minidocs.halo.run/v1alpha1
 * <p>仅暴露 publicVisible=true 的知识库及其已发布文档；未登录访问受
 * allowAnonymousRead 设置约束（RBAC 匿名聚合 + 服务层二次校验）。
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
                return knowledgeBaseService.listPublic(keyword, page, size)
                    .flatMap(result -> ServerResponse.ok().bodyValue(result));
            }));
    }

    private Mono<ServerResponse> getKnowledgeBase(ServerRequest request) {
        var kbSlug = request.pathVariable("kbSlug");
        return enforcePublicRead()
            .then(getPublicKnowledgeBase(kbSlug)
                .flatMap(kb -> ServerResponse.ok().bodyValue(kb)));
    }

    private Mono<ServerResponse> getDocTree(ServerRequest request) {
        var kbSlug = request.pathVariable("kbSlug");
        return enforcePublicRead()
            .then(getPublicKnowledgeBase(kbSlug)
                .flatMap(kb -> docService.buildTree(kb.getMetadata().getName(),
                        KnowledgeBaseDocService.PHASE_PUBLISHED))
                .flatMap(tree -> ServerResponse.ok().bodyValue(tree)));
    }

    private Mono<ServerResponse> listDocs(ServerRequest request) {
        var kbSlug = request.pathVariable("kbSlug");
        return enforcePublicRead()
            .then(getPublicKnowledgeBase(kbSlug)
                .flatMap(kb -> {
                    var keyword = request.queryParam("keyword").orElse(null);
                    var page = request.queryParam("page").map(Integer::parseInt).orElse(1);
                    var size = request.queryParam("size").map(Integer::parseInt).orElse(20);
                    return docService.list(kb.getMetadata().getName(), keyword,
                            KnowledgeBaseDocService.PHASE_PUBLISHED, page, size)
                        .flatMap(result -> ServerResponse.ok().bodyValue(result));
                }));
    }

    private Mono<ServerResponse> getDoc(ServerRequest request) {
        var kbSlug = request.pathVariable("kbSlug");
        var docSlug = request.pathVariable("docSlug");
        return enforcePublicRead()
            .then(getPublicKnowledgeBase(kbSlug)
                .flatMap(kb -> docService.getPublishedDocBySlug(kb.getMetadata().getName(), docSlug))
                .flatMap(doc -> ServerResponse.ok().bodyValue(doc)));
    }

    private Mono<ServerResponse> getDocBySlug(ServerRequest request) {
        var docSlug = request.pathVariable("docSlug");
        return enforcePublicRead()
            .then(docService.getPublishedDocBySlug(docSlug)
                .flatMap(doc -> getPublicKnowledgeBase(doc.getSpec().getKnowledgeBaseName())
                    .thenReturn(doc))
                .flatMap(doc -> ServerResponse.ok().bodyValue(doc)));
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
     * 获取公开知识库（按 slug 或 metadata.name 解析，不存在或非公开时返回 404）。
     */
    private Mono<KnowledgeBase> getPublicKnowledgeBase(String kbSlug) {
        return knowledgeBaseService.getBySlugOrName(kbSlug)
            .filter(kb -> Boolean.TRUE.equals(kb.getSpec().getPublicVisible()))
            .switchIfEmpty(Mono.error(
                new ResponseStatusException(HttpStatus.NOT_FOUND, "知识库不存在: " + kbSlug)));
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
