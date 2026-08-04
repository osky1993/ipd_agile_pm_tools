<script setup lang="ts">
/** 报告章节容器：编号标题 + 打印避免分页截断。 */
defineProps<{ title: string; pageBreak?: boolean }>()
</script>

<template>
  <section class="report-section" :class="{ 'page-break': pageBreak }">
    <h2 class="rs-title">{{ title }}</h2>
    <slot />
  </section>
</template>

<style scoped>
.report-section { margin-bottom: 22px; }
.rs-title {
  font-size: 16px; color: #1f2d3d; margin: 0 0 10px;
  padding-bottom: 6px; border-bottom: 2px solid #409eff;
}
</style>
