<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import * as echarts from 'echarts'
import { metricsApi, type MetricsOverview, type TrendPoint } from '@/api/metrics'
import { alertApi, type Alert } from '@/api/perf'
import { type WorkItem } from '@/api/workitem'
import WorkItemDrawer from '@/components/WorkItemDrawer.vue'
import ProjectChips from '@/components/ProjectChips.vue'

const projectId = ref<number | null>(null)
const m = ref<MetricsOverview | null>(null)

const chartEl = ref<HTMLElement | null>(null)
let chart: echarts.ECharts | null = null

const trend = ref<TrendPoint[]>([])
const trendDays = ref(30)
const alerts = ref<Alert[]>([])
const alertsExpanded = ref(false)
const SEV_TAG: Record<string, string> = { HIGH: 'danger', MED: 'warning', LOW: 'info' }
const sevCount = (s: string) => alerts.value.filter((a) => a.severity === s).length
const shownAlerts = computed(() => (alertsExpanded.value ? alerts.value : alerts.value.slice(0, 5)))
function openAlert(a: Alert) {
  if (a.refType === 'WORK_ITEM') { currentId.value = a.refId; drawerVisible.value = true }
}
const defectTrendEl = ref<HTMLElement | null>(null)
const dcpTrendEl = ref<HTMLElement | null>(null)
let defectTrendChart: echarts.ECharts | null = null
let dcpTrendChart: echarts.ECharts | null = null

const drillVisible = ref(false)
const drillTitle = ref('')
const drillItems = ref<WorkItem[]>([])
const drawerVisible = ref(false)
const currentId = ref<number | null>(null)

const TYPE_LABEL: Record<string, string> = { CAPABILITY: '能力', REQUIREMENT: '需求', STORY: '故事', TASK: '任务', DEFECT: '缺陷', RISK: '风险', CHANGE: '变更' }

async function load() {
  if (!projectId.value) return
  ;[m.value, trend.value, alerts.value] = await Promise.all([
    metricsApi.overview(projectId.value),
    metricsApi.trend(projectId.value, trendDays.value),
    alertApi.list(projectId.value),
  ])
  await nextTick()
  renderChart()
  renderTrendCharts()
}

async function reloadTrend() {
  if (!projectId.value) return
  trend.value = await metricsApi.trend(projectId.value, trendDays.value)
  await nextTick()
  renderTrendCharts()
}

function renderChart() {
  if (!chartEl.value || !m.value) return
  if (!chart) chart = echarts.init(chartEl.value)
  const q = m.value.quality
  chart.setOption({
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [{
      name: '缺陷', type: 'pie', radius: ['45%', '70%'],
      data: [
        { value: q.defectClosed, name: '已关闭', itemStyle: { color: '#67c23a' } },
        { value: q.openDefects, name: '未关闭', itemStyle: { color: '#f56c6c' } },
      ],
      label: { formatter: '{b}: {c}' },
    }],
  })
}

function renderTrendCharts() {
  const t = trend.value
  if (!t.length) return
  const dates = t.map(p => p.date.slice(5))
  const pct = (a: number | null, b: number | null) =>
    a === null || b === null || !b ? null : Math.round((a / b) * 100)

  if (defectTrendEl.value) {
    if (!defectTrendChart) defectTrendChart = echarts.init(defectTrendEl.value)
    defectTrendChart.setOption({
      tooltip: { trigger: 'axis' },
      legend: { bottom: 0 },
      grid: { top: 24, left: 36, right: 36, bottom: 32 },
      xAxis: { type: 'category', data: dates },
      yAxis: [{ type: 'value', minInterval: 1 }],
      series: [
        { name: '流入', type: 'bar', data: t.map(p => p.defectInflow), itemStyle: { color: '#f56c6c' } },
        { name: '关闭', type: 'bar', data: t.map(p => p.defectClosed), itemStyle: { color: '#67c23a' } },
        { name: '未关存量', type: 'line', connectNulls: true, data: t.map(p => p.openDefects), itemStyle: { color: '#e6a23c' } },
      ],
    })
  }
  if (dcpTrendEl.value) {
    if (!dcpTrendChart) dcpTrendChart = echarts.init(dcpTrendEl.value)
    dcpTrendChart.setOption({
      tooltip: { trigger: 'axis', valueFormatter: (v: number | null) => (v === null ? '—' : `${v}%`) },
      legend: { bottom: 0 },
      grid: { top: 24, left: 40, right: 36, bottom: 32 },
      xAxis: { type: 'category', data: dates },
      yAxis: { type: 'value', min: 0, max: 100, axisLabel: { formatter: '{value}%' } },
      series: [
        { name: 'DCP条件满足率', type: 'line', connectNulls: true, data: t.map(p => pct(p.criteriaMet, p.criteriaTotal)), itemStyle: { color: '#409eff' } },
        { name: '需求验收率', type: 'line', connectNulls: true, data: t.map(p => pct(p.reqAccepted, p.reqTotal)), itemStyle: { color: '#67c23a' } },
      ],
    })
  }
}

async function drill(metric: string, title: string) {
  if (!projectId.value) return
  drillItems.value = await metricsApi.drilldown(projectId.value, metric)
  drillTitle.value = title
  drillVisible.value = true
}
function openItem(w: WorkItem) { currentId.value = w.id; drillVisible.value = false; drawerVisible.value = true }

</script>

<template>
  <div>
    <div class="toolbar">
      <ProjectChips v-model="projectId" class="chips-flex" @change="load" />
      <a v-if="projectId" :href="metricsApi.exportCsvUrl(projectId)" target="_blank">
        <el-button><el-icon><Download /></el-icon>导出工作项 CSV</el-button>
      </a>
    </div>

    <template v-if="m">
      <!-- 预警与待办：数据里"该办的事"主动聚合 -->
      <el-card v-if="alerts.length" shadow="never" class="alert-card">
        <template #header>
          <div class="rd-head">
            <b>⚠️ 预警与待办（{{ alerts.length }}）</b>
            <el-tag v-if="sevCount('HIGH')" type="danger" size="small">HIGH {{ sevCount('HIGH') }}</el-tag>
            <el-tag v-if="sevCount('MED')" type="warning" size="small">MED {{ sevCount('MED') }}</el-tag>
            <el-tag v-if="sevCount('LOW')" type="info" size="small">LOW {{ sevCount('LOW') }}</el-tag>
          </div>
        </template>
        <div v-for="(a, i) in shownAlerts" :key="i" class="alert-row"
          :class="{ link: a.refType === 'WORK_ITEM' }" @click="openAlert(a)">
          <el-tag :type="SEV_TAG[a.severity]" size="small" class="sev">{{ a.severity }}</el-tag>
          <b class="a-title">{{ a.title }}</b>
          <span class="a-detail">{{ a.detail }}</span>
          <router-link v-if="['GATE_CRITERION', 'DECISION'].includes(a.refType)" to="/dcp" class="a-go" @click.stop>去处理 →</router-link>
        </div>
        <el-button v-if="alerts.length > 5" link type="primary" size="small" @click="alertsExpanded = !alertsExpanded">
          {{ alertsExpanded ? '收起' : `展开全部 ${alerts.length} 条` }}
        </el-button>
      </el-card>

      <!-- 四组指标 -->
      <el-row :gutter="12" class="mrow">
        <el-col :span="6">
          <el-card shadow="never" class="grp">
            <template #header><b>价值与承诺</b></template>
            <div class="tiles">
              <div class="tile" @click="drill('capabilityAccepted', '已验收产品能力')">
                <div class="num">{{ m.value.capabilityAccepted }}/{{ m.value.capabilityTotal }}</div><div class="lbl">能力验收</div>
              </div>
              <div class="tile" @click="drill('requirementAccepted', '已验收需求')">
                <div class="num">{{ m.value.requirementAccepted }}/{{ m.value.requirementTotal }}</div><div class="lbl">需求验收</div>
              </div>
              <div class="tile" @click="drill('pendingChanges', '待审批变更')">
                <div class="num">{{ m.value.pendingChanges }}</div><div class="lbl">待决策变更</div>
              </div>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="grp">
            <template #header><b>交付流动</b></template>
            <div class="tiles">
              <div class="tile" @click="drill('wip', '进行中工作项')"><div class="num">{{ m.flow.wip }}</div><div class="lbl">WIP</div></div>
              <div class="tile" @click="drill('throughput', '已验收工作项')"><div class="num">{{ m.flow.throughput }}</div><div class="lbl">吞吐量</div></div>
              <div class="tile"><div class="num">{{ m.flow.cycleP50 }}/{{ m.flow.cycleP85 }}/{{ m.flow.cycleP95 }}</div><div class="lbl">Cycle P50/85/95(天)</div></div>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="grp">
            <template #header><b>工程质量</b></template>
            <div class="tiles">
              <div class="tile"><div class="num" :class="{ warn: m.quality.testPassRate < 100 }">{{ m.quality.testPassRate }}%</div><div class="lbl">测试通过率</div></div>
              <div class="tile" @click="drill('openDefects', '未关闭缺陷')"><div class="num" :class="{ warn: m.quality.openDefects }">{{ m.quality.openDefects }}</div><div class="lbl">未关缺陷</div></div>
              <div class="tile" @click="drill('uncoveredRequirements', '未覆盖需求')"><div class="num">{{ m.quality.reqCoverage }}%</div><div class="lbl">需求测试覆盖</div></div>
            </div>
          </el-card>
        </el-col>
        <el-col :span="6">
          <el-card shadow="never" class="grp">
            <template #header><b>缺陷关闭情况</b></template>
            <div ref="chartEl" class="chart"></div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 趋势（规划§7.4）：流入/关闭精确回算，存量类指标随每日快照累积 -->
      <el-row :gutter="12" class="mrow">
        <el-col :span="12">
          <el-card shadow="never">
            <template #header>
              <div class="rd-head">
                <b>缺陷流入 / 关闭趋势</b>
                <el-radio-group v-model="trendDays" size="small" style="margin-left:auto" @change="reloadTrend">
                  <el-radio-button :value="14">14天</el-radio-button>
                  <el-radio-button :value="30">30天</el-radio-button>
                  <el-radio-button :value="90">90天</el-radio-button>
                </el-radio-group>
              </div>
            </template>
            <div ref="defectTrendEl" class="trend-chart"></div>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card shadow="never">
            <template #header><b>DCP 条件满足 / 需求验收趋势</b></template>
            <div ref="dcpTrendEl" class="trend-chart"></div>
            <p class="hint" style="margin:4px 0 0">存量指标来自每日快照（打开驾驶舱即记录当天），历史随使用累积。</p>
          </el-card>
        </el-col>
      </el-row>

      <!-- 产品成熟度：跨职能准备度 -->
      <el-card v-if="m.maturity" shadow="never" class="readiness">
        <template #header>
          <div class="rd-head">
            <b>产品成熟度 · 跨职能准备度</b>
            <el-tag :type="m.maturity.overall.ready ? 'success' : 'danger'">{{ m.maturity.overall.ready ? '整机就绪' : '整机未就绪' }}</el-tag>
            <span class="req">需求验收 {{ m.maturity.overall.reqAccepted }}/{{ m.maturity.overall.reqTotal }}</span>
          </div>
        </template>
        <el-alert v-if="!m.maturity.overall.ready && m.maturity.overall.reqAccepted > 0" type="warning" :closable="false" show-icon class="mb">
          <b>局部完成但整机未就绪</b> —— {{ m.maturity.overall.reasons.join('；') }}
        </el-alert>
        <div class="domains">
          <div v-for="d in m.maturity.domains" :key="d.domain" class="dcard">
            <el-progress type="dashboard" :width="84" :percentage="d.total ? Math.round((d.met / d.total) * 100) : 0"
              :color="d.redlineUnmet.length ? '#f56c6c' : d.notReady ? '#e6a23c' : '#67c23a'" />
            <div class="dname">{{ d.domain }}<el-tag v-if="d.redlineUnmet.length" type="danger" size="small">红线</el-tag></div>
            <div class="ddetail">{{ d.met }}/{{ d.total }} 满足</div>
          </div>
        </div>
      </el-card>

      <p class="hint">指标全部由业务数据经 SQL 视图自动计算（无人工填报），点击带下划线的指标可下钻到原始工作项。不含任何个人绩效口径。</p>
    </template>

    <!-- 下钻 -->
    <el-dialog v-model="drillVisible" :title="`下钻：${drillTitle}（${drillItems.length}）`" width="620px">
      <el-table :data="drillItems" border size="small" @row-click="openItem" class="clickable">
        <el-table-column prop="code" label="编号" width="140" />
        <el-table-column label="类型" width="80"><template #default="{ row }">{{ TYPE_LABEL[row.type] }}</template></el-table-column>
        <el-table-column prop="title" label="标题" show-overflow-tooltip />
        <el-table-column prop="status" label="状态" width="120" />
      </el-table>
      <div v-if="!drillItems.length" class="empty">无数据</div>
    </el-dialog>

    <WorkItemDrawer v-model="drawerVisible" :item-id="currentId" @changed="load" />
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 12px; align-items: flex-start; margin-bottom: 16px; }
.chips-flex { flex: 1; }
.mrow { margin-bottom: 16px; }
.grp { height: 100%; }
.tiles { display: flex; flex-direction: column; gap: 10px; }
.tile { cursor: pointer; padding: 4px 6px; border-radius: 6px; }
.tile:hover { background: #f0f7ff; }
.num { font-size: 22px; font-weight: 700; color: #409eff; }
.num.warn { color: #e6a23c; }
.lbl { font-size: 12px; color: #909399; text-decoration: underline dotted; }
.chart { height: 180px; }
.trend-chart { height: 220px; }
.alert-card { margin-bottom: 16px; border-left: 3px solid #f56c6c; }
.alert-row { display: flex; align-items: baseline; gap: 8px; padding: 4px 2px; border-radius: 4px; font-size: 13px; }
.alert-row.link { cursor: pointer; }
.alert-row.link:hover { background: #fef0f0; }
.alert-row .sev { flex-shrink: 0; }
.a-title { flex-shrink: 0; }
.a-detail { color: #909399; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.a-go { margin-left: auto; flex-shrink: 0; color: #409eff; text-decoration: none; font-size: 12px; }
.readiness { margin-bottom: 16px; }
.rd-head { display: flex; align-items: center; gap: 12px; }
.rd-head .req { color: #909399; font-size: 13px; margin-left: auto; }
.domains { display: flex; gap: 16px; flex-wrap: wrap; justify-content: space-around; }
.dcard { text-align: center; }
.dname { font-weight: 600; margin-top: 4px; }
.ddetail { font-size: 12px; color: #909399; }
.mb { margin-bottom: 12px; }
.hint { color: #909399; font-size: 12px; }
.clickable :deep(.el-table__row) { cursor: pointer; }
.empty { color: #c0c4cc; text-align: center; padding: 12px; }
</style>
