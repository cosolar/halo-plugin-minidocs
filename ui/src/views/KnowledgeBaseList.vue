<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import {
  Dialog,
  Toast,
  VButton,
  VCard,
  VDropdown,
  VDropdownItem,
  VEmpty,
  VLoading,
  VModal,
  VPageHeader,
  VSpace,
  VSwitch,
  VTag,
  IconRiPencilFill,
  IconDeleteBin,
  IconAddCircle,
  IconPages,
  IconSearch,
  IconGrid,
  IconList,
  IconEye,
  IconLockPasswordLine,
  IconClose,
  IconUpload,
} from "@halo-dev/components";
import { axiosInstance, consoleApiClient } from "@halo-dev/api-client";
import { utils } from "@halo-dev/ui-shared";
import UserSelect from "../components/UserSelect.vue";
import TagInput from "../components/TagInput.vue";
import PaginationBar from "../components/PaginationBar.vue";
import { renderMarkdownToHtml } from "../utils/mdRenderer";

interface Stats {
  total: number;
  publicCount: number;
  privateCount: number;
  shareCount: number;
  docCount: number;
  kbGrowth: number;
  docGrowth: number;
  publicRatio: string;
}

interface KnowledgeBase {
  metadata: {
    name: string;
    creationTimestamp?: string;
    [key: string]: unknown;
  };
  spec: {
    displayName: string;
    slug?: string;
    description?: string;
    cover?: string;
    logo?: string;
    tags?: string[];
    publicVisible?: boolean;
    members?: string[];
    priority?: number;
    creationTime?: string;
    updateTime?: string;
    shareEnabled?: boolean;
    shareToken?: string;
    sharePassword?: string;
    shareExpiresAt?: string;
    accessCount?: number;
    likeCount?: number;
  };
  status?: {
    docCount?: number;
    lastPublishTime?: string;
    kbGrowth?: number;
    docGrowth?: number;
  };
}

const API_PREFIX = "/apis/console.api.minidocs.halo.run/v1alpha1";

const router = useRouter();

const kbs = ref<KnowledgeBase[]>([]);
const loading = ref(false);
const total = ref(0);
const page = ref(1);
const size = ref(10);
const keyword = ref("");
const publicVisible = ref<boolean | undefined>(undefined);
const viewMode = ref<"grid" | "list">("grid");
const isMobile = ref(window.matchMedia("(max-width: 767px)").matches);
function onResize() {
  isMobile.value = window.matchMedia("(max-width: 767px)").matches;
}
const sortBy = ref<"updateTime" | "createTime" | "name" | "docCount" | "priority">("updateTime");
const stats = ref<Stats | null>(null);
const statsLoading = ref(false);
// 统计栏显示/隐藏：localStorage 记忆用户选择；statsGlobalEnabled 为插件设置里的总开关
const statsVisible = ref(localStorage.getItem("minidocs_stats_visible") !== "0");
const statsGlobalEnabled = ref(true);
function toggleStatsVisible() {
  statsVisible.value = !statsVisible.value;
  localStorage.setItem("minidocs_stats_visible", statsVisible.value ? "1" : "0");
}
async function loadStatsSettings() {
  try {
    const { data } = await axiosInstance.get(`${API_PREFIX}/knowledgebases/settings`);
    statsGlobalEnabled.value = data?.showStats !== false;
  } catch {
    // 读取失败时保持默认显示
  }
}

const modalVisible = ref(false);
const saving = ref(false);
const editing = ref<KnowledgeBase | null>(null);
const form = reactive({
  name: "",
  displayName: "",
  slug: "",
  description: "",
  cover: "",
  logo: "",
  priority: 10,
  tags: [] as string[],
  members: [] as string[],
  publicVisible: false,
});

// 知识库封面上传
const kbCoverInput = ref<HTMLInputElement | null>(null);
function triggerKbCover() {
  kbCoverInput.value?.click();
}
async function onKbCoverChange(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) {
    return;
  }
  try {
    const { data } =
      await consoleApiClient.storage.attachment.uploadAttachmentForConsole({
        file,
      });
    const url = data.status?.permalink;
    if (url) {
      form.cover = url;
      Toast.success("封面已上传");
    } else {
      Toast.error("上传成功但未获取到图片地址");
    }
  } catch {
    Toast.error("封面上传失败，请重试");
  } finally {
    input.value = "";
  }
}

// 知识库导出
const exporting = ref(false);
async function exportSelected() {
  if (!selectedNames.value.length) {
    Toast.warning("请先勾选要导出的知识库");
    return;
  }
  await doExport(selectedNames.value);
}
async function exportOne(kb: KnowledgeBase) {
  await doExport([kb.metadata.name], kb.spec.slug || "");
}
async function doExport(names: string[], filename = "") {
  exporting.value = true;
  try {
    const response = await axiosInstance.post(
      `${API_PREFIX}/knowledgebases/export`,
      { names },
      { responseType: "blob" }
    );
    const blob = response.data as Blob;
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    // 优先用别名（slug）命名，别名为空时回退到时间戳命名
    a.download = filename
      ? `${filename}.zip`
      : `MiniDocs-${exportTimestamp()}.zip`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    Toast.success(`已导出 ${names.length} 个知识库`);
  } catch {
    Toast.error("导出失败，请重试");
  } finally {
    exporting.value = false;
  }
}
function exportTimestamp() {
  const d = new Date();
  const p = (n: number, len = 2) => String(n).padStart(len, "0");
  return `${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}${p(
    d.getHours()
  )}${p(d.getMinutes())}${p(d.getSeconds())}${p(d.getMilliseconds(), 3)}`;
}

// 一键发布：用前端 cherry 渲染每篇文档的 raw 生成 HTML 保存，并把未发布的文档转为已发布
const publishing = ref(false);
async function publishSelected() {
  if (!selectedNames.value.length) {
    Toast.warning("请先勾选要发布的知识库");
    return;
  }
  const names = [...selectedNames.value];
  const confirmed = await new Promise<boolean>((resolve) => {
    Dialog.info({
      title: "一键发布",
      description:
        `将渲染并发布所选 ${names.length} 个知识库下的所有文档：` +
        "缺少正文的文档会用编辑器渲染补齐 HTML，未发布的文档将转为已发布。确定继续吗？",
      confirmText: "发布",
      cancelText: "取消",
      onConfirm: () => resolve(true),
      onCancel: () => resolve(false),
    });
  });
  if (!confirmed) {
    return;
  }
  publishing.value = true;
  let done = 0;
  let total = 0;
  try {
    for (const kbName of names) {
      const { data } = await axiosInstance.get(
        `${API_PREFIX}/knowledgebases/${kbName}/docs`,
        { params: { page: 1, size: 1000 } }
      );
      const docs: Array<{ metadata: any; spec: any }> = data.items || [];
      total += docs.length;
      for (const doc of docs) {
        const spec = doc.spec || {};
        const hadContent = !!(spec.content && String(spec.content).trim());
        const needPublish = spec.phase !== "published";
        // 已发布且有正文的文档无需处理，减少不必要的重渲染
        if (!needPublish && hadContent) {
          continue;
        }
        let content = spec.content || "";
        if (!hadContent) {
          try {
            content = await renderMarkdownToHtml(spec.raw || "");
          } catch (e) {
            console.error(`渲染文档 ${doc.metadata?.name} 失败`, e);
            content = "";
          }
        }
        const payload = { spec: { ...spec, content, phase: "published" } };
        await axiosInstance.put(
          `${API_PREFIX}/knowledgebases/${kbName}/docs/${doc.metadata.name}`,
          payload
        );
        done++;
      }
    }
  } finally {
    publishing.value = false;
  }
  clearSelection();
  await load();
  await loadStats();
  if (total === 0) {
    Toast.success("所选知识库下没有需要发布的文档");
  } else {
    Toast.success(`发布完成：已处理 ${done}/${total} 篇文档`);
  }
}

// 知识库导入（zip）
interface ImportPreviewItem {
  displayName: string;
  docCount: number;
  exists: boolean;
}
interface ImportResultItem {
  displayName: string;
  imported: boolean;
  message: string;
}
const kbImportInput = ref<HTMLInputElement | null>(null);
const importModalVisible = ref(false);
const importing = ref(false);
const importFile = ref<File | null>(null);
const importPreviewItems = ref<ImportPreviewItem[]>([]);
const importWarnings = ref<ImportResultItem[]>([]);
const importOverwrite = ref(true);
let importResultTimer: ReturnType<typeof setTimeout> | null = null;

function showImportResult(items: ImportResultItem[]) {
  importWarnings.value = items;
  if (importResultTimer) {
    clearTimeout(importResultTimer);
  }
  if (!items.length) {
    return;
  }
  importResultTimer = setTimeout(() => {
    importWarnings.value = [];
    importResultTimer = null;
  }, 5000);
}

function openImportModal() {
  kbImportInput.value?.click();
}
async function onImportFileChange(e: Event) {
  const input = e.target as HTMLInputElement;
  const file = input.files?.[0];
  input.value = "";
  if (!file) {
    return;
  }
  if (!/\.zip$/i.test(file.name)) {
    Toast.warning("请选择 zip 文件");
    return;
  }
  importFile.value = file;
  importWarnings.value = [];
  importing.value = true;
  try {
    const formData = new FormData();
    formData.append("file", file);
    const { data } = await axiosInstance.post(
      `${API_PREFIX}/knowledgebases/import/preview`,
      formData
    );
    importPreviewItems.value = data as ImportPreviewItem[];
    if (!importPreviewItems.value.length) {
      Toast.error("zip 文件中未解析到知识库");
      return;
    }
    importModalVisible.value = true;
  } catch {
    Toast.error("解析 zip 文件失败");
  } finally {
    importing.value = false;
  }
}
async function confirmImport() {
  if (!importFile.value) {
    return;
  }
  importing.value = true;
  try {
    const formData = new FormData();
    formData.append("file", importFile.value);
    formData.append("strategy", importOverwrite.value ? "overwrite" : "skip");
    const { data } = await axiosInstance.post(
      `${API_PREFIX}/knowledgebases/import`,
      formData
    );
    showImportResult(data as ImportResultItem[]);
    importModalVisible.value = false;
    Toast.success("导入完成");
    clearSelection();
    await load();
    await loadStats();
  } catch {
    Toast.error("导入失败，请重试");
  } finally {
    importing.value = false;
  }
}
function closeImportModal() {
  if (importing.value) {
    return;
  }
  importModalVisible.value = false;
  importFile.value = null;
  importPreviewItems.value = [];
}

// 批量选择
const selectedNames = ref<string[]>([]);
const cancelToken = ref(false);

const filteredCount = computed(() => {
  if (publicVisible.value === undefined) return total.value;
  return stats.value
    ? publicVisible.value
      ? stats.value.publicCount
      : stats.value.privateCount
    : 0;
});

function getStatusTheme(kb: KnowledgeBase) {
  if (kb.spec.members?.length) {
    return { bg: "bg-blue-50", text: "text-blue-600" };
  }
  if (kb.spec.publicVisible) {
    return { bg: "bg-green-50", text: "text-green-600" };
  }
  return { bg: "bg-purple-50", text: "text-purple-600" };
}

// 卡片封面右上角状态徽标：团队 > 公开 > 私有
function getStatusBadge(kb: KnowledgeBase): { text: string; cls: string } {
  if (kb.spec.members?.length) {
    return { text: "团队", cls: "kb-badge-team" };
  }
  if (kb.spec.publicVisible) {
    return { text: "公开", cls: "kb-badge-public" };
  }
  return { text: "私有", cls: "kb-badge-private" };
}

// 卡片上最多展示 3 个标签，避免撑破卡片高度
function visibleTags(kb: KnowledgeBase) {
  return (kb.spec.tags || []).filter(Boolean).slice(0, 3);
}

// 预览前台阅读页：优先链接别名，回退 metadata.name（后端两者均可解析）
function previewKb(kb: KnowledgeBase) {
  const key = kb.spec.slug || kb.metadata.name;
  window.open(
    `${window.location.origin}/docs/view/${encodeURIComponent(key)}`,
    "_blank",
    "noopener"
  );
}

function formatTime(time?: string, fmt = "YYYY-MM-DD HH:mm") {
  if (!time) return "-";
  return utils.date.format(time, fmt);
}

// 相对时间，超过 30 天显示具体日期
function formatRelativeTime(time?: string) {
  if (!time) return "-";
  const timestamp = new Date(time).getTime();
  if (Number.isNaN(timestamp)) return "-";
  const diff = Date.now() - timestamp;
  const min = 60 * 1000;
  const hour = 60 * min;
  const day = 24 * hour;
  const month = 30 * day;
  if (diff < min) return "刚刚";
  if (diff < hour) return `${Math.floor(diff / min)} 分钟前`;
  if (diff < day) return `${Math.floor(diff / hour)} 小时前`;
  if (diff < month) return `${Math.floor(diff / day)} 天前`;
  return formatTime(time);
}

function isSelected(name: string) {
  return selectedNames.value.includes(name);
}

function toggleSelect(kb: KnowledgeBase) {
  const name = kb.metadata.name;
  const idx = selectedNames.value.indexOf(name);
  if (idx > -1) {
    selectedNames.value.splice(idx, 1);
  } else {
    selectedNames.value.push(name);
  }
}

function clearSelection() {
  selectedNames.value = [];
}

async function loadStats() {
  statsLoading.value = true;
  try {
    const { data } = await axiosInstance.get(`${API_PREFIX}/knowledgebases/stats`);
    stats.value = data;
  } finally {
    statsLoading.value = false;
  }
}

async function load() {
  loading.value = true;
  try {
    const { data } = await axiosInstance.get(`${API_PREFIX}/knowledgebases`, {
      params: {
        page: page.value,
        size: size.value,
        sortBy: sortBy.value,
        keyword: keyword.value || undefined,
        publicVisible:
          publicVisible.value === undefined ? undefined : publicVisible.value,
      },
    });
    kbs.value = data.items;
    total.value = data.total;
  } finally {
    loading.value = false;
  }
}

// 防抖实时搜索
let searchTimer: ReturnType<typeof setTimeout> | null = null;
function onKeywordInput() {
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(() => {
    page.value = 1;
    load();
  }, 350);
}

function search() {
  page.value = 1;
  load();
}

function clearKeyword() {
  keyword.value = "";
  page.value = 1;
  load();
}

function filterVisibility(value?: boolean) {
  publicVisible.value = value;
  page.value = 1;
  load();
}

function onPageChange(newPage: number) {
  page.value = newPage;
  load();
}

function onSizeChange(newSize: number) {
  size.value = newSize;
  page.value = 1;
  load();
}

function goDetail(name: string) {
  router.push({ name: "KnowledgeBaseDetail", params: { name } });
}

function openCreate() {
  editing.value = null;
  form.name = utils.id.uuid();
  form.displayName = "";
  form.slug = "";
  form.description = "";
  form.cover = "";
  form.logo = "";
  form.priority = 10;
  form.tags = [];
  form.members = [];
  form.publicVisible = false;
  modalVisible.value = true;
}

function openEdit(kb: KnowledgeBase) {
  editing.value = kb;
  form.name = kb.metadata.name;
  form.displayName = kb.spec.displayName;
  form.slug = kb.spec.slug || "";
  form.description = kb.spec.description || "";
  form.cover = kb.spec.cover || "";
  form.logo = kb.spec.logo || "";
  form.priority = kb.spec.priority ?? 10;
  form.tags = kb.spec.tags || [];
  form.members = kb.spec.members || [];
  form.publicVisible = !!kb.spec.publicVisible;
  modalVisible.value = true;
}

async function save() {
  if (!form.displayName) {
    Toast.warning("请填写知识库名称");
    return;
  }
  saving.value = true;
  try {
    const spec = {
      displayName: form.displayName,
      slug: form.slug || undefined,
      description: form.description || undefined,
      cover: form.cover || undefined,
      logo: form.logo || undefined,
      priority: form.priority == null ? undefined : form.priority,
      tags: form.tags.filter(Boolean),
      members: form.publicVisible ? [] : form.members,
      publicVisible: form.publicVisible,
    };
    if (editing.value) {
      await axiosInstance.put(`${API_PREFIX}/knowledgebases/${form.name}`, {
        metadata: { name: form.name },
        spec,
      });
      Toast.success("知识库已更新");
    } else {
      await axiosInstance.post(`${API_PREFIX}/knowledgebases`, {
        metadata: { name: form.name },
        spec,
      });
      Toast.success("知识库已创建");
    }
    modalVisible.value = false;
    await load();
    await loadStats();
  } finally {
    saving.value = false;
  }
}

// ============ 外链分享 ============
const shareModalVisible = ref(false);
const shareSaving = ref(false);
const shareKb = ref<KnowledgeBase | null>(null);
const shareForm = reactive({
  enabled: false,
  password: "",
  token: "",
  period: 0,
  url: "",
});

function generateShareToken() {
  const chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
  let s = "";
  for (let i = 0; i < 12; i++) {
    s += chars[Math.floor(Math.random() * chars.length)];
  }
  return s;
}

function isKbShared(kb: KnowledgeBase) {
  return !!((kb.spec as Record<string, unknown>)?.shareEnabled);
}

function buildShareUrl(token: string) {
  return token ? `${window.location.origin}/docs/share/${token}` : "";
}

function openShare(kb: KnowledgeBase) {
  shareKb.value = kb;
  const spec = (kb.spec || {}) as Record<string, unknown>;
  shareForm.enabled = !!spec.shareEnabled;
  shareForm.token = (spec.shareToken as string) || "";
  shareForm.password = (spec.sharePassword as string) || "";
  let period = 0;
  if (spec.shareExpiresAt) {
    const remainDays = Math.ceil(
      (new Date(spec.shareExpiresAt as string).getTime() - Date.now()) / 86400000
    );
    if (remainDays > 0) {
      period = remainDays <= 7 ? 7 : remainDays <= 30 ? 30 : 90;
    }
  }
  shareForm.period = period;
  shareForm.url = buildShareUrl(shareForm.token);
  shareModalVisible.value = true;
}

async function saveShare() {
  const kb = shareKb.value;
  if (!kb) return;
  if (shareForm.enabled && !shareForm.token) {
    shareForm.token = generateShareToken();
  }
  shareSaving.value = true;
  try {
    const baseSpec = (kb.spec || {}) as Record<string, unknown>;
    const expiresAt = shareForm.enabled && shareForm.period > 0
      ? new Date(Date.now() + shareForm.period * 86400000).toISOString()
      : undefined;
    const spec = {
      ...baseSpec,
      shareEnabled: shareForm.enabled,
      shareToken: shareForm.token || undefined,
      sharePassword: shareForm.enabled && shareForm.password
        ? shareForm.password : undefined,
      shareExpiresAt: expiresAt,
    };
    const resp = await axiosInstance.put(
      `${API_PREFIX}/knowledgebases/${kb.metadata.name}`,
      { metadata: { name: kb.metadata.name }, spec }
    );
    const saved = (resp.data as KnowledgeBase | undefined)?.spec as
      | Record<string, unknown>
      | undefined;
    shareForm.enabled = !!(saved?.shareEnabled as boolean);
    shareForm.token = (saved?.shareToken as string) || shareForm.token;
    shareForm.url = buildShareUrl(shareForm.token);
    // 保存后不关闭弹窗，方便立即复制外链
    Toast.success(shareForm.enabled ? "已保存，请复制外链" : "已关闭外链分享");
    // 刷新列表，同步卡片上的“已分享”状态标识
    await load();
  } finally {
    shareSaving.value = false;
  }
}

async function copyShareLink() {
  const url = shareForm.url;
  if (!url) {
    Toast.warning("请先开启分享并保存，再复制链接");
    return;
  }
  try {
    await navigator.clipboard.writeText(url);
    Toast.success("链接已复制");
  } catch {
    const ta = document.createElement("textarea");
    ta.value = url;
    document.body.appendChild(ta);
    ta.select();
    try {
      document.execCommand("copy");
      Toast.success("链接已复制");
    } catch {
      Toast.error("复制失败，请手动复制");
    }
    document.body.removeChild(ta);
  }
}

function remove(kb: KnowledgeBase) {
  Dialog.warning({
    title: "删除知识库",
    description: `确定删除「${kb.spec.displayName}」吗？其下所有文档将一并删除，此操作不可恢复。`,
    confirmText: "删除",
    cancelText: "取消",
    onConfirm: async () => {
      await axiosInstance.delete(`${API_PREFIX}/knowledgebases/${kb.metadata.name}`);
      Toast.success("知识库已删除");
      selectedNames.value = selectedNames.value.filter(
        (n) => n !== kb.metadata.name
      );
      await load();
      await loadStats();
    },
  });
}

// 批量删除
function batchRemove() {
  if (!selectedNames.value.length) return;
  const count = selectedNames.value.length;
  Dialog.warning({
    title: "批量删除知识库",
    description: `确定删除选中的 ${count} 个知识库吗？其下所有文档将一并删除，此操作不可恢复。`,
    confirmText: "删除",
    cancelText: "取消",
    onConfirm: async () => {
      for (const name of [...selectedNames.value]) {
        try {
          await axiosInstance.delete(`${API_PREFIX}/knowledgebases/${name}`);
        } catch (e) {
          console.error(`删除知识库 ${name} 失败`, e);
        }
      }
      Toast.success(`已删除 ${count} 个知识库`);
      clearSelection();
      await load();
      await loadStats();
    },
  });
}

onMounted(() => {
  loadStatsSettings();
  loadStats();
  load();
  window.addEventListener("resize", onResize);
});

onBeforeUnmount(() => {
  window.removeEventListener("resize", onResize);
});
</script>

<template>
  <div class="knowledge-base-list">
    <!-- 顶部标题区 -->
    <VPageHeader title="知识库">
      <template #description>
        集中管理与组织团队的知识内容
      </template>
      <template #actions>
        <VButton type="secondary" @click="openImportModal">
          <template #icon>
            <IconUpload class="h-4 w-4" />
          </template>
          导入
        </VButton>
        <input
          ref="kbImportInput"
          type="file"
          accept=".zip,application/zip"
          class="hidden-file-input"
          @change="onImportFileChange"
        />
        <VButton type="primary" @click="openCreate">
          <template #icon>
            <IconAddCircle class="h-4 w-4" />
          </template>
          新建
        </VButton>
      </template>
    </VPageHeader>

    <!-- 页面主体：统计面板 + 内容面板 -->
    <div class="knowledge-base-body">
      <!-- 统计区面板 -->
      <div v-if="statsGlobalEnabled && statsVisible" class="stats-panel">
        <div class="stats-panel-header">
          <span class="stats-panel-title">数据概览</span>
          <button
            class="stats-toggle-btn"
            title="隐藏统计栏"
            aria-label="隐藏统计栏"
            @click="toggleStatsVisible"
          >
            <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
          </button>
        </div>
        <div v-if="stats" class="stats-grid">
          <VCard :body-class="['!p-0']" class="stat-card">
            <div class="stat-card-inner">
              <div class="stat-icon stat-icon-blue">
                <svg
                  class="h-6 w-6"
                  viewBox="0 0 1024 1024"
                  fill="currentColor"
                  aria-hidden="true"
                >
                  <path d="M136.533333 170.666667a102.4 102.4 0 0 1 102.4-102.4h68.266667a238.7968 238.7968 0 0 1 204.8 115.780266A238.7968 238.7968 0 0 1 716.8 68.266667h68.266667a102.4 102.4 0 0 1 102.4 102.4v102.4a102.4 102.4 0 0 1 102.4 102.4v409.6a170.666667 170.666667 0 0 1-170.666667 170.666666H204.8a170.666667 170.666667 0 0 1-170.666667-170.666666V375.466667a102.4 102.4 0 0 1 102.4-102.4V170.666667z m0 170.666666a34.133333 34.133333 0 0 0-34.133333 34.133334v409.6a102.4 102.4 0 0 0 102.4 102.4h614.4a102.4 102.4 0 0 0 102.4-102.4V375.466667a34.133333 34.133333 0 0 0-34.133333-34.133334v409.6a102.4 102.4 0 0 1-102.4 102.4H238.933333a102.4 102.4 0 0 1-102.4-102.4V341.333333z m170.666667-204.8H238.933333a34.133333 34.133333 0 0 0-34.133333 34.133334v580.266666a34.133333 34.133333 0 0 0 34.133333 34.133334h238.933334V307.2a170.666667 170.666667 0 0 0-170.666667-170.666667z m477.866667 648.533334a34.133333 34.133333 0 0 0 34.133333-34.133334V170.666667a34.133333 34.133333 0 0 0-34.133333-34.133334h-68.266667a170.666667 170.666667 0 0 0-170.666667 170.666667v477.866667h238.933334z"></path>
                </svg>
              </div>
              <div class="stat-content">
                <div class="stat-main">
                  <span class="stat-label">知识库总数</span>
                  <span class="stat-value">{{ stats.total }}</span>
                </div>
                <span
                  v-if="stats.kbGrowth"
                  class="stat-trend"
                  :class="stats.kbGrowth >= 0 ? 'trend-up' : 'trend-down'"
                >
                  {{ stats.kbGrowth >= 0 ? '↑' : '↓' }} {{ Math.abs(stats.kbGrowth) }} 较上月
                </span>
                <span v-else class="stat-trend trend-flat">— 较上月</span>
              </div>
            </div>
          </VCard>
          <VCard :body-class="['!p-0']" class="stat-card">
            <div class="stat-card-inner">
              <div class="stat-icon stat-icon-teal">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"/><line x1="2" y1="12" x2="22" y2="12"/><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z"/></svg>
              </div>
              <div class="stat-content">
                <div class="stat-main">
                  <span class="stat-label">公开知识库</span>
                  <span class="stat-value">{{ stats.publicCount }}</span>
                </div>
                <span class="stat-trend"> 占比 {{ stats.publicRatio || '0%' }} </span>
              </div>
            </div>
          </VCard>
          <VCard :body-class="['!p-0']" class="stat-card">
            <div class="stat-card-inner">
              <div class="stat-icon stat-icon-orange">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><rect x="3" y="11" width="18" height="11" rx="2" ry="2"/><path d="M7 11V7a5 5 0 0 1 10 0v4"/></svg>
              </div>
              <div class="stat-content">
                <div class="stat-main">
                  <span class="stat-label">私有知识库</span>
                  <span class="stat-value">{{ stats.privateCount }}</span>
                </div>
                <span
                  class="stat-trend"
                  :class="stats.privateCount ? 'trend-private' : 'trend-flat'"
                >
                  仅成员可见
                </span>
              </div>
            </div>
          </VCard>
          <VCard :body-class="['!p-0']" class="stat-card">
            <div class="stat-card-inner">
              <div class="stat-icon stat-icon-purple">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
              </div>
              <div class="stat-content">
                <div class="stat-main">
                  <span class="stat-label">已分享</span>
                  <span class="stat-value">{{ stats.shareCount }}</span>
                </div>
                <span class="stat-trend">
                  外链可访问
                </span>
              </div>
            </div>
          </VCard>
          <VCard :body-class="['!p-0']" class="stat-card">
            <div class="stat-card-inner">
              <div class="stat-icon stat-icon-green">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>
              </div>
              <div class="stat-content">
                <div class="stat-main">
                  <span class="stat-label">文档总数</span>
                  <span class="stat-value">{{ stats.docCount }}</span>
                </div>
                <span
                  v-if="stats.docGrowth"
                  class="stat-trend"
                  :class="stats.docGrowth >= 0 ? 'trend-up' : 'trend-down'"
                >
                  {{ stats.docGrowth >= 0 ? '↑' : '↓' }} {{ Math.abs(stats.docGrowth) }} 较上月
                </span>
                <span v-else class="stat-trend trend-flat">— 较上月</span>
              </div>
            </div>
          </VCard>
        </div>
      </div>

      <!-- 统计栏隐藏时的恢复条（总开关关闭时不显示） -->
      <div v-else-if="statsGlobalEnabled" class="stats-hidden-bar">
        <button class="stats-toggle-btn stats-toggle-restore" @click="toggleStatsVisible">
          <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M1 12s4-8 11-8 11 8 11 8-4 8-11 8-11-8-11-8z"/><circle cx="12" cy="12" r="3"/></svg>
          <span>显示统计栏</span>
        </button>
      </div>

      <!-- 内容面板：批量操作栏 + 工具栏 + 可滚动列表 + 分页 -->
      <div class="kb-content-panel">

        <!-- 批量操作栏 -->
        <div v-if="selectedNames.length" class="batch-bar">
          <span class="batch-bar-text">
            <IconUpload class="h-4 w-4" />
            已选择 {{ selectedNames.length }} 个知识库
          </span>
          <VSpace>
            <VButton size="sm" type="secondary" @click="clearSelection">取消选择</VButton>
            <VButton size="sm" type="primary" :loading="publishing" @click="publishSelected">
              <template #icon>
                <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"/></svg>
              </template>
              一键发布
            </VButton>
            <VButton size="sm" type="secondary" :loading="exporting" @click="exportSelected">
              <template #icon>
                <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v10"/><polyline points="7 10 12 15 17 10"/><path d="M4 19h16"/></svg>
              </template>
              导出所选
            </VButton>
            <VButton size="sm" type="danger" @click="batchRemove">
              <template #icon>
                <IconDeleteBin class="h-3.5 w-3.5" />
              </template>
              批量删除
            </VButton>
          </VSpace>
        </div>

        <!-- 搜索与筛选工具栏 -->
        <div class="toolbar">
          <div class="toolbar-left">
            <div class="filter-tabs">
              <button
                :class="{ active: publicVisible === undefined }"
                @click="filterVisibility(undefined)"
              >
                <span>全部</span>
                <span class="tab-count">{{ stats?.total || 0 }}</span>
              </button>
              <button
                :class="{ active: publicVisible === true }"
                @click="filterVisibility(true)"
              >
                <span>公开</span>
                <span class="tab-count">{{ stats?.publicCount || 0 }}</span>
              </button>
              <button
                :class="{ active: publicVisible === false }"
                @click="filterVisibility(false)"
              >
                <span>私有</span>
                <span class="tab-count">{{ stats?.privateCount || 0 }}</span>
              </button>
            </div>
            <div class="search-box">
              <IconSearch class="search-icon" />
              <input
                v-model="keyword"
                type="text"
                class="search-input"
                placeholder="搜索知识库名称或描述..."
                @input="onKeywordInput"
                @keyup.enter="search"
              />
              <button v-if="keyword" class="search-clear" @click="clearKeyword">
                <IconClose class="h-3.5 w-3.5" />
              </button>
            </div>
          </div>
          <div class="toolbar-right">
            <div class="sort-box">
              <svg
                class="sort-icon"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                stroke-width="1.9"
                stroke-linecap="round"
                stroke-linejoin="round"
                aria-hidden="true"
              >
                <path d="M4 6h16" />
                <path d="M7 12h10" />
                <path d="M10 18h4" />
              </svg>
              <select v-model="sortBy" class="sort-select" @change="load">
                <option value="updateTime">最近更新</option>
                <option value="createTime">最近创建</option>
                <option value="name">名称</option>
                <option value="docCount">文档数</option>
                <option value="priority">优先级</option>
              </select>
            </div>
            <div class="view-toggle">
              <button
                :class="{ active: viewMode === 'grid' }"
                @click="viewMode = 'grid'"
              >
                <IconGrid class="h-4 w-4" />
              </button>
              <button
                :class="{ active: viewMode === 'list' }"
                @click="viewMode = 'list'"
              >
                <IconList class="h-4 w-4" />
              </button>
            </div>
          </div>
        </div>

    <!-- 可滚动内容区 -->
    <div class="kb-scroll-area">
      <div v-if="loading" class="kb-state">
        <VLoading />
      </div>

      <div v-else-if="!kbs.length" class="kb-state">
        <VEmpty
          :title="keyword ? '没有搜索结果' : '暂无知识库'"
          :message="
            keyword
              ? `未找到与「${keyword}」匹配的知识库，试试其他关键词`
              : '点击右上角「新建知识库」开始创建你的第一个知识库'
          "
        >
          <template #actions>
            <VSpace v-if="keyword">
              <VButton type="secondary" @click="clearKeyword">清除搜索</VButton>
            </VSpace>
            <VButton v-else type="primary" @click="openCreate">
              <template #icon>
                <IconAddCircle class="h-4 w-4" />
              </template>
              新建知识库
            </VButton>
          </template>
        </VEmpty>
      </div>

      <!-- 网格视图（移动端强制列表模式，不渲染网格） -->
    <div v-else-if="viewMode === 'grid' && !isMobile" class="kb-grid">
      <article
        v-for="kb in kbs"
        :key="kb.metadata.name"
        class="kb-card"
        :class="{ 'is-selected': isSelected(kb.metadata.name) }"
        @click="toggleSelect(kb)"
      >
        <!-- 封面 + 状态徽标 -->
        <div class="kb-card-cover">
          <img
            v-if="kb.spec.cover"
            class="kb-cover-img"
            :src="kb.spec.cover"
            alt=""
            loading="lazy"
          />
          <div v-else class="kb-cover-fallback"></div>

          <button
            type="button"
            class="kb-card-check"
            :class="{ 'is-on': isSelected(kb.metadata.name) }"
            title="选择"
            aria-label="选择"
            @click.stop.prevent="toggleSelect(kb)"
          >
            <svg
              class="kb-check-mark"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="3"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <polyline points="20 6 9 17 4 12"></polyline>
            </svg>
          </button>

          <span class="kb-status-badge" :class="getStatusBadge(kb).cls">
            <svg
              v-if="kb.spec.members?.length"
              class="kb-badge-icon"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2.2"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
              <circle cx="9" cy="7" r="4" />
              <path d="M23 21v-2a4 4 0 0 0-3-3.87" />
              <path d="M16 3.13a4 4 0 0 1 0 7.75" />
            </svg>
            <IconEye v-else-if="kb.spec.publicVisible" class="kb-badge-icon" />
            <IconLockPasswordLine v-else class="kb-badge-icon" />
            {{ getStatusBadge(kb).text }}
          </span>
        </div>

        <!-- logo + 标题 -->
        <div class="kb-card-head">
          <div class="kb-logo">
            <img v-if="kb.spec.logo" :src="kb.spec.logo" alt="" />
            <svg
              v-else
              class="kb-logo-fallback"
              viewBox="0 0 1024 1024"
              fill="currentColor"
              aria-hidden="true"
            >
              <path d="M136.533333 170.666667a102.4 102.4 0 0 1 102.4-102.4h68.266667a238.7968 238.7968 0 0 1 204.8 115.780266A238.7968 238.7968 0 0 1 716.8 68.266667h68.266667a102.4 102.4 0 0 1 102.4 102.4v102.4a102.4 102.4 0 0 1 102.4 102.4v409.6a170.666667 170.666667 0 0 1-170.666667 170.666666H204.8a170.666667 170.666667 0 0 1-170.666667-170.666666V375.466667a102.4 102.4 0 0 1 102.4-102.4V170.666667z m0 170.666666a34.133333 34.133333 0 0 0-34.133333 34.133334v409.6a102.4 102.4 0 0 0 102.4 102.4h614.4a102.4 102.4 0 0 0 102.4-102.4V375.466667a34.133333 34.133333 0 0 0-34.133333-34.133334v409.6a102.4 102.4 0 0 1-102.4 102.4H238.933333a102.4 102.4 0 0 1-102.4-102.4V341.333333z m170.666667-204.8H238.933333a34.133333 34.133333 0 0 0-34.133333 34.133334v580.266666a34.133333 34.133333 0 0 0 34.133333 34.133334h238.933334V307.2a170.666667 170.666667 0 0 0-170.666667-170.666667z m477.866667 648.533334a34.133333 34.133333 0 0 0 34.133333-34.133334V170.666667a34.133333 34.133333 0 0 0-34.133333-34.133334h-68.266667a170.666667 170.666667 0 0 0-170.666667 170.666667v477.866667h238.933334z"></path>
            </svg>
          </div>
          <h3 class="kb-card-title" :title="kb.spec.displayName">
            {{ kb.spec.displayName }}
          </h3>
        </div>

        <!-- 标签 -->
        <div v-if="visibleTags(kb).length" class="kb-card-tags">
          <span
            v-for="tag in visibleTags(kb)"
            :key="tag"
            class="kb-tag"
            :title="tag"
          >
            {{ tag }}
          </span>
        </div>

        <!-- 描述 -->
        <p class="kb-card-desc" :title="kb.spec.description || '暂无描述'">
          {{ kb.spec.description || "暂无描述" }}
        </p>

        <!-- 文档数 / 更新时间 -->
        <div class="kb-card-meta">
          <span class="kb-meta-item" title="文档数">
            <IconPages class="kb-meta-icon" />
            {{ kb.status?.docCount ?? 0 }} 篇文档
          </span>
          <span
            class="kb-meta-item"
            :title="
              kb.spec?.updateTime
                ? '更新于 ' + formatTime(kb.spec.updateTime)
                : '创建于 ' + formatTime(kb.metadata.creationTimestamp)
            "
          >
            更新于
            {{ formatRelativeTime(kb.spec?.updateTime || kb.metadata.creationTimestamp) }}
          </span>
        </div>

        <!-- 操作：进入 / 预览 / 编辑 / 更多 -->
        <div class="kb-card-footer">
          <button class="kb-enter-btn" @click.stop="goDetail(kb.metadata.name)">
            进入
            <svg
              class="kb-enter-chevron"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              stroke-width="2.6"
              stroke-linecap="round"
              stroke-linejoin="round"
            >
              <polyline points="9 18 15 12 9 6" />
            </svg>
          </button>
          <div class="kb-card-actions">
            <button
              class="kb-icon-btn kb-icon-preview"
              title="预览前台页面"
              aria-label="预览"
              @click.stop="previewKb(kb)"
            >
              <IconEye class="kb-icon" />
            </button>
            <button
              class="kb-icon-btn kb-icon-edit"
              title="编辑"
              aria-label="编辑"
              @click.stop="openEdit(kb)"
            >
              <IconRiPencilFill class="kb-icon" />
            </button>
            <VDropdown>
              <button
                class="kb-icon-btn kb-icon-more"
                title="更多操作"
                aria-label="更多操作"
                @click.stop
              >
                <svg class="kb-icon" viewBox="0 0 24 24" fill="currentColor">
                  <circle cx="12" cy="5" r="1.7" />
                  <circle cx="12" cy="12" r="1.7" />
                  <circle cx="12" cy="19" r="1.7" />
                </svg>
                <span v-if="isKbShared(kb)" class="kb-shared-dot"></span>
              </button>
              <template #popper>
                <VDropdownItem @click="openShare(kb)">
                  <template #prefix-icon>
                    <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="18" cy="5" r="3" /><circle cx="6" cy="12" r="3" /><circle cx="18" cy="19" r="3" /><line x1="8.59" y1="13.51" x2="15.42" y2="17.49" /><line x1="15.41" y1="6.51" x2="8.59" y2="10.49" /></svg>
                  </template>
                  {{ isKbShared(kb) ? "管理分享" : "分享" }}
                </VDropdownItem>
                <VDropdownItem @click="exportOne(kb)">
                  <template #prefix-icon>
                    <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v10" /><polyline points="7 10 12 15 17 10" /><path d="M4 19h16" /></svg>
                  </template>
                  导出
                </VDropdownItem>
                <VDropdownItem type="danger" @click="remove(kb)">
                  <template #prefix-icon>
                    <IconDeleteBin class="h-3.5 w-3.5" />
                  </template>
                  删除
                </VDropdownItem>
              </template>
            </VDropdown>
          </div>
        </div>
      </article>
    </div>

    <!-- 列表视图 -->
    <div v-else class="kb-list">
      <div
        v-for="kb in kbs"
        :key="kb.metadata.name"
        class="kb-list-row"
        @click="goDetail(kb.metadata.name)"
      >
        <input
          type="checkbox"
          class="kb-checkbox"
          :checked="isSelected(kb.metadata.name)"
          @click.stop
          @change="toggleSelect(kb)"
        />
        <svg
          class="h-4 w-4 flex-shrink-0"
          :class="getStatusTheme(kb).text"
          viewBox="0 0 1024 1024"
          fill="currentColor"
          aria-hidden="true"
        >
          <path d="M136.533333 170.666667a102.4 102.4 0 0 1 102.4-102.4h68.266667a238.7968 238.7968 0 0 1 204.8 115.780266A238.7968 238.7968 0 0 1 716.8 68.266667h68.266667a102.4 102.4 0 0 1 102.4 102.4v102.4a102.4 102.4 0 0 1 102.4 102.4v409.6a170.666667 170.666667 0 0 1-170.666667 170.666666H204.8a170.666667 170.666667 0 0 1-170.666667-170.666666V375.466667a102.4 102.4 0 0 1 102.4-102.4V170.666667z m0 170.666666a34.133333 34.133333 0 0 0-34.133333 34.133334v409.6a102.4 102.4 0 0 0 102.4 102.4h614.4a102.4 102.4 0 0 0 102.4-102.4V375.466667a34.133333 34.133333 0 0 0-34.133333-34.133334v409.6a102.4 102.4 0 0 1-102.4 102.4H238.933333a102.4 102.4 0 0 1-102.4-102.4V341.333333z m170.666667-204.8H238.933333a34.133333 34.133333 0 0 0-34.133333 34.133334v580.266666a34.133333 34.133333 0 0 0 34.133333 34.133334h238.933334V307.2a170.666667 170.666667 0 0 0-170.666667-170.666667z m477.866667 648.533334a34.133333 34.133333 0 0 0 34.133333-34.133334V170.666667a34.133333 34.133333 0 0 0-34.133333-34.133334h-68.266667a170.666667 170.666667 0 0 0-170.666667 170.666667v477.866667h238.933334z"></path>
        </svg>
        <div class="kb-list-info">
          <div class="kb-list-title-row">
            <h3 class="kb-card-title" :title="kb.spec.displayName">{{ kb.spec.displayName }}</h3>
            <VTag v-if="kb.spec.members?.length" size="sm" class="kb-status-inline kb-status-team">团队</VTag>
            <VTag v-else-if="kb.spec.publicVisible" size="sm" class="kb-status-inline kb-status-public">公开</VTag>
            <VTag v-else size="sm" class="kb-status-inline kb-status-private">私有</VTag>
          </div>
          <p class="kb-list-desc">
            {{ kb.spec.description || '暂无描述' }}
          </p>
        </div>
        <div class="kb-list-side">
          <div class="kb-list-meta">
            <span class="kb-meta-item">
              <IconPages class="h-3.5 w-3.5" />
              {{ kb.status?.docCount ?? 0 }} 篇文档
            </span>
            <span
              class="kb-meta-item"
              :title="kb.spec?.updateTime ? '更新于 ' + formatTime(kb.spec.updateTime) : '创建于 ' + formatTime(kb.metadata.creationTimestamp)"
            >
              更新于
              {{ formatRelativeTime(kb.spec?.updateTime || kb.metadata.creationTimestamp) }}
            </span>
          </div>
          <div class="kb-list-actions">
            <div class="kb-actions-inline">
              <button
                class="icon-btn icon-btn-share"
                :class="{ 'is-on': isKbShared(kb) }"
                :title="isKbShared(kb) ? '已分享，点击管理外链' : '分享'"
                aria-label="分享"
                @click.stop="openShare(kb)"
              >
                <span v-if="isKbShared(kb)" class="share-on-dot"></span>
                <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
              </button>
              <button
                class="icon-btn icon-btn-export"
                title="导出"
                aria-label="导出"
                @click.stop="exportOne(kb)"
              >
                <svg class="h-4 w-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v10"/><polyline points="7 10 12 15 17 10"/><path d="M4 19h16"/></svg>
              </button>
              <button
                class="icon-btn icon-btn-edit"
                title="编辑"
                aria-label="编辑"
                @click.stop="openEdit(kb)"
              >
                <IconRiPencilFill class="h-4 w-4" />
              </button>
              <button
                class="icon-btn icon-btn-delete"
                title="删除"
                aria-label="删除"
                @click.stop="remove(kb)"
              >
                <IconDeleteBin class="h-4 w-4" />
              </button>
            </div>
            <VDropdown class="kb-more-dropdown">
              <button class="icon-btn kb-more-btn" aria-label="更多操作" @click.stop>
                <svg class="h-4 w-4" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5" r="1.6"/><circle cx="12" cy="12" r="1.6"/><circle cx="12" cy="19" r="1.6"/></svg>
              </button>
              <template #popper>
                <VDropdownItem @click="openShare(kb)">
                  <template #prefix-icon>
                    <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><circle cx="18" cy="5" r="3"/><circle cx="6" cy="12" r="3"/><circle cx="18" cy="19" r="3"/><line x1="8.59" y1="13.51" x2="15.42" y2="17.49"/><line x1="15.41" y1="6.51" x2="8.59" y2="10.49"/></svg>
                  </template>
                  {{ isKbShared(kb) ? '管理分享' : '分享' }}
                </VDropdownItem>
              <VDropdownItem @click="exportOne(kb)">
                <template #prefix-icon>
                  <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 5v10"/><polyline points="7 10 12 15 17 10"/><path d="M4 19h16"/></svg>
                </template>
                导出
              </VDropdownItem>
              <VDropdownItem @click="openEdit(kb)">
                <template #prefix-icon>
                  <IconRiPencilFill class="h-3.5 w-3.5" />
                </template>
                编辑
              </VDropdownItem>
              <VDropdownItem type="danger" @click="remove(kb)">
                <template #prefix-icon>
                  <IconDeleteBin class="h-3.5 w-3.5" />
                </template>
                删除
              </VDropdownItem>
              </template>
            </VDropdown>
          </div>
        </div>
      </div>
    </div>

    <!-- 关闭滚动内容区 -->
    </div>

    <div class="kb-pagination-section">
      <PaginationBar
        :page="page"
        :size="size"
        :total="total"
        :size-options="[10, 20, 50, 100]"
        page-label="页"
        size-label="条/页"
        @update:page="onPageChange"
        @update:size="onSizeChange"
      />
    </div>
    </div>
  </div>

    <VModal
      v-model:visible="modalVisible"
      :title="editing ? '编辑知识库' : '新建知识库'"
      :width="560"
    >
      <div class="flex flex-col gap-4">
        <FormKit
          v-model="form.displayName"
          label="名称"
          name="displayName"
          validation="required"
          placeholder="例如：团队知识库"
        />
        <FormKit
          v-model="form.slug"
          label="链接别名"
          name="slug"
          type="text"
          placeholder="例如：team-kb（留空由系统自动生成）"
          help="用于前台访问链接 /docs/view/别名，只能包含字母、数字、连字符和下划线"
        />
        <FormKit
          v-model="form.description"
          label="描述"
          type="textarea"
          name="description"
          placeholder="一句话介绍这个知识库"
        />
        <div class="mb-4">
          <label class="formkit-label block text-sm font-medium text-gray-700">
            封面
          </label>
          <div class="kb-cover-field">
            <div class="kb-cover-preview">
              <img v-if="form.cover" :src="form.cover" alt="知识库封面" />
              <span v-else class="kb-cover-placeholder">无封面</span>
            </div>
            <div class="kb-cover-actions">
              <input
                v-model="form.cover"
                type="text"
                class="kb-cover-url-input"
                placeholder="粘贴图片链接"
              />
              <div class="kb-cover-btn-row">
                <VButton size="sm" type="primary" @click="triggerKbCover">
                  <template #icon>
                    <IconUpload class="h-3.5 w-3.5" />
                  </template>
                  上传本地图片
                </VButton>
                <input
                  ref="kbCoverInput"
                  type="file"
                  accept="image/*"
                  class="hidden-file-input"
                  @change="onKbCoverChange"
                />
                <button
                  v-if="form.cover"
                  class="kb-cover-remove"
                  @click="form.cover = ''"
                >
                  移除封面
                </button>
              </div>
            </div>
          </div>
          <p class="formkit-help mt-1 text-xs text-gray-500">
            可直接粘贴图片链接，或上传本地图片作为封面。建议用不含文字的纯视觉图（横向比例
            3:1 左右，主体居中）——标题由卡片单独渲染，图片里再写一遍标题会与卡片标题重复，
            且可能被封面裁切、无法被搜索
          </p>
        </div>
        <FormKit
          v-model="form.priority"
          label="优先级（排序权重）"
          type="number"
          name="priority"
          min="0"
          help="数字越小，在列表中选择“优先级”排序时越靠前"
        />
        <TagInput
          v-model="form.tags"
          label="标签"
          placeholder="输入后按回车添加"
        />
        <div v-if="!form.publicVisible" class="mb-4">
          <label class="formkit-label block text-sm font-medium text-gray-700">
            成员（私有知识库可访问者）
          </label>
          <p class="formkit-help mt-1 text-xs text-gray-500">
            勾选可访问该私有知识库的用户；开启公开可见后无需设置
          </p>
          <UserSelect
            v-model="form.members"
            class="mt-2"
            placeholder="请选择用户"
          />
        </div>
        <div class="flex items-center gap-2">
          <VSwitch v-model="form.publicVisible" />
          <span class="text-sm">公开可见（未登录用户可阅读公开知识库）</span>
        </div>
      </div>
      <template #footer>
        <VSpace>
          <VButton type="secondary" @click="modalVisible = false">取消</VButton>
          <VButton type="primary" :loading="saving" @click="save">保存</VButton>
        </VSpace>
      </template>
    </VModal>

    <VModal
      v-model:visible="importModalVisible"
      title="导入知识库"
      :width="560"
    >
      <div class="flex flex-col gap-4">
        <p class="text-sm text-gray-600">
          将根据 zip 包内的 <code>config.json</code> 还原知识库及其文档层级结构：
        </p>
        <div class="import-preview-list">
          <div
            v-for="item in importPreviewItems"
            :key="item.displayName"
            class="import-preview-row"
          >
            <div class="import-preview-main">
              <span class="import-preview-name">{{ item.displayName }}</span>
              <span class="import-preview-sub">{{ item.docCount }} 篇文档</span>
            </div>
            <div class="import-preview-status">
              <VTag v-if="item.exists" type="warning" size="sm">已存在，将被覆盖</VTag>
              <VTag v-else type="success" size="sm">新知识库</VTag>
            </div>
          </div>
        </div>
        <div class="flex items-center gap-3">
          <span class="text-sm text-gray-700">遇到同名知识库时：</span>
          <div class="flex items-center gap-1">
            <label class="import-radio-label">
              <input
                v-model="importOverwrite"
                type="radio"
                :value="true"
                class="mr-1"
              />
              覆盖
            </label>
            <label class="import-radio-label">
              <input
                v-model="importOverwrite"
                type="radio"
                :value="false"
                class="mr-1"
              />
              跳过
            </label>
          </div>
        </div>
        <p class="text-xs text-gray-500">
          覆盖采用安全替换：会先完整导入新数据并校验成功，再替换原同名知识库；
          若中途失败将自动回滚，原数据保持不变，不会丢失。
        </p>
      </div>
      <template #footer>
        <VSpace>
          <VButton type="secondary" :disabled="importing" @click="closeImportModal">
            取消
          </VButton>
          <VButton type="primary" :loading="importing" @click="confirmImport">
            开始导入
          </VButton>
        </VSpace>
      </template>
    </VModal>

    <!-- 外链分享弹窗 -->
    <VModal
      v-model:visible="shareModalVisible"
      :title="'分享知识库'"
      :width="560"
    >
      <div class="share-form">
        <div class="share-row share-toggle-row">
          <div class="share-row-left">
            <div class="share-row-title">开启外链分享</div>
            <div class="share-row-help">
              开启后任何人无需登录，凭外链即可查看，不受知识库公开/私有权限约束
            </div>
          </div>
          <VSwitch v-model="shareForm.enabled" />
        </div>

        <template v-if="shareForm.enabled">
          <div class="share-divider"></div>

          <!-- 访问密码 -->
          <div class="share-row">
            <div class="share-row-left">
              <div class="share-row-title">访问密码</div>
              <div class="share-row-help">留空则无需密码，任何持有外链的人均可直接访问</div>
            </div>
            <div class="share-row-right">
              <input
                v-model="shareForm.password"
                type="text"
                class="share-input"
                placeholder="不填表示无密码访问"
                autocomplete="off"
              />
            </div>
          </div>

          <!-- 外链有效期 -->
          <div class="share-row">
            <div class="share-row-left">
              <div class="share-row-title">外链有效期</div>
              <div class="share-row-help">到期后外链自动失效，需重新设置</div>
            </div>
            <div class="share-row-right">
              <select v-model="shareForm.period" class="share-select">
                <option :value="0">永久有效</option>
                <option :value="7">7 天</option>
                <option :value="30">30 天</option>
                <option :value="90">90 天</option>
              </select>
            </div>
          </div>

          <!-- 外链 -->
          <div class="share-divider"></div>
          <div class="share-row share-link-row">
            <div class="share-row-left">
              <div class="share-row-title">分享外链</div>
              <div class="share-row-help">保存后自动生成，可在此复制</div>
            </div>
            <div class="share-row-right share-link-box">
              <input
                :value="shareForm.url"
                type="text"
                class="share-input share-link-input"
                readonly
                placeholder="保存后将在此显示外链地址"
              />
              <VButton
                v-if="shareForm.url"
                size="sm"
                type="secondary"
                class="share-copy-btn"
                @click="copyShareLink"
              >
                复制链接
              </VButton>
            </div>
          </div>
        </template>
      </div>
      <template #footer>
        <VSpace>
          <VButton type="secondary" @click="shareModalVisible = false">取消</VButton>
          <VButton type="primary" :loading="shareSaving" @click="saveShare">
            保存
          </VButton>
        </VSpace>
      </template>
    </VModal>

    <div v-if="importWarnings.length" class="import-result-toast">
      <div
        v-for="w in importWarnings"
        :key="w.displayName"
        class="import-result-item"
      >
        <span class="import-result-name">{{ w.displayName }}</span>
        <span class="import-result-msg">{{ w.message }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.knowledge-base-list {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100vh;
  max-height: 100vh;
  overflow: hidden;
  background: #f9fafb;
}

/* 主体内容区：标题下方剩余空间，flex 列布局，铺满可用宽度 */
.knowledge-base-body {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  padding: 1rem;
  gap: 1rem;
}

/* 统计区面板 */
.stats-panel {
  flex: 0 0 auto;
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  padding: 0.75rem 1rem 1rem;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}

.stats-panel-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 0.5rem;
}

.stats-panel-title {
  font-size: 0.875rem;
  font-weight: 600;
  color: #374151;
}

.stats-toggle-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 0.375rem;
  width: 1.75rem;
  height: 1.75rem;
  border-radius: 8px;
  border: 1px solid transparent;
  background: #f3f4f6;
  color: #6b7280;
  cursor: pointer;
  transition: all 0.18s ease;
  padding: 0;
}

.stats-toggle-btn:hover {
  background: #e5e7eb;
  color: #374151;
}

.stats-toggle-restore {
  width: auto;
  padding: 0 0.75rem;
  font-size: 0.8125rem;
  gap: 0.375rem;
}

/* 统计栏隐藏时的恢复条 */
.stats-hidden-bar {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  background: #ffffff;
  border: 1px dashed #e5e7eb;
  border-radius: 10px;
  padding: 0.375rem 0.75rem;
}

/* 内容面板：批量栏 + 工具栏 + 可滚动列表 + 分页 */
.kb-content-panel {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  padding: 1rem;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
  overflow: hidden;
}

.kb-content-panel .batch-bar {
  flex: 0 0 auto;
  margin-bottom: 0.75rem;
}

.kb-content-panel .toolbar {
  flex: 0 0 auto;
  padding-bottom: 0.75rem;
  border-bottom: 1px solid #f3f4f6;
  margin-bottom: 0;
}

/* 可滚动列表区：自适应高度，超出时内部出现滚动条 */
.kb-content-panel .kb-scroll-area {
  flex: 1 1 auto;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  position: relative;
  padding-right: 0.25rem;
}

/* 加载 / 空状态在滚动区域内垂直居中 */
.kb-state {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100%;
}

/* 滚动条样式微调 */
.kb-scroll-area::-webkit-scrollbar {
  width: 6px;
}

.kb-scroll-area::-webkit-scrollbar-track {
  background: transparent;
}

.kb-scroll-area::-webkit-scrollbar-thumb {
  background: #d1d5db;
  border-radius: 3px;
}

.kb-scroll-area::-webkit-scrollbar-thumb:hover {
  background: #9ca3af;
}

/* 底部分页区：固定高度，不跟随列表滚动 */
.kb-pagination-section {
  flex: 0 0 auto;
  padding-top: 0.5rem;
  border-top: 1px solid #f3f4f6;
}

/* ========== 统计卡片 ========== */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(1, minmax(0, 1fr));
  gap: 1rem;
}

@media (min-width: 640px) {
  .stats-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (min-width: 1024px) {
  .stats-grid {
    grid-template-columns: repeat(5, minmax(0, 1fr));
  }
}

.stat-card {
  border-radius: 10px;
  border: none;
  background: #f9fafb;
  transition: all 0.2s ease;
}

.stat-card:hover {
  background: #f3f4f6;
  box-shadow: none;
}

.stat-card-inner {
  display: flex;
  align-items: center;
  gap: 0.875rem;
  padding: 0.75rem 0.875rem;
  min-height: 76px;
}

/* 统计卡片左上角彩色线性图标（纯描边，无背景块） */
.stat-icon {
  display: flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
}

.stat-icon svg {
  width: 1.375rem;
  height: 1.375rem;
  stroke-width: 1.8;
}

.stat-icon-blue   { color: #2563eb; }
.stat-icon-teal   { color: #14b8a6; }
.stat-icon-orange { color: #f59e0b; }
.stat-icon-purple { color: #8b5cf6; }
.stat-icon-green  { color: #22c55e; }

/* 数值行：标签/数值 + 右侧趋势 */
.stat-content {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 0.625rem;
}

.stat-main {
  display: flex;
  flex-direction: column;
  gap: 0.25rem;
}

.stat-label {
  font-size: 0.8125rem;
  color: #6b7280;
  font-weight: 500;
}

.stat-value {
  font-size: 1.625rem;
  line-height: 1;
  font-weight: 700;
  color: #111827;
  letter-spacing: -0.02em;
}

.stat-trend {
  font-size: 0.875rem;
  font-weight: 500;
  white-space: nowrap;
}

.trend-up {
  color: #16a34a;
}

.trend-down {
  color: #dc2626;
}

.trend-flat {
  color: #9ca3af;
}

.trend-private {
  color: #d97706;
}

/* ========== 批量操作栏 ========== */
.batch-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0.625rem 1rem;
  margin-bottom: 1rem;
  border-radius: 0.5rem;
  background: #eff6ff;
  border: 1px solid #dbeafe;
}

.batch-bar-text {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
  color: #1d4ed8;
  font-weight: 500;
}

/* ========== 搜索与筛选工具栏 ========== */
.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  margin-bottom: 1rem;
}

.toolbar-left,
.toolbar-right {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 0.75rem;
}

/* 左侧（页签 + 搜索）占据剩余空间，搜索框随之伸展 */
.toolbar-left {
  flex: 1 1 auto;
  min-width: 0;
}

.search-box {
  position: relative;
  flex: 1 1 200px;
  min-width: 160px;
  max-width: 420px;
}

.search-icon {
  position: absolute;
  left: 0.75rem;
  top: 50%;
  transform: translateY(-50%);
  width: 1rem;
  height: 1rem;
  color: #9ca3af;
  pointer-events: none;
}

.search-input {
  width: 100%;
  height: 38px;
  padding: 0 2.25rem 0 2.5rem;
  font-size: 0.8125rem;
  color: #374151;
  background: #ffffff;
  border: 1px solid #e8ecf3;
  border-radius: 12px;
  outline: none;
  transition: all 0.2s ease;
}

.search-input::placeholder {
  color: #9ca3af;
}

.search-input:focus {
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}

.search-clear {
  position: absolute;
  right: 0.5rem;
  top: 50%;
  transform: translateY(-50%);
  display: flex;
  align-items: center;
  justify-content: center;
  width: 1.5rem;
  height: 1.5rem;
  border-radius: 0.25rem;
  color: #9ca3af;
  background: transparent;
  border: none;
  cursor: pointer;
}

.search-clear:hover {
  background: #f3f4f6;
  color: #4b5563;
}

.filter-tabs {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px;
  background: #f6f8fc;
  border: 1px solid #eef1f6;
  border-radius: 10px;
  flex-shrink: 0;
}

.filter-tabs button {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 30px;
  padding: 0 14px;
  font-size: 0.8125rem;
  font-weight: 500;
  color: #4b5563;
  background: transparent;
  border: none;
  border-radius: 9999px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.filter-tabs button:hover {
  color: #111827;
}

/* 计数比标题稍轻 */
.filter-tabs button .tab-count {
  font-weight: 600;
  color: #9ca3af;
}

.filter-tabs button.active {
  color: #ffffff;
  background: #3b82f6;
  font-weight: 600;
  box-shadow: 0 2px 6px rgba(59, 130, 246, 0.3);
}

.filter-tabs button.active .tab-count {
  color: rgba(255, 255, 255, 0.82);
}

.sort-box {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  height: 38px;
  padding: 0 8px 0 12px;
  background: #ffffff;
  border: 1px solid #e8ecf3;
  border-radius: 12px;
  flex-shrink: 0;
  transition: all 0.2s ease;
}

.sort-box:hover {
  border-color: #dbe2ec;
}

.sort-box:focus-within {
  border-color: #93c5fd;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
}

.sort-icon {
  width: 15px;
  height: 15px;
  color: #374151;
  flex-shrink: 0;
}

.sort-select {
  height: 100%;
  padding: 0 1.5rem 0 2px;
  font-size: 0.8125rem;
  font-weight: 500;
  color: #374151;
  background: transparent;
  border: none;
  border-radius: 12px;
  outline: none;
  cursor: pointer;
  appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='14' height='14' viewBox='0 0 24 24' fill='none' stroke='%239ca3af' stroke-width='2.2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='M6 9l6 6 6-6'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 0.125rem center;
}

.view-toggle {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 4px;
  background: #ffffff;
  border: 1px solid #e8ecf3;
  border-radius: 12px;
  flex-shrink: 0;
}

.view-toggle button {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  color: #9ca3af;
  background: transparent;
  border: none;
  border-radius: 9px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.view-toggle button:hover {
  color: #6b7280;
  background: #f3f4f6;
}

.view-toggle button.active {
  color: #3b82f6;
  background: #e8f0ff;
}

/* ========== 网格视图 ========== */
.kb-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(286px, 1fr));
  gap: 1rem;
}

/* ---------- 知识库卡片（设计稿还原） ---------- */
.kb-card {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  background: #ffffff;
  border: 1px solid #eef1f6;
  border-radius: 12px;
  overflow: hidden;
  cursor: pointer;
  box-shadow: 0 1px 2px rgba(16, 24, 40, 0.04);
  transition: box-shadow 0.22s ease, border-color 0.22s ease, transform 0.22s ease;
}

.kb-card:hover {
  border-color: #e3e8f0;
  box-shadow: 0 10px 24px rgba(16, 24, 40, 0.08);
  transform: translateY(-1px);
}

.kb-card.is-selected {
  border-color: #bfdbfe;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
}

/* 封面：整幅铺满卡片顶部，跟随卡片圆角裁切 */
.kb-card-cover {
  position: relative;
  flex: 0 0 auto;
  height: 70px;
  background: #eef2f8;
  overflow: hidden;
}

.kb-cover-img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

/* 无封面时的兜底底色 */
.kb-cover-fallback {
  width: 100%;
  height: 100%;
  background: linear-gradient(120deg, #e9f1ff 0%, #e2ecfb 46%, #e6f5f0 100%);
}

/* 状态徽标：封面右上角 */
.kb-status-badge {
  position: absolute;
  top: 10px;
  right: 10px;
  z-index: 2;
  display: inline-flex;
  align-items: center;
  gap: 3px;
  height: 21px;
  padding: 0 8px;
  border-radius: 9999px;
  font-size: 0.75rem;
  font-weight: 600;
  line-height: 1;
  color: #ffffff;
  box-shadow: 0 1px 4px rgba(16, 24, 40, 0.16);
}

.kb-badge-icon {
  width: 0.75rem;
  height: 0.75rem;
}

.kb-badge-public {
  background: #10b981;
}

.kb-badge-private {
  background: #f59e0b;
}

.kb-badge-team {
  background: #3b82f6;
}

/* logo + 标题：logo 绝对定位悬浮压在封面下沿（不占排版高），标题行紧凑贴封面，标签紧随标题 */
.kb-card-head {
  position: relative;
  z-index: 2;
  display: block;
  padding: 0 18px;
}

.kb-logo {
  position: absolute;
  left: 18px;
  top: -24px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 50px;
  height: 50px;
  padding: 4px;
  border-radius: 12px;
  background: #ffffff;
  border: 1px solid #f0f2f6;
  box-shadow: 0 3px 10px rgba(16, 24, 40, 0.1);
  overflow: hidden;
}

.kb-card-head .kb-card-title {
  padding-top: 10px;
  padding-left: 62px;
}

.kb-logo img {
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.kb-logo-fallback {
  width: 92%;
  height: 92%;
  color: #10b981;
}

.kb-card-title {
  min-width: 0;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.9375rem;
  font-weight: 700;
  color: #111827;
  line-height: 1.35;
  letter-spacing: -0.01em;
}

/* 标签 */
.kb-card-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  padding: 10px 18px 0;
}

.kb-tag {
  display: inline-flex;
  align-items: center;
  height: 22px;
  padding: 0 10px;
  border-radius: 9999px;
  background: #eef2fd;
  color: #4655c4;
  font-size: 0.78125rem;
  font-weight: 500;
  white-space: nowrap;
}

/* 描述：固定两行 */
.kb-card-desc {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  margin: 0;
  padding: 11px 18px 0;
  font-size: 0.8125rem;
  line-height: 1.4;
  color: #6b7280;
  word-break: break-word;
}

/* 文档数 / 更新时间 */
.kb-card-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.625rem;
  margin-top: auto;
  padding: 14px 18px 0;
}

.kb-meta-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 0.75rem;
  color: #9ca3af;
}

.kb-meta-icon {
  width: 0.875rem;
  height: 0.875rem;
  flex-shrink: 0;
}

/* 操作区 */
.kb-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.625rem;
  padding: 8px 18px 17px;
}

.kb-enter-btn {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  height: 26px;
  padding: 0 14px;
  border: none;
  border-radius: 6px;
  background: #10b981;
  color: #ffffff;
  font-size: 0.8125rem;
  font-weight: 600;
  line-height: 1;
  cursor: pointer;
  transition: background 0.18s ease, box-shadow 0.18s ease;
}

.kb-enter-btn:hover {
  background: #0ea472;
  box-shadow: 0 4px 12px rgba(16, 185, 129, 0.3);
}

.kb-enter-btn:active {
  transform: translateY(1px);
}

.kb-enter-chevron {
  width: 13px;
  height: 13px;
}

.kb-card-actions {
  display: flex;
  align-items: center;
  gap: 11px;
  flex-shrink: 0;
}

.kb-icon-btn {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: 8px;
  background: transparent;
  color: #6b7280;
  cursor: pointer;
  flex-shrink: 0;
  transition: background 0.18s ease, color 0.18s ease, border-color 0.18s ease, transform 0.18s ease;
}

.kb-icon-btn:hover {
  transform: translateY(-1px);
}

.kb-icon-btn:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.22);
}

.kb-icon {
  width: 18px;
  height: 18px;
}

/* 预览（浅绿） */
.kb-icon-preview {
  background: #e7f7f1;
  color: #059669;
}

.kb-icon-preview:hover {
  background: #d2f0e5;
  color: #047857;
}

/* 编辑（浅蓝） */
.kb-icon-edit {
  background: #ecf2ff;
  color: #2563eb;
}

.kb-icon-edit:hover {
  background: #dde9ff;
  color: #1d4ed8;
}

/* 更多（白底描边） */
.kb-icon-more {
  background: #ffffff;
  color: #9ca3af;
  border-color: #e9ebf0;
}

.kb-icon-more:hover {
  background: #f7f8fa;
  color: #6b7280;
  border-color: #e0e3ea;
}

/* 已开启外链分享：更多按钮右上角绿点 */
.kb-shared-dot {
  position: absolute;
  top: -1px;
  right: -1px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #10b981;
  border: 2px solid #ffffff;
}

/* 悬浮选择角标：默认隐藏，hover / 已选中时显示；圆形空心，选中后变蓝色实底 + 白色对勾 */
.kb-card-check {
  position: absolute;
  top: 10px;
  left: 10px;
  z-index: 3;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(15, 23, 42, 0.18);
  color: #3b82f6;
  box-shadow: 0 1px 3px rgba(16, 24, 40, 0.16);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.18s ease, background 0.18s ease, border-color 0.18s ease;
}

.kb-card:hover .kb-card-check,
.kb-card:focus-within .kb-card-check,
.kb-card-check.is-on {
  opacity: 1;
}

.kb-card-check .kb-check-mark {
  width: 12px;
  height: 12px;
  opacity: 0;
  transition: opacity 0.18s ease;
}

.kb-card-check.is-on {
  background: #3b82f6;
  border-color: #3b82f6;
  color: #ffffff;
}

.kb-card-check.is-on .kb-check-mark {
  opacity: 1;
}

/* 列表视图的行内状态标签（与卡片徽标同色系） */
.kb-status-inline {
  display: inline-flex;
  align-items: center;
  border-radius: 9999px;
  margin-left: 0.5rem;
  flex-shrink: 0;
}

.kb-status-private,
.kb-status-public,
.kb-status-team {
  color: #ffffff !important;
  background-color: transparent;
  border-color: transparent !important;
}

.kb-status-private {
  background-color: #f59e0b !important;
  border-color: #f59e0b !important;
}

.kb-status-public {
  background-color: #10b981 !important;
  border-color: #10b981 !important;
}

.kb-status-team {
  background-color: #3b82f6 !important;
  border-color: #3b82f6 !important;
}

/* 列表视图复用的内联操作组 */
.kb-actions-inline {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.icon-btn.kb-more-btn {
  display: none;
  background: #f3f4f6;
  color: #4b5563;
  border-color: transparent;
}

.kb-list-actions .kb-actions-inline {
  gap: 0.375rem;
}

/* ========== 图标按钮（仅图标，无文字） ========== */
.icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 1.875rem;
  height: 1.875rem;
  border-radius: 9999px;
  border: 1px solid transparent;
  background: transparent;
  cursor: pointer;
  transition: all 0.18s ease;
  color: #4b5563;
  padding: 0;
  flex-shrink: 0;
}

.icon-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 10px rgba(0, 0, 0, 0.08);
}

.icon-btn:active {
  transform: translateY(0);
  box-shadow: none;
}

.icon-btn:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.25);
}

.icon-btn-edit {
  background: #eff6ff;
  color: #2563eb;
}

.icon-btn-edit:hover {
  background: #dbeafe;
  color: #1d4ed8;
  border-color: #bfdbfe;
}

.icon-btn-delete {
  background: #fef2f2;
  color: #dc2626;
}

.icon-btn-delete:hover {
  background: #fee2e2;
  color: #b91c1c;
  border-color: #fecaca;
}

/* ========== 列表视图 ========== */
.kb-list {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
}

.kb-list-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  padding: 0.5rem 0.75rem;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.15s ease;
}

.kb-list-row:hover {
  background: #f5f7fa;
}

.kb-list-info {
  min-width: 0;
  flex: 1 1 auto;
}

.kb-list-title-row {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.kb-list-title-row .kb-card-title {
  font-size: 0.875rem;
  max-width: 100%;
}

.kb-list-desc {
  margin: 0.125rem 0 0;
  font-size: 0.75rem;
  color: #9ca3af;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.kb-list-side {
  display: flex;
  align-items: center;
  gap: 0;
  flex-shrink: 0;
  margin-left: auto;
}

.kb-list-meta {
  display: flex;
  align-items: center;
  gap: 0.875rem;
  color: #9ca3af;
  flex-shrink: 0;
}

.kb-list-actions {
  display: flex;
  align-items: center;
  gap: 0.375rem;
  flex-shrink: 0;
}

/* 列表内操作组：默认中性灰，hover 时呈现各自彩色 */
.kb-list-actions .icon-btn {
  background: #f3f4f6;
  color: #6b7280;
  border-color: transparent;
}

.kb-list-actions .icon-btn-share.is-on {
  background: #d1fae5;
  color: #047857;
}

/* 桌面端宽屏：数量/时间信息靠最右；行 hover 时按钮组展开，信息自动左移让位（窄屏/触屏设备按钮常显） */
@media (hover: hover) and (min-width: 768px) {
  .kb-list-actions {
    max-width: 0;
    margin-left: 0;
    opacity: 0;
    overflow: hidden;
    transition: max-width 0.25s ease, opacity 0.2s ease, margin-left 0.25s ease;
  }

  .kb-list-row:hover .kb-list-actions,
  .kb-list-row:focus-within .kb-list-actions {
    max-width: 10rem;
    margin-left: 1.25rem;
    opacity: 1;
  }
}

@media (max-width: 1024px) {
  .kb-list-row {
    flex-wrap: wrap;
  }

  .kb-list-info {
    flex: 1 1 calc(100% - 4.5rem);
  }

  .kb-list-side {
    flex: 1 1 100%;
    margin-left: 4.5rem;
    margin-top: 0.5rem;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 0.5rem;
  }

  .kb-list-actions {
    margin-left: auto;
  }
}

/* ========== 复选框 ========== */
.kb-checkbox {
  width: 1rem;
  height: 1rem;
  accent-color: #3b82f6;
  cursor: pointer;
  border-radius: 0.25rem;
  flex-shrink: 0;
  margin-top: 0.25rem;
}

/* ========== 标签 ========== */
:deep(.tag-wrapper.tag-success) {
  background: #dcfce7;
  border-color: #bbf7d0;
  color: #166534;
  font-weight: 500;
}

:deep(.tag-wrapper.tag-warning) {
  background: #fef3c7;
  border-color: #fde68a;
  color: #92400e;
  font-weight: 500;
}

:deep(.tag-wrapper.tag-secondary) {
  background: #f1f5f9;
  border-color: #e2e8f0;
  color: #475569;
}

/* ========== 按钮 ========== */
:deep(.btn-default) {
  transition: all 0.2s ease;
}

:deep(.btn-default:hover) {
  background-color: #f3f4f6;
  border-color: #e5e7eb;
}

/* ========== 模态框 ========== */
:deep(.modal-content) {
  border-radius: 12px;
  overflow: hidden;
}

:deep(.modal-header) {
  padding: 1rem 1.25rem;
  border-bottom: 1px solid #f3f4f6;
  background: #fafafa;
}

:deep(.modal-header-title) {
  font-weight: 600;
  color: #111827;
  font-size: 1rem;
}

:deep(.modal-body) {
  padding: 1.25rem;
}

:deep(.modal-footer) {
  padding: 1rem 1.25rem;
  background: #fafafa;
  border-top: 1px solid #f3f4f6;
}

/* ========== 入场动画 ========== */
@keyframes fadeInUp {
  from {
    opacity: 0;
    transform: translateY(8px);
  }

  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.kb-card {
  animation: fadeInUp 0.25s ease-out both;
}

/* ========== 隐藏文件输入 ========== */
.hidden-file-input {
  display: none;
}

/* ========== 导出按钮 ========== */
.icon-btn-export {
  background: #fffbeb;
  color: #d97706;
}

.icon-btn-export:hover {
  background: #fef3c7;
  color: #b45309;
  border-color: #fde68a;
}

/* ========== 分享按钮 ========== */
.icon-btn-share {
  position: relative;
  background: #ecfdf5;
  color: #059669;
}

.icon-btn-share:hover,
.icon-btn-share.is-on {
  background: #d1fae5;
  color: #047857;
  border-color: #a7f3d0;
}

/* 已分享状态：右上角绿色小圆点 */
.share-on-dot {
  position: absolute;
  top: 1px;
  right: 1px;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #10b981;
  border: 2px solid #fff;
  box-shadow: 0 0 0 1px #10b981;
}

/* ========== 知识库封面上传 ========== */
.kb-cover-field {
  display: flex;
  gap: 0.75rem;
  align-items: flex-start;
  margin-top: 0.375rem;
}

.kb-cover-preview {
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

.kb-cover-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.kb-cover-placeholder {
  font-size: 0.75rem;
  color: #bfbfbf;
}

.kb-cover-actions {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 0.375rem;
}

.kb-cover-url-input {
  width: 15rem;
  max-width: 100%;
  padding: 0.375rem 0.625rem;
  font-size: 0.8125rem;
  color: #374151;
  border: 1px solid #d1d5db;
  border-radius: 0.375rem;
  outline: none;
  transition: border-color 0.15s ease;
}

.kb-cover-url-input::placeholder {
  color: #9ca3af;
}

.kb-cover-url-input:focus {
  border-color: #10b981;
}

.kb-cover-btn-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.kb-cover-remove {
  padding: 0;
  font-size: 0.75rem;
  color: #ff4d4f;
  background: transparent;
  border: none;
  cursor: pointer;
}

.kb-cover-remove:hover {
  text-decoration: underline;
}

/* ========== 导入知识库预览 ========== */
.import-preview-list {
  display: flex;
  flex-direction: column;
  border: 1px solid #e5e7eb;
  border-radius: 0.5rem;
  overflow: hidden;
  max-height: 280px;
  overflow-y: auto;
}

.import-preview-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 0.625rem 0.875rem;
  border-bottom: 1px solid #f3f4f6;
}

.import-preview-row:last-child {
  border-bottom: none;
}

.import-preview-main {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
  min-width: 0;
}

.import-preview-name {
  font-size: 0.875rem;
  font-weight: 500;
  color: #111827;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.import-preview-sub {
  font-size: 0.75rem;
  color: #6b7280;
}

.import-preview-status {
  flex-shrink: 0;
}

.import-radio-label {
  display: inline-flex;
  align-items: center;
  font-size: 0.875rem;
  color: #374151;
  cursor: pointer;
  padding: 0 0.25rem;
}

.import-radio-label input {
  accent-color: #3b82f6;
}

/* ========== 导入结果浮层 ========== */
.import-result-toast {
  position: fixed;
  right: 1.5rem;
  bottom: 1.5rem;
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
  z-index: 1000;
}

.import-result-item {
  display: flex;
  flex-direction: column;
  gap: 0.125rem;
  min-width: 16rem;
  max-width: 22rem;
  padding: 0.625rem 0.875rem;
  border-radius: 0.5rem;
  background: #111827;
  color: #f9fafb;
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.15);
  animation: fadeInUp 0.25s ease-out both;
}

.import-result-name {
  font-size: 0.8125rem;
  font-weight: 600;
}

.import-result-msg {
  font-size: 0.75rem;
  color: #d1d5db;
}

/* ========== 外链分享弹窗 ========== */
.share-form {
  display: flex;
  flex-direction: column;
  gap: 0.875rem;
}

.share-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 1rem;
}

.share-toggle-row {
  align-items: center;
}

.share-row-left {
  flex: 1 1 auto;
  min-width: 0;
}

.share-row-title {
  font-size: 0.875rem;
  font-weight: 600;
  color: #111827;
}

.share-row-help {
  margin-top: 0.1875rem;
  font-size: 0.75rem;
  color: #6b7280;
}

.share-row-right {
  flex: 0 0 auto;
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.share-input {
  width: 13rem;
  padding: 0.4375rem 0.625rem;
  font-size: 0.8125rem;
  line-height: 1.4;
  color: #111827;
  background: #f9fafb;
  border: 1px solid #d1d5db;
  border-radius: 0.375rem;
  outline: none;
  transition: border-color 0.15s ease, box-shadow 0.15s ease;
}

.share-input:focus {
  border-color: #10b981;
  box-shadow: 0 0 0 3px rgba(16, 185, 129, 0.15);
}

.share-select {
  width: 9rem;
  padding: 0.4375rem 0.5rem;
  font-size: 0.8125rem;
  color: #111827;
  background: #f9fafb;
  border: 1px solid #d1d5db;
  border-radius: 0.375rem;
  outline: none;
}

.share-divider {
  height: 1px;
  background: #e5e7eb;
}

.share-link-row {
  align-items: center;
}

.share-link-box {
  gap: 0.5rem;
}

.share-link-input {
  width: 15rem;
  color: #6b7280;
  background: #f3f4f6;
  cursor: default;
}

.share-copy-btn {
  flex-shrink: 0;
}

/* ========== 移动端适配（≤767px） ========== */
@media (max-width: 767px) {
  .knowledge-base-body {
    padding: 0.625rem;
    gap: 0.625rem;
  }

  /* 顶部标题区：标题与按钮同行不换行，按钮仅图标 */
  :deep(.page-header) {
    flex-wrap: nowrap;
    padding: 0.625rem 0.875rem;
  }

  :deep(.page-header__actions) {
    width: auto;
    flex-wrap: nowrap;
    flex-shrink: 0;
    gap: 0.5rem;
  }

  :deep(.page-header__actions .btn) {
    flex: 0 0 auto;
    justify-content: center;
    width: 2.125rem;
    height: 2.125rem;
    padding: 0;
  }

  :deep(.page-header__actions .btn .btn-content) {
    display: none;
  }

  :deep(.page-header__actions .btn .btn-icon) {
    margin: 0;
  }

  /* 移动端隐藏统计面板 */
  .stats-panel,
  .stats-hidden-bar {
    display: none;
  }

  /* 内容面板 */
  .kb-content-panel {
    padding: 0.625rem;
    border-radius: 10px;
  }

  /* 批量操作栏：垂直堆叠，按钮整行排布 */
  .batch-bar {
    flex-direction: column;
    align-items: stretch;
    gap: 0.625rem;
    padding: 0.625rem 0.75rem;
  }

  .batch-bar :deep(.space-wrapper) {
    width: 100%;
    flex-wrap: wrap;
  }

  .batch-bar :deep(.space-wrapper .btn) {
    flex: 1 1 auto;
    justify-content: center;
    padding-top: 0;
    padding-bottom: 0;
  }

  /* 工具栏：筛选占一行，搜索框与排序下拉同一行 */
  .toolbar {
    display: grid;
    grid-template-columns: minmax(0, 1fr) auto;
    grid-template-areas:
      "tabs tabs"
      "search sort";
    align-items: center;
    gap: 0.5rem;
  }

  .toolbar-left {
    display: contents;
  }

  .filter-tabs {
    grid-area: tabs;
    width: 100%;
    display: flex;
  }

  .filter-tabs button {
    flex: 1 1 0;
    justify-content: center;
    gap: 4px;
    padding: 0 6px;
    font-size: 0.8125rem;
    white-space: nowrap;
  }

  .search-box {
    grid-area: search;
    width: 100%;
    min-width: 0;
  }

  .search-input {
    height: 2.375rem;
  }

  .toolbar-right {
    grid-area: sort;
    width: auto;
    justify-content: flex-end;
  }

  .sort-box {
    flex: 0 0 auto;
    min-width: 0;
  }

  .sort-select {
    min-width: 0;
    max-width: 6.5rem;
  }

  /* 网格视图：单列布局，卡片内边距收紧 */
  .kb-grid {
    grid-template-columns: 1fr;
    gap: 0.75rem;
  }

  .kb-card-cover {
    height: 86px;
  }

  .kb-card-head {
    display: block;
    padding: 0 16px;
  }

  .kb-logo {
    position: absolute;
    left: 16px;
    top: -20px;
    width: 44px;
    height: 44px;
    padding: 3px;
    border-radius: 11px;
  }

  .kb-card-head .kb-card-title {
    padding-top: 8px;
    padding-left: 54px;
  }

  .kb-card-tags,
  .kb-card-desc {
    padding-left: 16px;
    padding-right: 16px;
  }

  .kb-card-meta {
    padding: 14px 16px 0;
  }

  .kb-card-footer {
    padding: 8px 16px 17px;
  }

  /* 触屏没有 hover：选择框常显 */
  .kb-card-check {
    opacity: 1;
  }

  /* 移动端：操作收进「更多」菜单 */
  .kb-actions-inline {
    display: none;
  }

  .icon-btn.kb-more-btn {
    display: inline-flex;
  }

  /* 移动端视图切换：强制列表模式，隐藏切换按钮 */
  .view-toggle {
    display: none;
  }

  /* ===== 移动端列表视图：卡片式行，杜绝横向溢出 ===== */
  .kb-list {
    gap: 0.625rem;
  }

  .kb-list-row {
    align-items: center;
    gap: 0.625rem;
    padding: 0.75rem 0.875rem;
    background: #ffffff;
    border: 1px solid #eef0f2;
    border-radius: 12px;
    box-shadow: 0 1px 2px rgba(17, 24, 39, 0.04);
    max-width: 100%;
  }

  .kb-list-row:active {
    background: #f9fafb;
  }

  .kb-list-row .kb-checkbox {
    width: 1.125rem;
    height: 1.125rem;
    flex-shrink: 0;
  }

  .kb-list-info {
    flex: 1 1 0;
    min-width: 0;
  }

  .kb-list-title-row {
    gap: 0.375rem;
    min-width: 0;
  }

  .kb-list-title-row .kb-card-title {
    flex: 1 1 0;
    min-width: 0;
    font-size: 0.9375rem;
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .kb-list-title-row .kb-status-inline {
    flex-shrink: 0;
    font-size: 0.6875rem;
    padding: 0.125rem 0.5rem;
  }

  .kb-list-desc {
    white-space: normal;
    display: -webkit-box;
    -webkit-line-clamp: 2;
    -webkit-box-orient: vertical;
    font-size: 0.8125rem;
    line-height: 1.5;
    color: #6b7280;
    margin-top: 0.25rem;
    word-break: break-word;
    overflow: hidden;
  }

  /* 第二行：元信息 + 更多按钮，margin-left 归零修复溢出 */
  .kb-list-side {
    flex: 1 1 100%;
    margin-left: 0;
    margin-top: 0.375rem;
    justify-content: space-between;
    flex-wrap: nowrap;
    gap: 0.5rem;
    padding-top: 0.5rem;
    border-top: 1px dashed #eef0f2;
    min-width: 0;
  }

  .kb-list-meta {
    min-width: 0;
    gap: 0.625rem;
    font-size: 0.75rem;
  }

  .kb-list-actions {
    gap: 0.375rem;
    margin-left: auto;
    flex-shrink: 0;
  }

  /* 弹窗宽度适配（新建/导入/分享） */
  :deep(.modal-content) {
    width: calc(100vw - 1.5rem) !important;
    max-height: 85vh;
  }

  /* 分享表单：窄屏下纵向堆叠 */
  .share-row {
    flex-direction: column;
    gap: 0.5rem;
  }

  .share-toggle-row {
    flex-direction: row;
  }

  /* 纵向堆叠后 align-items: center 会把标题区水平居中，改为拉伸保持左对齐 */
  .share-link-row {
    align-items: stretch;
  }

  .share-row-right {
    width: 100%;
  }

  .share-input,
  .share-link-input {
    width: 100%;
  }

  .share-link-box {
    flex-direction: column;
    align-items: stretch;
  }
}

/* 极窄屏（≤400px）：进一步收紧 */
@media (max-width: 400px) {
  .kb-card-head,
  .kb-card-tags,
  .kb-card-desc,
  .kb-card-meta,
  .kb-card-footer {
    padding-left: 12px;
    padding-right: 12px;
  }
}
</style>