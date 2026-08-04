<script setup lang="ts">
import { computed } from 'vue'

/**
 * 纯 CSS 时间轴（路标图）：多泳道，支持里程碑点（版本菱形/DCP 旗标）与区间条（迭代）。
 * 日期 → 百分比定位；打印友好（无 canvas），报告页可复用。
 */
export interface TimelineItem {
  type: 'point' | 'bar'
  shape?: 'diamond' | 'flag' | 'dot'
  date?: string | null      // point 用
  start?: string | null     // bar 用
  end?: string | null
  label: string
  tip?: string              // tooltip 附加说明
  status?: 'plan' | 'active' | 'done' | 'late'
}
export interface TimelineLane {
  label: string
  items: TimelineItem[]
}

const props = defineProps<{ lanes: TimelineLane[] }>()

const DAY = 86400000
const parse = (s?: string | null) => (s ? new Date(s + 'T00:00:00').getTime() : null)

/** 全部日期的最小/最大范围，左右各留 6% 空白；无数据时以今天为中心前后 60 天 */
const range = computed(() => {
  const ts: number[] = []
  for (const lane of props.lanes) {
    for (const it of lane.items) {
      for (const d of [it.date, it.start, it.end]) {
        const t = parse(d)
        if (t != null) ts.push(t)
      }
    }
  }
  const now = Date.now()
  let min = ts.length ? Math.min(...ts) : now - 60 * DAY
  let max = ts.length ? Math.max(...ts) : now + 60 * DAY
  min = Math.min(min, now)
  max = Math.max(max, now)
  const pad = Math.max((max - min) * 0.06, 7 * DAY)
  return { min: min - pad, max: max + pad }
})

const pct = (t: number) => ((t - range.value.min) / (range.value.max - range.value.min)) * 100

const todayPct = computed(() => pct(new Date(new Date().toDateString()).getTime()))

/** 月份刻度 */
const monthTicks = computed(() => {
  const out: { pct: number; label: string }[] = []
  const d = new Date(range.value.min)
  d.setDate(1)
  d.setMonth(d.getMonth() + 1)
  while (d.getTime() < range.value.max) {
    out.push({ pct: pct(d.getTime()), label: `${d.getFullYear()}/${d.getMonth() + 1}` })
    d.setMonth(d.getMonth() + 1)
  }
  return out
})

function pointStyle(it: TimelineItem) {
  const t = parse(it.date)
  return t == null ? null : { left: pct(t) + '%' }
}
function barStyle(it: TimelineItem) {
  const s = parse(it.start)
  const e = parse(it.end)
  if (s == null || e == null) return null
  return { left: pct(s) + '%', width: Math.max(pct(e) - pct(s), 0.5) + '%' }
}
const tipText = (it: TimelineItem) =>
  [it.label, it.date ?? (it.start && it.end ? `${it.start} ~ ${it.end}` : ''), it.tip]
    .filter(Boolean).join('　')
</script>

<template>
  <div class="rt">
    <!-- 月份刻度轴 -->
    <div class="rt-row rt-axis">
      <div class="rt-label"></div>
      <div class="rt-track">
        <span v-for="t in monthTicks" :key="t.label" class="rt-tick" :style="{ left: t.pct + '%' }">{{ t.label }}</span>
        <span class="rt-today" :style="{ left: todayPct + '%' }"><i>今天</i></span>
      </div>
    </div>

    <div v-for="lane in lanes" :key="lane.label" class="rt-row">
      <div class="rt-label">{{ lane.label }}</div>
      <div class="rt-track">
        <span v-for="t in monthTicks" :key="t.label" class="rt-grid" :style="{ left: t.pct + '%' }" />
        <span class="rt-today-line" :style="{ left: todayPct + '%' }" />
        <template v-for="(it, i) in lane.items" :key="i">
          <el-tooltip v-if="it.type === 'point' && pointStyle(it)" :content="tipText(it)" placement="top">
            <span class="rt-point" :class="[it.status ?? 'plan', it.shape ?? 'dot']" :style="pointStyle(it)!">
              <i class="rt-mark" />
              <em class="rt-text">{{ it.label }}</em>
            </span>
          </el-tooltip>
          <el-tooltip v-else-if="it.type === 'bar' && barStyle(it)" :content="tipText(it)" placement="top">
            <span class="rt-bar" :class="it.status ?? 'plan'" :style="barStyle(it)!">
              <em class="rt-bar-text">{{ it.label }}</em>
            </span>
          </el-tooltip>
        </template>
      </div>
    </div>

    <div class="rt-legend">
      <span><i class="lg diamond plan" /> 版本（计划）</span>
      <span><i class="lg diamond done" /> 版本（已发布）</span>
      <span><i class="lg flag plan" /> DCP（计划）</span>
      <span><i class="lg flag done" /> DCP（已决策）</span>
      <span><i class="lg flag late" /> DCP（逾期）</span>
      <span><i class="lg barlg active" /> 迭代</span>
    </div>
  </div>
</template>

<style scoped>
.rt { border: 1px solid #ebeef5; border-radius: 6px; background: #fff; padding: 8px 0 4px; overflow: hidden; }
.rt-row { display: flex; align-items: stretch; min-height: 52px; border-top: 1px solid #f5f7fa; }
.rt-row.rt-axis { min-height: 26px; border-top: none; }
.rt-label { width: 110px; flex: none; display: flex; align-items: center; padding-left: 14px; font-size: 13px; color: #606266; font-weight: 600; }
.rt-track { flex: 1; position: relative; margin-right: 16px; }

.rt-tick { position: absolute; top: 4px; transform: translateX(-50%); font-size: 11px; color: #c0c4cc; }
.rt-grid { position: absolute; top: 0; bottom: 0; border-left: 1px dashed #f0f2f5; }
.rt-today { position: absolute; top: 2px; transform: translateX(-50%); z-index: 3; }
.rt-today i { font-style: normal; font-size: 11px; color: #fff; background: #f56c6c; border-radius: 3px; padding: 1px 5px; }
.rt-today-line { position: absolute; top: 0; bottom: 0; border-left: 1.5px solid #f56c6c; opacity: .55; z-index: 2; }

.rt-point { position: absolute; top: 50%; transform: translate(-50%, -50%); display: flex; flex-direction: column; align-items: center; z-index: 1; cursor: default; }
.rt-point .rt-mark { display: block; width: 10px; height: 10px; }
.rt-point.diamond .rt-mark { transform: rotate(45deg); border: 2px solid currentColor; background: #fff; }
.rt-point.flag .rt-mark { width: 0; height: 0; border-left: 6px solid currentColor; border-top: 5px solid transparent; border-bottom: 5px solid transparent; border-right: none; }
.rt-point.dot .rt-mark { border-radius: 50%; background: currentColor; }
.rt-point .rt-text { font-style: normal; font-size: 11px; margin-top: 2px; color: #606266; white-space: nowrap; }

.rt-point.plan { color: #409eff; }
.rt-point.done { color: #67c23a; }
.rt-point.done.diamond .rt-mark { background: currentColor; }
.rt-point.late { color: #f56c6c; }
.rt-point.active { color: #e6a23c; }

.rt-bar { position: absolute; top: 50%; transform: translateY(-50%); height: 18px; border-radius: 9px; opacity: .85; display: flex; align-items: center; overflow: hidden; }
.rt-bar.plan { background: #c6e2ff; }
.rt-bar.active { background: #409eff; }
.rt-bar.done { background: #d1edc4; }
.rt-bar.late { background: #fbc4c4; }
.rt-bar-text { font-style: normal; font-size: 11px; color: #303133; padding: 0 8px; white-space: nowrap; }
.rt-bar.active .rt-bar-text { color: #fff; }

.rt-legend { display: flex; gap: 16px; padding: 8px 14px 4px; font-size: 12px; color: #909399; flex-wrap: wrap; }
.rt-legend .lg { display: inline-block; width: 10px; height: 10px; margin-right: 4px; vertical-align: -1px; }
.rt-legend .diamond { transform: rotate(45deg); border: 2px solid currentColor; }
.rt-legend .diamond.plan { color: #409eff; background: #fff; }
.rt-legend .diamond.done { color: #67c23a; background: #67c23a; }
.rt-legend .flag { width: 0; height: 0; border-left: 8px solid currentColor; border-top: 5px solid transparent; border-bottom: 5px solid transparent; }
.rt-legend .flag.plan { color: #409eff; }
.rt-legend .flag.done { color: #67c23a; }
.rt-legend .flag.late { color: #f56c6c; }
.rt-legend .barlg { width: 18px; height: 8px; border-radius: 4px; }
.rt-legend .barlg.active { background: #409eff; }
</style>
