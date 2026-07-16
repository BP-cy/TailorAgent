import CodeBlock from '@tiptap/extension-code-block'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import CodeBlockView from '@/components/CodeBlockView.vue'

/**
 * 代码块扩展
 * 继承 CodeBlock，统一使用 CodeBlockView 渲染
 * CodeBlockView 内部根据 language 区分 mermaid 图表和普通代码块
 */
export const MermaidNode = CodeBlock.extend({
  name: 'codeBlock',

  addNodeView() {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    return VueNodeViewRenderer(CodeBlockView as any)
  },
})
