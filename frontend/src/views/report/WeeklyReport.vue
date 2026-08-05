<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import http from '@/api/http'
import { alertApi, type Alert } from '@/api/perf'
import ReportSection from '@/components/report/ReportSection.vue'
import MarkdownView from '@/components/MarkdownView.vue'
import { statusLabel, typeLabel, decisionLabel, decisionTypeLabel } from '@/utils/labels'
import { useUserStore } from '@/stores/users'
import '@/assets/print.css'

interface Project { id: number; code: string; name: string }
interface ActivityItem { id: number; code: string; type: string; title: string; status: string; createdAt: string }
interface TransitionRow { workItemId: number; code: string; type: string; title: string; fromStatus: string; toStatus: string; reason?: string | null; at: string }
interface DecisionRow { id: number; code: string; decisionType: string; conclusion: string; reason?: string; decidedAt: string }
interface EvidenceRow { id: number; code: string; fileName: string; createdAt: string }
interface Summary {
  since: string; until: string
  created: ActivityItem[]; transitions: TransitionRow[]
  decisions: DecisionRow[]; evidences: EvidenceRow[]
}

const route = useRoute()
const projectId = Number(route.params.projectId)
const days = Number(route.query.days ?? 7)
const loading = ref(true)
const users = useUserStore()

const project = ref<Project | null>(null)
const data = ref<Summary | null>(null)
const alerts = ref<Alert[]>([])

const now = new Date().toLocaleString('zh-CN')
const DONE_STATUSES = ['Accepted', 'Closed', 'Verified', 'Approved']
const doneTransitions = computed(() =>
  (data.value?.transitions ?? []).filter((t) => DONE_STATUSES.includes(t.toStatus)))
const highAlerts = computed(() => alerts.value.filter((a) => a.severity === 'HIGH'))

function doPrint() {
  window.print()
}

async function load() {
  try {
    users.load()
    ;[project.value, data.value, alerts.value] = await Promise.all([
      http.get<any, Project>(`/projects/${projectId}`),
      http.get<any, Summary>(`/projects/${projectId}/weekly`, { params: { days } }),
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
      <span class="tip">时间窗可用 ?days=14 调整（默认 7 天）</span>
    </div>

    <div class="report-page" v-if="project && data">
      <header class="rp-head">
        <h1>{{ project.name }} · 项目周报</h1>
        <div class="rp-meta">
          <span>{{ data.since }} ~ {{ data.until }}（近 {{ days }} 天）</span>
          <span>生成时间：{{ now }}</span>
        </div>
      </header>

      <ReportSection title="一、本期概览">
        <div class="kpi-row">
          <div class="kpi"><b>{{ data.created.length }}</b><span>新增工作项</span></div>
          <div class="kpi"><b>{{ data.transitions.length }}</b><span>状态流转</span></div>
          <div class="kpi ok"><b>{{ doneTransitions.length }}</b><span>完成/验收/关闭</span></div>
          <div class="kpi"><b>{{ data.decisions.length }}</b><span>决策</span></div>
          <div class="kpi"><b>{{ data.evidences.length }}</b><span>新增证据</span></div>
          <div class="kpi" :class="{ danger: highAlerts.length }"><b>{{ highAlerts.length }}</b><span>当前 HIGH 预警</span></div>
        </div>
      </ReportSection>

      <ReportSection title="二、本期完成（验收/关闭/批准）">
        <table class="rp-table" v-if="doneTransitions.length">
          <thead><tr><th style="width:130px">编号</th><th style="width:80px">类型</th><th>标题</th><th style="width:110px">终态</th><th style="width:110px">时间</th></tr></thead>
          <tbody>
            <tr v-for="(t, i) in doneTransitions" :key="i">
              <td class="mono">{{ t.code }}</td>
              <td>{{ typeLabel(t.type) }}</td>
              <td>{{ t.title }}</td>
              <td>{{ statusLabel(t.toStatus, t.type) }}</td>
              <td>{{ t.at.slice(0, 10) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="rp-empty">本期无完成项。</p>
      </ReportSection>

      <ReportSection title="三、新增工作项">
        <table class="rp-table" v-if="data.created.length">
          <thead><tr><th style="width:130px">编号</th><th style="width:80px">类型</th><th>标题</th><th style="width:110px">当前状态</th></tr></thead>
          <tbody>
            <tr v-for="w in data.created" :key="w.id">
              <td class="mono">{{ w.code }}</td>
              <td>{{ typeLabel(w.type) }}</td>
              <td>{{ w.title }}</td>
              <td>{{ statusLabel(w.status, w.type) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="rp-empty">本期无新增。</p>
      </ReportSection>

      <ReportSection title="四、全部状态流转" page-break>
        <table class="rp-table" v-if="data.transitions.length">
          <thead><tr><th style="width:130px">编号</th><th>标题</th><th style="width:190px">流转</th><th>理由</th><th style="width:110px">时间</th></tr></thead>
          <tbody>
            <tr v-for="(t, i) in data.transitions" :key="i">
              <td class="mono">{{ t.code }}</td>
              <td>{{ t.title }}</td>
              <td>{{ statusLabel(t.fromStatus, t.type) }} → {{ statusLabel(t.toStatus, t.type) }}</td>
              <td>{{ t.reason ?? '' }}</td>
              <td>{{ t.at.slice(0, 10) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="rp-empty">本期无状态流转。</p>
      </ReportSection>

      <ReportSection title="五、决策与证据">
        <table class="rp-table" v-if="data.decisions.length">
          <thead><tr><th style="width:110px">编号</th><th style="width:90px">类型</th><th style="width:110px">结论</th><th>理由</th><th style="width:110px">时间</th></tr></thead>
          <tbody>
            <tr v-for="d in data.decisions" :key="d.id">
              <td class="mono">{{ d.code }}</td>
              <td>{{ decisionTypeLabel(d.decisionType) }}</td>
              <td>{{ decisionLabel(d.conclusion) }}</td>
              <td class="reason-col"><MarkdownView v-if="d.reason" :source="d.reason" /></td>
              <td>{{ d.decidedAt.slice(0, 10) }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="rp-empty">本期无决策。</p>
        <p v-if="data.evidences.length" class="ev-line">
          本期新增证据：{{ data.evidences.map((e) => `${e.code} ${e.fileName}`).join('、') }}
        </p>
      </ReportSection>

      <ReportSection title="六、当前 HIGH 预警（下期关注）">
        <table class="rp-table" v-if="highAlerts.length">
          <thead><tr><th style="width:130px">编号</th><th style="width:150px">预警</th><th>说明</th><th style="width:100px">期限</th></tr></thead>
          <tbody>
            <tr v-for="(a, i) in highAlerts" :key="i">
              <td class="mono">{{ a.refCode }}</td>
              <td>{{ a.title }}</td>
              <td>{{ a.detail }}</td>
              <td>{{ a.due ?? '—' }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="rp-empty">当前无 HIGH 级预警 🎉</p>
      </ReportSection>

      <footer class="rp-foot">IPD 敏捷数字化工具箱 · 周报由审计流水与状态时间线自动生成</footer>
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
.rp-head { border-bottom: 3px double #409eff; padding-bottom: 14px; margin-bottom: 22px; }
.kpi-row { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; }
.kpi { text-align: center; border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 4px; }
.kpi b { display: block; font-size: 20px; color: #409eff; }
.kpi.ok b { color: #67c23a; }
.kpi.danger b { color: #f56c6c; }
.kpi span { font-size: 12px; color: #909399; }
.rp-table { width: 100%; border-collapse: collapse; font-size: 13px; margin-top: 10px; }
.rp-table th, .rp-table td { border: 1px solid #ebeef5; padding: 6px 10px; text-align: left; vertical-align: top; }
.rp-table th { background: #f5f7fa; color: #606266; }
.reason-col { min-width: 220px; }
.mono { font-family: monospace; }
.rp-empty { color: #909399; font-size: 13px; }
.ev-line { font-size: 13px; color: #606266; margin-top: 8px; }
.rp-foot { margin-top: 26px; text-align: center; color: #c0c4cc; font-size: 12px; }
</style>
