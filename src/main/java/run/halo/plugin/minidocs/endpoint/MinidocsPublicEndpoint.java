package run.halo.plugin.minidocs.endpoint;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.plugin.minidocs.extension.KnowledgeBase;
import run.halo.plugin.minidocs.extension.KnowledgeBaseDoc;
import run.halo.plugin.minidocs.service.KnowledgeBaseDocService;
import run.halo.plugin.minidocs.service.KnowledgeBaseDocService.DocTreeNode;
import run.halo.plugin.minidocs.service.KnowledgeBaseService;
import run.halo.plugin.minidocs.setting.BasicSetting;

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
