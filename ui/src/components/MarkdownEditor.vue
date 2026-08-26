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

// ============ TOC 手风琴 ============
// cherry 滚动时更新 .current、内容变化时重建列表，用 MutationObserver 覆盖两种时机：
// 折叠非当前查看分支的子标题，只展开当前标题所在分支。
let tocObserver: MutationObserver | null = null;

function applyTocAccordion() {
  const host = hostRef.value;
  if (!host) {
    return;
  }
  const list = host.querySelector<HTMLElement>(
    ".cherry-flex-toc .cherry-toc-list"
  );
  if (!list) {
    return;
  }
  const items = Array.from(
    list.querySelectorAll<HTMLElement>(".cherry-toc-one-a")
  );
  if (items.length === 0) {
    return;
  }
  const levels = items.map((el) => {
    const m = el.className.match(/cherry-toc-one-a__(\d)/);
    return m ? Number(m[1]) : 1;
  });
  const current = list.querySelector(".cherry-toc-one-a.current");
  const currentIdx = current ? items.indexOf(current as HTMLElement) : -1;
  // 判断某个祖先项是否在当前查看标题的祖先链上（无当前项时全部展开）
  const isInCurrentBranch = (ancIdx: number): boolean => {
    if (currentIdx < 0) {
      return true;
    }
    let j = currentIdx;
    while (j >= 0) {
      if (j === ancIdx) {
        return true;
      }
      let k = j - 1;
      while (k >= 0 && levels[k] >= levels[j]) {
        k--;
      }
      j = k;
    }
    return false;
  };
  items.forEach((el, idx) => {
    const lvl = levels[idx];
    // 一级标题始终显示
    if (lvl === 1) {
      el.style.display = "";
      return;
    }
    // 找该项的最近祖先（前一个层级更小的项）
    let ancIdx = -1;
    for (let j = idx - 1; j >= 0; j--) {
      if (levels[j] < lvl) {
        ancIdx = j;
        break;
      }
    }
    el.style.display = isInCurrentBranch(ancIdx) ? "" : "none";
  });
}

function setupTocAccordion() {
  const host = hostRef.value;
  if (!host) {
    return;
  }
  const list = host.querySelector<HTMLElement>(
    ".cherry-flex-toc .cherry-toc-list"
  );
  if (!list) {
    return;
  }
  tocObserver?.disconnect();
  tocObserver = new MutationObserver(() => applyTocAccordion());
  tocObserver.observe(list, {
    childList: true,
    subtree: true,
    attributes: true,
    attributeFilter: ["class"],
  });
  applyTocAccordion();
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
    // 预览区自定义主题：追加自定义 class，配合下方样式美化预览排版（字体/标题/代码块等）
    previewer: {
      className: "minidocs-preview-theme",
    },
    engine: {
      syntax: {
        codeBlock: {
          // 每个 mermaid 图块顶部显示「预览 / 源码」切换
          mermaid: { showSourceToolbar: true },
          changeLang: false,    // 是否显示语言切换
          editCode: false,      // 是否显示编辑按钮
          expandCode: false, // 是否展开/收起代码块，当代码块行数大于10行时，会自动收起代码块
          lineNumber: false, // 默认显示行号
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
  // TOC 手风琴：监听列表变化（滚动高亮/内容重建），自动折叠非当前分支
  setupTocAccordion();
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
  tocObserver?.disconnect();
  tocObserver = null;
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

/* ============ 预览区自定义主题（previewer.className=minidocs-preview-theme） ============
 * 重要：cherry 的 createPreviewer 把 className 与 cherry-markdown 加在【同一个】预览容器
 * 元素上（类名数组 ["cherry-previewer cherry-markdown", className, ...]），并非父子嵌套，
 * 所以样式必须直接作用于 .minidocs-preview-theme，不能用后代选择器 .x .cherry-markdown。
 * scoped 样式无法直接命中 cherry 内部 DOM，需用 :deep() 穿透。
 */
.markdown-editor-wrapper :deep(.minidocs-preview-theme) {
  /* 基础阅读排版：中文字体栈 + 舒适行距 */
  padding: 20px 26px 48px;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
    "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Helvetica Neue",
    Arial, sans-serif;
  font-size: 15px;
  line-height: 1.8;
  color: #2c3e50;
  word-break: break-word;

  /* ---- 标题 ---- */
  h1,
  h2,
  h3,
  h4,
  h5,
  h6 {
    margin: 1.6em 0 0.8em;
    font-weight: 600;
    line-height: 1.4;
    color: #1f2d3d;
    position: relative;
  }
  h1 {
    font-size: 1.7em;
    padding-bottom: 0.4em;
    border-bottom: 2px solid #e8edf3;
  }
  h2 {
    font-size: 1.45em;
    padding-bottom: 0.35em;
    border-bottom: 1px solid #eef2f7;
  }
  h3 {
    font-size: 1.25em;
  }
  h4 {
    font-size: 1.1em;
  }
  h5 {
    font-size: 1em;
  }
  h6 {
    font-size: 0.9em;
    color: #5a6b7f;
  }
  /* 标题前加主题色竖条（h2 起） */
  h2::before,
  h3::before {
    content: "";
    display: inline-block;
    width: 4px;
    height: 0.9em;
    margin-right: 8px;
    border-radius: 2px;
    background: #4c8dff;
    vertical-align: -0.08em;
  }

  /* ---- 段落与间距 ---- */
  p {
    margin: 0.7em 0;
  }

  /* ---- 加粗：主题蓝 ---- */
  strong {
    font-weight: 700;
    color: #2f6fed;
  }

  /* ---- 链接 ---- */
  a {
    color: #2f6fed;
    text-decoration: none;
    border-bottom: 1px solid transparent;
    transition: color 0.15s, border-color 0.15s;
  }
  a:hover {
    color: #1d4ed8;
    border-bottom-color: currentColor;
  }

  /* ---- 行内代码 ---- */
  code:not(pre code) {
    padding: 2px 6px;
    border: none; /* 覆盖 cherry 默认的边框样式 */
    border-radius: 4px;
    background: #f0f4fa;
    color: #d6336c;
    font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo,
      monospace;
    font-size: 0.88em;
  }

  /* ---- 代码块 ---- */
  pre {
    margin: 1em 0;
    padding: 14px 16px;
    border: 1px solid #e5e9f0;
    border-radius: 8px;
    background: #f7f9fc;
    overflow-x: auto;
    line-height: 1.6;
  }
  pre code {
    font-family: "SFMono-Regular", Consolas, "Liberation Mono", Menlo,
      monospace;
    font-size: 13px;
    color: #34495e;
    background: transparent;
  }

  /* ---- 引用 ---- */
  blockquote {
    margin: 1em 0;
    padding: 10px 16px;
    border-left: 4px solid #4c8dff;
    border-radius: 0 6px 6px 0;
    background: #f5f8ff;
    color: #576a82;
  }
  blockquote p {
    margin: 0.4em 0;
  }

  /* ---- 列表 ---- */
  ul,
  ol {
    margin: 0.6em 0;
    padding-left: 1.6em;
  }
  li {
    margin: 0.3em 0;
  }
  ul > li::marker {
    color: #4c8dff;
  }
  ol > li::marker {
    color: #4c8dff;
    font-weight: 600;
  }
  /* 任务清单 */
  input[type="checkbox"] {
    margin-right: 6px;
    accent-color: #4c8dff;
  }

  /* ---- 表格 ---- */
  table {
    margin: 1em 0;
    width: 100%;
    font-size: 0.95em;
    /* 四角圆角：必须用 separate + border-spacing:0，collapse 模式下圆角不生效 */
    border-collapse: separate;
    border-spacing: 0;
    border: 1px solid #e3e8ef;
    border-radius: 8px;
    overflow: hidden; /* 裁剪表头/斑马纹背景的方角，露出圆角 */
  }
  th,
  td {
    padding: 8px 12px;
    border-bottom: 1px solid #e3e8ef;
    border-right: 1px solid #e3e8ef;
    text-align: left;
  }
  /* 去掉最右列右边框和末行下边框，避免与外框线重叠变粗 */
  th:last-child,
  td:last-child {
    border-right: none;
  }
  tbody tr:last-child td {
    border-bottom: none;
  }
  th {
    background: #f3f6fb;
    font-weight: 600;
    color: #1f2d3d;
  }
  tbody tr:nth-child(even) {
    background: #fafbfd;
  }
  tbody tr:hover {
    background: #f0f6ff;
  }

  /* ---- 图片 ---- */
  img {
    max-width: 100%;
    height: auto;
    border-radius: 6px;
    display: block;
    margin: 0.8em auto;
    box-shadow: 0 2px 8px rgba(31, 45, 61, 0.08);
  }

  /* ---- 分隔线 ---- */
  hr {
    margin: 1.6em 0;
    border: none;
    height: 1px;
    background: linear-gradient(90deg, transparent, #d7dee8, transparent);
  }

  /* ---- mermaid 图 ---- */
  figure[data-type="mermaid"] {
    margin: 1em auto;
    text-align: center;
  }
  figure[data-type="mermaid"] svg {
    max-width: 100%;
    height: auto;
  }

  /* ---- 公式（KaTeX）---- */
  .Cherry-Math,
  .Cherry-InlineMath {
    overflow-x: auto;
    overflow-y: hidden;
  }
  .katex-display {
    margin: 1em 0;
  }
}

/* ============ TOC 悬浮目录美化（cherry 挂载到 wrapper 上，独立于预览区） ============
 * 美化仅作用于完整模式（cherry-flex-toc__full）；折叠小圆点模式（__pure）保持 cherry
 * 原生样式，保证点击头部图标「收缩到侧边」功能正常。
 */
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full) {
  /* 卡片化：白底 + 细边框 + 柔和阴影 */
  width: 180px;
  background: #ffffff;
  border: 1px solid #e8edf3;
  border-radius: 10px;
  box-shadow: 0 4px 16px rgba(31, 45, 61, 0.1);
}
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full:hover) {
  width: 280px;
  background: #ffffff;
}
/* 头部：主题蓝渐变 */
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-head) {
  border-bottom: none;
  padding: 8px 12px;
  background: linear-gradient(90deg, #4c8dff, #6ba5ff);
}
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-head .cherry-toc-title) {
  color: #ffffff;
  font-size: 14px;
  font-weight: 600;
}
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-head i) {
  color: rgba(255, 255, 255, 0.85);
}
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-head i:hover) {
  color: #ffffff;
}
/* 列表滚动条 */
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list) {
  scrollbar-width: thin;
  scrollbar-color: #c9d6ea transparent;
}
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list::-webkit-scrollbar) {
  width: 6px;
}
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list::-webkit-scrollbar-thumb) {
  background: #c9d6ea;
  border-radius: 3px;
}
/* 条目：去掉粗橙条，改圆角蓝条 + 悬停/当前项淡蓝底 */
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a) {
  position: relative; /* 供层级连线伪元素定位 */
  box-sizing: border-box;
  height: 32px;
  line-height: 32px;
  margin: 2px 8px;
  width: auto;
  border-left: 3px solid transparent;
  border-radius: 0 6px 6px 0;
  padding-left: 16px;
  color: #2c3e50;
  font-size: 13px;
  transition: all 0.2s;
}
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a:hover),
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a.current) {
  border-left-color: #4c8dff;
  color: #2f6fed;
  background: #eef4ff;
}
/* 层级缩进 16px 均匀递进；字体按层级递减字号、颜色渐浅，一级最突出 */
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a__1) {
  padding-left: 16px;
  font-size: 14px;
  font-weight: 600;
  color: #1f2d3d;
}
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a__2) {
  padding-left: 32px;
  font-size: 13px;
  font-weight: 500;
  color: #2c3e50;
}
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a__3) {
  padding-left: 48px;
  font-size: 12.5px;
  color: #44566c;
}
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a__4) {
  padding-left: 64px;
  font-size: 12px;
  color: #576a82;
}
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a__5) {
  padding-left: 80px;
  font-size: 12px;
  color: #6b7d93;
}
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a__6) {
  padding-left: 96px;
  font-size: 12px;
  color: #7b8ba1;
}
/* 当前章节高亮优先于层级颜色 */
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a.current) {
  color: #2f6fed;
  font-weight: 600;
}
/* 层级连线：所有子级共享同一条 16px 竖线列，竖线上下各延伸 4px 跨过条目间距，
 * 保证整条树线连续不断开；肘线从竖线连到本项文字列，宽度按层级递增（浅蓝灰） */
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a::before),
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a::after) {
  content: "";
  position: absolute;
  background: #d9e2ef;
}
/* 竖线：统一在 16px 列，上下延伸跨过 2px 条目间距 */
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a__2::before),
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a__3::before),
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a__4::before),
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a__5::before),
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a__6::before) {
  left: 16px;
  top: -4px;
  bottom: -4px;
  width: 1px;
}
/* 肘线：从竖线列连到本项文字列，宽度随层级递增 */
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a__2::after) {
  left: 16px;
  top: 50%;
  width: 16px;
  height: 1px;
}
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a__3::after) {
  left: 16px;
  top: 50%;
  width: 32px;
  height: 1px;
}
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a__4::after) {
  left: 16px;
  top: 50%;
  width: 48px;
  height: 1px;
}
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a__5::after) {
  left: 16px;
  top: 50%;
  width: 64px;
  height: 1px;
}
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__full .cherry-toc-list .cherry-toc-one-a__6::after) {
  left: 16px;
  top: 50%;
  width: 80px;
  height: 1px;
}
/* 折叠（pure）小圆点模式的展开箭头改为主题蓝，其余保持 cherry 原生样式 */
.markdown-editor-wrapper :deep(.cherry-flex-toc.cherry-flex-toc__pure .cherry-toc-head .ch-icon-chevronsLeft) {
  color: #4c8dff;
}
</style>