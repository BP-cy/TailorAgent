<script setup>
import { ref, computed, watch, onMounted, nextTick } from 'vue'
import { NodeViewWrapper } from '@tiptap/vue-3'
import mermaid from 'mermaid'

const props = defineProps({
  node: { type: Object, required: true },
  updateAttributes: { type: Function, required: true },
  selected: { type: Boolean, default: false },
  editor: { type: Object, required: true },
  getPos: { type: Function, required: true },
})

// 初始化 mermaid
mermaid.initialize({
  startOnLoad: false,
  theme: 'default',
  securityLevel: 'loose',
})

const editing = ref(false)
const svgHtml = ref('')
const renderError = ref('')
const textareaRef = ref(null)
const renderKey = ref(0)

const isEditable = computed(() => props.editor?.isEditable ?? false)

// 从 node 获取当前代码
const code = computed(() => props.node.textContent || '')

// 渲染 mermaid 图表
async function renderDiagram() {
  const src = code.value.trim()
  if (!src) {
    svgHtml.value = ''
    renderError.value = ''
    return
  }
  try {
    const id = `mermaid-editor-${Date.now()}-${renderKey.value++}`
    const { svg } = await mermaid.render(id, src)
    svgHtml.value = svg
    renderError.value = ''
  } catch (err) {
    svgHtml.value = ''
    renderError.value = err.message || '图表语法错误'
  }
}

// 进入编辑模式
function startEditing() {
  if (!isEditable.value) return
  editing.value = true
  nextTick(() => {
    textareaRef.value?.focus()
  })
}

// 退出编辑模式，渲染图表
function stopEditing() {
  editing.value = false
  renderDiagram()
}

// 更新节点内容（替换整个 codeBlock 的文本）
function updateCode(e) {
  const newCode = e.target.value
  const { state, dispatch } = props.editor.view
  const pos = props.getPos()
  // 替换 node 内所有文本
  const start = pos + 1 // +1 跳过节点自身的开始位置
  const end = pos + props.node.nodeSize - 1
  const tr = state.tr.replaceWith(
    start,
    end,
    newCode ? state.schema.text(newCode) : state.schema.text(' ')
  )
  dispatch(tr)
}

// 处理 Tab 键缩进
function onKeydown(e) {
  if (e.key === 'Tab') {
    e.preventDefault()
    const ta = e.target
    const start = ta.selectionStart
    const end = ta.selectionEnd
    const val = ta.value
    // 插入两个空格
    const newVal = val.substring(0, start) + '  ' + val.substring(end)
    // 需要通过 updateCode 同步到 node
    ta.value = newVal
    ta.selectionStart = ta.selectionEnd = start + 2
    updateCode({ target: ta })
  }
  if (e.key === 'Escape') {
    stopEditing()
  }
}

// 删除此节点
function deleteNode() {
  if (!isEditable.value) return
  const pos = props.getPos()
  const { state, dispatch } = props.editor.view
  dispatch(state.tr.delete(pos, pos + props.node.nodeSize))
}

// 组件挂载时渲染
onMounted(() => {
  renderDiagram()
})

// 监听 node 内容变化（例如 undo/redo）
watch(code, () => {
  if (!editing.value) {
    renderDiagram()
  }
})
</script>

<template>
  <NodeViewWrapper class="mermaid-node-wrapper" :class="{ 'is-selected': selected }">
    <!-- 标签栏 -->
    <div class="mermaid-header">
      <span class="mermaid-label">
        <span class="material-symbols-outlined text-sm">schema</span>
        Mermaid
      </span>
      <div v-if="isEditable" class="mermaid-actions">
        <button v-if="!editing" @click="startEditing" class="mermaid-action-btn" title="编辑代码">
          <span class="material-symbols-outlined text-sm">edit</span>
        </button>
        <button v-else @click="stopEditing" class="mermaid-action-btn active" title="预览图表">
          <span class="material-symbols-outlined text-sm">visibility</span>
        </button>
        <button @click="deleteNode" class="mermaid-action-btn delete" title="删除">
          <span class="material-symbols-outlined text-sm">delete</span>
        </button>
      </div>
    </div>

    <!-- 编辑模式：代码编辑区 -->
    <div v-if="editing" class="mermaid-code-area">
      <textarea
        ref="textareaRef"
        :value="code"
        @input="updateCode"
        @blur="stopEditing"
        @keydown="onKeydown"
        placeholder="在此输入 Mermaid 语法..."
        spellcheck="false"
        class="mermaid-textarea"
      ></textarea>
    </div>

    <!-- 预览模式：渲染图表 -->
    <div
      v-else
      class="mermaid-preview"
      :class="{ 'cursor-pointer': isEditable, 'cursor-default': !isEditable }"
      @click="isEditable ? startEditing() : undefined"
    >
      <!-- 渲染成功 -->
      <div v-if="svgHtml" class="mermaid-svg" v-html="svgHtml"></div>
      <!-- 渲染失败 -->
      <div v-else-if="renderError" class="mermaid-error">
        <span class="material-symbols-outlined text-base">error</span>
        <span>{{ renderError }}</span>
      </div>
      <!-- 空内容 -->
      <div v-else class="mermaid-empty">
        <span class="material-symbols-outlined text-2xl">schema</span>
        <span>点击编辑 Mermaid 图表</span>
      </div>
    </div>
  </NodeViewWrapper>
</template>

<style scoped>
.mermaid-node-wrapper {
  border: 1.5px solid var(--color-outline-variant, #e0e0e0);
  border-radius: 10px;
  margin: 1em 0;
  overflow: hidden;
  transition: border-color 0.2s;
}
.mermaid-node-wrapper.is-selected {
  border-color: var(--color-primary, #6750a4);
}

.mermaid-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 12px;
  background: var(--color-surface-container-high, #f0f0f0);
  border-bottom: 1px solid var(--color-outline-variant, #e0e0e0);
}

.mermaid-label {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
  font-weight: 700;
  color: var(--color-on-surface-variant, #666);
  user-select: none;
}

.mermaid-actions {
  display: flex;
  gap: 2px;
}

.mermaid-action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 6px;
  color: var(--color-on-surface-variant, #666);
  transition: all 0.15s;
}
.mermaid-action-btn:hover {
  background: var(--color-surface-container-highest, #e0e0e0);
  color: var(--color-on-surface, #333);
}
.mermaid-action-btn.active {
  color: var(--color-primary, #6750a4);
}
.mermaid-action-btn.delete:hover {
  background: #fce4ec;
  color: #c62828;
}

.mermaid-code-area {
  padding: 0;
}

.mermaid-textarea {
  display: block;
  width: 100%;
  min-height: 120px;
  padding: 14px 16px;
  border: none;
  outline: none;
  resize: vertical;
  font-family: 'SF Mono', 'Fira Code', 'Cascadia Code', Consolas, monospace;
  font-size: 13px;
  line-height: 1.6;
  color: var(--color-on-surface, #333);
  background: var(--color-surface-container-low, #fafafa);
  tab-size: 2;
}
.mermaid-textarea::placeholder {
  color: var(--color-on-surface-variant, #999);
  opacity: 0.5;
}

.mermaid-preview {
  min-height: 80px;
  transition: background 0.15s;
}

.mermaid-svg {
  display: flex;
  justify-content: center;
  padding: 16px;
  overflow-x: auto;
}
.mermaid-svg :deep(svg) {
  max-width: 100%;
  height: auto;
}

.mermaid-error {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 14px 16px;
  color: #c62828;
  font-size: 12px;
}

.mermaid-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 24px;
  color: var(--color-on-surface-variant, #999);
  opacity: 0.5;
  font-size: 13px;
}
</style>