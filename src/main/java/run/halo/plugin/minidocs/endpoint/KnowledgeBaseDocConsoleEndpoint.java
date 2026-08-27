package run.halo.plugin.minidocs.endpoint;

import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import run.halo.app.extension.Metadata;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
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
            .POST("/knowledgebases/{name}/docs/import", this::importDocs)
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
        var author = currentUsername(request);
        return request.bodyToMono(KnowledgeBaseDoc.class)
            .flatMap(doc -> author.flatMap(name -> docService.create(kbName, doc, name)))
            .flatMap(doc -> ServerResponse.ok().bodyValue(doc));
    }

    /**
     * 当前登录用户名（匿名时为 "unknown"）。
     */
    private Mono<String> currentUsername(ServerRequest request) {
        return request.principal()
            .map(Principal::getName)
            .filter(name -> name != null && !name.isBlank())
            .defaultIfEmpty("unknown");
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

    /**
     * 批量导入 Markdown 文档（multipart/form-data）。
     *
     * <p>Part 说明：{@code parentName} 为目标父文档名称（可空=根目录）；
     * 其余 {@code .md} 文件 Part 各导入为一篇文档，标题取文件名（去扩展名）。
     */
    private Mono<ServerResponse> importDocs(ServerRequest request) {
        var kbName = request.pathVariable("name");
        return request.multipartData().flatMap(parts -> {
            Part parentPart = parts.getFirst("parentName");
            String rawParent = parentPart instanceof FormFieldPart field ? field.value() : null;
            String parentName = StringUtils.hasText(rawParent) ? rawParent : null;
            List<FilePart> files = parts.get("files").stream()
                .filter(FilePart.class::isInstance)
                .map(FilePart.class::cast)
                .toList();
            var order = new AtomicInteger(0);
            return currentUsername(request).flatMap(author ->
                    importAll(kbName, parentName, files, order, author))
                .map(count -> Map.of("count", count))
                .flatMap(ServerResponse.ok()::bodyValue);
        });
    }

    private Mono<Integer> importAll(String kbName, String parentName, List<FilePart> files,
        AtomicInteger order, String author) {
        if (files.isEmpty()) {
            return Mono.just(0);
        }
        FilePart file = files.get(0);
        var rest = files.subList(1, files.size());
        return readFilePart(file)
            .flatMap(content -> {
                String title = titleOfFile(file.filename());
                var doc = new KnowledgeBaseDoc();
                var md = new Metadata();
                md.setName(UUID.randomUUID().toString());
                doc.setMetadata(md);
                var spec = new KnowledgeBaseDoc.Spec();
                spec.setKnowledgeBaseName(kbName);
                spec.setTitle(title);
                spec.setContent(content);
                spec.setParentName(parentName);
                spec.setPriority(order.getAndIncrement());
                doc.setSpec(spec);
                return docService.create(kbName, doc, author);
            })
            .then(importAll(kbName, parentName, rest, order, author).map(n -> n + 1));
    }

    private Mono<String> readFilePart(FilePart part) {
        return DataBufferUtils.join(part.content())
            .map(buffer -> {
                var bytes = new byte[buffer.readableByteCount()];
                buffer.read(bytes);
                DataBufferUtils.release(buffer);
                var text = new String(bytes, StandardCharsets.UTF_8);
                // 剥离 UTF-8 BOM：否则首个标题前缀该不可见字符，导致 # 不被识别为标题
                if (text.startsWith("\uFEFF")) {
                    text = text.substring(1);
                }
                return text;
            });
    }

    private String titleOfFile(String filename) {
        if (!StringUtils.hasText(filename)) {
            return "未命名文档";
        }
        String base = filename;
        int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        if (base.toLowerCase().endsWith(".md")) {
            base = base.substring(0, base.length() - 3);
        }
        return StringUtils.hasText(base) ? base : "未命名文档";
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
            .flatMap(move -> docService.move(kbName, docName, move.parentName(), move.priority(),
                move.beforeName(), move.afterName()))
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
    public record MoveDocRequest(String parentName, Integer priority, String beforeName,
        String afterName) {
    }
}
