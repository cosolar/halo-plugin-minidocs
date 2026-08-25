<script setup lang="ts">
import { IconClose } from "@halo-dev/components";
import { ref } from "vue";

const props = defineProps<{
  modelValue: string[];
  label?: string;
  placeholder?: string;
}>();

const emit = defineEmits<{
  (e: "update:modelValue", value: string[]): void;
}>();

const inputValue = ref("");

function normalizeValues(raw: string): string[] {
  return raw
    .replace(/\n/g, ",")
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

function addTag() {
  const values = normalizeValues(inputValue.value);
  if (!values.length) return;
  const next = new Set(props.modelValue);
  values.forEach((v) => next.add(v));
  emit("update:modelValue", Array.from(next));
  inputValue.value = "";
}

function removeTag(tag: string) {
  emit(
    "update:modelValue",
    props.modelValue.filter((item) => item !== tag)
  );
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === "Enter") {
    event.preventDefault();
    addTag();
  }
}

function onPaste(event: ClipboardEvent) {
  const text = event.clipboardData?.getData("text") || "";
  const values = normalizeValues(text);
  if (values.length) {
    event.preventDefault();
    const next = new Set(props.modelValue);
    values.forEach((v) => next.add(v));
    emit("update:modelValue", Array.from(next));
    inputValue.value = "";
  }
}
</script>

<template>
  <div class="tag-input-wrapper">
    <label v-if="label" class="tag-input-label">{{ label }}</label>
    <p v-if="$slots.help || placeholder" class="tag-input-help">
      <slot name="help">输入后按回车添加，多个标签可用逗号分隔</slot>
    </p>
    <div class="tag-input-box">
      <span v-for="tag in modelValue" :key="tag" class="tag-input-chip">
        {{ tag }}
        <button
          type="button"
          class="tag-input-remove"
          @click.stop="removeTag(tag)"
        >
          <IconClose class="h-3 w-3" />
        </button>
      </span>
      <input
        v-model="inputValue"
        type="text"
        class="tag-input-field"
        :placeholder="modelValue.length ? '' : (placeholder || '请输入标签')"
        @keydown="onKeydown"
        @paste="onPaste"
        @blur="addTag"
      />
    </div>
  </div>
</template>

<style scoped>
.tag-input-wrapper {
  margin-bottom: 1rem;
}

.tag-input-label {
  display: block;
  margin-bottom: 0.25rem;
  font-size: 0.875rem;
  font-weight: 500;
  color: #374151;
}

.tag-input-help {
  margin: 0 0 0.5rem 0;
  font-size: 0.75rem;
  color: #6b7280;
  line-height: 1.25;
}

.tag-input-box {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
  min-height: 2.5rem;
  padding: 0.375rem 0.75rem;
  border: 1px solid #e5e7eb;
  border-radius: 0.375rem;
  background: #ffffff;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.tag-input-box:focus-within {
  border-color: #3b82f6;
  box-shadow: 0 0 0 2px rgba(59, 130, 246, 0.15);
}

.tag-input-chip {
  display: inline-flex;
  align-items: center;
  gap: 0.25rem;
  padding: 0.25rem 0.5rem;
  font-size: 0.75rem;
  font-weight: 500;
  color: #1f2937;
  background: #f3f4f6;
  border: 1px solid #e5e7eb;
  border-radius: 0.25rem;
  line-height: 1;
}

.tag-input-remove {
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

.tag-input-remove:hover {
  color: #ef4444;
}

.tag-input-field {
  flex: 1 1 auto;
  min-width: 6rem;
  padding: 0.25rem 0;
  border: none;
  outline: none;
  font-size: 0.875rem;
  color: #374151;
  background: transparent;
}

.tag-input-field::placeholder {
  color: #9ca3af;
}
</style>
