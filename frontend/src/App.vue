<script setup lang="ts">
import { RouterView } from 'vue-router'
import AppToast from '@/components/AppToast.vue'
import AppConfirm from '@/components/AppConfirm.vue'
// 知识库目录树由 KnowledgePanel 按需加载，App 不再预取。
</script>

<template>
  <RouterView />
  <AppToast />
  <AppConfirm />
</template>