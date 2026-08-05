<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '@/api/http'
import { dcpApi, type Overview } from '@/api/dcp'
import { baselineApi, type Diff } from '@/api/baseline'
import { stageApi, type StageGate } from '@/api/catalog'
import { readinessApi, type ReadinessSummary } from '@/api/readiness'
import { decisionApi, type Decision } from '@/api/governance'
import ReportSection from '@/components/report/ReportSection.vue'
import StaticChart from '@/components/report/StaticChart.vue'
import MarkdownView from '@/components/MarkdownView.vue'
import { decisionLabel } from '@/utils/labels'
import '@/assets/print.css'

interface Project { id: number; code: string; name: string }

const route = useRoute()
const gateId = Number(route.params.gateId)
const projectId = Number(route.query.projectId)
const loading = ref(true)

const project = ref<Project | null>(null)
const gate = ref<StageGate | null>(null)
const overview = ref<Overview | null>(null)
const baselineDiff = ref<Diff | null>(null)
const readiness = ref<ReadinessSummary | null>(null)
const decisions = ref<Decision[]>([])

const now = new Date().toLocaleString('zh-CN')

const CRITERION_STATUS_ZH: Record<string, string> = {
  MET: '已满足', PARTIAL: '部分满足', NOT_READY: '未就绪', WAIVED: '已豁免', PENDING: '待评估',
}
const cStatus = (s: string) => CRITERION_STATUS_ZH[s] ?? s

/** 本 gate 的决策链：真沿 prevDecisionId 回溯（链头=未被任何 prev 引用的最新者；孤儿降序兜底） */
const decisionChain = computed<Decision[]>(() => {
  const mine = decisions.value.filter((d) => d.subjectType === 'STAGE_GATE' && d.subjectId === gateId)
  if (!mine.length) return []
  const byId = new Map(mine.map((d) => [d.id, d]))
  const referenced = new Set(mine.map((d) => d.prevDecisionId).filter((x): x is number => x != null))
  const heads = mine.filter((d) => !referenced.has(d.id)).sort((a, b) => b.id - a.id)
  const chain: Decision[] = []
  const seen = new Set<number>()
  for (const head of heads) {
    let cur: Decision | undefined = head
    while (cur && !seen.has(cur.id)) {
      chain.push(cur)
      seen.add(cur.id)
      cur = cur.prevDecisionId != null ? byId.get(cur.prevDecisionId) : undefined
    }
  }
  // 孤儿（数据缺口）兜底追加
  for (const d of [...mine].sort((a, b) => b.id - a.id)) {
    if (!seen.has(d.id)) chain.push(d)
  }
  return chain
})
const latestDecision = computed(() => decisionChain.value[0] ?? null)

/** 五领域满足率柱图 */
const domainOption = computed(() => {
  const domains = readiness.value?.domains ?? []
  return {
    tooltip: { trigger: 'axis' as const },
    grid: { left: 44, right: 20, top: 26, bottom: 24 },
    xAxis: { type: 'category' as const, data: domains.map((d) => d.domain) },
    yAxis: { type: 'value' as const, max: 100, axisLabel: { formatter: '{value}%' } },
    series: [{
      type: 'bar' as const,
      barWidth: 42,
      data: domains.map((d) => ({
        value: d.metPercent,
        itemStyle: { color: d.redlineUnmet.length ? '#f56c6c' : '#409eff' },
      })),
      label: { show: true, position: 'top' as const, formatter: '{c}%' },
    }],
  }
})

const snapshot = computed(() => overview.value?.snapshot ?? null)
const criteria = computed(() => overview.value?.criteria ?? [])
const redlines = computed(() => criteria.value.filter((c) => c.isRedline))

function doPrint() {
  window.print()
}

async function load() {
  try {
    const [ov, gates, rs, ds, p] = await Promise.all([
      dcpApi.overview(gateId),
      stageApi.list(projectId),
      readinessApi.summary(projectId),
      decisionApi.list(projectId),
      http.get<any, Project>(`/projects/${projectId}`),
    ])
    overview.value = ov
    gate.value = gates.find((g) => g.id === gateId) ?? null
    readiness.value = rs
    decisions.value = ds
    project.value = p
    // 范围对比：取项目最新基线的 diff（无基线则该章节提示未建）
    try {
      const bls = await baselineApi.list(projectId)
      if (bls.length) baselineDiff.value = await baselineApi.diff(bls[0].id)
    } catch { /* 基线读取失败不阻塞报告 */ }
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

    <div class="report-page" v-if="project && gate && snapshot">
      <header class="rp-head">
        <h1>{{ project.name }} · DCP 决策包</h1>
        <div class="rp-meta">
          <span>{{ gate.stageName }} / {{ gate.gateName }}（{{ gate.code }}）</span>
          <span v-if="gate.planDate">计划评审日：{{ gate.planDate }}</span>
          <span>生成时间：{{ now }}</span>
        </div>
        <div v-if="latestDecision" class="rp-verdict" :class="latestDecision.conclusion.toLowerCase()">
          最新决策：{{ latestDecision.code }} · {{ decisionLabel(latestDecision.conclusion) }} · {{ latestDecision.decidedAt }}
        </div>
        <div v-else class="rp-verdict pending">尚未评审</div>
      </header>

      <ReportSection title="一、准备度总览">
        <div class="kpi-row">
          <div class="kpi danger" v-if="snapshot.redlineUnmet.length"><b>{{ snapshot.redlineUnmet.length }}</b><span>红线未满足</span></div>
          <div class="kpi ok" v-else><b>0</b><span>红线未满足</span></div>
          <div class="kpi"><b>{{ snapshot.evidenceMissing.length }}</b><span>证据缺失</span></div>
          <div class="kpi"><b>{{ snapshot.ownerMissing.length }}</b><span>无责任人</span></div>
          <div class="kpi"><b>{{ snapshot.pending }}</b><span>待评估</span></div>
        </div>
        <table class="rp-table">
          <thead><tr><th>领域</th><th>总数</th><th>已满足</th><th>部分</th><th>未就绪</th><th>豁免</th></tr></thead>
          <tbody>
            <tr v-for="(s, d) in snapshot.byDomain" :key="d">
              <td>{{ d }}</td><td>{{ s.total }}</td><td>{{ s.met }}</td>
              <td>{{ s.partial }}</td><td>{{ s.notReady }}</td><td>{{ s.waived }}</td>
            </tr>
          </tbody>
        </table>
      </ReportSection>

      <ReportSection title="二、五领域跨职能准备度">
        <StaticChart :option="domainOption" :height="260" />
        <p v-if="readiness && !readiness.overall.ready" class="rp-warn">
          ⚠ 整机未就绪：{{ readiness.overall.reasons.join('；') }}
        </p>
      </ReportSection>

      <ReportSection title="三、红线清单" page-break>
        <table class="rp-table" v-if="redlines.length">
          <thead><tr><th style="width:110px">编号</th><th style="width:70px">领域</th><th>条件</th><th style="width:90px">状态</th><th style="width:70px">证据数</th></tr></thead>
          <tbody>
            <tr v-for="c in redlines" :key="c.id" :class="{ 'row-danger': !['MET', 'WAIVED'].includes(c.status) }">
              <td class="mono">{{ c.code }}</td>
              <td>{{ c.domain }}</td>
              <td>{{ c.criterion }}</td>
              <td>{{ cStatus(c.status) }}</td>
              <td>{{ c.evidenceCount }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="rp-empty">本 DCP 无红线条件。</p>
      </ReportSection>

      <ReportSection title="四、准入条件与证据">
        <table class="rp-table">
          <thead><tr><th style="width:110px">编号</th><th style="width:70px">领域</th><th>条件</th><th style="width:60px">红线</th><th style="width:90px">状态</th><th style="width:70px">证据数</th></tr></thead>
          <tbody>
            <tr v-for="c in criteria" :key="c.id">
              <td class="mono">{{ c.code }}</td>
              <td>{{ c.domain }}</td>
              <td>{{ c.criterion }}</td>
              <td>{{ c.isRedline ? '●' : '' }}</td>
              <td>{{ cStatus(c.status) }}</td>
              <td>{{ c.evidenceCount }}</td>
            </tr>
          </tbody>
        </table>
      </ReportSection>

      <ReportSection title="五、范围对比（相对最新基线）">
        <template v-if="baselineDiff">
          <p class="bl-line">
            对比基线 <b>{{ baselineDiff.baseline.name }}</b>（{{ baselineDiff.baseline.createdAt.slice(0, 10) }} 固化，{{ baselineDiff.summary.baselineCount }} 项）：
            基线外新增 <b :class="{ warn: baselineDiff.summary.added }">{{ baselineDiff.summary.added }}</b> 项（蔓延率 {{ baselineDiff.summary.creepRate }}%）、
            移除 {{ baselineDiff.summary.removed }} 项、已完成 {{ baselineDiff.summary.done }} 项、
            平均日期偏差 {{ baselineDiff.summary.avgSlipDays ?? '—' }} 天、估算漂移 {{ baselineDiff.summary.estimateDeltaTotal > 0 ? '+' : '' }}{{ baselineDiff.summary.estimateDeltaTotal }}
          </p>
          <table class="rp-table" v-if="baselineDiff.rows.some(r => r.kind === 'ADDED' || (r.slipDays ?? 0) > 0)">
            <thead><tr><th style="width:130px">编号</th><th>标题</th><th style="width:110px">对比结论</th><th style="width:100px">日期偏差</th></tr></thead>
            <tbody>
              <tr v-for="r in baselineDiff.rows.filter(r => r.kind === 'ADDED' || (r.slipDays ?? 0) > 0)" :key="r.workItemId">
                <td class="mono">{{ r.code }}</td>
                <td>{{ r.title }}</td>
                <td>{{ r.kind === 'ADDED' ? '基线外新增' : '日期拖期' }}</td>
                <td>{{ r.slipDays != null ? `+${r.slipDays} 天` : '—' }}</td>
              </tr>
            </tbody>
          </table>
          <p v-else class="rp-empty">范围与日期均在基线承诺内。</p>
        </template>
        <p v-else class="rp-empty">尚未建立基线（DCP 评审通过时自动固化，或在「基线管理」页手动建立）。</p>
      </ReportSection>

      <ReportSection title="六、决策记录链（只增不改）" page-break>
        <table class="rp-table" v-if="decisionChain.length">
          <thead><tr><th style="width:110px">编号</th><th style="width:110px">结论</th><th>理由</th><th style="width:120px">遗留承诺</th><th style="width:150px">时间</th></tr></thead>
          <tbody>
            <tr v-for="d in decisionChain" :key="d.id">
              <td class="mono">{{ d.code }}<span v-if="d.prevDecisionId" class="rev">（修订）</span></td>
              <td>{{ decisionLabel(d.conclusion) }}</td>
              <td class="reason-col"><MarkdownView v-if="d.reason" :source="d.reason" /></td>
              <td>{{ d.commitmentDue ? `期限 ${d.commitmentDue}` : '—' }}</td>
              <td>{{ d.decidedAt }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="rp-empty">本 DCP 尚无决策记录。</p>
      </ReportSection>

      <footer class="rp-foot">IPD 敏捷数字化工具箱 · 决策快照均固化可复现 · 本报告由业务数据自动生成</footer>
    </div>
  </div>
</template>

<style scoped>
.report-wrap { min-height: 100vh; background: #f0f2f5; padding: 20px 0 60px; }
.toolbar { max-width: 830px; margin: 0 auto 14px; display: flex; align-items: center; gap: 12px; }
.tip { color: #909399; font-size: 12px; }
.report-page { max-width: 830px; margin: 0 auto; background: #fff; box-shadow: 0 2px 12px rgba(0,0,0,.08); padding: 34px 40px; }

.rp-head h1 { font-size: 22px; margin: 0 0 8px; color: #1f2d3d; }
.rp-meta { display: flex; gap: 20px; color: #909399; font-size: 13px; flex-wrap: wrap; }
.rp-head { border-bottom: 3px double #409eff; padding-bottom: 14px; margin-bottom: 22px; }
.rp-verdict { margin-top: 10px; padding: 8px 14px; border-radius: 6px; font-size: 14px; font-weight: 600; }
.rp-verdict.pass { background: #f0f9eb; color: #67c23a; }
.rp-verdict.conditional { background: #fdf6ec; color: #e6a23c; }
.rp-verdict.reject { background: #fef0f0; color: #f56c6c; }
.rp-verdict.pending { background: #f4f4f5; color: #909399; }

.kpi-row { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; margin-bottom: 12px; }
.kpi { text-align: center; border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 4px; }
.kpi b { display: block; font-size: 20px; color: #409eff; }
.kpi.danger b { color: #f56c6c; }
.kpi.ok b { color: #67c23a; }
.kpi span { font-size: 12px; color: #909399; }

.rp-table { width: 100%; border-collapse: collapse; font-size: 13px; margin-top: 10px; }
.rp-table th, .rp-table td { border: 1px solid #ebeef5; padding: 6px 10px; text-align: left; vertical-align: top; }
.rp-table th { background: #f5f7fa; color: #606266; }
.row-danger td { background: #fef0f0; }
.reason-col { min-width: 220px; }
.mono { font-family: monospace; }
.rev { color: #e6a23c; font-size: 12px; }
.rp-warn { color: #e6a23c; font-size: 13px; }
.bl-line { font-size: 13px; color: #606266; line-height: 1.8; }
.bl-line .warn { color: #f56c6c; }
.rp-empty { color: #909399; font-size: 13px; }
.rp-foot { margin-top: 26px; text-align: center; color: #c0c4cc; font-size: 12px; }
</style>
