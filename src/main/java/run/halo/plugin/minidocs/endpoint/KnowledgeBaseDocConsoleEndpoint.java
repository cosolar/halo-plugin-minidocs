package run.halo.plugin.minidocs.endpoint;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.plugin.minidocs.extension.KnowledgeBaseDoc;
import run.halo.plugin.minidocs.service.KnowledgeBaseDocService;

import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

/**
 * 知识库文档 Console 管理端点。
 *
 * <p>前缀：/apis/console.api.minidocs.halo.run/v1alpha1
 *
 * @author Cosolar
 */
@Component
@RequiredArgsConstructor
public class KnowledgeBaseDocConsoleEndpoint implements CustomEndpoint {

    private final KnowledgeBaseDocService docService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
            .GET("/knowledgebases/{name}/docs", this::listDocs)
            .GET("/knowledgebases/{name}/tree", this::getDocTree)
            .GET("/knowledgebases/{name}/docs/{docName}", this::getDoc)
            .POST("/knowledgebases/{name}/docs", accept(MediaType.APPLICATION_JSON),
                this::createDoc)
            .PUT("/knowledgebases/{name}/docs/{docName}", accept(MediaType.APPLICATION_JSON),
                this::updateDoc)
            .DELETE("/knowledgebases/{name}/docs/{docName}", this::deleteDoc)
            .POST("/knowledgebases/{name}/docs/{docName}/publish", this::publishDoc)
            .POST("/knowledgebases/{name}/docs/{docName}/move", accept(MediaType.APPLICATION_JSON),
                this::moveDoc)
            .GET("/knowledgebases/{name}/docs/{docName}/export", this::exportDoc)
            .build();
    }

    private Mono<ServerResponse> listDocs(ServerRequest request) {
        var kbName = request.pathVariable("name");
        var keyword = request.queryParam("keyword").orElse(null);
        var phase = request.queryParam("phase").orElse(null);
        var page = request.queryParam("page").map(Integer::parseInt).orElse(1);
        var size = request.queryParam("size").map(Integer::parseInt).orElse(20);
        return docService.list(kbName, keyword, phase, page, size)
            .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<ServerResponse> getDocTree(ServerRequest request) {
        var kbName = request.pathVariable("name");
        return docService.buildTree(kbName, null)
            .flatMap(tree -> ServerResponse.ok().bodyValue(tree));
    }

    private Mono<ServerResponse> getDoc(ServerRequest request) {
        var kbName = request.pathVariable("name");
        var docName = request.pathVariable("docName");
        return docService.get(kbName, docName)
            .flatMap(doc -> ServerResponse.ok().bodyValue(doc));
    }

    private Mono<ServerResponse> createDoc(ServerRequest request) {
        var kbName = request.pathVariable("name");
        return request.bodyToMono(KnowledgeBaseDoc.class)
            .flatMap(doc -> docService.create(kbName, doc))
            .flatMap(doc -> ServerResponse.ok().bodyValue(doc));
    }

    private Mono<ServerResponse> updateDoc(ServerRequest request) {
        var kbName = request.pathVariable("name");
        var docName = request.pathVariable("docName");
        return request.bodyToMono(KnowledgeBaseDoc.class)
            .flatMap(update -> docService.update(kbName, docName, update))
            .flatMap(doc -> ServerResponse.ok().bodyValue(doc));
    }

    private Mono<ServerResponse> deleteDoc(ServerRequest request) {
        var kbName = request.pathVariable("name");
        var docName = request.pathVariable("docName");
        return docService.delete(kbName, docName)
            .then(ServerResponse.ok().build());
    }

    private Mono<ServerResponse> publishDoc(ServerRequest request) {
        var kbName = request.pathVariable("name");
        var docName = request.pathVariable("docName");
        return docService.publish(kbName, docName)
            .then(ServerResponse.ok().build());
    }

    private Mono<ServerResponse> moveDoc(ServerRequest request) {
        var kbName = request.pathVariable("name");
        var docName = request.pathVariable("docName");
        return request.bodyToMono(MoveDocRequest.class)
            .flatMap(move -> docService.move(kbName, docName, move.parentName(), move.priority()))
            .then(ServerResponse.ok().build());
    }

    private Mono<ServerResponse> exportDoc(ServerRequest request) {
        var kbName = request.pathVariable("name");
        var docName = request.pathVariable("docName");
        return docService.exportMarkdown(kbName, docName)
            .flatMap(markdown -> ServerResponse.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + docName + ".md\"")
                .bodyValue(markdown));
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.minidocs.halo.run", "v1alpha1");
    }

    /**
     * 移动文档请求体。
     */
    public record MoveDocRequest(String parentName, Integer priority) {
    }
}
