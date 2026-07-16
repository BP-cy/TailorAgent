<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useConfirm } from '@/composables/useConfirm'

const { state, onConfirm, onCancel } = useConfirm()

const inputEl = ref<HTMLInputElement | null>(null)

// 打开输入对话框时自动聚焦并选中
watch(
  () => state.visible,
  (v) => {
    if (v && state.isPrompt) {
      nextTick(() => {
        inputEl.value?.focus()
        inputEl.value?.select()
      })
    }
  },
)
</script>

<template>
  <Teleport to="body">
    <div
      v-if="state.visible"
      class="fixed inset-0 z-[9999] flex items-center justify-center"
      @click.self="onCancel"
    >
      <div class="absolute inset-0 bg-black/30"></div>
      <div class="relative bg-white rounded-xl shadow-lg px-6 py-5 w-80 flex flex-col gap-4">
        <p v-if="state.title" class="text-sm font-medium text-on-surface">{{ state.title }}</p>
        <p class="text-sm text-on-surface whitespace-pre-line">{{ state.message }}</p>

        <input
          v-if="state.isPrompt"
          ref="inputEl"
          v-model="state.inputValue"
          type="text"
          :placeholder="state.placeholder"
          class="h-9 w-full rounded-lg border border-outline-variant bg-white px-3 text-sm outline-none transition-colors focus:border-primary"
          @keyup.enter="onConfirm"
          @keyup.esc="onCancel"
        />

        <div class="flex justify-end gap-2">
          <button
            type="button"
            class="rounded-md border border-outline-variant bg-white px-4 py-1.5 text-sm text-on-surface-variant transition-colors hover:bg-surface-container-high"
            @click="onCancel"
          >
            {{ state.cancelText }}
          </button>
          <button
            type="button"
            class="rounded-md px-4 py-1.5 text-sm text-white transition-colors"
            :class="state.danger ? 'bg-red-500 hover:bg-red-600' : 'bg-primary hover:bg-primary-container'"
            @click="onConfirm"
          >
            {{ state.confirmText }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>
