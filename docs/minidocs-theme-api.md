# 主题 API 文档

本文档介绍 MiniDocs 插件为主题端（Halo Java 模板，Thymeleaf / FreeMarker）提供的 Finder API 与类型定义。若使用前端框架做客户端渲染，可直接调用匿名公共 API，端点列表请参考 [REST API 文档](./minidocs-rest-api.md)。

## 路由

MiniDocs 内置了一组前台主题模板路由（由 `KnowledgeBaseRouter` 以 `@Component` + `@Bean RouterFunction` 注册，Halo 自动收集），主题可选择性用同名模板覆盖；未提供时使用插件内置默认模板 `docs.html` / `doc.html` / `doc_share.html`：

| 路由 | 渲染模板 | 说明 |
| --- | --- | --- |
| `/docs` | `docs.html` | 文档列表页（模板通过 `minidocsFinder` 自取公开知识库与文档） |
| `/docs/view/{kbSlug}` | `doc.html` | 知识库阅读页；`kbSlug` 支持知识库 `metadata.name` 或 `spec.slug` |
| `/docs/share/{kbSlug}` | `doc_share.html` | 知识库分享页（左侧文档树、中间阅读区、右侧大纲）；可选 `?docSlug=` 直接定位文档 |

> 服务端仅做公开性校验，不向模板塞业务数据；知识库 / 文档树 / 文档数据由模板通过 `minidocsFinder` 自行查询。非公开知识库访问返回 `404`。

若主题希望自定义页面结构，也可不依赖内置路由，自行在 Halo 后台创建页面并选择主题自定义模板，用页面 slug 或主题设置传入知识库 `name` / `slug`，再在模板中通过 `minidocsFinder` 取数渲染。`minidocsFinder` 还可用于任意主题模板位置（如全局侧边栏 / 页脚），渲染「全站知识库入口」或「文档页脚导航」。

> 无论采用何种路由，Finder 变量 `${minidocsFinder}` 都由插件在 Halo 渲染上下文自动注入，**只要插件已启用即可直接使用**，无需主题额外声明。

## Finder API

### minidocsFinder

`minidocsFinder` 对应当前实现中的 `@Finder("minidocsFinder")`，用于查询公开知识库及其已发布文档。返回类型为 `reactor.core.publisher.Mono<T>`，Halo 模板引擎会自动订阅并解包，在模板中直接作为普通对象 / 集合使用即可，无需（也不能）手动 `block()`。

所有方法仅返回 `publicVisible=true` 的知识库与 `phase=published` 的文档；当插件设置关闭「允许未登录用户阅读」且用户未登录时，访问会触发 `403`，与公共 REST API 一致。

#### minidocsFinder.listKnowledgeBases(page, size)

分页列出公开知识库。

**参数**：

| 参数 | 说明 |
| --- | --- |
| `page` | 页码，从 `1` 开始 |
| `size` | 每页条数 |

**返回值**：`ListResult<KnowledgeBase>`

**示例**：

```html
<ul>
  <li th:each="kb : ${minidocsFinder.listKnowledgeBases(1, 10).items}">
    <a th:href="'/docs/view/' + ${kb.spec.slug}" th:text="${kb.spec.displayName}"></a>
    <small th:text="${kb.status.docCount} + ' 篇'"></small>
  </li>
</ul>
```

#### minidocsFinder.getKnowledgeBase(kbSlug)

获取单个公开知识库详情；非公开知识库返回空。

**参数**：

| 参数 | 说明 |
| --- | --- |
| `kbSlug` | 知识库标识，支持 `metadata.name` 或 `spec.slug` |

**返回值**：`KnowledgeBase`（非公开 / 不存在时为空）

**示例**：

```html
<div th:with="kb = ${minidocsFinder.getKnowledgeBase(kbSlug)}" th:if="${kb != null}">
  <h1 th:text="${kb.spec.displayName}"></h1>
  <p th:text="${kb.spec.description}"></p>
</div>
```

#### minidocsFinder.listDocs(kbSlug, page, size)

分页列出知识库下已发布文档。

**参数**：

| 参数 | 说明 |
| --- | --- |
| `kbSlug` | 知识库标识，支持 `metadata.name` 或 `spec.slug` |
| `page` | 页码，从 `1` 开始 |
| `size` | 每页条数 |

**返回值**：`ListResult<KnowledgeBaseDoc>`

**示例**：

```html
<ul>
  <li th:each="doc : ${minidocsFinder.listDocs(kbSlug, 1, 20).items}">
    <a th:href="'/docs/view/' + ${kbSlug} + '?docSlug=' + ${doc.spec.slug}" th:text="${doc.spec.title}"></a>
    <small th:text="${doc.spec.summary}"></small>
  </li>
</ul>
```

#### minidocsFinder.getDoc(kbSlug, docSlug)

获取单篇已发布文档（按文档 `spec.slug` 取，并校验归属该知识库）。

**参数**：

| 参数 | 说明 |
| --- | --- |
| `kbSlug` | 知识库标识，支持 `metadata.name` 或 `spec.slug` |
| `docSlug` | 文档的 `spec.slug`（URL 友好标识） |

**返回值**：`KnowledgeBaseDoc`（不存在时为空）

**示例**：

```html
<div th:with="doc = ${minidocsFinder.getDoc(kbSlug, docSlug)}" th:if="${doc != null}">
  <h1 th:text="${doc.spec.title}"></h1>
</div>
```

> 公共 API 统一使用 slug 字段查询：知识库标识为 `kbSlug`，文档标识为 `docSlug`。

#### minidocsFinder.getDocTree(kbSlug)

获取该知识库已发布文档的文档树（递归嵌套）。

**参数**：

| 参数 | 说明 |
| --- | --- |
| `kbSlug` | 知识库标识，支持 `metadata.name` 或 `spec.slug` |

**返回值**：`List<DocTreeNode>`

文档按 `spec.priority`、`metadata.name` 升序；`children` 为子节点列表，可递归渲染。适合侧边栏导航。

**示例**：

```html
<nav>
  <ul>
    <li th:each="node : ${minidocsFinder.getDocTree(kbSlug)}">
      <a th:href="'/docs/view/' + ${kbSlug} + '?docSlug=' + ${node.slug}" th:text="${node.title}"></a>
      <ul th:if="${node.children != null and !node.children.isEmpty()}">
        <li th:each="child : ${node.children}">
          <a th:href="'/docs/view/' + ${kbSlug} + '?docSlug=' + ${child.slug}" th:text="${child.title}"></a>
        </li>
      </ul>
    </li>
  </ul>
</nav>
```

> 多级文档树建议在主题后端（自定义 TemplateModel / Spring Bean）递归展平为一维列表再传入模板，或在前端配合公共 REST 的 `/tree` 接口渲染。Finder 的 `getDocTree` 更适用于 1~2 级深度的静态展示。

#### minidocsFinder.getDocBySlug(docSlug)

按文档 `slug` 查询已发布文档（所属知识库须公开）。适合「`/docs/view/{kbSlug}?docSlug={docSlug}`」这类可读 URL 的详情页。

**参数**：

| 参数 | 说明 |
| --- | --- |
| `docSlug` | 文档的 `spec.slug` |

**返回值**：`KnowledgeBaseDoc`（不存在时为空）

**示例**：

```html
<article th:with="doc = ${minidocsFinder.getDocBySlug(docSlug)}" th:if="${doc != null}">
  <h1 th:text="${doc.spec.title}"></h1>
  <div id="doc-body" th:attr="data-md=${doc.spec.raw}"></div>
</article>
```

> `docSlug` 变量由你的主题通过「页面 slug 约定」或「主题设置」传入模板（与内置路由 `/docs/view/{kbSlug}?docSlug=` 中的 `docSlug` 一致）。文档 `spec.raw` 为**原始 Markdown 文本**，需主题自行渲染（参考下方「Markdown 渲染」）。若希望直接输出已渲染 HTML，可直接使用文档的 `spec.content` 字段（编辑时由前端 Markdown 编辑器生成的 HTML）。

> 取单篇文档有两种方式：
> - `getDoc(kbSlug, docSlug)`：按文档的 `spec.slug` 取（URL 友好，并校验归属该知识库）。
> - `getDocBySlug(docSlug)`：按文档的 `spec.slug` 取（URL 友好，推荐在前台链接 / 详情页中使用）。
>
> 两者均要求所属知识库 `publicVisible=true` 且文档 `phase=published`，统一使用 slug 字段查询。

## Markdown 渲染

`minidocsFinder` 返回的文档 `spec.raw` 是原始 Markdown。主题中常用两种渲染方式：

### 前端脚本渲染（轻量）

把原始 Markdown 以 JSON 安全方式注入 `<script>`，再用 `marked` + `highlight.js` 渲染：

```html
<div id="doc-html" class="markdown-body"></div>
<script th:inline="javascript">
  /*<![CDATA[*/
  const DOC_MD = /*[[${doc.spec.raw}]]*/ '';
  /*]]>*/
</script>
<script type="module">
  import { marked } from 'https://esm.sh/marked';
  document.getElementById('doc-html').innerHTML = marked.parse(DOC_MD);
</script>
```

### 主题侧渲染 Bean（服务端）

实现并注册一个 Spring Bean，供 Thymeleaf 通过 `${@beanName.method(...)}` 调用：

```java
@Component("markdownRender")
public class MarkdownRender {
    public String render(String markdown) {
        return CommonmarkRenderer.render(markdown); // 使用 commonmark / flexmark 等
    }
}
```

```html
<div class="markdown-body" th:utext="${@markdownRender.render(doc.spec.raw)}"></div>
```

> 注意用 `th:utext`（不转义）输出 HTML。

## 公共 REST API

如果主题使用前端框架进行客户端渲染，可以直接调用匿名公共 API（如文档树、按 slug 获取文档）。端点列表与匿名访问规则请参考 [REST API 文档](./minidocs-rest-api.md)。

## 类型定义

### KnowledgeBase

```json
{
  "metadata": {
    "name": "kb-abc",
    "creationTimestamp": "2026-08-01T10:00:00Z"
  },
  "spec": {
    "displayName": "产品手册",
    "description": "公司内部产品文档",
    "logo": "https://example.com/logo.png",
    "publicVisible": true,
    "members": ["alice", "bob"],
    "tags": ["产品", "对外"],
    "priority": 0,
    "creatorName": "admin",
    "cover": "https://example.com/cover.png",
    "creationTime": "2026-08-01T10:00:00Z",
    "updateTime": "2026-08-20T09:00:00Z"
  },
  "status": {
    "docCount": 32,
    "lastPublishTime": "2026-08-25T12:00:00Z",
    "kbGrowth": 3,
    "docGrowth": 12
  }
}
```

`status` 为观测状态，由插件异步维护，仅供参考。公开接口仅返回 `publicVisible=true` 的知识库；Finder 同样遵循此规则，非公开知识库 `getKnowledgeBase` 返回空。

### KnowledgeBaseDoc

```json
{
  "metadata": {
    "name": "doc-xyz",
    "creationTimestamp": "2026-08-10T08:00:00Z"
  },
  "spec": {
    "knowledgeBaseName": "kb-abc",
    "title": "快速开始",
    "slug": "quick-start",
    "author": "alice",
    "cover": "https://example.com/doc.png",
    "summary": "本文介绍如何快速上手。",
    "creationTime": "2026-08-10T08:00:00Z",
    "updateTime": "2026-08-22T10:00:00Z",
    "raw": "# 快速开始\n...Markdown 原文...",
    "content": "<h1>快速开始</h1>...前端编辑器生成的 HTML...",
    "parentName": null,
    "priority": 0,
    "tags": ["入门"],
    "phase": "published",
    "publishTime": "2026-08-22T10:00:00Z"
  }
}
```

> `raw` 为原始 Markdown（供主题自行渲染或编辑器使用），`content` 为渲染后的 HTML（编辑时由前端 Markdown 编辑器生成并保存，主题可直接 `th:utext` 输出）。`phase` 取值：`draft`、`published`。

### DocTreeNode

```json
{
  "name": "doc-1",
  "title": "入门",
  "slug": "guide",
  "phase": "published",
  "priority": 0,
  "parentName": null,
  "publishTime": "2026-08-20T10:00:00Z",
  "children": [
    {
      "name": "doc-2",
      "title": "安装",
      "slug": "install",
      "phase": "published",
      "priority": 0,
      "parentName": "doc-1",
      "publishTime": "2026-08-21T10:00:00Z",
      "children": []
    }
  ]
}
```

### ListResult\<KnowledgeBase\> / ListResult\<KnowledgeBaseDoc\>

```json
{
  "page": 1,
  "size": 20,
  "total": 42,
  "items": [],
  "first": true,
  "last": false,
  "hasNext": true,
  "hasPrevious": false,
  "totalPages": 3
}
```

分页接口统一返回该结构，主题可使用 `total`、`page`、`size`、`totalPages` 构建分页器。

---

## 注意事项

1. **变量名固定**：模板中必须使用 `${minidocsFinder}`，对应插件 `@Finder("minidocsFinder")`，不要猜测其他名字。插件已内置 `/docs`、`/docs/view/{kbSlug}`、`/docs/share/{kbSlug}` 路由与默认模板，主题可用同名模板覆盖，或通过 Finder 自行在任意位置取数渲染。
2. **仅公开数据**：Finder 只返回 `publicVisible=true` 的知识库与 `phase=published` 的文档，与公共 REST 接口一致；私有内容需登录后走 Console API / 标准 CRUD（需「知识库管理」角色）。
3. **匿名开关**：`allowAnonymousRead=false` 时匿名访问返回 `403`；若公开页面向游客开放，提醒站点管理员开启该设置（插件设置 → 基础设置）。
4. **空值保护**：`getKnowledgeBase` / `getDoc` / `getDocBySlug` 在非公开、不存在时返回空，模板中务必用 `th:if="${xxx != null}"` 判断后再渲染，避免异常。
5. **排序**：知识库与文档均按 `spec.priority` 升序返回，文档树亦同。
6. **不要在 Finder 调用里做写操作**：Finder 仅用于读取展示；创建 / 编辑 / 发布等写操作请使用 Console API 或标准 CRUD 端点（需认证与相应角色）。
