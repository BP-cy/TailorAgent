<script setup lang="ts">
import MarkdownIt from 'markdown-it'
import { computed } from 'vue'

/**
 * 对话气泡的 Markdown 渲染器 —— 与编辑/阅读页（Tiptap 管线）完全解耦。
 * 只做「md 字符串 → 静态 HTML」，只读、不可交互。
 *
 * 安全：html=false 不解析原始 HTML 标签，挡掉大模型输出里的
 * <script>/<img onerror> 等注入，故 v-html 安全，无需额外 DOMPurify。
 *
 * 朴素方案：代码块按 prose 默认 <pre><code> 渲染，暂不接 Shiki 高亮。
 */
const md = new MarkdownIt({
  html: false, // 不解析原始 HTML —— v-html 安全的前提
  linkify: true, // 裸 URL 自动转链接
  breaks: true, // 单换行 → <br>，贴合聊天逐行输入习惯
})

const defaultLinkOpen = md.renderer.rules.link_open
md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
  const token = tokens[idx]
  const href = token.attrGet('href')

  // 外部网页明确使用新窗口语义；JCEF 会拦截该请求并交给系统默认浏览器。
  if (href) {
    try {
      const target = new URL(href, window.location.href)
      if (
        (target.protocol === 'http:' || target.protocol === 'https:') &&
        target.origin !== window.location.origin
      ) {
        token.attrSet('target', '_blank')
        token.attrSet('rel', 'noopener noreferrer')
      }
    } catch {
      // 非法 URL 保持原样，最终仍由 JCEF 导航策略阻止。
    }
  }

  return defaultLinkOpen
    ? defaultLinkOpen(tokens, idx, options, env, self)
    : self.renderToken(tokens, idx, options)
}

const props = defineProps<{ content: string }>()

const rendered = computed(() => md.render(props.content || ''))
</script>

<template>
  <div class="chat-md prose prose-sm max-w-none" v-html="rendered" />
</template>

<style scoped>
/* 气泡内边距已负责留白，去掉 prose 首尾元素的外边距，避免上下出现空隙 */
.chat-md :deep(> :first-child) {
  margin-top: 0;
}
.chat-md :deep(> :last-child) {
  margin-bottom: 0;
}
/* 长代码/表格在气泡内横向滚动而非撑破宽度 */
.chat-md :deep(pre) {
  overflow-x: auto;
}
</style>
