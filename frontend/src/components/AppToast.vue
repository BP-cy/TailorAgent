<script setup lang="ts">
import { useToast, type ToastType } from '@/composables/useToast'

const { toasts } = useToast()

const typeClasses: Record<ToastType, string> = {
  success: 'bg-green-50/90 text-green-800 border-green-200',
  error: 'bg-red-50/90 text-red-800 border-red-200',
  warning: 'bg-amber-50/90 text-amber-800 border-amber-200',
  info: 'bg-surface-container-lowest/90 text-on-surface border-outline-variant/50',
}

const typeIcons: Record<ToastType, string> = {
  success: 'check_circle',
  error: 'error',
  warning: 'warning',
  info: 'info',
}
</script>

<template>
  <Teleport to="body">
    <div class="fixed top-6 left-1/2 -translate-x-1/2 z-[9999] flex flex-col items-center gap-3 pointer-events-none">
      <transition-group name="toast">
        <div
          v-for="toast in toasts"
          :key="toast.id"
          :class="[
            'pointer-events-auto px-5 py-3 rounded-xl shadow-lg text-sm font-medium flex items-center gap-2.5 max-w-[380px]',
            'border backdrop-blur-sm transition-all duration-300',
            toast.visible ? 'opacity-100 translate-y-0' : 'opacity-0 -translate-y-2',
            typeClasses[toast.type],
          ]"
        >
          <span class="material-symbols-outlined text-[18px]">{{ typeIcons[toast.type] }}</span>
          <span>{{ toast.message }}</span>
        </div>
      </transition-group>
    </div>
  </Teleport>
</template>

<style scoped>
.toast-enter-active,
.toast-leave-active {
  transition: all 0.3s ease;
}
.toast-enter-from {
  opacity: 0;
  transform: translateY(-12px);
}
.toast-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}
</style>
