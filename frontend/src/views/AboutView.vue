<script setup lang="ts">
const stack = [
  ['后端', 'Spring Boot 3.5（内嵌 Tomcat，仅监听 127.0.0.1）'],
  ['嵌入引擎', 'JCEF — 完整 Chromium'],
  ['前端', 'Vue 3 + TypeScript + Vite'],
  ['状态管理', 'Pinia'],
  ['代码编辑器', 'Tiptap（所见即所得）'],
  ['Markdown', 'markdown-it + shiki'],
  ['打包', 'jpackage → Windows .msi'],
]
</script>

<template>
  <div class="about">
    <h1>关于 TailorAgent</h1>
    <p>一个嵌入式网页技术栈的 Windows 桌面应用骨架。</p>
    <table>
      <tbody>
        <tr v-for="[k, v] in stack" :key="k">
          <th>{{ k }}</th>
          <td>{{ v }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
.about {
  padding: 24px 32px;
}
table {
  border-collapse: collapse;
  margin-top: 12px;
}
th,
td {
  border: 1px solid #ddd;
  padding: 6px 12px;
  text-align: left;
}
th {
  background: #f5f5f5;
  white-space: nowrap;
}
</style>