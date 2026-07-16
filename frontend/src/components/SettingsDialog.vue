<script setup lang="ts">
import { computed, reactive, ref, watch, onUnmounted } from 'vue'
import { useToast } from '@/composables/useToast'
import { useConfirm } from '@/composables/useConfirm'
import ModelIcon from '@/components/icons/ModelIcon.vue'
import McpIcon from '@/components/icons/McpIcon.vue'
import EnvIcon from '@/components/icons/EnvIcon.vue'
import SkillIcon from '@/components/icons/SkillIcon.vue'
import KbIcon from '@/components/KbIcon.vue'
import {
  fetchProviders,
  fetchConfig,
  saveConfig,
  testChatModel,
  testEmbeddingModel,
  fetchMcpStatus,
  fetchKbMcpStatus,
  fetchRuntimes,
  installRuntime,
  fetchSkills,
  importSkillFiles,
  importSkillDir,
  deleteSkill,
  fetchKbSkills,
  importKbSkillFiles,
  importKbSkillDir,
  deleteKbSkill,
  uninstallApp,
  type ProviderInfo,
  type AppConfig,
  type ChatModelCfg,
  type OCRModelCfg,
  type EmbeddingModelCfg,
  type McpServerCfg,
  type McpServerStatus,
  type RuntimeStatus,
  type SkillInfo,
  type SkillImportFile,
} from '../api'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'close'): void; (e: 'saved'): void }>()

const { showToast } = useToast()
const { confirm } = useConfirm()

// --- 基础数据 ---
const providers = ref<ProviderInfo[]>([])
const emptyEmbedding = (): EmbeddingModelCfg => ({
  baseUrl: '', modelName: '', apiKey: '', dimensions: null, batchSize: 10,
})
const appConfig = ref<AppConfig>({
  availableChatModels: [], availableOCRModels: [], embeddingModel: emptyEmbedding(),
})

// --- 知识库 Embedding 模型状态（独立于对话/OCR 模型） ---
const embeddingTesting = ref(false)
const embeddingStatus = ref<'ok' | 'fail' | null>(null)
const embeddingStatusMessage = ref('')
const embeddingExpanded = ref(false)
const embeddingConfigured = computed(() => Boolean(
  appConfig.value.embeddingModel.baseUrl?.trim()
  && appConfig.value.embeddingModel.modelName?.trim(),
))
const embeddingSummaryText = computed(() => {
  if (embeddingTesting.value) return '测试中'
  if (embeddingStatus.value === 'ok') return '连接正常'
  if (embeddingStatus.value === 'fail') return '连接失败'
  return embeddingConfigured.value ? '未测试' : '未配置'
})
const embeddingStatusDotClass = computed(() => {
  if (embeddingTesting.value) return 'bg-blue-400 animate-pulse'
  if (embeddingStatus.value === 'ok') return 'bg-green-500'
  if (embeddingStatus.value === 'fail') return 'bg-red-500'
  return embeddingConfigured.value ? 'bg-amber-400' : 'bg-gray-300'
})

function validateEmbeddingForm(): boolean {
  const model = appConfig.value.embeddingModel
  if (!model.baseUrl.trim() || !model.modelName.trim()) {
    showToast('请填写 Embedding Base URL 和模型名', 'warning')
    return false
  }
  const batchSize = Number(model.batchSize ?? 10)
  if (!Number.isInteger(batchSize) || batchSize < 1 || batchSize > 2048) {
    showToast('单次向量化条数必须是 1 到 2048 之间的整数', 'warning')
    return false
  }
  model.batchSize = batchSize
  return true
}

async function testEmbedding() {
  const model = appConfig.value.embeddingModel
  if (!validateEmbeddingForm()) return
  embeddingTesting.value = true
  embeddingStatus.value = null
  try {
    const result = await testEmbeddingModel(model)
    embeddingStatus.value = result.ok ? 'ok' : 'fail'
    embeddingStatusMessage.value = result.message
    showToast(result.message, result.ok ? 'success' : 'error')
  } catch (e) {
    embeddingStatus.value = 'fail'
    embeddingStatusMessage.value = (e as Error).message
    showToast('Embedding 连接失败: ' + (e as Error).message, 'error')
  } finally {
    embeddingTesting.value = false
  }
}

async function saveEmbedding() {
  if (!validateEmbeddingForm()) return
  if (await doSave()) {
    embeddingExpanded.value = false
    showToast('Embedding 模型已保存；模型或维度变化后请重新构建知识库索引', 'success')
  }
}

// --- 添加表单状态 ---
const showAddForm = ref(false)
const addMode = ref<'preset' | 'custom'>('preset')
const selectedPresetId = ref('')
const newDraft = reactive<ChatModelCfg>({
  baseUrl: '', modelName: '', apiKey: '', displayName: '', source: 'preset',
  contextLength: 200000, maxInputTokens: 168000, maxOutputTokens: 32000,
})

// --- 内联编辑状态（对话模型） ---
const editingIndex = ref<number | null>(null)
const editDraft = reactive<ChatModelCfg>({
  baseUrl: '', modelName: '', apiKey: '', displayName: '', source: 'preset',
  contextLength: 200000, maxInputTokens: 168000, maxOutputTokens: 32000,
})

// --- 对话模型连通性状态 ---
// 按「签名」(baseUrl|modelName|apiKey) 索引，避免删除/重排时与 index 错位。
type ChatStatus = 'testing' | 'ok' | 'fail'
const chatModelStatus = ref<Record<string, ChatStatus>>({})
const chatModelStatusMsg = ref<Record<string, string>>({})
// 添加表单「保存」按钮的测试中状态（测试通过才写入配置）
const addTesting = ref(false)

function modelKey(m: Pick<ChatModelCfg, 'baseUrl' | 'modelName' | 'apiKey'>): string {
  return `${m.baseUrl}|${m.modelName}|${m.apiKey}`
}

/** 测试单个对话模型，结果写入状态映射（不阻塞，供列表逐个并发调用） */
async function testChatModelCfg(m: ChatModelCfg) {
  const key = modelKey(m)
  chatModelStatus.value = { ...chatModelStatus.value, [key]: 'testing' }
  try {
    const { ok, message } = await testChatModel(m)
    chatModelStatus.value = { ...chatModelStatus.value, [key]: ok ? 'ok' : 'fail' }
    chatModelStatusMsg.value = { ...chatModelStatusMsg.value, [key]: message }
  } catch (e) {
    chatModelStatus.value = { ...chatModelStatus.value, [key]: 'fail' }
    chatModelStatusMsg.value = { ...chatModelStatusMsg.value, [key]: (e as Error).message }
  }
}

/** 并发测试列表内全部对话模型 */
function testAllChatModels() {
  for (const m of appConfig.value.availableChatModels) testChatModelCfg(m)
}

function chatStatusOf(m: ChatModelCfg): ChatStatus | undefined {
  return chatModelStatus.value[modelKey(m)]
}
function chatStatusText(m: ChatModelCfg): string {
  const s = chatStatusOf(m)
  if (s === 'ok') return '可用'
  if (s === 'fail') return '不可用'
  if (s === 'testing') return '检测中'
  return '未检测'
}
function chatStatusDotClass(m: ChatModelCfg): string {
  const s = chatStatusOf(m)
  if (s === 'ok') return 'bg-green-500'
  if (s === 'fail') return 'bg-red-500'
  if (s === 'testing') return 'bg-amber-400 animate-pulse'
  return 'bg-gray-300'
}
function chatStatusTextClass(m: ChatModelCfg): string {
  const s = chatStatusOf(m)
  if (s === 'ok') return 'text-green-600'
  if (s === 'fail') return 'text-red-500'
  return 'text-on-surface-variant/60'
}

// --- 添加表单状态（OCR） ---
const showAddOCRForm = ref(false)
const addOCRMode = ref<'preset' | 'custom'>('preset')
const selectedOCRPresetId = ref('')
const newOCRDraft = reactive<OCRModelCfg>({
  baseUrl: '', modelName: '', apiKey: '', displayName: '', source: 'preset',
})

// --- 内联编辑状态（OCR） ---
const editingOCRIndex = ref<number | null>(null)
const editOCRDraft = reactive<OCRModelCfg>({
  baseUrl: '', modelName: '', apiKey: '', displayName: '', source: 'preset',
})

// --- 左侧菜单分区：模型 / MCP / Skill / 编辑MCP / 编辑Skill / 环境配置 / 关于 ---
// kbmcp / kbskill 为「知识库 AI 编辑 agent」专用，与主对话的 mcp / skill 隔离（后端 kbMcpServers + /api/kb-skills）。
const activeSection = ref<'model' | 'mcp' | 'skill' | 'kbmcp' | 'kbskill' | 'env' | 'about'>('model')

// --- 卸载确认弹窗 ---
const uninstallConfirmOpen = ref(false)
const uninstallDeleteData = ref(false)
const uninstalling = ref(false)

function openUninstallConfirm() {
  uninstallDeleteData.value = false
  uninstallConfirmOpen.value = true
}

async function doUninstall() {
  uninstalling.value = true
  try {
    await uninstallApp(uninstallDeleteData.value)
    uninstallConfirmOpen.value = false
    showToast('正在卸载，应用即将关闭…', 'success')
  } catch (e) {
    showToast('卸载失败: ' + (e as Error).message, 'error')
  } finally {
    uninstalling.value = false
  }
}

// --- MCP 区子标签：我的 MCP / MCP 市场 ---
const mcpTab = ref<'mine' | 'market'>('mine')

// --- Skill 区子标签：我的 SKILL / SKILL 市场 ---
const skillTab = ref<'mine' | 'market'>('mine')
const skills = ref<SkillInfo[]>([])
const skillsLoading = ref(false)
const skillImporting = ref(false)
const skillDragOver = ref(false)

// --- 添加 MCP（粘贴 JSON） ---
const showAddMcp = ref(false)
const addMcpJsonText = ref('')

// --- MCP 连接状态（红绿灯数据源，按 name 索引） ---
const mcpStatus = ref<McpServerStatus[]>([])
const mcpStatusMap = computed(() => {
  const map: Record<string, McpServerStatus> = {}
  for (const s of mcpStatus.value) map[s.name] = s
  return map
})
let mcpPollTimer: ReturnType<typeof setTimeout> | null = null

// ======== 知识库 AI 编辑 agent 专用的 MCP / Skill 状态（结构照抄上方主对话版，作用于 kbMcpServers / kb-skills） ========
// --- 编辑 MCP 子标签 / 添加 / 红绿灯 ---
const kbMcpTab = ref<'mine' | 'market'>('mine')
const showAddKbMcp = ref(false)
const addKbMcpJsonText = ref('')
const kbMcpStatus = ref<McpServerStatus[]>([])
const kbMcpStatusMap = computed(() => {
  const map: Record<string, McpServerStatus> = {}
  for (const s of kbMcpStatus.value) map[s.name] = s
  return map
})
let kbMcpPollTimer: ReturnType<typeof setTimeout> | null = null

// --- 编辑 Skill 子标签 / 列表 / 导入态 ---
const kbSkillTab = ref<'mine' | 'market'>('mine')
const kbSkills = ref<SkillInfo[]>([])
const kbSkillsLoading = ref(false)
const kbSkillImporting = ref(false)
const kbSkillDragOver = ref(false)

// --- 环境配置（运行时检测/安装） ---
const runtimes = ref<RuntimeStatus[]>([])
const runtimesLoading = ref(false)
const installingId = ref<string | null>(null)

// MCP 市场预设卡片：一键安装 = 复用粘贴 JSON 的同一套规整/合并流程，config 即等价于用户粘贴的内容
const marketItems: { name: string; desc: string; tag: string; config: Record<string, unknown> }[] = [
  {
    name: 'playwright',
    desc: '浏览器自动化：网页操作、抓取、截图（npx）',
    tag: 'npx',
    config: {
      mcpServers: {
        playwright: { command: 'npx', args: ['@playwright/mcp@latest'] },
      },
    },
  },
  {
    name: 'sequential-thinking',
    desc: '结构化分步推理，提升复杂任务规划能力（npx）',
    tag: 'npx',
    config: {
      mcpServers: {
        'sequential-thinking': { command: 'npx', args: ['-y', '@modelcontextprotocol/server-sequential-thinking'] },
      },
    },
  },
  {
    name: 'context7',
    desc: '实时拉取库/框架的最新官方文档与示例（npx）',
    tag: 'npx',
    config: {
      mcpServers: {
        '@upstash/context7-mcp': { command: 'npx', args: ['-y', '@upstash/context7-mcp@latest'] },
      },
    },
  },
  {
    name: 'codegraph',
    desc: '代码知识图谱：符号、调用关系、影响面查询',
    tag: 'cli',
    config: {
      mcpServers: {
        codegraph: { command: 'codegraph', args: ['serve', '--mcp'] },
      },
    },
  },
  {
    name: 'mermaid-chart',
    desc: 'Mermaid 图表生成与渲染（npx）',
    tag: 'npx',
    config: {
      mcpServers: {
        'mermaid-chart': { command: 'npx', args: ['@pickstar-2002/mermaid-chart-mcp@latest'] },
      },
    },
  },
  {
    name: 'mcp-deepwiki',
    desc: '查询 DeepWiki 上的开源仓库文档（npx）',
    tag: 'npx',
    config: {
      mcpServers: {
        'mcp-deepwiki': { command: 'npx', args: ['-y', 'mcp-deepwiki@latest'] },
      },
    },
  },
]

// --- 加载 ---
async function load() {
  try {
    const loaded = await fetchConfig()
    appConfig.value = {
      ...loaded,
      embeddingModel: {
        ...emptyEmbedding(),
        ...(loaded.embeddingModel ?? {}),
      },
    }
  } catch {
    showToast('加载配置失败', 'warning')
  }
}

async function loadProviders() {
  try {
    providers.value = await fetchProviders()
  } catch {
    showToast('获取AI厂商列表失败', 'warning')
  }
}

// 初次加载厂商列表
loadProviders()

// 每次打开时加载最新配置，并探测对话模型可用状态
watch(() => props.open, async (open) => {
  if (open) {
    await load()
    resetForms()
    if (activeSection.value === 'model') testAllChatModels()
  }
})

// --- 保存 ---
async function doSave() {
  try {
    await saveConfig(appConfig.value)
    emit('saved')
    showToast('配置已保存', 'success')
    return true
  } catch (e) {
    showToast('保存失败: ' + (e as Error).message, 'error')
    return false
  }
}

// --- 表单重置 ---
function resetForms() {
  showAddForm.value = false
  showAddOCRForm.value = false
  editingIndex.value = null
  editingOCRIndex.value = null
  embeddingStatus.value = null
  embeddingStatusMessage.value = ''
  embeddingExpanded.value = false
  addMode.value = 'preset'
  addOCRMode.value = 'preset'
  selectedPresetId.value = ''
  selectedOCRPresetId.value = ''
  Object.assign(newDraft, { baseUrl: '', modelName: '', apiKey: '', displayName: '', source: 'preset' })
  Object.assign(newOCRDraft, { baseUrl: '', modelName: '', apiKey: '', displayName: '', source: 'preset' })
  showAddMcp.value = false
  addMcpJsonText.value = ''
  mcpTab.value = 'mine'
  skillTab.value = 'mine'
  showAddKbMcp.value = false
  addKbMcpJsonText.value = ''
  kbMcpTab.value = 'mine'
  kbSkillTab.value = 'mine'
}

// --- 预设厂商选中时自动填 baseUrl ---
watch(selectedPresetId, (pid) => {
  if (pid) {
    const p = providers.value.find((item) => item.id === pid)
    if (p) {
      newDraft.baseUrl = p.baseUrl
      newDraft.displayName = p.defaultModel
      newDraft.modelName = p.defaultModel
      newDraft.source = 'preset'
    }
  }
})

watch(selectedOCRPresetId, (pid) => {
  if (pid) {
    const p = providers.value.find((item) => item.id === pid)
    if (p) {
      newOCRDraft.baseUrl = p.baseUrl
      newOCRDraft.displayName = p.defaultModel
      newOCRDraft.modelName = p.defaultModel
      newOCRDraft.source = 'preset'
    }
  }
})

// --- 预设模式下模型名变化时联动展示名称（仅取模型名） ---
watch(() => newDraft.modelName, (model) => {
  if (addMode.value === 'preset' && model) {
    newDraft.displayName = model
  }
})

watch(() => newOCRDraft.modelName, (model) => {
  if (addOCRMode.value === 'preset' && model) {
    newOCRDraft.displayName = model
  }
})

// --- 添加对话模型 ---
// 点击保存后先在后端做一次连接测试（发「你好」），通过才写入配置，失败则提示且不落库。
async function confirmAdd() {
  if (!newDraft.baseUrl.trim() || !newDraft.modelName.trim()) {
    showToast('请填写 Base URL 和模型名', 'warning')
    return
  }
  if (!newDraft.displayName.trim()) {
    newDraft.displayName = newDraft.modelName
  }
  addTesting.value = true
  const { ok, message } = await testChatModel({ ...newDraft })
  addTesting.value = false
  if (!ok) {
    showToast('连接失败：' + message, 'error')
    return
  }
  const cfg = { ...newDraft }
  const models = [...appConfig.value.availableChatModels, cfg]
  appConfig.value = { ...appConfig.value, availableChatModels: models }
  // 已通过测试，直接标记为可用，省去列表二次探测
  chatModelStatus.value = { ...chatModelStatus.value, [modelKey(cfg)]: 'ok' }
  showAddForm.value = false
  Object.assign(newDraft, {
    baseUrl: '', modelName: '', apiKey: '', displayName: '', source: 'preset',
    contextLength: 200000, maxInputTokens: 168000, maxOutputTokens: 32000,
  })
  selectedPresetId.value = ''
  addMode.value = 'preset'
  doSave()
}

function startEdit(index: number) {
  editingIndex.value = index
  Object.assign(editDraft, appConfig.value.availableChatModels[index])
}

function cancelEdit() {
  editingIndex.value = null
}

function confirmEdit(index: number) {
  const models = [...appConfig.value.availableChatModels]
  models[index] = { ...editDraft }
  appConfig.value = { ...appConfig.value, availableChatModels: models }
  editingIndex.value = null
  doSave()
  // 连接参数可能已变，重新探测该模型的可用状态
  testChatModelCfg(models[index])
}

async function deleteChatModel(index: number) {
  const name = appConfig.value.availableChatModels[index].displayName || '未命名'
  if (!(await confirm({ message: `确定删除「${name}」？`, danger: true, confirmText: '删除' }))) return
  const models = appConfig.value.availableChatModels.filter((_, i) => i !== index)
  appConfig.value = { ...appConfig.value, availableChatModels: models }
  doSave()
}

// --- 添加 OCR 模型 ---
function confirmAddOCR() {
  if (!newOCRDraft.baseUrl.trim() || !newOCRDraft.modelName.trim()) {
    showToast('请填写 Base URL 和模型名', 'warning')
    return
  }
  if (!newOCRDraft.displayName.trim()) {
    newOCRDraft.displayName = newOCRDraft.modelName
  }
  const models = [...appConfig.value.availableOCRModels, { ...newOCRDraft }]
  appConfig.value = { ...appConfig.value, availableOCRModels: models }
  showAddOCRForm.value = false
  Object.assign(newOCRDraft, { baseUrl: '', modelName: '', apiKey: '', displayName: '', source: 'preset' })
  selectedOCRPresetId.value = ''
  addOCRMode.value = 'preset'
  doSave()
}

function startEditOCR(index: number) {
  editingOCRIndex.value = index
  Object.assign(editOCRDraft, appConfig.value.availableOCRModels[index])
}

function cancelEditOCR() {
  editingOCRIndex.value = null
}

function confirmEditOCR(index: number) {
  const models = [...appConfig.value.availableOCRModels]
  models[index] = { ...editOCRDraft }
  appConfig.value = { ...appConfig.value, availableOCRModels: models }
  editingOCRIndex.value = null
  doSave()
}

async function deleteOCRModel(index: number) {
  const name = appConfig.value.availableOCRModels[index].displayName || '未命名'
  if (!(await confirm({ message: `确定删除「${name}」？`, danger: true, confirmText: '删除' }))) return
  const models = appConfig.value.availableOCRModels.filter((_, i) => i !== index)
  appConfig.value = { ...appConfig.value, availableOCRModels: models }
  doSave()
}

// --- MCP 服务管理（粘贴 JSON 添加 / 删除 / 启用开关） ---

/** 把任意一条原始对象规整为标准 McpServerCfg（缺省字段补全、传输方式推断） */
function coerceMcpEntry(name: string, raw: Record<string, unknown>): McpServerCfg {
  const url = typeof raw.url === 'string' ? raw.url : ''
  const transportType = typeof raw.transportType === 'string' && raw.transportType
    ? raw.transportType
    : (url ? 'streamable_http' : 'stdio')
  return {
    name: (typeof raw.name === 'string' && raw.name) ? raw.name : name,
    transportType,
    command: typeof raw.command === 'string' ? raw.command : '',
    args: Array.isArray(raw.args) ? raw.args.map(String) : [],
    env: (raw.env && typeof raw.env === 'object') ? raw.env as Record<string, string> : {},
    url,
    headers: (raw.headers && typeof raw.headers === 'object') ? raw.headers as Record<string, string> : {},
    enabled: typeof raw.enabled === 'boolean' ? raw.enabled : true,
  }
}

/**
 * 解析用户粘贴的 JSON，兼容三种格式：
 * 1. 本应用数组格式：[{ name, transportType, ... }]
 * 2. Claude Desktop / Cursor 对象格式：{ "mcpServers": { "name": {...} } }
 * 3. 裸对象：{ "name": {...} }
 */
function normalizeMcpInput(parsed: unknown): McpServerCfg[] {
  if (Array.isArray(parsed)) {
    return parsed.map((item) => coerceMcpEntry('', (item ?? {}) as Record<string, unknown>))
  }
  if (parsed && typeof parsed === 'object') {
    const root = parsed as Record<string, unknown>
    if (Array.isArray(root.mcpServers)) return normalizeMcpInput(root.mcpServers)
    const obj = (root.mcpServers && typeof root.mcpServers === 'object')
      ? root.mcpServers as Record<string, unknown>
      : root
    return Object.entries(obj).map(([name, raw]) => coerceMcpEntry(name, (raw ?? {}) as Record<string, unknown>))
  }
  throw new Error('配置格式无法识别，应为数组或对象')
}

/** 提示文案：stdio 命令对应的运行时未检测到时给出警告 */
function warnIfRuntimeMissing(entries: McpServerCfg[]) {
  for (const e of entries) {
    if (e.transportType !== 'stdio') continue
    const cmd = (e.command || '').toLowerCase()
    const needId = cmd.includes('uvx') || cmd.includes('uv') ? 'uv'
      : (cmd.includes('npx') || cmd.includes('node') ? 'node' : '')
    if (!needId) continue
    const rt = runtimes.value.find((r) => r.id === needId)
    if (rt && !rt.installed) {
      showToast(`「${e.name}」需要 ${rt.displayName}，但本机未检测到，请到「环境配置」安装`, 'warning')
    }
  }
}

/** 解析粘贴的 JSON → 按 name 去重合并入配置 → 保存并轮询状态 */
function confirmAddMcp() {
  let parsed: unknown
  try {
    parsed = JSON.parse(addMcpJsonText.value)
  } catch (e) {
    showToast('JSON 解析失败：' + (e as Error).message, 'error')
    return
  }
  let incoming: McpServerCfg[]
  try {
    incoming = normalizeMcpInput(parsed)
  } catch (e) {
    showToast((e as Error).message, 'error')
    return
  }
  if (incoming.length === 0) {
    showToast('未解析到任何 MCP 服务', 'warning')
    return
  }
  if (incoming.some((m) => !m.name.trim())) {
    showToast('存在未命名的 MCP 服务，请补全 name 字段', 'warning')
    return
  }
  // 按 name 去重：同名覆盖，新增追加
  const merged = [...(appConfig.value.mcpServers ?? [])]
  for (const entry of incoming) {
    const idx = merged.findIndex((m) => m.name === entry.name)
    if (idx >= 0) merged[idx] = entry
    else merged.push(entry)
  }
  appConfig.value = { ...appConfig.value, mcpServers: merged }
  showAddMcp.value = false
  addMcpJsonText.value = ''
  doSave()
  warnIfRuntimeMissing(incoming)
  startMcpStatusPolling()
}

/**
 * 市场一键安装：把预设 config 当作用户粘贴的 JSON，走同一套规整 + 按 name 去重合并流程。
 * 等价于在「我的 MCP」里粘贴该配置，因此行为与手动添加完全一致。
 */
function installMarketMcp(item: { name: string; config: Record<string, unknown> }) {
  let incoming: McpServerCfg[]
  try {
    incoming = normalizeMcpInput(item.config)
  } catch (e) {
    showToast((e as Error).message, 'error')
    return
  }
  const merged = [...(appConfig.value.mcpServers ?? [])]
  for (const entry of incoming) {
    const idx = merged.findIndex((m) => m.name === entry.name)
    if (idx >= 0) merged[idx] = entry
    else merged.push(entry)
  }
  appConfig.value = { ...appConfig.value, mcpServers: merged }
  doSave()
  warnIfRuntimeMissing(incoming)
  startMcpStatusPolling()
  showToast(`「${item.name}」已添加到我的 MCP`, 'success')
}

/** 市场卡片是否已安装：预设里的所有 server 名都已存在于配置中 */
function isMarketInstalled(item: { config: Record<string, unknown> }): boolean {
  let entries: McpServerCfg[]
  try {
    entries = normalizeMcpInput(item.config)
  } catch {
    return false
  }
  const names = new Set((appConfig.value.mcpServers ?? []).map((m) => m.name))
  return entries.length > 0 && entries.every((e) => names.has(e.name))
}

async function deleteMcp(index: number) {
  const list = appConfig.value.mcpServers ?? []
  const name = list[index]?.name || '未命名'
  if (!(await confirm({ message: `确定删除 MCP 服务「${name}」？`, danger: true, confirmText: '删除' }))) return
  const next = list.filter((_, i) => i !== index)
  appConfig.value = { ...appConfig.value, mcpServers: next }
  doSave()
  startMcpStatusPolling()
}

/** 卡片上直接切换启用状态（即时保存 + 轮询状态） */
function toggleMcpEnabled(index: number) {
  const list = [...(appConfig.value.mcpServers ?? [])]
  list[index] = { ...list[index], enabled: !list[index].enabled }
  appConfig.value = { ...appConfig.value, mcpServers: list }
  doSave()
  startMcpStatusPolling()
}

// --- MCP 连接状态轮询 ---
/** 拉取一次状态 */
async function refreshMcpStatus() {
  try {
    mcpStatus.value = await fetchMcpStatus()
  } catch {
    // 状态拉取失败不打扰用户，红绿灯退化为灰
  }
}

/** 是否仍有处于「建连中」的服务（决定是否继续轮询） */
function hasConnecting(): boolean {
  return mcpStatus.value.some((s) => s.status === 'CONNECTING')
}

/**
 * 启动状态轮询：后端 sync 是异步的，刚改完配置会短暂 CONNECTING。
 * 每 1.5s 拉一次，最多约 10 次，期间状态稳定（无 CONNECTING）即停止。
 */
function startMcpStatusPolling() {
  stopMcpStatusPolling()
  let remaining = 10
  const tick = async () => {
    await refreshMcpStatus()
    remaining -= 1
    if (remaining <= 0 || !hasConnecting()) {
      mcpPollTimer = null
      return
    }
    mcpPollTimer = setTimeout(tick, 1500)
  }
  mcpPollTimer = setTimeout(tick, 600)
}

function stopMcpStatusPolling() {
  if (mcpPollTimer) {
    clearTimeout(mcpPollTimer)
    mcpPollTimer = null
  }
}

/** 红绿灯样式：CONNECTED 绿 / FAILED 红 / CONNECTING 琥珀 / 其余灰 */
function statusDotClass(name: string): string {
  const st = mcpStatusMap.value[name]?.status
  if (st === 'CONNECTED') return 'bg-green-500'
  if (st === 'FAILED') return 'bg-red-500'
  if (st === 'CONNECTING') return 'bg-amber-400 animate-pulse'
  return 'bg-gray-300'
}

function statusText(name: string): string {
  const st = mcpStatusMap.value[name]?.status
  if (st === 'CONNECTED') return '已连接'
  if (st === 'FAILED') return '连接失败'
  if (st === 'CONNECTING') return '连接中'
  return '已停用'
}

// --- 环境配置（运行时检测/安装） ---
async function loadRuntimes() {
  runtimesLoading.value = true
  try {
    runtimes.value = await fetchRuntimes()
  } catch {
    showToast('检测运行时失败', 'warning')
  } finally {
    runtimesLoading.value = false
  }
}

async function doInstall(id: string) {
  installingId.value = id
  try {
    const { ok, message } = await installRuntime(id)
    showToast(message, ok ? 'success' : 'error')
  } catch (e) {
    showToast('安装失败: ' + (e as Error).message, 'error')
  } finally {
    installingId.value = null
  }
}

// --- Skill 管理（专家提示词包） ---
async function loadSkills() {
  skillsLoading.value = true
  try {
    skills.value = await fetchSkills()
  } catch (e) {
    showToast('加载 Skill 失败: ' + (e as Error).message, 'error')
  } finally {
    skillsLoading.value = false
  }
}

/** File → base64（去掉 data:URL 前缀） */
function fileToBase64(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => {
      const s = String(reader.result)
      const comma = s.indexOf(',')
      resolve(comma >= 0 ? s.slice(comma + 1) : s)
    }
    reader.onerror = () => reject(reader.error)
    reader.readAsDataURL(file)
  })
}

/** 读尽某目录下所有条目（readEntries 每次最多返回 100 条，须循环直到空） */
function readAllEntries(reader: FileSystemDirectoryReader): Promise<FileSystemEntry[]> {
  return new Promise((resolve, reject) => {
    const all: FileSystemEntry[] = []
    const next = () =>
      reader.readEntries((batch) => {
        if (batch.length === 0) {
          resolve(all)
          return
        }
        all.push(...batch)
        next()
      }, reject)
    next()
  })
}

/** 递归收集文件夹内全部文件（相对路径 + base64） */
async function collectFiles(entry: FileSystemEntry, prefix: string, out: SkillImportFile[]) {
  if (out.length > 200) return
  if (entry.isFile) {
    const file = await new Promise<File>((res, rej) => (entry as FileSystemFileEntry).file(res, rej))
    out.push({ path: prefix + entry.name, contentBase64: await fileToBase64(file) })
  } else if (entry.isDirectory) {
    const children = await readAllEntries((entry as FileSystemDirectoryEntry).createReader())
    for (const c of children) await collectFiles(c, prefix + entry.name + '/', out)
  }
}

/** 拖拽文件夹到导入区 */
async function onSkillDrop(e: DragEvent) {
  e.preventDefault()
  skillDragOver.value = false
  const items = e.dataTransfer?.items
  if (!items || items.length === 0) return
  let dirEntry: FileSystemDirectoryEntry | null = null
  for (let i = 0; i < items.length; i++) {
    const entry = items[i].webkitGetAsEntry?.()
    if (entry?.isDirectory) {
      dirEntry = entry as FileSystemDirectoryEntry
      break
    }
  }
  if (!dirEntry) {
    showToast('请拖入一个包含 SKILL.md 的文件夹', 'warning')
    return
  }
  skillImporting.value = true
  try {
    const files: SkillImportFile[] = []
    const children = await readAllEntries(dirEntry.createReader())
    for (const c of children) await collectFiles(c, '', files)
    if (!files.some((f) => f.path.toLowerCase() === 'skill.md')) {
      showToast('文件夹根目录缺少 SKILL.md', 'warning')
      return
    }
    skills.value = await importSkillFiles(dirEntry.name, files)
    showToast(`Skill「${dirEntry.name}」已导入`, 'success')
  } catch (e) {
    showToast('导入失败: ' + (e as Error).message, 'error')
  } finally {
    skillImporting.value = false
  }
}

/** 点击「选择文件夹」走原生对话框 */
async function onSkillBrowse() {
  if (skillImporting.value) return
  skillImporting.value = true
  try {
    const before = skills.value.length
    skills.value = await importSkillDir()
    if (skills.value.length > before) showToast('Skill 导入完成', 'success')
  } catch (e) {
    showToast('导入失败: ' + (e as Error).message, 'error')
  } finally {
    skillImporting.value = false
  }
}

async function removeSkill(name: string) {
  if (!(await confirm({ message: `确定删除 Skill「${name}」？`, danger: true, confirmText: '删除' }))) return
  try {
    skills.value = await deleteSkill(name)
    showToast('已删除', 'success')
  } catch (e) {
    showToast('删除失败: ' + (e as Error).message, 'error')
  }
}

// ======== 知识库编辑 agent 的 MCP 管理（照抄主对话版，目标换成 kbMcpServers + /api/mcp/kb-status） ========

/** 解析粘贴 JSON → 按 name 去重合并入 kbMcpServers → 保存并轮询 */
function confirmAddKbMcp() {
  let parsed: unknown
  try {
    parsed = JSON.parse(addKbMcpJsonText.value)
  } catch (e) {
    showToast('JSON 解析失败：' + (e as Error).message, 'error')
    return
  }
  let incoming: McpServerCfg[]
  try {
    incoming = normalizeMcpInput(parsed)
  } catch (e) {
    showToast((e as Error).message, 'error')
    return
  }
  if (incoming.length === 0) {
    showToast('未解析到任何 MCP 服务', 'warning')
    return
  }
  if (incoming.some((m) => !m.name.trim())) {
    showToast('存在未命名的 MCP 服务，请补全 name 字段', 'warning')
    return
  }
  const merged = [...(appConfig.value.kbMcpServers ?? [])]
  for (const entry of incoming) {
    const idx = merged.findIndex((m) => m.name === entry.name)
    if (idx >= 0) merged[idx] = entry
    else merged.push(entry)
  }
  appConfig.value = { ...appConfig.value, kbMcpServers: merged }
  showAddKbMcp.value = false
  addKbMcpJsonText.value = ''
  doSave()
  warnIfRuntimeMissing(incoming)
  startKbMcpStatusPolling()
}

/** 市场一键安装到 kbMcpServers（与主对话同一套 marketItems / 规整流程） */
function installMarketKbMcp(item: { name: string; config: Record<string, unknown> }) {
  let incoming: McpServerCfg[]
  try {
    incoming = normalizeMcpInput(item.config)
  } catch (e) {
    showToast((e as Error).message, 'error')
    return
  }
  const merged = [...(appConfig.value.kbMcpServers ?? [])]
  for (const entry of incoming) {
    const idx = merged.findIndex((m) => m.name === entry.name)
    if (idx >= 0) merged[idx] = entry
    else merged.push(entry)
  }
  appConfig.value = { ...appConfig.value, kbMcpServers: merged }
  doSave()
  warnIfRuntimeMissing(incoming)
  startKbMcpStatusPolling()
  showToast(`「${item.name}」已添加到编辑 MCP`, 'success')
}

function isMarketKbInstalled(item: { config: Record<string, unknown> }): boolean {
  let entries: McpServerCfg[]
  try {
    entries = normalizeMcpInput(item.config)
  } catch {
    return false
  }
  const names = new Set((appConfig.value.kbMcpServers ?? []).map((m) => m.name))
  return entries.length > 0 && entries.every((e) => names.has(e.name))
}

async function deleteKbMcp(index: number) {
  const list = appConfig.value.kbMcpServers ?? []
  const name = list[index]?.name || '未命名'
  if (!(await confirm({ message: `确定删除 MCP 服务「${name}」？`, danger: true, confirmText: '删除' }))) return
  const next = list.filter((_, i) => i !== index)
  appConfig.value = { ...appConfig.value, kbMcpServers: next }
  doSave()
  startKbMcpStatusPolling()
}

function toggleKbMcpEnabled(index: number) {
  const list = [...(appConfig.value.kbMcpServers ?? [])]
  list[index] = { ...list[index], enabled: !list[index].enabled }
  appConfig.value = { ...appConfig.value, kbMcpServers: list }
  doSave()
  startKbMcpStatusPolling()
}

async function refreshKbMcpStatus() {
  try {
    kbMcpStatus.value = await fetchKbMcpStatus()
  } catch {
    // 状态拉取失败不打扰用户
  }
}

function hasKbConnecting(): boolean {
  return kbMcpStatus.value.some((s) => s.status === 'CONNECTING')
}

function startKbMcpStatusPolling() {
  stopKbMcpStatusPolling()
  let remaining = 10
  const tick = async () => {
    await refreshKbMcpStatus()
    remaining -= 1
    if (remaining <= 0 || !hasKbConnecting()) {
      kbMcpPollTimer = null
      return
    }
    kbMcpPollTimer = setTimeout(tick, 1500)
  }
  kbMcpPollTimer = setTimeout(tick, 600)
}

function stopKbMcpStatusPolling() {
  if (kbMcpPollTimer) {
    clearTimeout(kbMcpPollTimer)
    kbMcpPollTimer = null
  }
}

function kbStatusDotClass(name: string): string {
  const st = kbMcpStatusMap.value[name]?.status
  if (st === 'CONNECTED') return 'bg-green-500'
  if (st === 'FAILED') return 'bg-red-500'
  if (st === 'CONNECTING') return 'bg-amber-400 animate-pulse'
  return 'bg-gray-300'
}

function kbStatusText(name: string): string {
  const st = kbMcpStatusMap.value[name]?.status
  if (st === 'CONNECTED') return '已连接'
  if (st === 'FAILED') return '连接失败'
  if (st === 'CONNECTING') return '连接中'
  return '已停用'
}

// ======== 知识库编辑 agent 的 Skill 管理（照抄主对话版，走 /api/kb-skills） ========
async function loadKbSkills() {
  kbSkillsLoading.value = true
  try {
    kbSkills.value = await fetchKbSkills()
  } catch (e) {
    showToast('加载 Skill 失败: ' + (e as Error).message, 'error')
  } finally {
    kbSkillsLoading.value = false
  }
}

async function onKbSkillDrop(e: DragEvent) {
  e.preventDefault()
  kbSkillDragOver.value = false
  const items = e.dataTransfer?.items
  if (!items || items.length === 0) return
  let dirEntry: FileSystemDirectoryEntry | null = null
  for (let i = 0; i < items.length; i++) {
    const entry = items[i].webkitGetAsEntry?.()
    if (entry?.isDirectory) {
      dirEntry = entry as FileSystemDirectoryEntry
      break
    }
  }
  if (!dirEntry) {
    showToast('请拖入一个包含 SKILL.md 的文件夹', 'warning')
    return
  }
  kbSkillImporting.value = true
  try {
    const files: SkillImportFile[] = []
    const children = await readAllEntries(dirEntry.createReader())
    for (const c of children) await collectFiles(c, '', files)
    if (!files.some((f) => f.path.toLowerCase() === 'skill.md')) {
      showToast('文件夹根目录缺少 SKILL.md', 'warning')
      return
    }
    kbSkills.value = await importKbSkillFiles(dirEntry.name, files)
    showToast(`Skill「${dirEntry.name}」已导入`, 'success')
  } catch (e) {
    showToast('导入失败: ' + (e as Error).message, 'error')
  } finally {
    kbSkillImporting.value = false
  }
}

async function onKbSkillBrowse() {
  if (kbSkillImporting.value) return
  kbSkillImporting.value = true
  try {
    const before = kbSkills.value.length
    kbSkills.value = await importKbSkillDir()
    if (kbSkills.value.length > before) showToast('Skill 导入完成', 'success')
  } catch (e) {
    showToast('导入失败: ' + (e as Error).message, 'error')
  } finally {
    kbSkillImporting.value = false
  }
}

async function removeKbSkill(name: string) {
  if (!(await confirm({ message: `确定删除 Skill「${name}」？`, danger: true, confirmText: '删除' }))) return
  try {
    kbSkills.value = await deleteKbSkill(name)
    showToast('已删除', 'success')
  } catch (e) {
    showToast('删除失败: ' + (e as Error).message, 'error')
  }
}

// --- 分区切换时按需加载数据 ---
watch(activeSection, (section) => {
  // 切走时停掉两条 MCP 轮询，避免后台空转
  stopMcpStatusPolling()
  stopKbMcpStatusPolling()
  if (section === 'mcp') {
    refreshMcpStatus()
  } else if (section === 'kbmcp') {
    refreshKbMcpStatus()
  } else if (section === 'env') {
    loadRuntimes()
  } else if (section === 'skill') {
    loadSkills()
  } else if (section === 'kbskill') {
    loadKbSkills()
  } else {
    testAllChatModels()
  }
})

// 打开弹窗时，若已停在 MCP/Skill/环境分区，主动加载一次
watch(() => props.open, (open) => {
  if (!open) {
    stopMcpStatusPolling()
    stopKbMcpStatusPolling()
    return
  }
  if (activeSection.value === 'mcp') refreshMcpStatus()
  else if (activeSection.value === 'kbmcp') refreshKbMcpStatus()
  else if (activeSection.value === 'env') loadRuntimes()
  else if (activeSection.value === 'skill') loadSkills()
  else if (activeSection.value === 'kbskill') loadKbSkills()
})

onUnmounted(() => {
  stopMcpStatusPolling()
  stopKbMcpStatusPolling()
})
</script>

<template>
  <Teleport to="body">
    <transition name="fade">
      <div
        v-if="open"
        class="fixed inset-0 z-[1000] flex items-center justify-center bg-black/40"
        @click.self="emit('close')"
      >
        <div class="w-[1000px] h-[660px] rounded-2xl bg-white shadow-2xl flex flex-col relative">
          <!-- 右上关闭按钮 -->
          <button
            type="button"
            class="absolute top-3 right-3 z-10 flex items-center justify-center w-6 h-6 rounded-full text-on-surface-variant/50 transition-colors hover:text-on-surface hover:bg-surface-container"
            @click="emit('close')"
          >
            <span class="material-symbols-outlined text-[16px]">close</span>
          </button>

          <!-- 主体：左侧菜单 + 右侧属性 -->
          <div class="flex flex-1 min-h-0">
            <!-- 左侧菜单 -->
            <nav class="w-44 flex-shrink-0 rounded-l-2xl bg-[#F2F2F2] pt-[45px] pb-3 px-2 flex flex-col gap-0.5">
              <button
                type="button"
                class="flex items-center gap-2 w-full rounded-lg text-sm py-2.5 px-3 transition-colors"
                :class="activeSection === 'model' ? 'bg-gray-400/10 text-black font-bold' : 'text-on-surface-variant hover:bg-gray-400/5'"
                @click="activeSection = 'model'"
              >
                <ModelIcon class="w-[18px] h-[18px]" />
                模型
              </button>
              <button
                type="button"
                class="flex items-center gap-2 w-full rounded-lg text-sm py-2.5 px-3 transition-colors"
                :class="activeSection === 'mcp' ? 'bg-gray-400/10 text-black font-bold' : 'text-on-surface-variant hover:bg-gray-400/5'"
                @click="activeSection = 'mcp'"
              >
                <McpIcon class="w-[18px] h-[18px]" />
                MCP
              </button>
              <button
                type="button"
                class="flex items-center gap-2 w-full rounded-lg text-sm py-2.5 px-3 transition-colors"
                :class="activeSection === 'skill' ? 'bg-gray-400/10 text-black font-bold' : 'text-on-surface-variant hover:bg-gray-400/5'"
                @click="activeSection = 'skill'"
              >
                <SkillIcon class="w-[18px] h-[18px]" />
                Skill
              </button>
              <button
                type="button"
                class="flex items-center gap-2 w-full rounded-lg text-sm py-2.5 px-3 transition-colors"
                :class="activeSection === 'kbmcp' ? 'bg-gray-400/10 text-black font-bold' : 'text-on-surface-variant hover:bg-gray-400/5'"
                @click="activeSection = 'kbmcp'"
              >
                <McpIcon class="w-[18px] h-[18px]" />
                编辑 MCP
              </button>
              <button
                type="button"
                class="flex items-center gap-2 w-full rounded-lg text-sm py-2.5 px-3 transition-colors"
                :class="activeSection === 'kbskill' ? 'bg-gray-400/10 text-black font-bold' : 'text-on-surface-variant hover:bg-gray-400/5'"
                @click="activeSection = 'kbskill'"
              >
                <SkillIcon class="w-[18px] h-[18px]" />
                编辑 Skill
              </button>
              <button
                type="button"
                class="flex items-center gap-2 w-full rounded-lg text-sm py-2.5 px-3 transition-colors"
                :class="activeSection === 'env' ? 'bg-gray-400/10 text-black font-bold' : 'text-on-surface-variant hover:bg-gray-400/5'"
                @click="activeSection = 'env'"
              >
                <EnvIcon class="w-[18px] h-[18px]" />
                环境配置
              </button>
              <button
                type="button"
                class="flex items-center gap-2 w-full rounded-lg text-sm py-2.5 px-3 transition-colors"
                :class="activeSection === 'about' ? 'bg-gray-400/10 text-black font-bold' : 'text-on-surface-variant hover:bg-gray-400/5'"
                @click="activeSection = 'about'"
              >
                <span class="material-symbols-outlined text-[18px]">info</span>
                关于
              </button>
            </nav>

            <!-- 右侧面板 -->
            <div class="flex-1 px-6 pt-[45px] pb-4 flex flex-col gap-4 overflow-y-auto bg-white rounded-r-2xl">

              <!-- ============ 模型分区（对话模型 + OCR 模型） ============ -->
              <template v-if="activeSection === 'model'">

              <!-- ======== 对话模型面板 ======== -->
              <div class="flex flex-col gap-3">
                <div class="flex items-center justify-between">
                  <h3 class="text-sm font-semibold text-on-surface">对话模型列表</h3>
                  <button
                    v-if="!showAddForm"
                    type="button"
                    class="flex items-center gap-1 rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs text-on-surface transition-colors hover:bg-surface-container"
                    @click="showAddForm = true"
                  >
                    <span class="material-symbols-outlined text-[16px]">add</span>
                    添加
                  </button>
                </div>

                <!-- 添加表单 -->
                <div
                  v-if="showAddForm"
                  class="rounded-xl border border-outline-variant/60 bg-surface-container-low p-4 flex flex-col gap-3"
                >
                  <!-- 来源切换 -->
                  <div class="flex items-center gap-2">
                    <span class="text-xs text-on-surface-variant">来源：</span>
                    <button
                      type="button"
                      class="px-3 py-1 rounded-md text-xs font-medium transition-colors"
                      :class="addMode === 'preset' ? 'bg-primary text-on-primary' : 'bg-surface-container-high text-on-surface-variant'"
                      @click="addMode = 'preset'"
                    >预设厂商</button>
                    <button
                      type="button"
                      class="px-3 py-1 rounded-md text-xs font-medium transition-colors"
                      :class="addMode === 'custom' ? 'bg-primary text-on-primary' : 'bg-surface-container-high text-on-surface-variant'"
                      @click="addMode = 'custom'"
                    >自定义</button>
                  </div>

                  <!-- 预设厂商选择器 -->
                  <div v-if="addMode === 'preset'" class="flex flex-col gap-1.5">
                    <label class="text-xs font-medium text-on-surface">选择厂商</label>
                    <select
                      v-model="selectedPresetId"
                      class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                    >
                      <option value="" disabled>选择厂商</option>
                      <option v-for="p in providers" :key="p.id" :value="p.id">{{ p.name }}</option>
                    </select>
                  </div>

                  <!-- 手动输入字段 -->
                  <div class="flex flex-col gap-1.5">
                    <label class="text-xs font-medium text-on-surface">Base URL</label>
                    <input
                      v-model="newDraft.baseUrl"
                      type="text"
                      placeholder="https://api.example.com/v1"
                      class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                    />
                  </div>
                  <!-- 模型名：预设下拉 / 自定义输入 -->
                  <div class="flex flex-col gap-1.5">
                    <label class="text-xs font-medium text-on-surface">模型名</label>
                    <select
                      v-if="addMode === 'preset'"
                      v-model="newDraft.modelName"
                      class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                    >
                      <option value="" disabled>选择模型</option>
                      <option
                        v-for="m in providers.find(p => p.id === selectedPresetId)?.models ?? []"
                        :key="m"
                        :value="m"
                      >{{ m }}</option>
                    </select>
                    <input
                      v-else
                      v-model="newDraft.modelName"
                      type="text"
                      placeholder="gpt-4"
                      class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                    />
                  </div>
                  <div class="flex flex-col gap-1.5">
                    <label class="text-xs font-medium text-on-surface">API Key</label>
                    <input
                      v-model="newDraft.apiKey"
                      type="password"
                      placeholder="sk-..."
                      class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                    />
                  </div>
                  <div class="flex flex-col gap-1.5">
                    <label class="text-xs font-medium text-on-surface">展示名称（可选）</label>
                    <input
                      v-model="newDraft.displayName"
                      type="text"
                      placeholder="用于 ChatPanel 下拉框显示"
                      class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                    />
                  </div>
                  <!-- 上下文窗口相关（token）：上下文长度参与占比统计，最大输入/输出长度预留 -->
                  <div class="grid grid-cols-3 gap-2">
                    <div class="flex flex-col gap-1.5">
                      <label class="text-xs font-medium text-on-surface">上下文长度</label>
                      <input
                        v-model.number="newDraft.contextLength"
                        type="number"
                        min="0"
                        placeholder="200000"
                        class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                      />
                    </div>
                    <div class="flex flex-col gap-1.5">
                      <label class="text-xs font-medium text-on-surface">最大输入长度</label>
                      <input
                        v-model.number="newDraft.maxInputTokens"
                        type="number"
                        min="0"
                        placeholder="168000"
                        class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                      />
                    </div>
                    <div class="flex flex-col gap-1.5">
                      <label class="text-xs font-medium text-on-surface">最大输出长度</label>
                      <input
                        v-model.number="newDraft.maxOutputTokens"
                        type="number"
                        min="0"
                        placeholder="32000"
                        class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                      />
                    </div>
                  </div>
                  <div class="flex justify-end gap-2 mt-1">
                    <button
                      type="button"
                      class="rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs text-on-surface transition-colors hover:bg-surface-container"
                      :disabled="addTesting"
                      @click="showAddForm = false"
                    >取消</button>
                    <button
                      type="button"
                      class="rounded-lg bg-primary px-3 py-1.5 text-xs text-on-primary transition-colors hover:bg-primary-container disabled:opacity-60"
                      :disabled="addTesting"
                      @click="confirmAdd"
                    >{{ addTesting ? '测试连接中…' : '保存' }}</button>
                  </div>
                </div>

                <!-- 模型列表 -->
                <div v-if="appConfig.availableChatModels.length === 0 && !showAddForm" class="text-center py-10">
                  <p class="text-sm text-on-surface-variant/60">暂无配置，点击「添加」新增</p>
                </div>
                <div class="flex flex-col gap-2">
                  <div
                    v-for="(model, index) in appConfig.availableChatModels"
                    :key="index"
                    class="rounded-xl border border-outline-variant/60 bg-surface-container-low p-3"
                  >
                    <!-- 显示模式：仅展示「展示名称 + 状态 + Base URL」 -->
                    <template v-if="editingIndex !== index">
                      <div class="flex items-center justify-between gap-2">
                        <div class="flex-1 min-w-0">
                          <div class="flex items-center gap-2">
                            <!-- 可用状态灯 -->
                            <span
                              class="w-2 h-2 rounded-full flex-shrink-0"
                              :class="chatStatusDotClass(model)"
                              :title="chatModelStatusMsg[modelKey(model)] || chatStatusText(model)"
                            ></span>
                            <span class="text-sm font-medium text-on-surface truncate">
                              {{ model.displayName || model.modelName || '未命名' }}
                            </span>
                            <span class="text-[11px] flex-shrink-0" :class="chatStatusTextClass(model)">{{ chatStatusText(model) }}</span>
                          </div>
                          <div class="text-xs text-on-surface-variant mt-0.5 truncate pl-4">{{ model.baseUrl }}</div>
                        </div>
                        <div class="flex items-center gap-1 flex-shrink-0 ml-2">
                          <button
                            type="button"
                            class="flex items-center justify-center w-7 h-7 rounded-lg text-on-surface-variant transition-colors hover:bg-surface-container disabled:opacity-50"
                            title="测试连接"
                            :disabled="chatStatusOf(model) === 'testing'"
                            @click="testChatModelCfg(model)"
                          >
                            <span
                              v-if="chatStatusOf(model) === 'testing'"
                              class="material-symbols-outlined text-[16px] animate-spin"
                            >progress_activity</span>
                            <KbIcon v-else name="connect" class="w-4 h-4" />
                          </button>
                          <button
                            type="button"
                            class="flex items-center justify-center w-7 h-7 rounded-lg text-on-surface-variant transition-colors hover:bg-surface-container"
                            @click="startEdit(index)"
                          >
                            <KbIcon name="edit" class="w-4 h-4" />
                          </button>
                          <button
                            type="button"
                            class="flex items-center justify-center w-7 h-7 rounded-lg text-on-surface-variant transition-colors hover:bg-red-50 hover:text-red-500"
                            @click="deleteChatModel(index)"
                          >
                            <KbIcon name="delete" class="w-4 h-4" />
                          </button>
                        </div>
                      </div>
                    </template>

                    <!-- 编辑模式 -->
                    <template v-else>
                      <div class="flex flex-col gap-2">
                        <div class="flex flex-col gap-1.5">
                          <label class="text-xs font-medium text-on-surface">Base URL</label>
                          <input v-model="editDraft.baseUrl" type="text"
                            class="w-full rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs outline-none transition-colors focus:border-primary" />
                        </div>
                        <div class="flex flex-col gap-1.5">
                          <label class="text-xs font-medium text-on-surface">模型名</label>
                          <input v-model="editDraft.modelName" type="text"
                            class="w-full rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs outline-none transition-colors focus:border-primary" />
                        </div>
                        <div class="flex flex-col gap-1.5">
                          <label class="text-xs font-medium text-on-surface">API Key</label>
                          <input v-model="editDraft.apiKey" type="password"
                            class="w-full rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs outline-none transition-colors focus:border-primary" />
                        </div>
                        <div class="flex flex-col gap-1.5">
                          <label class="text-xs font-medium text-on-surface">展示名称</label>
                          <input v-model="editDraft.displayName" type="text"
                            class="w-full rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs outline-none transition-colors focus:border-primary" />
                        </div>
                        <!-- 上下文窗口相关（token） -->
                        <div class="grid grid-cols-3 gap-2">
                          <div class="flex flex-col gap-1.5">
                            <label class="text-xs font-medium text-on-surface">上下文长度</label>
                            <input v-model.number="editDraft.contextLength" type="number" min="0"
                              class="w-full rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs outline-none transition-colors focus:border-primary" />
                          </div>
                          <div class="flex flex-col gap-1.5">
                            <label class="text-xs font-medium text-on-surface">最大输入长度</label>
                            <input v-model.number="editDraft.maxInputTokens" type="number" min="0"
                              class="w-full rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs outline-none transition-colors focus:border-primary" />
                          </div>
                          <div class="flex flex-col gap-1.5">
                            <label class="text-xs font-medium text-on-surface">最大输出长度</label>
                            <input v-model.number="editDraft.maxOutputTokens" type="number" min="0"
                              class="w-full rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs outline-none transition-colors focus:border-primary" />
                          </div>
                        </div>
                        <div class="flex justify-end gap-2 mt-1">
                          <button type="button"
                            class="rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs text-on-surface transition-colors hover:bg-surface-container"
                            @click="cancelEdit">取消</button>
                          <button type="button"
                            class="rounded-lg bg-primary px-3 py-1.5 text-xs text-on-primary transition-colors hover:bg-primary-container"
                            @click="confirmEdit(index)">保存</button>
                        </div>
                      </div>
                    </template>
                  </div>
                </div>
              </div>

              <!-- ======== 知识库 Embedding 模型 ======== -->
              <div class="flex flex-col gap-3">
                <div>
                  <h3 class="text-sm font-semibold text-on-surface">知识库 Embedding 模型</h3>
                  <p class="mt-1 text-xs text-on-surface-variant/70">
                    用于 Markdown 的 KNN 向量召回；模型或维度变化后需要重新构建知识库索引。
                  </p>
                </div>

                <div class="rounded-xl border border-outline-variant/60 bg-surface-container-low p-3">
                  <!-- 摘要模式：与对话模型卡片一致，默认不展开敏感配置。 -->
                  <template v-if="!embeddingExpanded">
                    <div class="flex items-center justify-between gap-2">
                      <div class="flex-1 min-w-0">
                        <div class="flex items-center gap-2">
                          <span
                            class="w-2 h-2 rounded-full flex-shrink-0"
                            :class="embeddingStatusDotClass"
                            :title="embeddingStatusMessage || embeddingSummaryText"
                          ></span>
                          <span class="text-sm font-medium text-on-surface truncate">
                            {{ appConfig.embeddingModel.modelName || '未配置 Embedding 模型' }}
                          </span>
                          <span
                            class="text-[11px] flex-shrink-0"
                            :class="embeddingStatus === 'ok' ? 'text-green-600' : embeddingStatus === 'fail' ? 'text-red-500' : 'text-on-surface-variant/60'"
                          >{{ embeddingSummaryText }}</span>
                        </div>
                        <div class="text-xs text-on-surface-variant mt-0.5 truncate pl-4">
                          {{ appConfig.embeddingModel.baseUrl || '请展开填写 Base URL、模型名和 API Key' }}
                        </div>
                        <div class="flex items-center gap-2 mt-1 pl-4 text-[11px] text-on-surface-variant/70">
                          <span>{{ appConfig.embeddingModel.dimensions ? `${appConfig.embeddingModel.dimensions} 维` : '模型默认维度' }}</span>
                          <span>·</span>
                          <span>每批 {{ appConfig.embeddingModel.batchSize ?? 10 }} 条</span>
                        </div>
                      </div>
                      <div class="flex items-center gap-1 flex-shrink-0 ml-2">
                        <button
                          type="button"
                          class="flex items-center justify-center w-7 h-7 rounded-lg text-on-surface-variant transition-colors hover:bg-surface-container disabled:opacity-50"
                          title="测试连接"
                          :disabled="embeddingTesting"
                          @click="testEmbedding"
                        >
                          <span v-if="embeddingTesting" class="material-symbols-outlined text-[16px] animate-spin">progress_activity</span>
                          <KbIcon v-else name="connect" class="w-4 h-4" />
                        </button>
                        <button
                          type="button"
                          class="flex items-center justify-center w-7 h-7 rounded-lg text-on-surface-variant transition-colors hover:bg-surface-container"
                          title="展开编辑"
                          @click="embeddingExpanded = true"
                        >
                          <KbIcon name="edit" class="w-4 h-4" />
                        </button>
                      </div>
                    </div>
                  </template>

                  <!-- 展开编辑模式 -->
                  <template v-else>
                    <div class="flex flex-col gap-3 p-1">
                      <div class="flex items-center justify-between">
                        <span class="text-xs font-medium text-on-surface">Embedding 配置</span>
                        <span class="flex items-center gap-1 text-[11px] text-on-surface-variant/70">
                          <span class="w-2 h-2 rounded-full" :class="embeddingStatusDotClass"></span>
                          {{ embeddingSummaryText }}
                        </span>
                      </div>
                      <div class="flex flex-col gap-1.5">
                        <label class="text-xs font-medium text-on-surface">Base URL</label>
                        <input
                          v-model="appConfig.embeddingModel.baseUrl"
                          type="text"
                          placeholder="https://api.example.com/v1"
                          class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                        />
                      </div>
                      <div class="grid grid-cols-[minmax(0,1fr)_130px_150px] gap-3">
                        <div class="flex flex-col gap-1.5">
                          <label class="text-xs font-medium text-on-surface">模型名</label>
                          <input
                            v-model="appConfig.embeddingModel.modelName"
                            type="text"
                            placeholder="text-embedding-3-small"
                            class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                          />
                        </div>
                        <div class="flex flex-col gap-1.5">
                          <label class="text-xs font-medium text-on-surface">固定维度（可选）</label>
                          <input
                            v-model.number="appConfig.embeddingModel.dimensions"
                            type="number"
                            min="1"
                            placeholder="模型默认"
                            class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                          />
                        </div>
                        <div class="flex flex-col gap-1.5">
                          <label class="text-xs font-medium text-on-surface" title="一次 Embedding API 请求中的文本分块数量，不是文件数量">
                            单次向量化条数
                          </label>
                          <input
                            v-model.number="appConfig.embeddingModel.batchSize"
                            type="number"
                            min="1"
                            max="2048"
                            step="1"
                            placeholder="10"
                            class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                          />
                        </div>
                      </div>
                      <div class="flex flex-col gap-1.5">
                        <label class="text-xs font-medium text-on-surface">API Key</label>
                        <input
                          v-model="appConfig.embeddingModel.apiKey"
                          type="password"
                          placeholder="本地无鉴权服务可留空"
                          class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                        />
                      </div>
                      <p class="text-[11px] leading-5 text-on-surface-variant/70">
                        单次向量化条数限制一次 Embedding API 请求包含的文本分块数量，不是索引文件数量。
                        供应商提示 batch size 超限时请调小；默认值为 10。
                      </p>
                      <div class="flex justify-end gap-2">
                        <button
                          type="button"
                          class="rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs text-on-surface transition-colors hover:bg-surface-container disabled:opacity-50"
                          :disabled="embeddingTesting"
                          @click="testEmbedding"
                        >{{ embeddingTesting ? '测试中…' : '测试连接' }}</button>
                        <button
                          type="button"
                          class="rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs text-on-surface transition-colors hover:bg-surface-container"
                          @click="embeddingExpanded = false"
                        >收起</button>
                        <button
                          type="button"
                          class="rounded-lg bg-primary px-3 py-1.5 text-xs text-on-primary transition-colors hover:bg-primary-container"
                          @click="saveEmbedding"
                        >保存</button>
                      </div>
                    </div>
                  </template>
                </div>
              </div>

              <!-- ======== OCR模型面板 ======== -->
              <div class="flex flex-col gap-3">
                <div class="flex items-center justify-between">
                  <h3 class="text-sm font-semibold text-on-surface">OCR模型列表</h3>
                  <button
                    v-if="!showAddOCRForm"
                    type="button"
                    class="flex items-center gap-1 rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs text-on-surface transition-colors hover:bg-surface-container"
                    @click="showAddOCRForm = true"
                  >
                    <span class="material-symbols-outlined text-[16px]">add</span>
                    添加
                  </button>
                </div>

                <!-- 添加表单 -->
                <div
                  v-if="showAddOCRForm"
                  class="rounded-xl border border-outline-variant/60 bg-surface-container-low p-4 flex flex-col gap-3"
                >
                  <div class="flex items-center gap-2">
                    <span class="text-xs text-on-surface-variant">来源：</span>
                    <button type="button"
                      class="px-3 py-1 rounded-md text-xs font-medium transition-colors"
                      :class="addOCRMode === 'preset' ? 'bg-primary text-on-primary' : 'bg-surface-container-high text-on-surface-variant'"
                      @click="addOCRMode = 'preset'"
                    >预设厂商</button>
                    <button type="button"
                      class="px-3 py-1 rounded-md text-xs font-medium transition-colors"
                      :class="addOCRMode === 'custom' ? 'bg-primary text-on-primary' : 'bg-surface-container-high text-on-surface-variant'"
                      @click="addOCRMode = 'custom'"
                    >自定义</button>
                  </div>
                  <div v-if="addOCRMode === 'preset'" class="flex flex-col gap-1.5">
                    <label class="text-xs font-medium text-on-surface">选择厂商</label>
                    <select v-model="selectedOCRPresetId"
                      class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary">
                      <option value="" disabled>选择厂商</option>
                      <option v-for="p in providers" :key="p.id" :value="p.id">{{ p.name }}</option>
                    </select>
                  </div>
                  <div class="flex flex-col gap-1.5">
                    <label class="text-xs font-medium text-on-surface">Base URL</label>
                    <input v-model="newOCRDraft.baseUrl" type="text" placeholder="https://api.example.com/v1"
                      class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary" />
                  </div>
                  <!-- 模型名：预设下拉 / 自定义输入 -->
                  <div class="flex flex-col gap-1.5">
                    <label class="text-xs font-medium text-on-surface">模型名</label>
                    <select
                      v-if="addOCRMode === 'preset'"
                      v-model="newOCRDraft.modelName"
                      class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary"
                    >
                      <option value="" disabled>选择模型</option>
                      <option
                        v-for="m in providers.find(p => p.id === selectedOCRPresetId)?.models ?? []"
                        :key="m"
                        :value="m"
                      >{{ m }}</option>
                    </select>
                    <input
                      v-else
                      v-model="newOCRDraft.modelName" type="text" placeholder="ocr-model"
                      class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary" />
                  </div>
                  <div class="flex flex-col gap-1.5">
                    <label class="text-xs font-medium text-on-surface">API Key</label>
                    <input v-model="newOCRDraft.apiKey" type="password" placeholder="sk-..."
                      class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary" />
                  </div>
                  <div class="flex flex-col gap-1.5">
                    <label class="text-xs font-medium text-on-surface">展示名称（可选）</label>
                    <input v-model="newOCRDraft.displayName" type="text" placeholder="用于前端下拉框显示"
                      class="w-full rounded-lg border border-outline-variant bg-white px-3 py-2 text-sm outline-none transition-colors focus:border-primary" />
                  </div>
                  <div class="flex justify-end gap-2 mt-1">
                    <button type="button"
                      class="rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs text-on-surface transition-colors hover:bg-surface-container"
                      @click="showAddOCRForm = false">取消</button>
                    <button type="button"
                      class="rounded-lg bg-primary px-3 py-1.5 text-xs text-on-primary transition-colors hover:bg-primary-container"
                      @click="confirmAddOCR">保存</button>
                  </div>
                </div>

                <!-- OCR 模型列表 -->
                <div v-if="appConfig.availableOCRModels.length === 0 && !showAddOCRForm" class="text-center py-10">
                  <p class="text-sm text-on-surface-variant/60">暂无配置，点击「添加」新增</p>
                </div>
                <div class="flex flex-col gap-2">
                  <div
                    v-for="(model, index) in appConfig.availableOCRModels"
                    :key="index"
                    class="rounded-xl border border-outline-variant/60 bg-surface-container-low p-3"
                  >
                    <template v-if="editingOCRIndex !== index">
                      <div class="flex items-start justify-between">
                        <div class="flex-1 min-w-0">
                          <div class="text-sm font-medium text-on-surface truncate">
                            {{ model.displayName || model.modelName || '未命名' }}
                          </div>
                          <div class="text-xs text-on-surface-variant mt-0.5 truncate">{{ model.baseUrl }}</div>
                          <div class="flex items-center gap-2 mt-1">
                            <span class="text-xs text-on-surface-variant/60">{{ model.modelName }}</span>
                            <span
                              class="text-[10px] rounded px-1.5 py-0.5 font-medium"
                              :class="model.source === 'preset' ? 'bg-blue-50 text-blue-600' : 'bg-amber-50 text-amber-600'"
                            >{{ model.source === 'preset' ? '预设' : '自定义' }}</span>
                          </div>
                        </div>
                        <div class="flex items-center gap-1 flex-shrink-0 ml-2">
                          <button type="button"
                            class="flex items-center justify-center w-7 h-7 rounded-lg text-on-surface-variant transition-colors hover:bg-surface-container"
                            @click="startEditOCR(index)">
                            <KbIcon name="edit" class="w-4 h-4" />
                          </button>
                          <button type="button"
                            class="flex items-center justify-center w-7 h-7 rounded-lg text-on-surface-variant transition-colors hover:bg-red-50 hover:text-red-500"
                            @click="deleteOCRModel(index)">
                            <KbIcon name="delete" class="w-4 h-4" />
                          </button>
                        </div>
                      </div>
                    </template>
                    <template v-else>
                      <div class="flex flex-col gap-2">
                        <div class="flex flex-col gap-1.5">
                          <label class="text-xs font-medium text-on-surface">Base URL</label>
                          <input v-model="editOCRDraft.baseUrl" type="text"
                            class="w-full rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs outline-none transition-colors focus:border-primary" />
                        </div>
                        <div class="flex flex-col gap-1.5">
                          <label class="text-xs font-medium text-on-surface">模型名</label>
                          <input v-model="editOCRDraft.modelName" type="text"
                            class="w-full rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs outline-none transition-colors focus:border-primary" />
                        </div>
                        <div class="flex flex-col gap-1.5">
                          <label class="text-xs font-medium text-on-surface">API Key</label>
                          <input v-model="editOCRDraft.apiKey" type="password"
                            class="w-full rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs outline-none transition-colors focus:border-primary" />
                        </div>
                        <div class="flex flex-col gap-1.5">
                          <label class="text-xs font-medium text-on-surface">展示名称</label>
                          <input v-model="editOCRDraft.displayName" type="text"
                            class="w-full rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs outline-none transition-colors focus:border-primary" />
                        </div>
                        <div class="flex justify-end gap-2 mt-1">
                          <button type="button"
                            class="rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs text-on-surface transition-colors hover:bg-surface-container"
                            @click="cancelEditOCR">取消</button>
                          <button type="button"
                            class="rounded-lg bg-primary px-3 py-1.5 text-xs text-on-primary transition-colors hover:bg-primary-container"
                            @click="confirmEditOCR(index)">保存</button>
                        </div>
                      </div>
                    </template>
                  </div>
                </div>
              </div>
              </template>

              <!-- ============ MCP 服务分区 ============ -->
              <template v-else-if="activeSection === 'mcp'">
              <div class="flex flex-col gap-3">
                <!-- 固定 h-8 起始高度：「添加」按钮仅在「我的 MCP」出现，避免切换标签时表头高度变化导致下方内容上下抖动 -->
                <div class="flex items-center justify-between h-8">
                  <!-- 子标签：我的 MCP / MCP 市场 -->
                  <div class="flex items-center rounded-lg bg-surface-container-high p-0.5">
                    <button type="button"
                      class="px-3 py-1 rounded-md text-xs font-medium transition-colors"
                      :class="mcpTab === 'mine' ? 'bg-white text-on-surface shadow-sm' : 'text-on-surface-variant'"
                      @click="mcpTab = 'mine'">我的 MCP</button>
                    <button type="button"
                      class="px-3 py-1 rounded-md text-xs font-medium transition-colors"
                      :class="mcpTab === 'market' ? 'bg-white text-on-surface shadow-sm' : 'text-on-surface-variant'"
                      @click="mcpTab = 'market'">MCP 市场</button>
                  </div>
                  <button
                    v-if="mcpTab === 'mine' && !showAddMcp"
                    type="button"
                    class="flex items-center gap-1 rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs text-on-surface transition-colors hover:bg-surface-container"
                    @click="showAddMcp = true"
                  >
                    <span class="material-symbols-outlined text-[16px]">add</span>
                    添加
                  </button>
                </div>

                <!-- ============ 我的 MCP ============ -->
                <template v-if="mcpTab === 'mine'">

                <!-- 添加：粘贴 JSON -->
                <div
                  v-if="showAddMcp"
                  class="rounded-xl border border-outline-variant/60 bg-surface-container-low p-4 flex flex-col gap-3"
                >
                  <p class="text-xs text-on-surface-variant/80 leading-relaxed">
                    粘贴 MCP 配置（JSON）。兼容 Claude Desktop / Cursor 的
                    <code class="rounded bg-surface-container-high px-1 py-0.5">{ "mcpServers": { ... } }</code>
                    对象格式，也支持数组格式；同名服务会被覆盖。
                  </p>
                  <textarea
                    v-model="addMcpJsonText"
                    spellcheck="false"
                    placeholder='{
  "mcpServers": {
    "fetch": {
      "command": "uvx",
      "args": ["mcp-server-fetch"]
    }
  }
}'
                    class="w-full h-56 rounded-xl border border-outline-variant bg-white px-3 py-2 text-xs font-mono leading-relaxed outline-none transition-colors focus:border-primary resize-none"
                  ></textarea>
                  <div class="flex justify-end gap-2">
                    <button type="button"
                      class="rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs text-on-surface transition-colors hover:bg-surface-container"
                      @click="showAddMcp = false; addMcpJsonText = ''">取消</button>
                    <button type="button"
                      class="rounded-lg bg-primary px-3 py-1.5 text-xs text-on-primary transition-colors hover:bg-primary-container"
                      @click="confirmAddMcp">添加</button>
                  </div>
                </div>

                <!-- 空态 -->
                <div v-if="(appConfig.mcpServers ?? []).length === 0 && !showAddMcp" class="text-center py-10">
                  <p class="text-sm text-on-surface-variant/60">暂无 MCP 服务，点击「添加」粘贴配置</p>
                </div>

                <!-- MCP 服务列表：名字 + 红绿灯 + 启用开关 + 删除 -->
                <div class="flex flex-col gap-2">
                  <div
                    v-for="(mcp, index) in (appConfig.mcpServers ?? [])"
                    :key="index"
                    class="rounded-xl border border-outline-variant/60 bg-surface-container-low p-3"
                  >
                    <div class="flex items-center justify-between gap-2">
                      <div class="flex items-center gap-2 min-w-0">
                        <!-- 红绿灯 -->
                        <span
                          class="w-2.5 h-2.5 rounded-full flex-shrink-0"
                          :class="statusDotClass(mcp.name)"
                          :title="mcpStatusMap[mcp.name]?.lastError || statusText(mcp.name)"
                        ></span>
                        <span class="text-sm font-medium text-on-surface truncate">{{ mcp.name || '未命名' }}</span>
                        <span class="text-[11px] text-on-surface-variant/70 flex-shrink-0">{{ statusText(mcp.name) }}</span>
                      </div>
                      <div class="flex items-center gap-1 flex-shrink-0">
                        <button type="button"
                          class="flex items-center justify-center w-7 h-7 rounded-lg transition-colors hover:bg-surface-container"
                          :class="mcp.enabled ? 'text-primary' : 'text-on-surface-variant/50'"
                          :title="mcp.enabled ? '点击停用' : '点击启用'"
                          @click="toggleMcpEnabled(index)">
                          <span class="material-symbols-outlined text-[18px]">{{ mcp.enabled ? 'toggle_on' : 'toggle_off' }}</span>
                        </button>
                        <button type="button"
                          class="flex items-center justify-center w-7 h-7 rounded-lg text-on-surface-variant transition-colors hover:bg-red-50 hover:text-red-500"
                          @click="deleteMcp(index)">
                          <KbIcon name="delete" class="w-4 h-4" />
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
                </template>

                <!-- ============ MCP 市场（预设一键安装） ============ -->
                <template v-else>
                  <p class="text-xs text-on-surface-variant/80 leading-relaxed">
                    常用 MCP 服务一键安装，等价于在「我的 MCP」粘贴对应 JSON 配置。
                    <code class="rounded bg-surface-container-high px-1 py-0.5">npx</code> 类需 Node.js 运行时，可到「环境配置」检测安装。
                  </p>
                  <div class="grid grid-cols-2 gap-2">
                    <div
                      v-for="item in marketItems"
                      :key="item.name"
                      class="rounded-xl border border-outline-variant/60 bg-surface-container-low p-3 flex flex-col gap-2"
                    >
                      <div class="flex items-center gap-2">
                        <span class="text-sm font-medium text-on-surface truncate">{{ item.name }}</span>
                        <span class="text-[10px] rounded px-1.5 py-0.5 font-medium bg-blue-50 text-blue-600">{{ item.tag }}</span>
                      </div>
                      <p class="text-xs text-on-surface-variant/70 leading-relaxed flex-1">{{ item.desc }}</p>
                      <button
                        v-if="isMarketInstalled(item)"
                        type="button" disabled
                        class="self-end flex items-center gap-1 rounded-lg border border-outline-variant bg-surface-container-high px-3 py-1 text-xs text-on-surface-variant/50 cursor-not-allowed">
                        <span class="material-symbols-outlined text-[16px]">check</span>
                        已安装
                      </button>
                      <button
                        v-else
                        type="button"
                        class="self-end flex items-center gap-1 rounded-lg bg-primary px-3 py-1 text-xs text-on-primary transition-colors hover:bg-primary-container"
                        @click="installMarketMcp(item)">
                        <span class="material-symbols-outlined text-[16px]">download</span>
                        一键安装
                      </button>
                    </div>
                  </div>
                </template>
              </div>
              </template>

              <!-- ============ Skill 分区（专家提示词包） ============ -->
              <template v-else-if="activeSection === 'skill'">
              <div class="flex flex-col gap-3">
                <!-- 固定 h-8 起始高度，避免切换标签时表头高度变化导致抖动 -->
                <div class="flex items-center justify-between h-8">
                  <!-- 子标签：我的 SKILL / SKILL 市场 -->
                  <div class="flex items-center rounded-lg bg-surface-container-high p-0.5">
                    <button type="button"
                      class="px-3 py-1 rounded-md text-xs font-medium transition-colors"
                      :class="skillTab === 'mine' ? 'bg-white text-on-surface shadow-sm' : 'text-on-surface-variant'"
                      @click="skillTab = 'mine'">我的 SKILL</button>
                    <button type="button"
                      class="px-3 py-1 rounded-md text-xs font-medium transition-colors"
                      :class="skillTab === 'market' ? 'bg-white text-on-surface shadow-sm' : 'text-on-surface-variant'"
                      @click="skillTab = 'market'">SKILL 市场</button>
                  </div>
                  <button
                    v-if="skillTab === 'mine'"
                    type="button"
                    class="flex items-center gap-1 rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs text-on-surface transition-colors hover:bg-surface-container disabled:opacity-50"
                    :disabled="skillImporting"
                    @click="onSkillBrowse"
                  >
                    <span class="material-symbols-outlined text-[16px]">folder_open</span>
                    选择文件夹
                  </button>
                </div>

                <!-- ============ 我的 SKILL ============ -->
                <template v-if="skillTab === 'mine'">
                  <p class="text-xs text-on-surface-variant/80 leading-relaxed">
                    Skill 是「按需加载的专家提示词包」。每个 Skill 是一个含
                    <code class="rounded bg-surface-container-high px-1 py-0.5">SKILL.md</code> 的文件夹，
                    其 name / description 会进入系统提示，匹配任务时由模型自动加载。
                  </p>

                  <!-- 拖拽导入区（也可点击走原生选择） -->
                  <div
                    class="rounded-xl border-2 border-dashed px-4 py-6 flex flex-col items-center justify-center gap-1.5 text-center transition-colors cursor-pointer"
                    :class="skillDragOver ? 'border-primary bg-primary/5' : 'border-outline-variant/60 bg-surface-container-low hover:bg-surface-container'"
                    @dragover.prevent="skillDragOver = true"
                    @dragleave.prevent="skillDragOver = false"
                    @drop="onSkillDrop"
                    @click="onSkillBrowse"
                  >
                    <span class="material-symbols-outlined text-[28px] text-on-surface-variant/60">
                      {{ skillImporting ? 'hourglass_top' : 'upload' }}
                    </span>
                    <p class="text-sm text-on-surface">{{ skillImporting ? '导入中…' : '拖入 Skill 文件夹，或点击选择' }}</p>
                    <p class="text-[11px] text-on-surface-variant/60">文件夹根目录须包含 SKILL.md</p>
                  </div>

                  <!-- 空态 -->
                  <div v-if="skills.length === 0 && !skillsLoading" class="text-center py-6">
                    <p class="text-sm text-on-surface-variant/60">暂无 Skill，拖入文件夹导入</p>
                  </div>

                  <!-- Skill 列表：名称 + 描述 + 删除 -->
                  <div class="flex flex-col gap-2">
                    <div
                      v-for="s in skills"
                      :key="s.name"
                      class="rounded-xl border border-outline-variant/60 bg-surface-container-low p-3"
                    >
                      <div class="flex items-start justify-between gap-2">
                        <div class="flex flex-col gap-0.5 min-w-0">
                          <span class="text-sm font-medium text-on-surface truncate">{{ s.name }}</span>
                          <span class="text-xs text-on-surface-variant/70 leading-relaxed line-clamp-2">
                            {{ s.description || '（无描述）' }}
                          </span>
                        </div>
                        <button type="button"
                          class="flex items-center justify-center w-7 h-7 rounded-lg text-on-surface-variant transition-colors hover:bg-red-50 hover:text-red-500 flex-shrink-0"
                          @click="removeSkill(s.name)">
                          <KbIcon name="delete" class="w-4 h-4" />
                        </button>
                      </div>
                    </div>
                  </div>
                </template>

                <!-- ============ SKILL 市场（占位） ============ -->
                <template v-else>
                  <div class="text-center py-12 flex flex-col items-center gap-2">
                    <span class="material-symbols-outlined text-[32px] text-on-surface-variant/40">storefront</span>
                    <p class="text-sm text-on-surface-variant/60">SKILL 市场建设中，敬请期待</p>
                  </div>
                </template>
              </div>
              </template>

              <!-- ============ 编辑 MCP 分区（知识库 AI 编辑 agent 专用，作用于 kbMcpServers） ============ -->
              <template v-else-if="activeSection === 'kbmcp'">
              <div class="flex flex-col gap-3">
                <div class="flex items-center justify-between h-8">
                  <div class="flex items-center rounded-lg bg-surface-container-high p-0.5">
                    <button type="button"
                      class="px-3 py-1 rounded-md text-xs font-medium transition-colors"
                      :class="kbMcpTab === 'mine' ? 'bg-white text-on-surface shadow-sm' : 'text-on-surface-variant'"
                      @click="kbMcpTab = 'mine'">我的 MCP</button>
                    <button type="button"
                      class="px-3 py-1 rounded-md text-xs font-medium transition-colors"
                      :class="kbMcpTab === 'market' ? 'bg-white text-on-surface shadow-sm' : 'text-on-surface-variant'"
                      @click="kbMcpTab = 'market'">MCP 市场</button>
                  </div>
                  <button
                    v-if="kbMcpTab === 'mine' && !showAddKbMcp"
                    type="button"
                    class="flex items-center gap-1 rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs text-on-surface transition-colors hover:bg-surface-container"
                    @click="showAddKbMcp = true"
                  >
                    <span class="material-symbols-outlined text-[16px]">add</span>
                    添加
                  </button>
                </div>

                <p class="text-xs text-on-surface-variant/70 leading-relaxed">
                  这里配置的 MCP 仅供<b>知识库 AI 编辑</b>使用（如搜索、资料检索），与主对话的 MCP 相互独立。
                </p>

                <!-- ============ 我的 MCP ============ -->
                <template v-if="kbMcpTab === 'mine'">
                <div
                  v-if="showAddKbMcp"
                  class="rounded-xl border border-outline-variant/60 bg-surface-container-low p-4 flex flex-col gap-3"
                >
                  <p class="text-xs text-on-surface-variant/80 leading-relaxed">
                    粘贴 MCP 配置（JSON）。兼容 Claude Desktop / Cursor 的
                    <code class="rounded bg-surface-container-high px-1 py-0.5">{ "mcpServers": { ... } }</code>
                    对象格式，也支持数组格式；同名服务会被覆盖。
                  </p>
                  <textarea
                    v-model="addKbMcpJsonText"
                    spellcheck="false"
                    placeholder='{
  "mcpServers": {
    "tavily": {
      "command": "npx",
      "args": ["-y", "tavily-mcp"],
      "env": { "TAVILY_API_KEY": "tvly-..." }
    }
  }
}'
                    class="w-full h-56 rounded-xl border border-outline-variant bg-white px-3 py-2 text-xs font-mono leading-relaxed outline-none transition-colors focus:border-primary resize-none"
                  ></textarea>
                  <div class="flex justify-end gap-2">
                    <button type="button"
                      class="rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs text-on-surface transition-colors hover:bg-surface-container"
                      @click="showAddKbMcp = false; addKbMcpJsonText = ''">取消</button>
                    <button type="button"
                      class="rounded-lg bg-primary px-3 py-1.5 text-xs text-on-primary transition-colors hover:bg-primary-container"
                      @click="confirmAddKbMcp">添加</button>
                  </div>
                </div>

                <div v-if="(appConfig.kbMcpServers ?? []).length === 0 && !showAddKbMcp" class="text-center py-10">
                  <p class="text-sm text-on-surface-variant/60">暂无 MCP 服务，点击「添加」粘贴配置</p>
                </div>

                <div class="flex flex-col gap-2">
                  <div
                    v-for="(mcp, index) in (appConfig.kbMcpServers ?? [])"
                    :key="index"
                    class="rounded-xl border border-outline-variant/60 bg-surface-container-low p-3"
                  >
                    <div class="flex items-center justify-between gap-2">
                      <div class="flex items-center gap-2 min-w-0">
                        <span
                          class="w-2.5 h-2.5 rounded-full flex-shrink-0"
                          :class="kbStatusDotClass(mcp.name)"
                          :title="kbMcpStatusMap[mcp.name]?.lastError || kbStatusText(mcp.name)"
                        ></span>
                        <span class="text-sm font-medium text-on-surface truncate">{{ mcp.name || '未命名' }}</span>
                        <span class="text-[11px] text-on-surface-variant/70 flex-shrink-0">{{ kbStatusText(mcp.name) }}</span>
                      </div>
                      <div class="flex items-center gap-1 flex-shrink-0">
                        <button type="button"
                          class="flex items-center justify-center w-7 h-7 rounded-lg transition-colors hover:bg-surface-container"
                          :class="mcp.enabled ? 'text-primary' : 'text-on-surface-variant/50'"
                          :title="mcp.enabled ? '点击停用' : '点击启用'"
                          @click="toggleKbMcpEnabled(index)">
                          <span class="material-symbols-outlined text-[18px]">{{ mcp.enabled ? 'toggle_on' : 'toggle_off' }}</span>
                        </button>
                        <button type="button"
                          class="flex items-center justify-center w-7 h-7 rounded-lg text-on-surface-variant transition-colors hover:bg-red-50 hover:text-red-500"
                          @click="deleteKbMcp(index)">
                          <KbIcon name="delete" class="w-4 h-4" />
                        </button>
                      </div>
                    </div>
                  </div>
                </div>
                </template>

                <!-- ============ MCP 市场（预设一键安装到编辑 MCP） ============ -->
                <template v-else>
                  <p class="text-xs text-on-surface-variant/80 leading-relaxed">
                    常用 MCP 服务一键安装到「编辑 MCP」，等价于在「我的 MCP」粘贴对应 JSON。
                    <code class="rounded bg-surface-container-high px-1 py-0.5">npx</code> 类需 Node.js 运行时，可到「环境配置」检测安装。
                  </p>
                  <div class="grid grid-cols-2 gap-2">
                    <div
                      v-for="item in marketItems"
                      :key="item.name"
                      class="rounded-xl border border-outline-variant/60 bg-surface-container-low p-3 flex flex-col gap-2"
                    >
                      <div class="flex items-center gap-2">
                        <span class="text-sm font-medium text-on-surface truncate">{{ item.name }}</span>
                        <span class="text-[10px] rounded px-1.5 py-0.5 font-medium bg-blue-50 text-blue-600">{{ item.tag }}</span>
                      </div>
                      <p class="text-xs text-on-surface-variant/70 leading-relaxed flex-1">{{ item.desc }}</p>
                      <button
                        v-if="isMarketKbInstalled(item)"
                        type="button" disabled
                        class="self-end flex items-center gap-1 rounded-lg border border-outline-variant bg-surface-container-high px-3 py-1 text-xs text-on-surface-variant/50 cursor-not-allowed">
                        <span class="material-symbols-outlined text-[16px]">check</span>
                        已安装
                      </button>
                      <button
                        v-else
                        type="button"
                        class="self-end flex items-center gap-1 rounded-lg bg-primary px-3 py-1 text-xs text-on-primary transition-colors hover:bg-primary-container"
                        @click="installMarketKbMcp(item)">
                        <span class="material-symbols-outlined text-[16px]">download</span>
                        一键安装
                      </button>
                    </div>
                  </div>
                </template>
              </div>
              </template>

              <!-- ============ 编辑 Skill 分区（知识库 AI 编辑 agent 专用，走 /api/kb-skills） ============ -->
              <template v-else-if="activeSection === 'kbskill'">
              <div class="flex flex-col gap-3">
                <div class="flex items-center justify-between h-8">
                  <div class="flex items-center rounded-lg bg-surface-container-high p-0.5">
                    <button type="button"
                      class="px-3 py-1 rounded-md text-xs font-medium transition-colors"
                      :class="kbSkillTab === 'mine' ? 'bg-white text-on-surface shadow-sm' : 'text-on-surface-variant'"
                      @click="kbSkillTab = 'mine'">我的 SKILL</button>
                    <button type="button"
                      class="px-3 py-1 rounded-md text-xs font-medium transition-colors"
                      :class="kbSkillTab === 'market' ? 'bg-white text-on-surface shadow-sm' : 'text-on-surface-variant'"
                      @click="kbSkillTab = 'market'">SKILL 市场</button>
                  </div>
                  <button
                    v-if="kbSkillTab === 'mine'"
                    type="button"
                    class="flex items-center gap-1 rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs text-on-surface transition-colors hover:bg-surface-container disabled:opacity-50"
                    :disabled="kbSkillImporting"
                    @click="onKbSkillBrowse"
                  >
                    <span class="material-symbols-outlined text-[16px]">folder_open</span>
                    选择文件夹
                  </button>
                </div>

                <template v-if="kbSkillTab === 'mine'">
                  <p class="text-xs text-on-surface-variant/80 leading-relaxed">
                    这里的 Skill 仅供<b>知识库 AI 编辑</b>使用（如专业绘图规范），与主对话的 Skill 相互独立。
                    每个 Skill 是一个含 <code class="rounded bg-surface-container-high px-1 py-0.5">SKILL.md</code> 的文件夹。
                  </p>

                  <div
                    class="rounded-xl border-2 border-dashed px-4 py-6 flex flex-col items-center justify-center gap-1.5 text-center transition-colors cursor-pointer"
                    :class="kbSkillDragOver ? 'border-primary bg-primary/5' : 'border-outline-variant/60 bg-surface-container-low hover:bg-surface-container'"
                    @dragover.prevent="kbSkillDragOver = true"
                    @dragleave.prevent="kbSkillDragOver = false"
                    @drop="onKbSkillDrop"
                    @click="onKbSkillBrowse"
                  >
                    <span class="material-symbols-outlined text-[28px] text-on-surface-variant/60">
                      {{ kbSkillImporting ? 'hourglass_top' : 'upload' }}
                    </span>
                    <p class="text-sm text-on-surface">{{ kbSkillImporting ? '导入中…' : '拖入 Skill 文件夹，或点击选择' }}</p>
                    <p class="text-[11px] text-on-surface-variant/60">文件夹根目录须包含 SKILL.md</p>
                  </div>

                  <div v-if="kbSkills.length === 0 && !kbSkillsLoading" class="text-center py-6">
                    <p class="text-sm text-on-surface-variant/60">暂无 Skill，拖入文件夹导入</p>
                  </div>

                  <div class="flex flex-col gap-2">
                    <div
                      v-for="s in kbSkills"
                      :key="s.name"
                      class="rounded-xl border border-outline-variant/60 bg-surface-container-low p-3"
                    >
                      <div class="flex items-start justify-between gap-2">
                        <div class="flex flex-col gap-0.5 min-w-0">
                          <span class="text-sm font-medium text-on-surface truncate">{{ s.name }}</span>
                          <span class="text-xs text-on-surface-variant/70 leading-relaxed line-clamp-2">
                            {{ s.description || '（无描述）' }}
                          </span>
                        </div>
                        <button type="button"
                          class="flex items-center justify-center w-7 h-7 rounded-lg text-on-surface-variant transition-colors hover:bg-red-50 hover:text-red-500 flex-shrink-0"
                          @click="removeKbSkill(s.name)">
                          <KbIcon name="delete" class="w-4 h-4" />
                        </button>
                      </div>
                    </div>
                  </div>
                </template>

                <template v-else>
                  <div class="text-center py-12 flex flex-col items-center gap-2">
                    <span class="material-symbols-outlined text-[32px] text-on-surface-variant/40">storefront</span>
                    <p class="text-sm text-on-surface-variant/60">SKILL 市场建设中，敬请期待</p>
                  </div>
                </template>
              </div>
              </template>

              <!-- ============ 环境配置分区 ============ -->
              <template v-else-if="activeSection === 'env'">
              <div class="flex flex-col gap-3">
                <div class="flex items-center justify-between">
                  <h3 class="text-sm font-semibold text-on-surface">运行时环境</h3>
                  <button
                    type="button"
                    class="flex items-center gap-1 rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs text-on-surface transition-colors hover:bg-surface-container disabled:opacity-50"
                    :disabled="runtimesLoading"
                    @click="loadRuntimes"
                  >
                    <span class="material-symbols-outlined text-[16px]" :class="runtimesLoading ? 'animate-spin' : ''">refresh</span>
                    重新检测
                  </button>
                </div>
                <p class="text-xs text-on-surface-variant/80 leading-relaxed">
                  本地（stdio）MCP 需要对应运行时：<code class="rounded bg-surface-container-high px-1 py-0.5">npx</code> 依赖 Node.js，
                  <code class="rounded bg-surface-container-high px-1 py-0.5">uvx</code> 依赖 uv。一键安装通过 winget 完成，
                  安装后通常需<b>重启本应用</b>才能识别新命令。
                </p>

                <div v-if="runtimesLoading && runtimes.length === 0" class="text-center py-10">
                  <p class="text-sm text-on-surface-variant/60">检测中…</p>
                </div>

                <div class="flex flex-col gap-2">
                  <div
                    v-for="rt in runtimes"
                    :key="rt.id"
                    class="rounded-xl border border-outline-variant/60 bg-surface-container-low p-3 flex items-center justify-between gap-2"
                  >
                    <div class="flex items-center gap-2 min-w-0">
                      <span
                        class="w-2.5 h-2.5 rounded-full flex-shrink-0"
                        :class="rt.installed ? 'bg-green-500' : 'bg-gray-300'"
                      ></span>
                      <span class="text-sm font-medium text-on-surface">{{ rt.displayName }}</span>
                      <span v-if="rt.installed" class="text-[11px] text-green-600 font-medium">已安装 {{ rt.version }}</span>
                      <span v-else class="text-[11px] text-on-surface-variant/60">未检测到（{{ rt.checkedCommand }}）</span>
                    </div>
                    <button
                      v-if="!rt.installed"
                      type="button"
                      class="flex items-center gap-1 rounded-lg bg-primary px-3 py-1.5 text-xs text-on-primary transition-colors hover:bg-primary-container disabled:opacity-50"
                      :disabled="installingId === rt.id"
                      @click="doInstall(rt.id)"
                    >
                      <span class="material-symbols-outlined text-[16px]">download</span>
                      {{ installingId === rt.id ? '安装中…' : '一键安装' }}
                    </button>
                  </div>
                </div>
              </div>
              </template>

              <!-- ============ 关于 / 卸载分区 ============ -->
              <template v-else-if="activeSection === 'about'">
              <div class="flex flex-col gap-4">
                <div>
                  <h3 class="text-sm font-semibold text-on-surface">关于</h3>
                  <p class="text-xs text-on-surface-variant/80 mt-1">TailorAgent · 桌面智能体</p>
                </div>

                <!-- 危险区：卸载应用 -->
                <div class="rounded-xl border border-red-200 bg-red-50/40 p-4 flex flex-col gap-2">
                  <div class="flex items-center gap-2">
                    <span class="material-symbols-outlined text-[18px] text-red-500">warning</span>
                    <h4 class="text-sm font-semibold text-red-600">卸载应用</h4>
                  </div>
                  <p class="text-xs text-on-surface-variant/80 leading-relaxed">
                    将卸载 TailorAgent 程序本体（会弹出系统卸载 / 管理员授权）。你可以选择是否同时删除本地数据。
                  </p>
                  <button
                    type="button"
                    class="self-start mt-1 flex items-center gap-1 rounded-lg bg-red-500 px-3 py-1.5 text-xs text-white transition-colors hover:bg-red-600"
                    @click="openUninstallConfirm"
                  >
                    <span class="material-symbols-outlined text-[16px]">delete_forever</span>
                    卸载 TailorAgent
                  </button>
                </div>
              </div>
              </template>
            </div>
          </div>

        </div>
      </div>
    </transition>
  </Teleport>

  <!-- 卸载确认弹窗 -->
  <Teleport to="body">
    <transition name="fade">
      <div
        v-if="uninstallConfirmOpen"
        class="fixed inset-0 z-[1100] flex items-center justify-center bg-black/40"
        @click.self="!uninstalling && (uninstallConfirmOpen = false)"
      >
        <div class="w-[420px] rounded-2xl bg-white shadow-2xl p-6 flex flex-col gap-4">
          <div class="flex items-center gap-2">
            <span class="material-symbols-outlined text-[20px] text-red-500">warning</span>
            <h3 class="text-base font-semibold text-on-surface">确认卸载 TailorAgent？</h3>
          </div>
          <p class="text-xs text-on-surface-variant/80 leading-relaxed">
            确认后应用将关闭并启动卸载。默认<b>保留</b>你的本地数据（配置、会话、工作区），仅清理可再生的缓存，重装后数据可恢复。
          </p>
          <label class="flex items-start gap-2 rounded-lg border border-outline-variant/60 bg-surface-container-low p-3 cursor-pointer">
            <input v-model="uninstallDeleteData" type="checkbox" class="mt-0.5 accent-red-500" />
            <span class="text-xs text-on-surface leading-relaxed">
              同时删除我的所有本地数据（配置、会话、工作区、API Key）
              <span class="text-red-500 font-medium">— 不可恢复</span>
            </span>
          </label>
          <div class="flex justify-end gap-2">
            <button
              type="button"
              class="rounded-lg border border-outline-variant bg-white px-3 py-1.5 text-xs text-on-surface transition-colors hover:bg-surface-container disabled:opacity-50"
              :disabled="uninstalling"
              @click="uninstallConfirmOpen = false"
            >取消</button>
            <button
              type="button"
              class="rounded-lg bg-red-500 px-3 py-1.5 text-xs text-white transition-colors hover:bg-red-600 disabled:opacity-60"
              :disabled="uninstalling"
              @click="doUninstall"
            >{{ uninstalling ? '正在卸载…' : '确认卸载' }}</button>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.2s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
