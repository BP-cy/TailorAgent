<script setup lang="ts">
import { ref } from 'vue'
import { useKnowledgeStore, type KbNode, type KbStatus } from '@/stores/knowledge'
import KbIcon from './KbIcon.vue'
import FileTypeIcon from './FileTypeIcon.vue'

defineOptions({ name: 'KnowledgeTree' })

// 展开态放 store：跨重挂载（reload / 进出编辑页）保持不折叠
const store = useKnowledgeStore()

defineProps<{ nodes: KbNode[]; depth: number }>()
const emit = defineEmits<{
  (e: 'select', n: KbNode): void
  (e: 'edit', n: KbNode): void
  (e: 'rename', n: KbNode): void
  (e: 'renameTo', payload: { from: string; to: string }): void
  (e: 'remove', n: KbNode): void
  (e: 'newDoc', folder: string): void
  (e: 'newFolder', folder: string): void
  (e: 'importFiles', folder: string): void
  (e: 'dropFiles', payload: { folder: string; files: File[] }): void
  (e: 'move', payload: { from: string; to: string }): void
}>()

// 拖拽悬停高亮的文件夹 path（当前实例内）
const dragOver = ref<string | null>(null)

// 内联重命名：正在编辑的节点 path + 编辑中的名字
const editingPath = ref<string | null>(null)
const editingName = ref('')

// 每级缩进（px）：图标本身对齐到父级图标右侧一格
const INDENT = 20

// 文件夹图标：viewBox 0 0 1024 1024，未打开 / 打开两套线性图标
const FOLDER_CLOSED =
  'M880 298.4H521L403.7 186.2c-1.5-1.4-3.5-2.2-5.5-2.2H144c-17.7 0-32 14.3-32 32v592c0 17.7 14.3 32 32 32h736c17.7 0 32-14.3 32-32V330.4c0-17.7-14.3-32-32-32zM840 768H184V256h188.5l119.6 114.4H840V768z'
const FOLDER_OPEN =
  'M928 444H820V330.4c0-17.7-14.3-32-32-32H473L355.7 186.2c-1.5-1.4-3.5-2.2-5.5-2.2H96c-17.7 0-32 14.3-32 32v592c0 17.7 14.3 32 32 32h698c13 0 24.8-7.9 29.7-20l134-332c1.5-3.8 2.3-7.9 2.3-12 0-17.7-14.3-32-32-32zM136 256h188.5l119.6 114.4H748V444H238c-13 0-24.8 7.9-29.7 20L136 643.2V256z m635.3 512H159l103.3-256h612.4L771.3 768z'

const statusMeta: Record<KbStatus, { text: string; cls: string }> = {
  unindexed: { text: '未索引', cls: 'bg-surface-container text-on-surface-variant/70' },
  processing: { text: '处理中', cls: 'bg-amber-100 text-amber-700' },
  indexed: { text: '已索引', cls: 'bg-green-100 text-green-700' },
  failed: { text: '索引失败', cls: 'bg-red-100 text-red-700' },
}

// ── 内联重命名 ───────────────────────────────────────
function startEdit(n: KbNode) {
  editingPath.value = n.path
  editingName.value = n.name
}
function focusInput(el: HTMLInputElement | null) {
  if (el && document.activeElement !== el) {
    el.focus()
    el.select()
  }
}
function commitEdit(n: KbNode) {
  if (editingPath.value !== n.path) return
  const name = editingName.value.trim()
  editingPath.value = null
  if (!name || name === n.name || name.includes('/')) return
  // 同层换名：取父目录 + 新名（所有路径均以 MD/ 或 files/ 开头，故必含 '/'）
  const dir = n.path.slice(0, n.path.lastIndexOf('/'))
  emit('renameTo', { from: n.path, to: `${dir}/${name}` })
}
function cancelEdit() {
  editingPath.value = null
}

// ── 拖拽 ─────────────────────────────────────────────
function onDragStart(ev: DragEvent, n: KbNode) {
  if (!ev.dataTransfer) return
  ev.dataTransfer.setData('application/x-kb-path', n.path)
  ev.dataTransfer.effectAllowed = 'move'
}
function onFolderDragOver(ev: DragEvent, folder: KbNode) {
  ev.preventDefault()
  // 从操作系统拖入的真实文件 → copy（导入）；内部节点 → move
  if (ev.dataTransfer) {
    ev.dataTransfer.dropEffect = ev.dataTransfer.types.includes('Files') ? 'copy' : 'move'
  }
  dragOver.value = folder.path
}
function onFolderDrop(ev: DragEvent, folder: KbNode) {
  ev.preventDefault()
  dragOver.value = null
  const files = Array.from(ev.dataTransfer?.files || [])
  if (files.length) {
    emit('dropFiles', { folder: folder.path, files })
    return
  }
  const from = ev.dataTransfer?.getData('application/x-kb-path')
  if (from) emit('move', { from, to: folder.path })
}
</script>

<template>
  <div class="flex flex-col gap-0.5">
    <template v-for="n in nodes" :key="n.path">
      <!-- 文件夹 -->
      <div v-if="n.type === 'folder'" class="select-none">
        <div
          class="group flex h-10 items-center gap-2.5 rounded-lg px-2.5 cursor-pointer transition-colors"
          :class="dragOver === n.path ? 'bg-primary/10' : 'hover:bg-surface-container-low'"
          :style="{ paddingLeft: depth * INDENT + 10 + 'px' }"
          draggable="true"
          @click="store.toggleExpanded(n.path)"
          @dragstart.stop="onDragStart($event, n)"
          @dragover="onFolderDragOver($event, n)"
          @dragleave="dragOver = null"
          @drop.stop="onFolderDrop($event, n)"
        >
          <svg viewBox="0 0 1024 1024" class="w-[22px] h-[22px] flex-shrink-0 fill-current text-gray-600" aria-hidden="true">
            <path :d="store.expanded[n.path] ? FOLDER_OPEN : FOLDER_CLOSED" />
          </svg>
          <input
            v-if="editingPath === n.path"
            v-model="editingName"
            :ref="(el) => focusInput(el as HTMLInputElement | null)"
            class="flex-1 min-w-0 rounded border border-primary/50 bg-white px-1.5 py-0.5 text-[15px] font-medium text-on-surface outline-none"
            @click.stop
            @keyup.enter="commitEdit(n)"
            @keyup.esc="cancelEdit"
            @blur="commitEdit(n)"
          />
          <span
            v-else
            class="text-[15px] font-medium truncate flex-1"
            title="双击重命名"
            @dblclick.stop.prevent="startEdit(n)"
          >{{ n.name }}</span>
          <div class="flex-shrink-0 items-center gap-0.5 hidden group-hover:flex" @click.stop>
            <button type="button" title="在此新建文档"
              class="rounded-md p-1 text-on-surface-variant/60 hover:bg-surface-container hover:text-primary" @click="emit('newDoc', n.path)">
              <KbIcon name="new-doc" class="w-[18px] h-[18px]" />
            </button>
            <button type="button" title="在此新建文件夹"
              class="rounded-md p-1 text-on-surface-variant/60 hover:bg-surface-container hover:text-primary" @click="emit('newFolder', n.path)">
              <KbIcon name="new-folder" class="w-[18px] h-[18px]" />
            </button>
            <button type="button" title="导入文件到此"
              class="rounded-md p-1 text-on-surface-variant/60 hover:bg-surface-container hover:text-primary" @click="emit('importFiles', n.path)">
              <KbIcon name="import-file" class="w-[18px] h-[18px]" />
            </button>
            <button type="button" title="移动"
              class="rounded-md p-1 text-on-surface-variant/60 hover:bg-surface-container hover:text-primary" @click="emit('rename', n)">
              <KbIcon name="move" class="w-[18px] h-[18px]" />
            </button>
            <button type="button" title="删除"
              class="rounded-md p-1 text-on-surface-variant/60 hover:bg-surface-container hover:text-red-500" @click="emit('remove', n)">
              <KbIcon name="delete" class="w-[18px] h-[18px]" />
            </button>
          </div>
        </div>
        <!-- 递归子节点 -->
        <KnowledgeTree
          v-if="store.expanded[n.path] && n.children && n.children.length"
          :nodes="n.children"
          :depth="depth + 1"
          class="mt-0.5"
          @select="emit('select', $event)"
          @edit="emit('edit', $event)"
          @rename="emit('rename', $event)"
          @rename-to="emit('renameTo', $event)"
          @remove="emit('remove', $event)"
          @new-doc="emit('newDoc', $event)"
          @new-folder="emit('newFolder', $event)"
          @import-files="emit('importFiles', $event)"
          @drop-files="emit('dropFiles', $event)"
          @move="emit('move', $event)"
        />
      </div>

      <!-- 文件 -->
      <div
        v-else
        class="group flex h-10 items-center gap-2.5 rounded-lg px-2.5 hover:bg-surface-container-low"
        :class="n.type === 'md' ? 'cursor-pointer' : ''"
        :style="{ paddingLeft: depth * INDENT + 10 + 'px' }"
        draggable="true"
        @click="n.type === 'md' && emit('select', n)"
        @dragstart.stop="onDragStart($event, n)"
      >
        <KbIcon v-if="n.type === 'md'" name="md" class="w-[22px] h-[22px] flex-shrink-0 text-sky-500" />
        <FileTypeIcon v-else :name="n.name" class="w-[22px] h-[22px] flex-shrink-0" />
        <span class="text-[15px] truncate flex-1">{{ n.name }}</span>
        <span v-if="n.status" class="flex-shrink-0 rounded px-1.5 py-0.5 text-[11px] font-medium"
              :class="statusMeta[n.status].cls">
          {{ statusMeta[n.status].text }}
        </span>
        <div class="flex-shrink-0 items-center gap-0.5 hidden group-hover:flex" @click.stop>
          <button v-if="n.type === 'md'" type="button" title="编辑"
            class="rounded-md p-1 text-on-surface-variant/60 hover:bg-surface-container hover:text-primary" @click="emit('edit', n)">
            <KbIcon name="edit" class="w-[18px] h-[18px]" />
          </button>
          <button type="button" title="移动"
            class="rounded-md p-1 text-on-surface-variant/60 hover:bg-surface-container hover:text-primary" @click="emit('rename', n)">
            <KbIcon name="move" class="w-[18px] h-[18px]" />
          </button>
          <button type="button" title="删除"
            class="rounded-md p-1 text-on-surface-variant/60 hover:bg-surface-container hover:text-red-500" @click="emit('remove', n)">
            <KbIcon name="delete" class="w-[18px] h-[18px]" />
          </button>
        </div>
      </div>
    </template>
  </div>
</template>
