package run.halo.plugin.minidocs.endpoint;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.plugin.minidocs.extension.KnowledgeBase;
import run.halo.plugin.minidocs.service.KnowledgeBaseService;

import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import run.halo.plugin.minidocs.endpoint.KnowledgeBaseStatsDto;

/**
 * 知识库 Console 管理端点。
 *
 * <p>前缀：/apis/console.api.minidocs.halo.run/v1alpha1
 *
 * @author Cosolar
 */
@Component
@RequiredArgsConstructor
public class KnowledgeBaseConsoleEndpoint implements CustomEndpoint {

    private final KnowledgeBaseService knowledgeBaseService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
            .GET("/knowledgebases/stats", this::stats)
            .GET("/knowledgebases", this::listKnowledgeBases)
            .GET("/knowledgebases/{name}", this::getKnowledgeBase)
            .POST("/knowledgebases", accept(MediaType.APPLICATION_JSON),
                this::createKnowledgeBase)
            .PUT("/knowledgebases/{name}", accept(MediaType.APPLICATION_JSON),
                this::updateKnowledgeBase)
            .DELETE("/knowledgebases/{name}", this::deleteKnowledgeBase)
            .build();
    }

    private Mono<ServerResponse> stats(ServerRequest request) {
        return knowledgeBaseService.stats()
            .flatMap(stats -> ServerResponse.ok().bodyValue(stats));
    }

    private Mono<ServerResponse> listKnowledgeBases(ServerRequest request) {
        var keyword = request.queryParam("keyword").orElse(null);
        var publicVisible = request.queryParam("publicVisible")
            .map(Boolean::parseBoolean)
            .orElse(null);
        var page = request.queryParam("page").map(Integer::parseInt).orElse(1);
        var size = request.queryParam("size").map(Integer::parseInt).orElse(20);
        return knowledgeBaseService.list(keyword, publicVisible, page, size)
            .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<ServerResponse> getKnowledgeBase(ServerRequest request) {
        var name = request.pathVariable("name");
        return knowledgeBaseService.get(name)
            .flatMap(kb -> ServerResponse.ok().bodyValue(kb));
    }

    private Mono<ServerResponse> createKnowledgeBase(ServerRequest request) {
        return request.bodyToMono(KnowledgeBase.class)
            .flatMap(knowledgeBaseService::create)
            .flatMap(kb -> ServerResponse.ok().bodyValue(kb));
    }

    private Mono<ServerResponse> updateKnowledgeBase(ServerRequest request) {
        var name = request.pathVariable("name");
        return request.bodyToMono(KnowledgeBase.class)
            .flatMap(update -> knowledgeBaseService.update(name, update))
            .flatMap(kb -> ServerResponse.ok().bodyValue(kb));
    }

    private Mono<ServerResponse> deleteKnowledgeBase(ServerRequest request) {
        var name = request.pathVariable("name");
        return knowledgeBaseService.delete(name)
            .then(ServerResponse.ok().build());
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.minidocs.halo.run", "v1alpha1");
    }
}
