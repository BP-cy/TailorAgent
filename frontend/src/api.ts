// 统一的后端调用入口。开发期经 Vite 代理转发到 127.0.0.1:8080，
// 打包后前后端同源（同一端口），相对路径 /api 直接命中后端。
export interface HelloResponse {
  message: string
  time: string
}

export async function fetchHello(): Promise<HelloResponse> {
  const res = await fetch('/api/hello')
  if (!res.ok) {
    throw new Error(`后端返回 ${res.status}`)
  }
  return res.json()
}

/**
 * 上传图片到后端本地媒体目录，返回可直接用于 <img src> 的相对 URL（/media/xxx）。
 * 不用 base64 内联：保持正文干净，便于后续切片 / 向量化。
 */
export async function uploadImage(file: File): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  const res = await fetch('/api/upload', { method: 'POST', body: form })
  if (!res.ok) {
    const data = (await res.json().catch(() => null)) as { msg?: string } | null
    throw new Error(data?.msg || `上传失败（${res.status}）`)
  }
  const data = (await res.json()) as { url: string }
  return data.url
}

// ==================== 知识库（文件做真相源，相对路径做键） ====================

/** 文档索引状态。unindexed=未索引/脏；indexed=已切块建索引 */
export type KbStatus = 'unindexed' | 'processing' | 'indexed' | 'failed'
/** 树节点类型 */
export type KbNodeType = 'folder' | 'md' | 'file'

/** 知识库目录树节点（由后端扫描磁盘目录派生）。path 为相对 knowledge 根的相对路径（正斜杠）。 */
export interface KbNode {
  path: string          // 如 MD/工作/报告.md 或 MD/工作
  name: string
  type: KbNodeType
  status?: KbStatus     // 仅文件节点
  size?: number         // 仅文件节点
  mtime?: string        // 仅文件节点 ISO 8601
  children?: KbNode[]   // 仅文件夹节点
}

/** 单篇文档读取结果 */
export interface KbDoc {
  path: string
  name: string
  content: string
}

async function kbUnwrap<T>(res: Response, fallback: string): Promise<T> {
  const json = (await res.json().catch(() => null)) as { code: number; message?: string; data?: T } | null
  if (!res.ok || !json || json.code !== 1) {
    throw new Error(json?.message || `${fallback}（${res.status}）`)
  }
  return json.data as T
}

/** 获取某子树（MD/files）的嵌套目录树 */
export async function fetchKbTree(type: 'MD' | 'files' = 'MD'): Promise<KbNode[]> {
  const res = await fetch(`/api/knowledge/tree?type=${type}`)
  return kbUnwrap<KbNode[]>(res, '获取目录树失败')
}

/** 读取单篇文档正文 */
export async function fetchKbDoc(path: string): Promise<KbDoc> {
  const res = await fetch(`/api/knowledge/doc?path=${encodeURIComponent(path)}`)
  return kbUnwrap<KbDoc>(res, '获取文档失败')
}

/** 新建文档 */
export async function createKbDoc(path: string, content = ''): Promise<KbDoc> {
  const res = await fetch('/api/knowledge/doc', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path, content }),
  })
  return kbUnwrap<KbDoc>(res, '新建文档失败')
}

/** 保存文档（覆盖写 + 标脏） */
export async function saveKbDoc(path: string, content: string): Promise<void> {
  const res = await fetch(`/api/knowledge/doc?path=${encodeURIComponent(path)}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content }),
  })
  await kbUnwrap<string>(res, '保存文档失败')
}

/** 新建文件夹 */
export async function mkdirKb(path: string): Promise<void> {
  const res = await fetch('/api/knowledge/folder', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ path }),
  })
  await kbUnwrap<string>(res, '新建文件夹失败')
}

/** 移动 / 重命名 */
export async function renameKb(from: string, to: string): Promise<void> {
  const res = await fetch('/api/knowledge/rename', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ from, to }),
  })
  await kbUnwrap<string>(res, '移动失败')
}

/** 删除文档或文件夹 */
export async function deleteKb(path: string): Promise<void> {
  const res = await fetch(`/api/knowledge/doc?path=${encodeURIComponent(path)}`, { method: 'DELETE' })
  await kbUnwrap<string>(res, '删除失败')
}

/**
 * 导入文件到某文件夹（folder = MD/files 下的相对目录，根即 'MD'/'files'）。
 * 一次请求整批上传，同名由后端自动加后缀；返回成功导入的文件数。
 */
export async function uploadKbFiles(folder: string, files: File[]): Promise<number> {
  const form = new FormData()
  form.append('folder', folder)
  for (const f of files) form.append('files', f)
  const res = await fetch('/api/knowledge/upload', { method: 'POST', body: form })
  return kbUnwrap<number>(res, '导入文件失败')
}

export type KnowledgeIndexJobState = 'queued' | 'running' | 'completed' | 'failed'
export type KnowledgeIndexJobPhase = 'planning' | 'embedding' | 'writing' | 'completed'

/** 后端异步索引任务进度；completedFiles 表示已完成解析、切块和向量化的文件数。 */
export interface KnowledgeIndexJobStatus {
  jobId: string
  state: KnowledgeIndexJobState
  phase: KnowledgeIndexJobPhase
  totalFiles: number
  completedFiles: number
  currentPath?: string | null
  message: string
  error?: string | null
  startedAt?: string | null
  completedAt?: string | null
}

/** 启动异步索引（带 path 只重建该篇，否则建立全部待索引 Markdown 的文件队列）。 */
export async function indexKb(path?: string): Promise<KnowledgeIndexJobStatus> {
  const res = await fetch('/api/knowledge/index', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(path ? { path } : {}),
  })
  return kbUnwrap<KnowledgeIndexJobStatus>(res, '启动索引任务失败')
}

/** 查询指定索引任务进度。 */
export async function fetchKbIndexJob(jobId: string): Promise<KnowledgeIndexJobStatus> {
  const res = await fetch(`/api/knowledge/index/status?jobId=${encodeURIComponent(jobId)}`)
  return kbUnwrap<KnowledgeIndexJobStatus>(res, '查询索引进度失败')
}

/** 查询当前排队或运行中的索引任务，供面板重新打开时恢复进度。 */
export async function fetchActiveKbIndexJob(): Promise<KnowledgeIndexJobStatus | null> {
  const res = await fetch('/api/knowledge/index/active')
  return (await kbUnwrap<KnowledgeIndexJobStatus | null>(res, '查询活动索引任务失败')) ?? null
}

// ==================== 应用配置 ====================

/** 单个对话模型配置 */
export interface ChatModelCfg {
  baseUrl: string
  modelName: string
  apiKey: string
  displayName: string
  source: string          // "preset" | "custom"
  contextLength?: number      // 上下文窗口长度（token），占比 UI 的分母
  maxInputTokens?: number     // 最大输入长度（token），预留
  maxOutputTokens?: number    // 最大输出长度（token），预留
}

/** 单个 OCR 模型配置 */
export interface OCRModelCfg {
  baseUrl: string
  modelName: string
  apiKey: string
  displayName: string
  source: string
}

/** 知识库 KNN 检索使用的 OpenAI 兼容 Embedding 模型。 */
export interface EmbeddingModelCfg {
  baseUrl: string
  modelName: string
  apiKey: string
  dimensions?: number | null
  /** 一次 Embedding API 请求包含的文本分块数量，不是文件数量。 */
  batchSize?: number | null
}

/** 单个 MCP 服务配置 —— 对应后端 McpServerConfig */
export interface McpServerCfg {
  name: string
  transportType: string                // "stdio" | "streamable_http"
  command: string                       // stdio: 启动命令
  args: string[]                        // stdio: 命令参数
  env: Record<string, string>           // stdio: 子进程环境变量
  url: string                           // streamable_http: 远程端点
  headers: Record<string, string>       // streamable_http: 请求头
  enabled: boolean
}

/** AI 配置 —— 持久化到后端 app-config.json，通过 API 读写 */
export interface AppConfig {
  availableChatModels: ChatModelCfg[]
  availableOCRModels: OCRModelCfg[]
  embeddingModel: EmbeddingModelCfg
  mcpServers?: McpServerCfg[]
  /** 知识库 AI 编辑 agent 专用的 MCP 服务列表（与主对话隔离） */
  kbMcpServers?: McpServerCfg[]
  workingDir?: string
}

/** 获取当前 AI 配置 */
export async function fetchConfig(): Promise<AppConfig> {
  const res = await fetch('/api/config')
  if (!res.ok) {
    const err = (await res.json().catch(() => null)) as { message?: string } | null
    throw new Error(err?.message || `获取配置失败（${res.status}）`)
  }
  const data = (await res.json()) as { code: number; data: AppConfig }
  return data.data
}

/** 打开原生文件夹选择对话框，选中后自动保存到配置并返回更新后的配置 */
export async function selectWorkingDir(): Promise<AppConfig> {
  const res = await fetch('/api/config/select-working-dir', { method: 'POST' })
  if (!res.ok) {
    const err = (await res.json().catch(() => null)) as { message?: string } | null
    throw new Error(err?.message || `选择文件夹失败（${res.status}）`)
  }
  const data = (await res.json()) as { code: number; data: AppConfig }
  return data.data
}

/** 在 workspace 容器下新建一个 ws-日期-时间戳 工作区，设为 workingDir 并返回更新后的配置 */
export async function newWorkingDir(): Promise<AppConfig> {
  const res = await fetch('/api/config/new-working-dir', { method: 'POST' })
  if (!res.ok) {
    const err = (await res.json().catch(() => null)) as { message?: string } | null
    throw new Error(err?.message || `新建工作区失败（${res.status}）`)
  }
  const data = (await res.json()) as { code: number; data: AppConfig }
  return data.data
}

/**
 * 测试对话模型连通性 —— 后端用 baseUrl/apiKey/modelName 向 API 真发一句「你好」验证。
 * 返回 { ok, message }；ok=true 表示连通。后端异常码返回 HTTP 200 + code=-1，故按 code 判定。
 */
export async function testChatModel(
  cfg: Pick<ChatModelCfg, 'baseUrl' | 'apiKey' | 'modelName'>,
): Promise<{ ok: boolean; message: string }> {
  const res = await fetch('/api/config/test-connection', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(cfg),
  })
  const data = (await res.json().catch(() => null)) as { code: number; message?: string } | null
  return {
    ok: res.ok && data?.code === 1,
    message: data?.message || (res.ok ? '连接正常' : `连接失败（${res.status}）`),
  }
}

/** 测试 Embedding 端点，成功时返回服务实际输出的向量维度。 */
export async function testEmbeddingModel(
  cfg: EmbeddingModelCfg,
): Promise<{ ok: boolean; message: string; dimension?: number }> {
  const res = await fetch('/api/config/test-embedding', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(cfg),
  })
  const data = (await res.json().catch(() => null)) as
    { code: number; message?: string; data?: number } | null
  return {
    ok: res.ok && data?.code === 1,
    message: data?.message || (res.ok ? '连接正常' : `连接失败（${res.status}）`),
    dimension: data?.data,
  }
}

/** 保存 AI 配置到后端文件（全量写入） */
export async function saveConfig(config: AppConfig): Promise<AppConfig> {
  const res = await fetch('/api/config', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(config),
  })
  if (!res.ok) {
    const err = (await res.json().catch(() => null)) as { message?: string } | null
    throw new Error(err?.message || `保存配置失败（${res.status}）`)
  }
  const data = (await res.json()) as { code: number; data: AppConfig }
  return data.data
}

// ==================== 应用卸载 ====================

/**
 * 触发应用卸载 —— 后端启动独立清理脚本(等应用退出后 msiexec 卸载 + 按需删数据)并优雅关闭应用。
 * @param deleteData true=删除全部本地数据(配置/会话/工作区,不可恢复);false=仅清理缓存、保留数据供重装。
 */
export async function uninstallApp(deleteData: boolean): Promise<void> {
  const res = await fetch('/api/app/uninstall', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ deleteData }),
  })
  const data = (await res.json().catch(() => null)) as { code: number; message?: string } | null
  if (!res.ok || data?.code !== 1) {
    throw new Error(data?.message || `卸载启动失败（${res.status}）`)
  }
}

// ==================== MCP 连接状态 ====================

/** MCP 服务连接状态 —— 对应后端 McpServerStatusDto */
export interface McpServerStatus {
  name: string
  transportType: string
  status: 'CONNECTED' | 'FAILED' | 'CONNECTING' | 'DISABLED'
  lastError?: string
  updatedAt: number
}

/** 获取所有 MCP 服务的实时连接状态（红绿灯数据源） */
export async function fetchMcpStatus(): Promise<McpServerStatus[]> {
  const res = await fetch('/api/mcp/status')
  if (!res.ok) {
    const err = (await res.json().catch(() => null)) as { message?: string } | null
    throw new Error(err?.message || `获取 MCP 状态失败（${res.status}）`)
  }
  const data = (await res.json()) as { code: number; data: McpServerStatus[] }
  return data.data ?? []
}

/** 获取知识库编辑 agent 的 MCP 服务实时连接状态（对应 kbMcpServers） */
export async function fetchKbMcpStatus(): Promise<McpServerStatus[]> {
  const res = await fetch('/api/mcp/kb-status')
  if (!res.ok) {
    const err = (await res.json().catch(() => null)) as { message?: string } | null
    throw new Error(err?.message || `获取知识库 MCP 状态失败（${res.status}）`)
  }
  const data = (await res.json()) as { code: number; data: McpServerStatus[] }
  return data.data ?? []
}

// ==================== Skill（专家提示词包） ====================

/** Skill 信息 —— 对应后端 SkillInfo（name + description） */
export interface SkillInfo {
  name: string
  description: string
}

/** Skill 导入用的单个文件：相对路径 + base64 内容 */
export interface SkillImportFile {
  path: string
  contentBase64: string
}

/** 写操作统一解包：code !== 1 视为失败 */
function unwrapSkills(json: { code: number; message?: string; data?: SkillInfo[] }): SkillInfo[] {
  if (json.code !== 1) throw new Error(json.message || '操作失败')
  return json.data ?? []
}

/** 获取已安装的 Skill 列表 */
export async function fetchSkills(): Promise<SkillInfo[]> {
  const res = await fetch('/api/skills')
  if (!res.ok) throw new Error(`获取 Skill 列表失败（${res.status}）`)
  const data = (await res.json()) as { code: number; data?: SkillInfo[] }
  return data.data ?? []
}

/** 拖入文件夹导入 Skill（前端读取文件 → base64 上传） */
export async function importSkillFiles(folderName: string, files: SkillImportFile[]): Promise<SkillInfo[]> {
  const res = await fetch('/api/skills/import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ folderName, files }),
  })
  const json = (await res.json()) as { code: number; message?: string; data?: SkillInfo[] }
  return unwrapSkills(json)
}

/** 通过原生文件夹选择对话框导入 Skill（用户取消则返回原列表） */
export async function importSkillDir(): Promise<SkillInfo[]> {
  const res = await fetch('/api/skills/import-dir', { method: 'POST' })
  const json = (await res.json()) as { code: number; message?: string; data?: SkillInfo[] }
  return unwrapSkills(json)
}

/** 删除某 Skill，返回最新列表 */
export async function deleteSkill(name: string): Promise<SkillInfo[]> {
  const res = await fetch(`/api/skills/${encodeURIComponent(name)}`, { method: 'DELETE' })
  const json = (await res.json()) as { code: number; message?: string; data?: SkillInfo[] }
  return unwrapSkills(json)
}

// ==================== 知识库编辑 Skill（/api/kb-skills，与主对话隔离） ====================

/** 获取知识库编辑 agent 已安装的 Skill 列表 */
export async function fetchKbSkills(): Promise<SkillInfo[]> {
  const res = await fetch('/api/kb-skills')
  if (!res.ok) throw new Error(`获取知识库 Skill 列表失败（${res.status}）`)
  const data = (await res.json()) as { code: number; data?: SkillInfo[] }
  return data.data ?? []
}

/** 拖入文件夹导入知识库 Skill（前端读取文件 → base64 上传） */
export async function importKbSkillFiles(folderName: string, files: SkillImportFile[]): Promise<SkillInfo[]> {
  const res = await fetch('/api/kb-skills/import', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ folderName, files }),
  })
  const json = (await res.json()) as { code: number; message?: string; data?: SkillInfo[] }
  return unwrapSkills(json)
}

/** 通过原生文件夹选择对话框导入知识库 Skill（用户取消则返回原列表） */
export async function importKbSkillDir(): Promise<SkillInfo[]> {
  const res = await fetch('/api/kb-skills/import-dir', { method: 'POST' })
  const json = (await res.json()) as { code: number; message?: string; data?: SkillInfo[] }
  return unwrapSkills(json)
}

/** 删除某知识库 Skill，返回最新列表 */
export async function deleteKbSkill(name: string): Promise<SkillInfo[]> {
  const res = await fetch(`/api/kb-skills/${encodeURIComponent(name)}`, { method: 'DELETE' })
  const json = (await res.json()) as { code: number; message?: string; data?: SkillInfo[] }
  return unwrapSkills(json)
}

// ==================== 环境配置（运行时检测/安装） ====================

/** 本地运行时检测结果 —— 对应后端 RuntimeStatusDto */
export interface RuntimeStatus {
  id: string                // "node" | "uv"
  displayName: string
  installed: boolean
  version?: string
  checkedCommand: string
}

/** 检测本地运行时（Node.js / uv）安装情况 */
export async function fetchRuntimes(): Promise<RuntimeStatus[]> {
  const res = await fetch('/api/env/runtimes')
  if (!res.ok) {
    const err = (await res.json().catch(() => null)) as { message?: string } | null
    throw new Error(err?.message || `检测运行时失败（${res.status}）`)
  }
  const data = (await res.json()) as { code: number; data: RuntimeStatus[] }
  return data.data ?? []
}

/**
 * 通过 winget 一键安装指定运行时。
 * 返回后端提示文案（成功/失败均有 message）；code=1 视为已启动安装。
 */
export async function installRuntime(runtimeId: string): Promise<{ ok: boolean; message: string }> {
  const res = await fetch('/api/env/install', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ runtimeId }),
  })
  const data = (await res.json().catch(() => null)) as { code: number; message?: string } | null
  return { ok: data?.code === 1, message: data?.message || (res.ok ? '已启动安装' : `安装失败（${res.status}）`) }
}

// ==================== AI 对话 ====================

export interface ProviderInfo {
  id: string
  name: string
  baseUrl: string
  defaultModel: string
  models: string[]
}

/**
 * 会话事件 —— 轮次内的原子消息单元(异构)。
 * role 决定左右(user 右、其余左);type 决定用哪个组件渲染(text/tool_call/tool_result)。
 * 工具名、读/写操作等细节放 payload(JSON 字符串),不新增 type。
 */
export interface ChatEvent {
  id?: number
  turnId?: number
  sessionId?: number
  seq?: number
  role: 'user' | 'assistant' | 'tool' | 'system'
  type: string
  content?: string
  payload?: string
  status?: string
  createdAt?: string
  /** 仅前端:流式期间标记该 reasoning 事件仍在接收(true=正在思考中);落库重放时无此字段 */
  streaming?: boolean
}

/** 会话(侧边栏一项) */
export interface ChatSession {
  id: number
  title: string
  createdAt: string
  updatedAt: string
}

/**
 * 对话请求。
 * 历史由后端从数据库投影,前端每轮只发本轮 content。
 * sessionId 为 null 表示新建会话;modelIndex 指定用 availableChatModels 中第几个模型。
 */
export interface ChatRequest {
  sessionId: number | null
  content: string
  modelIndex: number
}

/** tool_call 事件负载(对应后端 ChatEvent.payload 的 JSON,也是 SSE tool_call 事件的 data) */
export interface ToolCallPayload {
  callId: string
  toolName: string
  source: 'local' | 'mcp'
  args: string            // 模型给出的入参(原始 JSON 字符串)
}

/** tool_result 事件负载 */
export interface ToolResultPayload {
  callId: string
  status: 'success' | 'error'
  result?: string         // 成功时(可能被截断)
  error?: string          // 失败时
}

/** 对话 SSE 流式回调 —— 按事件名分发 */
export interface ChatStreamHandlers {
  /** 会话/轮次已创建,拿到 sessionId(新建会话场景下用于选中 + 刷新列表) */
  onStart?: (d: { sessionId: number; turnId: number }) => void
  /** 模型发起一次工具调用 */
  onToolCall?: (d: ToolCallPayload) => void
  /** 一次工具调用返回 */
  onToolResult?: (d: ToolResultPayload) => void
  /** 助手思考内容增量(reasoning 模型的 reasoningContent,逐块流式;在 text 之前送达) */
  onReasoning?: (d: { eventId?: number; content: string }) => void
  /** 助手正文增量(逐块流式,需前端累加) */
  onText?: (d: { eventId?: number; content: string }) => void
  /** 本轮正常结束;contextTokens 为本轮结束后的上下文占用估算(供占比条刷新) */
  onDone?: (d: { sessionId: number; contextTokens?: number }) => void
  /** 本轮被用户主动停止 */
  onCancelled?: (d: { sessionId: number }) => void
  /** 出错(含模型/配置错误);收到后流即终止 */
  onError: (message: string) => void
}

/** 获取厂商列表（下拉框数据源） */
export async function fetchProviders(): Promise<ProviderInfo[]> {
  const res = await fetch('/api/config/providers')
  if (!res.ok) throw new Error(`获取提供商列表失败（${res.status}）`)
  const data = (await res.json()) as { code: number; data: ProviderInfo[] }
  return data.data
}

/**
 * 发送对话消息 —— SSE 流式。
 *
 * 通过 fetch + ReadableStream 消费后端 SSE,按事件名(start/tool_call/tool_result/text/done/error)
 * 分发到对应回调;每个事件的 data 是一行 JSON。沿用 streamAiEdit 的解析骨架。
 * 工具调用过程(tool_call/tool_result)边执行边推送,最终文本由 text 事件送达。
 */
export async function sendChatStream(
  req: ChatRequest,
  handlers: ChatStreamHandlers,
  signal?: AbortSignal,
): Promise<void> {
  // 收到一个完整 SSE 事件后按事件名分发
  function dispatch(eventType: string, data: string) {
    if (eventType === 'error') {
      let msg = '对话服务异常'
      try {
        const e = JSON.parse(data) as { message?: string }
        msg = e.message || msg
      } catch { /* 保留默认 */ }
      handlers.onError(msg)
      return
    }
    let obj: unknown
    try {
      obj = JSON.parse(data)
    } catch {
      return // 非 JSON(如心跳注释),忽略
    }
    switch (eventType) {
      case 'start':       handlers.onStart?.(obj as { sessionId: number; turnId: number }); break
      case 'tool_call':   handlers.onToolCall?.(obj as ToolCallPayload); break
      case 'tool_result': handlers.onToolResult?.(obj as ToolResultPayload); break
      case 'reasoning':   handlers.onReasoning?.(obj as { eventId?: number; content: string }); break
      case 'text':        handlers.onText?.(obj as { eventId?: number; content: string }); break
      case 'done':        handlers.onDone?.(obj as { sessionId: number; contextTokens?: number }); break
      case 'cancelled':   handlers.onCancelled?.(obj as { sessionId: number }); break
    }
  }

  try {
    const res = await fetch('/api/chat', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
      signal,
    })
    if (!res.ok) {
      const err = (await res.json().catch(() => null)) as { message?: string } | null
      throw new Error(err?.message || `对话请求失败（${res.status}）`)
    }
    const reader = res.body?.getReader()
    if (!reader) throw new Error('浏览器不支持流式读取')

    const decoder = new TextDecoder()
    let buffer = ''
    let eventType = ''            // 当前事件名,跨行跟踪(默认 message)
    let eventData: string | null = null  // 同一事件多行 data: 以 \n 拼接

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || '' // 末行可能不完整,留到下次

      for (const line of lines) {
        if (line === '' || line === '\r') {
          // 空行 = 事件边界,分发后重置
          if (eventData !== null) {
            dispatch(eventType || 'message', eventData)
            eventData = null
          }
          eventType = ''
          continue
        }
        if (line.startsWith('event:')) {
          eventType = line.slice(6).trim()
          continue
        }
        if (line.startsWith('data:')) {
          const payload = line.slice(5).replace(/^ /, '')
          eventData = eventData === null ? payload : eventData + '\n' + payload
        }
      }
    }
    // 流关闭时分发残留事件
    if (eventData !== null) dispatch(eventType || 'message', eventData)
  } catch (err) {
    if ((err as Error).name === 'AbortError') {
      handlers.onError('请求已取消')
      return
    }
    handlers.onError((err as Error).message || '对话请求失败')
  }
}

/**
 * 主动停止某一正在运行的轮次 —— 后端据 turnId 强杀工具进程、断流并把轮次置为 cancelled。
 * 尽力而为:失败不抛(前端还会 abort 本地连接兜底)。
 */
export async function cancelChat(turnId: number): Promise<void> {
  try {
    await fetch('/api/chat/cancel', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ turnId }),
    })
  } catch {
    /* 忽略:前端 AbortController 会兜底断开本地连接 */
  }
}

// ==================== 会话历史 ====================

/** 列出全部会话(侧边栏列表,后端按最后更新时间倒序) */
export async function fetchSessions(): Promise<ChatSession[]> {
  const res = await fetch('/api/sessions')
  if (!res.ok) throw new Error(`获取会话列表失败（${res.status}）`)
  const data = (await res.json()) as { code: number; data: ChatSession[] }
  return data.data
}

/** 载入某会话的完整事件流(自增 id 升序) */
export async function fetchSessionEvents(id: number): Promise<ChatEvent[]> {
  const res = await fetch(`/api/sessions/${id}/events`)
  if (!res.ok) throw new Error(`载入会话失败（${res.status}）`)
  const data = (await res.json()) as { code: number; data: ChatEvent[] }
  return data.data
}

/** 删除会话 */
export async function deleteSession(id: number): Promise<void> {
  const res = await fetch(`/api/sessions/${id}`, { method: 'DELETE' })
  if (!res.ok) throw new Error(`删除会话失败（${res.status}）`)
}

/** 手动压缩上下文的返回结果 —— 对应后端 CompactionResult */
export interface CompactionResult {
  compacted: boolean
  tokensBefore: number
  tokensAfter: number
  /** 压缩后整轮上下文的估算占用 token（供刷新占比条）；未压缩时缺省 */
  contextTokens?: number
  message?: string
}

/**
 * 手动压缩会话上下文 —— 把较早对话压成摘要,降低后续输入 token。
 * 同步等待后端用所选模型生成摘要;返回压缩前后估算 + 压缩后的上下文占用。
 */
export async function compactSession(sessionId: number, modelIndex: number): Promise<CompactionResult> {
  const res = await fetch('/api/chat/compact', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ sessionId, modelIndex }),
  })
  const json = (await res.json().catch(() => null)) as
    { code: number; message?: string; data?: CompactionResult } | null
  if (!res.ok || !json || json.code !== 1) {
    throw new Error(json?.message || `压缩失败（${res.status}）`)
  }
  return json.data as CompactionResult
}

/** 取某会话最新的上下文占用 token 数（无记录返回 null） */
export async function fetchContextUsage(id: number): Promise<number | null> {
  const res = await fetch(`/api/sessions/${id}/context-usage`)
  if (!res.ok) throw new Error(`载入上下文用量失败（${res.status}）`)
  const data = (await res.json()) as { code: number; data: { contextTokens: number | null } }
  return data.data?.contextTokens ?? null
}

// ==================== 知识库 AI 编辑（agent + kb 工具，SSE 流式，不落库） ====================

/** 一条对话历史消息（跨轮记忆用，不落库） */
export interface KbEditMessage {
  role: 'user' | 'assistant'
  content: string
}

/** 知识库 AI 编辑请求体 */
export interface KbEditRequest {
  docPath: string            // 当前打开文档的相对路径
  instruction: string        // 编辑指令
  modelIndex: number         // 所用模型索引
  editId: string             // 本轮编辑 id（前端生成，用于主动取消时精确定位）
  history?: KbEditMessage[]  // 本轮之前的对话历史（跨轮记忆，仅随请求带上、不落库）
}

/** 知识库 AI 编辑 SSE 回调 —— 事件名与 /api/chat 一致，故可复用 ToolCallCard / ThinkingCard 渲染 */
export interface KbEditHandlers {
  onStart?: (d: { docPath: string; editId?: string }) => void
  onToolCall?: (d: ToolCallPayload) => void
  onToolResult?: (d: ToolResultPayload) => void
  /** 思考增量（reasoning 模型）：与 text 平行，前端折叠成 ThinkingCard */
  onReasoning?: (d: { content: string }) => void
  onText?: (d: { content: string }) => void
  /** 一次编辑轮次结束 */
  onDone?: (d: { docPath: string }) => void
  /** 被用户主动取消 */
  onCancelled?: (d: { editId?: string }) => void
  onError: (message: string) => void
}

/**
 * 发起知识库 AI 编辑 SSE 流式请求。
 *
 * 打到 /api/knowledge/ai-edit，事件结构与 /api/chat 相同（start/tool_call/tool_result/text/done/error），
 * 沿用同一套 SSE 行解析。模型用 kb_read/edit/write_file 直接改磁盘文件，前端据 tool_result 读盘刷新。
 */
export async function streamKnowledgeEdit(
  req: KbEditRequest,
  handlers: KbEditHandlers,
  signal?: AbortSignal,
): Promise<void> {
  function dispatch(eventType: string, data: string) {
    if (eventType === 'error') {
      let msg = 'AI 编辑服务异常'
      try {
        const e = JSON.parse(data) as { message?: string }
        msg = e.message || msg
      } catch { /* 保留默认 */ }
      handlers.onError(msg)
      return
    }
    let obj: unknown
    try {
      obj = JSON.parse(data)
    } catch {
      return // 非 JSON（心跳注释等），忽略
    }
    switch (eventType) {
      case 'start':       handlers.onStart?.(obj as { docPath: string; editId?: string }); break
      case 'tool_call':   handlers.onToolCall?.(obj as ToolCallPayload); break
      case 'tool_result': handlers.onToolResult?.(obj as ToolResultPayload); break
      case 'reasoning':   handlers.onReasoning?.(obj as { content: string }); break
      case 'text':        handlers.onText?.(obj as { content: string }); break
      case 'done':        handlers.onDone?.(obj as { docPath: string }); break
      case 'cancelled':   handlers.onCancelled?.(obj as { editId?: string }); break
    }
  }

  try {
    const res = await fetch('/api/knowledge/ai-edit', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(req),
      signal,
    })
    if (!res.ok) {
      const err = (await res.json().catch(() => null)) as { message?: string } | null
      throw new Error(err?.message || `AI 编辑请求失败（${res.status}）`)
    }
    const reader = res.body?.getReader()
    if (!reader) throw new Error('浏览器不支持流式读取')

    const decoder = new TextDecoder()
    let buffer = ''
    let eventType = ''
    let eventData: string | null = null

    while (true) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      const lines = buffer.split('\n')
      buffer = lines.pop() || ''

      for (const line of lines) {
        if (line === '' || line === '\r') {
          if (eventData !== null) {
            dispatch(eventType || 'message', eventData)
            eventData = null
          }
          eventType = ''
          continue
        }
        if (line.startsWith('event:')) {
          eventType = line.slice(6).trim()
          continue
        }
        if (line.startsWith('data:')) {
          const payload = line.slice(5).replace(/^ /, '')
          eventData = eventData === null ? payload : eventData + '\n' + payload
        }
      }
    }
    if (eventData !== null) dispatch(eventType || 'message', eventData)
  } catch (err) {
    if ((err as Error).name === 'AbortError') {
      handlers.onError('请求已取消')
      return
    }
    handlers.onError((err as Error).message || 'AI 编辑请求失败')
  }
}

/**
 * 主动取消一次知识库 AI 编辑（尽力而为，失败不抛）。
 * 后端据 editId dispose 模型流；前端同时 abort fetch 断开 SSE。
 */
export async function cancelKnowledgeEdit(editId: string): Promise<void> {
  try {
    await fetch('/api/knowledge/ai-edit/cancel', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ editId }),
    })
  } catch {
    /* 取消是尽力而为：网络失败也无妨，abort 已断开本地连接 */
  }
}
