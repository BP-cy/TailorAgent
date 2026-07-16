<script setup lang="ts">
// 工具调用展示卡 —— 本地 @Tool 与外部 MCP 工具共用同一套 UI(靠 source 区分图标)。
// 一张卡聚合一次调用的 tool_call(名称/参数)与 tool_result(结果/状态);
// 默认折叠,点击头部展开查看参数与返回。
import { computed, ref } from 'vue'
import McpIcon from './icons/McpIcon.vue'
import ToolIcon from './icons/ToolIcon.vue'

const props = defineProps<{
  toolName: string
  source: 'local' | 'mcp'
  /** 模型给出的入参(原始 JSON 字符串) */
  args?: string
  /** running / success / error */
  status: string
  result?: string
  error?: string
}>()

const expanded = ref(false)

// 入参美化:能解析成 JSON 就缩进展示,否则原样
const prettyArgs = computed(() => {
  if (!props.args) return ''
  try {
    return JSON.stringify(JSON.parse(props.args), null, 2)
  } catch {
    return props.args
  }
})

const statusMeta = computed(() => {
  switch (props.status) {
    case 'success': return { label: '完成', cls: 'text-green-600', dot: 'bg-green-500' }
    case 'error':   return { label: '失败', cls: 'text-red-500', dot: 'bg-red-500' }
    default:        return { label: '调用中', cls: 'text-amber-600', dot: 'bg-amber-500' }
  }
})

const running = computed(() => props.status !== 'success' && props.status !== 'error')
</script>

<template>
  <div class="rounded-xl border border-outline-variant/60 bg-surface-variant/20 text-sm overflow-hidden">
    <!-- 头部:图标 + 工具名 + 状态 + 展开箭头 -->
    <button
      type="button"
      class="flex w-full items-center gap-2 px-3 py-2 text-left outline-none transition-colors hover:bg-surface-variant/40"
      @click="expanded = !expanded"
    >
      <!-- 来源图标:mcp 用网络图标,local 用工具图标 -->
      <McpIcon v-if="source === 'mcp'" class="w-4 h-4 text-on-surface-variant/70" />
      <ToolIcon v-else class="w-4 h-4 text-on-surface-variant/70" />

      <span class="font-medium text-on-surface truncate">{{ toolName }}</span>
      <span class="text-[11px] text-on-surface-variant/50 flex-shrink-0">{{ source === 'mcp' ? 'MCP' : '内置' }}</span>

      <!-- 状态徽标 -->
      <span class="ml-auto flex items-center gap-1 text-xs flex-shrink-0" :class="statusMeta.cls">
        <span
          v-if="running"
          class="material-symbols-outlined text-[14px] animate-spin"
        >progress_activity</span>
        <span v-else class="w-1.5 h-1.5 rounded-full" :class="statusMeta.dot" />
        {{ statusMeta.label }}
      </span>
      <span
        class="material-symbols-outlined text-[18px] text-on-surface-variant/40 transition-transform flex-shrink-0"
        :class="expanded ? 'rotate-180' : ''"
      >expand_more</span>
    </button>

    <!-- 详情:参数 + 结果/错误 -->
    <div v-if="expanded" class="px-3 pb-3 pt-1 space-y-2 border-t border-outline-variant/40">
      <div v-if="prettyArgs">
        <div class="text-[11px] text-on-surface-variant/50 mb-1">参数</div>
        <pre class="text-xs bg-black/[0.03] rounded-lg p-2 overflow-x-auto whitespace-pre-wrap break-all">{{ prettyArgs }}</pre>
      </div>
      <div v-if="error">
        <div class="text-[11px] text-red-500/70 mb-1">错误</div>
        <pre class="text-xs bg-red-50 text-red-600 rounded-lg p-2 overflow-x-auto whitespace-pre-wrap break-all">{{ error }}</pre>
      </div>
      <div v-else-if="result">
        <div class="text-[11px] text-on-surface-variant/50 mb-1">返回</div>
        <pre class="text-xs bg-black/[0.03] rounded-lg p-2 overflow-x-auto whitespace-pre-wrap break-all">{{ result }}</pre>
      </div>
    </div>
  </div>
</template>
