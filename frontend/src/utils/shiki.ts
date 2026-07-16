/**
 * Shiki 高亮器单例 —— 纯 JS 引擎（无 WASM），适配桌面 WebView。
 *
 * 所有语法通过显式 import() 字面量加载（不用模板字符串变量），
 * Rollup 为每个语法生成独立 chunk。首屏 JS 体积从 2.6MB → ~1MB。
 * ensureHighlighter() 预加载 10 种高频语言。
 */
import { createHighlighterCore, type HighlighterCore } from 'shiki/core'
import { createJavaScriptRegexEngine } from 'shiki/engine/javascript'
import { bundledThemes } from 'shiki/themes'

// ── 显式 import() 映射（字面量，Rollup 可逐个拆分 chunk） ─────

// 启动预装（10 种）—— 并行加载
const PRELOAD_LANGS = [
  () => import('@shikijs/langs/typescript'),
  () => import('@shikijs/langs/javascript'),
  () => import('@shikijs/langs/json'),
  () => import('@shikijs/langs/html'),
  () => import('@shikijs/langs/css'),
  () => import('@shikijs/langs/bash'),
  () => import('@shikijs/langs/python'),
  () => import('@shikijs/langs/java'),
  () => import('@shikijs/langs/markdown'),
]

/** 预装语言对应的用户侧名称 */
const PRELOAD_NAMES = [
  'typescript', 'javascript', 'json', 'html', 'css',
  'bash', 'python', 'java', 'markdown',
]

/** 动态语言：用户侧名称 → 显式 import() */
const DYNAMIC_LANGS: Record<string, () => Promise<any>> = {
  tsx:            () => import('@shikijs/langs/tsx'),
  jsx:            () => import('@shikijs/langs/jsx'),
  scss:           () => import('@shikijs/langs/scss'),
  jsonc:          () => import('@shikijs/langs/jsonc'),
  kotlin:         () => import('@shikijs/langs/kotlin'),
  shell:          () => import('@shikijs/langs/shellscript'),
  powershell:     () => import('@shikijs/langs/powershell'),
  sql:            () => import('@shikijs/langs/sql'),
  mysql:          () => import('@shikijs/langs/sql'),
  pgsql:          () => import('@shikijs/langs/plsql'),
  yaml:           () => import('@shikijs/langs/yaml'),
  toml:           () => import('@shikijs/langs/toml'),
  xml:            () => import('@shikijs/langs/xml'),
  diff:           () => import('@shikijs/langs/diff'),
  mdx:            () => import('@shikijs/langs/mdx'),
  rust:           () => import('@shikijs/langs/rust'),
  go:             () => import('@shikijs/langs/go'),
  c:              () => import('@shikijs/langs/c'),
  cpp:            () => import('@shikijs/langs/cpp'),
  csharp:         () => import('@shikijs/langs/csharp'),
  swift:          () => import('@shikijs/langs/swift'),
  php:            () => import('@shikijs/langs/php'),
  ruby:           () => import('@shikijs/langs/ruby'),
  lua:            () => import('@shikijs/langs/lua'),
  r:              () => import('@shikijs/langs/r'),
  graphql:        () => import('@shikijs/langs/graphql'),
  dockerfile:     () => import('@shikijs/langs/dockerfile'),
  makefile:       () => import('@shikijs/langs/makefile'),
  nginx:          () => import('@shikijs/langs/nginx'),
  protobuf:       () => import('@shikijs/langs/protobuf'),
  vue:            () => import('@shikijs/langs/vue'),
  'vue-html':     () => import('@shikijs/langs/vue-html'),
  astro:          () => import('@shikijs/langs/astro'),
  svelte:         () => import('@shikijs/langs/svelte'),
}

// ── 单例 ──────────────────────────────────────────────────────

let hlPromise: Promise<HighlighterCore> | null = null
let hl: HighlighterCore | null = null
const loadedLangs = new Set<string>()
const loadingFutures = new Map<string, Promise<void>>()
const engine = createJavaScriptRegexEngine()

export async function ensureHighlighter(): Promise<HighlighterCore> {
  if (hl) return hl
  if (!hlPromise) {
    hlPromise = (async () => {
      // 并行加载 10 种预装语法
      const modules = await Promise.all(PRELOAD_LANGS.map((l) => l()))
      const langs = modules.map((m: any) => m.default ?? m).filter(Boolean)

      const h = await createHighlighterCore({
        themes: [bundledThemes['github-light'], bundledThemes['github-dark']],
        langs: langs as any,
        engine,
      })

      hl = h
      PRELOAD_NAMES.forEach((n) => loadedLangs.add(n))
      return h
    })().catch((err) => {
      hlPromise = null
      console.error('[Shiki] 初始化失败:', err)
      throw err
    })
  }
  return hlPromise
}

async function loadLanguage(name: string): Promise<void> {
  if (!hl) await ensureHighlighter()
  if (loadedLangs.has(name)) return
  if (loadingFutures.has(name)) return loadingFutures.get(name)!

  const loader = DYNAMIC_LANGS[name]
  if (!loader) {
    loadedLangs.add(name)
    return
  }

  const promise = loader()
    .then((mod: any) => {
      const langModule = mod.default ?? mod
      if (hl && langModule) {
        try {
          hl.loadLanguageSync(...(Array.isArray(langModule) ? langModule : [langModule]))
        } catch { /* 已加载则静默忽略 */ }
      }
      loadedLangs.add(name)
    })

  loadingFutures.set(name, promise)
  return promise
}

export function codeToHtml(code: string, lang: string): string {
  if (!hl) return escapeHtml(code)
  const normalizedLang = lang?.trim().toLowerCase() || 'plaintext'
  if (!loadedLangs.has(normalizedLang)) {
    loadLanguage(normalizedLang)
  }
  try {
    return hl.codeToHtml(code, { lang: normalizedLang, theme: 'github-light' })
  } catch {
    try {
      return hl.codeToHtml(code, { lang: 'plaintext', theme: 'github-light' })
    } catch {
      return escapeHtml(code)
    }
  }
}

export async function preloadLanguage(lang: string): Promise<void> {
  await ensureHighlighter()
  await loadLanguage(lang.trim().toLowerCase())
}

export function escapeHtml(text: string): string {
  return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
}
