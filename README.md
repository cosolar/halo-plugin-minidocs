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
- 权限控制：内置「知识库查看」「知识库管理」角色模板；可开启「允许未登录用户阅读公开知识库」控制匿名访问。
- 文档发布：支持单篇文档发布，发布后内容通过公开接口与 Finder 对外可见。
- 文档导入导出：支持批量导入文档，以及将文档导出为 Markdown（受导出开关约束）。
- 主题适配：提供 `minidocsFinder` Finder API 与匿名公共 REST API，便于主题渲染知识库列表、文档树与文档详情。

## 安装使用

1. 下载插件 JAR：
   - GitHub Releases：访问本项目 Releases 下载 Assets 中的 JAR 文件。
   - Halo 应用市场：在 Halo 后台「应用市场」搜索 MiniDocs 安装。
2. 在 Halo Console 的插件管理中上传并安装插件，安装和更新方式可参考：<https://docs.halo.run/user-guide/plugins>
3. 安装完成后，访问 Console 左侧的**知识库**菜单管理知识库与文档。
4. 如需游客直接访问公开知识库，请在插件设置中开启**允许未登录用户阅读公开知识库**；如需提供文档下载，请确认**允许导出文档**已开启。
5. 主题侧接入方式见下方「主题适配」。

## 主题适配

此插件为主题端提供了：

- **Finder API** `minidocsFinder`：支持 `listKnowledgeBases(page, size)`、`getKnowledgeBase(name)`、`listDocs(kbName, page, size)`、`getDoc(kbName, docName)`、`getDocTree(kbName)` 和 `getDocBySlug(slug)`，用于在 Thymeleaf / FreeMarker 模板中渲染知识库与文档。
- **公共 REST API**：提供匿名知识库 / 文档查询接口（含文档树、按 slug 获取），可用于前端框架、小程序或服务端集成。
- **数据可见性**：Finder 与公开 API 仅返回 `publicVisible=true` 的知识库及其 `phase=published` 的文档；匿名访问受插件「允许未登录用户阅读」设置约束。

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
