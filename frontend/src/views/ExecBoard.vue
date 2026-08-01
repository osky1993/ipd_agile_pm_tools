<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import * as echarts from 'echarts'
import { execApi, type ExecOverview } from '@/api/exec'

const data = ref<ExecOverview | null>(null)
const now = ref(new Date())
const countdown = ref(60)
const rootEl = ref<HTMLElement | null>(null)
let clockTimer: number | undefined
let tickTimer: number | undefined

const SURFACE = '#0f1a2c'
/** 分类色（validate_palette.js 于 dark surface #0f1a2c 全项 PASS），按项目出现顺序固定分配、色随实体 */
const SERIES = ['#3987e5', '#008300', '#d55181', '#c98500', '#199e70']
const HEALTH_COLOR: Record<string, string> = { GOOD: '#67c23a', RISK: '#e6a23c', DANGER: '#f56c6c' }
const HEALTH_LABEL: Record<string, string> = { GOOD: '健康', RISK: '关注', DANGER: '风险' }
const SEV_COLOR: Record<string, string> = { HIGH: '#f56c6c', MED: '#e6a23c', LOW: '#6b7a94' }
const LIFECYCLE: Record<string, string> = { ACTIVE: '进行中', ON_HOLD: '已挂起' }
const DECISION_LABEL: Record<string, string> = { PASS: '通过', CONDITIONAL: '有条件通过', REJECT: '不通过' }

const colorOf = computed<Record<string, string>>(() => {
  const m: Record<string, string> = {}
  data.value?.projects.forEach((p, i) => { m[p.code] = SERIES[i % SERIES.length] })
  return m
})
const reqRate = computed(() => {
  const s = data.value?.summary
  return !s || !s.reqTotal ? 0 : Math.round((s.reqAccepted / s.reqTotal) * 100)
})

const stackEl = ref<HTMLElement | null>(null)
const leadEl = ref<HTMLElement | null>(null)
let stackChart: echarts.ECharts | null = null
let leadChart: echarts.ECharts | null = null

const AXIS = {
  axisLine: { lineStyle: { color: '#2a3a55' } },
  axisLabel: { color: '#8fa2c0' },
  splitLine: { lineStyle: { color: '#1c2a44' } },
}

async function load() {
  data.value = await execApi.overview()
  countdown.value = 60
  await nextTick()
  renderCharts()
}

function renderCharts() {
  const d = data.value
  if (!d) return
  const codes = d.projects.map((p) => p.code)
  if (stackEl.value) {
    if (!stackChart) stackChart = echarts.init(stackEl.value)
    stackChart.setOption({
      backgroundColor: 'transparent',
      tooltip: { trigger: 'axis' },
      legend: { bottom: 0, textStyle: { color: '#c9d4e8' } },
      grid: { top: 16, left: 34, right: 12, bottom: 40 },
      xAxis: { type: 'category', data: d.weeklyThroughput.map((w) => w.weekStart.slice(5)), ...AXIS },
      yAxis: { type: 'value', minInterval: 1, ...AXIS },
      series: codes.map((c) => ({
        name: c,
        type: 'bar',
        stack: 'total',
        data: d.weeklyThroughput.map((w) => w.byProject[c] ?? 0),
        itemStyle: { color: colorOf.value[c], borderColor: SURFACE, borderWidth: 2, borderRadius: 2 },
        barMaxWidth: 30,
      })),
    })
  }
  if (leadEl.value) {
    if (!leadChart) leadChart = echarts.init(leadEl.value)
    const withLead = d.projects.filter((p) => p.leadP85 !== null)
    leadChart.setOption({
      backgroundColor: 'transparent',
      tooltip: { trigger: 'item', valueFormatter: (v: number) => `${v} 天` },
      grid: { top: 16, left: 56, right: 28, bottom: 24 },
      xAxis: { type: 'value', ...AXIS },
      yAxis: { type: 'category', inverse: true, data: withLead.map((p) => p.code), ...AXIS },
      series: [{
        name: 'Lead P85',
        type: 'bar',
        barMaxWidth: 18,
        data: withLead.map((p) => ({
          value: p.leadP85,
          itemStyle: { color: colorOf.value[p.code], borderRadius: [0, 4, 4, 0] },
        })),
        label: { show: true, position: 'right', color: '#c9d4e8', formatter: '{c} 天' },
      }],
    })
  }
}

function toggleFullscreen() {
  if (document.fullscreenElement) {
    document.exitFullscreen()
  } else {
    rootEl.value?.requestFullscreen()
  }
}

onMounted(async () => {
  await load()
  clockTimer = window.setInterval(() => { now.value = new Date() }, 1000)
  tickTimer = window.setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) load()
  }, 1000)
})
onUnmounted(() => {
  window.clearInterval(clockTimer)
  window.clearInterval(tickTimer)
  stackChart?.dispose()
  leadChart?.dispose()
})
</script>

<template>
  <div ref="rootEl" class="screen">
    <header class="head">
      <router-link to="/dashboard" class="back">← 返回工作台</router-link>
      <h1>IPD 项目群数据大屏</h1>
      <div class="head-right">
        <span class="clock">{{ now.toLocaleString('zh-CN', { hour12: false }) }}</span>
        <span class="refresh">{{ countdown }}s 后刷新</span>
        <button class="fs" @click="toggleFullscreen">⛶ 全屏</button>
      </div>
    </header>

    <template v-if="data">
      <!-- KPI 带 -->
      <section class="kpis">
        <div class="kpi"><div class="num">{{ data.summary.projectsActive }}</div><div class="lbl">在建项目<span v-if="data.summary.projectsOnHold"> · 挂起 {{ data.summary.projectsOnHold }}</span></div></div>
        <div class="kpi"><div class="num">{{ reqRate }}<small>%</small></div><div class="lbl">需求验收率（{{ data.summary.reqAccepted }}/{{ data.summary.reqTotal }}）</div></div>
        <div class="kpi"><div class="num">{{ data.summary.activeSprints }}</div><div class="lbl">活跃 Sprint</div></div>
        <div class="kpi" :class="{ bad: data.summary.openDefects }"><div class="num">{{ data.summary.openDefects }}</div><div class="lbl">未关缺陷</div></div>
        <div class="kpi" :class="{ warn: data.summary.pendingChanges }"><div class="num">{{ data.summary.pendingChanges }}</div><div class="lbl">待决策变更</div></div>
        <div class="kpi" :class="{ bad: data.summary.alertHigh }"><div class="num">{{ data.summary.alertHigh }}</div><div class="lbl">高危预警</div></div>
        <div class="kpi"><div class="num">{{ data.summary.improvementsDoing }}<small>/{{ data.summary.improvementsVerified }}</small></div><div class="lbl">改进 进行中/已验证</div></div>
      </section>

      <div class="main">
        <div class="left">
          <!-- 项目健康卡 -->
          <section class="cards">
            <div v-for="p in data.projects" :key="p.id" class="pcard"
              :style="{ borderLeftColor: HEALTH_COLOR[p.health] }">
              <div class="pc-head">
                <span class="dot" :style="{ background: colorOf[p.code] }" />
                <b class="pc-code">{{ p.code }}</b>
                <span class="pc-name">{{ p.name }}</span>
                <span class="health" :style="{ color: HEALTH_COLOR[p.health] }">● {{ HEALTH_LABEL[p.health] }}</span>
              </div>
              <div class="pc-stage">
                {{ p.gateName || '未设阶段' }}
                <span v-if="p.lastDecision" class="pc-dec">最新决策 {{ DECISION_LABEL[p.lastDecision] ?? p.lastDecision }}（{{ p.lastDecisionAt }}）</span>
                <span v-if="p.lifecycleStatus !== 'ACTIVE'" class="pc-hold">{{ LIFECYCLE[p.lifecycleStatus] }}</span>
              </div>
              <div class="pc-bar">
                <div class="bar-bg"><div class="bar-fill" :style="{ width: (p.reqTotal ? (p.reqAccepted / p.reqTotal) * 100 : 0) + '%', background: colorOf[p.code] }" /></div>
                <span class="bar-txt">需求 {{ p.reqAccepted }}/{{ p.reqTotal }}</span>
              </div>
              <div class="pc-stats">
                <span>通过率 <b>{{ p.testPassRate === null ? '—' : p.testPassRate + '%' }}</b></span>
                <span>缺陷 <b :class="{ 'v-bad': p.openDefects }">{{ p.openDefects }}</b></span>
                <span>红线 <b :class="{ 'v-bad': p.redlineUnmet }">{{ p.redlineUnmet ? p.redlineUnmet + ' 未满足' : '满足' }}</b></span>
                <span>预警 <b :class="{ 'v-bad': p.alertHigh, 'v-warn': !p.alertHigh && p.alertMed }">{{ p.alertHigh + p.alertMed }}</b></span>
                <span>整机 <b :class="p.ready ? 'v-good' : 'v-warn'">{{ p.ready ? '就绪' : '未就绪' }}</b></span>
              </div>
            </div>
          </section>

          <!-- 图表 -->
          <section class="charts">
            <div class="chart-box">
              <div class="chart-title">近 8 周验收吞吐（按项目）</div>
              <div ref="stackEl" class="chart" />
            </div>
            <div class="chart-box">
              <div class="chart-title">端到端 Lead Time P85（天）</div>
              <div ref="leadEl" class="chart" />
            </div>
          </section>
        </div>

        <!-- 预警栏 -->
        <aside class="alerts">
          <div class="a-head">预警与待办 <span class="a-cnt">{{ data.summary.alertHigh + data.summary.alertMed }}</span></div>
          <div v-if="!data.alerts.length" class="a-empty">当前无预警 ✓</div>
          <div v-for="(a, i) in data.alerts" :key="i" class="a-row">
            <span class="a-sev" :style="{ background: SEV_COLOR[a.severity] }">{{ a.severity }}</span>
            <span class="a-proj">{{ a.projectCode }}</span>
            <div class="a-body">
              <div class="a-title">{{ a.title }}</div>
              <div class="a-detail">{{ a.detail }}</div>
            </div>
          </div>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.screen { min-height: 100vh; background: #0f1a2c; color: #e8eef8; padding: 18px 26px; box-sizing: border-box; font-size: 14px; }
.head { display: flex; align-items: center; gap: 20px; margin-bottom: 16px; }
.head h1 { flex: 1; text-align: center; font-size: 26px; letter-spacing: 4px; margin: 0; color: #fff; }
.back { color: #8fa2c0; text-decoration: none; font-size: 13px; }
.back:hover { color: #3987e5; }
.head-right { display: flex; align-items: center; gap: 14px; color: #8fa2c0; font-size: 13px; }
.clock { font-variant-numeric: tabular-nums; }
.fs { background: #1c2a44; color: #c9d4e8; border: 1px solid #2a3a55; border-radius: 6px; padding: 4px 10px; cursor: pointer; }
.fs:hover { border-color: #3987e5; }

.kpis { display: grid; grid-template-columns: repeat(7, 1fr); gap: 12px; margin-bottom: 16px; }
.kpi { background: #16233c; border-radius: 10px; padding: 12px 16px; text-align: center; }
.kpi .num { font-size: 32px; font-weight: 700; color: #fff; font-variant-numeric: tabular-nums; }
.kpi .num small { font-size: 16px; color: #8fa2c0; }
.kpi.bad .num { color: #f56c6c; }
.kpi.warn .num { color: #e6a23c; }
.kpi .lbl { font-size: 12px; color: #8fa2c0; margin-top: 2px; }

.main { display: grid; grid-template-columns: 1fr 340px; gap: 14px; }
.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(330px, 1fr)); gap: 12px; margin-bottom: 14px; }
.pcard { background: #16233c; border-radius: 10px; border-left: 4px solid; padding: 12px 14px; }
.pc-head { display: flex; align-items: center; gap: 8px; }
.dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.pc-code { color: #fff; }
.pc-name { color: #c9d4e8; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.health { font-size: 12px; flex-shrink: 0; }
.pc-stage { font-size: 12px; color: #8fa2c0; margin: 6px 0; }
.pc-dec { margin-left: 8px; }
.pc-hold { margin-left: 8px; color: #e6a23c; }
.pc-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.bar-bg { flex: 1; height: 8px; background: #0f1a2c; border-radius: 4px; overflow: hidden; }
.bar-fill { height: 100%; border-radius: 4px; }
.bar-txt { font-size: 12px; color: #c9d4e8; white-space: nowrap; }
.pc-stats { display: flex; gap: 14px; font-size: 12px; color: #8fa2c0; flex-wrap: wrap; }
.pc-stats b { color: #e8eef8; font-weight: 600; }
.v-bad { color: #f56c6c !important; }
.v-warn { color: #e6a23c !important; }
.v-good { color: #67c23a !important; }

.charts { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.chart-box { background: #16233c; border-radius: 10px; padding: 12px; }
.chart-title { font-size: 13px; color: #c9d4e8; margin-bottom: 4px; }
.chart { height: 240px; }

.alerts { background: #16233c; border-radius: 10px; padding: 12px; max-height: calc(100vh - 220px); overflow-y: auto; }
.a-head { font-size: 15px; color: #fff; margin-bottom: 10px; }
.a-cnt { color: #f56c6c; }
.a-empty { color: #67c23a; text-align: center; padding: 30px 0; }
.a-row { display: flex; gap: 8px; padding: 8px 0; border-bottom: 1px solid #1c2a44; align-items: flex-start; }
.a-sev { font-size: 11px; color: #0f1a2c; font-weight: 700; border-radius: 4px; padding: 1px 6px; flex-shrink: 0; }
.a-proj { font-size: 12px; color: #8fa2c0; flex-shrink: 0; padding-top: 1px; }
.a-body { min-width: 0; }
.a-title { font-size: 13px; color: #e8eef8; }
.a-detail { font-size: 12px; color: #8fa2c0; overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
</style>
