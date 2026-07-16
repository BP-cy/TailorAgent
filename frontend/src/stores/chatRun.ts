import { defineStore } from 'pinia'
import { reactive } from 'vue'
import type { ChatEvent } from '../api'

/**
 * 会话运行态 store —— 按 sessionId 托管「事件缓冲 + 是否有 running 轮次」。
 *
 * <p>为什么放 store 而不是组件:只有一个 ChatPanel 实例,若把 events / sending 绑在实例上,
 * 一轮在跑就会锁住全部会话,且切走后在途的流会把内容写进新会话的数组(串台)。
 * 把缓冲与运行态**按会话拆开**后,在途的流写进它自己那条会话的缓冲,
 * 多个会话可各自并发开轮次,UI 也能只锁当前会话。
 *
 * <p>为什么不查后端:启动恢复已把所有残留 running 轮次刷成 cancelled(见 session-management 记忆),
 * 所以重开 App 后端永远没有"正在跑"的轮次 —— 运行态纯属本次 App 生命周期的前端内存概念。
 */

/** 缓冲键:真实 sessionId(number),或新建任务尚无 id 时的占位 'new' */
export type RunKey = number | 'new'

export const useChatRunStore = defineStore('chatRun', () => {
  // 每会话的事件缓冲(乐观插入 + 流式追加都写这里)
  const buffers = reactive(new Map<RunKey, ChatEvent[]>())
  // 哪些会话当前有 running 轮次
  const running = reactive(new Set<RunKey>())
  // 哪些会话本轮已开始收到流式增量(控制「思考中...」占位)
  const started = reactive(new Set<RunKey>())
  // 哪些会话的历史已从 DB 载入(或为本次新建,缓冲即权威)——避免重复拉取覆盖在途内容
  const loaded = reactive(new Set<RunKey>())
  // 每会话在途请求的 AbortController(用户主动停止时 abort,立即断开客户端连接)
  const controllers = reactive(new Map<RunKey, AbortController>())
  // 每会话当前运行轮次的 turnId(取消接口据此精确定位;onStart 时写入)
  const turnIds = reactive(new Map<RunKey, number>())
  // 每会话最新的上下文占用 token 数(占比条的分子;onDone 推送 / 载入时回读)
  const contextTokens = reactive(new Map<RunKey, number>())

  /** 取某会话的事件缓冲;不存在则建一个空的(reactive)并返回 */
  function bufferFor(key: RunKey): ChatEvent[] {
    if (!buffers.has(key)) buffers.set(key, [])
    return buffers.get(key)!
  }

  /** 用整段事件流覆盖某会话缓冲(从 DB 载入历史时用) */
  function setBuffer(key: RunKey, events: ChatEvent[]) {
    buffers.set(key, events)
  }

  function isRunning(key: RunKey): boolean {
    return running.has(key)
  }
  function setRunning(key: RunKey, value: boolean) {
    if (value) running.add(key)
    else running.delete(key)
  }

  function isStarted(key: RunKey): boolean {
    return started.has(key)
  }
  function setStarted(key: RunKey, value: boolean) {
    if (value) started.add(key)
    else started.delete(key)
  }

  function isLoaded(key: RunKey): boolean {
    return loaded.has(key)
  }
  function setLoaded(key: RunKey, value: boolean) {
    if (value) loaded.add(key)
    else loaded.delete(key)
  }

  function setController(key: RunKey, c: AbortController | null) {
    if (c) controllers.set(key, c)
    else controllers.delete(key)
  }
  function getController(key: RunKey): AbortController | undefined {
    return controllers.get(key)
  }

  function setTurnId(key: RunKey, turnId: number) {
    turnIds.set(key, turnId)
  }
  function getTurnId(key: RunKey): number | undefined {
    return turnIds.get(key)
  }

  function setContextTokens(key: RunKey, n: number) {
    contextTokens.set(key, n)
  }
  function getContextTokens(key: RunKey): number | undefined {
    return contextTokens.get(key)
  }

  /** 删除会话时清理其残留运行态/缓冲,避免内存泄漏与脏状态 */
  function dropSession(id: number) {
    buffers.delete(id)
    running.delete(id)
    started.delete(id)
    loaded.delete(id)
    controllers.delete(id)
    turnIds.delete(id)
    contextTokens.delete(id)
  }

  /**
   * 新建任务的流拿到真实 sessionId 后,把 'new' 占位的缓冲与运行/已开始/已载入态迁移到真实 id。
   * 迁移后 ChatPanel 的 activeKey 由 'new' 变为 realId,视图无缝衔接到同一条缓冲(数组引用不变)。
   */
  function rekeyNewToId(realId: number) {
    if (buffers.has('new')) {
      buffers.set(realId, buffers.get('new')!)
      buffers.delete('new')
    }
    if (running.has('new')) {
      running.delete('new')
      running.add(realId)
    }
    if (started.has('new')) {
      started.delete('new')
      started.add(realId)
    }
    if (controllers.has('new')) {
      controllers.set(realId, controllers.get('new')!)
      controllers.delete('new')
    }
    if (turnIds.has('new')) {
      turnIds.set(realId, turnIds.get('new')!)
      turnIds.delete('new')
    }
    if (contextTokens.has('new')) {
      contextTokens.set(realId, contextTokens.get('new')!)
      contextTokens.delete('new')
    }
    // 新建会话的缓冲即权威,标记已载入,防止之后切回时被 DB 拉取覆盖
    started.delete('new')
    loaded.add(realId)
  }

  return {
    bufferFor, setBuffer,
    isRunning, setRunning,
    isStarted, setStarted,
    isLoaded, setLoaded,
    setController, getController,
    setTurnId, getTurnId,
    setContextTokens, getContextTokens,
    dropSession, rekeyNewToId,
  }
})
