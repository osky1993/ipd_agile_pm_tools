<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { dcpApi, type CriterionView, type Snapshot } from '@/api/dcp'
import { stageApi, criterionApi, type StageGate, type GateCriterion } from '@/api/catalog'
import { evidenceApi, decisionApi, type Decision } from '@/api/governance'
import { readinessApi, type ReadinessSummary } from '@/api/readiness'
import { workItemApi, type WorkItem } from '@/api/workitem'
import ProjectChips from '@/components/ProjectChips.vue'

const STATUS_OPTS = [
  { value: 'NOT_READY', label: '未准备' },
  { value: 'PARTIAL', label: '部分准备' },
  { value: 'MET', label: '已满足' },
  { value: 'WAIVED', label: '豁免' },
]
const STATUS_LABEL: Record<string, string> = Object.fromEntries(STATUS_OPTS.map((o) => [o.value, o.label]))
const statusType = (s: string) => (s === 'MET' ? 'success' : s === 'PARTIAL' ? 'warning' : s === 'WAIVED' ? 'info' : 'danger')
const conclType = (c: string) => (c === 'PASS' ? 'success' : c === 'REJECT' ? 'danger' : 'warning')

const projectId = ref<number | null>(null)
const gates = ref<StageGate[]>([])
const gateId = ref<number | null>(null)
const criteria = ref<CriterionView[]>([])
const snapshot = ref<Snapshot | null>(null)
const risks = ref<WorkItem[]>([])
const decisions = ref<Decision[]>([])

const reviewDialog = ref(false)
const reviewForm = reactive({ conclusion: 'CONDITIONAL', reason: '', linkedRiskId: undefined as number | undefined, commitmentDue: '' })
const snapshotDialog = ref(false)
const snapshotView = ref<any>(null)

// 跨职能准备度视图（E6）
const viewMode = ref<'dcp' | 'readiness'>('dcp')
const rSummary = ref<ReadinessSummary | null>(null)
const rItems = ref<GateCriterion[]>([])
const READINESS_DOMAINS = ['技术', '质量', '供应', '制造', '上市']
const itemDialog = ref(false)
const itemForm = reactive({ domain: '技术', criterion: '', isRedline: false, ownerId: undefined as number | undefined, status: 'NOT_READY' })

async function loadReadiness() {
  if (!projectId.value) return
  ;[rSummary.value, rItems.value] = await Promise.all([
    readinessApi.summary(projectId.value),
    readinessApi.items(projectId.value),
  ])
}
function itemsOf(domain: string) {
  return rItems.value.filter((i) => i.domain === domain)
}
async function submitItem() {
  if (!projectId.value || !itemForm.criterion) return ElMessage.warning('检查项描述必填')
  await criterionApi.create({
    projectId: projectId.value,
    domain: itemForm.domain,
    criterion: itemForm.criterion,
    isRedline: itemForm.isRedline ? 1 : 0,
    ownerId: itemForm.ownerId,
    status: itemForm.status,
    isReadiness: 1,
  } as any)
  ElMessage.success('已新增准备度检查项')
  itemDialog.value = false
  itemForm.criterion = ''; itemForm.isRedline = false; itemForm.ownerId = undefined; itemForm.status = 'NOT_READY'
  await loadReadiness()
}
async function changeItemStatus(row: GateCriterion, status: string) {
  await criterionApi.update(row.id, { status })
  await loadReadiness()
}
async function switchView(mode: 'dcp' | 'readiness') {
  viewMode.value = mode
  if (mode === 'readiness') await loadReadiness()
}

const currentGate = computed(() => gates.value.find((g) => g.id === gateId.value))

async function loadGates() {
  if (!projectId.value) return
  gates.value = await stageApi.list(projectId.value)
  if (gates.value.length && !gates.value.find((g) => g.id === gateId.value)) gateId.value = gates.value[0].id
  await loadOverview()
}

async function loadOverview() {
  if (!projectId.value) return
  risks.value = await workItemApi.list(projectId.value, 'RISK')
  decisions.value = (await decisionApi.list(projectId.value)).filter((d) => d.decisionType === 'DCP')
  if (gateId.value) {
    const ov = await dcpApi.overview(gateId.value)
    criteria.value = ov.criteria
    snapshot.value = ov.snapshot
  } else {
    criteria.value = []; snapshot.value = null
  }
}

async function changeStatus(row: CriterionView, status: string) {
  try {
    await dcpApi.updateCriterion(row.id, { status })
    ElMessage.success('已更新状态')
    await loadOverview()
  } catch { await loadOverview() }
}

async function uploadEvidence(row: CriterionView, e: Event) {
  const files = (e.target as HTMLInputElement).files
  if (!files || !files.length || !projectId.value) return
  await evidenceApi.upload(projectId.value, files[0], 'GATE_CRITERION', row.id)
  ElMessage.success('已上传证据并关联')
  await loadOverview()
}

async function submitReview() {
  if (!gateId.value) return
  try {
    await dcpApi.review(gateId.value, {
      conclusion: reviewForm.conclusion,
      reason: reviewForm.reason,
      linkedRiskId: reviewForm.linkedRiskId,
      commitmentDue: reviewForm.commitmentDue || undefined,
    })
    ElMessage.success('评审决策已记录')
    reviewDialog.value = false
    reviewForm.reason = ''; reviewForm.linkedRiskId = undefined; reviewForm.commitmentDue = ''
    await loadOverview()
  } catch { /* 规则拦截已提示 */ }
}

function viewSnapshot(d: Decision & { snapshot?: string }) {
  try { snapshotView.value = d.snapshot ? JSON.parse(d.snapshot) : null } catch { snapshotView.value = null }
  snapshotDialog.value = true
}

</script>

<template>
  <div>
    <div class="toolbar">
      <div class="filters">
        <ProjectChips v-model="projectId" @change="loadGates" />
        <el-radio-group :model-value="viewMode" @change="(v: any) => switchView(v)">
          <el-radio-button value="dcp">DCP 准入条件</el-radio-button>
          <el-radio-button value="readiness">跨职能准备度</el-radio-button>
        </el-radio-group>
        <el-select v-if="viewMode === 'dcp'" v-model="gateId" placeholder="阶段/DCP" style="width:200px" @change="loadOverview">
          <el-option v-for="g in gates" :key="g.id" :label="`${g.stageName} / ${g.gateName}`" :value="g.id" />
        </el-select>
      </div>
      <el-button v-if="viewMode === 'dcp'" type="primary" :disabled="!gateId" @click="reviewDialog = true">发起 DCP 评审</el-button>
      <el-button v-else type="primary" @click="itemDialog = true">新增准备度检查项</el-button>
    </div>

    <!-- 跨职能准备度视图（E6） -->
    <template v-if="viewMode === 'readiness'">
      <el-alert v-if="rSummary && !rSummary.overall.ready" type="warning" :closable="false" show-icon class="mb">
        <b>局部完成但整机未就绪</b>：需求已验收 {{ rSummary.overall.reqAccepted }}/{{ rSummary.overall.reqTotal }}，但存在
        {{ rSummary.overall.reasons.length }} 项阻碍 —— {{ rSummary.overall.reasons.join('；') }}
      </el-alert>
      <el-alert v-else-if="rSummary" type="success" :closable="false" show-icon class="mb">
        整机就绪：五领域无红线未满足与未就绪项。
      </el-alert>

      <el-row :gutter="12" class="kpis" v-if="rSummary">
        <el-col v-for="d in rSummary.domains" :key="d.domain" :span="Math.floor(24 / rSummary.domains.length)">
          <el-card shadow="never" :class="{ danger: d.redlineUnmet.length }">
            <div class="dm-name">{{ d.domain }}<el-tag v-if="d.redlineUnmet.length" type="danger" size="small" class="rl">红线</el-tag></div>
            <el-progress type="dashboard" :width="90" :percentage="d.total ? Math.round((d.met / d.total) * 100) : 0"
              :color="d.redlineUnmet.length ? '#f56c6c' : undefined" />
            <div class="dm-detail">{{ d.met }}/{{ d.total }} 满足 · 未就绪 {{ d.notReady }}</div>
          </el-card>
        </el-col>
      </el-row>

      <el-collapse v-for="dm in READINESS_DOMAINS" :key="dm" class="rdomain">
        <el-collapse-item :title="`${dm}（${itemsOf(dm).length} 项）`" :name="dm">
          <el-table :data="itemsOf(dm)" border size="small">
            <el-table-column prop="code" label="编号" width="130" />
            <el-table-column prop="criterion" label="检查项" show-overflow-tooltip />
            <el-table-column label="红线" width="60">
              <template #default="{ row }"><el-tag v-if="row.isRedline" type="danger" size="small">红线</el-tag></template>
            </el-table-column>
            <el-table-column label="责任人" width="80">
              <template #default="{ row }">{{ row.ownerId ? '#' + row.ownerId : '—' }}</template>
            </el-table-column>
            <el-table-column label="状态" width="130">
              <template #default="{ row }">
                <el-select :model-value="row.status" size="small" @change="(v: string) => changeItemStatus(row, v)">
                  <el-option v-for="o in STATUS_OPTS" :key="o.value" :label="o.label" :value="o.value" />
                </el-select>
              </template>
            </el-table-column>
          </el-table>
          <div v-if="!itemsOf(dm).length" class="empty">该领域暂无检查项</div>
        </el-collapse-item>
      </el-collapse>

      <el-dialog v-model="itemDialog" title="新增准备度检查项" width="460px">
        <el-form label-width="80px">
          <el-form-item label="领域">
            <el-select v-model="itemForm.domain" style="width:100%">
              <el-option v-for="d in READINESS_DOMAINS" :key="d" :label="d" :value="d" />
            </el-select>
          </el-form-item>
          <el-form-item label="检查项"><el-input v-model="itemForm.criterion" type="textarea" :rows="2" /></el-form-item>
          <el-form-item label="责任人ID"><el-input v-model.number="itemForm.ownerId" placeholder="用户ID" /></el-form-item>
          <el-form-item label="状态">
            <el-select v-model="itemForm.status" style="width:100%">
              <el-option v-for="o in STATUS_OPTS" :key="o.value" :label="o.label" :value="o.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="红线"><el-checkbox v-model="itemForm.isRedline">红线项（未满足将影响 DCP 判断）</el-checkbox></el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="itemDialog = false">取消</el-button>
          <el-button type="primary" @click="submitItem">新增</el-button>
        </template>
      </el-dialog>
    </template>

    <template v-else>

    <!-- 准备度概览 -->
    <el-row :gutter="12" class="kpis" v-if="snapshot">
      <el-col :span="6">
        <el-card shadow="never" :class="{ danger: snapshot.redlineUnmet.length }">
          <div class="kpi-num">{{ snapshot.redlineUnmet.length }}</div>
          <div class="kpi-label">红线未满足</div>
          <div class="kpi-detail">{{ snapshot.redlineUnmet.join('、') || '—' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" :class="{ warning: snapshot.evidenceMissing.length }">
          <div class="kpi-num">{{ snapshot.evidenceMissing.length }}</div>
          <div class="kpi-label">证据缺失（已满足无证据）</div>
          <div class="kpi-detail">{{ snapshot.evidenceMissing.join('、') || '—' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never" :class="{ warning: snapshot.ownerMissing.length }">
          <div class="kpi-num">{{ snapshot.ownerMissing.length }}</div>
          <div class="kpi-label">无责任人</div>
          <div class="kpi-detail">{{ snapshot.ownerMissing.join('、') || '—' }}</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <div class="domain-stats">
            <div v-for="(st, dm) in snapshot.byDomain" :key="dm" class="dstat">
              <span class="dm">{{ dm }}</span>
              <el-progress :percentage="Math.round((st.met / st.total) * 100)" :stroke-width="10" />
            </div>
          </div>
          <div class="kpi-label">分领域满足率</div>
        </el-card>
      </el-col>
    </el-row>

    <!-- 条件清单 -->
    <el-table :data="criteria" border stripe>
      <el-table-column prop="domain" label="领域" width="70" />
      <el-table-column prop="code" label="编号" width="130" />
      <el-table-column prop="criterion" label="准入条件" show-overflow-tooltip />
      <el-table-column label="红线" width="60">
        <template #default="{ row }"><el-tag v-if="row.isRedline" type="danger" size="small">红线</el-tag></template>
      </el-table-column>
      <el-table-column label="责任人" width="80">
        <template #default="{ row }">{{ row.ownerId ? '#' + row.ownerId : '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="130">
        <template #default="{ row }">
          <el-select :model-value="row.status" size="small" @change="(v: string) => changeStatus(row, v)">
            <el-option v-for="o in STATUS_OPTS" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </template>
      </el-table-column>
      <el-table-column label="证据" width="130">
        <template #default="{ row }">
          <el-tag :type="row.evidenceCount ? 'success' : 'info'" size="small">{{ row.evidenceCount }} 份</el-tag>
          <label class="upload-lbl">
            <input type="file" class="hidden-file" @change="(e) => uploadEvidence(row, e)" />上传
          </label>
        </template>
      </el-table-column>
    </el-table>

    <!-- 决策历史 -->
    <el-divider content-position="left">DCP 评审决策历史</el-divider>
    <el-table :data="decisions" border size="small">
      <el-table-column prop="code" label="编号" width="130" />
      <el-table-column label="结论" width="120">
        <template #default="{ row }"><el-tag size="small" :type="conclType(row.conclusion)">{{ row.conclusion }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="reason" label="理由" show-overflow-tooltip />
      <el-table-column label="遗留风险" width="90">
        <template #default="{ row }"><span v-if="row.linkedRiskId">#{{ row.linkedRiskId }}</span><span v-else>—</span></template>
      </el-table-column>
      <el-table-column prop="commitmentDue" label="承诺期限" width="120" />
      <el-table-column prop="decidedAt" label="决策时间" width="160" />
      <el-table-column label="下钻" width="90">
        <template #default="{ row }"><el-button link type="primary" size="small" @click="viewSnapshot(row)">查看快照</el-button></template>
      </el-table-column>
    </el-table>

    <!-- 发起评审 -->
    <el-dialog v-model="reviewDialog" :title="`发起 DCP 评审 · ${currentGate?.gateName || ''}`" width="480px">
      <el-alert v-if="snapshot?.redlineUnmet.length" type="error" :closable="false" show-icon class="mb">
        存在红线未满足项（{{ snapshot.redlineUnmet.join('、') }}），不能判"通过"。
      </el-alert>
      <el-form label-width="88px">
        <el-form-item label="评审结论">
          <el-radio-group v-model="reviewForm.conclusion">
            <el-radio-button value="PASS">通过</el-radio-button>
            <el-radio-button value="CONDITIONAL">有条件通过</el-radio-button>
            <el-radio-button value="REJECT">不通过</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="理由"><el-input v-model="reviewForm.reason" type="textarea" :rows="2" /></el-form-item>
        <template v-if="reviewForm.conclusion === 'CONDITIONAL'">
          <el-form-item label="遗留风险" required>
            <el-select v-model="reviewForm.linkedRiskId" placeholder="绑定遗留风险" style="width:100%">
              <el-option v-for="r in risks" :key="r.id" :label="`${r.code} ${r.title}`" :value="r.id" />
            </el-select>
          </el-form-item>
          <el-form-item label="完成期限" required>
            <el-date-picker v-model="reviewForm.commitmentDue" type="date" value-format="YYYY-MM-DD" style="width:100%" />
          </el-form-item>
        </template>
      </el-form>
      <p class="hint">系统计算准备度，但决策由授权角色（REVIEWER）作出；有条件通过必须绑定遗留风险与期限。</p>
      <template #footer>
        <el-button @click="reviewDialog = false">取消</el-button>
        <el-button type="primary" @click="submitReview">提交决策</el-button>
      </template>
    </el-dialog>

    <!-- 快照下钻 -->
    <el-dialog v-model="snapshotDialog" title="决策时固化的准备度快照" width="460px">
      <template v-if="snapshotView">
        <p><b>红线未满足：</b>{{ snapshotView.redlineUnmet?.join('、') || '无' }}</p>
        <p><b>证据缺失：</b>{{ snapshotView.evidenceMissing?.join('、') || '无' }}</p>
        <p><b>无责任人：</b>{{ snapshotView.ownerMissing?.join('、') || '无' }}</p>
        <p><b>分领域满足：</b></p>
        <ul>
          <li v-for="(st, dm) in snapshotView.byDomain" :key="dm">{{ dm }}：{{ st.met }}/{{ st.total }} 满足</li>
        </ul>
      </template>
      <div v-else class="empty">无快照</div>
    </el-dialog>
    </template>
  </div>
</template>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.filters { display: flex; gap: 12px; }
.kpis { margin-bottom: 16px; }
.kpi-num { font-size: 28px; font-weight: 700; color: #409eff; }
.kpi-label { font-size: 13px; color: #606266; margin-top: 2px; }
.kpi-detail { font-size: 12px; color: #909399; margin-top: 4px; min-height: 16px; }
.danger { border-color: #f56c6c; }
.danger .kpi-num { color: #f56c6c; }
.warning { border-color: #e6a23c; }
.warning .kpi-num { color: #e6a23c; }
.domain-stats { display: flex; flex-direction: column; gap: 4px; }
.dstat { display: flex; align-items: center; gap: 8px; }
.dstat .dm { font-size: 12px; width: 36px; }
.dstat :deep(.el-progress) { flex: 1; }
.upload-lbl { color: #409eff; font-size: 12px; margin-left: 8px; cursor: pointer; }
.hidden-file { display: none; }
.hint { color: #909399; font-size: 12px; margin-top: 8px; }
.mb { margin-bottom: 12px; }
.empty { color: #c0c4cc; }
</style>
