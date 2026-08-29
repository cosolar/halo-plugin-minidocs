package cn.minims.minidocs.endpoint;

import java.security.Principal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ListResult;
import run.halo.app.plugin.ReactiveSettingFetcher;
import cn.minims.minidocs.extension.KnowledgeBase;
import cn.minims.minidocs.setting.BasicSetting;
import cn.minims.minidocs.service.KnowledgeBaseDocService;
import cn.minims.minidocs.service.KnowledgeBaseService;

import static org.springframework.web.reactive.function.server.RequestPredicates.accept;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import cn.minims.minidocs.endpoint.KnowledgeBaseStatsDto;

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
    private final ReactiveSettingFetcher settingFetcher;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return route()
            .GET("/knowledgebases/stats", this::stats)
            .GET("/knowledgebases/settings", this::settings)
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

    private Mono<ServerResponse> settings(ServerRequest request) {
        // 供 Markdown 编辑器读取代码块高亮主题；不依赖 Halo 超管专属的
        // /apis/api.console.halo.run/.../json-config 接口
        return settingFetcher.fetch("basic", BasicSetting.class)
            .map(BasicSetting::codeBlockThemeOrDefault)
            .defaultIfEmpty("default")
            .flatMap(theme -> ServerResponse.ok().bodyValue(Map.of("codeBlockTheme", theme)));
    }

    private Mono<ServerResponse> listKnowledgeBases(ServerRequest request) {
        var keyword = request.queryParam("keyword").orElse(null);
        var publicVisible = request.queryParam("publicVisible")
            .map(Boolean::parseBoolean)
            .orElse(null);
        var page = request.queryParam("page").map(Integer::parseInt).orElse(1);
        var size = request.queryParam("size").map(Integer::parseInt).orElse(20);
        var sortBy = request.queryParam("sortBy").orElse("updateTime");
        return knowledgeBaseService.listAccessible(keyword, publicVisible, sortBy, page, size)
            .flatMap(result -> ServerResponse.ok().bodyValue(result));
    }

    private Mono<ServerResponse> getKnowledgeBase(ServerRequest request) {
        var name = request.pathVariable("name");
        return knowledgeBaseService.requireAccessByName(name)
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
        return knowledgeBaseService.requireAccessByName(name)
            .then(request.bodyToMono(KnowledgeBase.class)
                .flatMap(update -> knowledgeBaseService.update(name, update))
                .flatMap(kb -> ServerResponse.ok().bodyValue(kb)));
    }

    private Mono<ServerResponse> deleteKnowledgeBase(ServerRequest request) {
        var name = request.pathVariable("name");
        return knowledgeBaseService.requireAccessByName(name)
            .flatMap(kb -> knowledgeBaseService.delete(name))
            .then(ServerResponse.ok().build());
    }

    private Mono<ServerResponse> exportKnowledgeBases(ServerRequest request) {
        return settingFetcher.fetch("basic", BasicSetting.class)
            .map(BasicSetting::docExportEnabled)
            .defaultIfEmpty(true)
            .flatMap(enabled -> {
                if (!enabled) {
                    return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "知识库导出已被管理员禁用"));
                }
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
                    .flatMap(names -> knowledgeBaseService.currentAccess().flatMap(access -> {
                        // 只导出当前用户有权访问的知识库（私有非成员不可导出）
                        var accessible = names.stream()
                            .filter(n -> filterExportAccessible(n, access))
                            .toList();
                        return docService.exportZip(accessible)
                            .flatMap(bytes -> ServerResponse.ok()
                                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                    "attachment; filename=\"minidocs" + exportTimestamp() + ".zip\"")
                                .bodyValue(new ByteArrayResource(bytes)));
                    }));
            });
    }

    private static String exportTimestamp() {
        return DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS")
            .format(LocalDateTime.now());
    }

    private boolean filterExportAccessible(String name, KnowledgeBaseService.UserAccess access) {
        // 用 try 忽略不存在的知识库（保留原行为不阻塞导出）
        return knowledgeBaseService.get(name)
            .map(kb -> KnowledgeBaseService.canAccess(kb, access.username(), access.manage()))
            .onErrorReturn(false)
            .blockOptional()
            .orElse(false);
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
