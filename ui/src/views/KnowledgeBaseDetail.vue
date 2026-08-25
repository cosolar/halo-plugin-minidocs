<script setup lang="ts">
import { computed, defineAsyncComponent, nextTick, onMounted, reactive, ref } from "vue";
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
  IconEye,
  IconClipboardLine,
  IconSettings,
  IconSearch,
  IconClose,
  IconBookRead,
  IconInformation,
} from "@halo-dev/components";
import { axiosInstance, consoleApiClient } from "@halo-dev/api-client";
import { utils } from "@halo-dev/ui-shared";
import DocPreview from "../components/DocPreview.vue";
// bytemd 体积较大，按需懒加载
const MarkdownEditor = defineAsyncComponent(
  () => import("../components/MarkdownEditor.vue")
);
import { htmlToMarkdown, markdownToHtml } from "../utils/markdown";

interface KnowledgeBase {
  metadata: { name: string };
  spec: {
    displayName: string;
    description?: string;
    publicVisible?: boolean;
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
  metadata: { name: string };
  spec: {
    knowledgeBaseName: string;
    title: string;
    slug?: string;
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
  content: "",
  markdown: "",
  slug: "",
  priority: "",
});
const moveParent = ref("");
const moveModalVisible = ref(false);
const createModalVisible = ref(false);
const createTitle = ref("");
const parentTarget = ref<DocTreeNode | null>(null);
const wordCount = ref(0);

// Markdown 编辑器引用（懒加载组件）
const markdownEditorRef = ref<{
  getContent: () => string;
  setContent: (c: string) => void;
  scrollToTop: () => void;
} | null>(null);

const expanded = ref<Set<string>>(new Set());
const allExpanded = ref(false);

// 搜索
const searchKeyword = ref("");
let searchTimer: ReturnType<typeof setTimeout> | null = null;

// 编辑 / 预览（默认预览模式）
const previewMode = ref(true);

// 预览阅读宽度（来自插件设置，默认 960px）
const previewWidth = ref(960);

// 保存状态
const saveStatus = ref<"saved" | "unsaved" | "saving">("saved");

// 设置抽屉
const settingsVisible = ref(false);
const tags = ref<string[]>([]);
const tagInput = ref("");
const tagInputRef = ref<HTMLInputElement | null>(null);

// 重命名
const renameModalVisible = ref(false);
const renameTitle = ref("");
const renameTarget = ref<DocTreeNode | null>(null);

const saveStatusLabel = computed(() => {
  if (saveStatus.value === "saved") return "已保存";
  if (saveStatus.value === "saving") return "保存中";
  return "未保存";
});

// 预览内容：Markdown 实时转换
const previewHtml = computed(() => markdownToHtml(docForm.markdown));

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

// 当前文档更新时间
const updateTime = computed(() => {
  const time = doc.value?.spec.publishTime;
  return time ? utils.date.format(time, "YYYY-MM-DD HH:mm") : "-";
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
  wordCount.value = markdown.replace(/\s/g, "").length;
  saveStatus.value = "unsaved";
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

// 读取插件设置（预览阅读宽度等）
async function loadPluginConfig() {
  try {
    const { data } = await consoleApiClient.plugin.plugin.fetchPluginJsonConfig({
      name: "halo-plugin-minidocs",
    });
    const basic = (data as Record<string, Record<string, unknown>>)?.basic || {};
    const width = Number(basic.previewWidth);
    if (width && width > 0) {
      previewWidth.value = width;
    }
  } catch (err) {
    // 读取失败时使用默认宽度
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
    docForm.content = data.spec.content || "";
    docForm.markdown = normalizeContent(data.spec.content || "");
    docForm.slug = data.spec.slug || "";
    docForm.priority = data.spec.priority?.toString() || "";
    moveParent.value = data.spec.parentName || "";
    tags.value = data.spec.tags || [];
    wordCount.value = docForm.markdown.replace(/\s/g, "").length;
    saveStatus.value = "saved";
    previewMode.value = true;
    // 同步到 Markdown 编辑器
    await nextTick();
    markdownEditorRef.value?.setContent(docForm.markdown);
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
    // 从 Markdown 编辑器获取最新内容，直接以 Markdown 原始文本保存
    const markdown = markdownEditorRef.value?.getContent() || docForm.markdown;
    docForm.markdown = markdown;
    docForm.content = markdown;
    const payload = {
      spec: {
        ...doc.value?.spec,
        title: docForm.title,
        content: markdown,
        slug: docForm.slug.trim() || undefined,
        tags: tags.value,
        priority: docForm.priority === "" ? undefined : Number(docForm.priority),
      },
    };
    await axiosInstance.put(
      `${API_PREFIX}/knowledgebases/${kbName}/docs/${selected.value.name}`,
      payload
    );
    Toast.success("文档已保存");
    saveStatus.value = "saved";
    wordCount.value = markdown.replace(/\s/g, "").length;
    await loadTree();
    await refreshSelected();
  } finally {
    saving.value = false;
  }
}

async function publishDoc() {
  if (!selected.value) {
    return;
  }
  await axiosInstance.post(
    `${API_PREFIX}/knowledgebases/${kbName}/docs/${selected.value.name}/publish`
  );
  Toast.success("文档已发布");
  await loadTree();
  await refreshSelected();
}

// 归档 / 取消归档（直接更新 phase）
async function setPhase(phase: string, tip: string) {
  if (!selected.value || !doc.value) {
    return;
  }
  const payload = {
    spec: {
      ...doc.value.spec,
      phase,
    },
  };
  await axiosInstance.put(
    `${API_PREFIX}/knowledgebases/${kbName}/docs/${selected.value.name}`,
    payload
  );
  Toast.success(tip);
  await loadTree();
  await refreshSelected();
}

function archiveDoc() {
  setPhase("archived", "文档已归档");
}

function unarchiveDoc() {
  setPhase("draft", "文档已取消归档");
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
  await loadTree();
  await refreshSelected();
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

function removeNode(node: DocTreeNode) {
  Dialog.warning({
    title: "删除文档",
    description: `确定删除「${node.title}」吗？其子文档将一并删除，此操作不可恢复。`,
    confirmText: "删除",
    cancelText: "取消",
    onConfirm: async () => {
      await axiosInstance.delete(
        `${API_PREFIX}/knowledgebases/${kbName}/docs/${node.name}`
      );
      Toast.success("文档已删除");
      if (selected.value?.name === node.name) {
        selected.value = null;
        doc.value = null;
      }
      await loadTree();
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
    await loadTree();
    if (selected.value?.name === target.name) {
      await refreshSelected();
    }
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
        title: docForm.title,
        content: markdown,
        slug: docForm.slug.trim() || undefined,
        tags: tags.value,
        priority: docForm.priority === "" ? undefined : Number(docForm.priority),
      },
    };
    await axiosInstance.put(
      `${API_PREFIX}/knowledgebases/${kbName}/docs/${selected.value.name}`,
      payload
    );
    Toast.success("文档设置已保存");
    saveStatus.value = "saved";
    settingsVisible.value = false;
    await loadTree();
    await refreshSelected();
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

onMounted(async () => {
  await Promise.all([loadKb(), loadTree(), loadPluginConfig()]);
});
</script>

<template>
  <div class="kb-detail">
    <div class="main-layout">
      <!-- 左侧边栏 -->
      <aside class="sidebar">
        <!-- 顶部白色 header -->
        <div class="sidebar-top">
          <button class="back-btn" @click="goBack">
            <IconArrowLeft class="icon" />
            <span>返回列表</span>
          </button>
          <div class="sidebar-top-title">
            <h1 class="kb-title">{{ kb?.spec.displayName || "知识库详情" }}</h1>
            <span class="save-status" :class="saveStatus">
              <span class="status-dot"></span>
              {{ saveStatusLabel }}
            </span>
          </div>
        </div>

        <!-- 浅灰内容区 -->
        <div class="sidebar-body">
          <div class="section-header">
            <span class="section-title">文档目录</span>
            <span class="section-count">{{ kbStats.total }} 篇</span>
            <div class="section-actions">
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

          <div class="tree-scroll">
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
                  :class="{ selected: selected?.name === item.node.name }"
                  :style="{ paddingLeft: `${8 + item.depth * 16}px` }"
                  @click="selectDoc(item.node)"
                >
                  <button
                    v-if="hasChildren(item.node)"
                    class="expand-btn"
                    @click.stop="toggleExpand(item.node)"
                  >
                    <IconArrowDown v-if="isExpanded(item.node)" class="h-3.5 w-3.5" />
                    <IconArrowRight v-else class="h-3.5 w-3.5" />
                  </button>
                  <span v-else class="expand-placeholder"></span>

                  <IconFolder v-if="hasChildren(item.node)" class="node-icon folder" />
                  <IconPages v-else class="node-icon" />

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
              <span>文档总数</span>
              <span class="value">{{ kbStats.total }}</span>
            </div>
            <div class="kb-info-item">
              <span>公开文档</span>
              <span class="value">{{ kbStats.published }}</span>
            </div>
            <div class="kb-info-item">
              <span>最后更新</span>
              <span class="value">{{ formatDate(kbStats.lastUpdate) }}</span>
            </div>
          </div>
        </div>
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
              <VButton
                v-if="doc.spec.phase === 'archived'"
                size="sm"
                @click="unarchiveDoc"
              >
                <template #icon>
                  <IconEye class="h-4 w-4" />
                </template>
                取消归档
              </VButton>
              <VButton v-else size="sm" @click="archiveDoc">
                <template #icon>
                  <IconFolder class="h-4 w-4" />
                </template>
                归档
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

          <!-- 编辑器 / 预览 -->
          <div class="editor-wrapper">
            <div v-show="!previewMode" class="editor-container">
              <MarkdownEditor
                ref="markdownEditorRef"
                :content="docForm.markdown"
                placeholder="使用 Markdown 编写文档内容..."
                @update:content="onMarkdownUpdate"
              />
            </div>
            <div v-show="previewMode" class="preview-container">
              <DocPreview :html="previewHtml" :width="previewWidth" :visible="previewMode" />
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
  flex: 0 0 280px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  background: #f5f5f5;
  border-right: 1px solid #e8e8e8;
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

.save-status {
  display: inline-flex;
  align-items: center;
  gap: 0.3125rem;
  font-size: 0.6875rem;
  color: #8c8c8c;
  white-space: nowrap;
}

.save-status .status-dot {
  width: 0.4375rem;
  height: 0.4375rem;
  border-radius: 9999px;
  background: #52c41a;
}

.save-status.unsaved .status-dot {
  background: #faad14;
}

.save-status.unsaved {
  color: #d48806;
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

.section-count {
  font-size: 0.75rem;
  color: #8c8c8c;
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
  color: #52c41a;
  background: #f0fdf4;
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
  border-color: #52c41a;
  box-shadow: 0 0 0 3px rgba(82, 196, 26, 0.12);
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

.tree-node {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  height: 2.25rem;
  padding: 0 0.5rem;
  border-radius: 0.375rem;
  cursor: pointer;
  transition: background 0.15s ease;
}

.tree-node:hover {
  background: #ffffff;
}

.tree-node.selected {
  background: #f6ffed;
}

.tree-node.selected .node-title {
  color: #389e0d;
  font-weight: 500;
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
  width: 1rem;
  height: 1rem;
  flex-shrink: 0;
  color: #8c8c8c;
}

.node-icon.folder {
  color: #52c41a;
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
  background: #52c41a;
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
  color: #52c41a;
  background: #f6ffed;
}

.action-btn.danger:hover {
  color: #ff4d4f;
  background: #fff1f0;
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
  color: #52c41a;
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
  padding: 1.25rem;
}

.editor-container {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

/* 预览 */
.preview-container {
  flex: 1 1 auto;
  display: flex;
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
  color: #389e0d;
  background: #f6ffed;
  border-color: #d9f7be;
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
  color: #389e0d;
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
  border-color: #52c41a;
  box-shadow: 0 0 0 3px rgba(82, 196, 26, 0.12);
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
  border-color: #52c41a;
  box-shadow: 0 0 0 3px rgba(82, 196, 26, 0.12);
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
  background: #f6ffed;
  border: 1px solid #d9f7be;
  border-radius: 0.5rem;
}

.tip-card .tip-icon {
  flex-shrink: 0;
  width: 1rem;
  height: 1rem;
  color: #52c41a;
  margin-top: 0.125rem;
}

.tip-card p {
  font-size: 0.75rem;
  line-height: 1.5;
  color: #389e0d;
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
  background: #f6ffed;
}

.move-option input[type="radio"] {
  accent-color: #52c41a;
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
  color: #52c41a;
}

.move-option span {
  font-size: 0.8125rem;
  color: #595959;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.move-option.selected span {
  color: #389e0d;
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
  color: #ff4d4f;
  border: 1px solid #ffccc7;
  background: #ffffff;
}

:deep(.btn-danger:hover) {
  background: #fff1f0;
  border-color: #ffa39e;
  color: #f5222d;
  box-shadow: 0 2px 8px rgba(255, 77, 79, 0.18);
}

:deep(.btn-danger .btn-icon) {
  color: #ff4d4f;
}

:deep(.btn-secondary) {
  background: #595959;
  border-color: #595959;
}

:deep(.btn-secondary:hover) {
  background: #434343;
  border-color: #434343;
}
</style>
