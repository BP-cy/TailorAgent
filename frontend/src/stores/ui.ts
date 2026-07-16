import { defineStore } from 'pinia'
import { ref } from 'vue'

// 界面导航状态。放进 store 是为了在离开主页去编辑页、再返回时，
// 仍能恢复到原来的面板（如停在「知识库 / MarkDown」而不是重置回对话）。
export const useUiStore = defineStore('ui', () => {
  const mainView = ref<'chat' | 'knowledge'>('chat') // 主页右侧面板
  const kbTab = ref<'markdown' | 'file'>('markdown') // 知识库内的标签
  return { mainView, kbTab }
})