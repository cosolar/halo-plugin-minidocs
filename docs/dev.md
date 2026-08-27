# 开发文档

## 克隆仓库

```bash
git clone https://github.com/cosolar/halo-plugin-minidocs.git

# 或者当你 fork 之后
git clone https://github.com:cosolar/halo-plugin-minidocs.git
```

## 启动开发环境

所需环境依赖：

1. JDK 21
2. Docker（DevTools 运行 Halo 服务时可能需要）
3. Node.js 24
4. pnpm 11

```bash
# macOS / Linux
./gradlew haloServer

# Windows
./gradlew.bat haloServer
```

启动完成后，访问 `http://localhost:8090/console`，默认账号密码为 `admin` / `admin`。插件 Console 页面路径为左侧菜单的**知识库**。

代码变更后热重载插件：

```bash
# macOS / Linux
./gradlew reload

# Windows
./gradlew.bat reload
```

监听文件变更自动重载：

```bash
./gradlew watch
```

## 前端开发

前端代码位于 `ui/` 目录，使用 Vite + Vue 3 + TypeScript 构建，UI 产物通过 Gradle 任务打包进插件 JAR。

```bash
cd ui

pnpm install
pnpm dev          # 开发监听模式
pnpm build        # 生产构建
pnpm type-check   # vue-tsc --noEmit
```

前端直接依赖 `@halo-dev/api-client` 调用 Halo 与插件接口，无需额外的 API 客户端生成步骤。

> 注意：`ui/build.gradle` 中的 `pnpmBuild` 任务在 Windows 下通过 `cmd /c pnpm build` 包装，以正确解析 `pnpm.cmd`；构建插件 JAR 时会自动先执行前端构建。

## 运行测试

```bash
./gradlew test
```

## 构建插件

```bash
# macOS / Linux
./gradlew build

# Windows
./gradlew.bat build
```

构建产物位于 `build/libs/`。该任务会先构建 `ui/` 子项目，将其 `dist` 产物打包进插件 JAR 的 `ui` 资源目录。

如需构建后通过 `halo` CLI 直接升级线上 Halo 的插件，可执行：

```bash
./gradlew upgradePlugin
```

使用前需先配置线上服务器：`halo auth login`（或 `halo auth login --profile 线上环境名`）。
