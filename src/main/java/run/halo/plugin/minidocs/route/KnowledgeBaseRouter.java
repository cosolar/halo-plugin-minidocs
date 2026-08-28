package run.halo.plugin.minidocs.route;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.util.HashMap;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.theme.TemplateNameResolver;
import run.halo.plugin.minidocs.service.KnowledgeBaseService;

/**
 * 知识库分享页（主题模板）路由。
 *
 * <p>以 {@code @Component} + {@code @Bean RouterFunction} 方式注册前台模板路由，
 * 由 Halo 自动收集。提供以下路由：
 * <ul>
 *   <li>{@code /docs/share/{kbSlug}} 知识库分享页（左侧文档树、中间阅读区、右侧大纲），渲染 docs-share.html</li>
 *   <li>{@code /docs/view/{kbSlug}} 知识库阅读页，渲染 doc.html</li>
 *   <li>{@code /docs} 文档列表页（模板通过 minidocsFinder 自取公开知识库），渲染 docs.html</li>
 * </ul>
 *
 * <p>主题可选择性提供同名模板覆盖；未提供时使用插件内置默认模板。
 *
 * @author Cosolar
 */
@Component
@RequiredArgsConstructor
public class KnowledgeBaseRouter {

    private final KnowledgeBaseService knowledgeBaseService;
    private final TemplateNameResolver templateNameResolver;

    @Bean
    RouterFunction<ServerResponse> knowledgeBaseShareRoute() {
        return route(GET("/docs/share/{kbSlug}"), shareHandler("doc_share"))
            .andRoute(GET("/docs/view/{kbSlug}"), shareHandler("doc"))
            .andRoute(GET("/docs"), req -> templateNameResolver
                .resolveTemplateNameOrDefault(req.exchange(), "docs")
                .flatMap(t -> ServerResponse.ok().render(t)));
    }

    private HandlerFunction<ServerResponse> shareHandler(String template) {
        return request -> {
            var kbSlug = request.pathVariable("kbSlug");
            var docSlug = request.queryParam("docSlug")
                .filter(s -> !s.isBlank()).orElse(null);

            // 服务端仅做公开性校验，不把业务数据塞入 model；
            // 真正的知识库/文档树/文档数据由模板通过 minidocsFinder 自行查询。
            return knowledgeBaseService.getBySlugOrName(kbSlug)
                .flatMap(kb -> {
                    if (!Boolean.TRUE.equals(kb.getSpec().getPublicVisible())) {
                        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "知识库不存在或未公开: " + kbSlug));
                    }
                    return templateNameResolver
                        .resolveTemplateNameOrDefault(request.exchange(), template)
                        .flatMap(t -> {
                            var model = new HashMap<String, Object>();
                            model.put("kbSlug", kbSlug);
                            if (docSlug != null) {
                                model.put("docSlug", docSlug);
                            }
                            return ServerResponse.ok().render(t, model);
                        });
                })
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "知识库不存在或未公开: " + kbSlug)));
        };
    }
}
