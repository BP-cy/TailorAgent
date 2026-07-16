import { defineStore } from 'pinia'
import { ref } from 'vue'
import {
  fetchKbTree,
  fetchKbDoc,
  createKbDoc,
  saveKbDoc,
  mkdirKb,
  renameKb,
  deleteKb,
  indexKb,
  fetchKbIndexJob,
  fetchActiveKbIndexJob,
  uploadKbFiles,
  type KbNode,
  type KbDoc,
  type KnowledgeIndexJobStatus,
} from '@/api'
import { useToast } from '@/composables/useToast'

export type { KbNode, KbDoc, KbStatus, KbNodeType } from '@/api'

/** 由相对路径判断所属子树（MD / files） */
export function kbSubtree(path: string): 'MD' | 'files' {
  return path === 'files' || path.startsWith('files/') ? 'files' : 'MD'
}

export const useKnowledgeStore = defineStore('knowledge', () => {
  const mdTree = ref<KbNode[]>([])
  const fileTree = ref<KbNode[]>([])
  const loading = ref(false)
  const indexJob = ref<KnowledgeIndexJobStatus | null>(null)

  // 文件夹展开态（按相对路径；MD/ 与 files/ 前缀天然隔离）。
  // 放 store 而非组件内 ref：跨组件重挂载（reload 时 loading 切换、进出编辑页）保持展开状态。
  const expanded = ref<Record<string, boolean>>({})
  function toggleExpanded(path: string) {
    expanded.value[path] = !expanded.value[path]
  }

  /** 加载某子树目录树（后端扫描磁盘派生 + reconcile catalog） */
  async function loadTree(type: 'MD' | 'files' = 'MD') {
    loading.value = true
    try {
      const nodes = await fetchKbTree(type)
      if (type === 'files') fileTree.value = nodes
      else mdTree.value = nodes
    } catch (err) {
      const { showToast } = useToast()
      showToast((err as Error).message || '加载目录树失败', 'error')
    } finally {
      loading.value = false
    }
  }

  async function loadAll() {
    await Promise.all([loadTree('MD'), loadTree('files')])
  }

  /** 读取单篇正文（打开阅读/编辑页时调用） */
  async function readDoc(path: string): Promise<KbDoc> {
    return fetchKbDoc(path)
  }

  /** 新建文档 */
  async function createDoc(path: string, content = ''): Promise<KbDoc> {
    return createKbDoc(path, content)
  }

  /** 保存文档（人工编辑器保存；AI 编辑走独立接口，不经此） */
  async function saveDoc(path: string, content: string): Promise<void> {
    await saveKbDoc(path, content)
  }

  /** 新建文件夹 */
  async function mkdir(path: string): Promise<void> {
    await mkdirKb(path)
  }

  /** 导入文件到某文件夹（folder = 'MD'/'files' 或其下相对目录），返回成功数 */
  async function uploadFiles(folder: string, files: File[]): Promise<number> {
    return uploadKbFiles(folder, files)
  }

  /** 移动 / 重命名 */
  async function rename(from: string, to: string): Promise<void> {
    await renameKb(from, to)
  }

  /** 删除文档或文件夹 */
  async function remove(path: string): Promise<void> {
    await deleteKb(path)
  }

  /** 启动异步索引；后端已有活动任务时会返回原任务，避免重复入队。 */
  async function triggerIndex(path?: string): Promise<KnowledgeIndexJobStatus> {
    const job = await indexKb(path)
    indexJob.value = job
    return job
  }

  /** 刷新指定任务进度。 */
  async function refreshIndexJob(jobId: string): Promise<KnowledgeIndexJobStatus> {
    const job = await fetchKbIndexJob(jobId)
    indexJob.value = job
    return job
  }

  /** 恢复当前活动任务；Pinia 中已有未完成任务时优先按原 jobId 查询。 */
  async function restoreIndexJob(): Promise<KnowledgeIndexJobStatus | null> {
    const current = indexJob.value
    if (current && (current.state === 'queued' || current.state === 'running')) {
      return refreshIndexJob(current.jobId)
    }
    const active = await fetchActiveKbIndexJob()
    if (active) indexJob.value = active
    return active
  }

  return {
    mdTree, fileTree, loading, indexJob, expanded, toggleExpanded,
    loadTree, loadAll, readDoc, createDoc, saveDoc, mkdir, uploadFiles, rename, remove,
    triggerIndex, refreshIndexJob, restoreIndexJob,
  }
})
