<script setup lang="ts">
import { computed, ref, onMounted, onBeforeUnmount, watch } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useToast } from '@/composables/useToast'
import { useConfirm } from '@/composables/useConfirm'
import { useKnowledgeStore, type KbNode } from '../stores/knowledge'
import { useUiStore } from '../stores/ui'
import KnowledgeTree from './KnowledgeTree.vue'
import KbIcon from './KbIcon.vue'
import FileTypeIcon from './FileTypeIcon.vue'

const router = useRouter()
const store = useKnowledgeStore()
const ui = useUiStore()
const { showToast } = useToast()
const { prompt } = useConfirm()
const { mdTree, fileTree, loading, indexJob } = storeToRefs(store)
const { kbTab } = storeToRefs(ui)

const keyword = ref('')
// 点击构建索引后立即切换工具栏 UI，避免等待启动请求返回期间按钮仍可重复点击。
const indexStarting = ref(false)
// 文件导入：隐藏 input + 待落盘目录（点按钮时记下，选完文件后据此上传）
const fileInput = ref<HTMLInputElement | null>(null)
const pendingDir = ref<string>('')
// 删除确认弹窗（按路径）
const deleteDialog = ref<KbNode | null>(null)
const skipDeleteConfirm = ref(false)
const dontShowAgain = ref(false)

// 当前 tab → 子树 / 树数据
const subtree = computed<'MD' | 'files'>(() => (kbTab.value === 'markdown' ? 'MD' : 'files'))
const currentTree = computed(() => (kbTab.value === 'markdown' ? mdTree.value : fileTree.value))
const indexing = computed(() => {
  const state = indexJob.value?.state
  return state === 'queued' || state === 'running'
})
const indexBusy = computed(() => indexStarting.value || indexing.value)
const indexProgressPercent = computed(() => {
  if (indexStarting.value && !indexing.value) return 0
  const job = indexJob.value
  if (!job) return 0
  if (job.state === 'completed') return 100
  if (job.totalFiles <= 0) return 0
  return Math.min(100, Math.round((job.completedFiles / job.totalFiles) * 100))
})
const indexProgressTitle = computed(() => {
  const job = indexJob.value
  if (indexStarting.value && !indexing.value) return '正在启动索引'
  if (!job) return '正在准备索引'
  if (job.state === 'queued') return '索引任务已加入队列'
  if (job.state === 'failed') return '索引失败'
  if (job.state === 'completed') return '索引完成'
  if (job.phase === 'planning') return '正在规划索引文件队列'
  if (job.phase === 'writing') return `文件准备完成 ${job.completedFiles} / ${job.totalFiles}`
  const current = Math.min(job.completedFiles + 1, Math.max(job.totalFiles, 1))
  return `正在索引 ${current} / ${job.totalFiles}`
})

let indexPollTimer: ReturnType<typeof setTimeout> | null = null
let pollingJobId = ''
let notifiedTerminalJobId = ''

function stopIndexPolling() {
  if (indexPollTimer !== null) {
    clearTimeout(indexPollTimer)
    indexPollTimer = null
  }
  pollingJobId = ''
}

function scheduleIndexPoll(jobId: string) {
  if (indexPollTimer !== null) clearTimeout(indexPollTimer)
  pollingJobId = jobId
  indexPollTimer = setTimeout(() => pollIndexJob(jobId), 600)
}

async function finishIndexJob(jobId: string) {
  stopIndexPolling()
  const job = indexJob.value
  if (!job || job.jobId !== jobId || notifiedTerminalJobId === jobId) return
  notifiedTerminalJobId = jobId
  if (job.state === 'completed') {
    showToast(job.message || '知识库索引完成', 'success')
  } else if (job.state === 'failed') {
    showToast(job.error || job.message || '知识库索引失败', 'error')
  }
  await store.loadAll()
}

async function pollIndexJob(jobId: string) {
  if (pollingJobId !== jobId) return
  try {
    const job = await store.refreshIndexJob(jobId)
    if (job.state === 'queued' || job.state === 'running') {
      scheduleIndexPoll(jobId)
    } else {
      await finishIndexJob(jobId)
    }
  } catch (err) {
    stopIndexPolling()
    showToast((err as Error).message || '查询索引进度失败', 'error')
  }
}

onMounted(async () => {
  await store.loadTree(subtree.value)
  try {
    const job = await store.restoreIndexJob()
    if (job && (job.state === 'queued' || job.state === 'running')) {
      scheduleIndexPoll(job.jobId)
    }
  } catch (err) {
    showToast((err as Error).message || '恢复索引进度失败', 'warning')
  }
})
onBeforeUnmount(stopIndexPolling)
watch(subtree, (t) => store.loadTree(t))

// 搜索：有关键词时把当前子树拍平成匹配文件列表
function flatten(nodes: KbNode[], acc: KbNode[] = []): KbNode[] {
  for (const n of nodes) {
    if (n.type === 'folder') flatten(n.children || [], acc)
    else acc.push(n)
  }
  return acc
}
const searchResults = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return []
  return flatten(currentTree.value).filter((n) => n.name.toLowerCase().includes(kw))
})

// ── 导航 ─────────────────────────────────────────────
function openDoc(n: KbNode) {
  router.push({ name: 'reader', params: { path: n.path } })
}
function editDoc(n: KbNode) {
  router.push({ name: 'editor', params: { path: n.path } })
}

// ── 增删改 ───────────────────────────────────────────
async function reload() {
  await store.loadTree(subtree.value)
}

// 新建文档：不弹窗，直接进编辑页（草稿态）。标题即文件名，编辑页有标题才允许保存。
// folder 作为落盘目录（默认 MD 根）通过 query.dir 传给编辑页。
function newDoc(folder: string) {
  router.push({ name: 'editor', query: { dir: folder } })
}

// ── 文件导入 ─────────────────────────────────────────
// 点「导入文件」：记下目标目录，打开文件选择器。
function importFiles(folder: string) {
  pendingDir.value = folder
  fileInput.value?.click()
}
// 选完文件 → 上传到 pendingDir。
async function onFileChange(ev: Event) {
  const input = ev.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = '' // 复位，允许再次选同一文件
  if (files.length) await doUpload(pendingDir.value, files)
}
// 统一上传入口（按钮选择 / 拖入共用）。
async function doUpload(folder: string, files: File[]) {
  if (!files.length) return
  try {
    const n = await store.uploadFiles(folder, files)
    showToast(`已导入 ${n} 个文件`, 'success')
    await reload()
  } catch (err) {
    showToast((err as Error).message || '导入文件失败', 'error')
  }
}

// 定位某父路径（folder）下的直接子节点列表；根（MD/files）返回当前整棵子树。
function findChildren(nodes: KbNode[], parentPath: string): KbNode[] | null {
  if (parentPath === subtree.value) return currentTree.value
  for (const n of nodes) {
    if (n.type !== 'folder') continue
    if (n.path === parentPath) return n.children || []
    const found = findChildren(n.children || [], parentPath)
    if (found) return found
  }
  return null
}

// 新建文件夹：不弹窗，自动取「新建文件夹N」（N 为该层未占用的最小序号）。
async function newFolder(folder: string) {
  const kids = findChildren(currentTree.value, folder) || []
  const used = new Set(kids.filter((k) => k.type === 'folder').map((k) => k.name))
  let i = 1
  while (used.has(`新建文件夹${i}`)) i++
  try {
    await store.mkdir(`${folder}/新建文件夹${i}`)
    await reload()
  } catch (err) {
    showToast((err as Error).message || '新建文件夹失败', 'error')
  }
}

// 双击文件夹名内联重命名：直接改到目标全路径（同层换名）。
async function renameTo({ from, to }: { from: string; to: string }) {
  if (to === from) return
  try {
    await store.rename(from, to)
    await reload()
  } catch (err) {
    showToast((err as Error).message || '重命名失败', 'error')
  }
}

async function renameNode(n: KbNode) {
  const to = await prompt({ message: '重命名 / 移动到（知识库内相对路径）', defaultValue: n.path })
  if (!to || !to.trim() || to.trim() === n.path) return
  try {
    await store.rename(n.path, to.trim())
    await reload()
  } catch (err) {
    showToast((err as Error).message || '移动失败', 'error')
  }
}

// 拖拽移动：把 from 移入目标文件夹 to（to 为文件夹相对路径，根为 'MD'/'files'）
async function moveNode({ from, to }: { from: string; to: string }) {
  const base = from.split('/').pop() || from
  const dest = `${to}/${base}`
  if (dest === from) return // 已在该文件夹下，无需移动
  if (to === from || to.startsWith(from + '/')) {
    showToast('不能移动到自身或其子目录', 'warning')
    return
  }
  try {
    await store.rename(from, dest)
    await reload()
  } catch (err) {
    showToast((err as Error).message || '移动失败', 'error')
  }
}

// 拖到空白处 → 从操作系统拖入的真实文件导入到当前子树根；否则内部节点移动到根
const rootDragOver = ref(false)
function onRootDrop(ev: DragEvent) {
  ev.preventDefault()
  rootDragOver.value = false
  const files = Array.from(ev.dataTransfer?.files || [])
  if (files.length) {
    doUpload(subtree.value, files)
    return
  }
  const from = ev.dataTransfer?.getData('application/x-kb-path')
  if (from) moveNode({ from, to: subtree.value })
}

async function buildIndex() {
  if (indexBusy.value) return
  indexStarting.value = true
  try {
    const job = await store.triggerIndex()
    notifiedTerminalJobId = ''
    if (job.state === 'queued' || job.state === 'running') {
      scheduleIndexPoll(job.jobId)
    } else {
      await finishIndexJob(job.jobId)
    }
  } catch (err) {
    showToast((err as Error).message || '索引失败', 'error')
  } finally {
    indexStarting.value = false
  }
}

// 删除确认
function openDeleteDialog(n: KbNode) {
  if (skipDeleteConfirm.value) {
    doDelete(n)
    return
  }
  dontShowAgain.value = false
  deleteDialog.value = n
}
async function confirmDelete() {
  if (dontShowAgain.value) skipDeleteConfirm.value = true
  const n = deleteDialog.value
  deleteDialog.value = null
  if (n) await doDelete(n)
}
function cancelDelete() {
  deleteDialog.value = null
  dontShowAgain.value = false
}
async function doDelete(n: KbNode) {
  try {
    await store.remove(n.path)
    await reload()
  } catch (err) {
    showToast((err as Error).message || '删除失败', 'error')
  }
}
</script>

<template>
  <div class="flex-1 min-w-0 h-full overflow-auto px-8 py-6">
    <!-- 隐藏文件选择器（导入文件用） -->
    <input ref="fileInput" type="file" multiple class="hidden" @change="onFileChange" />

    <!-- 头部 -->
    <header class="flex flex-col gap-3 mb-5">
      <!-- 第一行：搜索 + 操作 -->
      <div class="flex items-center justify-end gap-3">
        <div class="relative">
          <span class="material-symbols-outlined text-[18px] absolute left-2.5 top-1/2 -translate-y-1/2 text-on-surface-variant/60">search</span>
          <input
            v-model="keyword"
            type="text"
            placeholder="搜索文档"
            class="h-9 w-52 rounded-lg border border-outline-variant bg-white pl-9 pr-3 text-sm outline-none transition-colors focus:border-primary"
          />
        </div>

        <!-- 构建期间在原按钮位置显示紧凑进度，进入成功/失败终态后自动恢复按钮。 -->
        <div
          v-if="indexBusy"
          class="flex h-9 w-44 flex-col justify-center rounded-lg border border-primary/20 bg-primary/5 px-3"
          role="status"
          aria-live="polite"
        >
          <div class="flex items-center justify-between gap-2 text-[11px] leading-none">
            <span class="min-w-0 truncate text-on-surface" :title="indexProgressTitle">
              {{ indexProgressTitle }}
            </span>
            <span class="flex-shrink-0 tabular-nums text-on-surface-variant">
              {{ indexProgressPercent }}%
            </span>
          </div>
          <div class="mt-1.5 h-1 overflow-hidden rounded-full bg-primary/15">
            <div
              class="h-full rounded-full bg-primary transition-[width] duration-300"
              :style="{ width: `${indexProgressPercent}%` }"
            ></div>
          </div>
        </div>

        <button
          v-else
          type="button"
          class="flex h-9 items-center gap-1.5 rounded-lg border border-outline-variant bg-white px-3 text-sm text-on-surface transition-colors hover:bg-surface-container disabled:opacity-50"
          @click="buildIndex"
        >
          <KbIcon name="build-index" class="w-[18px] h-[18px]" />
          构建索引
        </button>

        <button
          type="button"
          class="flex h-9 items-center gap-1.5 rounded-lg border border-outline-variant bg-white px-3 text-sm text-on-surface transition-colors hover:bg-surface-container"
          @click="newFolder(subtree)"
        >
          <KbIcon name="new-folder" class="w-[18px] h-[18px]" />
          新建文件夹
        </button>

        <button
          type="button"
          class="flex h-9 items-center gap-1.5 rounded-lg border border-outline-variant bg-white px-3 text-sm text-on-surface transition-colors hover:bg-surface-container"
          @click="importFiles(subtree)"
        >
          <KbIcon name="import-file" class="w-[18px] h-[18px]" />
          导入文件
        </button>

        <button
          v-if="kbTab === 'markdown'"
          type="button"
          class="flex h-9 items-center gap-1.5 rounded-lg bg-primary px-3 text-sm text-on-primary transition-colors hover:bg-primary-container"
          @click="newDoc(subtree)"
        >
          <KbIcon name="new-doc" class="w-[18px] h-[18px]" />
          新建文档
        </button>
      </div>

      <!-- 第二行：MD/文件切换 -->
      <div class="flex justify-center">
        <div class="inline-flex rounded-lg bg-surface-container p-1">
          <button
            type="button"
            class="px-4 py-1.5 rounded-md text-sm font-medium transition-colors"
            :class="kbTab === 'markdown' ? 'bg-white text-primary shadow-sm' : 'text-on-surface-variant'"
            @click="kbTab = 'markdown'"
          >
            MarkDown
          </button>
          <button
            type="button"
            class="px-4 py-1.5 rounded-md text-sm font-medium transition-colors"
            :class="kbTab === 'file' ? 'bg-white text-primary shadow-sm' : 'text-on-surface-variant'"
            @click="kbTab = 'file'"
          >
            文件
          </button>
        </div>
      </div>
    </header>

    <!-- 加载中 -->
    <div v-if="loading" class="text-center py-16 text-sm text-on-surface-variant/50">
      <span class="material-symbols-outlined text-2xl mb-2 block animate-spin">progress_activity</span>
      加载中...
    </div>

    <!-- 搜索结果（拍平） -->
    <div v-else-if="keyword.trim()" class="flex flex-col gap-0.5">
      <div
        v-for="n in searchResults"
        :key="n.path"
        class="group flex items-center gap-2 rounded-md px-2 py-2 hover:bg-surface-container-low"
        :class="n.type === 'md' ? 'cursor-pointer' : ''"
        @click="n.type === 'md' && openDoc(n)"
      >
        <KbIcon v-if="n.type === 'md'" name="md" class="w-[18px] h-[18px] text-sky-500" />
        <FileTypeIcon v-else :name="n.name" class="w-[18px] h-[18px]" />
        <div class="flex-1 min-w-0">
          <div class="text-sm truncate">{{ n.name }}</div>
          <div class="text-xs text-on-surface-variant/50 truncate">{{ n.path }}</div>
        </div>
        <div class="flex-shrink-0 items-center gap-1 hidden group-hover:flex" @click.stop>
          <button v-if="n.type === 'md'" type="button" title="编辑"
            class="rounded p-0.5 text-on-surface-variant/60 hover:text-primary" @click="editDoc(n)">
            <KbIcon name="edit" class="w-4 h-4" />
          </button>
          <button type="button" title="删除"
            class="rounded p-0.5 text-on-surface-variant/60 hover:text-red-500" @click="openDeleteDialog(n)">
            <KbIcon name="delete" class="w-4 h-4" />
          </button>
        </div>
      </div>
      <div v-if="searchResults.length === 0" class="text-center py-16 text-sm text-on-surface-variant/60">
        没有匹配的文档
      </div>
    </div>

    <!-- 目录树 -->
    <div
      v-else
      @dragover.prevent="rootDragOver = true"
      @dragleave="rootDragOver = false"
      @drop="onRootDrop"
    >
      <KnowledgeTree
        :nodes="currentTree"
        :depth="0"
        @select="openDoc"
        @edit="editDoc"
        @rename="renameNode"
        @rename-to="renameTo"
        @remove="openDeleteDialog"
        @new-doc="newDoc"
        @new-folder="newFolder"
        @import-files="importFiles"
        @drop-files="({ folder, files }) => doUpload(folder, files)"
        @move="moveNode"
      />
      <div v-if="currentTree.length === 0" class="text-center py-16 text-sm text-on-surface-variant/60">
        {{ kbTab === 'markdown' ? '暂无 Markdown 文档，点击右上角新建' : '暂无文件' }}
      </div>
    </div>

    <!-- 删除确认弹窗 -->
    <Teleport to="body">
      <div
        v-if="deleteDialog"
        class="fixed inset-0 z-50 flex items-center justify-center"
        @click.self="cancelDelete"
      >
        <div class="absolute inset-0 bg-black/30"></div>
        <div class="relative bg-white rounded-xl shadow-lg px-6 py-5 w-80 flex flex-col gap-4">
          <p class="text-sm text-on-surface">
            确认删除
            <span class="font-medium">{{ deleteDialog.name }}</span>
            {{ deleteDialog.type === 'folder' ? '（含其下全部内容）' : '' }}？
          </p>
          <label class="flex items-center gap-2 cursor-pointer">
            <input
              v-model="dontShowAgain"
              type="checkbox"
              class="w-4 h-4 rounded border-outline-variant text-primary focus:ring-primary"
            />
            <span class="text-xs text-on-surface-variant">本次使用不再进行删除操作提醒</span>
          </label>
          <div class="flex justify-end gap-2">
            <button
              type="button"
              class="rounded-md border border-outline-variant bg-white px-4 py-1.5 text-sm text-on-surface-variant transition-colors hover:bg-surface-container-high"
              @click="cancelDelete"
            >
              取消
            </button>
            <button
              type="button"
              class="rounded-md bg-red-500 px-4 py-1.5 text-sm text-white transition-colors hover:bg-red-600"
              @click="confirmDelete"
            >
              确认删除
            </button>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>
