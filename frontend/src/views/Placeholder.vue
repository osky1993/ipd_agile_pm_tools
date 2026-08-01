<script setup lang="ts">
import { useRoute } from 'vue-router'
const route = useRoute()

// 各页面对应的开发计划任务，方便演示时对照进度
const planMap: Record<string, string> = {
  '/dcp': 'E5 · 阶段3：DCP准入条件（T504）',
  '/requirements': 'E2 · 阶段1：产品能力与需求树（T208）',
  '/board': 'E3 · 阶段2：Sprint看板（T304）',
  '/quality': 'E3/E4 · 测试·缺陷·变更（T310/T406）',
  '/trace': 'E4/E7 · 追溯矩阵·风险·证据·决策（T407/T703）',
}
</script>

<template>
  <el-result icon="info" :title="String(route.meta.title)" :sub-title="planMap[route.path] || '规划中'">
    <template #extra>
      <el-tag type="warning">待开发</el-tag>
      <p class="tip">该页面按开发计划在对应 Epic 落地，骨架已就位。</p>
    </template>
  </el-result>
</template>

<style scoped>
.tip { color: #909399; font-size: 13px; margin-top: 12px; }
</style>
