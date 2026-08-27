package run.halo.plugin.minidocs.endpoint;

import java.security.Principal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.plugin.minidocs.extension.KnowledgeBase;
import run.halo.plugin.minidocs.service.KnowledgeBaseDocService;
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
    private final KnowledgeBaseDocService docService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
            .GET("/knowledgebases/stats", this::stats)
            .GET("/knowledgebases", this::listKnowledgeBases)
            .GET("/knowledgebases/{name}", this::getKnowledgeBase)
            .POST("/knowledgebases", accept(MediaType.APPLICATION_JSON),
                this::createKnowledgeBase)
            .POST("/knowledgebases/export", accept(MediaType.APPLICATION_JSON),
                this::exportKnowledgeBases)
            .POST("/knowledgebases/import", this::importKnowledgeBases)
            .POST("/knowledgebases/import/preview", this::importKnowledgeBasePreview)
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
        var sortBy = request.queryParam("sortBy").orElse("updateTime");
        return knowledgeBaseService.list(keyword, publicVisible, sortBy, page, size)
            .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<ServerResponse> getKnowledgeBase(ServerRequest request) {
        var name = request.pathVariable("name");
        return knowledgeBaseService.get(name)
            .flatMap(kb -> ServerResponse.ok().bodyValue(kb));
    }

    private Mono<ServerResponse> createKnowledgeBase(ServerRequest request) {
        var creator = request.principal()
            .map(Principal::getName)
            .filter(name -> name != null && !name.isBlank())
            .defaultIfEmpty("unknown");
        return request.bodyToMono(KnowledgeBase.class)
            .flatMap(kb -> creator.flatMap(name -> knowledgeBaseService.create(kb, name)))
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

    private Mono<ServerResponse> exportKnowledgeBases(ServerRequest request) {
        return request.bodyToMono(Map.class)
            .map(body -> {
                Object namesObj = body == null ? null : body.get("names");
                if (namesObj instanceof List<?> list) {
                    return list.stream()
                        .filter(String.class::isInstance)
                        .map(String.class::cast)
                        .toList();
                }
                return List.<String>of();
            })
            .flatMap(docService::exportZip)
            .flatMap(bytes -> ServerResponse.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"minidocs" + exportTimestamp() + ".zip\"")
                .bodyValue(new ByteArrayResource(bytes)));
    }

    private static String exportTimestamp() {
        return DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
            .format(LocalDateTime.now());
    }

    private Mono<ServerResponse> importKnowledgeBasePreview(ServerRequest request) {
        return request.multipartData().flatMap(parts -> {
            var part = parts.getFirst("file");
            if (!(part instanceof FilePart filePart)) {
                return ServerResponse.badRequest().bodyValue("缺少 zip 文件");
            }
            return readFileBytes(filePart)
                .flatMap(docService::previewImport)
                .flatMap(list -> ServerResponse.ok().bodyValue(list));
        });
    }

    private Mono<ServerResponse> importKnowledgeBases(ServerRequest request) {
        var creator = request.principal()
            .map(Principal::getName)
            .filter(n -> n != null && !n.isBlank())
            .defaultIfEmpty("unknown");
        return request.multipartData().flatMap(parts -> {
            var part = parts.getFirst("file");
            if (!(part instanceof FilePart filePart)) {
                return ServerResponse.badRequest().bodyValue("缺少 zip 文件");
            }
            var strategyPart = parts.getFirst("strategy");
            var strategy = strategyPart instanceof FormFieldPart ff
                && StringUtils.hasText(ff.value()) ? ff.value() : "overwrite";
            return readFileBytes(filePart)
                .flatMap(bytes -> creator.flatMap(
                    name -> docService.importFromZip(bytes, strategy, name)))
                .flatMap(list -> ServerResponse.ok().bodyValue(list));
        });
    }

    private Mono<byte[]> readFileBytes(FilePart filePart) {
        return DataBufferUtils.join(filePart.content()).map(buf -> {
            var bytes = new byte[buf.readableByteCount()];
            buf.read(bytes);
            DataBufferUtils.release(buf);
            return bytes;
        });
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.minidocs.halo.run", "v1alpha1");
    }
}
