package cn.minims.minidocs.finder;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import run.halo.app.extension.ListResult;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.app.theme.finders.Finder;
import cn.minims.minidocs.extension.KnowledgeBase;
import cn.minims.minidocs.extension.KnowledgeBaseDoc;
import cn.minims.minidocs.service.KnowledgeBaseDocService;
import cn.minims.minidocs.service.KnowledgeBaseDocService.DocTreeNode;
import cn.minims.minidocs.service.KnowledgeBaseService;
import cn.minims.minidocs.setting.BasicSetting;

/**
 * 主题 Finder API，模板变量：{@code ${minidocsFinder}}。
 *
 * <p>服务端可见性边界（资源级）：
 * <ul>
 *   <li>公开知识库（publicVisible=true）对所有人可见，但匿名访问受 {@code allowAnonymousRead}
 *       设置约束（Finder 与 {@code KnowledgeBaseRouter}、公共 REST 端点保持一致）；</li>
 *   <li>私有知识库仅其创建者、成员或具备知识库管理权限的用户可见（登录成员在主题列表亦可看到），
 *       其余一律拒绝，杜绝“主题传入私有知识库标识后取得文档正文”的越权读取；</li>
 *   <li>文档均只暴露已发布（published）状态。</li>
 * </ul>
 *
 * @author Cosolar
 */
@Component
@Finder("minidocsFinder")
@RequiredArgsConstructor
public class MinidocsFinder {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseDocService docService;
    private final ReactiveSettingFetcher settingFetcher;

    /**
     * 当前用户可访问的知识库分页列表（按最近更新倒序）。
     * <p>登录用户可看到公开知识库，以及自己作为创建者/成员（或具备管理权限）的私有知识库；
     * 未登录用户仅能看到公开知识库，且受 {@code allowAnonymousRead} 设置约束
     * （站点关闭匿名阅读时匿名请求返回空）。
     */
    public Mono<ListResult<KnowledgeBase>> listKnowledgeBases(int page, int size) {
        return currentUserAuthorized().flatMap(ok -> ok
            ? knowledgeBaseService.listAccessible(page, size)
            : Mono.just(ListResult.emptyResult()));
    }

    /**
     * 当前请求是否获准浏览公开知识库（登录用户放行；匿名需管理员开启匿名阅读）。
     */
    private Mono<Boolean> currentUserAuthorized() {
        return knowledgeBaseService.currentAccess()
            .flatMap(access -> {
                if (StringUtils.hasText(access.username()) || access.manage()) {
                    return Mono.just(true);
                }
                return anonymousReadEnabled();
            });
    }

    /**
     * 知识库详情；不可访问（非公开、或私有但非成员/创建者/管理）时返回空。
     */
    public Mono<KnowledgeBase> getKnowledgeBase(String kbSlug) {
        return resolveAccessible(kbSlug)
            .flatMap(kb -> canAccess(kb)
                .map(ok -> ok ? kb : null));
    }

    /**
     * 知识库下已发布文档分页列表（知识库不可访问时返回空结果）。
     */
    public Mono<ListResult<KnowledgeBaseDoc>> listDocs(String kbSlug, int page, int size) {
        return resolveAccessible(kbSlug)
            .flatMap(kb -> canAccess(kb)
                .flatMap(ok -> ok
                    ? docService.list(kb.getMetadata().getName(), null,
                        KnowledgeBaseDocService.PHASE_PUBLISHED, page, size)
                    : Mono.just(ListResult.emptyResult())));
    }

    /**
     * 已发布文档详情（所属知识库必须可访问）。
     */
    public Mono<KnowledgeBaseDoc> getDocBySlug(String kbSlug, String docSlug) {
        return resolveAccessible(kbSlug)
            .flatMap(kb -> canAccess(kb)
                .flatMap(ok -> ok
                    ? docService.getPublishedDocBySlug(kb.getMetadata().getName(), docSlug)
                    : Mono.empty()));
    }

    /**
     * 已发布文档树（所属知识库必须可访问）。
     */
    public Mono<List<DocTreeNode>> getDocTree(String kbSlug) {
        return resolveAccessible(kbSlug)
            .flatMap(kb -> canAccess(kb)
                .flatMap(ok -> ok
                    ? docService.buildTree(kb.getMetadata().getName(),
                        KnowledgeBaseDocService.PHASE_PUBLISHED)
                    : Mono.just(List.<DocTreeNode>of())));
    }

    /**
     * 按 slug 查询已发布文档（所属知识库必须可访问）。
     */
    public Mono<KnowledgeBaseDoc> getDocBySlug(String slug) {
        return docService.getPublishedDocBySlug(slug)
            .flatMap(doc -> resolveAccessible(doc.getSpec().getKnowledgeBaseName())
                .flatMap(kb -> canAccess(kb)
                    .map(ok -> ok ? doc : null)));
    }

    /**
     * 按 slug 或 metadata.name 解析知识库；不存在或不可访问时返回空。
     */
    private Mono<KnowledgeBase> resolveAccessible(String kbSlug) {
        return knowledgeBaseService.getBySlugOrName(kbSlug)
            .onErrorResume(e -> Mono.empty());
    }

    /**
     * 对当前用户做资源级可见性判定。
     * <p>已登录用户：公开知识库放行，私有知识库仅创建者/成员/管理权限者可见。
     * <p>匿名用户：仅公开知识库可见，且受 {@code allowAnonymousRead} 设置约束
     * （与公共 REST 端点保持一致）；站点关闭匿名阅读时，匿名请求一律拒绝。
     * <p>注意私有知识库在任一情况下均须满足创建者/成员/管理身份，杜绝“主题传入私有
     * 知识库标识后取得已发布文档及正文”的越权读取。
     */
    private Mono<Boolean> canAccess(KnowledgeBase kb) {
        return knowledgeBaseService.currentAccess()
            .flatMap(access -> {
                // 已登录用户（或具备管理权限）：按公开/私有+成员规则判定
                if (StringUtils.hasText(access.username()) || access.manage()) {
                    return Mono.just(KnowledgeBaseService.canAccess(kb, access.username(),
                        access.manage()));
                }
                // 匿名用户：非公开知识库拒绝；公开知识库还需管理员开启匿名阅读
                if (!Boolean.TRUE.equals(kb.getSpec().getPublicVisible())) {
                    return Mono.just(false);
                }
                return anonymousReadEnabled();
            });
    }

    /**
     * 站点是否允许未登录用户阅读公开知识库（默认允许）。
     */
    private Mono<Boolean> anonymousReadEnabled() {
        return settingFetcher.fetch("basic", BasicSetting.class)
            .map(BasicSetting::anonymousReadEnabled)
            .defaultIfEmpty(true);
    }
}