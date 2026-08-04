<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '@/api/http'
import { metricsApi, type MetricsOverview, type TrendPoint } from '@/api/metrics'
import { stageApi, versionApi, type StageGate, type ProductVersion } from '@/api/catalog'
import { decisionApi, type Decision } from '@/api/governance'
import { alertApi, type Alert } from '@/api/perf'
import { iterationApi, type Iteration } from '@/api/agile'
import ReportSection from '@/components/report/ReportSection.vue'
import StaticChart from '@/components/report/StaticChart.vue'
import RoadmapTimeline, { type TimelineLane } from '@/components/RoadmapTimeline.vue'
import MarkdownView from '@/components/MarkdownView.vue'
import { decisionLabel, decisionTypeLabel } from '@/utils/labels'
import '@/assets/print.css'

interface Project { id: number; code: string; name: string; goal: string; lifecycleStatus: string }

const route = useRoute()
const projectId = Number(route.params.projectId)
const loading = ref(true)

const project = ref<Project | null>(null)
const m = ref<MetricsOverview | null>(null)
const trend = ref<TrendPoint[]>([])
const gates = ref<StageGate[]>([])
const versions = ref<ProductVersion[]>([])
const iterations = ref<Iteration[]>([])
const decisions = ref<Decision[]>([])
const alerts = ref<Alert[]>([])

const now = new Date().toLocaleString('zh-CN')
const highAlerts = computed(() => alerts.value.filter((a) => a.severity === 'HIGH'))
const recentDecisions = computed(() => [...decisions.value].sort((a, b) => b.id - a.id).slice(0, 8))

// 里程碑时间轴（复用路标图组件）
const lanes = computed<TimelineLane[]>(() => {
  const latestByGate = new Map<number, Decision>()
  for (const d of [...decisions.value].sort((a, b) => a.id - b.id)) {
    if (d.subjectType === 'STAGE_GATE' && d.subjectId != null) latestByGate.set(d.subjectId, d)
  }
  const today = new Date(new Date().toDateString())
  return [
    {
      label: '产品版本',
      items: versions.value.flatMap((v) => {
        const date = v.actualReleaseDate ?? v.planReleaseDate
        if (!date) return []
        const done = !!v.actualReleaseDate
        return [{ type: 'point' as const, shape: 'diamond' as const, date, label: v.versionNo, status: done ? 'done' as const : (new Date(date) < today ? 'late' as const : 'plan' as const) }]
      }),
    },
    {
      label: '阶段 / DCP',
      items: gates.value.flatMap((g) => {
        const d = latestByGate.get(g.id)
        const passed = d && ['PASS', 'CONDITIONAL'].includes(d.conclusion)
        const date = passed ? (d!.decidedAt?.slice(0, 10) || g.planDate) : g.planDate
        if (!date) return []
        return [{ type: 'point' as const, shape: 'flag' as const, date, label: `${g.stageName}/${g.gateName}`, status: passed ? 'done' as const : (new Date(date) < today ? 'late' as const : 'plan' as const) }]
      }),
    },
    {
      label: '迭代',
      items: iterations.value.filter((i) => i.hidden !== 1 && i.startDate && i.endDate).map((i) => ({
        type: 'bar' as const, start: i.startDate, end: i.endDate, label: i.name,
        status: i.status === 'ACTIVE' ? 'active' as const : ['DONE', 'CLOSED'].includes(i.status) ? 'done' as const : 'plan' as const,
      })),
    },
  ]
})

// 缺陷流入/关闭趋势图 option
const defectTrendOption = computed(() => ({
  tooltip: { trigger: 'axis' as const },
  legend: { data: ['流入', '关闭'] },
  grid: { left: 40, right: 20, top: 34, bottom: 24 },
  xAxis: { type: 'category' as const, data: trend.value.map((t) => t.date.slice(5)) },
  yAxis: { type: 'value' as const, minInterval: 1 },
  series: [
    { name: '流入', type: 'bar' as const, data: trend.value.map((t) => t.defectInflow), itemStyle: { color: '#f56c6c' } },
    { name: '关闭', type: 'bar' as const, data: trend.value.map((t) => t.defectClosed), itemStyle: { color: '#67c23a' } },
  ],
}))

const gateStatus = (g: StageGate) => {
  const ds = decisions.value.filter((d) => d.subjectType === 'STAGE_GATE' && d.subjectId === g.id).sort((a, b) => b.id - a.id)
  return ds.length ? decisionLabel(ds[0].conclusion) : (g.planDate ? `计划 ${g.planDate}` : '未评审')
}

function doPrint() {
  window.print()
}

async function load() {
  try {
    ;[project.value, m.value, trend.value, gates.value, versions.value, iterations.value, decisions.value, alerts.value] =
      await Promise.all([
        http.get<any, Project>(`/projects/${projectId}`),
        metricsApi.overview(projectId),
        metricsApi.trend(projectId, 30),
        stageApi.list(projectId),
        versionApi.list(projectId),
        iterationApi.list(projectId),
        decisionApi.list(projectId),
        alertApi.list(projectId),
      ])
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <div class="report-wrap" v-loading="loading">
    <div class="no-print toolbar">
      <el-button type="primary" @click="doPrint">🖨 打印 / 存为 PDF</el-button>
      <span class="tip">建议打印设置：A4、纵向、开启背景图形</span>
    </div>

    <div class="report-page" v-if="project && m">
      <header class="rp-head">
        <h1>{{ project.name }} · 项目状态报告</h1>
        <div class="rp-meta">
          <span>项目编号：{{ project.code }}</span>
          <span>生成时间：{{ now }}</span>
        </div>
        <p v-if="project.goal" class="rp-goal">商业目标：{{ project.goal }}</p>
      </header>

      <ReportSection title="一、概况指标">
        <div class="kpi-row">
          <div class="kpi"><b>{{ m.value.requirementAccepted }}/{{ m.value.requirementTotal }}</b><span>需求已验收</span></div>
          <div class="kpi"><b>{{ m.quality.reqCoverage }}%</b><span>需求测试覆盖</span></div>
          <div class="kpi"><b>{{ m.quality.testPassRate }}%</b><span>测试通过率</span></div>
          <div class="kpi"><b>{{ m.quality.openDefects }}</b><span>未关缺陷</span></div>
          <div class="kpi"><b>{{ m.flow.wip }}</b><span>在制品 WIP</span></div>
          <div class="kpi"><b>{{ m.flow.cycleP85 }}d</b><span>周期 P85</span></div>
        </div>
      </ReportSection>

      <ReportSection title="二、里程碑与路标">
        <RoadmapTimeline :lanes="lanes" />
        <table class="rp-table">
          <thead><tr><th>阶段 / DCP</th><th>计划评审日</th><th>状态</th></tr></thead>
          <tbody>
            <tr v-for="g in gates" :key="g.id">
              <td>{{ g.stageName }} / {{ g.gateName }}</td>
              <td>{{ g.planDate ?? '—' }}</td>
              <td>{{ gateStatus(g) }}</td>
            </tr>
          </tbody>
        </table>
      </ReportSection>

      <ReportSection title="三、质量趋势（近 30 天缺陷流入/关闭）">
        <StaticChart :option="defectTrendOption" :height="280" />
      </ReportSection>

      <ReportSection title="四、TOP 风险与预警" page-break>
        <table class="rp-table" v-if="highAlerts.length">
          <thead><tr><th style="width:110px">编号</th><th style="width:150px">预警</th><th>说明</th><th style="width:100px">期限</th></tr></thead>
          <tbody>
            <tr v-for="(a, i) in highAlerts" :key="i">
              <td class="mono">{{ a.refCode }}</td>
              <td>{{ a.title }}</td>
              <td>{{ a.detail }}</td>
              <td>{{ a.due ?? '—' }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="rp-empty">当前无 HIGH 级预警。</p>
      </ReportSection>

      <ReportSection title="五、近期决策">
        <table class="rp-table" v-if="recentDecisions.length">
          <thead><tr><th style="width:110px">编号</th><th style="width:90px">类型</th><th style="width:110px">结论</th><th>理由</th><th style="width:150px">时间</th></tr></thead>
          <tbody>
            <tr v-for="d in recentDecisions" :key="d.id">
              <td class="mono">{{ d.code }}</td>
              <td>{{ decisionTypeLabel(d.decisionType) }}</td>
              <td>{{ decisionLabel(d.conclusion) }}</td>
              <td class="reason-col"><MarkdownView v-if="d.reason" :source="d.reason" /></td>
              <td>{{ d.decidedAt }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="rp-empty">暂无决策记录。</p>
      </ReportSection>

      <footer class="rp-foot">IPD 敏捷数字化工具箱 · 本报告由业务数据自动生成，无人工填报</footer>
    </div>
  </div>
</template>

<style scoped>
.report-wrap { min-height: 100vh; background: #f0f2f5; padding: 20px 0 60px; }
.toolbar { max-width: 830px; margin: 0 auto 14px; display: flex; align-items: center; gap: 12px; }
.tip { color: #909399; font-size: 12px; }
.report-page { max-width: 830px; margin: 0 auto; background: #fff; box-shadow: 0 2px 12px rgba(0,0,0,.08); padding: 34px 40px; }

.rp-head h1 { font-size: 22px; margin: 0 0 8px; color: #1f2d3d; }
.rp-meta { display: flex; gap: 20px; color: #909399; font-size: 13px; }
.rp-goal { font-size: 13px; color: #606266; margin: 8px 0 0; }
.rp-head { border-bottom: 3px double #409eff; padding-bottom: 14px; margin-bottom: 22px; }

.kpi-row { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; }
.kpi { text-align: center; border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 4px; }
.kpi b { display: block; font-size: 20px; color: #409eff; }
.kpi span { font-size: 12px; color: #909399; }

.rp-table { width: 100%; border-collapse: collapse; font-size: 13px; margin-top: 10px; }
.rp-table th, .rp-table td { border: 1px solid #ebeef5; padding: 6px 10px; text-align: left; vertical-align: top; }
.rp-table th { background: #f5f7fa; color: #606266; }
.reason-col { min-width: 220px; }
.mono { font-family: monospace; }
.rp-empty { color: #909399; font-size: 13px; }
.rp-foot { margin-top: 26px; text-align: center; color: #c0c4cc; font-size: 12px; }
</style>
