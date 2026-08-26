<template>
  <div class="markdown-editor-wrapper">
    <div class="cherry-host" :id="hostId" ref="hostRef"></div>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from "vue";
import Cherry from "cherry-markdown/dist/cherry-markdown.core.esm.js";
import "cherry-markdown/dist/cherry-markdown.css";

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

// core 包不带 mermaid 渲染，需显式注册 MermaidPlugin 并把 mermaid 实例传给 Cherry。
// MermaidPlugin 运行时由 core 模块导出，但其子路径 .d.ts 未声明，故运行时动态取。
let mermaidPluginReady = false;
async function ensureMermaidPlugin() {
  if (mermaidPluginReady) {
    return;
  }
  const mermaidMod = await import("cherry-markdown/dist/cherry-markdown.core.esm.js");
  const mermaidPlugin = (mermaidMod as unknown as { MermaidPlugin: unknown }).MermaidPlugin;
  const mermaidInstance = (await import("mermaid")).default;
  Cherry.usePlugin(mermaidPlugin as never, { mermaid: mermaidInstance });
  mermaidPluginReady = true;
}

onMounted(async () => {
  if (!hostRef.value) {
    return;
  }
  await ensureMermaidPlugin();
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
        "undo",
        "redo",
      ],
      toolbarRight: ["togglePreview", "|", "export"],
    },
    callback: {
      afterChange: (markdown: string) => {
        emit("update:content", markdown);
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