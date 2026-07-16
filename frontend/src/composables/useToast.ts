import { ref } from 'vue'

export type ToastType = 'success' | 'error' | 'warning' | 'info'

interface ToastItem {
  id: number
  message: string
  type: ToastType
  visible: boolean
}

// 模块级单例：全应用共享同一份 toast 队列
const toasts = ref<ToastItem[]>([])
let toastId = 0

/**
 * 轻量级 Toast 通知（全局单例，Tailwind 风格）。
 * @returns toasts 响应式队列 + showToast 触发函数
 */
export function useToast() {
  /**
   * 显示一条 Toast 消息。
   * @param message 消息内容
   * @param type 类型，默认 info
   * @param duration 自动关闭时间(ms)，默认 3000
   */
  function showToast(message: string, type: ToastType = 'info', duration = 3000) {
    const id = ++toastId
    toasts.value.push({ id, message, type, visible: true })

    setTimeout(() => {
      const toast = toasts.value.find((t) => t.id === id)
      if (toast) toast.visible = false
      // 等动画结束后移除
      setTimeout(() => {
        toasts.value = toasts.value.filter((t) => t.id !== id)
      }, 300)
    }, duration)
  }

  return { toasts, showToast }
}
