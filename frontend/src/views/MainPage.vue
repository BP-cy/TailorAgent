<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { storeToRefs } from 'pinia'
import SettingsDialog from '../components/SettingsDialog.vue'
import ChatPanel from '../components/ChatPanel.vue'
import KnowledgePanel from '../components/KnowledgePanel.vue'
import ChatAddIcon from '../components/icons/ChatAddIcon.vue'
import KnowledgeIcon from '../components/icons/KnowledgeIcon.vue'
import SettingsIcon from '../components/icons/SettingsIcon.vue'
import { useUiStore } from '../stores/ui'
import { useChatRunStore } from '../stores/chatRun'
import { fetchHello, fetchSessions, deleteSession, type ChatSession } from '../api'

// 会话运行态:任务列表据此在标题前显示「进行中」转圈
const run = useChatRunStore()

// 设置弹窗开关
const settingsOpen = ref(false)

// 设置变更版本号 —— 保存后递增，通知 ChatPanel 重新加载配置
const configVersion = ref(0)

// 当前右侧面板：chat=新建任务，knowledge=知识库（放进 store 以便从编辑页返回时保留）
const ui = useUiStore()
const { mainView: activeView } = storeToRefs(ui)

// 侧边栏收起状态
const sidebarCollapsed = ref(false)

// 任务列表：展开/收起
const tasksExpanded = ref(true)

// 历史会话列表 + 当前选中会话(null = 新建任务,空白对话)
const sessions = ref<ChatSession[]>([])
const selectedSessionId = ref<number | null>(null)

async function loadSessions() {
  try {
    sessions.value = await fetchSessions()
  } catch {
    // 列表加载失败不打断主流程,静默即可
  }
}

function relativeTime(iso: string): string {
  const diff = Date.now() - new Date(iso).getTime()
  const mins = Math.floor(diff / 60000)
  if (mins < 1) return '刚刚'
  if (mins < 60) return `${mins} 分钟前`
  const hours = Math.floor(mins / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 7) return `${days} 天前`
  return new Date(iso).toLocaleDateString('zh-CN')
}

// 打开历史会话
function openSession(s: ChatSession) {
  selectedSessionId.value = s.id
  activeView.value = 'chat'
}

// 新建任务:清空选中会话,ChatPanel 显示空白对话
function newTask() {
  selectedSessionId.value = null
  activeView.value = 'chat'
}

// ChatPanel 新建会话成功:选中它并刷新列表
function onSessionCreated(id: number) {
  selectedSessionId.value = id
  loadSessions()
}

// 本轮结束(会话更新时间变化):刷新列表排序
function onSessionUpdated() {
  loadSessions()
}

// 删除会话:若删的是当前会话则回到新建态
async function onDeleteSession(s: ChatSession) {
  try {
    await deleteSession(s.id)
    run.dropSession(s.id) // 清理该会话在 store 中的缓冲/运行态,避免脏状态
    if (selectedSessionId.value === s.id) selectedSessionId.value = null
    loadSessions()
  } catch {
    // 静默
  }
}

// 后端连接状态
const backendOnline = ref<boolean | null>(null) // null=检测中
const backendText = ref('检测中…')

onMounted(async () => {
  loadSessions()
  try {
    const hello = await fetchHello()
    backendOnline.value = true
    backendText.value = `后端已连接（${hello.time}）`
  } catch (e) {
    backendOnline.value = false
    backendText.value = `后端未连接：${(e as Error).message}`
  }
})

function onSettingsSaved() {
  configVersion.value++
}
</script>

<template>
  <div class="flex h-full bg-white text-on-surface">
    <!-- 左侧菜单栏：收起时宽度→0 -->
    <aside
      class="flex-shrink-0 flex flex-col bg-[#F2F2F2] border-outline-variant/60 transition-all duration-300 overflow-hidden"
      :class="sidebarCollapsed ? 'w-0 border-r-0' : 'w-[260px] border-r py-4 px-3'"
    >
      <!-- 标题行 + 收起按钮 -->
      <div class="flex items-center justify-between px-2 pt-1.5 pb-3">
        <strong class="text-base font-headline whitespace-nowrap">TailorAgent</strong>
        <button
          type="button"
          class="flex-shrink-0 w-7 h-7 rounded-lg flex items-center justify-center text-on-surface-variant hover:bg-surface-container transition-colors"
          title="收起菜单"
          @click="sidebarCollapsed = true"
        >
          <span class="material-symbols-outlined text-[20px]">chevron_left</span>
        </button>
      </div>

      <!-- 菜单 -->
      <nav class="flex flex-col gap-1">
        <button
          type="button"
          class="flex items-center gap-2 w-full rounded-lg text-xs py-2 px-3 transition-colors"
          :class="activeView === 'chat' && selectedSessionId === null
            ? 'bg-gray-400/10 text-black font-bold'
            : 'text-on-surface hover:bg-surface-container'"
          @click="newTask"
        >
          <ChatAddIcon class="w-[18px] h-[18px] flex-shrink-0" />
          <span class="whitespace-nowrap">新建任务</span>
        </button>

        <button
          type="button"
          class="flex items-center gap-2 w-full rounded-lg text-xs py-2 px-3 transition-colors"
          :class="activeView === 'knowledge'
            ? 'bg-gray-400/10 text-black font-bold'
            : 'text-on-surface hover:bg-surface-container'"
          @click="activeView = 'knowledge'"
        >
          <KnowledgeIcon class="w-[18px] h-[18px] flex-shrink-0" />
          <span class="whitespace-nowrap">知识库</span>
        </button>
      </nav>

      <!-- 分隔线 -->
      <div class="border-t border-outline-variant/60 mt-2 mb-0.5"></div>

      <!-- 任务列表 -->
      <div class="flex-1 flex flex-col min-h-0">
        <button
          type="button"
          class="flex items-center justify-between w-full rounded-lg text-xs py-2 px-3 text-on-surface-variant hover:bg-surface-container transition-colors flex-shrink-0"
          @click="tasksExpanded = !tasksExpanded"
        >
          <span class="font-medium whitespace-nowrap">任务</span>
          <span
            class="material-symbols-outlined text-[16px] transition-transform duration-200"
            :style="{ transform: tasksExpanded ? 'rotate(0deg)' : 'rotate(-90deg)' }"
          >
            expand_more
          </span>
        </button>
        <div
          v-if="tasksExpanded"
          class="overflow-y-auto task-scroll flex flex-col gap-0.5"
          :style="{ maxHeight: `${Math.min(Math.max(sessions.length, 1), 10) * 34}px` }"
        >
          <div
            v-for="s in sessions"
            :key="s.id"
            class="w-full text-left rounded-lg py-2 px-3 transition-colors hover:bg-surface-container group flex items-center gap-2 cursor-pointer"
            :class="selectedSessionId === s.id ? 'bg-gray-400/10' : ''"
            @click="openSession(s)"
          >
            <!-- 进行中转圈:该会话有 running 轮次时显示,环形旋转 -->
            <span
              v-if="run.isRunning(s.id)"
              class="flex-shrink-0 w-3 h-3 rounded-full border-2 border-primary/30 border-t-primary animate-spin"
              title="任务进行中"
            />
            <span
              class="text-xs font-medium truncate min-w-0 transition-colors"
              :class="selectedSessionId === s.id ? 'text-primary font-bold' : 'text-on-surface group-hover:text-primary'"
            >
              {{ s.title }}
            </span>
            <!-- 时间与删除按钮共用同一槽位:时间常驻占位(仅淡出),删除按钮绝对定位叠加在上层,
                 避免 hover 切换显隐导致槽位宽度变化、标题重新截断而产生抖动 -->
            <div class="relative flex-shrink-0 ml-auto flex items-center justify-end">
              <span class="text-[11px] text-on-surface-variant/40 tabular-nums transition-opacity group-hover:opacity-0">
                {{ relativeTime(s.updatedAt) }}
              </span>
              <button
                type="button"
                class="absolute right-0 top-1/2 -translate-y-1/2 flex items-center justify-center w-5 h-5 rounded text-on-surface-variant/60 hover:text-red-500 hover:bg-red-500/10 opacity-0 pointer-events-none transition-opacity group-hover:opacity-100 group-hover:pointer-events-auto"
                title="删除会话"
                @click.stop="onDeleteSession(s)"
              >
                <span class="material-symbols-outlined text-[15px]">delete</span>
              </button>
            </div>
          </div>
          <!-- 空态 -->
          <div v-if="sessions.length === 0" class="px-3 py-2 text-[11px] text-on-surface-variant/40">
            暂无历史会话
          </div>
        </div>
      </div>

      <div class="border-t border-outline-variant/60 pt-2.5 flex flex-col gap-2">
        <!-- 后端连接状态 -->
        <div
          class="flex items-center gap-2 px-3 py-1 text-xs text-on-surface-variant overflow-hidden"
          :title="backendText"
        >
          <span
            class="w-2 h-2 rounded-full flex-shrink-0"
            :class="{
              'bg-green-600': backendOnline === true,
              'bg-red-500': backendOnline === false,
              'bg-amber-500': backendOnline === null,
            }"
          />
          <span class="whitespace-nowrap">{{ backendText }}</span>
        </div>

        <button
          type="button"
          class="flex items-center gap-2 w-full rounded-lg text-xs py-2 px-3 text-on-surface transition-colors hover:bg-surface-container"
          @click="settingsOpen = true"
        >
          <SettingsIcon class="w-[18px] h-[18px] flex-shrink-0" />
          <span class="whitespace-nowrap">设置</span>
        </button>
      </div>
    </aside>

    <!-- 右侧面板区域 -->
    <div class="flex-1 relative min-w-0">
      <!-- 展开按钮：侧边栏收起时浮在左上角 -->
      <Transition name="expand-fade">
        <button
          v-if="sidebarCollapsed"
          type="button"
          class="absolute top-3 left-3 z-10 w-8 h-8 rounded-lg flex items-center justify-center bg-white border border-outline-variant shadow-sm text-on-surface-variant hover:bg-surface-container hover:text-on-surface transition-colors"
          title="展开菜单"
          @click="sidebarCollapsed = false"
        >
          <span class="material-symbols-outlined text-[20px]">chevron_right</span>
        </button>
      </Transition>

      <chat-panel
        v-if="activeView === 'chat'"
        :config-refresh="configVersion"
        :session-id="selectedSessionId"
        @session-created="onSessionCreated"
        @session-updated="onSessionUpdated"
      />
      <knowledge-panel v-else />
    </div>

    <SettingsDialog :open="settingsOpen" @close="settingsOpen = false" @saved="onSettingsSaved" />
  </div>
</template>

<style scoped>
/* 任务列表隐藏滚动条 */
.task-scroll {
  scrollbar-width: none; /* Firefox */
  -ms-overflow-style: none; /* IE */
}
.task-scroll::-webkit-scrollbar {
  display: none; /* Chrome / Edge / Safari */
}

/* 展开按钮过渡：淡入 + 从左侧滑入 */
.expand-fade-enter-active {
  transition: opacity 0.25s ease-out 0.18s, transform 0.25s ease-out 0.18s;
}
.expand-fade-leave-active {
  transition: opacity 0.15s ease-in, transform 0.15s ease-in;
}
.expand-fade-enter-from {
  opacity: 0;
  transform: translateX(-10px);
}
.expand-fade-leave-to {
  opacity: 0;
  transform: translateX(-10px);
}
</style>