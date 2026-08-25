<script setup lang="ts">
import { IconArrowRight, IconArrowLeft } from "@halo-dev/components";
import { computed } from "vue";

const props = defineProps<{
  page?: number;
  size?: number;
  total?: number;
  sizeOptions?: number[];
  pageLabel?: string;
  sizeLabel?: string;
}>();

const emit = defineEmits<{
  (e: "update:page", page: number): void;
  (e: "update:size", size: number): void;
}>();

const currentPage = computed(() => props.page || 1);
const currentSize = computed(() => props.size || 10);
const totalItems = computed(() => props.total || 0);
const totalPages = computed(() => Math.max(1, Math.ceil(totalItems.value / currentSize.value)));

function onPageChange(next: number) {
  if (next < 1 || next > totalPages.value) return;
  emit("update:page", next);
}

function onSizeChange(event: Event) {
  const target = event.target as HTMLSelectElement;
  emit("update:size", Number(target.value));
}
</script>

<template>
  <div class="pagination-bar">
    <div class="pagination-bar-left">
      <button
        type="button"
        class="pagination-bar-arrow"
        :disabled="currentPage <= 1"
        @click="onPageChange(currentPage - 1)"
      >
        <IconArrowLeft class="h-4 w-4" />
      </button>

      <span class="pagination-bar-text">
        {{ currentPage }} / {{ totalPages }} {{ pageLabel || "页" }}
      </span>

      <button
        type="button"
        class="pagination-bar-arrow"
        :disabled="currentPage >= totalPages"
        @click="onPageChange(currentPage + 1)"
      >
        <IconArrowRight class="h-4 w-4" />
      </button>
    </div>

    <div class="pagination-bar-size">
      <select
        :value="currentSize"
        class="pagination-bar-select"
        @change="onSizeChange"
      >
        <option v-for="opt in sizeOptions || [10, 20, 50, 100]" :key="opt" :value="opt">
          {{ opt }}
        </option>
      </select>
      <span class="pagination-bar-size-label">{{ sizeLabel || "条/页" }}</span>
    </div>
  </div>
</template>

<style scoped>
.pagination-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 1rem;
  padding: 0.5rem 0;
}

.pagination-bar-left {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.pagination-bar-arrow {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 2rem;
  height: 2rem;
  padding: 0;
  margin: 0;
  color: #4b5563;
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 0.375rem;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease, color 0.15s ease;
}

.pagination-bar-arrow:hover:not(:disabled) {
  background: #f3f4f6;
  border-color: #d1d5db;
  color: #111827;
}

.pagination-bar-arrow:disabled {
  color: #d1d5db;
  background: #f9fafb;
  border-color: #e5e7eb;
  cursor: not-allowed;
}

.pagination-bar-text {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 4.5rem;
  height: 2rem;
  padding: 0 0.75rem;
  font-size: 0.875rem;
  font-weight: 500;
  color: #374151;
  background: #f9fafb;
  border: 1px solid #e5e7eb;
  border-radius: 0.375rem;
  user-select: none;
}

.pagination-bar-size {
  display: flex;
  align-items: center;
  gap: 0.5rem;
  font-size: 0.875rem;
  color: #6b7280;
}

.pagination-bar-select {
  min-width: 4.5rem;
  height: 2rem;
  padding: 0 1.75rem 0 0.75rem;
  font-size: 0.875rem;
  color: #374151;
  background: #ffffff;
  border: 1px solid #e5e7eb;
  border-radius: 0.375rem;
  appearance: none;
  background-image: url("data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' width='16' height='16' viewBox='0 0 24 24' fill='none' stroke='%239ca3af' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'%3E%3Cpath d='m6 9 6 6 6-6'/%3E%3C/svg%3E");
  background-repeat: no-repeat;
  background-position: right 0.5rem center;
  background-size: 1rem;
}

.pagination-bar-select:focus {
  outline: none;
  border-color: #3b82f6;
  box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.15);
}

.pagination-bar-size-label {
  white-space: nowrap;
}
</style>
