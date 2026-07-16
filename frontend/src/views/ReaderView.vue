<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useEditor, EditorContent } from '@tiptap/vue-3'
import StarterKit from '@tiptap/starter-kit'
import Image from '@tiptap/extension-image'
import { Table, TableRow, TableHeader, TableCell } from '@tiptap/extension-table'
import { marked } from 'marked'
import { MermaidNode } from '@/extensions/MermaidNode'
import { useKnowledgeStore } from '../stores/knowledge'
import { parseMdSections, flattenSections, type FlatTocItem } from '@/utils/md-sections'

const route = useRoute()
const router = useRouter()
const store = useKnowledgeStore()

// 文档以相对路径为标识；正文从磁盘按需读取
const docPath = computed(() => String(route.params.path || ''))
const doc = ref<{ name: string; content: string } | null>(null)

async function loadDoc() {
  try {
    const d = await store.readDoc(docPath.value)
    doc.value = { name: d.name, content: d.content }
  } catch {
    doc.value = null
  }
}

// ── 博客式文章元信息（字数 / 预估阅读时长） ─────────────
const articleMeta = computed(() => {
  if (!doc.value) return null
  const chars = (doc.value.content || '').replace(/\s+/g, '').length
  const minutes = Math.max(1, Math.ceil(chars / 400)) // 中文约 400 字/分钟
  return { chars, minutes, dateStr: '' }
})

// ── Tiptap 只读编辑器（与 EditorView 完全相同的扩展，editable: false） ─
// 注意：去掉了 px/py/min-h，正文内边距改由本组件 scoped 样式控制（博客式贴边排版）
const tiptapEditor = useEditor({
  extensions: [
    StarterKit.configure({
      codeBlock: false, // 禁用默认 CodeBlock，使用 MermaidNode 替代（含 Shiki 高亮 + Mermaid 渲染）
    }),
    MermaidNode,
    Image.configure({
      inline: false,
      allowBase64: false,
    }),
    Table.configure({ resizable: true }),
    TableRow,
    TableHeader,
    TableCell,
  ],
  editable: false,
  content: '',
  editorProps: {
    attributes: {
      class: 'prose prose-sm max-w-none text-on-surface leading-7',
    },
  },
})

// ── 目录 TOC（与编辑页同款，直接基于原始 MD 构建，无 AI 按钮） ──────
const tocItems = ref<FlatTocItem[]>([])
const activeId = ref('')
const showMobileToc = ref(false)
/** 标题 DOM 缓存：TOC 项 id → 渲染后的标题元素，供滚动高亮使用 */
let headingMap: { id: string; el: Element }[] = []

function buildToc() {
  if (!doc.value) {
    tocItems.value = []
    return
  }
  const tree = parseMdSections(doc.value.content || '')
  tocItems.value = flattenSections(tree)
}

/**
 * 建立 TOC 项与正文标题 DOM 的映射（按标题文字 + 层级匹配）。
 * 实时查询编辑器 DOM，确保只读编辑器渲染完成后才能命中。
 */
function buildHeadingMap() {
  headingMap = []
  if (!tiptapEditor.value) return
  const editorDom = tiptapEditor.value.view.dom
  const headings = Array.from(editorDom.querySelectorAll('h1, h2, h3, h4, h5, h6'))
  for (const item of tocItems.value) {
    const el = headings.find(
      (el) => (el.textContent || '').trim() === item.text && parseInt(el.tagName.charAt(1)) === item.level,
    )
    if (el) headingMap.push({ id: item.id, el })
  }
}

/**
 * 点击目录项：滚动到对应标题。
 * 与编辑页 scrollToEditorHeading 逻辑一致——每次点击实时查询编辑器 DOM
 * 并按「标题文字 + 层级」匹配，避免依赖可能为空/过期的缓存。
 */
function scrollToHeading(item: FlatTocItem) {
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

// ── 滚动高亮（scroll-spy，博客阅读页常见交互） ───────────────────
let ticking = false
function onScroll() {
  if (ticking) return
  ticking = true
  requestAnimationFrame(() => {
    updateActive()
    ticking = false
  })
}
function updateActive() {
  // 缓存为空时（如首次滚动早于 nextTick 构建）惰性重建一次
  if (!headingMap.length) buildHeadingMap()
  if (!headingMap.length) return
  const offset = 100 // 顶部 sticky 导航高度 + 余量
  let current = headingMap[0].id
  for (const h of headingMap) {
    if (h.el.getBoundingClientRect().top <= offset) current = h.id
    else break
  }
  activeId.value = current
}

// ── 载入文档内容（与 EditorView 相同路径：MD → marked → setContent） ─
function loadContent() {
  if (!tiptapEditor.value || !doc.value) return
  const html = marked.parse(doc.value.content, { async: false }) as string
  tiptapEditor.value.commands.setContent(html)
  buildToc()
  nextTick(() => {
    buildHeadingMap()
    updateActive()
  })
}

onMounted(() => {
  loadDoc()
  window.addEventListener('scroll', onScroll, { passive: true })
})

// 编辑器实例就绪后再载入一次（onMounted 时实例可能尚未创建完成）
watch(tiptapEditor, (editor) => {
  if (editor) loadContent()
})

// doc 载入后渲染；路由变化（同一组件复用）时重新读盘
watch(doc, () => loadContent())
watch(docPath, () => loadDoc())

onBeforeUnmount(() => {
  window.removeEventListener('scroll', onScroll)
  tiptapEditor.value?.destroy()
})

// ── 导航 ────────────────────────────────────────────────────────
function back() {
  router.push({ name: 'main' })
}
</script>

<template>
  <div class="min-h-screen bg-surface">
    <!-- 悬浮返回按钮：玻璃拟态圆钮，悬停平滑展开为「返回」胶囊 -->
    <button
      @click="back"
      title="返回知识库"
      class="group fixed top-6 left-6 z-40 flex items-center h-11 px-2.5 rounded-full bg-surface-container-lowest/70 backdrop-blur-xl border border-outline-variant/30 shadow-lg shadow-black/[0.06] text-on-surface-variant hover:text-primary hover:border-primary/30 transition-all duration-300"
    >
      <span class="material-symbols-outlined text-xl transition-transform duration-300 group-hover:-translate-x-0.5">arrow_back</span>
      <span class="grid grid-cols-[0fr] group-hover:grid-cols-[1fr] transition-[grid-template-columns] duration-300 ease-out">
        <span class="overflow-hidden">
          <span class="block pl-1.5 pr-1 text-sm font-semibold whitespace-nowrap">返回</span>
        </span>
      </span>
    </button>

    <!-- 文档不存在 -->
    <main
      v-if="!doc"
      class="max-w-5xl mx-auto px-6 py-20 flex flex-col items-center gap-4 text-on-surface-variant/60"
    >
      <span class="material-symbols-outlined text-5xl">error</span>
      <p class="text-sm">文档不存在或已被删除</p>
      <button
        @click="back"
        class="flex items-center gap-1.5 rounded-lg border border-outline-variant px-4 py-2 text-sm text-on-surface hover:bg-surface-container transition-colors"
      >
        <span class="material-symbols-outlined text-lg">arrow_back</span>
        返回知识库
      </button>
    </main>

    <!-- 正文：博客式文章排版（贴边正文 + 文章头部），渲染效果与编辑页一致 -->
    <main v-else class="max-w-5xl mx-auto px-6 pt-20 pb-16">
      <article>
        <!-- 文章头部：标题 + 元信息 -->
        <header class="mb-10">
          <h1 class="text-3xl font-headline font-bold text-on-surface leading-tight">
            {{ doc.name }}
          </h1>
          <div
            v-if="articleMeta"
            class="mt-4 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-on-surface-variant/55"
          >
            <span v-if="articleMeta.dateStr" class="flex items-center gap-1">
              <span class="material-symbols-outlined text-[15px]">schedule</span>
              更新于 {{ articleMeta.dateStr }}
            </span>
            <span class="text-outline-variant/50">·</span>
            <span>{{ articleMeta.chars }} 字</span>
            <span class="text-outline-variant/50">·</span>
            <span>约 {{ articleMeta.minutes }} 分钟阅读</span>
          </div>
          <div class="mt-6 h-px bg-outline-variant/25"></div>
        </header>

        <!-- Tiptap 只读正文（无外框，直接铺在页面上） -->
        <EditorContent :editor="tiptapEditor" />
      </article>
    </main>

    <!-- 右侧 TOC 目录（超大屏，与编辑页同款样式） -->
    <aside v-if="doc && tocItems.length" class="hidden 2xl:block fixed top-20 w-48 z-10" style="left: calc(50% + 512px + 16px);">
      <nav>
        <h4 class="text-xs font-bold text-on-surface-variant/50 uppercase tracking-widest mb-3 flex items-center gap-1.5 px-0.5">
          <span class="material-symbols-outlined text-sm">toc</span>
          目录
        </h4>
        <ul class="toc-list space-y-0.5">
          <li
            v-for="item in tocItems"
            :key="item.id"
            :style="{ paddingLeft: item.depth * 12 + 'px' }"
          >
            <button
              @click="scrollToHeading(item)"
              class="block w-full text-left leading-relaxed py-0.5 transition-colors duration-150 hover:text-primary truncate"
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
    <template v-if="doc && tocItems.length">
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
              :style="{ paddingLeft: item.depth * 12 + 'px' }"
            >
              <button
                @click="scrollToHeading(item)"
                class="block w-full text-left leading-relaxed py-0.5 transition-colors duration-150 hover:text-primary truncate"
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
  </div>
</template>

<style scoped>
/* ── 与 EditorView :deep(.tiptap) 完全相同的排版（仅去掉内边距/最小高度，博客式贴边） ── */
:deep(.tiptap) {
  padding: 0;
  font-size: 14px;
  line-height: 1.8;
  color: var(--color-on-surface);
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

/* 表格（与编辑页一致） */
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
  display: none; /* 只读模式不显示拖拽手柄 */
}

/* 正文标题滚动偏移（顶部 sticky 导航高度） */
:deep(.tiptap h1),
:deep(.tiptap h2),
:deep(.tiptap h3),
:deep(.tiptap h4),
:deep(.tiptap h5),
:deep(.tiptap h6) {
  scroll-margin-top: 80px;
}

/* 代码块内编辑元素隐藏（只读） */
:deep(.codeblock-action-btn) { display: none; }
:deep(.codeblock-lang-label) { cursor: default; }
:deep(.codeblock-lang-label:hover) { background: transparent; border-color: transparent; }
:deep(.codeblock-preview) { cursor: default; }
:deep(.codeblock-pre) { cursor: default; }
:deep(.mermaid-action-btn) { display: none; }
:deep(.mermaid-preview) { cursor: default; }
:deep(.mermaid-preview:hover) { background: transparent; }

/* TOC 列表（纯字体层级，无框无树线，与编辑页一致） */
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
