# REST API 文档

本文档介绍 MiniDocs 插件提供的 REST API，包括公共 API、Console API 和 Halo 标准 Extension CRUD 端点。

插件 API 组：

- 公共 API：`api.minidocs.halo.run/v1alpha1`
- Console API：`console.api.minidocs.halo.run/v1alpha1`
- 标准 CRUD：`minidocs.halo.run/v1alpha1`

## 公共 API（匿名可访问）

此插件提供了一组位于 `api.minidocs.halo.run/v1alpha1` 的公共只读 JSON API，用于查询公开知识库及其已发布文档，可供主题前端、小程序或服务端集成。

### 端点列表

| 端点 | 方法 | 说明 |
| --- | --- | --- |
| `/apis/api.minidocs.halo.run/v1alpha1/knowledgebases` | `GET` | 分页列出公开知识库；支持 `keyword`、`page`、`size` 查询参数 |
| `/apis/api.minidocs.halo.run/v1alpha1/knowledgebases/{name}` | `GET` | 获取单个公开知识库详情；非公开知识库返回 `404` |
| `/apis/api.minidocs.halo.run/v1alpha1/knowledgebases/{name}/tree` | `GET` | 获取该知识库已发布文档的文档树（递归嵌套） |
| `/apis/api.minidocs.halo.run/v1alpha1/knowledgebases/{name}/docs` | `GET` | 分页列出该知识库已发布文档；支持 `keyword`、`page`、`size` |
| `/apis/api.minidocs.halo.run/v1alpha1/knowledgebases/{name}/docs/{docName}` | `GET` | 获取单篇已发布文档 |
| `/apis/api.minidocs.halo.run/v1alpha1/docs/{slug}` | `GET` | 按 slug 直接获取已发布文档（所属知识库须公开） |

> 路径中的 `{name}` 为知识库的 `metadata.name`；`{docName}` 为文档的 `metadata.name`；`{slug}` 为文档的 `spec.slug`。

### 匿名访问说明

公共 API 仅暴露 `publicVisible=true` 的知识库及其 `phase=published` 的文档。是否允许匿名读取由插件基础设置项 `allowAnonymousRead`（`settings.yaml` 中 `basic.allowAnonymousRead`）控制，由 `BasicSetting.anonymousReadEnabled()` 方法判断（字段为 `null` 时兜底为开启）：

- 设置为 `true`（或字段缺失为空）时，匿名用户可直接访问上述接口。
- 设置为 `false` 时，未登录访问会被服务层二次校验拦截并返回 `403`，已登录用户仍可正常访问。

> 注意：`settings.yaml` 中 `basic.allowAnonymousRead` 的默认值当前为 `false`（即默认关闭匿名阅读）；`anonymousReadEnabled()` 仅在字段为 `null` 时兜底为开启。站点管理员需在「插件设置 → 基础设置」中显式开启后方可对游客开放。

因此主题在调用公共 API 时应处理 `403`：引导用户登录，或提示站点管理员开启匿名阅读。查询接口只授予读取权限，不提供创建、修改或删除。

### 分页与排序说明

列表端点支持通过 `page`、`size` 查询参数分页，`page` 从 `1` 开始，默认 `size=20`。

排序字段为 `spec.priority`，值越小越靠前；知识库与文档均按 `priority`、创建时间、`metadata.name` 升序排列。公开 API 暂未开放 `sort` 参数自定义，统一按上述规则返回。

### 请求示例

```bash
# 列出公开知识库
curl "https://your-halo-site/apis/api.minidocs.halo.run/v1alpha1/knowledgebases?size=20"

# 获取单个知识库
curl "https://your-halo-site/apis/api.minidocs.halo.run/v1alpha1/knowledgebases/kb-abc"

# 获取文档树
curl "https://your-halo-site/apis/api.minidocs.halo.run/v1alpha1/knowledgebases/kb-abc/tree"

# 按 slug 获取文档
curl "https://your-halo-site/apis/api.minidocs.halo.run/v1alpha1/docs/quick-start"
```

## Console API（需要认证）

Console API 位于 `console.api.minidocs.halo.run/v1alpha1`，供 Console 前端与登录用户使用，需要登录认证，且受 Halo RBAC 角色（见下文）约束。

### 端点列表

#### 知识库（`KnowledgeBaseConsoleEndpoint`）

| 端点 | 方法 | 说明 |
| --- | --- | --- |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/stats` | `GET` | 聚合统计（总数、公开/私有数、文档数、月度环比 `kbGrowth`/`docGrowth`） |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases` | `GET` | 分页列出知识库；支持 `keyword`、`publicVisible`、`page`、`size` |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/{name}` | `GET` | 获取单个知识库（含私有） |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases` | `POST` | 创建知识库 |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/{name}` | `PUT` | 整体更新知识库 `spec` |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/{name}` | `DELETE` | 删除知识库（级联删除其下文档） |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/import` | `POST` | 批量导入整个知识库（multipart ZIP 上传，创建新知识库及其文档） |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/import/preview` | `POST` | 导入预览（multipart ZIP 上传，仅解析并返回待导入内容清单，不写入） |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/export` | `POST` | 导出整个知识库为 ZIP（受基础设置项 `allowDocExport` 约束） |

#### 文档（`KnowledgeBaseDocConsoleEndpoint`）

| 端点 | 方法 | 说明 |
| --- | --- | --- |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/{name}/docs` | `GET` | 分页列出文档；支持 `keyword`、`phase`、`page`、`size` |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/{name}/tree` | `GET` | 获取文档树（含草稿等全部状态） |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/{name}/docs/{docName}` | `GET` | 获取单篇文档 |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/{name}/docs` | `POST` | 创建文档 |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/{name}/docs/{docName}` | `PUT` | 整体更新文档 |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/{name}/docs/{docName}` | `DELETE` | 删除文档（级联删除子树） |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/{name}/docs/import` | `POST` | 批量导入（multipart 文件上传） |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/{name}/docs/{docName}/publish` | `POST` | 发布文档（`phase` → `published`） |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/{name}/docs/{docName}/move` | `POST` | 移动 / 排序文档 |
| `/apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/{name}/docs/{docName}/export` | `GET` | 导出 Markdown（受 `allowDocExport` 约束） |

### 角色与权限说明

插件在 `roleTemplate.yaml` 中内置角色模板，Console API 的访问受 Halo RBAC 控制：

| 角色模板 | 显示名 | 权限范围 |
| --- | --- | --- |
| `role-template-minidocs-view` | 知识库查看 | 对 `knowledgebases` / `knowledgebasedocs` 及其子资源的 `get` / `list` |
| `role-template-minidocs-manage` | 知识库管理 | 在 view 基础上增加 `create` / `update` / `patch` / `delete` 等写权限；依赖 view |
| `role-template-minidocs-anonymous` | （隐藏） | 聚合到 Halo 匿名用户，授权 `api.minidocs.halo.run` 公共 API 的 `get` / `list`（知识库、文档、文档树等只读），不出现在角色分配界面 |

匿名用户不会获得 view / manage 角色，但会被聚合授予 `role-template-minidocs-anonymous` 以访问公共 API；未登录访问 Console API 会被 Halo 网关拦截返回 `401` / `403`。主题若在已登录会话下调用写操作，需确保用户已被授予「知识库管理」角色，否则返回 `403`。

### 写操作请求体

创建 / 更新知识库（`POST` / `PUT /knowledgebases/{name}`）请求体为 `KnowledgeBase` JSON，`metadata.name` 创建时可省略，由服务端生成；更新时与路径 `{name}` 保持一致：

```json
{
  "spec": {
    "displayName": "我的知识库",
    "description": "由主题端创建的投稿知识库",
    "publicVisible": false,
    "tags": ["投稿"]
  }
}
```

创建文档（`POST /knowledgebases/{name}/docs`）请求体为 `KnowledgeBaseDoc` JSON，`spec.phase` 默认草稿（`draft`）：

```json
{
  "spec": {
    "title": "第一篇投稿",
    "slug": "my-first-post",
    "content": "# 标题\n正文内容…",
    "phase": "draft"
  }
}
```

移动 / 排序文档（`POST /docs/{docName}/move`）请求体字段均可选：

```json
{
  "parentName": "doc-parent",
  "priority": 2,
  "beforeName": null,
  "afterName": "doc-sibling"
}
```

- `parentName`：新父文档名（`null` / 缺省表示移到顶级）。
- `priority`：同级排序权重；同时传 `beforeName` / `afterName` 时按插入到目标之前 / 之后重排兄弟节点。
- 服务端校验不能移动到自身或其子文档下，否则返回 `400`。

### 导出说明

`export` 接口导出单篇文档的 Markdown。若插件设置「允许导出文档」（`allowDocExport`，由 `BasicSetting.docExportEnabled()` 判断，默认 `true`）关闭，单篇 `export` 接口与 Console 导出入口、以及知识库级 `/knowledgebases/export` 均返回 `403`。

## 标准 CRUD 端点（需要认证）

知识库与文档资源还可通过 Halo 标准 Extension CRUD 端点操作：

| 端点 | 说明 |
| --- | --- |
| `/apis/minidocs.halo.run/v1alpha1/knowledgebases` | `KnowledgeBase` 资源标准 CRUD |
| `/apis/minidocs.halo.run/v1alpha1/knowledgebasedocs` | `KnowledgeBaseDoc` 资源标准 CRUD |

标准 CRUD 端点同样受 Halo RBAC 控制，需要具有相应权限的已登录用户访问。

## 错误与状态码

| 状态码 | 含义 |
| --- | --- |
| `200` | 成功 |
| `201` | 创建成功（写操作） |
| `400` | 请求参数错误（如移动到非法父节点） |
| `401` / `403` | 未登录 / 无权限（匿名阅读关闭或未授予角色） |
| `404` | 知识库或文档不存在（含非公开知识库） |

Halo 使用 `application/problem+json` 返回结构化错误，客户端可读取 `status` 与 `type` 做程序判断，`detail` 作为可展示文案。

> 更多主题侧用法（模板变量与 Finder）请参考 [主题 API 文档](./minidocs-theme-api.md)。
