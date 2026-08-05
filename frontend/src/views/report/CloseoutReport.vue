<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '@/api/http'
import { stageApi, type StageGate } from '@/api/catalog'
import { decisionApi, type Decision } from '@/api/governance'
import { workItemApi, type WorkItem } from '@/api/workitem'
import { perfApi, type PerfOverview } from '@/api/perf'
import { baselineApi, type Diff } from '@/api/baseline'
import { assetsApi, LESSON_CATEGORY, type Lesson } from '@/api/assets'
import ReportSection from '@/components/report/ReportSection.vue'
import { decisionLabel, statusLabel } from '@/utils/labels'
import '@/assets/print.css'

/** 结项报告：全程回顾——里程碑达成、决策链回放（含各时点快照摘要）、风险闭环、
 *  范围蔓延（相对最新基线）、关键指标终值、经验教训。 */
interface Project { id: number; code: string; name: string; goal?: string; lifecycleStatus: string }
interface CloseoutCheck { openRisks: number; unreviewedGates: number; openDefects: number; pendingChanges: number; unmetRedlines: number; clean: boolean }

const route = useRoute()
const projectId = Number(route.params.projectId)
const loading = ref(true)
const now = new Date().toLocaleString('zh-CN')

const project = ref<Project | null>(null)
const check = ref<CloseoutCheck | null>(null)
const gates = ref<StageGate[]>([])
const decisions = ref<Decision[]>([])
const risks = ref<WorkItem[]>([])
const perf = ref<PerfOverview | null>(null)
const baselineDiff = ref<Diff | null>(null)
const lessons = ref<Lesson[]>([])

function doPrint() {
  window.print()
}

/** 每个 gate 的决策链（新→旧沿 prev 回溯） */
function gateChain(gateId: number): Decision[] {
  const mine = decisions.value.filter((d) => d.subjectType === 'STAGE_GATE' && d.subjectId === gateId)
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
  return chain
}

function snapSummary(d: Decision): string {
  try {
    const s = d.snapshot ? JSON.parse(d.snapshot) : null
    if (!s) return ''
    return `红线未满足 ${s.redlineUnmet?.length ?? 0} · 证据缺失 ${s.evidenceMissing?.length ?? 0} · 待评估 ${s.pending ?? 0}`
  } catch {
    return ''
  }
}

const riskStats = computed(() => {
  const total = risks.value.length
  const closed = risks.value.filter((r) => r.status === 'Closed').length
  const accepted = risks.value.filter((r) => r.status === 'Accepted').length
  return { total, closed, accepted, open: total - closed - accepted }
})

/** 指标终值展平（取二级指标） */
const metricRows = computed(() => {
  const out: { name: string; value: string }[] = []
  for (const g of perf.value?.groups ?? []) {
    for (const m of g.metrics) {
      if (m.level === 2 && m.value != null) out.push({ name: m.name, value: `${m.value}${m.unit}` })
    }
  }
  return out
})

onMounted(async () => {
  try {
    const [p, c, g, d, r, pf, ls] = await Promise.all([
      http.get<any, Project>(`/projects/${projectId}`),
      http.get<any, CloseoutCheck>(`/projects/${projectId}/closeout-check`),
      stageApi.list(projectId),
      decisionApi.list(projectId),
      workItemApi.list(projectId, 'RISK'),
      perfApi.metrics(projectId),
      assetsApi.lessons({ projectId }),
    ])
    project.value = p
    check.value = c
    gates.value = g
    decisions.value = d
    risks.value = r
    perf.value = pf
    lessons.value = ls
    try {
      const bls = await baselineApi.list(projectId)
      if (bls.length) baselineDiff.value = await baselineApi.diff(bls[0].id)
    } catch { /* 无基线不阻塞 */ }
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="report-wrap" v-loading="loading">
    <div class="no-print toolbar">
      <el-button type="primary" @click="doPrint">🖨 打印 / 存为 PDF</el-button>
    </div>

    <div class="report-page" v-if="project && check">
      <header class="rp-head">
        <h1>{{ project.name }} · 项目结项报告</h1>
        <div class="rp-meta">
          <span>项目编号：{{ project.code }}</span>
          <span>当前状态：{{ project.lifecycleStatus === 'CLOSED' ? '已结项' : '结项预检' }}</span>
          <span>生成时间：{{ now }}</span>
        </div>
        <p v-if="project.goal" class="rp-goal">商业目标：{{ project.goal }}</p>
      </header>

      <ReportSection title="一、结项检查">
        <div class="kpi-row">
          <div class="kpi" :class="{ danger: check.openRisks }"><b>{{ check.openRisks }}</b><span>未闭合风险</span></div>
          <div class="kpi" :class="{ danger: check.unreviewedGates }"><b>{{ check.unreviewedGates }}</b><span>未评审 DCP</span></div>
          <div class="kpi" :class="{ danger: check.openDefects }"><b>{{ check.openDefects }}</b><span>未关缺陷</span></div>
          <div class="kpi" :class="{ danger: check.pendingChanges }"><b>{{ check.pendingChanges }}</b><span>在途变更</span></div>
          <div class="kpi" :class="{ danger: check.unmetRedlines }"><b>{{ check.unmetRedlines }}</b><span>红线未满足</span></div>
        </div>
        <p class="hint-line">{{ check.clean ? '✓ 各项已清零，可干净结项。' : '⚠ 存在未了事项——结项即接受以上遗留，请在下方决策链与风险部分确认妥善交代。' }}</p>
      </ReportSection>

      <ReportSection title="二、里程碑达成（计划 vs 实际）">
        <table class="rp-table">
          <thead><tr><th>阶段 / DCP</th><th style="width:110px">计划评审日</th><th style="width:110px">实际决策日</th><th style="width:110px">最终结论</th></tr></thead>
          <tbody>
            <tr v-for="g in gates" :key="g.id">
              <td>{{ g.stageName }} / {{ g.gateName }}</td>
              <td>{{ g.planDate ?? '—' }}</td>
              <td>{{ gateChain(g.id)[0]?.decidedAt?.slice(0, 10) ?? '未评审' }}</td>
              <td>{{ gateChain(g.id)[0] ? decisionLabel(gateChain(g.id)[0].conclusion) : '—' }}</td>
            </tr>
          </tbody>
        </table>
      </ReportSection>

      <ReportSection title="三、决策链全回放（只增不改，新→旧）" page-break>
        <template v-for="g in gates" :key="g.id">
          <div v-if="gateChain(g.id).length" class="chain-gate">
            <h4>{{ g.stageName }} / {{ g.gateName }}</h4>
            <table class="rp-table">
              <thead><tr><th style="width:110px">编号</th><th style="width:100px">结论</th><th>理由</th><th style="width:200px">当时快照</th><th style="width:110px">时间</th></tr></thead>
              <tbody>
                <tr v-for="d in gateChain(g.id)" :key="d.id">
                  <td class="mono">{{ d.code }}<span v-if="d.prevDecisionId" class="rev">（修订）</span></td>
                  <td>{{ decisionLabel(d.conclusion) }}</td>
                  <td>{{ d.reason }}</td>
                  <td class="snap">{{ snapSummary(d) }}</td>
                  <td>{{ d.decidedAt.slice(0, 10) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </template>
        <p v-if="!decisions.some(d => d.subjectType === 'STAGE_GATE')" class="rp-empty">无 DCP 决策记录。</p>
      </ReportSection>

      <ReportSection title="四、风险闭环">
        <div class="kpi-row four">
          <div class="kpi"><b>{{ riskStats.total }}</b><span>风险总数</span></div>
          <div class="kpi ok"><b>{{ riskStats.closed }}</b><span>已关闭</span></div>
          <div class="kpi"><b>{{ riskStats.accepted }}</b><span>已接受</span></div>
          <div class="kpi" :class="{ danger: riskStats.open }"><b>{{ riskStats.open }}</b><span>遗留开放</span></div>
        </div>
        <table class="rp-table" v-if="riskStats.open">
          <thead><tr><th style="width:130px">编号</th><th>遗留风险</th><th style="width:100px">状态</th></tr></thead>
          <tbody>
            <tr v-for="r in risks.filter(x => !['Closed', 'Accepted'].includes(x.status))" :key="r.id">
              <td class="mono">{{ r.code }}</td>
              <td>{{ r.title }}</td>
              <td>{{ statusLabel(r.status, 'RISK') }}</td>
            </tr>
          </tbody>
        </table>
      </ReportSection>

      <ReportSection title="五、范围蔓延（相对最新基线）">
        <template v-if="baselineDiff">
          <p class="hint-line">
            基线 {{ baselineDiff.baseline.name }}（{{ baselineDiff.summary.baselineCount }} 项）→
            蔓延 {{ baselineDiff.summary.added }} 项（{{ baselineDiff.summary.creepRate }}%）、
            移除 {{ baselineDiff.summary.removed }}、完成 {{ baselineDiff.summary.done }}、
            平均日期偏差 {{ baselineDiff.summary.avgSlipDays ?? '—' }} 天、估算漂移 {{ baselineDiff.summary.estimateDeltaTotal }}
          </p>
        </template>
        <p v-else class="rp-empty">项目未建立基线，无法量化范围蔓延。</p>
      </ReportSection>

      <ReportSection title="六、关键指标终值" page-break>
        <table class="rp-table">
          <tbody>
            <tr v-for="(m, i) in metricRows" :key="i" v-show="i % 2 === 0">
              <td style="width:38%">{{ metricRows[i]?.name }}</td>
              <td style="width:12%"><b>{{ metricRows[i]?.value }}</b></td>
              <td style="width:38%">{{ metricRows[i + 1]?.name ?? '' }}</td>
              <td style="width:12%"><b>{{ metricRows[i + 1]?.value ?? '' }}</b></td>
            </tr>
          </tbody>
        </table>
      </ReportSection>

      <ReportSection title="七、经验教训">
        <table class="rp-table" v-if="lessons.length">
          <thead><tr><th style="width:90px">类别</th><th style="width:230px">结论</th><th>说明</th></tr></thead>
          <tbody>
            <tr v-for="l in lessons" :key="l.id">
              <td>{{ LESSON_CATEGORY[l.category] ?? l.category }}</td>
              <td>{{ l.title }}</td>
              <td>{{ l.detail }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="rp-empty">尚无经验教训——在「组织资产」或迭代复盘页登记后此处自动汇入。</p>
        <div class="blank-box">补充（打印后手填）：</div>
      </ReportSection>

      <footer class="rp-foot">IPD 敏捷数字化工具箱 · 结项报告由全程业务数据自动生成，决策快照均可复现</footer>
    </div>
  </div>
</template>

<style scoped>
.report-wrap { min-height: 100vh; background: #f0f2f5; padding: 20px 0 60px; }
.toolbar { max-width: 830px; margin: 0 auto 14px; }
.report-page { max-width: 830px; margin: 0 auto; background: #fff; box-shadow: 0 2px 12px rgba(0,0,0,.08); padding: 34px 40px; }
.rp-head h1 { font-size: 22px; margin: 0 0 8px; color: #1f2d3d; }
.rp-meta { display: flex; gap: 20px; color: #909399; font-size: 13px; flex-wrap: wrap; }
.rp-goal { font-size: 13px; color: #606266; margin: 8px 0 0; }
.rp-head { border-bottom: 3px double #409eff; padding-bottom: 14px; margin-bottom: 22px; }
.kpi-row { display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; margin-bottom: 10px; }
.kpi-row.four { grid-template-columns: repeat(4, 1fr); }
.kpi { text-align: center; border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 4px; }
.kpi b { display: block; font-size: 20px; color: #409eff; }
.kpi.ok b { color: #67c23a; }
.kpi.danger b { color: #f56c6c; }
.kpi span { font-size: 12px; color: #909399; }
.hint-line { font-size: 13px; color: #606266; }
.chain-gate h4 { font-size: 14px; color: #303133; margin: 12px 0 6px; }
.rp-table { width: 100%; border-collapse: collapse; font-size: 13px; margin-top: 6px; }
.rp-table th, .rp-table td { border: 1px solid #ebeef5; padding: 6px 10px; text-align: left; vertical-align: top; }
.rp-table th { background: #f5f7fa; color: #606266; }
.mono { font-family: monospace; }
.rev { color: #e6a23c; font-size: 12px; }
.snap { color: #909399; font-size: 12px; }
.rp-empty { color: #909399; font-size: 13px; }
.blank-box { border: 1px dashed #dcdfe6; border-radius: 6px; min-height: 48px; padding: 8px 12px; margin-top: 10px; color: #909399; font-size: 13px; }
.rp-foot { margin-top: 26px; text-align: center; color: #c0c4cc; font-size: 12px; }
</style>
