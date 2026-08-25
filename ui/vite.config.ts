import { viteConfig } from "@halo-dev/ui-plugin-bundler-kit/vite";
import UnoCSS from "unocss/vite";

// 使用 Halo UI 插件打包器预设配置，
// 产物输出到 ui/build/dist，由根项目的 processUiResources 打包进插件 JAR
export default viteConfig({
  vite: {
    plugins: [UnoCSS()],
  },
});
