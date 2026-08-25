<template>
  <div class="doc-preview">
    <div class="preview-scroll" ref="scrollRef" @scroll="onScroll">
      <div
        class="markdown-body preview-content"
        :style="{ maxWidth: `${width}px` }"
        ref="contentRef"
        v-html="html"
      ></div>
    </div>
    <aside v-if="outline.length" class="outline-panel">
      <div class="outline-header">
        <span>目录</span>
      </div>
      <ul class="outline-list">
        <li
          v-for="item in outline"
          :key="item.id"
          class="outline-item"
          :class="[`level-${Math.min(item.level, 6)}`, { active: item.id === activeId }]"
        >
          <a :href="`#${item.id}`" @click.prevent="scrollTo(item.id)">{{ item.text }}</a>
        </li>
      </ul>
    </aside>
  </div>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from "vue";
import "katex/dist/katex.min.css";
import "highlight.js/styles/github-dark.css";

interface OutlineItem {
  id: string;
  text: string;
  level: number;
}

const props = withDefaults(
  defineProps<{
    html: string;
    width?: number;
    visible?: boolean;
  }>(),
  {
    width: 960,
    visible: true,
  }
);

const scrollRef = ref<HTMLElement | null>(null);
const contentRef = ref<HTMLElement | null>(null);
const outline = ref<OutlineItem[]>([]);
const activeId = ref("");

// mermaid 体积较大，按需懒加载
let mermaidPromise: Promise<typeof import("mermaid")> | null = null;
function getMermaid() {
  if (!mermaidPromise) {
    mermaidPromise = import("mermaid").then((mod) => {
      mod.default.initialize({
        startOnLoad: false,
        theme: "default",
        fontFamily: "inherit",
      });
      return mod;
    });
  }
  return mermaidPromise;
}

async function renderMermaid() {
  const container = contentRef.value;
  if (!container) {
    return;
  }
  const elements = container.querySelectorAll<HTMLElement>(".mermaid");
  if (!elements.length) {
    return;
  }
  const mermaid = await getMermaid();
  for (const el of Array.from(elements)) {
    const id = `mermaid-${Math.random().toString(36).slice(2, 10)}`;
    try {
      const { svg } = await mermaid.default.render(id, el.textContent || "");
      el.innerHTML = svg;
      el.classList.add("rendered");
    } catch (err) {
      el.innerHTML = `<pre class="mermaid-error">${el.textContent}</pre>`;
    }
  }
}

function buildOutline() {
  const container = contentRef.value;
  if (!container) {
    return;
  }
  const headings = container.querySelectorAll("h1, h2, h3, h4, h5, h6");
  const items: OutlineItem[] = [];
  headings.forEach((h, i) => {
    const id = `heading-${i}`;
    h.id = id;
    const text = (h.textContent || "").trim();
    if (text) {
      items.push({ id, text, level: Number(h.tagName[1]) });
    }
  });
  outline.value = items;
}

function onScroll() {
  const container = scrollRef.value;
  if (!container) {
    return;
  }
  const headings = container.querySelectorAll("h1, h2, h3, h4, h5, h6");
  const containerTop = container.getBoundingClientRect().top;
  let current = "";
  for (const h of Array.from(headings)) {
    if (h.getBoundingClientRect().top - containerTop <= 16) {
      current = h.id;
    }
  }
  activeId.value = current;
}

function scrollTo(id: string) {
  const container = scrollRef.value;
  const target = container?.querySelector(`#${id}`);
  if (container && target) {
    const rect = (target as HTMLElement).getBoundingClientRect();
    const containerRect = container.getBoundingClientRect();
    const top = container.scrollTop + rect.top - containerRect.top - 16;
    container.scrollTo({ top, behavior: "smooth" });
  }
}

async function refresh() {
  await nextTick();
  await renderMermaid();
  buildOutline();
  activeId.value = "";
}

watch(
  () => props.html,
  () => {
    if (props.visible) {
      refresh();
    }
  }
);

watch(
  () => props.visible,
  (v) => {
    if (v) {
      refresh();
    }
  }
);

onMounted(() => {
  if (props.visible) {
    refresh();
  }
});
onBeforeUnmount(() => {
  mermaidPromise = null;
});
</script>

<style scoped>
.doc-preview {
  flex: 1 1 auto;
  display: flex;
  min-height: 0;
  overflow: hidden;
  background: #f7f8fa;
  border: 1px solid #eef0f3;
  border-radius: 0.75rem;
}

/* ===== 预览滚动区 ===== */
.preview-scroll {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  scroll-behavior: smooth;
}

.preview-scroll::-webkit-scrollbar {
  width: 8px;
  height: 8px;
}

.preview-scroll::-webkit-scrollbar-thumb {
  background: #d9d9d9;
  border-radius: 4px;
}

.preview-scroll::-webkit-scrollbar-thumb:hover {
  background: #bfbfbf;
}

.preview-content {
  margin: 0 auto;
  padding: 2.25rem 2.5rem 3rem;
  font-size: 15px;
  line-height: 1.85;
  color: #3f4756;
  background: #ffffff;
  min-height: 100%;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}

/* ===== 大纲面板 ===== */
.outline-panel {
  flex: 0 0 220px;
  width: 220px;
  min-height: 0;
  overflow-y: auto;
  padding: 1rem 0.75rem;
  background: #ffffff;
  border-left: 1px solid #eef0f3;
}

.outline-panel::-webkit-scrollbar {
  width: 6px;
}

.outline-panel::-webkit-scrollbar-thumb {
  background: #e0e0e0;
  border-radius: 3px;
}

.outline-header {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0 0.5rem 0.625rem;
  font-size: 0.75rem;
  font-weight: 600;
  color: #8c8c8c;
  letter-spacing: 0.05em;
  border-bottom: 1px solid #f0f0f0;
  margin-bottom: 0.5rem;
}

.outline-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.outline-item a {
  display: block;
  padding: 0.25rem 0.5rem;
  font-size: 0.75rem;
  line-height: 1.5;
  color: #8c8c8c;
  text-decoration: none;
  border-radius: 0.375rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  transition: all 0.15s ease;
}

.outline-item a:hover {
  color: #595959;
  background: #f5f5f5;
}

.outline-item.active a {
  color: #389e0d;
  background: #f6ffed;
  font-weight: 500;
}

.outline-item.level-2 a {
  padding-left: 1rem;
}

.outline-item.level-3 a {
  padding-left: 1.5rem;
}

.outline-item.level-4 a,
.outline-item.level-5 a,
.outline-item.level-6 a {
  padding-left: 2rem;
}

/* ===== 预览主题 ===== */
.preview-content :deep(h1),
.preview-content :deep(h2),
.preview-content :deep(h3),
.preview-content :deep(h4),
.preview-content :deep(h5),
.preview-content :deep(h6) {
  color: #1f2937;
  font-weight: 700;
  line-height: 1.4;
  margin: 1.6em 0 0.6em;
  scroll-margin-top: 16px;
}

.preview-content :deep(h1) {
  font-size: 1.85em;
  padding-bottom: 0.35em;
  border-bottom: 1px solid #eef0f3;
  margin-top: 0;
}

.preview-content :deep(h2) {
  font-size: 1.5em;
  padding-bottom: 0.3em;
  border-bottom: 1px solid #eef0f3;
}

.preview-content :deep(h3) {
  font-size: 1.25em;
}

.preview-content :deep(h4) {
  font-size: 1.1em;
}

.preview-content :deep(p) {
  margin: 0.85em 0;
}

.preview-content :deep(a) {
  color: #389e0d;
  text-decoration: none;
  border-bottom: 1px solid transparent;
  transition: border-color 0.15s ease;
}

.preview-content :deep(a:hover) {
  color: #237804;
  border-bottom-color: #237804;
}

.preview-content :deep(strong) {
  color: #1f2937;
  font-weight: 600;
}

.preview-content :deep(blockquote) {
  margin: 1.1em 0;
  padding: 0.75em 1.25em;
  color: #4b5563;
  background: #f6ffed;
  border-left: 4px solid #52c41a;
  border-radius: 0 8px 8px 0;
}

.preview-content :deep(blockquote p) {
  margin: 0.35em 0;
}

.preview-content :deep(code) {
  padding: 0.18em 0.45em;
  font-size: 0.875em;
  color: #d4380d;
  background: #fff7e6;
  border-radius: 4px;
  font-family: "JetBrains Mono", "Fira Code", Consolas, monospace;
}

.preview-content :deep(pre) {
  margin: 1.1em 0;
  padding: 1.1em 1.25em;
  overflow-x: auto;
  background: #1e293b;
  border-radius: 10px;
  line-height: 1.65;
}

.preview-content :deep(pre code) {
  padding: 0;
  font-size: 13.5px;
  color: #e2e8f0;
  background: transparent;
  border-radius: 0;
}

.preview-content :deep(pre code.hljs) {
  background: transparent;
}

.preview-content :deep(ul),
.preview-content :deep(ol) {
  padding-left: 1.75em;
  margin: 0.85em 0;
}

.preview-content :deep(li) {
  margin: 0.35em 0;
}

.preview-content :deep(li > input[type="checkbox"]) {
  margin-right: 0.4em;
}

.preview-content :deep(li:has(> input[type="checkbox"]:checked)) {
  color: #9ca3af;
  text-decoration: line-through;
}

.preview-content :deep(img) {
  max-width: 100%;
  height: auto;
  border-radius: 10px;
  margin: 0.85em 0;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.06);
}

.preview-content :deep(table) {
  width: 100%;
  margin: 1.1em 0;
  border-collapse: collapse;
  border: 1px solid #eef0f3;
  border-radius: 10px;
  overflow: hidden;
  font-size: 0.9375em;
}

.preview-content :deep(th) {
  padding: 0.65em 0.9em;
  text-align: left;
  font-weight: 600;
  color: #1f2937;
  background: #fafbfc;
  border-bottom: 2px solid #eef0f3;
}

.preview-content :deep(td) {
  padding: 0.6em 0.9em;
  border-bottom: 1px solid #f3f4f6;
}

.preview-content :deep(tr:last-child td) {
  border-bottom: none;
}

.preview-content :deep(tr:hover td) {
  background: #fafbfc;
}

.preview-content :deep(hr) {
  border: none;
  border-top: 2px solid #eef0f3;
  margin: 1.8em 0;
}

/* Mermaid 图表 */
.preview-content :deep(.mermaid) {
  margin: 1.1em 0;
  display: flex;
  justify-content: center;
  padding: 1.25em;
  background: #ffffff;
  border: 1px solid #eef0f3;
  border-radius: 10px;
  overflow-x: auto;
}

.preview-content :deep(.mermaid svg) {
  max-width: 100%;
  height: auto;
}

.preview-content :deep(.mermaid-error) {
  margin: 0;
  padding: 0.75em 1em;
  color: #cf1322;
  background: #fff1f0;
  border-radius: 6px;
  font-size: 0.8125rem;
  white-space: pre-wrap;
}

/* 公式 */
.preview-content :deep(.katex-display) {
  margin: 1.2em 0;
  overflow-x: auto;
  overflow-y: hidden;
  padding: 0.5em 0;
}

.preview-content :deep(.katex) {
  font-size: 1.05em;
}
</style>
