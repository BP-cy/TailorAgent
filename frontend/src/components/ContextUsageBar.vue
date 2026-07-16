<script setup lang="ts">
import { computed } from 'vue'

/**
 * 上下文窗口占用占比条 —— 提示用户本会话已用多少上下文,便于判断何时压缩历史。
 *
 * used:  当前上下文占用 token(后端 contextTokens;未知时为 undefined → 显示「—」)
 * total: 所选模型的上下文长度(contextLength;无效时不显示占比)
 */
const props = defineProps<{ used?: number; total?: number }>()

/** 是否有有效数据可展示占比 */
const hasData = computed(
  () => props.used != null && props.total != null && props.total > 0,
)

/** 占比 0~100(取整);无数据时为 0 */
const percent = computed(() => {
  if (!hasData.value) return 0
  return Math.min(100, Math.round((props.used! / props.total!) * 100))
})

/** 进度条颜色:<70% 常态、70~90% 警示、>90% 危险 */
const barClass = computed(() => {
  if (percent.value >= 90) return 'bg-red-500'
  if (percent.value >= 70) return 'bg-amber-400'
  return 'bg-primary'
})

/** token 数缩写(1234 → 1.2k) */
function fmt(n?: number): string {
  if (n == null) return '—'
  if (n < 1000) return String(n)
  return (n / 1000).toFixed(n < 10000 ? 1 : 0) + 'k'
}
</script>

<template>
  <div class="flex items-center gap-2 text-[11px] text-on-surface-variant/70" title="本会话上下文窗口占用">
    <span class="material-symbols-outlined text-[14px] flex-shrink-0">data_usage</span>
    <div class="h-1 w-20 rounded-full bg-outline-variant/40 overflow-hidden flex-shrink-0">
      <div
        class="h-full rounded-full transition-all duration-300"
        :class="barClass"
        :style="{ width: percent + '%' }"
      ></div>
    </div>
    <span v-if="hasData" class="tabular-nums whitespace-nowrap">
      {{ fmt(used) }} / {{ fmt(total) }}（{{ percent }}%）
    </span>
    <span v-else class="whitespace-nowrap">上下文 —</span>
  </div>
</template>
