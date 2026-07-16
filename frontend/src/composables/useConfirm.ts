import { reactive } from 'vue'

export interface ConfirmOptions {
  message: string
  title?: string
  confirmText?: string
  cancelText?: string
  danger?: boolean // 危险操作（删除类）→ 红色确认按钮
}

export interface PromptOptions extends ConfirmOptions {
  defaultValue?: string
  placeholder?: string
}

interface DialogState {
  visible: boolean
  message: string
  title: string
  confirmText: string
  cancelText: string
  danger: boolean
  isPrompt: boolean
  placeholder: string
  inputValue: string
  resolve: ((value: boolean | string | null) => void) | null
}

// 模块级单例：全应用共享同一个对话框
const state = reactive<DialogState>({
  visible: false,
  message: '',
  title: '',
  confirmText: '确定',
  cancelText: '取消',
  danger: false,
  isPrompt: false,
  placeholder: '',
  inputValue: '',
  resolve: null,
})

function settle(value: boolean | string | null) {
  state.visible = false
  const r = state.resolve
  state.resolve = null
  if (r) r(value)
}

/**
 * 全局确认 / 输入对话框（替代浏览器原生 confirm/prompt，避免出现「域名 says」前缀）。
 */
export function useConfirm() {
  /** 确认对话框，返回 true(确定) / false(取消)。参数可为纯文本或选项对象。 */
  function confirm(opts: ConfirmOptions | string): Promise<boolean> {
    const o = typeof opts === 'string' ? { message: opts } : opts
    state.message = o.message
    state.title = o.title ?? ''
    state.confirmText = o.confirmText ?? '确定'
    state.cancelText = o.cancelText ?? '取消'
    state.danger = o.danger ?? false
    state.isPrompt = false
    state.placeholder = ''
    state.inputValue = ''
    state.visible = true
    return new Promise<boolean>((resolve) => {
      state.resolve = resolve as (v: boolean | string | null) => void
    })
  }

  /** 输入对话框，返回输入的字符串；取消返回 null。 */
  function prompt(opts: PromptOptions | string): Promise<string | null> {
    const o = typeof opts === 'string' ? { message: opts } : opts
    state.message = o.message
    state.title = o.title ?? ''
    state.confirmText = o.confirmText ?? '确定'
    state.cancelText = o.cancelText ?? '取消'
    state.danger = false
    state.isPrompt = true
    state.placeholder = o.placeholder ?? ''
    state.inputValue = o.defaultValue ?? ''
    state.visible = true
    return new Promise<string | null>((resolve) => {
      state.resolve = resolve as (v: boolean | string | null) => void
    })
  }

  /** 组件内点击「确定」时调用 */
  function onConfirm() {
    settle(state.isPrompt ? state.inputValue : true)
  }
  /** 组件内点击「取消」/关闭时调用 */
  function onCancel() {
    settle(state.isPrompt ? null : false)
  }

  return { state, confirm, prompt, onConfirm, onCancel }
}
