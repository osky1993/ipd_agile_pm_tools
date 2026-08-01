<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import * as echarts from 'echarts'
import { teamApi, type TeamOverview, type Blocker, type Handoff } from '@/api/team'
import ProjectChips from '@/components/ProjectChips.vue'
import WorkItemDrawer from '@/components/WorkItemDrawer.vue'

const projectId = ref<number | null>(null)
const data = ref<TeamOverview | null>(null)
const now = ref(new Date())
const countdown = ref(60)
const rootEl = ref<HTMLElement | null>(null)
let clockTimer: number | undefined
let tickTimer: number | undefined

const drawerOpen = ref(false)
const drawerId = ref<number | null>(null)
function openItem(id: number | null) {
  if (!id) return
  drawerId.value = id
  drawerOpen.value = true
}

const SURFACE = '#0f1a2c'
/** 状态含空格，集中常量防笔误；色板与大屏一致（分类色已过 dataviz 校验） */
const STATUS_META: { key: string; label: string; color: string; match: (s: string) => boolean }[] = [
  { key: 'backlog', label: '待办', color: '#6b7a94', match: (s) => ['Backlog', 'Submitted', 'Impact Analysed'].includes(s) },
  { key: 'ready', label: '就绪', color: '#3987e5', match: (s) => ['Ready', 'Approved'].includes(s) },
  { key: 'doing', label: '进行中', color: '#c98500', match: (s) => ['In Progress', 'Open', 'Analysing', 'Fixing', 'Implemented', 'Mitigating'].includes(s) },
  { key: 'verify', label: '验证中', color: '#d55181', match: (s) => ['Verification', 'Retesting'].includes(s) },
  { key: 'done', label: '已完成', color: '#199e70', match: (s) => ['Accepted', 'Closed', 'Verified'].includes(s) },
]
const statusCat = (s: string) => Math.max(0, STATUS_META.findIndex((m) => m.match(s)))
const SYMBOL_BY_TYPE: Record<string, string> = {
  CAPABILITY: 'circle', REQUIREMENT: 'circle', STORY: 'circle',
  TASK: 'roundRect', DEFECT: 'triangle', CHANGE: 'diamond', RISK: 'pin',
}
const EDGE_STYLE: Record<string, object> = {
  dep: { color: '#f56c6c', width: 2.5 },
  parent_of: { color: '#55688a', width: 1, type: 'dashed', curveness: 0.1 },
  affects: { color: '#c98500', width: 1.5, type: 'dashed' },
  changes: { color: '#c98500', width: 2, type: 'dotted' },
}
const REL_ZH: Record<string, string> = { dep: '依赖', parent_of: '分解', affects: '缺陷影响', changes: '变更波及' }
const SEV_COLOR: Record<string, string> = { HIGH: '#f56c6c', MED: '#e6a23c', LOW: '#6b7a94' }
const KIND_META: Record<string, { label: string; color: string }> = {
  TEST_READY: { label: '可测试', color: '#d55181' },
  CHANGE_APPROVED: { label: '可实施', color: '#3987e5' },
  RETEST: { label: '待复测', color: '#c98500' },
  UNLOCKED: { label: '已解锁', color: '#199e70' },
}
const COL_LABEL: Record<string, string> = { 'Backlog': '待办', 'Ready': '就绪', 'In Progress': '进行', 'Verification': '验证', 'Accepted': '完成' }

const graphEl = ref<HTMLElement | null>(null)
let graphChart: echarts.ECharts | null = null
let graphFingerprint = ''

const sprintDanger = computed(() => {
  const s = data.value?.sprint
  return !!s && s.donePct !== null && s.timePct > 60 && s.donePct < 50
})

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

async function load() {
  if (!projectId.value) return
  data.value = await teamApi.overview(projectId.value)
  countdown.value = 60
  await nextTick()
  renderGraph()
}

/**
 * DAG 分层布局：所有边都是"上游→下游"语义（前置→后继/父→子/缺陷|变更→受影响），
 * Kahn 拓扑 + longest-path 定层（x=层），barycenter 一轮排序减少交叉（y=层内位次）。
 * 成环边不参与分层（仍绘制）；孤立节点单独放末层之后。
 */
function dagLayout(nodes: { id: number }[], edges: { source: number; target: number }[]) {
  const ids = nodes.map((n) => n.id)
  const idSet = new Set(ids)
  const succ = new Map<number, number[]>()
  const pred = new Map<number, number[]>()
  const indeg = new Map<number, number>()
  ids.forEach((id) => indeg.set(id, 0))
  for (const e of edges) {
    if (!idSet.has(e.source) || !idSet.has(e.target)) continue
    succ.set(e.source, [...(succ.get(e.source) ?? []), e.target])
    pred.set(e.target, [...(pred.get(e.target) ?? []), e.source])
    indeg.set(e.target, (indeg.get(e.target) ?? 0) + 1)
  }
  // Kahn + longest-path 定层；剥不完的（环成员）强制放当前最大层+1
  const layer = new Map<number, number>()
  const queue = ids.filter((id) => (indeg.get(id) ?? 0) === 0)
  queue.forEach((id) => layer.set(id, 0))
  const remaining = new Map(indeg)
  while (queue.length) {
    const n = queue.shift()!
    for (const s of succ.get(n) ?? []) {
      layer.set(s, Math.max(layer.get(s) ?? 0, (layer.get(n) ?? 0) + 1))
      const d = (remaining.get(s) ?? 0) - 1
      remaining.set(s, d)
      if (d === 0) queue.push(s)
    }
  }
  const maxAssigned = Math.max(0, ...layer.values())
  ids.filter((id) => !layer.has(id)).forEach((id) => layer.set(id, maxAssigned + 1)) // 环成员兜底

  // 分层分组 + barycenter 一轮排序（按前驱平均位次）
  const byLayer = new Map<number, number[]>()
  ids.forEach((id) => {
    const l = layer.get(id) ?? 0
    byLayer.set(l, [...(byLayer.get(l) ?? []), id])
  })
  const posInLayer = new Map<number, number>()
  const layers = [...byLayer.keys()].sort((a, b) => a - b)
  for (const l of layers) {
    let group = byLayer.get(l)!
    if (l > 0) {
      const bary = (id: number) => {
        const ps = (pred.get(id) ?? []).filter((p) => posInLayer.has(p))
        return ps.length ? ps.reduce((s, p) => s + posInLayer.get(p)!, 0) / ps.length : 1e9
      }
      group = [...group].sort((a, b) => bary(a) - bary(b))
    }
    group.forEach((id, i) => posInLayer.set(id, i))
    byLayer.set(l, group)
  }
  // 坐标：x=层 × 间距；y=层内居中展开
  const pos = new Map<number, { x: number; y: number }>()
  const X_GAP = 170
  const Y_GAP = 68
  for (const l of layers) {
    const group = byLayer.get(l)!
    group.forEach((id, i) => {
      pos.set(id, { x: l * X_GAP, y: (i - (group.length - 1) / 2) * Y_GAP })
    })
  }
  return pos
}

function renderGraph() {
  const g = data.value?.graph
  if (!graphEl.value || !g) return
  const fp = g.nodes.map((n) => `${n.id}${n.status}${n.blocked}`).join() + '|' + g.edges.length
  if (!graphChart) graphChart = echarts.init(graphEl.value)
  else if (fp === graphFingerprint) return // 数据未变不重排，避免 force 抖动
  graphFingerprint = fp

  if (!g.edges.length && !g.nodes.length) {
    graphChart.clear()
    return
  }
  const pos = dagLayout(g.nodes, g.edges)
  graphChart.clear()
  graphChart.setOption({
    backgroundColor: 'transparent',
    tooltip: {
      formatter: (p: any) => {
        if (p.dataType === 'edge') return REL_ZH[p.data.relation] ?? p.data.relation
        const n = p.data.raw
        return `<b>${n.code}</b> ${n.title}<br/>状态：${n.status}${n.blocked ? '　<span style="color:#f56c6c">⛔被阻塞</span>' : ''}` +
          (n.testBadge ? `<br/>验证用例：${n.testBadge === 'PASS' ? '✓ 通过' : n.testBadge === 'FAIL' ? '✗ 失败' : '○ 未执行'}` : '')
      },
    },
    legend: { bottom: 0, textStyle: { color: '#c9d4e8', fontSize: 11 }, itemWidth: 12, itemHeight: 8,
      data: STATUS_META.map((m) => m.label) },
    series: [{
      type: 'graph',
      layout: 'none',           // DAG 分层：上游在左、下游在右（dagLayout 自算坐标）
      roam: true,
      draggable: true,
      emphasis: { focus: 'adjacency', lineStyle: { width: 4 } },
      labelLayout: { hideOverlap: true },
      label: { show: true, position: 'bottom', fontSize: 11, color: '#c9d4e8',
        formatter: (p: any) => p.data.raw.code },
      edgeSymbol: ['none', 'arrow'],
      edgeSymbolSize: 7,
      categories: STATUS_META.map((m) => ({ name: m.label, itemStyle: { color: m.color } })),
      data: g.nodes.map((n) => ({
        id: String(n.id),
        name: n.code,
        raw: n,
        x: pos.get(n.id)?.x ?? 0,
        y: pos.get(n.id)?.y ?? 0,
        category: statusCat(n.status),
        symbol: SYMBOL_BY_TYPE[n.type] ?? 'circle',
        symbolSize: Math.min(24 + n.degree * 3, 44),
        itemStyle: n.blocked
          ? { borderColor: '#f56c6c', borderWidth: 3, shadowColor: 'rgba(245,108,108,.5)', shadowBlur: 12 }
          : { borderColor: SURFACE, borderWidth: 2 },
        label: n.testBadge === 'FAIL' ? { color: '#f56c6c' } : undefined,
      })),
      edges: g.edges.map((e) => {
        const sx = pos.get(e.source)
        const tx = pos.get(e.target)
        // 同层或逆向（回边/环）加大弧度绕行，避免穿过节点
        const back = sx && tx && tx.x <= sx.x
        const sameRow = sx && tx && Math.abs(sx.y - tx.y) < 1 && Math.abs(tx.x - sx.x) > 200
        return {
          source: String(e.source),
          target: String(e.target),
          relation: e.relation,
          lineStyle: { ...EDGE_STYLE[e.relation], curveness: back ? 0.45 : sameRow ? 0.25 : 0.12 },
        }
      }),
    }],
  })
  graphChart.off('click')
  graphChart.on('click', (p: any) => {
    if (p.dataType === 'node') openItem(Number(p.data.id))
  })
}

function toggleFullscreen() {
  if (document.fullscreenElement) document.exitFullscreen()
  else rootEl.value?.requestFullscreen()
}

onMounted(() => {
  clockTimer = window.setInterval(() => { now.value = new Date() }, 1000)
  tickTimer = window.setInterval(() => {
    countdown.value--
    if (countdown.value <= 0) load()
  }, 1000)
})
onUnmounted(() => {
  window.clearInterval(clockTimer)
  window.clearInterval(tickTimer)
  graphChart?.dispose()
})
</script>

<template>
  <div ref="rootEl" class="screen">
    <header class="head">
      <router-link to="/dashboard" class="back">← 返回工作台</router-link>
      <h1><span class="deco l" />团队协作屏<span class="deco r" /></h1>
      <ProjectChips v-model="projectId" dark class="chips" @change="load" />
      <div class="head-right">
        <span class="clock">{{ now.toLocaleTimeString('zh-CN', { hour12: false }) }}</span>
        <span class="refresh">{{ countdown }}s</span>
        <button class="fs" @click="toggleFullscreen">⛶</button>
      </div>
    </header>

    <template v-if="data">
      <!-- 迭代脉搏 -->
      <section class="panel pulse" v-if="data.sprint">
        <b class="sp-name">{{ data.sprint.name }}</b>
        <span class="sp-goal" v-if="data.sprint.goal">🎯 {{ data.sprint.goal }}</span>
        <div class="sp-progress">
          <div class="bar-bg"><div class="bar-fill" :class="{ danger: sprintDanger }" :style="{ width: data.sprint.timePct + '%' }" /></div>
          <span class="sp-txt">时间 {{ data.sprint.daysGone }}/{{ data.sprint.totalDays }} 天（{{ data.sprint.timePct }}%）</span>
        </div>
        <span class="sp-commit">承诺 {{ data.sprint.committedCount }} 件 {{ data.sprint.committedPoints }} 点 ·
          完成 <b :class="{ 'v-bad': sprintDanger }">{{ data.sprint.doneCount }} 件（{{ data.sprint.donePct ?? '—' }}%）</b></span>
        <span class="sp-cols">
          <span v-for="c in data.sprint.columns" :key="c.status" class="col-pill">
            {{ COL_LABEL[c.status] }} <b>{{ c.count }}</b>
          </span>
        </span>
      </section>
      <section class="panel pulse muted" v-else>当前项目没有进行中的迭代</section>

      <div class="main">
        <div class="left">
          <!-- 依赖网络 -->
          <section class="panel">
            <div class="p-title">上下游依赖网络（左=上游 → 右=下游）
              <span v-if="data.graph.truncated" class="p-note">节点较多，已截取 60 个</span>
              <span class="p-note">点击节点查看/操作详情 · 可拖拽缩放</span>
            </div>
            <div v-if="data.graph.edges.length || data.graph.nodes.length" ref="graphEl" class="graph" />
            <div v-else class="graph-empty">
              暂无工作项可展示。
            </div>
            <div v-if="!data.graph.edges.length && data.graph.nodes.length" class="graph-hint">
              还没有上下游关系——在工作项详情「关联」页签选择 <code>depends_on</code>（依赖）或 <code>blocks</code>（阻塞）建立依赖，这里将自动成图。
            </div>
            <div class="edge-legend">
              <span><i class="el-line dep" />依赖/阻塞</span>
              <span><i class="el-line parent" />分解(父子)</span>
              <span><i class="el-line affects" />缺陷影响</span>
              <span><i class="el-line changes" />变更波及</span>
              <span class="el-shape">▲缺陷 ◆变更 ■任务 ●需求/能力</span>
            </div>
          </section>

          <!-- 交接台 -->
          <section class="panel">
            <div class="p-title">交接台（近 7 天，下游可行动）</div>
            <div v-if="!data.handoffs.length" class="muted pad">近 7 天没有产生交接事项</div>
            <div class="handoffs">
              <div v-for="(h, i) in data.handoffs" :key="i" class="h-card" @click="openItem(h.downstreamId ?? h.itemId)">
                <div class="h-top">
                  <span class="h-kind" :style="{ background: KIND_META[h.kind]?.color }">{{ KIND_META[h.kind]?.label ?? h.kind }}</span>
                  <span class="h-time">{{ timeAgo(h.at) }}</span>
                </div>
                <div class="h-code">{{ h.itemCode }}<template v-if="h.downstreamCode"> → <b>{{ h.downstreamCode }}</b></template></div>
                <div class="h-action">{{ h.actionText }}</div>
              </div>
            </div>
          </section>
        </div>

        <aside class="right">
          <!-- 阻塞墙 -->
          <section class="panel b-panel">
            <div class="p-title">阻塞与风险 <span class="a-cnt">{{ data.blockers.length }}</span></div>
            <div v-if="!data.blockers.length" class="a-empty">当前无阻塞 ✓</div>
            <div v-for="(b, i) in data.blockers" :key="i" class="a-row" :class="{ link: b.itemId }"
              @click="openItem(b.itemId)">
              <span class="a-sev" :class="{ pulseAnim: b.severity === 'HIGH' }" :style="{ background: SEV_COLOR[b.severity] }">{{ b.severity }}</span>
              <div class="a-body">
                <div class="a-title">{{ b.title }}</div>
                <div class="a-detail">{{ b.detail }}</div>
              </div>
            </div>
          </section>

          <!-- 按负责人在办 -->
          <section class="panel">
            <div class="p-title">在办分派（Ready / 进行 / 验证）</div>
            <div v-for="o in data.owners" :key="o.ownerId ?? -1" class="owner">
              <div class="o-head">{{ o.ownerName }} <span class="o-sub">{{ o.items.length }} 件 · {{ o.points }} 点</span></div>
              <div v-for="it in o.items" :key="it.id" class="o-row" @click="openItem(it.id)">
                <span v-if="it.blocked" class="o-block">⛔</span>
                <span class="o-code">{{ it.code }}</span>
                <span class="o-title">{{ it.title }}</span>
                <span class="o-status">{{ it.status }}</span>
                <span v-if="it.stallDays > 7" class="o-stall">{{ it.stallDays }}天未动</span>
              </div>
            </div>
            <div v-if="!data.owners.length" class="muted pad">当前没有在办工作项</div>
          </section>
        </aside>
      </div>
    </template>

    <WorkItemDrawer v-model="drawerOpen" :item-id="drawerId" @changed="load" />
  </div>
</template>

<style scoped>
.screen { min-height: 100vh; background: radial-gradient(ellipse 90% 60% at 50% 0%, #17284a 0%, #0f1a2c 45%, #0b1424 100%); color: #e8eef8; padding: 14px 22px; box-sizing: border-box; font-size: 14px; }

.head { display: flex; align-items: center; gap: 16px; margin-bottom: 12px; flex-wrap: wrap; }
.head h1 { display: flex; align-items: center; gap: 14px; font-size: 22px; letter-spacing: 5px; margin: 0; color: #fff; text-shadow: 0 0 16px rgba(57, 135, 229, .45); }
.deco { display: inline-block; height: 2px; width: 70px; }
.deco.l { background: linear-gradient(90deg, transparent, #3987e5); }
.deco.r { background: linear-gradient(90deg, #3987e5, transparent); }
.back { color: #8fa2c0; text-decoration: none; font-size: 13px; }
.back:hover { color: #3987e5; }
.chips { flex: 1; }
.head-right { display: flex; align-items: center; gap: 10px; color: #8fa2c0; font-size: 13px; }
.clock { font-variant-numeric: tabular-nums; }
.refresh { color: #55688a; }
.fs { background: rgba(57,135,229,.12); color: #c9d4e8; border: 1px solid #2a3a55; border-radius: 6px; padding: 4px 10px; cursor: pointer; }
.fs:hover { border-color: #3987e5; }

.panel { background: linear-gradient(180deg, #18263f, #131e33); border: 1px solid rgba(90, 130, 190, .18); border-radius: 10px; padding: 10px 12px; box-shadow: 0 2px 12px rgba(0, 0, 0, .25); }
.p-title { font-size: 13px; color: #c9d4e8; margin-bottom: 8px; padding-left: 9px; position: relative; }
.p-title::before { content: ''; position: absolute; left: 0; top: 2px; bottom: 2px; width: 3px; border-radius: 2px; background: linear-gradient(180deg, #3987e5, #199e70); }
.p-note { float: right; color: #55688a; font-size: 12px; margin-left: 10px; }

/* 迭代脉搏 */
.pulse { display: flex; align-items: center; gap: 16px; margin-bottom: 12px; flex-wrap: wrap; }
.pulse.muted { color: #55688a; justify-content: center; }
.sp-name { color: #fff; }
.sp-goal { color: #8fa2c0; font-size: 13px; }
.sp-progress { display: flex; align-items: center; gap: 8px; flex: 1; min-width: 220px; }
.bar-bg { flex: 1; height: 8px; background: #0d1626; border-radius: 4px; overflow: hidden; }
.bar-fill { height: 100%; border-radius: 4px; background: #3987e5; transition: width .6s; }
.bar-fill.danger { background: linear-gradient(90deg, #e6a23c, #f56c6c); }
.sp-txt { font-size: 12px; color: #8fa2c0; white-space: nowrap; }
.sp-commit { font-size: 13px; color: #c9d4e8; }
.sp-cols { display: flex; gap: 6px; }
.col-pill { font-size: 12px; color: #8fa2c0; background: #0d1626; border-radius: 10px; padding: 2px 8px; }
.col-pill b { color: #e8eef8; }
.v-bad { color: #f56c6c !important; }

.main { display: grid; grid-template-columns: 5fr 2fr; gap: 12px; align-items: start; }
.left, .right { display: flex; flex-direction: column; gap: 12px; }

.graph { height: 52vh; }
.graph-empty { height: 20vh; display: flex; align-items: center; justify-content: center; color: #55688a; }
.graph-hint { color: #8fa2c0; font-size: 12px; padding: 6px 4px; }
.graph-hint code { color: #3987e5; background: #0d1626; padding: 1px 5px; border-radius: 4px; }
.edge-legend { display: flex; gap: 16px; font-size: 12px; color: #8fa2c0; padding: 6px 4px 0; border-top: 1px solid #1a2946; flex-wrap: wrap; }
.edge-legend span { display: inline-flex; align-items: center; gap: 5px; }
.el-line { display: inline-block; width: 22px; height: 0; border-top: 2px solid; }
.el-line.dep { border-color: #f56c6c; }
.el-line.parent { border-color: #55688a; border-top-style: dashed; }
.el-line.affects { border-color: #c98500; border-top-style: dashed; }
.el-line.changes { border-color: #c98500; border-top-style: dotted; border-top-width: 3px; }
.el-shape { color: #55688a; }

/* 交接台 */
.handoffs { display: flex; gap: 10px; overflow-x: auto; padding-bottom: 4px; }
.h-card { min-width: 190px; max-width: 240px; background: #0f1a2c; border: 1px solid #1e2f4e; border-radius: 8px; padding: 8px 10px; cursor: pointer; flex-shrink: 0; transition: border-color .15s; }
.h-card:hover { border-color: #3987e5; }
.h-top { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.h-kind { font-size: 11px; color: #0f1a2c; font-weight: 700; border-radius: 4px; padding: 1px 7px; }
.h-time { font-size: 11px; color: #55688a; }
.h-code { font-family: monospace; font-size: 12px; color: #8fa2c0; }
.h-code b { color: #e8eef8; }
.h-action { font-size: 12px; color: #c9d4e8; margin-top: 3px; }

/* 阻塞墙 */
.b-panel { max-height: 46vh; overflow-y: auto; }
.a-cnt { color: #f56c6c; }
.a-empty { color: #67c23a; text-align: center; padding: 22px 0; }
.a-row { display: flex; gap: 8px; padding: 7px 0; border-bottom: 1px solid #1a2946; align-items: flex-start; border-radius: 4px; }
.a-row:last-child { border-bottom: none; }
.a-row.link { cursor: pointer; }
.a-row.link:hover { background: rgba(57, 135, 229, .08); }
.a-sev { font-size: 11px; color: #0f1a2c; font-weight: 700; border-radius: 4px; padding: 1px 6px; flex-shrink: 0; }
.a-sev.pulseAnim { animation: pulse 2s ease-in-out infinite; }
@keyframes pulse { 0%, 100% { opacity: 1; } 50% { opacity: .55; } }
.a-body { min-width: 0; }
.a-title { font-size: 13px; color: #e8eef8; }
.a-detail { font-size: 12px; color: #8fa2c0; overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }

/* 在办分派 */
.owner { margin-bottom: 8px; }
.o-head { font-size: 13px; color: #fff; padding: 4px 0; border-bottom: 1px solid #1e2f4e; }
.o-sub { color: #55688a; font-size: 12px; margin-left: 6px; }
.o-row { display: flex; gap: 7px; align-items: baseline; padding: 5px 2px; font-size: 12px; cursor: pointer; border-radius: 4px; }
.o-row:hover { background: rgba(57, 135, 229, .08); }
.o-block { flex-shrink: 0; }
.o-code { font-family: monospace; color: #8fa2c0; flex-shrink: 0; }
.o-title { color: #c9d4e8; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.o-status { color: #55688a; flex-shrink: 0; }
.o-stall { color: #e6a23c; flex-shrink: 0; }
.muted { color: #55688a; }
.pad { padding: 12px 0; text-align: center; }
</style>
