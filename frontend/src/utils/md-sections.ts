/**
 * Markdown 段落解析工具。
 *
 * 将 Markdown 文本按标题（# ~ ######）拆分为嵌套的段落树，
 * 供 TOC 目录和 AI 辅助编辑使用。
 */

export interface MdSection {
  /** 唯一标识，形如 "sec-0", "sec-1" */
  id: string
  /** 标题级别 1-6 */
  level: number
  /** 标题文字（去除 # 和首尾空白） */
  heading: string
  /** 从根到当前标题的路径 */
  path: string[]
  /** 标题行在原始 MD 中的行号（0-based） */
  startLine: number
  /** 段落结束行号（不包含，即下一个同级/上级标题行号或总行数） */
  endLine: number
  /** 完整 MD 原文（含标题行，不含子段落） */
  content: string
  /** 子段落 */
  children: MdSection[]
}

/**
 * TOC 扁平展示项。
 * 将 MdSection 树 DFS 展开，携带 depth 用于 UI 缩进。
 */
export interface FlatTocItem {
  id: string
  text: string
  level: number
  /** 在树中的嵌套深度（0 = 顶层标题） */
  depth: number
  path: string[]
  /** 保留完整段落引用，供 AI 编辑使用 */
  section: MdSection
}

/** 标题行正则 */
const HEADING_RE = /^(#{1,6})\s+(.*)$/

/** 去除 Markdown 行内标记，还原为纯文本标题（供 TOC 显示） */
function cleanHeadingText(raw: string): string {
  return raw
    .replace(/\\([\\`*_{}[\]()#+\-.!>~|])/g, '$1') // 去转义反斜杠：1\. → 1.
    .replace(/\*\*([^*]+)\*\*/g, '$1') // 去加粗 **x**
    .replace(/\*([^*]+)\*/g, '$1') // 去斜体 *x*
    .replace(/`([^`]+)`/g, '$1') // 去行内代码 `x`
    .replace(/~~([^~]+)~~/g, '$1') // 去删除线 ~~x~~
    .replace(/\[([^\]]+)\]\([^)]*\)/g, '$1') // 链接 [text](url) → text
    .replace(/#+\s*$/, '') // 去 ATX 闭合 #
    .trim()
}

/**
 * 解析 Markdown 文本，按标题拆分为嵌套段落树。
 *
 * 算法：
 * 1. 逐行扫描找到所有标题行及其行号
 * 2. 用栈维护当前嵌套路径，遍历标题构建树
 * 3. 每个段落的 content 从标题行切到下一个同级/上级标题之前
 *
 * @param md 原始 Markdown 文本
 * @returns 顶层段落数组（按出现顺序）
 */
export function parseMdSections(md: string): MdSection[] {
  const lines = md.split('\n')

  // 找到所有标题位置（跳过代码块内的 # 行，避免把代码注释误判为标题）
  const headingPositions: { lineIdx: number; level: number; heading: string }[] = []
  let inCodeFence = false
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    if (/^\s*(```|~~~)/.test(line)) {
      inCodeFence = !inCodeFence
      continue
    }
    if (inCodeFence) continue
    const m = line.match(HEADING_RE)
    if (m) {
      headingPositions.push({ lineIdx: i, level: m[1].length, heading: cleanHeadingText(m[2]) })
    }
  }

  if (headingPositions.length === 0) return []

  // 栈构建嵌套树
  const root: MdSection[] = []
  const stack: MdSection[] = []
  let sectionId = 0

  for (let i = 0; i < headingPositions.length; i++) {
    const { lineIdx, level, heading } = headingPositions[i]

    // 下一个同级或上级标题的行号，决定当前段落的结束位置
    let nextLineIdx = lines.length
    for (let j = i + 1; j < headingPositions.length; j++) {
      if (headingPositions[j].level <= level) {
        nextLineIdx = headingPositions[j].lineIdx
        break
      }
    }

    // 弹栈至当前标题的父级
    while (stack.length > 0 && stack[stack.length - 1].level >= level) {
      stack.pop()
    }

    const path = stack.length > 0
      ? [...stack[stack.length - 1].path, heading]
      : [heading]

    const section: MdSection = {
      id: `sec-${sectionId++}`,
      level,
      heading,
      path,
      startLine: lineIdx,
      endLine: nextLineIdx,
      content: lines.slice(lineIdx, nextLineIdx).join('\n'),
      children: [],
    }

    if (stack.length > 0) {
      stack[stack.length - 1].children.push(section)
    } else {
      root.push(section)
    }

    stack.push(section)
  }

  return root
}

/**
 * 将 MdSection 树 DFS 扁平化，产出带 depth 的展示列表。
 *
 * @param sections parseMdSections 的输出
 * @returns 扁平 TOC 项列表，按文档出现顺序
 */
export function flattenSections(sections: MdSection[]): FlatTocItem[] {
  const result: FlatTocItem[] = []

  function walk(items: MdSection[], depth: number) {
    for (const sec of items) {
      result.push({
        id: sec.id,
        text: sec.heading,
        level: sec.level,
        depth,
        path: sec.path,
        section: sec,
      })
      walk(sec.children, depth + 1)
    }
  }

  walk(sections, 0)
  return result
}