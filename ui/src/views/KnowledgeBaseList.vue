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
import { axiosInstance } from "@halo-dev/api-client";
import { utils } from "@halo-dev/ui-shared";
import UserSelect from "../components/UserSelect.vue";
import TagInput from "../components/TagInput.vue";
import PaginationBar from "../components/PaginationBar.vue";

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
    description?: string;
    logo?: string;
    tags?: string[];
    publicVisible?: boolean;
    members?: string[];
    priority?: number;
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
const sortBy = ref<"updateTime" | "createTime" | "name" | "docCount">("updateTime");
const stats = ref<Stats | null>(null);
const statsLoading = ref(false);

const modalVisible = ref(false);
const saving = ref(false);
const editing = ref<KnowledgeBase | null>(null);
const form = reactive({
  name: "",
  displayName: "",
  description: "",
  logo: "",
  tags: [] as string[],
  members: [] as string[],
  publicVisible: false,
});

// 批量选择
const selectedNames = ref<string[]>([]);
const cancelToken = ref(false);
// logo 加载失败兜底
const logoErrors = ref<Set<string>>(new Set());

const filteredCount = computed(() => {
  if (publicVisible.value === undefined) return total.value;
  return stats.value
    ? publicVisible.value
      ? stats.value.publicCount
      : stats.value.privateCount
    : 0;
});

const themePresets = [
  { bg: "bg-blue-50", text: "text-blue-600" },
  { bg: "bg-emerald-50", text: "text-emerald-600" },
  { bg: "bg-indigo-50", text: "text-indigo-600" },
  { bg: "bg-orange-50", text: "text-orange-600" },
  { bg: "bg-purple-50", text: "text-purple-600" },
  { bg: "bg-cyan-50", text: "text-cyan-600" },
  { bg: "bg-rose-50", text: "text-rose-600" },
];

function getTheme(kb: KnowledgeBase) {
  const hash = kb.metadata.name.split("").reduce((acc, c) => acc + c.charCodeAt(0), 0);
  return themePresets[hash % themePresets.length];
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
        keyword: keyword.value || undefined,
        publicVisible:
          publicVisible.value === undefined ? undefined : publicVisible.value,
      },
    });
    kbs.value = sortItems(data.items);
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

function sortItems(items: KnowledgeBase[]) {
  const list = [...items];
  switch (sortBy.value) {
    case "name":
      list.sort((a, b) => a.spec.displayName.localeCompare(b.spec.displayName));
      break;
    case "docCount":
      list.sort((a, b) => (b.status?.docCount || 0) - (a.status?.docCount || 0));
      break;
    case "createTime":
      list.sort(
        (a, b) =>
          new Date(b.metadata.creationTimestamp || 0).getTime() -
          new Date(a.metadata.creationTimestamp || 0).getTime()
      );
      break;
    case "updateTime":
    default:
      list.sort(
        (a, b) =>
          new Date(b.status?.lastPublishTime || b.metadata.creationTimestamp || 0).getTime() -
          new Date(a.status?.lastPublishTime || a.metadata.creationTimestamp || 0).getTime()
      );
      break;
  }
  return list;
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
  form.description = "";
  form.logo = "";
  form.tags = [];
  form.members = [];
  form.publicVisible = false;
  modalVisible.value = true;
}

function openEdit(kb: KnowledgeBase) {
  editing.value = kb;
  form.name = kb.metadata.name;
  form.displayName = kb.spec.displayName;
  form.description = kb.spec.description || "";
  form.logo = kb.spec.logo || "";
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
      description: form.description || undefined,
      logo: form.logo || undefined,
      tags: form.tags.filter(Boolean),
      members: form.members,
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

function handleLogoError(name: string) {
  logoErrors.value.add(name);
}

function showLogo(kb: KnowledgeBase) {
  return !!kb.spec.logo && !logoErrors.value.has(kb.metadata.name);
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
              <div
                v-if="!showLogo(kb)"
                class="kb-avatar"
                :class="[getTheme(kb).bg, getTheme(kb).text]"
              >
                <IconBookRead class="h-6 w-6" />
              </div>
              <img
                v-else
                :src="kb.spec.logo"
                :alt="kb.spec.displayName"
                class="kb-avatar kb-avatar-img"
                loading="lazy"
                @error="handleLogoError(kb.metadata.name)"
              />
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
              v-if="kb.spec.publicVisible"
              type="success"
              size="sm"
              class="kb-status-tag kb-status-public"
            >
              <template #leftIcon>
                <IconEye class="h-3 w-3" />
              </template>
              公开
            </VTag>
            <VTag v-else type="secondary" size="sm" class="kb-status-tag kb-status-private">
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
            <span class="kb-meta-item">
              <IconPages class="h-3.5 w-3.5" />
              {{ kb.status?.docCount ?? 0 }} 篇文档
            </span>
            <span
              class="kb-meta-item"
              :title="kb.status?.lastPublishTime ? '更新于 ' + formatTime(kb.status.lastPublishTime) : '创建于 ' + formatTime(kb.metadata.creationTimestamp)"
            >
              更新于
              {{ formatRelativeTime(kb.status?.lastPublishTime || kb.metadata.creationTimestamp) }}
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
            <div
              v-if="!showLogo(kb)"
              class="kb-avatar"
              :class="[getTheme(kb).bg, getTheme(kb).text]"
            >
              <IconBookRead class="h-5 w-5" />
            </div>
            <img
              v-else
              :src="kb.spec.logo"
              :alt="kb.spec.displayName"
              class="kb-avatar kb-avatar-img"
              loading="lazy"
              @click.stop
              @error="handleLogoError(kb.metadata.name)"
            />
            <div class="kb-list-info">
              <div class="kb-list-title-row">
                <h3 class="kb-card-title">{{ kb.spec.displayName }}</h3>
                <VTag v-if="kb.spec.publicVisible" type="success" size="sm">公开</VTag>
                <VTag v-else type="warning" size="sm">私有</VTag>
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

    <div v-if="total > size" class="kb-pagination-section">
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
          v-model="form.description"
          label="描述"
          type="textarea"
          name="description"
          placeholder="一句话介绍这个知识库"
        />
        <FormKit
          v-model="form.logo"
          label="图标地址（URL，可选）"
          type="text"
          name="logo"
          placeholder="https://example.com/logo.png"
          help="填写后可展示为知识库卡片图标"
        />
        <TagInput
          v-model="form.tags"
          label="标签"
          placeholder="输入后按回车添加"
        />
        <div class="mb-4">
          <label class="formkit-label block text-sm font-medium text-gray-700">
            成员（私有知识库可访问者）
          </label>
          <p class="formkit-help mt-1 text-xs text-gray-500">
            勾选可访问该私有知识库的用户，公开知识库无需设置
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
  grid-template-columns: repeat(1, minmax(0, 1fr));
  gap: 1rem;
}

@media (min-width: 768px) {
  .kb-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (min-width: 1024px) {
  .kb-grid {
    grid-template-columns: repeat(4, minmax(0, 1fr));
  }
}

.kb-card-grid {
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

.kb-avatar-img {
  object-fit: cover;
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
  flex-shrink: 0;
  margin-top: 0.125rem;
  display: inline-flex;
  align-items: center;
  border-radius: 9999px;
}

.kb-status-public {
  background-color: rgba(220, 252, 231, 0.9);
  color: #15803d;
  border: 1px solid #bbf7d0;
}

.kb-status-private {
  background-color: rgba(224, 231, 255, 0.9);
  color: #4338ca;
  border: 1px solid #c7d2fe;
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
</style>