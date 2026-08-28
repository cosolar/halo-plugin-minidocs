<p align="center">
  <img src="docs/images/logo.png" height="150" alt="logo.png" />
</p>

<h1 align="center">MiniDocs 知识库</h1>

MiniDocs 是 Halo 2.x 的轻量知识库插件，用于搭建团队 / 个人知识库：多知识库管理、文档树、Markdown 编辑、分类标签与权限控制，并将知识库内容通过公开接口与 Finder 暴露给第三方主题，方便开发用户侧文档站点。

插件同时提供 Console 管理界面、`minidocsFinder` Finder API 和匿名公共 REST API，适用于传统 Thymeleaf 主题和前端框架渲染的文档页。

> 目标平台：Halo `>= 2.26.0`

## 演示界面

![知识库管理界面.png](docs/images/%E7%9F%A5%E8%AF%86%E5%BA%93%E7%AE%A1%E7%90%86%E7%95%8C%E9%9D%A2.png)

![知识库文档管理界面.png](docs/images/%E7%9F%A5%E8%AF%86%E5%BA%93%E6%96%87%E6%A1%A3%E7%AE%A1%E7%90%86%E7%95%8C%E9%9D%A2.png)

## 功能特性

- 知识库管理：支持多知识库创建、编辑、删除，公开 / 私有切换、成员授权与排序。
- 文档管理：支持文档树层级、Markdown 编辑、slug 别名、发布 / 草稿状态、标签与封面。
- 分类标签：知识库与文档均支持标签，便于按主题归类与检索。
- 权限控制：内置「知识库查看」「知识库管理」角色模板；私有知识库可指定成员（可访问者）列表，服务端按「创建者 / 成员 / 管理员」做资源级访问校验；可开关「允许未登录用户阅读公开知识库」控制匿名访问。
- 文档发布：支持单篇文档发布，发布后内容通过公开接口与 Finder 对外可见。
- 文档导入导出：支持批量导入（同名知识库安全覆盖、失败自动回滚保留原数据），以及将文档导出为 Markdown（受导出开关约束）。
- 外链分享：知识库卡片可一键开启外链分享，生成 `/docs/share/{token}` 对外链接，支持可选访问密码与有效期，分享页与阅读页同布局且无需登录。
- 主题适配：提供 `minidocsFinder` Finder API 与匿名公共 REST API，便于主题渲染知识库列表、文档树与文档详情。

## 安装使用

1. 下载插件 JAR：
   - GitHub Releases：访问本项目 Releases 下载 Assets 中的 JAR 文件。
   - Halo 应用市场：在 Halo 后台「应用市场」搜索 MiniDocs 安装。
2. 在 Halo Console 的插件管理中上传并安装插件，安装和更新方式可参考：<https://docs.halo.run/user-guide/plugins>
3. 安装完成后，访问 Console 左侧的**知识库**菜单管理知识库与文档。
4. 如需游客直接访问公开知识库，请在插件设置中开启**允许未登录用户阅读公开知识库**；如需提供文档下载，请确认**允许导出文档**已开启。
5. 主题侧接入方式见下方「主题适配」。

## 权限控制与知识库可见性

MiniDocs 的权限模型分为两层，叠加生效：**Halo 角色模板**决定「谁能在 Console 操作知识库」，**资源级访问控制**决定「谁能读取某个具体的知识库 / 文档」。同一套可见性规则在 Console 管理端、公共 REST API 与主题 Finder 中保持一致。

### 角色模板

插件安装时注册以下角色模板（在 Halo 后台「用户与权限 → 角色」中可分配给用户）：

| 角色模板 | 展示名 | 权限 |
| --- | --- | --- |
| `role-template-minidocs-view` | 知识库查看 | 知识库与文档的 `get`、`list`（读取） |
| `role-template-minidocs-manage` | 知识库管理 | 知识库与文档的 `create`、`patch`、`update`、`delete`、`deletecollection`（写操作），自动依赖「知识库查看」 |
| `role-template-minidocs-anonymous` | （隐藏，自动聚合到匿名角色） | 匿名访问公开 REST API 的 `get`、`list` |

> 「知识库查看」决定用户能否在 Console 看到知识库菜单并读取内容；「知识库管理」决定能否创建 / 编辑 / 删除知识库与文档、发布 / 取消发布、移动文档。

### 知识库可见性（公开 / 私有）

每个知识库都有 `publicVisible` 开关：

- **公开知识库**（`publicVisible=true`）：所有人可见，包括未登录访客（具体还受下方「匿名阅读开关」约束）。
- **私有知识库**（`publicVisible=false`，默认）：仅以下三类人可见——
  1. 知识库创建者（`spec.creatorName`，创建时自动写入）；
  2. 成员列表中的用户（`spec.members`，在知识库编辑中维护）；
  3. 具备知识库管理权限的用户（见「管理豁免」）。

其余请求一律拒绝：Console 接口返回 403，公共接口 / Finder 返回空或 404，避免通过「猜 URL / 传入私有知识库标识」越权读取私有内容。

### 管理豁免

以下两种身份视为知识库管理员，对所有知识库（含私有）拥有完全访问与管理权限：

1. **超级管理员**：拥有 `role_super-` 前缀角色的用户（Halo 超级管理员实际权限为 `ROLE_super-role`）；
2. **知识库管理者**：拥有 `plugin:halo-plugin-minidocs:knowledgebase:*` 前缀权限的用户（即分配了「知识库管理」角色模板）。

> 判定严格限定在上述两种来源，**不会**把仅拥有其它模块（附件、文章、评论等）`xxx-manage` 权限的普通用户当作知识库管理员，防止越权读取全部私有知识库。

### 匿名阅读开关

插件设置「基础设置 → 允许未登录用户阅读公开知识库」（`allowAnonymousRead`，默认关闭）：

- 开启：未登录访客可浏览公开知识库及其已发布文档；
- 关闭：未登录访客一律被拒（公共 API 返回 403，Finder 返回空），登录用户不受影响。

该开关只影响**匿名访问公开知识库**；私有知识库的创建者 / 成员 / 管理判定始终生效，不受此开关影响。

### 各入口的可见性边界

| 入口 | 可见内容 | 说明 |
| --- | --- | --- |
| Console 管理端（`console.api.minidocs.halo.run`） | 当前用户可访问的知识库（公开 + 自己创建 / 是成员的私有 + 管理可见全部） | 所有端点经 `requireAccessByName()` 统一校验，无权限返回 403；创建 / 更新 / 删除 / 发布 / 导入 / 导出等操作均需先通过校验 |
| 公共 REST API（`api.minidocs.halo.run`） | 仅公开知识库及其已发布文档 | 匿名访问受「匿名阅读开关」约束；私有知识库一律不出现 |
| 主题 Finder（`minidocsFinder`） | 按当前访问者返回：登录用户 = 公开 + 自己创建 / 是成员的私有库；未登录 = 仅公开库 | 与公共 API 一致的可见性边界；文档仅返回已发布 |
| 列表 / 分页 | `listAccessible` 先做资源级过滤，再排序、分页 | 分页 `total` 为「过滤后的全量」，避免分页数据失真 |
| 统计面板（stats） | 仅统计当前用户可访问的资源 | 不向普通用户泄露全站私有知识库 / 文档的聚合数量 |

### 文档级可见性

- 文档有 `draft`（草稿）与 `published`（发布）两种状态（历史数据中的 `archived` 兼容显示为「已归档」）。
- **对外（公共 API、Finder、主题阅读页）只暴露 `published` 的文档**；草稿不会出现在文档树与阅读页中，仅知识库的创建者 / 成员 / 管理者可在 Console 中查看与编辑。

### 导出控制

插件设置「允许导出文档（Markdown）」（`allowDocExport`，默认开启）：

- 开启：可导出单篇文档为 Markdown，也可批量导出知识库 ZIP；
- 关闭：两者均返回 403；且批量导出只会包含当前用户有权访问的知识库（私有非成员不可导出）。

## 外链分享

MiniDocs 支持为知识库开启「外链分享」，把知识库内容通过一条对外链接分享给任意访客。**分享访问走独立的分享链路，不依赖知识库的公开 / 私有权限**：只要持有有效外链，即便是不公开的私有知识库，也无需登录即可查看（此时仅受分享自身的「开启状态、有效期、访问密码」约束）。

### 开启与管理

1. 进入 Console 的**知识库**列表，点击某张知识库卡片右下角的**分享图标**（绿色，网络节点样式；已开启分享时图标高亮并带绿色小圆点）。
2. 在弹出的「分享知识库」弹窗中配置：
   - **开启外链分享**：总开关；
   - **访问密码**：留空表示无密码访问，任何持有外链的人均可直接进入；填写后需输入密码才能查看；
   - **外链有效期**：永久 / 7 天 / 30 天 / 90 天，到期后外链自动失效；
   - **复制外链**：保存后复制生成的 `https://你的域名/docs/share/{token}` 链接分发给访客。
3. 每次保存后弹窗保持打开，可直接复制更新后的外链；再次点击某知识库的分享图标即可重新查看、修改或关闭它的外链。

### 分享机制

- **每个知识库对应一条固定外链**（`shareToken` 在开启时生成）。外链不设访问次数与人数上限，只要未关闭、未过期即持续有效。
- **关闭再开启时外链保持不变**（token 不重新生成），因此链接地址不会变化；只有设置新有效期才会改变到期时间。
- **访问密码校验**通过后，服务端下发 HttpOnly Cookie 保持该浏览器的访问状态，有效期内无需重复输入。

## 主题适配

此插件为主题端提供了：

- **Finder API** `minidocsFinder`：支持 `listKnowledgeBases(page, size)`、`getKnowledgeBase(kbSlug)`、`listDocs(kbSlug, page, size)`、`getDoc(kbSlug, docSlug)`、`getDocTree(kbSlug)` 和 `getDocBySlug(docSlug)`，用于在 Thymeleaf / FreeMarker 模板中渲染知识库与文档。
- **公共 REST API**：提供匿名知识库 / 文档查询接口（含文档树、按 slug 获取），可用于前端框架、小程序或服务端集成。
- **数据可见性**：公开 API 仅返回 `publicVisible=true` 的知识库及其 `phase=published` 文档；Finder 则按**当前访问者**返回其可访问内容——未登录仅公开库，登录用户额外包含自己创建 / 是成员的私有库；所有文档仅 `phase=published`。匿名访问受插件「允许未登录用户阅读」设置约束。

详细文档请参考：

- [主题 API 文档](./docs/minidocs-theme-api.md) — 路由、Finder API、Markdown 渲染与类型定义。
- [REST API 文档](./docs/minidocs-rest-api.md) — 公共 API、Console API 和标准 CRUD 端点。

## 开发文档

本地开发、构建与调试说明请参考 [开发文档](./docs/dev.md)。

环境要求：JDK 21、pnpm、Docker（DevTools 运行 Halo 服务）。

```bash
./gradlew haloServer   # 启动 Halo 开发服务（http://localhost:8090/console，admin/admin）
./gradlew reload       # 代码变更后热重载插件
./gradlew watch        # 监听变更自动重载
./gradlew build        # 构建插件 JAR（含 UI 产物）
```

## 许可

[GPL-3.0](LICENSE)
