<script setup lang="ts">
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { NodeViewWrapper } from '@tiptap/vue-3'
import MermaidBlock from '@/components/MermaidBlock.vue'
import { ensureHighlighter, codeToHtml, escapeHtml } from '@/utils/shiki'

const props = defineProps({
  node: { type: Object, required: true },
  updateAttributes: { type: Function, required: true },
  selected: { type: Boolean, default: false },
  editor: { type: Object, required: true },
  getPos: { type: Function, required: true },
})

const isEditable = computed(() => props.editor?.isEditable ?? false)
const isMermaid = computed(() => props.node.attrs.language === 'mermaid')
const code = computed(() => props.node.textContent || '')
const lang = computed(() => (props.node.attrs.language || '').trim().toLowerCase())

const editing = ref(false)
const editingLang = ref(false)
const textareaRef = ref<HTMLTextAreaElement | null>(null)
const langInputRef = ref<HTMLInputElement | null>(null)
const highlightedHtml = ref('')
const hlReady = ref(false)

/** 常用语言列表，给 datalist 做自动补全 */
const COMMON_LANGS = [
  'typescript', 'javascript', 'tsx', 'jsx', 'json', 'html', 'css', 'scss',
  'python', 'java', 'kotlin', 'scala', 'go', 'rust', 'c', 'cpp', 'csharp',
  'swift', 'dart', 'php', 'ruby', 'lua', 'r', 'perl',
  'bash', 'shell', 'powershell', 'sql', 'mysql', 'pgsql',
  'yaml', 'toml', 'xml', 'diff', 'graphql', 'protobuf',
  'markdown', 'dockerfile', 'makefile', 'nginx', 'vue', 'astro', 'svelte',
  'plaintext',
]

function autoResize() {
  const ta = textareaRef.value
  if (!ta) return
  ta.style.height = '0'
  ta.style.height = ta.scrollHeight + 'px'
}

function updateHighlight() {
  if (!hlReady.value) {
    highlightedHtml.value = escapeHtml(code.value)
    return
  }
  highlightedHtml.value = codeToHtml(code.value, lang.value || 'plaintext')
}

onMounted(async () => {
  try {
    await ensureHighlighter()
    hlReady.value = true
  } catch {
    // Shiki 初始化失败时静默回退
  }
  updateHighlight()
})

watch([code, lang], () => {
  updateHighlight()
})

// --- 代码编辑 ---
function startEditing() {
  if (!isEditable.value) return
  editing.value = true
  nextTick(() => {
    const ta = textareaRef.value
    if (!ta) return
    ta.focus()
    requestAnimationFrame(() => {
      ta.style.height = '0'
      ta.style.height = ta.scrollHeight + 'px'
    })
  })
}

function stopEditing() {
  editing.value = false
}

function updateCode(e: Event) {
  const newCode = (e.target as HTMLTextAreaElement).value
  const { state, dispatch } = props.editor.view
  const pos = props.getPos()
  const start = pos + 1
  const end = pos + props.node.nodeSize - 1
  const tr = state.tr.replaceWith(
    start,
    end,
    newCode ? state.schema.text(newCode) : state.schema.text(' '),
  )
  dispatch(tr)
  nextTick(autoResize)
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Tab') {
    e.preventDefault()
    const ta = e.target as HTMLTextAreaElement
    const s = ta.selectionStart
    const end = ta.selectionEnd
    ta.value = ta.value.substring(0, s) + '  ' + ta.value.substring(end)
    ta.selectionStart = ta.selectionEnd = s + 2
    updateCode({ target: ta } as unknown as Event)
  }
  if (e.key === 'Escape') stopEditing()
}

// --- 语言编辑 ---
function startEditingLang() {
  if (!isEditable.value) return
  editingLang.value = true
  nextTick(() => {
    langInputRef.value?.focus()
    langInputRef.value?.select()
  })
}

function commitLang() {
  editingLang.value = false
}

function onLangKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter') commitLang()
  if (e.key === 'Escape') commitLang()
}

function updateLang(e: Event) {
  const newLang = (e.target as HTMLInputElement).value.trim().toLowerCase()
  props.updateAttributes({ language: newLang || null })
}

// --- 删除节点 ---
function deleteNode() {
  if (!isEditable.value) return
  const pos = props.getPos()
  const { state, dispatch } = props.editor.view
  dispatch(state.tr.delete(pos, pos + props.node.nodeSize))
}
</script>

<template>
  <!-- Mermaid 图表 -->
  <MermaidBlock
    v-if="isMermaid"
    :node="node"
    :updateAttributes="updateAttributes"
    :selected="selected"
    :editor="editor"
    :getPos="getPos"
  />
  <!-- 普通代码块 -->
  <NodeViewWrapper v-else class="codeblock-wrapper">
    <!-- 顶部栏：语言选择 + 操作按钮 -->
    <div class="codeblock-header">
      <div class="flex items-center gap-1.5 min-w-0">
        <span class="material-symbols-outlined text-sm text-on-surface-variant/60 shrink-0">code</span>
        <!-- 语言编辑态 -->
        <template v-if="editingLang">
          <input
            ref="langInputRef"
            :value="lang"
            @input="updateLang"
            @blur="commitLang"
            @keydown="onLangKeydown"
            :list="'langlist-' + getPos()"
            placeholder="语言"
            class="codeblock-lang-input"
          />
          <datalist :id="'langlist-' + getPos()">
            <option v-for="l in COMMON_LANGS" :key="l" :value="l" />
          </datalist>
        </template>
        <!-- 语言预览态：可编辑时显示为按钮，只读时显示为纯文本 -->
        <button
          v-else-if="isEditable"
          class="codeblock-lang-label"
          @click.stop="startEditingLang"
          :title="lang ? '点击修改语言' : '点击选择语言'"
        >
          {{ lang || 'plaintext' }}
        </button>
        <span
          v-else
          class="codeblock-lang-label-readonly"
        >
          {{ lang || 'plaintext' }}
        </span>
      </div>
      <button v-if="isEditable" class="codeblock-action-btn" title="删除" @click.stop="deleteNode">
        <span class="material-symbols-outlined text-sm">delete</span>
      </button>
    </div>

    <!-- 编辑模式 -->
    <pre v-if="editing" class="codeblock-pre"><textarea
        ref="textareaRef"
        :value="code"
        @input="updateCode"
        @blur="stopEditing"
        @keydown="onKeydown"
        spellcheck="false"
        class="codeblock-textarea"
      ></textarea></pre>
    <!-- 预览模式：Shiki 着色 -->
    <div
      v-else
      class="codeblock-preview shiki-wrapper"
      :class="{ 'cursor-text': isEditable, 'cursor-default': !isEditable }"
      @click="isEditable ? startEditing() : undefined"
      v-html="highlightedHtml"
    ></div>
  </NodeViewWrapper>
</template>

<style scoped>
.codeblock-wrapper {
  margin: 1em 0;
  border: 1.5px solid var(--color-outline-variant, #e0e0e0);
  border-radius: 10px;
  overflow: hidden;
}

/* ---- 顶部栏 ---- */
.codeblock-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px;
  background: var(--color-surface-container-high, #f0f0f0);
  border-bottom: 1px solid var(--color-outline-variant, #e0e0e0);
}

.codeblock-lang-label {
  font-size: 12px;
  font-weight: 600;
  font-family: var(--font-family-mono, 'JetBrains Mono', monospace);
  color: var(--color-on-surface-variant, #666);
  background: transparent;
  border: 1px solid transparent;
  border-radius: 4px;
  padding: 1px 6px;
  cursor: pointer;
  transition: all 0.15s;
}
.codeblock-lang-label:hover {
  background: var(--color-surface-container-highest, #e0e0e0);
  border-color: var(--color-outline-variant, #ccc);
}

.codeblock-lang-label-readonly {
  font-size: 12px;
  font-weight: 600;
  font-family: var(--font-family-mono, 'JetBrains Mono', monospace);
  color: var(--color-on-surface-variant, #666);
  user-select: none;
}

.codeblock-lang-input {
  font-size: 12px;
  font-weight: 600;
  font-family: var(--font-family-mono, 'JetBrains Mono', monospace);
  color: var(--color-on-surface, #333);
  background: var(--color-surface-container-lowest, #fff);
  border: 1px solid var(--color-primary, #0051ae);
  border-radius: 4px;
  padding: 1px 6px;
  outline: none;
  width: 110px;
}

.codeblock-action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 6px;
  color: var(--color-on-surface-variant, #666);
  transition: all 0.15s;
}
.codeblock-action-btn:hover {
  background: #fce4ec;
  color: #c62828;
}

/* ---- 代码区 ---- */
.codeblock-pre {
  background: #f6f8fa;
  padding: 0.75rem 1rem;
  overflow-x: auto;
  font-family: 'JetBrains Mono', 'SF Mono', 'Fira Code', Consolas, monospace;
  font-size: 0.875rem;
  line-height: 1.7;
  margin: 0;
  cursor: text;
  border-radius: 0;
}

.codeblock-preview {
  overflow-x: auto;
  cursor: text;
}

/* Shiki <pre>：去掉独立圆角（由外层 wrapper 统一控制） */
.shiki-wrapper :deep(pre) {
  border-radius: 0 !important;
  padding: 0.75rem 1rem;
  margin: 0 !important;
  font-family: 'JetBrains Mono', 'SF Mono', 'Fira Code', Consolas, monospace;
  font-size: 0.875rem;
  line-height: 1.7;
  overflow-x: auto;
}

.shiki-wrapper :deep(code) {
  font-family: inherit;
  font-size: inherit;
  line-height: inherit;
}

.codeblock-textarea {
  display: block;
  width: 100%;
  min-height: 40px;
  border: none;
  outline: none;
  resize: none;
  overflow: hidden;
  background: transparent;
  color: #333;
  font-family: inherit;
  font-size: inherit;
  line-height: inherit;
  letter-spacing: inherit;
  padding: 0;
  margin: 0;
  white-space: pre;
  tab-size: 2;
}
</style>
