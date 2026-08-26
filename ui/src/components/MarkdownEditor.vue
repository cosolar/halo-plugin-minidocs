<template>
  <div class="markdown-editor-wrapper">
    <div class="cherry-host" :id="hostId" ref="hostRef"></div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import Cherry from "cherry-markdown/dist/cherry-markdown.core.esm.js";
import "cherry-markdown/dist/cherry-markdown.css";
// 公式渲染：cherry 的 mathBlock/inlineMath 通过 externals.katex 或 window.katex 取 KaTeX 实例，
// 需显式传入 katex 并引入其样式，否则公式块退化为纯文本。
import katex from "katex";
import "katex/dist/katex.min.css";
// Halo 附件上传：使用控制台 API 上传到默认存储策略，返回 Attachment 的公开访问地址
import { consoleApiClient } from "@halo-dev/api-client";

const props = withDefaults(
  defineProps<{
    content?: string;
    // 初始/当前视图模型：edit=分屏实时预览，preview=只读预览
    model?: "edit" | "preview";
  }>(),
  {
    content: "",
    model: "edit",
  }
);

const emit = defineEmits<{
  (e: "update:content", value: string): void;
}>();

const hostRef = ref<HTMLElement | null>(null);
const hostId = `cherry-md-${Math.random().toString(36).slice(2, 10)}`;

let cherry: Cherry | null = null;

// Cherry.usePlugin 是静态全局注册，一旦任何 Cherry 实例被创建（Cherry.initialized=true）
// 就禁止再次调用。懒加载组件在路由切换/重挂载时可能被重复 import（模块重新求值），
// 若用模块级 async IIFE 会二次执行 usePlugin 而报错。故用 window 级守卫，
// 确保 mermaid 插件在整页全局生命周期内只注册一次。
const PLUGIN_REGISTERED_KEY = "__minidocsCherryMermaidRegistered__";

async function ensureMermaidPluginRegistered() {
  const g = window as unknown as Record<string, unknown>;
  if (g[PLUGIN_REGISTERED_KEY]) {
    return;
  }
  g[PLUGIN_REGISTERED_KEY] = true;
  const mermaidMod = await import("cherry-markdown/dist/cherry-markdown.core.esm.js");
  const mermaidPlugin = (mermaidMod as unknown as { MermaidPlugin: unknown }).MermaidPlugin;
  // import("mermaid") 返回的是模块命名空间，顶层只有 default/clearLayoutRenderState 等，
  // 不含 render/initialize；MermaidPlugin 构造时会调用 this.mermaidAPIRefs.initialize，
  // 若直接传命名空间对象会抛 "initialize is not a function"。必须取 .default（真正的
  // mermaid API 对象）并显式传 mermaidAPI，否则插件 mermaidAPIRefs=null 或构造失败。
  const mermaidInstance = (await import("mermaid")).default;
  Cherry.usePlugin(mermaidPlugin as never, { mermaidAPI: mermaidInstance });
}

const cherryReadyPromise = ensureMermaidPluginRegistered();

// 通过 Halo 控制台附件接口上传到默认存储策略，返回附件公开访问地址（permalink）
async function uploadFileToHalo(file: File): Promise<string> {
  const { data } = await consoleApiClient.storage.attachment.uploadAttachmentForConsole({
    file,
  });
  const url = data.status?.permalink;
  if (!url) {
    throw new Error(`附件上传失败：${file.name}`);
  }
  return url;
}

onMounted(async () => {
  if (!hostRef.value) {
    return;
  }
  await cherryReadyPromise;
  // 等待期间组件可能已被卸载，避免在已销毁的挂载点上初始化
  if (!hostRef.value.isConnected) {
    return;
  }
  cherry = new Cherry({
    id: hostId,
    value: props.content || "",
    editor: {
      // 分屏实时预览：左侧源码编辑，右侧实时渲染（mermaid/公式/代码高亮由 cherry 懒渲染）
      defaultModel: "edit&preview",
    },
    // 公式渲染依赖外部 KaTeX 实例：cherry 的 mathBlock/inlineMath 从 externals.katex 取 API
    externals: { katex },
    // 图片/文件上传：点击工具栏、拖拽或粘贴图片时调用，上传到 Halo 附件后回填公开地址
    fileUpload: (file: File, callback) => {
      uploadFileToHalo(file)
        .then((url) => callback(url, { name: file.name }))
        .catch((e) => {
          console.error("附件上传失败", e);
        });
    },
    engine: {
      syntax: {
        codeBlock: {
          // 每个 mermaid 图块顶部显示「预览 / 源码」切换
          mermaid: { showSourceToolbar: true },
          changeLang: false,    // 是否显示语言切换
          editCode: false,      // 是否显示编辑按钮
        },
        // 公式引擎：块级 $$...$$ 与行内 $...$ 均用 KaTeX 渲染（默认 MathJax 需额外加载脚本）
        mathBlock: { engine: "katex", selfClosing: false },
        inlineMath: { engine: "katex" },
      },
    },
    toolbars: {
      // 编辑模式工具栏：聚焦 Markdown 排版；内置「编辑/预览切换」「导出」由 cherry 提供
      toolbar: [
        "h1",
        "h2",
        "h3",
        "|",
        "bold",
        "italic",
        "strikethrough",
        "|",
        "ul",
        "ol",
        "checklist",
        "quote",
        "|",
        "code",
        "formula",
        "graph",
        "image",
        "link",
        "table",
        "hr",
        "|",
        "toc",
        "undo",
        "redo",
      ],
      toolbarRight: ["togglePreview", "|", "export"],
      // 悬浮目录：full=完整展示所有标题，position=absolute 跟随 cherry 内部滚动条
      toc: {
        defaultModel: "full",
        position: "absolute",
        updateLocationHash: false,
      },
    },
    callback: {
      afterChange: (markdown: string) => {
        emit("update:content", markdown);
      },
      // 多文件上传：工具栏选择多张图片时批量上传到 Halo 附件
      // 注意：cherry 的 fileUploadMulti 回调运行时接收 {url, params}[] 数组，但其类型声明
      // 误写成了单 url 字符串，这里按运行时行为断言回调参数类型
      fileUploadMulti: (files: File[], callback) => {
        const cb = callback as unknown as (
          results: { url: string; params?: { name?: string } }[]
        ) => void;
        Promise.all(files.map(uploadFileToHalo))
          .then((urls) =>
            cb(
              urls.map((url, i) => ({
                url,
                params: { name: files[i].name },
              }))
            )
          )
          .catch((e) => {
            console.error("附件上传失败", e);
          });
      },
    },
  });
  // 初始视图为只读预览时，直接切到 previewOnly
  if (props.model === "preview") {
    cherry.switchModel("previewOnly");
  }
});

// 由父组件控制顶部「预览/编辑」切换
watch(
  () => props.model,
  (m) => {
    if (!cherry) {
      return;
    }
    if (m === "preview") {
      cherry.switchModel("previewOnly");
    } else {
      cherry.switchModel("edit&preview");
    }
  }
);

// 确保外部 setContent 不回写文本导致 afterChange 循环
watch(
  () => props.content,
  (val) => {
    if (cherry && val !== cherry.getMarkdown()) {
      cherry.setMarkdown(val || "");
    }
  }
);

onBeforeUnmount(() => {
  cherry?.destroy();
  cherry = null;
});

function getContent() {
  return cherry ? cherry.getMarkdown() : "";
}

function getHtml() {
  return cherry ? cherry.getHtml() : "";
}

function setContent(content: string) {
  if (cherry) {
    cherry.setMarkdown(content || "");
  }
}

function switchToPreview() {
  cherry?.switchModel("previewOnly");
}

function switchToEdit() {
  cherry?.switchModel("edit&preview");
}

function scrollToTop() {
  if (!cherry || !hostRef.value) {
    return;
  }
  // 先尝试滚编辑区（CodeMirror）
  const view = cherry.getCodeMirror() as { scrollDOM?: HTMLElement } | undefined;
  if (view?.scrollDOM) {
    view.scrollDOM.scrollTop = 0;
    return;
  }
  // 只读预览下滚预览内容容器
  const preview = hostRef.value.querySelector<HTMLElement>(".cherry-previewer");
  if (preview) {
    preview.scrollTop = 0;
  }
}

defineExpose({
  getContent,
  getHtml,
  setContent,
  scrollToTop,
  switchToPreview,
  switchToEdit,
});
</script>

<style scoped>
.markdown-editor-wrapper {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
  overflow: hidden;
  background: #ffffff;
}

/* 挂载容器精确填满外层 flex 链 */
.cherry-host {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  height: 100%;
  overflow: auto;
}
</style>