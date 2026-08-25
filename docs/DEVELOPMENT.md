# halo-plugin-minidocs 开发文档

> Halo 知识库插件：在 Halo 平台中搭建团队/个人知识库，提供多知识库管理、文档树、Markdown 编辑、分类标签与权限控制，并通过公开 API 与 Finder 能力支持第三方主题开发用户侧页面。

- 插件标识：`halo-plugin-minidocs`
- 目标平台：Halo `>= 2.26.0`
- 技术栈：Spring Boot + Spring WebFlux（Java 21）+ Vue 3 + TypeScript
- 构建：Gradle（含 `run.halo.plugin.devtools`）+ pnpm

---

## 目录

1. [项目概述](#1-项目概述)
2. [功能列表](#2-功能列表)
3. [数据模型设计](#3-数据模型设计)
4. [后端架构与开发细节](#4-后端架构与开发细节)
5. [权限与安全设计](#5-权限与安全设计)
6. [前端管理界面开发细节](#6-前端管理界面开发细节)
7. [对外公开接口与主题集成](#7-对外公开接口与主题集成)
8. [开发、构建与调试](#8-开发构建与调试)
9. [里程碑规划](#9-里程碑规划)
10. [附录：API 汇总](#10-附录api-汇总)

---

## 1. 项目概述

本插件在 Halo 中新增两类自定义扩展（Extension，CRD 风格数据模型）：

- **KnowledgeBase（知识库）**：知识库容器，包含公开/私有属性、成员与排序。
- **KnowledgeBaseDoc（文档）**：知识库下的文档节点，支持父子层级（文档树）、Markdown 内容、标签、发布状态与别名 URL。

插件面向三类使用场景：

| 场景 | 使用者 | 交付形式 |
| --- | --- | --- |
| 知识库管理 | Halo Console 管理员 | 前端管理页面（Vue 3） |
| 知识库阅读 | 站点访客 / 登录用户 | 主题页面（由主题开发者基于公开接口构建） |
| 内容维护 | 授权用户 | Console 管理页面 + 公开接口的只读/可写能力 |

插件默认仅超级管理员可使用全部管理能力；通过 RoleTemplate 按需开放给其他角色或匿名访客。

---

## 2. 功能列表

> 支持度标注：`✅ 原生` = 使用 Halo 官方机制直接实现；`⚠️ 需自研` = 官方无现成能力，需在自定义代码中实现或借用官方机制；`二选一` = 两种实现路线均可，无规范限制。

### 2.1 知识库管理（M1）

| 编号 | 功能 | 说明 | 优先级 |
| --- | --- | --- | --- |
| KB-01 | 知识库列表 | 分页、关键字搜索、按排序权重排序 | P0 |
| KB-02 | 创建知识库 | 名称（必填）、描述、图标、公开/私有 | P0 |
| KB-03 | 编辑知识库 | 修改全部元信息 | P0 |
| KB-04 | 删除知识库 | ⚠️ 需自研：级联删除其下所有文档（自动 CRUD 不级联，须走自定义端点 `DELETE /knowledgebases/{name}`） | P0 |
| KB-05 | 公开/私有切换 | `publicVisible` 控制匿名可见性 | P0 |
| KB-06 | 成员管理 | ⚠️ 需自研：无内置成员授权模型，`members` 列表 + 请求时比对当前用户；用户被删除后引用需清理 | P1 |
| KB-07 | 排序与置顶 | `priority` 权重，越小越靠前 | P1 |
| KB-08 | 知识库统计 | ⚠️ 需自研：`ReactiveExtensionClient` 无聚合 API，由 Reconciler 维护 `status.docCount` / `status.lastPublishTime` | P1 |

### 2.2 文档管理（M2）

| 编号 | 功能 | 说明 | 优先级 |
| --- | --- | --- | --- |
| DOC-01 | 文档 CRUD | 创建、读取、更新、删除文档 | P0 |
| DOC-02 | 文档树 | `parentName` 父子关系，树形展示与拖拽排序 | P0 |
| DOC-03 | Markdown 编辑 | Console 内联 Markdown 编辑器（标题/正文/保存） | P0 |
| DOC-04 | 文档别名 | `slug` 生成可读 URL（主题侧 `/docs/{slug}`） | P0 |
| DOC-05 | 发布状态 | `draft / published / archived` 三态与 `publishTime` | P0 |
| DOC-06 | 标签管理 | 文档标签 `tags`，支持按标签筛选 | P1 |
| DOC-07 | 文档搜索 | ⚠️ 需选型：标题/标签可走索引；正文不能建扩展索引（索引值有长度限制），需接 Halo 搜索引擎（`HaloDocumentsProvider`）或服务层内存过滤 | P1 |
| DOC-08 | 批量操作 | 批量移动、批量删除、批量发布 | P2 |
| DOC-09 | 文档历史 | ⚠️ 需自研：快照机制仅针对 Post（`PostContentService`），自定义扩展需自建快照扩展 | P2 |
| DOC-10 | Markdown 导出 | 按 `allowDocExport` 设置导出 `.md` 文件 | P1 |

### 2.3 系统设置（M3）

| 编号 | 功能 | 说明 |
| --- | --- | --- |
| SET-01 | 站点名称 | `siteName`，展示在页面标题/头部 |
| SET-02 | 站点描述 | `description` |
| SET-03 | 匿名阅读开关 | `allowAnonymousRead`：是否允许未登录用户阅读公开知识库 |
| SET-04 | 文档导出开关 | `allowDocExport`：是否允许导出 Markdown |

### 2.4 前端管理界面（M4）

| 编号 | 页面 | 说明 |
| --- | --- | --- |
| UI-01 | 知识库列表页 `/knowledge-bases` | 卡片/列表展示、搜索、新建、编辑、删除、公开切换 |
| UI-02 | 知识库详情页 `/knowledge-bases/:name` | 左侧文档树 + 右侧文档编辑区，Markdown 编辑与发布 |
| UI-03 | 设置页 | 复用 Halo 设置表单（`settings.yaml` 已声明） |

菜单挂载于 Console 内容区（`group: content`，图标 `IconBookRead`），路由受 `plugin:halo-plugin-minidocs:knowledgebase:manage` 权限保护。

### 2.5 对外公开接口（M5，主题侧）

| 编号 | 能力 | 说明 |
| --- | --- | --- |
| PUB-01 | 公开 REST API | `/apis/api.minidocs.halo.run/v1alpha1/**`：知识库/文档只读查询 |
| PUB-02 | Finder API | Thymeleaf 模板变量 `${minidocsFinder}`：`listKnowledgeBases` / `getKnowledgeBase` / `listDocs` / `getDoc` / `getDocTree` 等 |
| PUB-03 | 文档树接口 | 返回按 `parentName`/`priority` 组织的树形结构 |
| PUB-04 | 权限过滤 | 公开接口仅暴露 `publicVisible=true` 的知识库及其文档；遵循 `allowAnonymousRead` 设置 |
| PUB-05 | 主题页面支持 | 提供示例模板变量与页面布局契约说明（可复用 `layout :: html(head, content)`） |
| PUB-06 | 文档内容渲染 | 二选一：返回原始 Markdown（推荐，主题自渲染）或服务端渲染 HTML |

### 2.6 权限与安全（M6）

| 编号 | 能力 | 说明 |
| --- | --- | --- |
| RBAC-01 | 管理角色 | `halo-plugin-minidocs-role-manage`：知识库/文档全部操作（`*`） |
| RBAC-02 | 只读角色 | `halo-plugin-minidocs-role-view`：`get/list` |
| RBAC-03 | 匿名聚合 | 公开接口聚合到 `anonymous`，未登录可读公开知识库（受 `allowAnonymousRead` 约束） |
| RBAC-04 | UI 权限 | 前端 `plugin:halo-plugin-minidocs:knowledgebase:manage` 控制菜单与路由 |

### 2.7 支持度评估小结

- **需自研的功能**：KB-04 级联删除（自定义端点）、KB-06 成员授权（列表比对 + 引用清理）、KB-08 统计（Reconciler 维护 status）、DOC-07 正文搜索（接 Halo 搜索引擎或内存过滤，标题/标签仍走索引）、DOC-09 版本历史（自建快照扩展）。
- **其余功能均为原生支持**：CRUD、排序筛选、标签、发布状态、设置、管理页、公开 API、Finder、RBAC 全部有官方机制可直接实现。
- **版本敏感点**：主题布局复用 `layout :: html(head, content)` 需 Halo 2.26+；UI 输出 ESM 需 `@halo-dev/ui-plugin-bundler-kit` 2.26+；自定义 FormKit 输入 2.25+；`IndexSpecs` 的 `unique`/`nullable` builder 2.22+。本项目 `spec.requires: ">=2.26.0"` 均已覆盖，无需改动。

---

## 3. 数据模型设计

两个扩展均已定义于 `src/main/java/run/halo/plugin/minidocs/extension/`，并在插件 `start()` 中注册。

### 3.1 KnowledgeBase（知识库）

- **GVK**：`minidocs.halo.run/v1alpha1`，kind `KnowledgeBase`，plural `knowledgebases`
- **REST 路径**：`/apis/minidocs.halo.run/v1alpha1/knowledgebases`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `spec.displayName` | String | ✅ | 知识库名称（≤100） |
| `spec.description` | String | - | 描述 |
| `spec.logo` | String | - | 图标（URL 或附件链接） |
| `spec.publicVisible` | Boolean | - | 是否公开可见，默认 `false` |
| `spec.members` | List\<String\> | - | 私有知识库可访问成员用户名 |
| `spec.priority` | Integer | - | 排序权重，越小越靠前 |

规划补充字段（P1）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `spec.slug` | String | 知识库别名，用于主题侧 URL（可选） |
| `status.docCount` | Integer | 文档总数（Reconciler 维护） |
| `status.lastPublishTime` | Instant | 最近发布时间（Reconciler 维护） |

### 3.2 KnowledgeBaseDoc（文档）

- **GVK**：`minidocs.halo.run/v1alpha1`，kind `KnowledgeBaseDoc`，plural `knowledgebasedocs`
- **REST 路径**：`/apis/minidocs.halo.run/v1alpha1/knowledgebasedocs`

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `spec.knowledgeBaseName` | String | ✅ | 所属知识库名称 |
| `spec.title` | String | ✅ | 文档标题（≤200） |
| `spec.slug` | String | - | 别名，生成可读 URL（≤200，需唯一校验） |
| `spec.content` | String | - | Markdown 内容 |
| `spec.parentName` | String | - | 父文档名称，空表示顶级文档 |
| `spec.priority` | Integer | - | 同层排序权重 |
| `spec.tags` | List\<String\> | - | 标签 |
| `spec.phase` | String | - | `draft / published / archived`，默认 `draft` |
| `spec.publishTime` | Instant | - | 最后发布时间 |

规划补充字段（P1/P2）：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `spec.permalink` | String | 主题侧访问路径模板（可选） |
| `spec.cover` | String | 封面图（可选） |
| `metadata.labels` | Map | 约定标签：`minidocs.halo.run/knowledge-base`、`minidocs.halo.run/parent`、`minidocs.halo.run/phase`，用于 `labelSelector` 高效查询 |
| `metadata.annotations` | Map | 约定注解：`minidocs.halo.run/child-count` 等（可选） |

### 3.3 索引设计（IndexSpecs）

在 `schemeManager.register(...)` 时通过 `indexSpecs` 声明，供 `fieldSelector` 与 `sort` 使用：

```java
schemeManager.register(KnowledgeBaseDoc.class, indexSpecs -> {
    indexSpecs.add(IndexSpecs.<KnowledgeBaseDoc, String>single("spec.knowledgeBaseName", String.class)
        .indexFunc(doc -> doc.getSpec().getKnowledgeBaseName()));
    indexSpecs.add(IndexSpecs.<KnowledgeBaseDoc, String>single("spec.parentName", String.class)
        .indexFunc(doc -> doc.getSpec().getParentName()));
    indexSpecs.add(IndexSpecs.<KnowledgeBaseDoc, String>single("spec.phase", String.class)
        .indexFunc(doc -> doc.getSpec().getPhase()));
    indexSpecs.add(IndexSpecs.<KnowledgeBaseDoc, Instant>single("spec.publishTime", Instant.class)
        .indexFunc(doc -> doc.getSpec().getPublishTime()));
    indexSpecs.add(IndexSpecs.<KnowledgeBaseDoc, String>multi("spec.tags", String.class)
        .indexFunc(doc -> doc.getSpec().getTags() == null ? Set.of() : Set.copyOf(doc.getSpec().getTags())));
    // 约定标签索引（可选，配合 labelSelector 使用）
    indexSpecs.add(IndexSpecs.<KnowledgeBaseDoc, String>single("labels.minidocs.halo.run/knowledge-base", String.class)
        .indexFunc(doc -> doc.getMetadata().getLabels().get("minidocs.halo.run/knowledge-base")));
});
```

> 说明：索引键需与查询使用的 `fieldSelector` 字段完全一致；多值字段（如 `tags`）使用 `multi`，排序字段使用可比较类型。

---

## 4. 后端架构与开发细节

### 4.1 代码结构

```
src/main/java/run/halo/plugin/minidocs/
├── KnowledgeBasePlugin.java          # 插件入口：注册/注销扩展
├── extension/
│   ├── KnowledgeBase.java            # 知识库扩展
│   └── KnowledgeBaseDoc.java         # 文档扩展
├── service/
│   ├── KnowledgeBaseService.java     # 知识库业务逻辑（含级联删除、统计）
│   ├── KnowledgeBaseDocService.java  # 文档业务逻辑（树构建、slug 校验、发布）
│   └── DocTreeService.java           # 文档树组装（可选并入 DocService）
├── endpoint/
│   ├── KnowledgeBaseConsoleEndpoint.java   # Console 管理 API（CustomEndpoint）
│   ├── KnowledgeBaseDocConsoleEndpoint.java
│   └── MinidocsPublicEndpoint.java         # 公开 API（主题侧）
├── finder/
│   └── MinidocsFinder.java           # Finder API（模板变量 ${minidocsFinder}）
├── reconciler/
│   ├── KnowledgeBaseStatsReconciler.java   # 维护知识库统计状态
│   └── DocSlugReconciler.java              # slug 去重/自动生成（可选）
└── role/
    └── RoleTemplateInitializer.java  # 动态注册 RoleTemplate（可选，推荐 YAML 静态声明）
```

### 4.2 插件入口（已有）

`KnowledgeBasePlugin` 继承 `BasePlugin`，在 `start()` 中注册两个扩展，`stop()` 中注销：

```java
@Override
public void start() {
    schemeManager.register(KnowledgeBase.class, indexSpecs -> { /* 见 3.3 */ });
    schemeManager.register(KnowledgeBaseDoc.class, indexSpecs -> { /* 见 3.3 */ });
}

@Override
public void stop() {
    schemeManager.unregister(Scheme.buildFromType(KnowledgeBase.class));
    schemeManager.unregister(Scheme.buildFromType(KnowledgeBaseDoc.class));
}
```

### 4.3 服务层设计

**KnowledgeBaseService**（注入 `ReactiveExtensionClient`）：

- `list(keyword, page, size)`：分页查询，关键字匹配 `displayName`/`description`，按 `spec.priority` 排序。
- `create(KnowledgeBase)`：校验 `displayName` 非空、`metadata.name` 唯一。
- `update(name, KnowledgeBase)`：整体更新（`client.update`）。
- `delete(name)`：先查询并删除该知识库下全部文档（级联），再删除知识库；建议结合 finalizer 或 Reconciler 保证一致性。
- `checkReadPermission(kb, authName)`：`publicVisible=true` 或 `members` 包含当前用户或管理员时放行。

**KnowledgeBaseDocService**：

- `listByKnowledgeBase(kbName, keyword, phase, page, size)`：用 `fieldSelector=spec.knowledgeBaseName=...` + 关键字过滤。
- `create/update/delete`：更新时维护标签 `minidocs.halo.run/knowledge-base` 等，便于列表查询。
- `buildTree(kbName)`：读取全部文档，按 `parentName` + `priority` 组装树。
- `publish(name)`：将 `phase` 置为 `published` 并写入 `publishTime`。
- `generateSlug(title)`：中文标题转拼音或回退为 `doc-{name}`，并在同知识库内保证唯一。
- `exportMarkdown(name)`：受 `allowDocExport` 设置约束，返回 `.md` 文本。

### 4.4 管理端 API（Console）

采用 **CustomEndpoint** 方式（推荐），`groupVersion()` 返回 `console.api.minidocs.halo.run/v1alpha1`，路径自动前缀 `/apis/console.api.minidocs.halo.run/v1alpha1`。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/knowledgebases` | 知识库分页列表（keyword） |
| POST | `/knowledgebases` | 创建知识库 |
| PUT | `/knowledgebases/{name}` | 更新知识库 |
| DELETE | `/knowledgebases/{name}` | 删除知识库（级联） |
| GET | `/knowledgebases/{name}/stats` | 知识库统计（文档数等） |
| GET | `/knowledgebases/{name}/docs` | 文档分页列表 |
| GET | `/knowledgebases/{name}/tree` | 文档树（管理页左侧树） |
| POST | `/knowledgebases/{name}/docs` | 创建文档 |
| PUT | `/knowledgebases/{name}/docs/{docName}` | 更新文档 |
| DELETE | `/knowledgebases/{name}/docs/{docName}` | 删除文档（级联子树） |
| POST | `/knowledgebases/{name}/docs/{docName}/publish` | 发布文档 |
| POST | `/knowledgebases/{name}/docs/{docName}/move` | 移动文档（改 parentName/priority） |
| GET | `/knowledgebases/{name}/docs/{docName}/export` | 导出 Markdown |

代码骨架：

```java
@Component
@RequiredArgsConstructor
public class KnowledgeBaseConsoleEndpoint implements CustomEndpoint {

    private final KnowledgeBaseService knowledgeBaseService;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "KnowledgeBaseV1alpha1Console";
        return SpringdocRouteBuilder.route()
            .GET("/knowledgebases", this::listKnowledgeBases,
                builder -> builder
                    .operationId("ListKnowledgeBases")
                    .description("List knowledge bases")
                    .tag(tag)
                    .response(responseBuilder()
                        .implementation(ListResult.generateGenericClass(KnowledgeBase.class))))
            .POST("/knowledgebases", this::createKnowledgeBase,
                builder -> builder
                    .operationId("CreateKnowledgeBase")
                    .description("Create a knowledge base")
                    .tag(tag))
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("console.api.minidocs.halo.run", "v1alpha1");
    }
}
```

> 查询参数可使用 `SortableRequest` 子类（`toListOptions()` 支持 keyword 组合查询），并配合 `Queries.equal/contains/or`。文档化的 tag 命名约定为 `{Kind}{Version}{Scope}`。

### 4.5 Reconciler（可选，P1）

使用 `ControllerBuilder` + `Reconciler` 维护派生状态：

- **KnowledgeBaseStatsReconciler**：监听 `KnowledgeBaseDoc` 变更（`onAdd/onUpdate/onDelete`），更新所属知识库的 `status.docCount`、`status.lastPublishTime`。
- **DocSlugReconciler**：为缺失 `slug` 的文档生成并保证唯一（或由服务层在创建时生成，二选一，避免重复逻辑）。

> 若仅需简单联动，可先用服务层同步维护，避免引入 Reconciler 的复杂度；P1 再迁移。

---

## 5. 权限与安全设计

### 5.1 角色模板（RoleTemplate）

在 `src/main/resources/extensions/` 下新增 `roleTemplate.yaml`（静态声明，优于代码注册）。命名以插件名作前缀避免冲突。

```yaml
apiVersion: v1alpha1
kind: Role
metadata:
  name: halo-plugin-minidocs-role-view
  labels:
    halo.run/role-template: "true"
  annotations:
    rbac.authorization.halo.run/module: "知识库管理"
    rbac.authorization.halo.run/display-name: "查看知识库"
    rbac.authorization.halo.run/ui-permissions: |
      ["plugin:halo-plugin-minidocs:knowledgebase:view"]
rules:
  - apiGroups: ["minidocs.halo.run"]
    resources: ["minidocs/knowledgebases", "minidocs/knowledgebasedocs"]
    verbs: ["get", "list"]
---
apiVersion: v1alpha1
kind: Role
metadata:
  name: halo-plugin-minidocs-role-manage
  labels:
    halo.run/role-template: "true"
  annotations:
    rbac.authorization.halo.run/dependencies: |
      ["halo-plugin-minidocs-role-view"]
    rbac.authorization.halo.run/module: "知识库管理"
    rbac.authorization.halo.run/display-name: "管理知识库"
    rbac.authorization.halo.run/ui-permissions: |
      ["plugin:halo-plugin-minidocs:knowledgebase:manage"]
rules:
  - apiGroups: ["minidocs.halo.run"]
    resources: ["minidocs/knowledgebases", "minidocs/knowledgebasedocs"]
    verbs: ["*"]
```

> 注意：资源规则格式为 `apiGroups: ["<group>"]` + `resources: ["<plugin-name>/<plural>"]`。默认情况下（不建 RoleTemplate）插件 API 仅超级管理员可访问，纯管理员插件可省略本文件。

### 5.2 公开接口的匿名访问

公开 API（`/apis/api.minidocs.halo.run/v1alpha1/**`）需要聚合到 `anonymous` 角色：

```yaml
apiVersion: v1alpha1
kind: Role
metadata:
  name: halo-plugin-minidocs-role-anonymous
  labels:
    halo.run/role-template: "true"
    halo.run/hidden: "true"
    rbac.authorization.halo.run/aggregate-to-anonymous: "true"
rules:
  - apiGroups: ["api.minidocs.halo.run"]
    resources: ["minidocs/knowledgebases", "minidocs/knowledgebasedocs"]
    verbs: ["get", "list"]
```

> 说明：`resources` 需按 `endpoint()` 中声明的顶层路径段逐一列出（资源命名遵循 `<插件名>/<资源名>` 规则）；仅开放只读动词，写操作保持管理员权限。

> 业务层面的匿名限制（`allowAnonymousRead`、`publicVisible`）在服务层二次校验，不能只依赖 RBAC。

### 5.3 数据访问控制

- **公开接口**：仅返回 `publicVisible=true` 的知识库及其文档；若 `allowAnonymousRead=false`，公开接口对匿名用户返回 403。
- **私有知识库**：`members` 包含当前用户名（`ReactiveSecurityContextHolder` 获取）或用户具备管理权限时放行。
- **管理 API**：依赖 RBAC 规则，默认超级管理员。

### 5.4 输入校验

- 扩展字段使用 `@Schema`（`requiredMode`、`maxLength` 等），创建/更新时 Halo 自动校验。
- 自定义端点请求体使用 Bean Validation（`@NotNull`、`@Size`）并注册 `LocalValidatorFactoryBean`。

---

## 6. 前端管理界面开发细节

### 6.1 技术选型

- Vue 3 + TypeScript + Vite（现有 `ui/` 子项目已配置）。
- 组件库：`@halo-dev/components`（对话框、按钮、图标等）。
- 状态与工具：`@halo-dev/ui-shared`（`utils.permission`、`utils.date` 等，禁止自装 dayjs/date-fns）。
- 请求：`@halo-dev/api-client`；可用 Gradle 任务 `generateApiClient` 从后端 OpenAPI 生成类型安全客户端（P1 接入）。

### 6.2 路由与菜单（已有）

`ui/src/index.ts` 已声明两条路由：

| 路由 | 页面组件 | 说明 |
| --- | --- | --- |
| `/knowledge-bases` | `KnowledgeBaseList.vue` | 知识库列表，菜单：内容区 `content`，图标 `IconBookRead`，priority 30 |
| `/knowledge-bases/:name` | `KnowledgeBaseDetail.vue` | 知识库详情（文档树 + 编辑） |

两条路由均受 `plugin:halo-plugin-minidocs:knowledgebase:manage` 权限保护。

### 6.3 页面开发要点

**KnowledgeBaseList.vue（列表页）**

- 数据：调 `GET /apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases?keyword=&page=&size=`。
- 交互：新建/编辑用 `@halo-dev/components` 的 `VModal` + FormKit 表单（`displayName`、`description`、`logo`（附件选择）、`publicVisible`（开关）、`members`（用户多选））。
- 删除：二次确认（`VEntity` 的操作区），提示会级联删除文档。
- 权限：`utils.permission.has([...])` 控制按钮显隐。

**KnowledgeBaseDetail.vue（详情页）**

- 左侧：文档树，调 `GET /apis/console.api.minidocs.halo.run/v1alpha1/knowledgebases/{name}/tree`，支持展开/折叠、拖拽调整顺序（`parentName` + `priority`）、新建子文档。
- 右侧：文档编辑区，标题输入 + Markdown 文本域（P0 用 textarea 起步，P1 接入 `@halo-dev/richtext-editor` 或 markdown 编辑器扩展点）。
- 保存：`POST/PUT .../docs`；发布按钮调 `POST .../docs/{docName}/publish`。
- 状态展示：`draft / published / archived` 徽标与 `publishTime`。

### 6.4 设置页

`settings.yaml` 已声明（`siteName`、`description`、`allowAnonymousRead`、`allowDocExport`），Halo Console 会自动渲染设置表单（插件设置入口），无需额外开发页面。

---

## 7. 对外公开接口与主题集成

### 7.1 公开 REST API（主题/第三方）

路由前缀 `/apis/api.minidocs.halo.run/v1alpha1`，通过 `MinidocsPublicEndpoint`（CustomEndpoint）实现，仅提供只读能力：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/knowledgebases` | 公开知识库列表（仅 `publicVisible=true`） |
| GET | `/knowledgebases/{name}` | 知识库详情 |
| GET | `/knowledgebases/{name}/tree` | 文档树（树形 JSON） |
| GET | `/knowledgebases/{name}/docs` | 文档分页列表（可按 phase、keyword 过滤） |
| GET | `/knowledgebases/{name}/docs/{docName}` | 文档详情（含 Markdown 内容） |
| GET | `/docs/{slug}` | 按 slug 查文档（供主题生成可读 URL） |

响应中返回字段示例：

```json
{
  "name": "quick-start",
  "title": "快速开始",
  "slug": "quick-start",
  "content": "# 快速开始\n...",
  "phase": "published",
  "publishTime": "2026-08-25T08:00:00Z",
  "parentName": "",
  "tags": ["指南"]
}
```

代码骨架（要点）：

```java
@Component
@RequiredArgsConstructor
public class MinidocsPublicEndpoint implements CustomEndpoint {

    private final ReactiveExtensionClient client;
    private final ReactiveSettingFetcher settingFetcher;

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        final var tag = "MinidocsV1alpha1Public";
        return SpringdocRouteBuilder.route()
            .GET("/knowledgebases", this::listKnowledgeBases,
                builder -> builder.operationId("ListPublicKnowledgeBases")
                    .description("List public knowledge bases").tag(tag))
            .GET("/knowledgebases/{name}/tree", this::getDocTree, ...)
            .build();
    }

    @Override
    public GroupVersion groupVersion() {
        return new GroupVersion("api.minidocs.halo.run", "v1alpha1");
    }
}
```

**匿名控制实现**：每个 handler 先 `settingFetcher.fetch("basic", BasicSetting.class)` 判断 `allowAnonymousRead`，再结合 `ReactiveSecurityContextHolder` 判断登录态：

```java
private Mono<Boolean> anonymousReadAllowed() {
    return settingFetcher.fetch("basic", BasicSetting.class)
        .map(BasicSetting::isAllowAnonymousRead)
        .defaultIfEmpty(true);
}
```

### 7.2 Finder API（Thymeleaf 模板变量）

`@Finder("minidocsFinder")` 暴露给主题模板，常用方法：

| 方法 | 返回 | 说明 |
| --- | --- | --- |
| `listKnowledgeBases(page, size)` | `ListResult<KnowledgeBase>` | 公开知识库分页 |
| `getKnowledgeBase(name)` | `KnowledgeBase` | 知识库详情 |
| `listDocs(kbName, phase, page, size)` | `ListResult<KnowledgeBaseDoc>` | 文档分页 |
| `getDoc(kbName, docName)` | `KnowledgeBaseDoc` | 文档详情 |
| `getDocTree(kbName)` | `List<TreeNode>` | 文档树 |
| `getDocBySlug(slug)` | `KnowledgeBaseDoc` | 按 slug 查询 |

主题模板使用示例：

```html
<div th:each="kb : ${minidocsFinder.listKnowledgeBases(1, 10).items}">
  <a th:href="'/kb/' + ${kb.metadata.name}">
    <h2 th:text="${kb.spec.displayName}"></h2>
  </a>
  <p th:text="${kb.spec.description}"></p>
</div>
```

```html
<div th:with="doc = ${minidocsFinder.getDocBySlug('quick-start')}">
  <h1 th:text="${doc.spec.title}"></h1>
  <!-- 插件返回原始 Markdown，主题侧使用自有渲染器处理后以 utext 输出 -->
  <div class="doc-content"
       th:utext="${@markdownRenderer.render(doc.spec.content)}"></div>
</div>
```

> 说明：`doc.spec.content` 为原始 Markdown 文本；示例中 `@markdownRenderer` 表示主题自定义的渲染 Bean，实际由主题自行实现（如 marked + highlight.js）。

### 7.3 全局模板变量（TemplateModel，可选）

如需全局变量（如插件站点配置），实现 `TemplateModel` bean 暴露 `minidocsSettings`（含 `siteName`、`allowAnonymousRead` 等），主题模板中直接 `${minidocsSettings.siteName}`。

### 7.4 主题侧页面开发建议（给第三方主题开发者的契约）

1. **列表页**：`/apis/api.minidocs.halo.run/v1alpha1/knowledgebases` 或 `minidocsFinder.listKnowledgeBases`。
2. **详情页 URL**：建议 `/docs/{slug}`（或 `/kb/{kbName}/docs/{slug}`），服务端提供按 slug 解析。
3. **Markdown 渲染**：插件返回原始 Markdown，主题使用自有渲染器（如 marked/highlight.js）。
4. **页面布局**：插件自身若提供模板页，遵循 Halo 2.26+ 的 `layout :: html(head, content)` 契约复用当前主题布局；主题开发者可直接用公开 API 自行实现页面，插件不强制主题结构。
5. **权限提示**：公开接口只返回 `publicVisible=true` 的内容；私有内容请引导用户登录后在 Console/UC 查看（UC 端路由可列为 P2 扩展）。

---

## 8. 开发、构建与调试

### 8.1 环境要求

- JDK 21、Gradle（wrapper 内置）、pnpm、Docker（DevTools 运行 Halo 服务）。

### 8.2 常用命令

| 命令 | 说明 |
| --- | --- |
| `./gradlew haloServer` | 启动 Halo 开发服务（Docker），访问 `http://localhost:8090/console`（admin/admin） |
| `./gradlew reload` | 代码变更后热重载插件 |
| `./gradlew watch` | 监听变更自动重载 |
| `./gradlew build` | 构建插件 JAR（含 UI 产物，经 `processUiResources` 拷贝） |
| `./gradlew generateApiClient` | 生成前端 API 客户端（P1 接入后使用） |
| `pnpm --dir ui dev` | 单独调试 UI（若配置了 UI 开发代理） |

> 开发服务配置在 `build.gradle` 的 `halo {}` 块（version 2.26、端口 8090、externalUrl 等）。

### 8.3 验证清单（每次提交前）

- [ ] `./gradlew build` 通过（Java 编译 + UI 构建 + 测试）
- [ ] Console 中插件可安装/启用，扩展列表出现 `knowledgebases` / `knowledgebasedocs`
- [ ] 管理页增删改查、文档树、发布流程可用
- [ ] 匿名访问公开接口：`publicVisible=true` 可读，`false` 返回 403
- [ ] Swagger UI（`/swagger-ui.html`）中可见 Console/Public 分组接口

---

## 9. 里程碑规划

| 里程碑 | 范围 | 验收标准 |
| --- | --- | --- |
| M0（已完成） | 项目骨架：manifest、扩展模型、UI 路由 | 插件可安装启用，扩展注册成功 |
| M1（P0） | 知识库 CRUD + 文档 CRUD + 文档树 + 基础管理页 | Console 内完成知识库/文档的完整管理 |
| M2（P0） | 发布流程（draft/published/archived）+ slug + 公开 API 只读 | 主题可通过公开 API 读取已发布内容 |
| M3（P1） | 成员权限、标签、标题/标签搜索、导出、统计 Reconciler（正文搜索独立可选） | 权限控制与检索能力可用 |
| M4（P1） | Finder API + 示例主题页面 + 文档历史 | 第三方主题可零后端开发接入 |
| M5（P2） | UC 端页面、评论接入、批量操作、Markdown 渲染增强 | 面向终端用户的内容协作闭环 |

> 实现提醒：M1 的级联删除必须走自定义端点（`DELETE /knowledgebases/{name}`），勿依赖自动生成的 CRUD；DOC-07 正文搜索按独立可选功能排期，避免 M3 范围失控。

---

## 10. 附录：API 汇总

| 分组 | 前缀 | 用途 |
| --- | --- | --- |
| 自动生成 CRUD | `/apis/minidocs.halo.run/v1alpha1/{knowledgebases,knowledgebasedocs}` | 扩展基础 CRUD（受 RBAC 保护） |
| Console 管理 | `/apis/console.api.minidocs.halo.run/v1alpha1/**` | 管理页业务接口 |
| 公开只读 | `/apis/api.minidocs.halo.run/v1alpha1/**` | 主题/第三方消费 |
| Finder | `${minidocsFinder}` | Thymeleaf 模板内调用 |

> 版本说明：本项目目标 Halo `2.26.0`（`spec.requires: ">=2.26.0"`）。升级 Halo 版本前先查阅官方 [API changelog](https://raw.githubusercontent.com/halo-dev/docs/refs/heads/main/docs/developer-guide/plugin/api-changelog.md)，避免使用已变更的 API 签名。
