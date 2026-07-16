<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useToast } from '@/composables/useToast'
import MarkdownMessage from './MarkdownMessage.vue'
import ToolCallCard from './ToolCallCard.vue'
import ThinkingCard from './ThinkingCard.vue'
import ContextUsageBar from './ContextUsageBar.vue'
import {
  sendChatStream,
  cancelChat,
  compactSession,
  fetchConfig,
  fetchSessionEvents,
  fetchContextUsage,
  selectWorkingDir,
  newWorkingDir,
  type ChatEvent,
  type ToolCallPayload,
  type ToolResultPayload,
  type AppConfig,
} from '../api'
import { useChatRunStore, type RunKey } from '../stores/chatRun'

const props = defineProps<{ configRefresh?: number; sessionId?: number | null }>()
const emit = defineEmits<{
  /** 新建会话成功,把新会话 id 抛给父级以更新选中态与列表 */
  (e: 'session-created', id: number): void
  /** 本轮结束,会话更新时间变化,通知父级刷新列表排序 */
  (e: 'session-updated'): void
}>()

const run = useChatRunStore()
const input = ref('')
// 当前视图对应的缓冲键:真实 sessionId,或新建任务的占位 'new'
const activeKey = computed<RunKey>(() => props.sessionId ?? 'new')
// 整条会话事件流(按 activeKey 取 store 缓冲);user 渲染在右、其余在左
const events = computed<ChatEvent[]>(() => run.bufferFor(activeKey.value))
// 仅当「当前会话」有 running 轮次时才锁本会话;别的会话/新建任务可并发开新轮次
const sending = computed(() => run.isRunning(activeKey.value))
// 当前会话本轮是否已开始流式接收(任一 reasoning/text/tool 增量到达)——控制「思考中...」占位
const started = computed(() => run.isStarted(activeKey.value))
const config = ref<AppConfig>({
  availableChatModels: [],
  availableOCRModels: [],
  embeddingModel: { baseUrl: '', modelName: '', apiKey: '', dimensions: null, batchSize: 10 },
})
const { showToast } = useToast()

const textareaRef = ref<HTMLTextAreaElement | null>(null)
const listRef = ref<HTMLDivElement | null>(null)
const selectedModelIndex = ref(0)
// 工作区下拉菜单开合
const showWsMenu = ref(false)
// 手动压缩上下文进行中(同步等待后端生成摘要)
const compacting = ref(false)

// 上下文占比:分子取本会话最新 contextTokens(后端推送/回读),分母取当前所选模型的上下文长度
// (随下拉切换模型实时变化)。任一缺失时占比条显示「—」。
const usedTokens = computed<number | undefined>(() => run.getContextTokens(activeKey.value))
const totalTokens = computed<number | undefined>(
  () => config.value.availableChatModels[selectedModelIndex.value]?.contextLength,
)

/** 渲染项:文本气泡 或 工具卡(由 tool_call + tool_result 按 callId 合并而成) */
interface RenderItem {
  key: string
  kind: 'text' | 'tool' | 'reasoning'
  role?: string
  content?: string
  /** reasoning 项专用:true=正在流式思考中(头部显示「正在思考中」) */
  thinking?: boolean
  callId?: string
  toolName?: string
  source?: 'local' | 'mcp'
  args?: string
  status?: string
  result?: string
  error?: string
}

/** 安全解析事件 payload(JSON 字符串) */
function parsePayload<T>(ev: ChatEvent): T | null {
  if (!ev.payload) return null
  try {
    return JSON.parse(ev.payload) as T
  } catch {
    return null
  }
}

/**
 * 把扁平事件流折叠成渲染项:text 直接成气泡;tool_call 起一张卡,
 * 同 callId 的 tool_result 把结果/状态并入同一张卡。流式追加与 DB 重放走同一逻辑。
 */
const renderItems = computed<RenderItem[]>(() => {
  const items: RenderItem[] = []
  const cardByCall = new Map<string, RenderItem>()
  events.value.forEach((ev, i) => {
    if (ev.type === 'tool_call') {
      const p = parsePayload<ToolCallPayload>(ev)
      if (!p) return
      const item: RenderItem = {
        key: `call-${p.callId}`,
        kind: 'tool',
        callId: p.callId,
        toolName: p.toolName,
        source: p.source,
        args: p.args,
        status: ev.status || 'running',
      }
      cardByCall.set(p.callId, item)
      items.push(item)
    } else if (ev.type === 'tool_result') {
      const p = parsePayload<ToolResultPayload>(ev)
      if (!p) return
      const card = cardByCall.get(p.callId)
      if (card) {
        card.status = p.status
        card.result = p.result
        card.error = p.error
      } else {
        // 理论上 result 必在 call 之后;兜底单独成卡
        items.push({
          key: `result-${p.callId}`, kind: 'tool', callId: p.callId,
          toolName: '工具', source: 'local', status: p.status, result: p.result, error: p.error,
        })
      }
    } else if (ev.type === 'reasoning') {
      // 思考内容:折叠卡片,渲染在左侧;streaming 标记决定「正在思考中 / 思考已完成」
      items.push({
        key: ev.id != null ? `ev-${ev.id}` : `reason-${i}`,
        kind: 'reasoning',
        role: ev.role,
        content: ev.content || '',
        thinking: ev.streaming === true,
      })
    } else {
      // text 及其它:按文本气泡渲染
      items.push({
        key: ev.id != null ? `ev-${ev.id}` : `local-${i}`,
        kind: 'text',
        role: ev.role,
        content: ev.content || '',
      })
    }
  })
  return items
})

// 自动高度：随内容增减，限制在 3~8 行区间
function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  const min = 72 // ≈3 行
  const max = 192 // ≈8 行
  el.style.height = Math.min(max, Math.max(min, el.scrollHeight)) + 'px'
}

function onInput() {
  autoResize()
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    send()
  }
}

function scrollToBottom() {
  nextTick(() => {
    const el = listRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

async function loadConfig() {
  try {
    config.value = await fetchConfig()
  } catch (e) {
    showToast('加载配置失败: ' + (e as Error).message, 'warning')
  }
}

// 切换会话:确保该会话事件流已载入 store 缓冲;
// 新建任务('new')或正在跑/已载入的会话不重复拉取(以免覆盖在途的流式内容)
async function loadSession(id?: number | null) {
  const key: RunKey = id ?? 'new'
  // 新建任务:'new' 缓冲可能正有流在跑,绝不清空,仅滚动到底
  if (key === 'new') {
    scrollToBottom()
    return
  }
  // 正在跑(在途流式内容)或已从 DB 载入过 → 直接复用 store 缓冲
  if (run.isRunning(key) || run.isLoaded(key)) {
    scrollToBottom()
    return
  }
  try {
    run.setBuffer(key, await fetchSessionEvents(id!))
    run.setLoaded(key, true)
    scrollToBottom()
    // 回读该会话最新上下文占用(失败静默,占比条退化为「—」)
    if (run.getContextTokens(key) == null) {
      fetchContextUsage(id!)
        .then((n) => { if (n != null) run.setContextTokens(key, n) })
        .catch(() => { /* 忽略:占比条显示「—」 */ })
    }
  } catch (e) {
    showToast('载入会话失败: ' + (e as Error).message, 'warning')
  }
}

onMounted(loadConfig)
watch(() => props.configRefresh, loadConfig)
watch(() => props.sessionId, (id) => loadSession(id), { immediate: true })

// 打开指定文件夹作为工作区(原生选择器)
async function onSelectWorkingDir() {
  showWsMenu.value = false
  try {
    config.value = await selectWorkingDir()
  } catch (e) {
    showToast('选择工作目录失败: ' + (e as Error).message, 'warning')
  }
}

// 新建工作区(在 workspace 容器下创建 ws-日期-时间戳 目录)
async function onNewWorkingDir() {
  showWsMenu.value = false
  try {
    config.value = await newWorkingDir()
    showToast('已新建工作区', 'success')
  } catch (e) {
    showToast('新建工作区失败: ' + (e as Error).message, 'warning')
  }
}

async function send() {
  const text = input.value.trim()
  // 只看「当前会话」是否在跑:别的会话有 running 轮次不影响这里
  if (!text || run.isRunning(activeKey.value)) return

  const models = config.value.availableChatModels
  if (models.length === 0) {
    showToast('请先在设置中添加对话模型', 'warning')
    return
  }

  const isNew = props.sessionId == null
  // 快照本轮的缓冲键与缓冲数组:即便用户随后切走,在途的流仍写回这条会话自己的缓冲(不串台)。
  // runKey 用 let:新建任务在 onStart 拿到真实 id 后由 'new' 改写为 realId;
  // buf 的数组引用在 rekey 后保持不变(store 直接搬同一个数组),故无需重新获取。
  let runKey: RunKey = activeKey.value
  const buf = run.bufferFor(runKey)

  // 本轮的 AbortController:用户主动停止时 abort,立即断开客户端连接(服务端取消另走 cancelChat)
  const controller = new AbortController()
  run.setController(runKey, controller)

  // 乐观插入用户消息(立即显示;DB 为准,下次载入以后端为准)
  buf.push({ role: 'user', type: 'text', content: text })
  input.value = ''
  run.setRunning(runKey, true)
  run.setStarted(runKey, false)
  nextTick(autoResize)
  scrollToBottom()

  // 取消/出错的统一收尾(去重:cancelled 事件与 abort 触发的 onError 可能先后到达)
  let finalized = false
  const finalizeStopped = () => {
    if (finalized) return
    finalized = true
    finishReasoning()
    buf.push({ role: 'assistant', type: 'text', content: '⏹ 已停止本轮任务' })
    emit('session-updated')
    scrollToBottom()
  }

  // 本轮流式累积游标:思考 / 正文各维护一个增量事件,delta 到达时追加到同一条
  let reasoningIdx = -1
  let textIdx = -1
  // 思考阶段结束(出现正文 / 工具调用 / 收尾)→ 把当前思考卡置为「思考已完成」并归零游标。
  // 归零是关键:工具返回后的下一段思考会另起一条 reasoning 事件,从而独立成卡、
  // 按到达顺序排在中间那批 tool_call/tool_result 之后,而非全挤进最初那张卡。
  const finishReasoning = () => {
    if (reasoningIdx !== -1 && buf[reasoningIdx]) {
      buf[reasoningIdx].streaming = false
    }
    reasoningIdx = -1
  }

  try {
    // reasoning / text 均为逐块增量:首块新建事件,后续块追加到同一条;工具事件穿插其间各自成卡
    await sendChatStream(
      {
        sessionId: props.sessionId ?? null,
        content: text,
        modelIndex: selectedModelIndex.value,
      },
      {
        onStart: (d) => {
          // 新建任务拿到真实 id:把 'new' 占位缓冲/运行态迁移到真实 id,后续写入与视图无缝衔接;
          // 此时 isRunning(realId) 已为 true,父级回填 sessionId 触发的 loadSession 会被运行态短路,不重复拉取
          if (isNew) {
            run.rekeyNewToId(d.sessionId)
            runKey = d.sessionId
            emit('session-created', d.sessionId)
          }
          // 记录本轮 turnId,供「停止」按钮调用取消接口精确定位
          run.setTurnId(runKey, d.turnId)
        },
        onToolCall: (d) => {
          run.setStarted(runKey, true)
          finishReasoning()
          buf.push({ role: 'tool', type: 'tool_call', payload: JSON.stringify(d), status: 'running' })
          textIdx = -1 // 工具调用后若再有正文,另起一条气泡
          scrollToBottom()
        },
        onToolResult: (d) => {
          buf.push({ role: 'tool', type: 'tool_result', payload: JSON.stringify(d), status: d.status })
          scrollToBottom()
        },
        onReasoning: (d) => {
          run.setStarted(runKey, true)
          if (reasoningIdx === -1) {
            buf.push({ role: 'assistant', type: 'reasoning', content: d.content, streaming: true })
            reasoningIdx = buf.length - 1
          } else {
            buf[reasoningIdx].content = (buf[reasoningIdx].content || '') + d.content
          }
          scrollToBottom()
        },
        onText: (d) => {
          run.setStarted(runKey, true)
          finishReasoning() // 正文开始 → 思考阶段结束
          if (textIdx === -1) {
            buf.push({ role: 'assistant', type: 'text', content: d.content })
            textIdx = buf.length - 1
          } else {
            buf[textIdx].content = (buf[textIdx].content || '') + d.content
          }
          scrollToBottom()
        },
        onDone: (d) => {
          finishReasoning()
          // 刷新本会话上下文占用(后端已含本轮投影 delta)
          if (d.contextTokens != null) run.setContextTokens(runKey, d.contextTokens)
          emit('session-updated')
          scrollToBottom()
        },
        onCancelled: () => {
          // 服务端确认本轮已取消
          finalizeStopped()
        },
        onError: (msg) => {
          finishReasoning()
          // 客户端 abort 触发的 onError('请求已取消') 视作停止,避免显示「错误：」
          if (msg === '请求已取消') {
            finalizeStopped()
            return
          }
          // 后端已将本轮标记为 error;此处仅作即时提示
          buf.push({ role: 'assistant', type: 'text', content: `错误：${msg}` })
          scrollToBottom()
        },
      },
      controller.signal,
    )
  } finally {
    run.setRunning(runKey, false)
    run.setController(runKey, null)
  }
}

/** 主动停止当前会话正在运行的轮次:服务端取消(强杀工具进程 + 断流)+ 客户端断连兜底 */
async function stop() {
  const key = activeKey.value
  if (!run.isRunning(key)) return
  const turnId = run.getTurnId(key)
  if (turnId != null) {
    await cancelChat(turnId)
  }
  run.getController(key)?.abort()
}

/**
 * 手动压缩当前会话上下文:把较早对话压成摘要,降低后续输入 token。
 * 同步等待后端用所选模型生成摘要;成功后刷新占比条。原始对话仍完整展示,仅模型上下文被替换。
 */
async function onCompact() {
  if (props.sessionId == null) {
    showToast('请先开始对话再压缩', 'info')
    return
  }
  if (compacting.value || sending.value) return
  if (config.value.availableChatModels.length === 0) {
    showToast('请先在设置中添加对话模型', 'warning')
    return
  }
  compacting.value = true
  try {
    const r = await compactSession(props.sessionId, selectedModelIndex.value)
    if (r.compacted && r.contextTokens != null) {
      run.setContextTokens(activeKey.value, r.contextTokens)
    }
    showToast(r.message || (r.compacted ? '已压缩上下文' : '无需压缩'), r.compacted ? 'success' : 'info')
  } catch (e) {
    showToast('压缩失败: ' + (e as Error).message, 'warning')
  } finally {
    compacting.value = false
  }
}
</script>

<template>
  <div
    class="w-full h-full flex flex-col items-center p-6"
  >
    <!-- 消息列表:占满剩余空间,把输入区压到底部;宽度取容器 70% 居中 -->
    <div
      ref="listRef"
      class="chat-scroll w-[70%] flex-1 overflow-y-auto mb-4"
    >
      <div
        v-for="item in renderItems"
        :key="item.key"
        class="mb-4 flex"
        :class="item.kind === 'text' && item.role === 'user' ? 'justify-end' : 'justify-start'"
      >
        <!-- 文本事件:按 Markdown 渲染。助手正文撑满对话框宽度(左右与对话框对齐);
             用户消息最多占对话框宽度的 60%,右对齐(右边缘与对话框对齐) -->
        <div
          v-if="item.kind === 'text'"
          class="px-3.5 py-2.5 text-[15px] leading-relaxed break-words"
          :class="item.role === 'user' ? 'max-w-[60%]' : 'w-full'"
        >
          <MarkdownMessage :content="item.content || ''" />
        </div>
        <!-- 思考事件:折叠卡片;撑满对话框宽度,左右与对话框对齐 -->
        <div v-else-if="item.kind === 'reasoning'" class="w-full px-3.5">
          <ThinkingCard :content="item.content || ''" :thinking="item.thinking" />
        </div>
        <!-- 工具事件:本地/MCP 共用卡片(tool_call + tool_result 已合并);撑满对话框宽度,边框与对话框对齐 -->
        <div v-else class="w-full">
          <ToolCallCard
            :tool-name="item.toolName || '工具'"
            :source="item.source || 'local'"
            :args="item.args"
            :status="item.status || 'running'"
            :result="item.result"
            :error="item.error"
          />
        </div>
      </div>
      <!-- 等待首个增量:仅在尚未收到任何流式内容时显示占位,内容开始流式后由气泡/思考卡接管 -->
      <div v-if="sending && !started" class="mb-4 flex justify-start">
        <div class="px-3.5 py-2.5 text-[15px] leading-relaxed text-on-surface-variant/60 italic">
          思考中...
        </div>
      </div>
    </div>

    <!-- 输入区:底部居中,宽度取容器 70% -->
    <div class="w-[70%]">
      <div class="border border-outline-variant rounded-2xl px-3.5 py-3 bg-white shadow-[0_2px_12px_rgba(0,0,0,0.04)]">
        <textarea
          ref="textareaRef"
          v-model="input"
          rows="3"
          placeholder="今天帮你做些什么？"
          class="w-full resize-none border-none outline-none text-[15px] leading-relaxed bg-transparent placeholder:text-on-surface-variant/50"
          @input="onInput"
          @keydown="onKeydown"
        />
        <div class="flex items-center justify-between mt-2 gap-2">
          <!-- 左侧：模型选择 -->
          <div class="flex items-center gap-2 min-w-0 flex-1">
            <select
              v-if="config.availableChatModels.length > 0"
              v-model="selectedModelIndex"
              class="rounded-lg border border-outline-variant/60 bg-white px-2.5 py-1.5 text-xs text-on-surface outline-none transition-colors focus:border-primary appearance-auto flex-shrink-0"
            >
              <option
                v-for="(m, i) in config.availableChatModels"
                :key="i"
                :value="i"
              >
                {{ m.displayName || m.modelName || '未命名' }}
              </option>
            </select>
          </div>

          <!-- 右侧：运行中显示停止按钮,否则显示发送按钮 -->
          <button
            v-if="sending"
            type="button"
            class="flex items-center justify-center w-9 h-9 rounded-full bg-red-500 text-white transition-colors hover:bg-red-600 flex-shrink-0"
            title="停止本轮任务"
            @click="stop"
          >
            <span class="material-symbols-outlined text-[20px]">stop</span>
          </button>
          <button
            v-else
            type="button"
            class="flex items-center justify-center w-9 h-9 rounded-full bg-primary text-on-primary transition-colors hover:bg-primary-container disabled:opacity-40 disabled:cursor-not-allowed flex-shrink-0"
            :disabled="!input.trim()"
            @click="send"
          >
            <svg
              class="w-[18px] h-[18px]"
              viewBox="0 0 1024 1024"
              xmlns="http://www.w3.org/2000/svg"
            >
              <path
                d="M871.04 89.770667L120.064 380.16a51.2 51.2 0 0 0-1.792 94.762667l303.36 130.56 131.072 303.957333a51.2 51.2 0 0 0 94.805333-1.877333l289.792-751.573334a51.2 51.2 0 0 0-66.261333-66.133333z m-41.130667 107.392l-231.978666 601.642666-97.962667-227.114666-3.584-7.338667a85.333333 85.333333 0 0 0-41.045333-37.248l-226.56-97.536 601.173333-232.405333z"
                fill="currentColor"
              />
            </svg>
          </button>
        </div>
      </div>

      <!-- 工作区选择(左) + 上下文占用占比(右):同一行 -->
      <div class="flex items-start justify-between gap-2 mt-2">
      <div class="relative min-w-0">
        <button
          type="button"
          class="inline-flex max-w-full items-center gap-1 rounded-lg border border-outline-variant/50 bg-white px-2.5 py-1.5 text-xs text-on-surface-variant/70 outline-none transition-colors hover:border-primary/50 hover:text-on-surface"
          title="工作区"
          @click="showWsMenu = !showWsMenu"
        >
          <span class="material-symbols-outlined text-[16px] flex-shrink-0">folder_open</span>
          <span class="break-all text-left">{{ config.workingDir || '选择工作区' }}</span>
          <span class="material-symbols-outlined text-[16px] flex-shrink-0">arrow_drop_down</span>
        </button>

        <!-- 点击空白关闭 -->
        <div
          v-if="showWsMenu"
          class="fixed inset-0 z-10"
          @click="showWsMenu = false"
        />
        <!-- 菜单 -->
        <div
          v-if="showWsMenu"
          class="absolute bottom-full left-0 mb-1 z-20 min-w-[160px] rounded-lg border border-outline-variant/60 bg-white py-1 shadow-[0_4px_16px_rgba(0,0,0,0.12)]"
        >
          <button
            type="button"
            class="flex w-full items-center gap-2 px-3 py-2 text-xs text-on-surface outline-none transition-colors hover:bg-surface-variant/40"
            @click="onSelectWorkingDir"
          >
            <span class="material-symbols-outlined text-[16px]">folder_open</span>
            打开文件夹
          </button>
          <button
            type="button"
            class="flex w-full items-center gap-2 px-3 py-2 text-xs text-on-surface outline-none transition-colors hover:bg-surface-variant/40"
            @click="onNewWorkingDir"
          >
            <span class="material-symbols-outlined text-[16px]">create_new_folder</span>
            新建工作区
          </button>
        </div>
      </div>

        <!-- 右侧:压缩按钮 + 上下文窗口占用占比(右对齐) -->
        <div class="flex items-center gap-2 flex-shrink-0 pt-1.5">
          <button
            type="button"
            class="inline-flex items-center gap-1 rounded-lg border border-outline-variant/50 bg-white px-2.5 py-1 text-xs text-on-surface-variant/70 outline-none transition-colors hover:border-primary/50 hover:text-on-surface disabled:opacity-40 disabled:cursor-not-allowed"
            :title="props.sessionId == null ? '开始对话后可压缩上下文' : '压缩较早对话为摘要,降低上下文占用'"
            :disabled="props.sessionId == null || compacting || sending"
            @click="onCompact"
          >
            <span class="material-symbols-outlined text-[16px]" :class="{ 'animate-spin': compacting }">
              {{ compacting ? 'progress_activity' : 'compress' }}
            </span>
            {{ compacting ? '压缩中…' : '压缩' }}
          </button>
          <ContextUsageBar :used="usedTokens" :total="totalTokens" />
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
/* 消息列表:保留滚动,隐藏滚动条(三端屏蔽) */
.chat-scroll {
  scrollbar-width: none; /* Firefox */
  -ms-overflow-style: none; /* IE/旧 Edge */
}
.chat-scroll::-webkit-scrollbar {
  display: none; /* Chrome/Edge/Safari */
}
</style>
