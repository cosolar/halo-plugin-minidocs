<script setup lang="ts">
import { computed, defineAsyncComponent, onBeforeUnmount, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import {
  Dialog,
  Toast,
  VButton,
  VEmpty,
  VLoading,
  VModal,
  VSpace,
  IconArrowLeft,
  IconArrowUpLine,
  IconAddCircle,
  IconFolder,
  IconPages,
  IconArrowRight,
  IconArrowDown,
  IconRiPencilFill,
  IconSave,
  IconSendPlaneFill,
  IconDeleteBin,
  IconClipboardLine,
  IconSettings,
  IconSearch,
  IconClose,
  IconBookRead,
  IconInformation,
  IconUpload,
} from "@halo-dev/components";
import { axiosInstance, consoleApiClient } from "@halo-dev/api-client";
import { utils } from "@halo-dev/ui-shared";
// cherry-markdown 完整包体积较大，按需懒加载
const MarkdownEditor = defineAsyncComponent(
  () => import("../components/MarkdownEditor.vue")
);
import { htmlToMarkdown } from "../utils/markdown";
import { renderMarkdownToHtml } from "../utils/mdRenderer";

interface KnowledgeBase {
  metadata: { name: string; creationTimestamp?: string };
  spec: {
    displayName: string;
    description?: string;
    publicVisible?: boolean;
    creatorName?: string;
    creationTime?: string;
    cover?: string;
    updateTime?: string;
    [key: string]: unknown;
  };
}

interface DocTreeNode {
  name: string;
  title: string;
  slug?: string;
  phase?: string;
  publishTime?: string;
  priority?: number;
  parentName?: string;
  children?: DocTreeNode[];
}

interface KnowledgeBaseDoc {
  metadata: { name: string; creationTimestamp?: string };
  spec: {
    knowledgeBaseName: string;
    title: string;
    slug?: string;
    author?: string;
    creationTime?: string;
    cover?: string;
    summary?: string;
    updateTime?: string;
    raw?: string;
    content?: string;
    parentName?: string;
    priority?: number;
    tags?: string[];
    phase?: string;
    publishTime?: string;
  };
}

interface FlattenedNode {
  node: DocTreeNode;
  depth: number;
}

const API_PREFIX = "/apis/console.api.minidocs.halo.run/v1alpha1";

const route = useRoute();
const router = useRouter();
const kbName = route.params.name as string;

const kb = ref<KnowledgeBase | null>(null);
const tree = ref<DocTreeNode[]>([]);
const treeLoading = ref(false);
const selected = ref<DocTreeNode | null>(null);
const doc = ref<KnowledgeBaseDoc | null>(null);
const docLoading = ref(false);
const saving = ref(false);

const docForm = reactive({
  title: "",
  raw: "",
  markdown: "",
  slug: "",
  priority: "",
  author: "",
  cover: "",
  summary: "",
  phase: "draft",
});
const moveParent = ref("");
const moveModalVisible = ref(false);
const createModalVisible = ref(false);
const createTitle = ref("");
const parentTarget = ref<DocTreeNode | null>(null);

// 文档封面上传
const docCoverInput = ref<HTMLInputElement | null>(null);

// 批量导入 Markdown
const importModalVisible = ref(false);
const importParent = ref("");
const importFiles = ref<File[]>([]);
const importFileInput = ref<HTMLInputElement | null>(null);
const importing = ref(false);
// 字数惰性计算，避免每字符输入都对大字符串 replace 一次
const wordCount = computed(() =>
  docForm.markdown ? docForm.markdown.replace(/\s/g, "").length : 0
);

// Markdown 编辑器引用（懒加载组件）
const markdownEditorRef = ref<{
  getContent: () => string;
  getHtml: () => string;
  setContent: (c: string) => void;
  scrollToTop: () => void;
  switchToPreview: () => void;
  switchToEdit: () => void;
} | null>(null);

const expanded = ref<Set<string>>(new Set());
const allExpanded = ref(false);

// 左侧边栏拖拽调整宽度（默认 280px，可拖 220~600px），右侧主区随 flex 自动跟随
const sidebarWidth = ref(280);
let resizeStartX = 0;
let resizeStartWidth = 280;
function startResize(e: PointerEvent) {
  if ((e.target as HTMLElement).closest("button, a, input, select, textarea")) {
    return;
  }
  resizeStartX = e.clientX;
  resizeStartWidth = sidebarWidth.value;
  window.addEventListener("pointermove", onResizeMove);
  window.addEventListener("pointerup", onResizeEnd);
  document.body.style.cursor = "col-resize";
  document.body.style.userSelect = "none";
  window.getSelection()?.removeAllRanges();
  e.preventDefault();
}
function onResizeMove(e: PointerEvent) {
  const w = resizeStartWidth + (e.clientX - resizeStartX);
  sidebarWidth.value = Math.min(600, Math.max(220, w));
}
function onResizeEnd() {
  window.removeEventListener("pointermove", onResizeMove);
  window.removeEventListener("pointerup", onResizeEnd);
  document.body.style.cursor = "";
  document.body.style.userSelect = "";
}
onBeforeUnmount(() => {
  window.removeEventListener("pointermove", onResizeMove);
  window.removeEventListener("pointerup", onResizeEnd);
  document.body.style.cursor = "";
  document.body.style.userSelect = "";
});

// 搜索
const searchKeyword = ref("");
let searchTimer: ReturnType<typeof setTimeout> | null = null;

// 编辑 / 预览（默认预览模式，由 cherry 实例在两个视图模型间切换）
const previewMode = ref(true);

// 设置抽屉
const settingsVisible = ref(false);
const tags = ref<string[]>([]);
const tagInput = ref("");
const tagInputRef = ref<HTMLInputElement | null>(null);

// 重命名
const renameModalVisible = ref(false);
const renameTitle = ref("");
const renameTarget = ref<DocTreeNode | null>(null);

// 预览/编辑由编辑器内的同一 cherry 实例在两个视图模型间切换，预览渲染交给 cherry
const currentEditorModel = computed(() => (previewMode.value ? "preview" : "edit"));

// 当前文档路径（知识库名 / 目录 / 文档名）
const docPath = computed(() => {
  if (!selected.value) {
    return "";
  }
  const parts: string[] = [];
  const find = (nodes: DocTreeNode[], name: string): boolean => {
    for (const node of nodes) {
      if (node.name === name) {
        parts.unshift(node.title);
        return true;
      }
      if (find(node.children || [], name)) {
        parts.unshift(node.title);
        return true;
      }
    }
    return false;
  };
  find(tree.value, selected.value.name);
  return parts.join(" / ");
});

// 当前文档行数
const lineCount = computed(() =>
  docForm.markdown ? docForm.markdown.split("\n").length : 0
);

// 当前文档作者（创建人，系统自动写入）
const docAuthor = computed(() => doc.value?.spec.author?.trim() || "-");

// 当前文档创建时间：优先后端 spec.creationTime，回退到 metadata.creationTimestamp
const createdTime = computed(() => {
  const t =
    doc.value?.spec.creationTime || doc.value?.metadata?.creationTimestamp;
  return t ? utils.date.format(t, "YYYY-MM-DD HH:mm") : "-";
});

// 当前文档更新时间：优先后端记录的 updateTime，回退到创建时间，避免显示 "-"
const updateTime = computed(() => {
  const t = doc.value?.spec.updateTime || doc.value?.metadata?.creationTimestamp;
  return t ? utils.date.format(t, "YYYY-MM-DD HH:mm") : "-";
});

const flattened = computed<FlattenedNode[]>(() => {
  const result: FlattenedNode[] = [];
  const walk = (nodes: DocTreeNode[], depth: number) => {
    nodes.forEach((node) => {
      result.push({ node, depth });
      if (expanded.value.has(node.name)) {
        walk(node.children || [], depth + 1);
      }
    });
  };
  walk(tree.value, 0);
  return result;
});

// 搜索过滤：命中节点及其所有祖先均保留
const filteredFlattened = computed<FlattenedNode[]>(() => {
  const kw = searchKeyword.value.trim().toLowerCase();
  if (!kw) {
    return flattened.value;
  }
  const matched = new Set<string>();
  const collect = (nodes: DocTreeNode[], ancestors: string[]) => {
    nodes.forEach((node) => {
      const chain = [...ancestors, node.name];
      if (node.title.toLowerCase().includes(kw)) {
        chain.forEach((name) => matched.add(name));
      }
      collect(node.children || [], chain);
    });
  };
  collect(tree.value, []);
  return flattened.value.filter((item) => matched.has(item.node.name));
});

// 知识库信息统计（从文档树计算）
const kbStats = computed(() => {
  let total = 0;
  let published = 0;
  let lastUpdate = "";
  const walk = (nodes: DocTreeNode[]) => {
    nodes.forEach((node) => {
      total++;
      if (node.phase === "published") {
        published++;
      }
      if (node.publishTime && node.publishTime > lastUpdate) {
        lastUpdate = node.publishTime;
      }
      walk(node.children || []);
    });
  };
  walk(tree.value);
  return { total, published, lastUpdate };
});

function isExpanded(node: DocTreeNode) {
  return expanded.value.has(node.name);
}

function toggleExpand(node: DocTreeNode) {
  if (expanded.value.has(node.name)) {
    expanded.value.delete(node.name);
  } else {
    expanded.value.add(node.name);
  }
}

function toggleExpandAll() {
  allExpanded.value = !allExpanded.value;
  expanded.value.clear();
  if (allExpanded.value) {
    const collect = (nodes: DocTreeNode[]) => {
      nodes.forEach((node) => {
        if ((node.children || []).length) {
          expanded.value.add(node.name);
          collect(node.children || []);
        }
      });
    };
    collect(tree.value);
  }
}

function hasChildren(node: DocTreeNode) {
  return (node.children || []).length > 0;
}

const moveOptions = computed(() => {
  if (!selected.value) {
    return [];
  }
  const excluded = new Set<string>();
  collectSubtree(selected.value, excluded);
  return flattened.value
    .filter((item) => !excluded.has(item.node.name))
    .map((item) => ({
      label: `${"\u3000".repeat(item.depth)}${item.node.title}`,
      value: item.node.name,
    }));
});

// 移动目标位置（树形，排除当前节点及其子树）
const moveTargets = computed(() => {
  if (!selected.value) {
    return [];
  }
  const excluded = new Set<string>();
  collectSubtree(selected.value, excluded);
  return flattened.value.filter((item) => !excluded.has(item.node.name));
});

function collectSubtree(node: DocTreeNode, set: Set<string>) {
  set.add(node.name);
  (node.children || []).forEach((child) => collectSubtree(child, set));
}

// ===== 拖拽移动（同级排序 / 拖到其他目录下 / 拖到空白处移至顶层） =====
type DropPosition = "before" | "child" | "after" | "top";

const dragState = reactive({
  dragging: false,
  fromName: "",
  targetName: "", // 当前悬停的目标节点
  position: "child" as DropPosition, // 目标处的放置位置：之前 / 作为子级 / 之后 / 顶层
  targetTop: false, // 悬停到空白区域 → 移动到顶层
});

// 被拖拽节点的整棵子树，用于排除这些节点作为放置目标
const dragSubtree = computed(() => {
  const set = new Set<string>();
  const fromName = dragState.fromName;
  if (!fromName) {
    return set;
  }
  const find = (nodes: DocTreeNode[]): boolean => {
    for (const node of nodes) {
      if (node.name === fromName) {
        collectSubtree(node, set);
        return true;
      }
      if (find(node.children || [])) {
        return true;
      }
    }
    return false;
  };
  find(tree.value);
  return set;
});

function canDropTarget(node: DocTreeNode) {
  if (!dragState.dragging) {
    return false;
  }
  return node.name !== dragState.fromName && !dragSubtree.value.has(node.name);
}

// 根据悬停纵向位置判断放置位置：上 30% 之前、中间 40% 子级、下 30% 之后
function zoneFor(e: DragEvent): DropPosition {
  const el = e.currentTarget as HTMLElement | null;
  if (!el || el.clientHeight <= 0) {
    return "child";
  }
  const ratio = e.offsetY / el.clientHeight;
  if (ratio < 0.3) {
    return "before";
  }
  if (ratio > 0.7) {
    return "after";
  }
  return "child";
}

function onDragStart(e: DragEvent, node: DocTreeNode) {
  dragState.fromName = node.name;
  dragState.dragging = true;
  dragState.targetName = "";
  dragState.position = "child";
  dragState.targetTop = false;
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = "move";
    e.dataTransfer.setData("text/plain", node.name);
  }
}

function onDragEnter(e: DragEvent, node: DocTreeNode) {
  if (canDropTarget(node)) {
    dragState.targetName = node.name;
    dragState.position = "child";
    dragState.targetTop = false;
  }
}

function onDragOver(e: DragEvent, node: DocTreeNode) {
  if (!canDropTarget(node)) {
    if (e.dataTransfer) {
      e.dataTransfer.dropEffect = "none";
    }
    if (dragState.targetName === node.name) {
      dragState.targetName = "";
    }
    // 阻止冒泡，避免悬停在无效节点时被容器误判为「拖到顶层」
    e.stopPropagation();
    return;
  }
  e.preventDefault();
  e.stopPropagation();
  if (e.dataTransfer) {
    e.dataTransfer.dropEffect = "move";
  }
  dragState.targetName = node.name;
  dragState.position = zoneFor(e);
}

function onDropOnNode(e: DragEvent, node: DocTreeNode) {
  e.preventDefault();
  e.stopPropagation();
  if (!dragState.dragging || dragState.targetName !== node.name) {
    resetDrag();
    return;
  }
  performMove(dragState.position, node);
}

function onListDragOver(e: DragEvent) {
  if (!dragState.dragging) {
    return;
  }
  e.preventDefault();
  dragState.targetTop = true;
  dragState.targetName = "";
  if (e.dataTransfer) {
    e.dataTransfer.dropEffect = "move";
  }
}

function onListDrop(e: DragEvent) {
  if (!dragState.dragging) {
    return;
  }
  e.preventDefault();
  if (dragState.targetTop) {
    performMove("top", undefined);
  } else {
    resetDrag();
  }
}

function resetDrag() {
  dragState.dragging = false;
  dragState.fromName = "";
  dragState.targetName = "";
  dragState.position = "child";
  dragState.targetTop = false;
}

function onDragEnd() {
  resetDrag();
}

async function performMove(
  position: DropPosition,
  target: DocTreeNode | undefined
) {
  const movedName = dragState.fromName;
  resetDrag();
  if (!movedName) {
    return;
  }

  let parentName: string | null = null;
  let beforeName: string | null = null;
  let afterName: string | null = null;

  if (position === "top") {
    parentName = null;
  } else if (position === "child") {
    if (!target) {
      return;
    }
    parentName = target.name;
  } else {
    if (!target) {
      return;
    }
    parentName = target.parentName || null;
    if (position === "before") {
      beforeName = target.name;
    } else {
      afterName = target.name;
    }
  }

  try {
    await axiosInstance.post(
      `${API_PREFIX}/knowledgebases/${kbName}/docs/${movedName}/move`,
      { parentName, beforeName, afterName }
    );
    const tip =
      position === "top"
        ? "文档已移动到顶层"
        : position === "child"
          ? `文档已移入「${target?.title}」`
          : position === "before"
            ? `文档已移动到「${target?.title}」之前`
            : `文档已移动到「${target?.title}」之后`;
    Toast.success(tip);
    // 展开移动后的父级，便于查看结果
    if (position === "child") {
      if (target) {
        expanded.value.add(target.name);
      }
    } else if (position !== "top" && target?.parentName) {
      toggleExpandAllDir(target.parentName);
    }
    if (selected.value?.name === movedName && doc.value) {
      doc.value.spec.parentName = parentName || undefined;
    }
    await loadTree();
  } catch (err) {
    Toast.error("移动失败，请重试");
  }
}

// 展开 parentName 节点及其到根的所有祖先，确保移动后可见
function toggleExpandAllDir(parentName: string) {
  const expandPath = (nodes: DocTreeNode[], name: string): boolean => {
    for (const node of nodes) {
      if (node.name === name) {
        return true;
      }
      if (expandPath(node.children || [], name)) {
        expanded.value.add(node.name);
        return true;
      }
    }
    return false;
  };
  expandPath(tree.value, parentName);
}

function phaseLabel(phase?: string) {
  if (phase === "published") {
    return "已发布";
  }
  if (phase === "archived") {
    return "已归档";
  }
  return "草稿";
}

// 兼容旧数据：content 若为完整 HTML 文档（历史版本以 HTML 保存），则转回 Markdown
function normalizeContent(content: string): string {
  if (!content) {
    return "";
  }
  const trimmed = content.trimStart();
  // 仅当内容以块级标签开头（真正的 HTML 文档）时才转换，避免误判 Markdown
  if (/^<(p|div|h[1-6]|ul|ol|table|blockquote|pre|section|article)\b/i.test(trimmed)) {
    return htmlToMarkdown(content);
  }
  // 兼容历史误转义：去除 turndown 批量引入的转义字符
  return unescapeTurndown(content);
}

// 去除 turndown 转换时批量引入的转义字符（\*、\|、\` 等），仅在批量出现时处理
function unescapeTurndown(content: string): string {
  const matches = content.match(/\\[*_`|\[\]()#+\-~]/g);
  if (!matches || matches.length < 3) {
    return content;
  }
  return content
    .replace(/\\\*/g, "*")
    .replace(/\\_/g, "_")
    .replace(/\\`/g, "`")
    .replace(/\\\|/g, "|")
    .replace(/\\\[/g, "[")
    .replace(/\\\]/g, "]")
    .replace(/\\\(/g, "(")
    .replace(/\\\)/g, ")")
    .replace(/\\#/g, "#")
    .replace(/\\\+/g, "+")
    .replace(/\\-/g, "-")
    .replace(/\\~/g, "~");
}

function formatDate(time?: string) {
  if (!time) return "-";
  return utils.date.format(time, "YYYY-MM-DD");
}

function onMarkdownUpdate(markdown: string) {
  docForm.markdown = markdown;
  
}

function scrollEditorToTop() {
  markdownEditorRef.value?.scrollToTop();
}

function onSearchInput() {
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    // 搜索时自动展开所有节点以便查看命中结果
    expanded.value.clear();
    const collect = (nodes: DocTreeNode[]) => {
      nodes.forEach((node) => {
        if ((node.children || []).length) {
          expanded.value.add(node.name);
          collect(node.children || []);
        }
      });
    };
    collect(tree.value);
  }, 200);
}

function clearSearch() {
  searchKeyword.value = "";
}

async function loadKb() {
  const { data } = await axiosInstance.get(`${API_PREFIX}/knowledgebases/${kbName}`);
  kb.value = data;
}

async function loadTree() {
  treeLoading.value = true;
  try {
    const { data } = await axiosInstance.get(`${API_PREFIX}/knowledgebases/${kbName}/tree`);
    tree.value = data;
  } finally {
    treeLoading.value = false;
  }
}

async function selectDoc(node: DocTreeNode) {
  selected.value = node;
  docLoading.value = true;
  try {
    const { data } = await axiosInstance.get(
      `${API_PREFIX}/knowledgebases/${kbName}/docs/${node.name}`
    );
    doc.value = data;
    docForm.title = data.spec.title;
    docForm.raw = data.spec.raw || "";
    docForm.markdown = normalizeContent(data.spec.raw || "");
    docForm.slug = data.spec.slug || "";
    docForm.priority = data.spec.priority?.toString() || "";
    docForm.author = data.spec.author || "";
    docForm.cover = data.spec.cover || "";
    docForm.summary = data.spec.summary || "";
    docForm.phase = data.spec.phase || "draft";
    moveParent.value = data.spec.parentName || "";
    tags.value = data.spec.tags || [];
    
    previewMode.value = true;
    // 内容已通过 `:content` prop 绑定并由编辑器内部 watch 注入，
    // 这里不再手动 setContent，避免大文档重复全量渲染
  } finally {
    docLoading.value = false;
  }
}

async function refreshSelected() {
  if (selected.value) {
    await selectDoc(selected.value);
  }
}

async function saveDoc() {
  if (!selected.value) {
    return;
  }
  saving.value = true;
  try {
    // 从 Markdown 编辑器获取最新内容：以原样 Markdown 保存，并用 cherry 渲染出的 HTML 持久化
    const markdown = markdownEditorRef.value?.getContent() || docForm.markdown;
    docForm.markdown = markdown;
    docForm.raw = markdown;
    const payload = {
      spec: {
        ...doc.value?.spec,
        knowledgeBaseName: kbName,
        title: docForm.title,
        raw: markdown,
        content: markdownEditorRef.value?.getHtml() || "",
        slug: docForm.slug.trim() || undefined,
        author: docForm.author.trim() || undefined,
        cover: docForm.cover.trim() || undefined,
        summary: docForm.summary.trim() || undefined,
        phase: docForm.phase,
        tags: tags.value,
        priority: docForm.priority === "" ? undefined : Number(docForm.priority),
      },
    };
    await axiosInstance.put(
      `${API_PREFIX}/knowledgebases/${kbName}/docs/${selected.value.name}`,
      payload
    );
    Toast.success("文档已保存");
    
    // 乐观更新本地 spec，避免保存后 refreshSelected 触发整篇大文档全量重渲染
    if (doc.value) {
      doc.value.spec = payload.spec;
    }
    await loadTree();
  } finally {
    saving.value = false;
  }
}

async function publishDoc() {
  const currentDoc = doc.value;
  const currentName = selected.value;
  if (!currentDoc || !currentName) {
    return;
  }
  // 校验：正文非空但 content（渲染 HTML）为空时，先用 cherry / mdRenderer 渲染补齐再发布，
  // 避免导入或旧数据的文档发布后阅读页正文空白
  const markdown = (
    markdownEditorRef.value?.getContent() ||
    docForm.markdown ||
    currentDoc.spec.raw ||
    ""
  ).trim();
  const emptyContent = !String(currentDoc.spec.content || "").trim();
  if (markdown && emptyContent) {
    await renderAndSaveContent(currentDoc, currentName.name, markdown);
  }
  await axiosInstance.post(
    `${API_PREFIX}/knowledgebases/${kbName}/docs/${currentName.name}/publish`
  );
  Toast.success("文档已发布");
  if (doc.value) {
    doc.value.spec.phase = "published";
  }
  await loadTree();
}

/**
 * 用 cherry（编辑器）或 mdRenderer 兜底把 Markdown 渲染为 HTML 并持久化到 content。
 */
async function renderAndSaveContent(
  currentDoc: KnowledgeBaseDoc,
  docName: string,
  markdown: string
) {
  let html = "";
  try {
    html = (markdownEditorRef.value?.getHtml() || "").trim();
  } catch (e) {
    console.error("获取编辑器 HTML 失败，改用 mdRenderer 渲染", e);
  }
  if (!html) {
    try {
      html = (await renderMarkdownToHtml(markdown)).trim();
    } catch (e) {
      console.error("渲染 Markdown 到 HTML 失败", e);
      return;
    }
  }
  if (!html) {
    return;
  }
  const payload = { spec: { ...currentDoc.spec, raw: markdown, content: html } };
  await axiosInstance.put(
    `${API_PREFIX}/knowledgebases/${kbName}/docs/${docName}`,
    payload
  );
  if (doc.value) {
    doc.value.spec = payload.spec;
  }
}

function openMoveModal() {
  if (!selected.value || !doc.value) {
    return;
  }
  moveParent.value = doc.value.spec.parentName || "";
  moveModalVisible.value = true;
}

async function confirmMove() {
  if (!selected.value) {
    return;
  }
  await axiosInstance.post(
    `${API_PREFIX}/knowledgebases/${kbName}/docs/${selected.value.name}/move`,
    {
      parentName: moveParent.value || null,
    }
  );
  Toast.success("文档已移动");
  moveModalVisible.value = false;
  if (doc.value) {
    doc.value.spec.parentName = moveParent.value || undefined;
  }
  await loadTree();
}

// 复制文档：创建新文档并复制内容
async function duplicateDoc() {
  if (!selected.value || !doc.value) {
    return;
  }
  const payload = {
    metadata: { name: utils.id.uuid() },
    spec: {
      knowledgeBaseName: kbName,
      title: `${doc.value.spec.title} 副本`,
      slug: doc.value.spec.slug ? `${doc.value.spec.slug}-copy` : undefined,
      raw: doc.value.spec.raw,
      content: doc.value.spec.content,
      parentName: doc.value.spec.parentName,
      priority: doc.value.spec.priority,
      tags: doc.value.spec.tags,
    },
  };
  await axiosInstance.post(`${API_PREFIX}/knowledgebases/${kbName}/docs`, payload);
  Toast.success("文档已复制");
  await loadTree();
}

function openCreateDoc() {
  parentTarget.value = selected.value;
  createTitle.value = "";
  createModalVisible.value = true;
}

function createChild(node: DocTreeNode) {
  parentTarget.value = node;
  createTitle.value = "";
  createModalVisible.value = true;
}

async function createDoc() {
  if (!createTitle.value) {
    Toast.warning("请填写文档标题");
    return;
  }
  const parentName = parentTarget.value ? parentTarget.value.name : undefined;
  await axiosInstance.post(`${API_PREFIX}/knowledgebases/${kbName}/docs`, {
    metadata: { name: utils.id.uuid() },
    spec: {
      title: createTitle.value,
      parentName,
    },
  });
  Toast.success("文档已创建");
  createModalVisible.value = false;
  await loadTree();
}

function removeDoc() {
  if (!selected.value) {
    return;
  }
  removeNode(selected.value);
}

// 从目录树中乐观移除节点（含其子树），避免删除后刷新树接口异常时 UI 不更新
function removeNodeFromTree(name: string) {
  const remove = (nodes: DocTreeNode[]): boolean => {
    for (let i = nodes.length - 1; i >= 0; i--) {
      const n = nodes[i];
      if (n.name === name) {
        nodes.splice(i, 1);
        return true;
      }
      if (n.children?.length && remove(n.children)) {
        return true;
      }
    }
    return false;
  };
  remove(tree.value);
}

function removeNode(node: DocTreeNode) {
  Dialog.warning({
    title: "删除文档",
    description: `确定删除「${node.title}」吗？其子文档将一并删除，此操作不可恢复。`,
    confirmText: "删除",
    cancelText: "取消",
    onConfirm: async () => {
      try {
        await axiosInstance.delete(
          `${API_PREFIX}/knowledgebases/${kbName}/docs/${node.name}`
        );
        Toast.success("文档已删除");
        removeNodeFromTree(node.name);
        if (selected.value?.name === node.name) {
          selected.value = null;
          doc.value = null;
        }
        try {
          await loadTree();
        } catch (err) {
          console.error("删除后刷新目录树失败，已使用本地乐观更新结果", err);
        }
      } catch (err) {
        Toast.error(`删除失败：${(err as Error)?.message || err}`);
        console.error(err);
      }
    },
  });
}

// ===== 重命名 =====
function openRename(node: DocTreeNode) {
  renameTarget.value = node;
  renameTitle.value = node.title;
  renameModalVisible.value = true;
}

async function confirmRename() {
  const target = renameTarget.value;
  const title = renameTitle.value.trim();
  if (!target || !title) {
    Toast.warning("请输入文档标题");
    return;
  }
  try {
    const { data } = await axiosInstance.get(
      `${API_PREFIX}/knowledgebases/${kbName}/docs/${target.name}`
    );
    const payload = {
      spec: {
        ...data.spec,
        title,
      },
    };
    await axiosInstance.put(
      `${API_PREFIX}/knowledgebases/${kbName}/docs/${target.name}`,
      payload
    );
    Toast.success("文档已重命名");
    renameModalVisible.value = false;
    if (doc.value) {
      doc.value.spec.title = title;
    }
    docForm.title = title;
    await loadTree();
  } catch (err) {
    Toast.error("重命名失败，请重试");
  }
}

function goBack() {
  router.push({ name: "KnowledgeBases" });
}

// ===== 设置抽屉 =====
function openSettings() {
  if (!doc.value) {
    return;
  }
  settingsVisible.value = true;
}

function closeSettings() {
  settingsVisible.value = false;
}

async function saveSettings() {
  if (!selected.value || !doc.value) {
    return;
  }
  saving.value = true;
  try {
    const markdown = markdownEditorRef.value?.getContent() || docForm.markdown;
    const payload = {
      spec: {
        ...doc.value.spec,
        knowledgeBaseName: kbName,
        title: docForm.title,
        raw: markdown,
        content: markdownEditorRef.value?.getHtml() || "",
        slug: docForm.slug.trim() || undefined,
        author: docForm.author.trim() || undefined,
        cover: docForm.cover.trim() || undefined,
        summary: docForm.summary.trim() || undefined,
        phase: docForm.phase,
        tags: tags.value,
        priority: docForm.priority === "" ? undefined : Number(docForm.priority),
      },
    };
    await axiosInstance.put(
      `${API_PREFIX}/knowledgebases/${kbName}/docs/${selected.value.name}`,
      payload
    );
    Toast.success("文档设置已保存");
    
    settingsVisible.value = false;
    if (doc.value) {
      doc.value.spec = payload.spec;
    }
    await loadTree();
  } finally {
    saving.value = false;
  }
}

// ===== 标签输入 =====
function addTag() {
  const value = tagInput.value.trim();
  if (value && !tags.value.includes(value)) {
    tags.value.push(value);
  }
  tagInput.value = "";
}

function removeTag(tag: string) {
  tags.value = tags.value.filter((t) => t !== tag);
}

function onTagBackspace() {
  if (!tagInput.value && tags.value.length) {
    tags.value.pop();
  }
}

// ===== 封面图片上传（结合 Halo 附件上传） =====
function triggerDocCover() {
  docCoverInput.value?.click();
}

async function onDocCoverChange(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) {
    return;
  }
  await uploadCoverFile(file, (url) => {
    docForm.cover = url;
  });
  input.value = "";
}

async function uploadCoverFile(file: File, onDone: (url: string) => void) {
  try {
    const { data } =
      await consoleApiClient.storage.attachment.uploadAttachmentForConsole({
        file,
      });
    const url = data.status?.permalink;
    if (url) {
      onDone(url);
      Toast.success("封面已上传");
    } else {
      Toast.error("上传成功但未获取到图片地址");
    }
  } catch {
    Toast.error("封面上传失败，请重试");
  }
}

// ===== 批量导入 Markdown =====
function openImportModal() {
  importParent.value = selected.value ? selected.value.name : "";
  importFiles.value = [];
  importModalVisible.value = true;
}

function triggerImportFiles() {
  importFileInput.value?.click();
}

function onImportFilesChange(e: Event) {
  const input = e.target as HTMLInputElement;
  const files = Array.from(input.files || []).filter((f) =>
    f.name.toLowerCase().endsWith(".md")
  );
  importFiles.value = [...importFiles.value, ...files];
  input.value = "";
}

function removeImportFile(index: number) {
  importFiles.value.splice(index, 1);
}

async function confirmImport() {
  if (!importFiles.value.length) {
    Toast.warning("请选择要导入的 Markdown 文件");
    return;
  }
  importing.value = true;
  try {
    const form = new FormData();
    if (importParent.value) {
      form.append("parentName", importParent.value);
    }
    importFiles.value.forEach((file) => form.append("files", file));
    const { data } = await axiosInstance.post(
      `${API_PREFIX}/knowledgebases/${kbName}/docs/import`,
      form
    );
    Toast.success(`已导入 ${data.count} 篇文档`);
    importModalVisible.value = false;
    importFiles.value = [];
    await loadTree();
  } catch {
    Toast.error("导入失败，请重试");
  } finally {
    importing.value = false;
  }
}

onMounted(async () => {
  await Promise.all([loadKb(), loadTree()]);
});
</script>

<template>
  <div class="kb-detail">
    <div class="main-layout">
      <!-- 左侧边栏 -->
      <aside class="sidebar" :style="{ width: sidebarWidth + 'px' }">
        <!-- 顶部白色 header -->
        <div class="sidebar-top">
          <button class="back-btn" @click="goBack">
            <IconArrowLeft class="icon" />
            <span>返回列表</span>
          </button>
          <div class="sidebar-top-title">
            <h1 class="kb-title">{{ kb?.spec.displayName || "知识库详情" }}</h1>
          </div>
        </div>

        <!-- 浅灰内容区 -->
        <div class="sidebar-body">
          <div class="section-header">
            <span class="section-title">文档目录</span>
            <div class="section-actions">
              <button class="icon-btn" title="批量导入 Markdown" @click="openImportModal">
                <IconUpload class="h-4 w-4" />
              </button>
              <button class="icon-btn" title="新建文档" @click="openCreateDoc">
                <IconAddCircle class="h-4 w-4" />
              </button>
              <button
                class="icon-btn"
                :title="allExpanded ? '全部折叠' : '全部展开'"
                @click="toggleExpandAll"
              >
                <IconArrowDown v-if="allExpanded" class="h-4 w-4" />
                <IconArrowRight v-else class="h-4 w-4" />
              </button>
            </div>
          </div>

          <div class="search-box">
            <IconSearch class="search-icon" />
            <input
              v-model="searchKeyword"
              type="text"
              placeholder="搜索文档标题..."
              @input="onSearchInput"
            />
            <button v-if="searchKeyword" class="search-clear" @click="clearSearch">
              <IconClose class="h-3.5 w-3.5" />
            </button>
          </div>

          <div
            class="tree-scroll"
            :class="{
              'drag-active': dragState.dragging,
              'drop-top': dragState.dragging && dragState.targetTop,
            }"
            @dragover="onListDragOver"
            @drop="onListDrop"
          >
            <VLoading v-if="treeLoading" />
            <VEmpty
              v-else-if="!filteredFlattened.length"
              title="暂无文档"
              message="点击「新建文档」创建第一篇文档"
            />
            <ul v-else class="tree-list">
              <li v-for="item in filteredFlattened" :key="item.node.name">
                <div
                  class="tree-node"
                  :class="{
                    selected: selected?.name === item.node.name,
                    'dragging-source':
                      dragState.dragging && dragState.fromName === item.node.name,
                    'drop-target':
                      dragState.dragging &&
                      dragState.targetName === item.node.name &&
                      dragState.position === 'child',
                    'drop-before':
                      dragState.dragging &&
                      dragState.targetName === item.node.name &&
                      dragState.position === 'before',
                    'drop-after':
                      dragState.dragging &&
                      dragState.targetName === item.node.name &&
                      dragState.position === 'after',
                  }"
                  :style="{ paddingLeft: `${8 + item.depth * 16}px` }"
                  draggable="true"
                  @click="selectDoc(item.node)"
                  @dragstart="onDragStart($event, item.node)"
                  @dragend="onDragEnd"
                  @dragenter="onDragEnter($event, item.node)"
                  @dragover="onDragOver($event, item.node)"
                  @drop="onDropOnNode($event, item.node)"
                >
                  <span class="drag-grip" aria-hidden="true"></span>
                  <button
                    v-if="hasChildren(item.node)"
                    class="expand-btn"
                    @click.stop="toggleExpand(item.node)"
                  >
                    <IconArrowDown v-if="isExpanded(item.node)" class="h-3.5 w-3.5" />
                    <IconArrowRight v-else class="h-3.5 w-3.5" />
                  </button>
                  <span v-else class="expand-placeholder"></span>

                  <span class="node-icon" :class="hasChildren(item.node) ? 'folder' : 'doc'">
                    <IconFolder v-if="hasChildren(item.node)" />
                    <IconPages v-else />
                  </span>

                  <span class="node-title">{{ item.node.title }}</span>

                  <span
                    class="node-status-dot"
                    :class="{ published: item.node.phase === 'published' }"
                  ></span>

                  <div class="node-actions" @click.stop>
                    <button
                      class="action-btn"
                      title="新建子文档"
                      @click="createChild(item.node)"
                    >
                      <IconAddCircle class="h-3.5 w-3.5" />
                    </button>
                    <button
                      class="action-btn"
                      title="重命名"
                      @click="openRename(item.node)"
                    >
                      <IconRiPencilFill class="h-3.5 w-3.5" />
                    </button>
                    <button
                      class="action-btn danger"
                      title="删除"
                      @click="removeNode(item.node)"
                    >
                      <IconDeleteBin class="h-3.5 w-3.5" />
                    </button>
                  </div>
                </div>
              </li>
            </ul>
          </div>

          <!-- 底部知识库信息 -->
          <div class="kb-info">
            <div class="kb-info-header">
              <IconBookRead class="h-4 w-4" />
              <span>知识库信息</span>
            </div>
            <div class="kb-info-item">
              <span>文档数量</span>
              <span class="value">{{ kbStats.total }}</span>
            </div>
            <div class="kb-info-item">
              <span>权限状态</span>
              <span class="value">{{
                kb?.spec?.publicVisible ? "公开" : "私有"
              }}</span>
            </div>
            <div class="kb-info-item">
              <span>创建人</span>
              <span class="value">{{ kb?.spec?.creatorName || "-" }}</span>
            </div>
            <div class="kb-info-item">
              <span>创建时间</span>
              <span class="value">{{
                formatDate(
                  kb?.spec?.creationTime || kb?.metadata?.creationTimestamp
                )
              }}</span>
            </div>
          </div>
        </div>
        <!-- 拖拽把手：调整侧边栏宽度 -->
        <div class="resize-handle" @pointerdown="startResize" title="拖动调整宽度"></div>
      </aside>

      <!-- 主内容区 -->
      <main class="editor-area">
        <VLoading v-if="docLoading" />
        <VEmpty
          v-else-if="!doc"
          title="未选择文档"
          message="在左侧文档树中选择一篇文档开始编辑"
        />
        <template v-else>
          <!-- 工具栏行1：视图切换 + 编辑器切换 + 操作按钮 -->
          <div class="doc-toolbar">
            <div class="toolbar-left">
              <!-- 视图切换：预览在前，编辑在后 -->
              <div class="view-toggle">
                <button :class="{ active: previewMode }" @click="previewMode = true">
                  预览
                </button>
                <button :class="{ active: !previewMode }" @click="previewMode = false">
                  编辑
                </button>
              </div>
            </div>

            <div class="toolbar-actions">
              <VButton type="primary" size="sm" :loading="saving" @click="saveDoc">
                <template #icon>
                  <IconSave class="h-4 w-4" />
                </template>
                保存
              </VButton>
              <VButton
                v-if="doc.spec.phase !== 'published'"
                size="sm"
                @click="publishDoc"
              >
                <template #icon>
                  <IconSendPlaneFill class="h-4 w-4" />
                </template>
                发布
              </VButton>
              <VButton size="sm" @click="openMoveModal">
                <template #icon>
                  <IconFolder class="h-4 w-4" />
                </template>
                移动到
              </VButton>
              <VButton size="sm" @click="duplicateDoc">
                <template #icon>
                  <IconClipboardLine class="h-4 w-4" />
                </template>
                复制
              </VButton>
              <VButton size="sm" type="danger" @click="removeDoc">
                <template #icon>
                  <IconDeleteBin class="h-4 w-4" />
                </template>
                删除
              </VButton>
              <VButton size="sm" @click="openSettings">
                <template #icon>
                  <IconSettings class="h-4 w-4" />
                </template>
                设置
              </VButton>
            </div>
          </div>

          <!-- 编辑器：编辑=分屏实时预览，预览=cherry 只读，同一实例切换 -->
          <div class="editor-wrapper">
            <div class="editor-container">
              <MarkdownEditor
                ref="markdownEditorRef"
                :content="docForm.markdown"
                :model="currentEditorModel"
                @update:content="onMarkdownUpdate"
              />
            </div>
          </div>

          <!-- 底部状态栏 -->
          <div class="status-bar">
            <span class="file-path" :title="docPath">
              {{ docPath || "未选择文档" }}
            </span>
            <div class="status-bar-right">
              <span class="stat-item">字数 {{ wordCount }}</span>
              <span class="stat-item">行数 {{ lineCount }}</span>
              <span class="stat-item">作者 {{ docAuthor }}</span>
              <span class="stat-item">创建时间 {{ createdTime }}</span>
              <span class="stat-item">更新时间 {{ updateTime }}</span>
              <button class="status-btn" title="回到顶部" @click="scrollEditorToTop">
                <IconArrowUpLine class="h-3.5 w-3.5" />
                <span>回到顶部</span>
              </button>
            </div>
          </div>
        </template>
      </main>
    </div>

    <!-- 右侧文档设置抽屉 -->
    <transition name="drawer">
      <div v-if="settingsVisible" class="settings-drawer">
        <div class="drawer-mask" @click="closeSettings"></div>
        <div class="drawer-panel">
          <div class="drawer-header">
            <span class="drawer-title">文档设置</span>
            <button class="drawer-close" @click="closeSettings">
              <IconClose class="h-4 w-4" />
            </button>
          </div>
          <div class="drawer-body">
            <div class="form-field">
              <label>文章标题</label>
              <input v-model="docForm.title" type="text" placeholder="输入文章标题" />
              <p class="help-text">修改后保存将同步更新文档名称</p>
            </div>
            <div class="form-field">
              <label>作者（创建人）</label>
              <input v-model="docForm.author" type="text" placeholder="输入作者用户名" />
              <p class="help-text">留空保存时由系统自动填入当前操作人</p>
            </div>
            <div class="form-field">
              <label>封面</label>
              <div class="cover-field">
                <div class="cover-preview">
                  <img v-if="docForm.cover" :src="docForm.cover" alt="文档封面" />
                  <span v-else class="cover-placeholder">无封面</span>
                </div>
                <div class="cover-actions">
                  <VButton size="sm" type="primary" @click="triggerDocCover">
                    <template #icon>
                      <IconUpload class="h-3.5 w-3.5" />
                    </template>
                    上传封面
                  </VButton>
                  <input
                    ref="docCoverInput"
                    type="file"
                    accept="image/*"
                    class="hidden-input"
                    @change="onDocCoverChange"
                  />
                  <button
                    v-if="docForm.cover"
                    class="cover-remove"
                    @click="docForm.cover = ''"
                  >
                    移除
                  </button>
                </div>
              </div>
              <p class="help-text">支持上传本地图片作为封面，或直接填写图片链接</p>
            </div>
            <div class="form-field">
              <label>摘要</label>
              <textarea
                v-model="docForm.summary"
                rows="3"
                placeholder="输入文档摘要"
              ></textarea>
              <p class="help-text">用于列表展示的文档简介</p>
            </div>
            <div class="form-field">
              <label>别名（slug）</label>
              <input v-model="docForm.slug" type="text" placeholder="doc-xxx" />
              <p class="help-text">用于生成文档的唯一链接地址</p>
            </div>
            <div class="form-field">
              <label>排序权重</label>
              <input
                v-model="docForm.priority"
                type="number"
                min="0"
                placeholder="10"
              />
              <p class="help-text">数字越小排序越靠前</p>
            </div>
            <div class="form-field">
              <label>标签</label>
              <div class="tag-input" @click="tagInputRef?.focus()">
                <span v-for="tag in tags" :key="tag" class="tag-chip">
                  {{ tag }}
                  <button class="tag-remove" @click="removeTag(tag)">
                    <IconClose class="h-3 w-3" />
                  </button>
                </span>
                <input
                  ref="tagInputRef"
                  v-model="tagInput"
                  type="text"
                  placeholder="输入后按回车添加"
                  @keydown.enter.prevent="addTag"
                  @keydown.backspace="onTagBackspace"
                />
              </div>
              <p class="help-text">按回车添加新标签</p>
            </div>
            <div class="tip-card">
              <IconInformation class="tip-icon" />
              <p>合理的标签能帮助团队更好地组织和查找文档</p>
            </div>
          </div>
          <div class="drawer-footer">
            <VButton type="secondary" @click="closeSettings">取消</VButton>
            <VButton type="primary" :loading="saving" @click="saveSettings">确定</VButton>
          </div>
        </div>
      </div>
    </transition>

    <!-- 移动文档模态框 -->
    <VModal v-model:visible="moveModalVisible" title="移动文档" :width="480">
      <div class="move-modal-body">
        <p class="move-modal-tip">选择文档移动到的目标位置：</p>
        <label class="move-option" :class="{ selected: moveParent === '' }">
          <input type="radio" v-model="moveParent" value="" />
          <IconFolder class="move-option-icon" />
          <span>顶层</span>
        </label>
        <label
          v-for="item in moveTargets"
          :key="item.node.name"
          class="move-option"
          :class="{ selected: moveParent === item.node.name }"
          :style="{ paddingLeft: `${8 + item.depth * 20}px` }"
        >
          <input type="radio" v-model="moveParent" :value="item.node.name" />
          <IconFolder v-if="hasChildren(item.node)" class="move-option-icon" />
          <IconPages v-else class="move-option-icon" />
          <span>{{ item.node.title }}</span>
        </label>
      </div>
      <template #footer>
        <VSpace>
          <VButton type="secondary" @click="moveModalVisible = false">取消</VButton>
          <VButton type="primary" @click="confirmMove">确定</VButton>
        </VSpace>
      </template>
    </VModal>

    <!-- 新建文档模态框 -->
    <VModal v-model:visible="createModalVisible" title="新建文档" :width="480">
      <FormKit
        v-model="createTitle"
        label="文档标题"
        name="createTitle"
        validation="required"
        placeholder="输入文档标题"
      />
      <p v-if="parentTarget" class="mt-2 text-sm text-gray-500">
        将创建为「{{ parentTarget.title }}」的子文档
      </p>
      <p v-else class="mt-2 text-sm text-gray-500">将创建为顶级文档</p>
      <template #footer>
        <VSpace>
          <VButton type="secondary" @click="createModalVisible = false">取消</VButton>
          <VButton type="primary" @click="createDoc">创建</VButton>
        </VSpace>
      </template>
    </VModal>

    <!-- 重命名文档模态框 -->
    <VModal v-model:visible="renameModalVisible" title="重命名文档" :width="480">
      <FormKit
        v-model="renameTitle"
        label="文档标题"
        name="renameTitle"
        validation="required"
        placeholder="输入新的文档标题"
      />
      <template #footer>
        <VSpace>
          <VButton type="secondary" @click="renameModalVisible = false">取消</VButton>
          <VButton type="primary" @click="confirmRename">确定</VButton>
        </VSpace>
      </template>
    </VModal>

    <!-- 批量导入 Markdown 模态框 -->
    <VModal v-model:visible="importModalVisible" title="批量导入 Markdown" :width="520">
      <div class="import-modal-body">
        <p class="import-modal-tip">选择导入到哪个目录下（设为「顶层」则导入到根目录）：</p>
        <div class="import-parent-list">
          <label class="move-option" :class="{ selected: importParent === '' }">
            <input type="radio" v-model="importParent" value="" />
            <IconFolder class="move-option-icon" />
            <span>顶层（根目录）</span>
          </label>
          <label
            v-for="item in moveTargets"
            :key="item.node.name"
            class="move-option"
            :class="{ selected: importParent === item.node.name }"
            :style="{ paddingLeft: `${8 + item.depth * 20}px` }"
          >
            <input type="radio" v-model="importParent" :value="item.node.name" />
            <IconFolder v-if="hasChildren(item.node)" class="move-option-icon" />
            <IconPages v-else class="move-option-icon" />
            <span>{{ item.node.title }}</span>
          </label>
        </div>

        <div class="import-files-area">
          <button class="import-pick-btn" type="button" @click="triggerImportFiles">
            <IconUpload class="h-4 w-4" />
            选择 .md 文件
          </button>
          <input
            ref="importFileInput"
            type="file"
            accept=".md"
            multiple
            class="hidden-input"
            @change="onImportFilesChange"
          />
          <p class="import-tip">支持多选，每个 Markdown 文件导入为一篇文档（标题取文件名）</p>
          <ul v-if="importFiles.length" class="import-file-list">
            <li v-for="(file, i) in importFiles" :key="i" class="import-file-item">
              <IconPages class="h-4 w-4" />
              <span class="import-file-name">{{ file.name }}</span>
              <button class="import-file-remove" @click="removeImportFile(i)">
                <IconClose class="h-3 w-3" />
              </button>
            </li>
          </ul>
          <p v-else class="import-empty">尚未选择文件</p>
        </div>
      </div>
      <template #footer>
        <VSpace>
          <VButton type="secondary" @click="importModalVisible = false">取消</VButton>
          <VButton type="primary" :loading="importing" @click="confirmImport">
            开始导入
          </VButton>
        </VSpace>
      </template>
    </VModal>
  </div>
</template>

<style scoped>
.kb-detail {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100vh;
  max-height: 100vh;
  overflow: hidden;
  background: #f5f5f5;
}

/* 隐藏 Halo Pro 的页脚，避免产生页面级滚动条（仅当前页面生效） */
:global(.main-content__footer) {
  display: none;
}

/* ========== 主布局 ========== */
.main-layout {
  flex: 1 1 auto;
  display: flex;
  min-height: 0;
}

/* ========== 左侧边栏 ========== */
.sidebar {
  position: relative;
  z-index: 1;
  flex: 0 0 auto;
  width: 280px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: #f5f5f5;
  border-right: 1px solid #e8e8e8;
  /* 折掉任何横向溢出：树内容由 .tree-scroll 内部纵向滚动，边栏自身绝不横向滚动，
     避免拖拽把手/窄宽度下的内容把边栏底部撑出一条横向滚动条 */
  overflow-x: hidden;
}

/* 拖拽把手：贴在侧边栏右缘，拖动改变宽度（右侧主区随 flex 自动跟随） */
.resize-handle {
  position: absolute;
  top: 0;
  bottom: 0;
  right: 0;
  width: 8px;
  cursor: col-resize;
  z-index: 5;
  transition: background 0.15s ease;
}

.resize-handle:hover,
.resize-handle:active {
  background: rgba(76, 141, 255, 0.25);
}

/* 侧边栏顶部白色 header */
.sidebar-top {
  flex: 0 0 auto;
  padding: 0.75rem 1rem 0.875rem;
  background: #ffffff;
  border-bottom: 1px solid #e8e8e8;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.25rem 0.375rem;
  font-size: 0.8125rem;
  color: #595959;
  background: transparent;
  border: none;
  border-radius: 0.375rem;
  cursor: pointer;
  transition: all 0.2s ease;
}

.back-btn:hover {
  color: #262626;
  background: #f5f5f5;
}

.back-btn .icon {
  width: 1rem;
  height: 1rem;
}

.sidebar-top-title {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  margin-top: 0.5rem;
  min-width: 0;
}

.kb-title {
  font-size: 1.0625rem;
  font-weight: 600;
  color: #262626;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 浅灰内容区 */
.sidebar-body {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
}

.section-header {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.75rem 1rem 0.5rem;
}

.section-title {
  font-size: 0.8125rem;
  font-weight: 500;
  color: #595959;
}

.section-actions {
  display: flex;
  align-items: center;
  gap: 0.125rem;
  margin-left: auto;
}

.icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 1.625rem;
  height: 1.625rem;
  color: #8c8c8c;
  background: transparent;
  border: none;
  border-radius: 0.375rem;
  cursor: pointer;
  transition: all 0.2s ease;
}

.icon-btn:hover {
  color: #2563eb;
  background: #eff6ff;
}

/* 搜索框 */
.search-box {
  position: relative;
  flex: 0 0 auto;
  padding: 0 1rem 0.625rem;
}

.search-box input {
  width: 100%;
  height: 2.125rem;
  padding: 0 1.875rem 0 2.125rem;
  font-size: 0.8125rem;
  color: #262626;
  background: #ffffff;
  border: 1px solid #d9d9d9;
  border-radius: 0.375rem;
  outline: none;
  transition: all 0.2s ease;
}

.search-box input::placeholder {
  color: #bfbfbf;
}

.search-box input:focus {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

.search-icon {
  position: absolute;
  left: 1.4375rem;
  top: 0.625rem;
  width: 0.9375rem;
  height: 0.9375rem;
  color: #bfbfbf;
  pointer-events: none;
}

.search-clear {
  position: absolute;
  right: 1.25rem;
  top: 0.3125rem;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 1.375rem;
  height: 1.375rem;
  color: #bfbfbf;
  background: transparent;
  border: none;
  border-radius: 0.25rem;
  cursor: pointer;
}

.search-clear:hover {
  background: #f5f5f5;
  color: #595959;
}

/* 目录树 */
.tree-scroll {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  padding: 0 0.5rem 0.5rem;
}

.tree-scroll::-webkit-scrollbar {
  width: 6px;
}

.tree-scroll::-webkit-scrollbar-thumb {
  background: #d9d9d9;
  border-radius: 3px;
}

.tree-list {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
  list-style: none;
  margin: 0;
  padding: 0;
}

/* 拖拽激活时容器过渡 */
.tree-scroll {
  transition: background 0.15s ease;
}

/* 拖到顶层（空白区域）提示 */
.tree-scroll.drop-top {
  background: #fafff6;
  box-shadow: inset 0 0 0 2px #60a5fa, inset 0 0 0 4px #ffffff;
  border-radius: 0.5rem;
}

.tree-node {
  position: relative;
  display: flex;
  align-items: center;
  gap: 0.375rem;
  height: 2.25rem;
  padding: 0 0.5rem;
  border-radius: 0.5rem;
  cursor: pointer;
  transition: background 0.15s ease, box-shadow 0.15s ease;
}

.tree-node::before {
  content: "";
  position: absolute;
  top: 0.4375rem;
  bottom: 0.4375rem;
  left: 0.125rem;
  width: 0.1875rem;
  border-radius: 9999px;
  background: transparent;
  transition: background 0.15s ease;
}

.tree-node:hover {
  background: #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.05);
}

.tree-node.selected {
  background: #eff6ff;
  box-shadow: inset 0 0 0 1px #bfdbfe;
}

.tree-node.selected::before {
  background: #2563eb;
}

.tree-node.selected .node-title {
  color: #1d4ed8;
  font-weight: 500;
}

/* 拖动手柄：网格小圆点 */
.drag-grip {
  flex-shrink: 0;
  width: 0.625rem;
  height: 1rem;
  opacity: 0;
  cursor: grab;
  background-image: radial-gradient(#c0c4cc 1.1px, transparent 1.2px);
  background-size: 0.3125rem 0.5rem;
  background-position: 0 0.0625rem;
  transition: opacity 0.15s ease;
  border-radius: 0.25rem;
}

.tree-node:hover .drag-grip,
.tree-node.dragging-source .drag-grip {
  opacity: 1;
}

.drag-grip:hover {
  background-color: #eff6ff;
}

/* 被拖拽的源节点 */
.tree-node.dragging-source {
  opacity: 0.45;
  background: #eff6ff;
  cursor: grabbing;
}

/* 有效放置目标（作为其子级） */
.tree-node.drop-target {
  background: #eff6ff;
  box-shadow: inset 0 0 0 1.5px #60a5fa;
}

.tree-node.drop-target::before {
  background: #2563eb;
}

.tree-node.drop-target .node-title {
  color: #1d4ed8;
  font-weight: 500;
}

.tree-node.drop-target .drag-grip {
  opacity: 1;
}

/* 同级排序：在目标上方 / 下方显示插入横线 */
.tree-node.drop-before::after,
.tree-node.drop-after::after {
  content: "";
  position: absolute;
  left: 0.375rem;
  right: 0.375rem;
  height: 0.1875rem;
  border-radius: 9999px;
  background: #2563eb;
  box-shadow: 0 0 0 1px #ffffff;
}

.tree-node.drop-before::after {
  top: 0;
}

.tree-node.drop-after::after {
  bottom: 0;
}

.tree-node.drop-before,
.tree-node.drop-after {
  background: #eff6ff;
}

.expand-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 1.25rem;
  height: 1.25rem;
  flex-shrink: 0;
  color: #8c8c8c;
  background: transparent;
  border: none;
  border-radius: 0.25rem;
  cursor: pointer;
}

.expand-btn:hover {
  color: #262626;
  background: #f5f5f5;
}

.expand-placeholder {
  width: 1.25rem;
  height: 1.25rem;
  flex-shrink: 0;
}

.node-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.375rem;
  height: 1.375rem;
  flex-shrink: 0;
  border-radius: 0.375rem;
}

.node-icon svg {
  width: 0.875rem;
  height: 0.875rem;
}

.node-icon.folder {
  color: #1d4ed8;
  background: #dbeafe;
}

.node-icon.doc {
  color: #409eff;
  background: #ecf5ff;
}

.node-title {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.8125rem;
  color: #595959;
}

.node-status-dot {
  width: 0.375rem;
  height: 0.375rem;
  flex-shrink: 0;
  border-radius: 9999px;
  background: #d9d9d9;
}

.node-status-dot.published {
  background: #2563eb;
}

.node-actions {
  display: none;
  align-items: center;
  gap: 0.125rem;
  flex-shrink: 0;
}

.tree-node:hover .node-actions {
  display: flex;
}

.action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 1.375rem;
  height: 1.375rem;
  color: #8c8c8c;
  background: transparent;
  border: none;
  border-radius: 0.25rem;
  cursor: pointer;
  transition: all 0.15s ease;
}

.action-btn:hover {
  color: #2563eb;
  background: #eff6ff;
}

.action-btn.danger:hover {
  color: #ffffff;
  background: #ff4d4f;
}

/* 知识库信息 */
.kb-info {
  flex: 0 0 auto;
  padding: 0.625rem 1rem 0.75rem;
  border-top: 1px solid #e8e8e8;
  background: #fafafa;
}

.kb-info-header {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  font-size: 0.8125rem;
  font-weight: 600;
  color: #262626;
  margin-bottom: 0.375rem;
}

.kb-info-header :deep(svg) {
  color: #2563eb;
}

.kb-info-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0.1875rem 0;
  font-size: 0.75rem;
  color: #8c8c8c;
}

.kb-info-item .value {
  color: #595959;
  font-weight: 500;
}

/* ========== 主内容区 ========== */
.editor-area {
  position: relative;
  z-index: 0;
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
  background: #ffffff;
}

/* 工具栏行1 */
.doc-toolbar {
  flex: 0 0 auto;
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.625rem;
  padding: 0.625rem 1.25rem;
  background: #ffffff;
  border-bottom: 1px solid #f0f0f0;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  min-width: 0;
}

.toolbar-actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
}

/* 编辑器区域 */
.editor-wrapper {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  padding: 0;
}

.editor-container {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

/* 底部状态栏 */
.status-bar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.5rem 1.25rem;
  background: #ffffff;
  border-top: 1px solid #f0f0f0;
}

.file-path {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.75rem;
  color: #8c8c8c;
}

.status-bar-right {
  display: flex;
  align-items: center;
  gap: 1rem;
  flex-shrink: 0;
}

.stat-item {
  font-size: 0.75rem;
  color: #8c8c8c;
  white-space: nowrap;
}

.status-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.1875rem 0.5rem;
  font-size: 0.75rem;
  color: #8c8c8c;
  background: transparent;
  border: 1px solid transparent;
  border-radius: 0.375rem;
  cursor: pointer;
  transition: all 0.2s ease;
}

.status-btn:hover {
  color: #1d4ed8;
  background: #eff6ff;
  border-color: #bfdbfe;
}

.view-toggle {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.1875rem;
  background: #f5f5f5;
  border: 1px solid #e8e8e8;
  border-radius: 0.5rem;
}

.view-toggle button {
  padding: 0.25rem 0.75rem;
  font-size: 0.75rem;
  font-weight: 500;
  color: #595959;
  background: transparent;
  border: none;
  border-radius: 0.375rem;
  cursor: pointer;
  transition: all 0.2s ease;
}

.view-toggle button:hover {
  color: #262626;
}

.view-toggle button.active {
  color: #1d4ed8;
  background: #ffffff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.06);
}

/* ========== 设置抽屉 ========== */
.settings-drawer {
  position: fixed;
  inset: 0;
  z-index: 1000;
}

.drawer-mask {
  position: absolute;
  inset: 0;
  background: rgba(0, 0, 0, 0.35);
}

.drawer-panel {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: 360px;
  max-width: 90vw;
  display: flex;
  flex-direction: column;
  background: #ffffff;
  box-shadow: -8px 0 24px rgba(0, 0, 0, 0.12);
}

.drawer-header {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem 1.25rem;
  border-bottom: 1px solid #f0f0f0;
}

.drawer-title {
  font-size: 1rem;
  font-weight: 600;
  color: #262626;
}

.drawer-close {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 2rem;
  height: 2rem;
  color: #595959;
  background: transparent;
  border: none;
  border-radius: 0.375rem;
  cursor: pointer;
  transition: all 0.2s ease;
}

.drawer-close:hover {
  color: #262626;
  background: #f5f5f5;
}

.drawer-body {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  padding: 1.25rem;
  display: flex;
  flex-direction: column;
  gap: 1.25rem;
}

.form-field {
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
}

.form-field label {
  font-size: 0.8125rem;
  font-weight: 500;
  color: #595959;
}

.form-field input {
  width: 100%;
  height: 2.25rem;
  padding: 0 0.75rem;
  font-size: 0.8125rem;
  color: #262626;
  background: #ffffff;
  border: 1px solid #d9d9d9;
  border-radius: 0.5rem;
  outline: none;
  transition: all 0.2s ease;
}

.form-field input:focus {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

.help-text {
  font-size: 0.75rem;
  color: #bfbfbf;
}

/* 标签输入 */
.tag-input {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.375rem;
  min-height: 2.25rem;
  padding: 0.3125rem 0.5rem;
  background: #ffffff;
  border: 1px solid #d9d9d9;
  border-radius: 0.5rem;
  cursor: text;
  transition: all 0.2s ease;
}

.tag-input:focus-within {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

.tag-input input {
  flex: 1 1 auto;
  min-width: 6rem;
  height: 1.5rem;
  padding: 0;
  font-size: 0.8125rem;
  border: none;
  box-shadow: none;
}

.tag-input input:focus {
  border: none;
  box-shadow: none;
}

.tag-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.125rem 0.375rem 0.125rem 0.5rem;
  font-size: 0.75rem;
  color: #595959;
  background: #f5f5f5;
  border: 1px solid #e8e8e8;
  border-radius: 0.375rem;
}

.tag-remove {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 1rem;
  height: 1rem;
  color: #8c8c8c;
  background: transparent;
  border: none;
  border-radius: 0.25rem;
  cursor: pointer;
}

.tag-remove:hover {
  color: #ff4d4f;
  background: #fff1f0;
}

/* 提示卡片 */
.tip-card {
  display: flex;
  align-items: flex-start;
  gap: 0.5rem;
  padding: 0.75rem;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  border-radius: 0.5rem;
}

.tip-card .tip-icon {
  flex-shrink: 0;
  width: 1rem;
  height: 1rem;
  color: #2563eb;
  margin-top: 0.125rem;
}

.tip-card p {
  font-size: 0.75rem;
  line-height: 1.5;
  color: #1d4ed8;
  margin: 0;
}

/* 移动文档弹窗 */
.move-modal-body {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  max-height: 60vh;
  overflow-y: auto;
  padding: 0.25rem 0;
}

.move-modal-tip {
  font-size: 0.8125rem;
  color: #8c8c8c;
  margin: 0 0 0.5rem;
}

.move-option {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.625rem;
  border-radius: 0.5rem;
  cursor: pointer;
  transition: background 0.15s ease;
}

.move-option:hover {
  background: #f5f5f5;
}

.move-option.selected {
  background: #eff6ff;
}

.move-option input[type="radio"] {
  accent-color: #2563eb;
  margin: 0;
  flex-shrink: 0;
}

.move-option-icon {
  width: 1rem;
  height: 1rem;
  flex-shrink: 0;
  color: #8c8c8c;
}

.move-option.selected .move-option-icon {
  color: #2563eb;
}

.move-option span {
  font-size: 0.8125rem;
  color: #595959;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.move-option.selected span {
  color: #1d4ed8;
  font-weight: 500;
}

.drawer-footer {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 0.5rem;
  padding: 1rem 1.25rem;
  border-top: 1px solid #f0f0f0;
  background: #fafafa;
}

/* 抽屉动画 */
.drawer-enter-active,
.drawer-leave-active {
  transition: opacity 0.25s ease;
}

.drawer-enter-active .drawer-panel,
.drawer-leave-active .drawer-panel {
  transition: transform 0.25s ease;
}

.drawer-enter-from,
.drawer-leave-to {
  opacity: 0;
}

.drawer-enter-from .drawer-panel,
.drawer-leave-to .drawer-panel {
  transform: translateX(100%);
}

/* ========== 按钮美化（绿色主题） ========== */
:deep(.btn) {
  border-radius: 0.5rem;
  font-weight: 500;
  transition: all 0.2s ease;
}

:deep(.btn-default) {
  background: #ffffff;
  border: 1px solid #e0e0e0;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

:deep(.btn-default:hover) {
  background-color: #f4f4f5;
  border-color: #18181b;
  color: #18181b;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.06);
}

:deep(.btn-default .btn-icon) {
  color: #8c8c8c;
  transition: color 0.2s ease;
}

:deep(.btn-default:hover .btn-icon) {
  color: #18181b;
}

:deep(.btn-danger) {
  color: #ffffff;
  border: 1px solid #ff4d4f;
  background: #ff4d4f;
}

:deep(.btn-danger:hover) {
  background: #f5222d;
  border-color: #f5222d;
  color: #ffffff;
  box-shadow: 0 2px 10px rgba(255, 77, 79, 0.32);
}

:deep(.btn-danger .btn-icon) {
  color: #ffffff;
}

:deep(.btn-secondary) {
  background: #595959;
  border-color: #595959;
}

:deep(.btn-secondary:hover) {
  background: #434343;
  border-color: #434343;
}

/* 隐藏的原生文件输入 */
.hidden-input {
  display: none;
}

/* 设置抽屉：封面 */
.cover-field {
  display: flex;
  gap: 0.75rem;
  align-items: flex-start;
}

.cover-preview {
  width: 7rem;
  height: 4rem;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 0.5rem;
  border: 1px solid #e8e8e8;
  background: #fafafa;
  overflow: hidden;
}

.cover-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.cover-placeholder {
  font-size: 0.75rem;
  color: #bfbfbf;
}

.cover-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.375rem;
}

.cover-remove {
  padding: 0;
  font-size: 0.75rem;
  color: #ff4d4f;
  background: transparent;
  border: none;
  cursor: pointer;
}

.cover-remove:hover {
  text-decoration: underline;
}

.form-field textarea {
  width: 100%;
  padding: 0.5rem 0.75rem;
  font-size: 0.8125rem;
  line-height: 1.5;
  color: #262626;
  background: #ffffff;
  border: 1px solid #d9d9d9;
  border-radius: 0.5rem;
  outline: none;
  resize: vertical;
  transition: all 0.2s ease;
}

.form-field textarea:focus {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.12);
}

/* 批量导入 */
.import-modal-body {
  display: flex;
  flex-direction: column;
  gap: 1rem;
}

.import-modal-tip {
  font-size: 0.8125rem;
  color: #8c8c8c;
  margin: 0;
}

.import-parent-list {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
  max-height: 12rem;
  overflow-y: auto;
  padding: 0.25rem 0;
  border: 1px solid #f0f0f0;
  border-radius: 0.5rem;
}

.import-files-area {
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
}

.import-pick-btn {
  display: inline-flex;
  align-items: center;
  gap: 0.375rem;
  padding: 0.5rem 0.875rem;
  font-size: 0.8125rem;
  font-weight: 500;
  color: #1d4ed8;
  background: #eff6ff;
  border: 1px solid #bfdbfe;
  border-radius: 0.5rem;
  cursor: pointer;
  transition: all 0.2s ease;
}

.import-pick-btn:hover {
  background: #dbeafe;
}

.import-tip {
  font-size: 0.75rem;
  color: #bfbfbf;
  margin: 0;
}

.import-file-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 0.375rem;
  max-height: 10rem;
  overflow-y: auto;
}

.import-file-item {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.375rem 0.5rem;
  background: #fafafa;
  border: 1px solid #f0f0f0;
  border-radius: 0.5rem;
}

.import-file-item svg {
  flex-shrink: 0;
  color: #409eff;
}

.import-file-name {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.8125rem;
  color: #595959;
}

.import-file-remove {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 1.25rem;
  height: 1.25rem;
  flex-shrink: 0;
  color: #8c8c8c;
  background: transparent;
  border: none;
  border-radius: 0.25rem;
  cursor: pointer;
}

.import-file-remove:hover {
  color: #ff4d4f;
  background: #fff1f0;
}

.import-empty {
  font-size: 0.8125rem;
  color: #bfbfbf;
  margin: 0;
  padding: 0.375rem 0;
}
</style>
