<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, reactive, ref } from 'vue'
import * as echarts from 'echarts'
import { execApi, type ExecOverview, type ProjectCard } from '@/api/exec'

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

/* ---------- KPI 数字滚动 ---------- */
const kpi = reactive({ active: 0, reqRate: 0, sprints: 0, defects: 0, changes: 0, high: 0, doing: 0 })
function countUp(key: keyof typeof kpi, target: number) {
  const from = kpi[key]
  if (from === target) return
  const start = performance.now()
  const dur = 700
  const step = (t: number) => {
    const k = Math.min((t - start) / dur, 1)
    kpi[key] = Math.round(from + (target - from) * (1 - Math.pow(1 - k, 3)))
    if (k < 1) requestAnimationFrame(step)
  }
  requestAnimationFrame(step)
}

/* ---------- IPD 阶段泳道 ---------- */
const stageOrder = (s: string) => {
  if (s.includes('概念')) return 1
  if (s.includes('计划')) return 2
  if (s.includes('开发')) return 3
  if (s.includes('验证')) return 4
  if (s.includes('发布')) return 5
  return 9
}
const stageLanes = computed(() => {
  const lanes = new Map<string, ProjectCard[]>()
  for (const p of data.value?.projects ?? []) {
    const key = p.stageName || '未设阶段'
    if (!lanes.has(key)) lanes.set(key, [])
    lanes.get(key)!.push(p)
  }
  return [...lanes.entries()]
    .map(([stage, items]) => ({ stage, items }))
    .sort((a, b) => stageOrder(a.stage) - stageOrder(b.stage))
})

/* ---------- 相对时间 ---------- */
function timeAgo(at: string | null): string {
  if (!at) return ''
  const ms = Date.now() - new Date(at).getTime()
  const min = Math.floor(ms / 60000)
  if (min < 1) return '刚刚'
  if (min < 60) return `${min}分钟前`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h}小时前`
  return `${Math.floor(h / 24)}天前`
}

/* ---------- 图表 ---------- */
const stackEl = ref<HTMLElement | null>(null)
const leadEl = ref<HTMLElement | null>(null)
const defectEl = ref<HTMLElement | null>(null)
const passEl = ref<HTMLElement | null>(null)
const charts: echarts.ECharts[] = []

const AXIS = {
  axisLine: { lineStyle: { color: '#26385a' } },
  axisLabel: { color: '#8fa2c0', fontSize: 11 },
  splitLine: { lineStyle: { color: '#1a2946' } },
}
const grad = (c1: string, c2: string) =>
  new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: c1 }, { offset: 1, color: c2 }])

function initChart(el: HTMLElement | null): echarts.ECharts | null {
  if (!el) return null
  const existed = charts.find((c) => c.getDom() === el)
  if (existed) return existed
  const c = echarts.init(el)
  charts.push(c)
  return c
}

async function load() {
  data.value = await execApi.overview()
  countdown.value = 60
  const s = data.value.summary
  countUp('active', s.projectsActive)
  countUp('reqRate', s.reqTotal ? Math.round((s.reqAccepted / s.reqTotal) * 100) : 0)
  countUp('sprints', s.activeSprints)
  countUp('defects', s.openDefects)
  countUp('changes', s.pendingChanges)
  countUp('high', s.alertHigh)
  countUp('doing', s.improvementsDoing)
  await nextTick()
  renderCharts()
}

function renderCharts() {
  const d = data.value
  if (!d) return
  const codes = d.projects.map((p) => p.code)

  const stack = initChart(stackEl.value)
  stack?.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0, textStyle: { color: '#c9d4e8', fontSize: 11 }, itemWidth: 12, itemHeight: 8 },
    grid: { top: 14, left: 30, right: 8, bottom: 40 },
    xAxis: { type: 'category', data: d.weeklyThroughput.map((w) => w.weekStart.slice(5)), ...AXIS },
    yAxis: { type: 'value', minInterval: 1, ...AXIS },
    series: codes.map((c) => ({
      name: c, type: 'bar', stack: 'total',
      data: d.weeklyThroughput.map((w) => w.byProject[c] ?? 0),
      itemStyle: { color: colorOf.value[c], borderColor: SURFACE, borderWidth: 2, borderRadius: 2 },
      barMaxWidth: 26,
    })),
  })

  const withLead = d.projects.filter((p) => p.leadP85 !== null)
  const lead = initChart(leadEl.value)
  lead?.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'item', valueFormatter: (v: number) => `${v} 天` },
    grid: { top: 14, left: 52, right: 34, bottom: 20 },
    xAxis: { type: 'value', ...AXIS },
    yAxis: { type: 'category', inverse: true, data: withLead.map((p) => p.code), ...AXIS },
    series: [{
      name: 'Lead P85', type: 'bar', barMaxWidth: 14,
      data: withLead.map((p) => ({ value: p.leadP85, itemStyle: { color: colorOf.value[p.code], borderRadius: [0, 4, 4, 0] } })),
      label: { show: true, position: 'right', color: '#c9d4e8', fontSize: 11, formatter: '{c}天' },
    }],
  })

  const defect = initChart(defectEl.value)
  defect?.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0, textStyle: { color: '#c9d4e8', fontSize: 11 }, itemWidth: 12, itemHeight: 8 },
    grid: { top: 14, left: 30, right: 8, bottom: 40 },
    xAxis: { type: 'category', data: d.combinedDefectTrend.map((w) => w.weekStart.slice(5)), ...AXIS },
    yAxis: { type: 'value', minInterval: 1, ...AXIS },
    series: [
      { name: '缺陷流入', type: 'bar', barMaxWidth: 12, data: d.combinedDefectTrend.map((w) => w.inflow),
        itemStyle: { color: grad('#f56c6c', '#a23a3a'), borderRadius: [3, 3, 0, 0] } },
      { name: '缺陷关闭', type: 'bar', barMaxWidth: 12, data: d.combinedDefectTrend.map((w) => w.closed),
        itemStyle: { color: grad('#67c23a', '#3a7a22'), borderRadius: [3, 3, 0, 0] } },
    ],
  })

  const withPass = d.projects.filter((p) => p.testPassRate !== null)
  const pass = initChart(passEl.value)
  pass?.setOption({
    backgroundColor: 'transparent',
    tooltip: { trigger: 'item', valueFormatter: (v: number) => `${v}%` },
    grid: { top: 14, left: 52, right: 40, bottom: 20 },
    xAxis: { type: 'value', max: 100, ...AXIS },
    yAxis: { type: 'category', inverse: true, data: withPass.map((p) => p.code), ...AXIS },
    series: [{
      name: '测试通过率', type: 'bar', barMaxWidth: 14,
      data: withPass.map((p) => ({ value: p.testPassRate, itemStyle: { color: colorOf.value[p.code], borderRadius: [0, 4, 4, 0] } })),
      label: { show: true, position: 'right', color: '#c9d4e8', fontSize: 11, formatter: '{c}%' },
    }],
  })
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
  charts.forEach((c) => c.dispose())
})
</script>

<template>
  <div ref="rootEl" class="screen">
    <header class="head">
      <router-link to="/dashboard" class="back">← 返回工作台</router-link>
      <h1><span class="deco l" />IPD 项目群数据大屏<span class="deco r" /></h1>
      <div class="head-right">
        <span class="clock">{{ now.toLocaleString('zh-CN', { hour12: false }) }}</span>
        <span class="refresh">{{ countdown }}s</span>
        <button class="fs" @click="toggleFullscreen">⛶ 全屏</button>
      </div>
    </header>

    <template v-if="data">
      <!-- KPI 带 -->
      <section class="kpis">
        <div class="kpi"><div class="num">{{ kpi.active }}</div><div class="lbl">在建项目<span v-if="data.summary.projectsOnHold"> · 挂起 {{ data.summary.projectsOnHold }}</span></div></div>
        <div class="kpi"><div class="num">{{ kpi.reqRate }}<small>%</small></div><div class="lbl">需求验收率（{{ data.summary.reqAccepted }}/{{ data.summary.reqTotal }}）</div></div>
        <div class="kpi"><div class="num">{{ kpi.sprints }}</div><div class="lbl">活跃 Sprint</div></div>
        <div class="kpi" :class="{ bad: data.summary.openDefects }"><div class="num">{{ kpi.defects }}</div><div class="lbl">未关缺陷</div></div>
        <div class="kpi" :class="{ warn: data.summary.pendingChanges }"><div class="num">{{ kpi.changes }}</div><div class="lbl">待决策变更</div></div>
        <div class="kpi" :class="{ bad: data.summary.alertHigh }"><div class="num">{{ kpi.high }}</div><div class="lbl">高危预警</div></div>
        <div class="kpi"><div class="num">{{ kpi.doing }}<small>/{{ data.summary.improvementsVerified }}</small></div><div class="lbl">改进 进行中/已验证</div></div>
      </section>

      <div class="main">
        <div class="left">
          <!-- IPD 阶段泳道 -->
          <section class="panel">
            <div class="p-title">IPD 阶段分布</div>
            <div class="lanes">
              <div v-for="lane in stageLanes" :key="lane.stage" class="lane">
                <div class="lane-head">{{ lane.stage }}</div>
                <div class="lane-body">
                  <span v-for="p in lane.items" :key="p.id" class="lane-chip">
                    <i class="dot" :style="{ background: HEALTH_COLOR[p.health] }" />{{ p.code }}
                  </span>
                </div>
              </div>
            </div>
          </section>

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
                <span v-if="p.lastDecision" class="pc-dec">决策 {{ DECISION_LABEL[p.lastDecision] ?? p.lastDecision }}（{{ p.lastDecisionAt }}）</span>
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

          <!-- 图表 2×2 -->
          <section class="charts">
            <div class="panel"><div class="p-title">近 8 周验收吞吐（按项目）</div><div ref="stackEl" class="chart" /></div>
            <div class="panel"><div class="p-title">端到端 Lead Time P85（天）</div><div ref="leadEl" class="chart" /></div>
            <div class="panel"><div class="p-title">组合缺陷 流入 / 关闭（近 8 周）</div><div ref="defectEl" class="chart" /></div>
            <div class="panel"><div class="p-title">各项目测试通过率</div><div ref="passEl" class="chart" /></div>
          </section>
        </div>

        <!-- 右栏 -->
        <aside class="right">
          <section class="panel a-panel">
            <div class="p-title">预警与待办 <span class="a-cnt">{{ data.summary.alertHigh + data.summary.alertMed }}</span></div>
            <div v-if="!data.alerts.length" class="a-empty">当前无预警 ✓</div>
            <div v-for="(a, i) in data.alerts" :key="i" class="a-row">
              <span class="a-sev" :class="{ pulse: a.severity === 'HIGH' }" :style="{ background: SEV_COLOR[a.severity] }">{{ a.severity }}</span>
              <span class="a-proj">{{ a.projectCode }}</span>
              <div class="a-body">
                <div class="a-title">{{ a.title }}</div>
                <div class="a-detail">{{ a.detail }}</div>
              </div>
            </div>
          </section>

          <section class="panel">
            <div class="p-title">最新动态</div>
            <div v-for="(e, i) in data.recentEvents" :key="i" class="ev-row">
              <span class="ev-time">{{ timeAgo(e.at) }}</span>
              <span class="ev-proj">{{ e.projectCode }}</span>
              <span class="ev-sum">{{ e.summary }}</span>
            </div>
          </section>

          <section class="panel" v-if="data.activeImprovements.length">
            <div class="p-title">进行中的改进</div>
            <div v-for="imp in data.activeImprovements" :key="imp.code" class="imp-row">
              <span class="ev-proj">{{ imp.projectCode }}</span>
              <div class="a-body">
                <div class="a-title">{{ imp.title }}</div>
                <div class="a-detail">{{ imp.code }}<template v-if="imp.metricName"> · {{ imp.metricName }}</template></div>
              </div>
            </div>
          </section>
        </aside>
      </div>
    </template>
  </div>
</template>

<style scoped>
.screen { min-height: 100vh; background: radial-gradient(ellipse 90% 60% at 50% 0%, #17284a 0%, #0f1a2c 45%, #0b1424 100%); color: #e8eef8; padding: 16px 24px; box-sizing: border-box; font-size: 14px; }

.head { display: flex; align-items: center; gap: 20px; margin-bottom: 14px; }
.head h1 { flex: 1; display: flex; align-items: center; justify-content: center; gap: 18px; font-size: 25px; letter-spacing: 6px; margin: 0; color: #fff; text-shadow: 0 0 18px rgba(57, 135, 229, .45); }
.deco { display: inline-block; height: 2px; width: 120px; }
.deco.l { background: linear-gradient(90deg, transparent, #3987e5); }
.deco.r { background: linear-gradient(90deg, #3987e5, transparent); }
.back { color: #8fa2c0; text-decoration: none; font-size: 13px; }
.back:hover { color: #3987e5; }
.head-right { display: flex; align-items: center; gap: 12px; color: #8fa2c0; font-size: 13px; }
.clock { font-variant-numeric: tabular-nums; }
.refresh { color: #55688a; }
.fs { background: rgba(57,135,229,.12); color: #c9d4e8; border: 1px solid #2a3a55; border-radius: 6px; padding: 4px 10px; cursor: pointer; }
.fs:hover { border-color: #3987e5; }

/* 面板通用：渐变卡 + 描边 + 标题竖条 */
.panel { background: linear-gradient(180deg, #18263f, #131e33); border: 1px solid rgba(90, 130, 190, .18); border-radius: 10px; padding: 10px 12px; box-shadow: 0 2px 12px rgba(0, 0, 0, .25); }
.p-title { font-size: 13px; color: #c9d4e8; margin-bottom: 8px; padding-left: 9px; position: relative; }
.p-title::before { content: ''; position: absolute; left: 0; top: 2px; bottom: 2px; width: 3px; border-radius: 2px; background: linear-gradient(180deg, #3987e5, #199e70); }

.kpis { display: grid; grid-template-columns: repeat(7, 1fr); gap: 10px; margin-bottom: 12px; }
.kpi { background: linear-gradient(180deg, #18263f, #131e33); border: 1px solid rgba(90, 130, 190, .18); border-radius: 10px; padding: 10px 14px 8px; text-align: center; position: relative; overflow: hidden; }
.kpi::before { content: ''; position: absolute; top: 0; left: 20%; right: 20%; height: 3px; border-radius: 0 0 3px 3px; background: linear-gradient(90deg, transparent, #3987e5, transparent); }
.kpi .num { font-size: 30px; font-weight: 700; color: #fff; font-variant-numeric: tabular-nums; text-shadow: 0 0 12px rgba(57, 135, 229, .35); }
.kpi .num small { font-size: 15px; color: #8fa2c0; }
.kpi.bad .num { color: #f56c6c; text-shadow: 0 0 12px rgba(245, 108, 108, .4); }
.kpi.warn .num { color: #e6a23c; text-shadow: 0 0 12px rgba(230, 162, 60, .4); }
.kpi .lbl { font-size: 12px; color: #8fa2c0; margin-top: 1px; }

.main { display: grid; grid-template-columns: 3fr 1fr; gap: 12px; align-items: start; }
.left { display: flex; flex-direction: column; gap: 12px; }
.right { display: flex; flex-direction: column; gap: 12px; }

/* 阶段泳道 */
.lanes { display: grid; grid-auto-flow: column; grid-auto-columns: 1fr; gap: 8px; }
.lane { background: rgba(15, 26, 44, .5); border-radius: 8px; padding: 6px 8px; }
.lane-head { font-size: 12px; color: #8fa2c0; margin-bottom: 6px; text-align: center; border-bottom: 1px solid #1e2f4e; padding-bottom: 4px; }
.lane-body { display: flex; flex-wrap: wrap; gap: 6px; justify-content: center; min-height: 24px; }
.lane-chip { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; color: #e8eef8; background: #1c2c49; border-radius: 12px; padding: 2px 9px; }
.lane-chip .dot { width: 7px; height: 7px; border-radius: 50%; }

.cards { display: grid; grid-template-columns: repeat(auto-fill, minmax(310px, 1fr)); gap: 10px; }
.pcard { background: linear-gradient(180deg, #18263f, #131e33); border: 1px solid rgba(90, 130, 190, .18); border-left: 4px solid; border-radius: 10px; padding: 10px 12px; transition: box-shadow .2s; }
.pcard:hover { box-shadow: 0 0 16px rgba(57, 135, 229, .2); }
.pc-head { display: flex; align-items: center; gap: 8px; }
.dot { width: 10px; height: 10px; border-radius: 50%; flex-shrink: 0; }
.pc-code { color: #fff; }
.pc-name { color: #c9d4e8; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; font-size: 13px; }
.health { font-size: 12px; flex-shrink: 0; }
.pc-stage { font-size: 12px; color: #8fa2c0; margin: 5px 0; }
.pc-dec { margin-left: 8px; }
.pc-hold { margin-left: 8px; color: #e6a23c; }
.pc-bar { display: flex; align-items: center; gap: 10px; margin-bottom: 6px; }
.bar-bg { flex: 1; height: 7px; background: #0d1626; border-radius: 4px; overflow: hidden; }
.bar-fill { height: 100%; border-radius: 4px; transition: width .6s ease; }
.bar-txt { font-size: 12px; color: #c9d4e8; white-space: nowrap; }
.pc-stats { display: flex; gap: 12px; font-size: 12px; color: #8fa2c0; flex-wrap: wrap; }
.pc-stats b { color: #e8eef8; font-weight: 600; }
.v-bad { color: #f56c6c !important; }
.v-warn { color: #e6a23c !important; }
.v-good { color: #67c23a !important; }

.charts { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.chart { height: 190px; }

/* 右栏 */
.a-panel { max-height: 44vh; overflow-y: auto; }
.a-cnt { color: #f56c6c; }
.a-empty { color: #67c23a; text-align: center; padding: 24px 0; }
.a-row { display: flex; gap: 8px; padding: 7px 0; border-bottom: 1px solid #1a2946; align-items: flex-start; }
.a-row:last-child { border-bottom: none; }
.a-sev { font-size: 11px; color: #0f1a2c; font-weight: 700; border-radius: 4px; padding: 1px 6px; flex-shrink: 0; }
.a-sev.pulse { animation: pulse 2s ease-in-out infinite; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: .55; } }
.a-proj { font-size: 12px; color: #8fa2c0; flex-shrink: 0; padding-top: 1px; font-family: monospace; }
.a-body { min-width: 0; }
.a-title { font-size: 13px; color: #e8eef8; }
.a-detail { font-size: 12px; color: #8fa2c0; overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }

.ev-row { display: flex; gap: 8px; padding: 6px 0; border-bottom: 1px solid #1a2946; font-size: 12px; align-items: baseline; }
.ev-row:last-child { border-bottom: none; }
.ev-time { color: #55688a; flex-shrink: 0; min-width: 52px; }
.ev-proj { color: #8fa2c0; font-family: monospace; flex-shrink: 0; }
.ev-sum { color: #c9d4e8; overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }

.imp-row { display: flex; gap: 8px; padding: 7px 0; border-bottom: 1px solid #1a2946; align-items: flex-start; }
.imp-row:last-child { border-bottom: none; }
</style>
