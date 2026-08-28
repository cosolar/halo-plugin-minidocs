package cn.minims.minidocs.route;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RouterFunctions.route;

import java.util.HashMap;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import run.halo.app.plugin.ReactiveSettingFetcher;
import run.halo.app.theme.TemplateNameResolver;
import cn.minims.minidocs.extension.KnowledgeBase;
import cn.minims.minidocs.extension.KnowledgeBaseDoc;
import cn.minims.minidocs.service.KnowledgeBaseDocService;
import cn.minims.minidocs.service.KnowledgeBaseService;
import cn.minims.minidocs.setting.BasicSetting;

/**
 * 知识库前台模板路由。
 *
 * <p>以 {@code @Component} + {@code @Bean RouterFunction} 方式注册前台模板路由，
 * 由 Halo 自动收集。提供以下路由：
 * <ul>
 *   <li>{@code /docs/share/{shareToken}} 知识库外链分享页（与阅读页布局一致，无需登录），
 *       支持可选密码（GET 查询参数 {@code ?password=}）与有效期。</li>
 *   <li>{@code /docs/view/{kbSlug}} 知识库阅读页，渲染 doc.html。</li>
 *   <li>{@code /docs} 文档列表页（模板通过 minidocsFinder 自取公开知识库），渲染 docs.html。</li>
 * </ul>
 *
 * <p><b>阅读页/列表页</b>走常规授权大门（公开知识库按匿名阅读开关、私有知识库按
 * 创建者/成员/管理权限），业务数据由模板通过 {@code minidocsFinder} 自取。
 * <p><b>分享页</b>不走常规权限：只要持有有效外链（{@code shareToken}），即便是不公开的
 * 私有知识库也无需登录即可查看，仅受分享自身的「开启状态、有效期、访问密码」约束。
 *
 * @author Cosolar
 */
@Component
@RequiredArgsConstructor
public class KnowledgeBaseRouter {

    private final KnowledgeBaseService knowledgeBaseService;
    private final KnowledgeBaseDocService docService;
    private final TemplateNameResolver templateNameResolver;
    private final ReactiveSettingFetcher settingFetcher;

    @Bean
    RouterFunction<ServerResponse> knowledgeBaseShareRoute() {
        return route(GET("/docs/share/{shareToken}"), this::handleShare)
            .andRoute(GET("/docs/view/{kbSlug}"), this::handleView)
            .andRoute(GET("/docs"), this::handleDocsList);
    }

    /**
     * 外链分享页：按 shareToken 解析知识库并做分享级校验（开启、未过期、密码匹配）。
     * 校验通过则渲染 doc_share.html（完整阅读页），否则渲染密码门或 404。
     *
     * <p>密码经 GET 查询参数提交（{@code ?password=xxx}）：Halo 对前台路由的 POST 有
     * CSRF 保护，匿名访客无法携带 token，故不走 POST 校验端点；密码匹配后下发
     * HttpOnly 访问 cookie，后续访问不再需要密码。
     */
    private Mono<ServerResponse> handleShare(ServerRequest request) {
        var token = request.pathVariable("shareToken");
        var docSlug = request.queryParam("docSlug")
            .filter(s -> !s.isBlank()).orElse(null);

        return knowledgeBaseService.findByShareToken(token)
            .flatMap(kb -> {
                var state = KnowledgeBaseService.shareState(kb);
                if (!state.enabled() || state.expired()) {
                    return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "分享链接不存在或已失效"));
                }
                String setCookie = null;
                if (state.passwordRequired()) {
                    var cookieVal = shareCookie(request, token);
                    if (!KnowledgeBaseService.shareCookieMatches(kb, cookieVal)) {
                        var inputPwd = request.queryParam("password").orElse(null);
                        if (inputPwd == null
                            || !KnowledgeBaseService.verifySharePassword(kb, inputPwd)) {
                            return renderShareGate(request, token, inputPwd != null);
                        }
                        setCookie = buildShareCookie(kb, token);
                    }
                }
                var kbName = kb.getMetadata().getName();
                var finalSetCookie = setCookie;
                return docService.buildTree(kbName, KnowledgeBaseDocService.PHASE_PUBLISHED)
                    .flatMap(tree -> {
                        var effective = StringUtils.hasText(docSlug) ? docSlug
                            : firstTreeSlug(tree);
                        if (!StringUtils.hasText(effective)) {
                            return Mono.just(new ResolvedShare(tree, null, null));
                        }
                        return docService.getPublishedDocBySlug(kbName, effective)
                            .map(doc -> new ResolvedShare(tree, doc.getSpec().getSlug(), doc))
                            .onErrorResume(e -> Mono.just(
                                new ResolvedShare(tree, effective, null)));
                    })
                    .flatMap(r -> knowledgeBaseService
                        .incrementAccess(kbName)
                        .then(renderSharePage(request, token, kb, r, finalSetCookie)));
            })
            .onErrorResume(ResponseStatusException.class, Mono::error)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                "分享链接不存在或已失效")));
    }

    /**
     * 阅读页 /docs/view/{kbSlug}（常规权限大门）。
     */
    private Mono<ServerResponse> handleView(ServerRequest request) {
        var kbSlug = request.pathVariable("kbSlug");
        var docSlug = request.queryParam("docSlug")
            .filter(s -> !s.isBlank()).orElse(null);

        return resolveLoginUser(request)
            .flatMap(loginUser -> knowledgeBaseService.getBySlugOrName(kbSlug)
                .flatMap(kb -> authorizeKb(kb, loginUser).flatMap(ok -> {
                    if (!ok) {
                        return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "知识库不存在或无权访问: " + kbSlug));
                    }
                    return knowledgeBaseService.incrementAccess(kb.getMetadata().getName())
                        .then(renderView(request, "doc", kbSlug, docSlug, loginUser));
                })))
            .onErrorResume(ResponseStatusException.class, Mono::error)
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND,
                "知识库不存在或无权访问: " + kbSlug)));
    }

    /**
     * 文档列表页 /docs。
     */
    private Mono<ServerResponse> handleDocsList(ServerRequest request) {
        return resolveLoginUser(request)
            .flatMap(loginUser -> anonymousReadEnabled()
                .flatMap(anon -> {
                    if (!anon && loginUser.isEmpty()) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                            "站点未开放匿名阅读，请登录后访问"));
                    }
                    return resolveTemplate(request, "docs").flatMap(t -> {
                        var model = new HashMap<String, Object>();
                        model.put("loginUser", loginUser);
                        return ServerResponse.ok().render(t, model);
                    });
                }));
    }

    // ---------------- 渲染辅助 ----------------

    private Mono<ServerResponse> renderSharePage(ServerRequest request, String token,
        KnowledgeBase kb, ResolvedShare r, String setCookie) {
        return resolveTemplate(request, "doc_share").flatMap(t -> {
            var model = new HashMap<String, Object>();
            model.put("shareToken", token);
            model.put("knowledgeBase", kb);
            model.put("docTree", r.tree());
            model.put("docSlug", r.docSlug());
            model.put("doc", r.doc());
            model.put("gate", false);
            var builder = ServerResponse.ok();
            if (setCookie != null) {
                builder = builder.header("Set-Cookie", setCookie);
            }
            return builder.render(t, model);
        });
    }

    private Mono<ServerResponse> renderShareGate(ServerRequest request, String token,
        boolean passwordError) {
        return resolveTemplate(request, "doc_share").flatMap(t -> {
            var model = new HashMap<String, Object>();
            model.put("shareToken", token);
            model.put("gate", true);
            model.put("gateError", passwordError);
            return ServerResponse.ok().render(t, model);
        });
    }

    private Mono<ServerResponse> renderView(ServerRequest request, String template,
        String kbSlug, String docSlug, String loginUser) {
        return resolveTemplate(request, template).flatMap(t -> {
            var model = new HashMap<String, Object>();
            model.put("kbSlug", kbSlug);
            if (docSlug != null) {
                model.put("docSlug", docSlug);
            }
            model.put("loginUser", loginUser);
            return ServerResponse.ok().render(t, model);
        });
    }

    private Mono<String> resolveTemplate(ServerRequest request, String template) {
        return templateNameResolver.resolveTemplateNameOrDefault(request.exchange(), template);
    }

    // ---------------- 数据/校验辅助 ----------------

    private record ResolvedShare(List<KnowledgeBaseDocService.DocTreeNode> tree,
                                 String docSlug, KnowledgeBaseDoc doc) {
    }

    private String firstTreeSlug(List<KnowledgeBaseDocService.DocTreeNode> tree) {
        if (tree == null || tree.isEmpty()) {
            return null;
        }
        return tree.get(0).getSlug();
    }

    private String shareCookie(ServerRequest request, String token) {
        var list = request.cookies().get(KnowledgeBaseService.shareCookieName(token));
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0).getValue();
    }

    /**
     * 构建分享访问 cookie：有效期取「分享剩余时长」与 30 天中的较小值，永久分享按 30 天。
     */
    private String buildShareCookie(KnowledgeBase kb, String token) {
        long maxAge = 30L * 24 * 60 * 60;
        var expiresAt = kb.getSpec().getShareExpiresAt();
        if (expiresAt != null) {
            long remain = java.time.Duration.between(java.time.Instant.now(), expiresAt)
                .getSeconds();
            if (remain > 60) {
                maxAge = Math.min(remain, maxAge);
            }
        }
        return KnowledgeBaseService.shareCookieName(token) + "="
            + KnowledgeBaseService.encodeShareCookie(kb.getSpec().getSharePassword())
            + "; Path=/; HttpOnly; SameSite=Lax; Max-Age=" + maxAge;
    }

    /**
     * 对某个知识库做前台阅读授权。
     * <p>公开：匿名阅读开关开启或已登录则放行；私有：仅创建者/成员/管理权限可放行。
     */
    private Mono<Boolean> authorizeKb(KnowledgeBase kb, String loginUser) {
        if (Boolean.TRUE.equals(kb.getSpec().getPublicVisible())) {
            return anonymousReadEnabled()
                .map(anon -> anon || !loginUser.isEmpty());
        }
        if (loginUser.isEmpty()) {
            return Mono.just(false);
        }
        return knowledgeBaseService.hasManagePermission()
            .map(manage -> KnowledgeBaseService.canAccess(kb, loginUser, manage));
    }

    private Mono<Boolean> anonymousReadEnabled() {
        return settingFetcher.fetch("basic", BasicSetting.class)
            .map(BasicSetting::anonymousReadEnabled)
            .defaultIfEmpty(true);
    }

    /**
     * 当前登录用户名（未登录返回空字符串）。
     */
    private Mono<String> resolveLoginUser(ServerRequest request) {
        return request.principal()
            .map(java.security.Principal::getName)
            .map(n -> "anonymousUser".equals(n) ? "" : n)
            .defaultIfEmpty("");
    }
}