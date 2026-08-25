<template>
  <div class="markdown-editor-wrapper" ref="wrapperRef">
    <Editor
      :value="value"
      :plugins="plugins"
      :locale="locale"
      :placeholder="placeholder"
      :status="false"
      @change="handleChange"
    />
  </div>
</template>

<script setup lang="ts">
import { ref, watch } from "vue";
import { Editor } from "@bytemd/vue-next";
import gfm from "@bytemd/plugin-gfm";
import highlight from "@bytemd/plugin-highlight";
import mermaid from "@bytemd/plugin-mermaid";
import math from "@bytemd/plugin-math";
import zh_Hans from "bytemd/locales/zh_Hans.json";
import "bytemd/dist/index.css";
import "highlight.js/styles/github.css";
import "katex/dist/katex.min.css";

const props = withDefaults(
  defineProps<{
    content?: string;
    placeholder?: string;
  }>(),
  {
    content: "",
    placeholder: "使用 Markdown 编写文档内容...",
  }
);

const emit = defineEmits<{
  (e: "update:content", value: string): void;
}>();

const plugins = [gfm(), highlight(), mermaid(), math()];
const locale = zh_Hans;

const value = ref(props.content || "");

watch(
  () => props.content,
  (val) => {
    if (val !== value.value) {
      value.value = val || "";
    }
  }
);

function handleChange(v: string) {
  value.value = v;
  emit("update:content", v);
}

const wrapperRef = ref<HTMLElement | null>(null);

function getContent() {
  return value.value;
}

function setContent(content: string) {
  value.value = content || "";
}

// 同时滚动编辑区与预览区到顶部（替代 bytemd 状态栏的「回到顶部」）
function scrollToTop() {
  const wrapper = wrapperRef.value;
  if (!wrapper) {
    return;
  }
  const editorScroll = wrapper.querySelector<HTMLElement>(".CodeMirror-scroll");
  const preview = wrapper.querySelector<HTMLElement>(".bytemd-preview");
  editorScroll?.scrollTo({ top: 0 });
  preview?.scrollTo({ top: 0 });
}

defineExpose({
  getContent,
  setContent,
  scrollToTop,
});
</script>

<style scoped>
.markdown-editor-wrapper {
  position: relative;
  flex: 1 1 auto;
  min-height: 0;
  background: #ffffff;
  overflow: hidden;
}

/* 绝对定位让 bytemd 精确填充容器，高度由外层 flex 链确定 */
.markdown-editor-wrapper :deep(.bytemd) {
  position: absolute !important;
  top: 0 !important;
  left: 0 !important;
  right: 0 !important;
  bottom: 0 !important;
  height: 100% !important;
  overflow: hidden;
  border: none;
  border-radius: 0;
}

/* 用 bytemd 原生高度机制：body 占满除工具栏外的空间（状态栏已隐藏） */
.markdown-editor-wrapper :deep(.bytemd-body) {
  height: calc(100% - 40px);
  overflow: auto;
}

.markdown-editor-wrapper :deep(.bytemd-editor),
.markdown-editor-wrapper :deep(.bytemd-preview) {
  height: 100%;
  min-height: 0;
}

.markdown-editor-wrapper :deep(.bytemd-editor .CodeMirror) {
  font-size: 15px;
  line-height: 1.8;
}

/* 编辑器滚动条样式 */
.markdown-editor-wrapper :deep(.bytemd-body),
.markdown-editor-wrapper :deep(.bytemd-preview),
.markdown-editor-wrapper :deep(.CodeMirror-scroll) {
  scrollbar-width: thin;
  scrollbar-color: #d9d9d9 transparent;
}

/* Mermaid 图表容器 */
.markdown-editor-wrapper :deep(.bytemd-mermaid) {
  display: flex;
  justify-content: center;
  margin: 1em 0;
  padding: 1em;
  background: #ffffff;
  border: 1px solid #eef0f3;
  border-radius: 10px;
  overflow-x: auto;
}

.markdown-editor-wrapper :deep(.bytemd-mermaid svg) {
  max-width: 100%;
  height: auto;
}

.markdown-editor-wrapper :deep(.bytemd-body::-webkit-scrollbar),
.markdown-editor-wrapper :deep(.bytemd-preview::-webkit-scrollbar),
.markdown-editor-wrapper :deep(.CodeMirror-scroll::-webkit-scrollbar) {
  width: 8px;
  height: 8px;
}

.markdown-editor-wrapper :deep(.bytemd-body::-webkit-scrollbar-thumb),
.markdown-editor-wrapper :deep(.bytemd-preview::-webkit-scrollbar-thumb),
.markdown-editor-wrapper :deep(.CodeMirror-scroll::-webkit-scrollbar-thumb) {
  background: #d9d9d9;
  border-radius: 4px;
}

.markdown-editor-wrapper :deep(.bytemd-body::-webkit-scrollbar-thumb:hover),
.markdown-editor-wrapper :deep(.bytemd-preview::-webkit-scrollbar-thumb:hover),
.markdown-editor-wrapper :deep(.CodeMirror-scroll::-webkit-scrollbar-thumb:hover) {
  background: #bfbfbf;
}

.markdown-editor-wrapper :deep(.bytemd-body::-webkit-scrollbar-track),
.markdown-editor-wrapper :deep(.bytemd-preview::-webkit-scrollbar-track),
.markdown-editor-wrapper :deep(.CodeMirror-scroll::-webkit-scrollbar-track) {
  background: transparent;
}
</style>
