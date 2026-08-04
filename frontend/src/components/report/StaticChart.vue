<script setup lang="ts">
import { onMounted, onUnmounted, ref, watch } from 'vue'
import * as echarts from 'echarts'

/**
 * 报告页专用静态图表：离屏固定尺寸渲染 ECharts → dataURL → <img>，
 * 彻底规避打印时 canvas 缩放/懒渲染问题；打印像素密度 2x。
 */
const props = defineProps<{
  option: echarts.EChartsOption
  width?: number
  height?: number
}>()
const emit = defineEmits<{ (e: 'rendered'): void }>()

const src = ref('')
let host: HTMLDivElement | null = null

function render() {
  const w = props.width ?? 720
  const h = props.height ?? 320
  if (!host) {
    host = document.createElement('div')
    host.style.cssText = `position:fixed;left:-99999px;top:0;width:${w}px;height:${h}px;`
    document.body.appendChild(host)
  }
  host.style.width = `${w}px`
  host.style.height = `${h}px`
  const chart = echarts.init(host, undefined, { devicePixelRatio: 2 })
  chart.setOption({ animation: false, ...props.option })
  src.value = chart.getDataURL({ pixelRatio: 2, backgroundColor: '#fff' })
  chart.dispose()
  emit('rendered')
}

watch(() => props.option, render, { deep: true })
onMounted(render)
onUnmounted(() => {
  host?.remove()
  host = null
})
</script>

<template>
  <img v-if="src" :src="src" class="static-chart" :style="{ width: (width ?? 720) + 'px' }" alt="chart" />
</template>

<style scoped>
.static-chart { max-width: 100%; display: block; margin: 0 auto; }
</style>
