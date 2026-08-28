package run.halo.plugin.minidocs.finder;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;
import run.halo.app.theme.finders.Finder;
import run.halo.plugin.minidocs.extension.KnowledgeBase;
import run.halo.plugin.minidocs.extension.KnowledgeBaseDoc;
import run.halo.plugin.minidocs.service.KnowledgeBaseDocService;
import run.halo.plugin.minidocs.service.KnowledgeBaseDocService.DocTreeNode;
import run.halo.plugin.minidocs.service.KnowledgeBaseService;

/**
 * 主题 Finder API，模板变量：{@code ${minidocsFinder}}。
 *
 * <p>仅暴露公开知识库（publicVisible=true）及其已发布文档。
 *
 * @author Cosolar
 */
@Component
@Finder("minidocsFinder")
@RequiredArgsConstructor
public class MinidocsFinder {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseDocService docService;

    /**
     * 公开知识库分页列表。
     */
    public Mono<ListResult<KnowledgeBase>> listKnowledgeBases(int page, int size) {
        return knowledgeBaseService.listPublic(null, page, size);
    }

    /**
     * 公开知识库详情；非公开知识库返回空。
     */
    public Mono<KnowledgeBase> getKnowledgeBase(String kbSlug) {
        return knowledgeBaseService.getBySlugOrName(kbSlug)
            .filter(kb -> Boolean.TRUE.equals(kb.getSpec().getPublicVisible()));
    }

    /**
     * 知识库下已发布文档分页列表。
     */
    public Mono<ListResult<KnowledgeBaseDoc>> listDocs(String kbSlug, int page, int size) {
        return knowledgeBaseService.getBySlugOrName(kbSlug)
            .flatMap(kb -> docService.list(kb.getMetadata().getName(), null,
                KnowledgeBaseDocService.PHASE_PUBLISHED, page, size));
    }

    /**
     * 已发布文档详情。
     */
    public Mono<KnowledgeBaseDoc> getDoc(String kbSlug, String docName) {
        return knowledgeBaseService.getBySlugOrName(kbSlug)
            .flatMap(kb -> docService.getPublishedDoc(kb.getMetadata().getName(), docName));
    }

    /**
     * 已发布文档树。
     */
    public Mono<List<DocTreeNode>> getDocTree(String kbSlug) {
        return knowledgeBaseService.getBySlugOrName(kbSlug)
            .flatMap(kb -> docService.buildTree(kb.getMetadata().getName(),
                KnowledgeBaseDocService.PHASE_PUBLISHED));
    }

    /**
     * 按 slug 查询已发布文档（所属知识库必须公开）。
     */
    public Mono<KnowledgeBaseDoc> getDocBySlug(String slug) {
        return docService.getPublishedDocBySlug(slug)
            .flatMap(doc -> getKnowledgeBase(doc.getSpec().getKnowledgeBaseName())
                .thenReturn(doc));
    }
}
