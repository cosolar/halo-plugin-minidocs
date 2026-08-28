<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import {
  Dialog,
  Toast,
  VButton,
  VCard,
  VEmpty,
  VLoading,
  VModal,
  VPageHeader,
  VSpace,
  VSwitch,
  VTag,
  IconBookRead,
  IconRiPencilFill,
  IconDeleteBin,
  IconAddCircle,
  IconPages,
  IconSearch,
  IconGrid,
  IconList,
  IconEye,
  IconLockPasswordLine,
  IconArrowRight,
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
const sortBy = ref<"updateTime" | "createTime" | "name" | "docCount" | "priority">("updateTime");
const stats = ref<Stats | null>(null);
const statsLoading = ref(false);

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
  await doExport([kb.metadata.name]);
}
async function doExport(names: string[]) {
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
    a.download = `minidocs${exportTimestamp()}.zip`;
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
  loadStats();
  load();
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
          导入知识库
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
          新建知识库
        </VButton>
      </template>
    </VPageHeader>

    <!-- 页面主体：统计面板 + 内容面板 -->
    <div class="knowledge-base-body">
      <!-- 统计区面板 -->
      <div class="stats-panel">
        <div v-if="stats" class="stats-grid">
          <VCard :body-class="['!p-0']" class="stat-card">
            <div class="stat-card-inner">
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
          </VCard>
          <VCard :body-class="['!p-0']" class="stat-card">
            <div class="stat-card-inner">
              <div class="stat-main">
                <span class="stat-label">公开知识库</span>
                <span class="stat-value">{{ stats.publicCount }}</span>
              </div>
              <span class="stat-trend"> 占比 {{ stats.publicRatio || '0%' }} </span>
            </div>
          </VCard>
          <VCard :body-class="['!p-0']" class="stat-card">
            <div class="stat-card-inner">
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
          </VCard>
          <VCard :body-class="['!p-0']" class="stat-card">
            <div class="stat-card-inner">
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
          </VCard>
        </div>
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
                全部 {{ stats?.total || 0 }}
              </button>
              <button
                :class="{ active: publicVisible === true }"
                @click="filterVisibility(true)"
              >
                公开 {{ stats?.publicCount || 0 }}
              </button>
              <button
                :class="{ active: publicVisible === false }"
                @click="filterVisibility(false)"
              >
                私有 {{ stats?.privateCount || 0 }}
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
            <select v-model="sortBy" class="sort-select" @change="load">
              <option value="updateTime">最近更新</option>
              <option value="createTime">最近创建</option>
              <option value="name">名称</option>
              <option value="docCount">文档数</option>
              <option value="priority">优先级</option>
            </select>
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

      <!-- 网格视图 -->
    <div v-else-if="viewMode === 'grid'" class="kb-grid">
      <VCard
        v-for="kb in kbs"
        :key="kb.metadata.name"
        :title="kb.spec.displayName"
        :body-class="['!p-0']"
        class="kb-card kb-card-grid"
      >
        <template #header>
          <div class="kb-card-header">
            <input
              type="checkbox"
              class="kb-checkbox"
              :checked="isSelected(kb.metadata.name)"
              @click.stop
              @change="toggleSelect(kb)"
            />
            <div class="kb-card-info">
              <div class="kb-avatar" :class="[getStatusTheme(kb).bg, getStatusTheme(kb).text]">
                <IconBookRead class="h-6 w-6" />
              </div>
              <div class="kb-card-titles">
                <h3 class="kb-card-title">{{ kb.spec.displayName }}</h3>
                <div v-if="kb.spec.tags?.length" class="kb-tag-list kb-tag-list-inline">
                  <span
                    v-for="tag in kb.spec.tags.slice(0, 4)"
                    :key="tag"
                    class="kb-tag-chip"
                  >
                    {{ tag }}
                  </span>
                </div>
              </div>
            </div>
            <VTag
              v-if="kb.spec.members?.length"
              size="sm"
              class="kb-status-tag kb-status-team"
            >
              <template #leftIcon>
                <svg class="h-3 w-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
              </template>
              团队
            </VTag>
            <VTag
              v-else-if="kb.spec.publicVisible"
              size="sm"
              class="kb-status-tag kb-status-public"
            >
              <template #leftIcon>
                <IconEye class="h-3 w-3" />
              </template>
              公开
            </VTag>
            <VTag v-else size="sm" class="kb-status-tag kb-status-private">
              <template #leftIcon>
                <IconLockPasswordLine class="h-3 w-3" />
              </template>
              私有
            </VTag>
          </div>
        </template>

        <div class="kb-card-body">
          <p class="kb-card-desc">
            {{ kb.spec.description || '暂无描述' }}
          </p>
          <div class="kb-card-meta">
            <span class="kb-meta-left">
              <span class="kb-meta-item" title="文档数">
                <IconPages class="h-3.5 w-3.5" />
                {{ kb.status?.docCount ?? 0 }}
              </span>
              <span class="kb-meta-item" :title="'访问量 ' + (kb.spec?.accessCount ?? 0)">
                <IconEye class="h-3.5 w-3.5" />
                {{ kb.spec?.accessCount ?? 0 }}
              </span>
              <span class="kb-meta-item" :title="'点赞量 ' + (kb.spec?.likeCount ?? 0)">
                <svg class="h-3.5 w-3.5" viewBox="0 0 24 24" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20.84 4.61a5.5 5.5 0 0 0-7.78 0L12 5.67l-1.06-1.06a5.5 5.5 0 0 0-7.78 7.78l1.06 1.06L12 21.23l7.78-7.78 1.06-1.06a5.5 5.5 0 0 0 0-7.78z"/></svg>
                {{ kb.spec?.likeCount ?? 0 }}
              </span>
            </span>
            <span
              class="kb-meta-item"
              :title="kb.spec?.updateTime ? '更新于 ' + formatTime(kb.spec.updateTime) : '创建于 ' + formatTime(kb.metadata.creationTimestamp)"
            >
              更新于
              {{ formatRelativeTime(kb.spec?.updateTime || kb.metadata.creationTimestamp) }}
            </span>
          </div>
        </div>

        <template #footer>
          <div class="kb-card-footer">
            <VButton
              size="sm"
              type="primary"
              class="kb-enter-btn"
              @click="goDetail(kb.metadata.name)"
            >
              <template #icon>
                <IconArrowRight class="h-3.5 w-3.5" />
              </template>
              进入
            </VButton>
            <div class="kb-card-actions">
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
          </div>
        </template>
      </VCard>
    </div>

    <!-- 列表视图 -->
    <div v-else class="kb-list">
      <VCard
        v-for="kb in kbs"
        :key="kb.metadata.name"
        :body-class="['!p-0']"
        class="kb-card kb-card-list"
        @click="goDetail(kb.metadata.name)"
      >
        <template #header>
          <div class="kb-list-row">
            <input
              type="checkbox"
              class="kb-checkbox"
              :checked="isSelected(kb.metadata.name)"
              @click.stop
              @change="toggleSelect(kb)"
            />
            <div class="kb-avatar" :class="[getStatusTheme(kb).bg, getStatusTheme(kb).text]">
              <IconBookRead class="h-5 w-5" />
            </div>
            <div class="kb-list-info">
              <div class="kb-list-title-row">
                <h3 class="kb-card-title">{{ kb.spec.displayName }}</h3>
                <VTag v-if="kb.spec.members?.length" size="sm" class="kb-status-inline kb-status-team">团队</VTag>
                <VTag v-else-if="kb.spec.publicVisible" size="sm" class="kb-status-inline kb-status-public">公开</VTag>
                <VTag v-else size="sm" class="kb-status-inline kb-status-private">私有</VTag>
              </div>
              <p class="kb-card-desc">
                {{ kb.spec.description || '暂无描述' }}
              </p>
            </div>
            <div class="kb-list-meta">
              <span class="kb-meta-item">
                <IconPages class="h-3.5 w-3.5" />
                {{ kb.status?.docCount ?? 0 }} 篇文档
              </span>
              <span
                class="kb-meta-item"
                :title="'创建于 ' + formatTime(kb.metadata.creationTimestamp)"
              >
                创建于
                {{ formatTime(kb.metadata.creationTimestamp, 'MM/DD HH:mm') }}
              </span>
            </div>
            <div class="kb-list-actions">
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
          </div>
        </template>
      </VCard>
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
              <VButton size="sm" type="primary" @click="triggerKbCover">
                <template #icon>
                  <IconUpload class="h-3.5 w-3.5" />
                </template>
                上传封面
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
                移除
              </button>
            </div>
          </div>
          <p class="formkit-help mt-1 text-xs text-gray-500">
            支持上传本地图片作为封面，或填入图片链接
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

/* 主体内容区：标题下方剩余空间，flex 列布局 */
.knowledge-base-body {
  display: flex;
  flex-direction: column;
  flex: 1 1 auto;
  min-height: 0;
  width: 100%;
  max-width: 1560px;
  margin: 0 auto;
  padding: 1rem;
  gap: 1rem;
}

/* 统计区面板 */
.stats-panel {
  flex: 0 0 auto;
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  padding: 1.25rem;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
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
    grid-template-columns: repeat(4, minmax(0, 1fr));
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
  align-items: flex-end;
  justify-content: space-between;
  gap: 0.75rem;
  padding: 1.25rem;
  min-height: 100px;
}

.stat-main {
  display: flex;
  flex-direction: column;
  gap: 0.5rem;
}

.stat-label {
  font-size: 0.875rem;
  color: #6b7280;
  font-weight: 500;
}

.stat-value {
  font-size: 2rem;
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

.search-box {
  position: relative;
  width: 240px;
  flex: 0 0 auto;
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
  height: 2.25rem;
  padding: 0 2.25rem 0 2.5rem;
  font-size: 0.875rem;
  color: #374151;
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 0.5rem;
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
  gap: 0.25rem;
  padding: 0.25rem;
  background: #f3f4f6;
  border: 1px solid #e5e7eb;
  border-radius: 0.5rem;
}

.filter-tabs button {
  padding: 0.375rem 0.75rem;
  font-size: 0.875rem;
  font-weight: 500;
  color: #6b7280;
  background: transparent;
  border: none;
  border-radius: 0.375rem;
  cursor: pointer;
  transition: all 0.2s ease;
}

.filter-tabs button:hover {
  color: #374151;
}

.filter-tabs button.active {
  color: #111827;
  background: #ffffff;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.05);
}

.sort-select {
  height: 2.25rem;
  padding: 0 2rem 0 0.75rem;
  font-size: 0.875rem;
  color: #374151;
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 0.5rem;
  outline: none;
  cursor: pointer;
  appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%236b7280' stroke-width='2'%3E%3Cpath d='M6 9l6 6 6-6'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 0.625rem center;
  transition: all 0.2s ease;
}

.sort-select:focus {
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}

.view-toggle {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.25rem;
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 0.5rem;
}

.view-toggle button {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 2rem;
  height: 1.75rem;
  color: #9ca3af;
  background: transparent;
  border: none;
  border-radius: 0.375rem;
  cursor: pointer;
  transition: all 0.2s ease;
}

.view-toggle button:hover {
  color: #6b7280;
  background: #f3f4f6;
}

.view-toggle button.active {
  color: #111827;
  background: #f3f4f6;
}

/* ========== 网格视图 ========== */
.kb-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(320px, 1fr));
  gap: 1rem;
}

.kb-card-grid {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  border: 1px solid #e5e7eb;
  border-radius: 12px;
  background: #ffffff;
  transition: all 0.2s ease;
}

.kb-card-grid:hover {
  border-color: #d1d5db;
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.06);
}

.kb-card-grid :deep(.card-body) {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
}

.kb-card-header {
  display: flex;
  align-items: flex-start;
  gap: 0.75rem;
  padding: 1rem 1rem 0.5rem;
}

.kb-card-info {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  min-width: 0;
  flex: 1;
  padding-right: 4.5rem;
}

.kb-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 2.75rem;
  height: 2.75rem;
  flex-shrink: 0;
  border-radius: 0.5rem;
}

.kb-card-titles {
  min-width: 0;
  flex: 1;
}

.kb-card-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 1rem;
  font-weight: 600;
  color: #111827;
  line-height: 1.4;
}

.kb-card-desc {
  display: -webkit-box;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
  overflow: hidden;
  font-size: 0.8125rem;
  color: #6b7280;
  line-height: 1.5;
  margin: 0;
  word-break: break-word;
}

.kb-status-tag {
  position: absolute;
  top: 0;
  right: 0;
  z-index: 1;
  display: inline-flex;
  align-items: center;
  border-radius: 0 12px 0 12px !important;
  box-shadow: 0 2px 6px rgba(0, 0, 0, 0.12);
}

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
}

.kb-status-private {
  background-color: #7c3aed !important;
  border-color: #7c3aed !important;
}

.kb-status-public {
  background-color: #16a34a !important;
  border-color: #16a34a !important;
}

.kb-status-team {
  background-color: #2563eb !important;
  border-color: #2563eb !important;
}

.kb-meta-left {
  display: inline-flex;
  align-items: center;
  gap: 0.75rem;
  min-width: 0;
}

.kb-card-body {
  flex: 1 1 auto;
  display: flex;
  flex-direction: column;
  gap: 0.625rem;
  padding: 0.5rem 1rem 1rem;
}

.kb-card-body .kb-card-desc {
  margin: 0;
}

.kb-tag-list {
  display: flex;
  flex-wrap: wrap;
  gap: 0.5rem;
  min-height: 1.5rem;
}

.kb-card-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  margin-top: auto;
  padding-top: 0.75rem;
  border-top: 1px solid #f3f4f6;
}

.kb-meta-item {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  font-size: 0.75rem;
  color: #6b7280;
}

.kb-card-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  padding: 0.5rem 0.875rem;
  border-top: 1px solid #f3f4f6;
  background: #fafafa;
  border-radius: 0 0 12px 12px;
}

.kb-card-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.kb-enter-btn {
  height: 1.875rem;
  padding-top: 0;
  padding-bottom: 0;
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

/* ========== 标签胶囊（卡片标题下） ========== */
.kb-tag-list-inline {
  margin-top: 0.375rem;
  display: flex;
  flex-wrap: wrap;
  gap: 0.375rem;
  min-height: 0;
}

.kb-tag-chip {
  display: inline-flex;
  align-items: center;
  max-width: 8rem;
  padding: 0.0625rem 0.5rem;
  font-size: 0.6875rem;
  font-weight: 500;
  line-height: 1.4;
  color: #475569;
  background: #f1f5f9;
  border: 1px solid #e2e8f0;
  border-radius: 9999px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ========== 列表视图 ========== */
.kb-list {
  display: flex;
  flex-direction: column;
  gap: 0.75rem;
}

.kb-card-list {
  width: 100%;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  transition: all 0.2s ease;
  cursor: pointer;
}

.kb-card-list:hover {
  border-color: #d1d5db;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.05);
}

.kb-list-row {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  width: 100%;
  padding: 0.875rem 1rem;
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

.kb-list-meta {
  display: flex;
  align-items: center;
  gap: 1rem;
  flex-shrink: 0;
  min-width: 220px;
  color: #6b7280;
}

.kb-list-actions {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  flex-shrink: 0;
  margin-left: auto;
}

@media (max-width: 1024px) {
  .kb-list-row {
    flex-wrap: wrap;
  }

  .kb-list-info {
    width: calc(100% - 4rem);
  }

  .kb-list-meta {
    width: 100%;
    margin-left: 2.5rem;
    margin-top: 0.5rem;
  }

  .kb-list-actions {
    width: 100%;
    justify-content: flex-end;
    margin-left: 2.5rem;
    margin-top: 0.5rem;
  }
}

@media (min-width: 1025px) {
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
</style>