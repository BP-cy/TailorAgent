<script setup lang="ts">
import { ref, computed, onBeforeUnmount, onMounted, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useEditor, EditorContent } from '@tiptap/vue-3'
import type { Editor } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import Image from '@tiptap/extension-image'
import { Table, TableRow, TableHeader, TableCell } from '@tiptap/extension-table'
import { marked } from 'marked'
import TurndownService from 'turndown'
import { gfm } from 'turndown-plugin-gfm'
import { MermaidNode } from '@/extensions/MermaidNode'
import { uploadImage, streamKnowledgeEdit, cancelKnowledgeEdit, type ToolCallPayload, type ToolResultPayload } from '@/api'
import { useKnowledgeStore } from '@/stores/knowledge'
import { useUiStore } from '@/stores/ui'
import { parseMdSections, flattenSections, type FlatTocItem } from '@/utils/md-sections'
import { useToast } from '@/composables/useToast'
import { useConfirm } from '@/composables/useConfirm'
import ToolCallCard from '@/components/ToolCallCard.vue'
import MarkdownMessage from '@/components/MarkdownMessage.vue'
import ThinkingCard from '@/components/ThinkingCard.vue'

const { showToast } = useToast()
const { confirm } = useConfirm()

const route = useRoute()
const router = useRouter()
const store = useKnowledgeStore()
const ui = useUiStore()

// 文档以相对路径为标识（形如 MD/工作/报告.md）；无 path 表示新建
const docPath = ref<string | null>(route.params.path ? String(route.params.path) : null)
const isEdit = computed(() => !!docPath.value)

const title = ref('')
const saving = ref(false)
const loading = ref(false)
/** 原始 Markdown 文本 —— TOC 和 AI 编辑的数据源 */
const originalMd = ref('')
/** 原始标题 —— 配合 originalMd 做脏检测 */
const originalName = ref('')
/** 是否有未保存的修改 */
const hasUnsavedChanges = computed(() => {
  if (!tiptapEditor.value) return false
  const currentMd = turndown.turndown(getHtml())
  return title.value !== originalName.value || currentMd !== originalMd.value
})

// --- 正文格式转换 ---
// 知识库文档以 Markdown 存储（便于切片 / 向量化），编辑器内部用 HTML。
// 载入：Markdown → HTML；保存：HTML → Markdown。
const turndown = new TurndownService({ codeBlockStyle: 'fenced', headingStyle: 'atx' })
turndown.use(gfm)

// TOC 专用 turndown：禁用转义。保存用的 turndown 会把 `1.` 转成 `1\.`、`*`/`_`/`[` 等加反斜杠
// （为产出可逆 MD），但 TOC 只需要标题文字，这些转义反而是噪音。禁用后标题保持原样。
const tocTurndown = new TurndownService({ codeBlockStyle: 'fenced', headingStyle: 'atx' })
tocTurndown.use(gfm)
tocTurndown.escape = (s: string) => s

// --- Markdown 文本检测 ---
function looksLikeMarkdown(text: string): boolean {
  // 包含代码围栏（```lang）直接视为 Markdown
  if (/```\w+/m.test(text)) return true
  const patterns = [
    /^#{1,6}\s/m,           // 标题
    /\*\*[^*]+\*\*/,        // 加粗
    /^\s*[-*+]\s/m,         // 无序列表
    /^\s*\d+\.\s/m,         // 有序列表
    /^\s*>/m,               // 引用
    /\[.+\]\(.+\)/,         // 链接
  ]
  let matches = 0
  for (const p of patterns) {
    if (p.test(text)) matches++
  }
  return matches >= 2
}

// --- 将粘贴的 Markdown 插入编辑器 ---
// 一次性把整段 MD 转成 HTML 再插入：marked 会输出 <table>、<pre><code class="language-mermaid">、
// <p> 等【同级兄弟】节点，insertContent 一次解析即作为兄弟插入，代码块的 language 也由
// `class="language-xxx"` 还原（与载入文档同一条路径，所以载入时一直正常）。
//
// 旧实现按代码围栏把 MD 拆成多段、逐段 insertContent：插入表格后光标会落进表格最后一个
// 单元格，后续的 mermaid 代码块和段落就被塞进了单元格里——即「图表渲染进表格」的根因。
function insertMarkdownContent(editor: Editor, md: string) {
  const normalized = md.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  const html = marked.parse(normalized, { async: false }) as string
  editor.commands.insertContent(html, {
    parseOptions: { preserveWhitespace: false },
  })
}

// --- 图片上传 ---
const uploading = ref(false)
async function uploadImageFile(file: File): Promise<string | null> {
  if (!file || !file.type.startsWith('image/')) return null
  uploading.value = true
  try {
    return await uploadImage(file)
  } catch (err) {
    showToast((err as Error).message || '图片上传失败', 'error')
    return null
  } finally {
    uploading.value = false
  }
}

// --- Tiptap 富文本编辑器 ---
const tiptapEditor = useEditor({
  extensions: [
    StarterKit.configure({
      codeBlock: false, // 禁用默认 CodeBlock，使用 MermaidNode 替代
    }),
    MermaidNode,
    Image.configure({
      inline: false,
      allowBase64: false, // 图片走本地上传接口，不内联 base64
    }),
    Table.configure({ resizable: true }),
    TableRow,
    TableHeader,
    TableCell,
  ],
  content: '',
  editorProps: {
    attributes: {
      class: 'prose prose-sm max-w-none focus:outline-none min-h-[500px] px-6 py-5 text-on-surface leading-7',
    },
    // 粘贴图片时自动上传，粘贴 Markdown 时自动转换为富文本
    handlePaste(_view, event) {
      // 优先处理图片粘贴
      const files = Array.from(event.clipboardData?.files || [])
      const image = files.find((f) => f.type.startsWith('image/'))
      if (image) {
        event.preventDefault()
        uploadImageFile(image).then((url) => {
          if (url && tiptapEditor.value) {
            tiptapEditor.value.chain().focus().setImage({ src: url }).run()
          }
        })
        return true
      }
      // 检测并处理 Markdown 粘贴（优先用纯文本解析，确保代码块 language 属性正确，
      // Mermaid 代码块（```mermaid）才能被 MermaidBlock 正确识别和渲染）
      const plainText = event.clipboardData?.getData('text/plain') || ''
      if (plainText && looksLikeMarkdown(plainText)) {
        event.preventDefault()
        if (tiptapEditor.value) {
          insertMarkdownContent(tiptapEditor.value, plainText)
        }
        return true
      }
      return false
    },
    // 拖拽图片时自动上传
    handleDrop(_view, event) {
      const files = Array.from((event as DragEvent).dataTransfer?.files || [])
      const image = files.find((f) => f.type.startsWith('image/'))
      if (image) {
        event.preventDefault()
        uploadImageFile(image).then((url) => {
          if (url && tiptapEditor.value) {
            tiptapEditor.value.chain().focus().setImage({ src: url }).run()
          }
        })
        return true
      }
      return false
    },
  },
})

/** 从磁盘读取当前文档并渲染进编辑器（打开时、AI 每次 write 后调用） */
async function loadDocFromDisk() {
  if (!docPath.value) return
  try {
    const doc = await store.readDoc(docPath.value)
    title.value = doc.name.replace(/\.md$/i, '')
    originalName.value = title.value
    originalMd.value = doc.content || ''
    const html = marked.parse(doc.content || '', { async: false }) as string
    tiptapEditor.value?.commands.setContent(html)
  } catch {
    showToast('文档加载失败', 'error')
    docPath.value = null
  }
}

// --- 载入已有文档 ---
onMounted(async () => {
  if (!docPath.value) return
  loading.value = true
  await loadDocFromDisk()
  loading.value = false
})

onBeforeUnmount(() => {
  aiAbort?.abort()
  tiptapEditor.value?.destroy()
})

// --- 获取当前编辑器内容 ---
function getHtml(): string {
  return tiptapEditor.value?.getHTML() || ''
}

// --- 保存 → 写入磁盘文件 ---
async function handleSave() {
  const html = getHtml()
  const name = title.value.trim()
  if (!name) {
    showToast('请输入文档标题', 'error')
    return
  }
  // 正文允许为空：只要标题非空即可保存（空正文写入空 Markdown）
  saving.value = true
  try {
    const markdown = turndown.turndown(html)
    if (docPath.value) {
      // 已有文档：标题变化则先重命名文件（同目录），再保存正文
      const dir = docPath.value.includes('/') ? docPath.value.slice(0, docPath.value.lastIndexOf('/')) : 'MD'
      const desired = `${dir}/${name}.md`
      if (desired !== docPath.value) {
        await store.rename(docPath.value, desired)
        docPath.value = desired
        router.replace({ name: 'editor', params: { path: desired } })
      }
      await store.saveDoc(docPath.value, markdown)
    } else {
      // 新建：落到来源文件夹（新建时由知识库面板通过 query.dir 传入，默认 MD 根）
      const dir = (route.query.dir as string) || 'MD'
      const path = `${dir}/${name}.md`
      await store.createDoc(path, markdown)
      docPath.value = path
      router.replace({ name: 'editor', params: { path } })
    }
    originalName.value = name
    originalMd.value = markdown
    showToast('已保存到知识库', 'success')
  } catch (err) {
    showToast((err as Error).message || '保存失败', 'error')
  } finally {
    saving.value = false
  }
}

async function goBack() {
  if (aiRunning.value) {
    showToast('AI 编辑进行中，请稍候', 'warning')
    return
  }
  if (hasUnsavedChanges.value) {
    if (!(await confirm('有未保存的修改，确定要离开吗？'))) return
  }
  ui.mainView = 'knowledge'
  ui.kbTab = 'markdown'
  router.push({ name: 'main' })
}

// --- 富文本工具栏 ---
function ed(): Editor | undefined {
  return tiptapEditor.value
}
function toggleBold() { ed()?.chain().focus().toggleBold().run() }
function toggleItalic() { ed()?.chain().focus().toggleItalic().run() }
function toggleStrike() { ed()?.chain().focus().toggleStrike().run() }
function toggleCode() { ed()?.chain().focus().toggleCode().run() }
function toggleCodeBlock() { ed()?.chain().focus().toggleCodeBlock().run() }
function insertMermaidBlock() {
  ed()?.chain().focus().setCodeBlock({ language: 'mermaid' }).run()
}
function toggleBulletList() { ed()?.chain().focus().toggleBulletList().run() }
function toggleOrderedList() { ed()?.chain().focus().toggleOrderedList().run() }
function toggleBlockquote() { ed()?.chain().focus().toggleBlockquote().run() }
function setHeading(level: 1 | 2 | 3) { ed()?.chain().focus().toggleHeading({ level }).run() }
function toggleHorizontalRule() { ed()?.chain().focus().setHorizontalRule().run() }
function insertTable() {
  ed()?.chain().focus().insertTable({ rows: 3, cols: 3, withHeaderRow: true }).run()
}
function addColumnAfter() { ed()?.chain().focus().addColumnAfter().run() }
function deleteColumn() { ed()?.chain().focus().deleteColumn().run() }
function addRowAfter() { ed()?.chain().focus().addRowAfter().run() }
function deleteRow() { ed()?.chain().focus().deleteRow().run() }
function deleteTable() { ed()?.chain().focus().deleteTable().run() }

// --- 图片按钮：通过隐藏 input 触发文件选择 ---
const imageInputRef = ref<HTMLInputElement | null>(null)
function triggerImageUpload() {
  imageInputRef.value?.click()
}
async function onImageFileChange(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const url = await uploadImageFile(file)
  if (url && tiptapEditor.value) {
    tiptapEditor.value.chain().focus().setImage({ src: url }).run()
  }
  input.value = '' // 清空 input，使同一文件可再次选择
}

// --- AI 编辑对话板（agent + kb 工具，SSE 流式，不落库；常驻左侧）---
const aiInstruction = ref('')
const aiRunning = ref(false)
let aiAbort: AbortController | null = null
let reloadTimer: ReturnType<typeof setTimeout> | null = null
/** 当前在跑编辑的 id，供停止按钮定位（发起时生成） */
let currentEditId: string | null = null

/** 对话板渲染项：用户消息 / 助手正文 / 思考 / 工具卡 */
interface EditItem {
  id: number
  kind: 'user' | 'assistant' | 'reasoning' | 'tool'
  text?: string
  /** reasoning 项专用：true=正在流式思考中 */
  thinking?: boolean
  callId?: string
  toolName?: string
  source?: 'local' | 'mcp'
  args?: string
  status?: string
  result?: string
  error?: string
}
const editItems = ref<EditItem[]>([])
let itemSeq = 0
/** 事件流容器：新增内容后自动滚到底部 */
const listRef = ref<HTMLDivElement | null>(null)

/** 滚动事件流到底部（等 DOM 更新后） */
function scrollToBottom() {
  nextTick(() => {
    const el = listRef.value
    if (el) el.scrollTop = el.scrollHeight
  })
}

/**
 * push 渲染项后取回它在响应式数组里的代理再返回。
 * 关键：直接持有 push 进去的原始对象引用去改 .text，改的是 raw target、绕过 Proxy 的 set 陷阱，
 * 不触发重渲染（表现为「流式内容卡住、最后一次性刷出」）；取回数组里的代理再改才有响应式。
 */
function pushItem(item: EditItem): EditItem {
  editItems.value.push(item)
  return editItems.value[editItems.value.length - 1]
}

/** tool_call 的 args 是否指向当前文档 */
function affectsCurrentDoc(argsJson?: string): boolean {
  if (!argsJson || !docPath.value) return false
  try {
    const o = JSON.parse(argsJson) as { filePath?: string }
    return o.filePath === docPath.value
  } catch {
    return false
  }
}

/** 防抖读盘刷新：AI 每次 write/edit 后重新渲染正文 */
function scheduleReload() {
  if (reloadTimer) clearTimeout(reloadTimer)
  reloadTimer = setTimeout(() => loadDocFromDisk(), 150)
}

/** 生成本轮编辑 id（127.0.0.1/localhost 属安全上下文，crypto.randomUUID 可用；兜底防万一） */
function genEditId(): string {
  try {
    return crypto.randomUUID()
  } catch {
    return 'kb-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2)
  }
}

/** 发起知识库 AI 编辑 */
function sendAiEdit() {
  const instr = aiInstruction.value.trim()
  if (!instr) {
    showToast('请输入编辑指令', 'error')
    return
  }
  if (!docPath.value) {
    showToast('请先保存文档后再用 AI 编辑', 'warning')
    return
  }
  if (aiRunning.value) return

  // 跨轮记忆：把本轮之前的对话（用户指令 + 非空助手正文）随请求带上，让模型记得上下文（历史不落库，仅前端内存）。
  // 排除 reasoning/tool 项；截断到最近 20 条，避免长会话把上下文撑爆。
  const history = editItems.value
    .filter((it) => it.kind === 'user' || (it.kind === 'assistant' && !!(it.text || '').trim()))
    .slice(-20)
    .map((it) => ({ role: (it.kind === 'user' ? 'user' : 'assistant') as 'user' | 'assistant', content: it.text || '' }))

  editItems.value.push({ id: itemSeq++, kind: 'user', text: instr })
  scrollToBottom()
  aiInstruction.value = ''
  aiRunning.value = true
  aiAbort = new AbortController()
  const editId = genEditId()
  currentEditId = editId

  let assistant: EditItem | null = null
  // 思考分段游标：工具/正文/结束到达时关段，下一段思考另起新卡（与 ChatPanel 一致）
  let reasoningItem: EditItem | null = null
  const finishReasoning = () => {
    if (reasoningItem) reasoningItem.thinking = false
    reasoningItem = null
  }
  // 取消/出错统一收尾（去重：cancelled 事件与 abort 触发的 onError('请求已取消') 可能先后到达）
  let finalized = false
  const finalizeStopped = () => {
    if (finalized) return
    finalized = true
    finishReasoning()
    aiRunning.value = false
    currentEditId = null
    scheduleReload() // 已产生的改动读盘刷新
    showToast('已停止本轮编辑', 'info')
  }

  streamKnowledgeEdit(
    { docPath: docPath.value, instruction: instr, modelIndex: 0, editId, history },
    {
      onToolCall(d: ToolCallPayload) {
        finishReasoning()
        assistant = null // 工具后若再有正文，另起气泡
        editItems.value.push({
          id: itemSeq++, kind: 'tool',
          callId: d.callId, toolName: d.toolName, source: d.source, args: d.args, status: 'running',
        })
        scrollToBottom()
      },
      onToolResult(d: ToolResultPayload) {
        const it = editItems.value.find((x) => x.kind === 'tool' && x.callId === d.callId)
        if (it) {
          it.status = d.status
          it.result = d.result
          it.error = d.error
          if (d.status === 'success'
              && (it.toolName === 'kb_write_file' || it.toolName === 'kb_edit_file')
              && affectsCurrentDoc(it.args)) {
            scheduleReload()
          }
        }
        scrollToBottom()
      },
      onReasoning(d) {
        // 逐块增量：首块新建思考卡（取回响应式代理），后续块追加到同一条；工具/正文到达则关段
        if (!reasoningItem) {
          reasoningItem = pushItem({ id: itemSeq++, kind: 'reasoning', text: d.content, thinking: true })
        } else {
          reasoningItem.text = (reasoningItem.text || '') + d.content
        }
        scrollToBottom()
      },
      onText(d) {
        finishReasoning()
        if (!assistant) {
          assistant = pushItem({ id: itemSeq++, kind: 'assistant', text: '' })
        }
        assistant.text = (assistant.text || '') + d.content
        scrollToBottom()
      },
      onDone() {
        finishReasoning()
        aiRunning.value = false
        currentEditId = null
        scheduleReload()
      },
      onCancelled() {
        finalizeStopped()
      },
      onError(message) {
        // abort 触发的 '请求已取消' 与 cancelled 事件走同一收尾（去重）；其余为真实错误
        if (message === '请求已取消') {
          finalizeStopped()
          return
        }
        finishReasoning()
        showToast(message, 'error')
        aiRunning.value = false
        currentEditId = null
      },
    },
    aiAbort.signal,
  )
}

/** 主动停止本轮编辑：调后端取消断流 + abort 断开 SSE（两处分别中断，缺一不可） */
function stopAiEdit() {
  if (!aiRunning.value) return
  if (currentEditId) cancelKnowledgeEdit(currentEditId)
  aiAbort?.abort()
}

function aiAutoResize(e: Event) {
  const el = e.target as HTMLTextAreaElement
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 160) + 'px'
}
function onAiKeydown(e: KeyboardEvent) {
  if (aiRunning.value) return
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendAiEdit()
  }
}

// --- 编辑器目录 TOC（MD-based）---
const tocItems = ref<FlatTocItem[]>([])
const activeId = ref('')
const showMobileToc = ref(false)
let tocTimer: ReturnType<typeof setTimeout> | null = null

/**
 * MD-based TOC：把当前编辑器 HTML 转成 MD 后用 parseMdSections 拆段落树。
 *
 * 用 tocTurndown（禁用转义）转换，标题文字不会被加反斜杠（`1.` 不再变 `1\.`）；
 * 产出的 MdSection 树同时承载每段的 MD 内容/层级路径/行范围，供后续 AI 辅助编辑复用。
 */
function buildEditorToc() {
  if (tocTimer) clearTimeout(tocTimer)
  tocTimer = setTimeout(() => {
    if (!tiptapEditor.value) return
    const html = tiptapEditor.value.getHTML()
    if (!html || html === '<p></p>') {
      tocItems.value = []
      return
    }
    const md = tocTurndown.turndown(html)
    const tree = parseMdSections(md)
    tocItems.value = flattenSections(tree)
  }, 300)
}

/** MD → DOM 滚动桥接：按标题文字 + 层级反向匹配编辑器 DOM 并滚动 */
function scrollToEditorHeading(item: FlatTocItem) {
  if (!tiptapEditor.value) return
  const editorDom = tiptapEditor.value.view.dom
  const headings = Array.from(editorDom.querySelectorAll('h1, h2, h3, h4, h5, h6'))
  const el = headings.find(
    (el) => (el.textContent || '').trim() === item.text && parseInt(el.tagName.charAt(1)) === item.level,
  )
  if (!el) return
  el.scrollIntoView({ behavior: 'smooth', block: 'start' })
  activeId.value = item.id
  showMobileToc.value = false
}

// 监听编辑器实例就绪，绑定 update 事件
watch(tiptapEditor, (editor) => {
  if (editor) {
    editor.on('update', buildEditorToc)
    nextTick(buildEditorToc)
  }
})
</script>

<template>
  <div class="min-h-screen bg-surface">
    <!-- 顶部操作栏 -->
    <header class="sticky top-0 z-30 bg-surface-container-lowest/80 backdrop-blur-xl border-b border-outline-variant/30">
      <div class="max-w-5xl mx-auto px-6 h-14 flex items-center justify-between">
        <button
          @click="goBack"
          class="flex items-center gap-1.5 text-on-surface-variant hover:text-on-surface transition-colors text-sm font-medium"
        >
          <span class="material-symbols-outlined text-lg">arrow_back</span>
          返回
        </button>
        <div class="flex items-center gap-3">
          <button
            @click="handleSave"
            :disabled="saving"
            class="px-5 py-2 bg-primary text-white text-sm font-bold rounded-lg hover:bg-primary/90 disabled:opacity-50 disabled:cursor-not-allowed transition-all flex items-center gap-1.5"
          >
            <span v-if="saving" class="material-symbols-outlined text-base animate-spin">progress_activity</span>
            <span v-else class="material-symbols-outlined text-base">{{ isEdit ? 'save' : 'draft' }}</span>
            {{ isEdit ? '保存' : '保存到知识库' }}
          </button>
        </div>
      </div>
    </header>

    <!-- 主编辑区 -->
    <main v-if="loading" class="max-w-5xl mx-auto px-6 py-8 text-center text-on-surface-variant/50 text-sm">
      <span class="material-symbols-outlined text-4xl mb-3 block animate-spin">progress_activity</span>
      加载文档中...
    </main>
    <main v-else class="max-w-5xl mx-auto px-6 py-8">
      <!-- 标题 -->
      <div class="mb-6">
        <input
          v-model="title"
          type="text"
          placeholder="输入文档标题..."
          maxlength="100"
                   class="w-full text-2xl font-headline font-bold text-on-surface placeholder:text-on-surface-variant/35 bg-transparent border-none outline-none leading-snug disabled:opacity-50 disabled:cursor-not-allowed"
        />
        <div class="mt-3 h-px bg-outline-variant/25"></div>
      </div>

      <!-- 富文本编辑器 -->
      <div>
        <!-- 工具栏（sticky 固定在 header 下方，AI 编辑中全部禁用） -->
        <div class="flex items-center gap-0.5 flex-wrap bg-surface-container-lowest border border-outline-variant/40 rounded-t-xl px-3 py-2 sticky top-14 z-20">
          <button @click="setHeading(1)" class="toolbar-btn" title="标题1">H1</button>
          <button @click="setHeading(2)" class="toolbar-btn" title="标题2">H2</button>
          <button @click="setHeading(3)" class="toolbar-btn" title="标题3">H3</button>
          <span class="w-px h-5 bg-outline-variant/30 mx-1"></span>
          <button @click="toggleBold" class="toolbar-btn" title="加粗">
            <span class="material-symbols-outlined text-base">format_bold</span>
          </button>
          <button @click="toggleItalic" class="toolbar-btn" title="斜体">
            <span class="material-symbols-outlined text-base">format_italic</span>
          </button>
          <button @click="toggleStrike" class="toolbar-btn" title="删除线">
            <span class="material-symbols-outlined text-base">strikethrough_s</span>
          </button>
          <span class="w-px h-5 bg-outline-variant/30 mx-1"></span>
          <button @click="toggleBulletList" class="toolbar-btn" title="无序列表">
            <span class="material-symbols-outlined text-base">format_list_bulleted</span>
          </button>
          <button @click="toggleOrderedList" class="toolbar-btn" title="有序列表">
            <span class="material-symbols-outlined text-base">format_list_numbered</span>
          </button>
          <button @click="toggleBlockquote" class="toolbar-btn" title="引用">
            <span class="material-symbols-outlined text-base">format_quote</span>
          </button>
          <span class="w-px h-5 bg-outline-variant/30 mx-1"></span>
          <button @click="toggleCode" class="toolbar-btn" title="行内代码">
            <span class="material-symbols-outlined text-base">code</span>
          </button>
          <button @click="toggleCodeBlock" class="toolbar-btn" title="代码块">
            <span class="material-symbols-outlined text-base">data_object</span>
          </button>
          <button @click="toggleHorizontalRule" class="toolbar-btn" title="分割线">
            <span class="material-symbols-outlined text-base">horizontal_rule</span>
          </button>
          <span class="w-px h-5 bg-outline-variant/30 mx-1"></span>
          <button @click="insertTable" class="toolbar-btn" title="插入表格">
            <span class="material-symbols-outlined text-base">grid_on</span>
          </button>
          <button @click="addColumnAfter" class="toolbar-btn toolbar-text-btn" title="添加列">+Col</button>
          <button @click="deleteColumn" class="toolbar-btn toolbar-text-btn" title="删除列">-Col</button>
          <button @click="addRowAfter" class="toolbar-btn toolbar-text-btn" title="添加行">+Row</button>
          <button @click="deleteRow" class="toolbar-btn toolbar-text-btn" title="删除行">-Row</button>
          <button @click="deleteTable" class="toolbar-btn" title="删除表格">
            <span class="material-symbols-outlined text-base">delete</span>
          </button>
          <span class="w-px h-5 bg-outline-variant/30 mx-1"></span>
          <button @click="insertMermaidBlock" class="toolbar-btn" title="Mermaid 图表">
            <span class="material-symbols-outlined text-base">schema</span>
          </button>
          <button @click="triggerImageUpload" :disabled="uploading" class="toolbar-btn" title="插入图片">
            <span v-if="uploading" class="material-symbols-outlined text-base animate-spin">progress_activity</span>
            <span v-else class="material-symbols-outlined text-base">image</span>
          </button>
          <input
            ref="imageInputRef"
            type="file"
            accept="image/*"
            class="hidden"
            @change="onImageFileChange"
                     />
        </div>

        <!-- 编辑区 -->
        <div class="bg-surface-container-lowest border border-t-0 border-outline-variant/40 rounded-b-xl min-h-[500px]">
          <EditorContent :editor="tiptapEditor" />
        </div>
      </div>
    </main>

    <!-- 右侧 TOC 目录（超大屏） -->
    <aside v-if="tocItems.length && !loading" class="hidden 2xl:block fixed top-20 w-48 z-10" style="left: calc(50% + 512px + 16px);">
      <nav>
        <h4 class="text-xs font-bold text-on-surface-variant/50 uppercase tracking-widest mb-3 flex items-center gap-1.5 px-0.5">
          <span class="material-symbols-outlined text-sm">toc</span>
          目录
        </h4>
        <ul class="toc-list space-y-0.5">
          <li
            v-for="item in tocItems"
            :key="item.id"
            class="group flex items-center"
            :style="{ paddingLeft: item.depth * 12 + 'px' }"
          >
            <button
              @click="scrollToEditorHeading(item)"
              class="flex-1 min-w-0 text-left leading-relaxed py-0.5 transition-colors duration-150 hover:text-primary truncate"
              :class="[
                item.level === 1
                  ? 'text-[15px] font-bold text-on-surface'
                  : item.level === 2
                    ? 'text-[13px] font-semibold text-on-surface/80'
                    : 'text-[12px] font-medium text-on-surface-variant/60',
                activeId === item.id ? '!text-primary' : '',
              ]"
            >
              {{ item.text }}
            </button>
          </li>
        </ul>
      </nav>
    </aside>

    <!-- 移动端 / 中屏 TOC 浮动按钮 -->
    <template v-if="tocItems.length && !loading">
      <button
        @click="showMobileToc = !showMobileToc"
        class="2xl:hidden fixed bottom-6 right-6 z-40 w-11 h-11 bg-primary text-on-primary rounded-full shadow-lg flex items-center justify-center hover:scale-105 active:scale-95 transition-transform"
      >
        <span class="material-symbols-outlined text-xl">{{ showMobileToc ? 'close' : 'toc' }}</span>
      </button>

      <!-- 移动端 TOC 面板 -->
      <Transition name="toc-panel">
        <div
          v-if="showMobileToc"
          class="2xl:hidden fixed bottom-20 right-6 z-40 w-72 max-h-[70vh] overflow-y-auto bg-surface-container-lowest rounded-2xl shadow-2xl border border-outline-variant/20 p-4"
        >
          <h4 class="text-xs font-bold text-on-surface-variant/50 uppercase tracking-widest mb-3 flex items-center gap-1.5">
            <span class="material-symbols-outlined text-sm">toc</span>
            目录
          </h4>
          <ul class="toc-list space-y-0.5">
            <li
              v-for="item in tocItems"
              :key="item.id"
              class="group flex items-center"
              :style="{ paddingLeft: item.depth * 12 + 'px' }"
            >
              <button
                @click="scrollToEditorHeading(item)"
                class="flex-1 min-w-0 text-left leading-relaxed py-0.5 transition-colors duration-150 hover:text-primary truncate"
                :class="[
                  item.level === 1
                    ? 'text-[15px] font-bold text-on-surface'
                    : item.level === 2
                      ? 'text-[13px] font-semibold text-on-surface/80'
                      : 'text-[12px] font-medium text-on-surface-variant/60',
                  activeId === item.id ? '!text-primary' : '',
                ]"
              >
                {{ item.text }}
              </button>
            </li>
          </ul>
        </div>
      </Transition>
    </template>

    <!-- 左侧 AI 编辑对话板：常驻、无边框、与编辑页同底色（仿右侧 TOC）；agent + kb 工具实时 event 流，不落库，关页即弃 -->
    <!-- 在左侧留白区（正文左边缘 50%-512px 到窗口左边）内水平居中：左偏移 = (区宽 - 面板宽) / 2 -->
    <aside
      v-if="!loading"
      class="fixed top-14 bottom-0 w-80 z-10 flex flex-col"
      style="left: calc((50% - 512px - 320px) / 2)"
    >
      <!-- 顶部提示（一行） -->
      <div class="flex items-center gap-1.5 px-4 h-11 shrink-0">
        <span class="material-symbols-outlined text-primary text-lg">auto_awesome</span>
        <span class="text-sm font-bold text-on-surface">AI 编辑</span>
<!--        <span class="text-xs text-on-surface-variant/50">直接修改当前文档</span>-->
      </div>

      <!-- 事件流：中间区域，撑满剩余高度，可滚动、隐藏滚动条 -->
      <div ref="listRef" class="kb-edit-scroll flex-1 overflow-y-auto px-3 py-2 flex flex-col gap-2">
        <div v-if="!editItems.length" class="text-center text-xs text-on-surface-variant/50 py-8">
          输入指令，AI 会用工具直接修改当前文档
        </div>
        <template v-for="item in editItems" :key="item.id">
          <div
            v-if="item.kind === 'user'"
            class="shrink-0 self-end max-w-[85%] rounded-2xl bg-primary text-on-primary px-3.5 py-2 text-sm whitespace-pre-wrap break-words"
          >{{ item.text }}</div>
          <div
            v-else-if="item.kind === 'assistant'"
            class="shrink-0 self-start w-full text-sm text-on-surface break-words"
          ><MarkdownMessage :content="item.text || ''" /></div>
          <div v-else-if="item.kind === 'reasoning'" class="shrink-0 self-start w-full">
            <ThinkingCard :content="item.text || ''" :thinking="item.thinking" />
          </div>
          <ToolCallCard
            v-else
            class="shrink-0"
            :tool-name="item.toolName || ''"
            :source="item.source || 'local'"
            :args="item.args"
            :status="item.status || 'running'"
            :result="item.result"
            :error="item.error"
          />
        </template>
      </div>

      <!-- 输入区：底部（仿 ChatPanel 输入框样式） -->
      <div class="px-3 pb-3 pt-1 shrink-0">
        <div class="border border-outline-variant rounded-2xl px-3 py-2.5 bg-white shadow-[0_2px_12px_rgba(0,0,0,0.04)]">
          <textarea
            v-model="aiInstruction"
            placeholder="输入编辑指令，回车发送…"
            rows="2"
            maxlength="1000"
            :disabled="aiRunning"
            @keydown="onAiKeydown"
            @input="aiAutoResize"
            class="w-full resize-none border-none outline-none bg-transparent text-sm leading-relaxed text-on-surface placeholder:text-on-surface-variant/50 overflow-y-auto disabled:opacity-50"
            style="max-height: 160px"
          ></textarea>
          <div class="flex items-center justify-end mt-1.5">
            <!-- 运行中显示红色停止键，否则显示发送键（仿 ChatPanel 图标） -->
            <button
              v-if="aiRunning"
              @click="stopAiEdit"
              title="停止本轮编辑"
              class="flex items-center justify-center w-9 h-9 rounded-full bg-red-500 text-white transition-colors hover:bg-red-600 shrink-0"
            >
              <span class="material-symbols-outlined text-[20px]">stop</span>
            </button>
            <button
              v-else
              @click="sendAiEdit"
              :disabled="!aiInstruction.trim()"
              title="发送"
              class="flex items-center justify-center w-9 h-9 rounded-full bg-primary text-on-primary transition-colors hover:bg-primary-container disabled:opacity-40 disabled:cursor-not-allowed shrink-0"
            >
              <svg class="w-[18px] h-[18px]" viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg">
                <path
                  d="M871.04 89.770667L120.064 380.16a51.2 51.2 0 0 0-1.792 94.762667l303.36 130.56 131.072 303.957333a51.2 51.2 0 0 0 94.805333-1.877333l289.792-751.573334a51.2 51.2 0 0 0-66.261333-66.133333z m-41.130667 107.392l-231.978666 601.642666-97.962667-227.114666-3.584-7.338667a85.333333 85.333333 0 0 0-41.045333-37.248l-226.56-97.536 601.173333-232.405333z"
                  fill="currentColor"
                />
              </svg>
            </button>
          </div>
        </div>
      </div>
    </aside>
  </div>
</template>

<style scoped>
/* 富文本工具栏按钮 */
.toolbar-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 6px;
  font-size: 12px;
  font-weight: 700;
  color: var(--color-on-surface-variant);
  transition: all 0.15s;
}
.toolbar-btn:hover {
  background-color: var(--color-surface-container-high);
  color: var(--color-on-surface);
}
.toolbar-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
  pointer-events: none;
}
.toolbar-text-btn {
  font-size: 10px;
  letter-spacing: -0.5px;
  width: auto;
  padding: 0 6px;
}

/* Tiptap 编辑器内容样式 */
:deep(.tiptap) {
  min-height: 500px;
  padding: 20px 24px;
  font-size: 14px;
  line-height: 1.8;
  color: var(--color-on-surface);
}
:deep(.tiptap:focus) {
  outline: none;
}
:deep(.tiptap h1) { font-size: 1.75em; font-weight: 700; margin: 1em 0 0.5em; }
:deep(.tiptap h2) { font-size: 1.4em; font-weight: 700; margin: 0.9em 0 0.4em; }
:deep(.tiptap h3) { font-size: 1.15em; font-weight: 700; margin: 0.8em 0 0.3em; }
:deep(.tiptap p) { margin: 0.5em 0; }
:deep(.tiptap ul),
:deep(.tiptap ol) { padding-left: 1.5em; margin: 0.5em 0; }
:deep(.tiptap li) { margin: 0.2em 0; }
:deep(.tiptap blockquote) {
  border-left: 3px solid var(--color-primary);
  padding-left: 1em;
  margin: 0.8em 0;
  color: var(--color-on-surface-variant);
}
:deep(.tiptap code) {
  background: var(--color-surface-container-high);
  padding: 0.15em 0.4em;
  border-radius: 4px;
  font-size: 0.9em;
  font-family: var(--font-family-mono);
}
:deep(.tiptap pre) {
  background: var(--color-surface-container-high);
  padding: 1em;
  border-radius: 8px;
  overflow-x: auto;
  margin: 0.8em 0;
}
:deep(.tiptap pre code) {
  background: none;
  padding: 0;
}
:deep(.tiptap hr) {
  border: none;
  border-top: 1px solid var(--color-outline-variant);
  margin: 1.5em 0;
}
:deep(.tiptap strong) { font-weight: 700; }
:deep(.tiptap em) { font-style: italic; }
:deep(.tiptap s) { text-decoration: line-through; }
:deep(.tiptap img) {
  max-width: 100%;
  height: auto;
  border-radius: 8px;
  margin: 1em 0;
}

/* 表格样式 */
/* 外层容器：max-width 兜住不溢出 + 表格过宽时横向滚动 + 4px 半透明细滚动条 */
:deep(.tiptap .tableWrapper) {
  max-width: 100%;
  overflow-x: auto;
  scrollbar-width: thin; /* Firefox: 细滚动条 */
}
:deep(.tiptap .tableWrapper::-webkit-scrollbar) {
  height: 4px;
}
:deep(.tiptap .tableWrapper::-webkit-scrollbar-thumb) {
  background: var(--color-outline-variant, rgba(0,0,0,0.12));
  border-radius: 2px;
}
:deep(.tiptap .tableWrapper::-webkit-scrollbar-track) {
  background: transparent;
}
:deep(.tiptap table) {
  border-collapse: collapse;
  width: 100%;
  margin: 1em 0;
  overflow: hidden;
}
:deep(.tiptap th),
:deep(.tiptap td) {
  border: 1px solid var(--color-outline-variant, rgba(0, 0, 0, 0.15));
  padding: 0.5em 0.75em;
  text-align: left;
  vertical-align: top;
  min-width: 80px;
}
:deep(.tiptap th) {
  background: var(--color-surface-container-high, rgba(0, 0, 0, 0.04));
  font-weight: 700;
}
:deep(.tiptap .column-resize-handle) {
  position: absolute;
  right: -2px;
  top: 0;
  bottom: -2px;
  width: 4px;
  background-color: var(--color-primary);
  pointer-events: none;
}
:deep(.tiptap .selectedCell::after) {
  content: '';
  position: absolute;
  inset: 0;
  background: var(--color-primary-container, rgba(0, 0, 0, 0.06));
  pointer-events: none;
}

/* 编辑器标题滚动偏移（header + toolbar 高度） */
:deep(.tiptap h1),
:deep(.tiptap h2),
:deep(.tiptap h3),
:deep(.tiptap h4),
:deep(.tiptap h5),
:deep(.tiptap h6) {
  scroll-margin-top: 120px;
}

/* 编辑器 TOC 列表（纯字体层级，无框无树线） */
.toc-list {
  max-height: calc(100vh - 120px);
  overflow-y: auto;
}
.toc-list::-webkit-scrollbar {
  width: 2px;
}
.toc-list::-webkit-scrollbar-thumb {
  background: var(--color-outline-variant, rgba(0, 0, 0, 0.1));
  border-radius: 2px;
}

/* AI 编辑事件流：保留滚动，隐藏滚动条（三端屏蔽） */
.kb-edit-scroll {
  scrollbar-width: none; /* Firefox */
  -ms-overflow-style: none; /* IE/旧 Edge */
}
.kb-edit-scroll::-webkit-scrollbar {
  display: none; /* Chrome/Edge/Safari */
}

/* 移动端 TOC 面板动画 */
.toc-panel-enter-active,
.toc-panel-leave-active {
  transition: all 0.25s ease;
}
.toc-panel-enter-from,
.toc-panel-leave-to {
  opacity: 0;
  transform: translateY(12px) scale(0.95);
}
</style>
