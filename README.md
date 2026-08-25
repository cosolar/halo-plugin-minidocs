# halo-plugin-minidocs

在 Halo 中搭建团队/个人知识库的插件：多知识库管理、文档树、Markdown 编辑、分类标签与权限控制，并提供公开接口与 Finder 能力，方便第三方主题开发用户侧页面。

- 目标平台：Halo `>= 2.26.0`
- 技术栈：Spring Boot + Spring WebFlux（Java 21）+ Vue 3 + TypeScript
- 构建：Gradle（含 `run.halo.plugin.devtools`）+ pnpm

## 功能概览

| 模块 | 说明 |
| --- | --- |
| 知识库管理 | 多知识库 CRUD、公开/私有切换、成员授权、排序 |
| 文档管理 | 文档树、Markdown 编辑、slug 别名、发布状态、标签 |
| 系统设置 | 站点名称/描述、匿名阅读、文档导出开关 |
| 管理界面 | Console 列表页 + 详情页（文档树 + 编辑器） |
| 公开接口 | `/apis/api.minidocs.halo.run/v1alpha1/**` 只读 API + `${minidocsFinder}` Finder |
| 权限 | RoleTemplate 管理/只读角色、匿名聚合、UI 权限控制 |

详细的功能列表、开发细节与支持度评估见 [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)。

## 目录结构

```
├── src/main/
│   ├── java/run/halo/plugin/minidocs/   # 后端：插件入口、扩展模型、服务与端点
│   └── resources/
│       ├── plugin.yaml                  # 插件清单
│       ├── logo.svg                     # 插件 Logo
│       └── extensions/                  # 扩展 YAML 声明（设置、角色模板等）
├── ui/                                  # 前端（Vue 3 + TypeScript + Vite）
│   └── src/
│       ├── index.ts                     # 插件 UI 入口（definePlugin）
│       └── views/                       # 管理页面
├── docs/                                # 开发文档
├── build.gradle / settings.gradle / gradle.properties
└── gradlew(.bat) / gradle/wrapper/
```

## 开发

环境要求：JDK 21、pnpm、Docker（DevTools 运行 Halo 服务）。

```bash
./gradlew haloServer   # 启动 Halo 开发服务（http://localhost:8090/console，admin/admin）
./gradlew reload       # 代码变更后热重载插件
./gradlew watch        # 监听变更自动重载
./gradlew build        # 构建插件 JAR（含 UI 产物）
```

## 许可

[GPL-3.0](LICENSE)
