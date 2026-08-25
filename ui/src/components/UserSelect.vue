<script setup lang="ts">
import { consoleApiClient, type User } from "@halo-dev/api-client";
import { IconClose, IconSearch } from "@halo-dev/components";
import { computed, onMounted, onUnmounted, ref, watch } from "vue";

interface ListedUser {
  user: User;
}

const props = defineProps<{
  modelValue?: string[];
  placeholder?: string;
}>();

const emit = defineEmits<{
  (e: "update:modelValue", value: string[]): void;
}>();

const users = ref<User[]>([]);
const loading = ref(false);
const keyword = ref("");
const open = ref(false);
const triggerRef = ref<HTMLElement | null>(null);
const dropdownRef = ref<HTMLElement | null>(null);

function handleClickOutside(event: MouseEvent) {
  const target = event.target as Node;
  const hitTrigger = triggerRef.value?.contains(target) ?? false;
  const hitDropdown = dropdownRef.value?.contains(target) ?? false;
  if (open.value && !hitTrigger && !hitDropdown) {
    open.value = false;
  }
}

onMounted(() => document.addEventListener("click", handleClickOutside));
onUnmounted(() => document.removeEventListener("click", handleClickOutside));

const selectedSet = computed(() => new Set(props.modelValue || []));

const filteredUsers = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  const list = users.value.filter((u) => u && u.metadata);
  if (!q) return list;
  return list.filter(
    (u) =>
      u.metadata.name.toLowerCase().includes(q) ||
      (u.spec.displayName || "").toLowerCase().includes(q) ||
      (u.spec.email || "").toLowerCase().includes(q)
  );
});

async function loadUsers() {
  loading.value = true;
  try {
    const { data } = await consoleApiClient.user.listUsers({
      page: 1,
      size: 1000,
      keyword: keyword.value || undefined,
    });
    // listUsers returns UserEndpointListedUserList: { items: Array<{ user: User }> }
    users.value = (data.items || [])
      .map((item) => item.user)
      .filter(
        (u): u is User =>
          Boolean(u && u.metadata) && u.spec?.disabled !== true
      );
  } catch {
    users.value = [];
  } finally {
    loading.value = false;
  }
}

function toggleOpen() {
  open.value = !open.value;
  if (open.value) {
    keyword.value = "";
    loadUsers();
  }
}

function isSelected(name: string) {
  return selectedSet.value.has(name);
}

function toggleUser(name: string) {
  const next = new Set(selectedSet.value);
  if (next.has(name)) {
    next.delete(name);
  } else {
    next.add(name);
  }
  emit("update:modelValue", Array.from(next));
}

function removeUser(name: string) {
  const next = new Set(selectedSet.value);
  next.delete(name);
  emit("update:modelValue", Array.from(next));
}

function displayName(name: string) {
  const user = users.value.find((u) => u.metadata.name === name);
  if (user?.spec.displayName && user.spec.displayName !== name) {
    return `${user.spec.displayName} (${name})`;
  }
  return name;
}

watch(open, (val) => {
  if (val) keyword.value = "";
});

loadUsers();
</script>

<template>
  <div class="user-select">
    <div ref="triggerRef" class="user-select-trigger" @click="toggleOpen">
      <div class="user-select-tags">
        <span v-if="!modelValue?.length" class="user-select-placeholder">
          {{ placeholder || '请选择用户' }}
        </span>
        <span
          v-for="name in modelValue"
          :key="name"
          class="user-select-tag"
          @click.stop
        >
          {{ displayName(name) }}
          <button class="user-select-tag-remove" @click.stop="removeUser(name)">
            <IconClose class="h-3 w-3" />
          </button>
        </span>
      </div>
      <IconSearch class="user-select-arrow" />
    </div>

    <Transition name="dropdown">
      <div v-show="open" ref="dropdownRef" class="user-select-dropdown">
        <div class="user-select-search">
          <IconSearch class="h-4 w-4 text-gray-400" />
          <input
            v-model="keyword"
            type="text"
            placeholder="搜索用户名 / 昵称 / 邮箱"
            @click.stop
          />
        </div>
        <div v-if="loading" class="user-select-status">加载中...</div>
        <div v-else-if="!filteredUsers.length" class="user-select-status">
          暂无匹配用户
        </div>
        <div v-else class="user-select-options">
          <label
            v-for="user in filteredUsers"
            :key="user.metadata.name"
            class="user-select-option"
            :class="{ selected: isSelected(user.metadata.name) }"
          >
            <input
              type="checkbox"
              :checked="isSelected(user.metadata.name)"
              @change="toggleUser(user.metadata.name)"
              @click.stop
            />
            <span class="user-select-avatar">
              <img
                v-if="user.spec.avatar"
                :src="user.spec.avatar"
                alt=""
              />
              <span v-else class="user-select-avatar-default">
                {{ (user.spec.displayName || user.metadata.name).slice(0, 1).toUpperCase() }}
              </span>
            </span>
            <span class="user-select-info">
              <span class="user-select-name">
                {{ user.spec.displayName || user.metadata.name }}
              </span>
              <span class="user-select-username">@{{ user.metadata.name }}</span>
            </span>
          </label>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.user-select {
  position: relative;
  width: 100%;
}

.user-select-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 0.5rem;
  min-height: 2.5rem;
  padding: 0.5rem 0.75rem;
  border: 1px solid #e5e7eb;
  border-radius: 0.5rem;
  background: #ffffff;
  cursor: pointer;
  transition: all 0.2s ease;
}

.user-select-trigger:hover {
  border-color: #d1d5db;
}

.user-select-trigger:focus-within,
.user-select-open .user-select-trigger {
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}

.user-select-tags {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.375rem;
  flex: 1;
}

.user-select-placeholder {
  color: #9ca3af;
  font-size: 0.875rem;
}

.user-select-tag {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.125rem 0.5rem;
  font-size: 0.75rem;
  color: #374151;
  background: #f3f4f6;
  border: 1px solid #e5e7eb;
  border-radius: 0.375rem;
}

.user-select-tag-remove {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0;
  margin: 0;
  border: none;
  background: transparent;
  color: #9ca3af;
  cursor: pointer;
}

.user-select-tag-remove:hover {
  color: #4b5563;
}

.user-select-arrow {
  width: 1rem;
  height: 1rem;
  color: #9ca3af;
  flex-shrink: 0;
}

.user-select-dropdown {
  position: absolute;
  z-index: 50;
  top: calc(100% + 0.25rem);
  left: 0;
  right: 0;
  display: flex;
  flex-direction: column;
  max-height: 320px;
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 0.5rem;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);
  overflow: hidden;
}

.user-select-search {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding: 0.5rem 0.75rem;
  border-bottom: 1px solid #f3f4f6;
}

.user-select-search input {
  flex: 1;
  border: none;
  outline: none;
  font-size: 0.875rem;
  color: #374151;
  background: transparent;
}

.user-select-search input::placeholder {
  color: #9ca3af;
}

.user-select-status {
  padding: 1rem;
  text-align: center;
  font-size: 0.875rem;
  color: #6b7280;
}

.user-select-options {
  flex: 1;
  overflow-y: auto;
  padding: 0.25rem;
}

.user-select-option {
  display: flex;
  align-items: center;
  gap: 0.625rem;
  padding: 0.5rem 0.625rem;
  border-radius: 0.375rem;
  cursor: pointer;
  transition: background 0.15s ease;
}

.user-select-option:hover,
.user-select-option.selected {
  background: #f3f4f6;
}

.user-select-option input[type="checkbox"] {
  width: 1rem;
  height: 1rem;
  accent-color: #3b82f6;
  cursor: pointer;
}

.user-select-avatar {
  width: 1.75rem;
  height: 1.75rem;
  border-radius: 9999px;
  overflow: hidden;
  flex-shrink: 0;
  background: #f3f4f6;
  display: flex;
  align-items: center;
  justify-content: center;
}

.user-select-avatar img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.user-select-avatar-default {
  font-size: 0.75rem;
  font-weight: 600;
  color: #6b7280;
}

.user-select-info {
  display: flex;
  flex-direction: column;
  min-width: 0;
  flex: 1;
}

.user-select-name {
  font-size: 0.875rem;
  color: #111827;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-select-username {
  font-size: 0.75rem;
  color: #9ca3af;
}

.dropdown-enter-active,
.dropdown-leave-active {
  transition: opacity 0.15s ease, transform 0.15s ease;
}

.dropdown-enter-from,
.dropdown-leave-to {
  opacity: 0;
  transform: translateY(-4px);
}
</style>
