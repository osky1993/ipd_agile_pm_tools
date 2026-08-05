<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { evidenceApi, decisionApi, type Evidence, type Decision } from '@/api/governance'
import { metricsApi, type MatrixRow } from '@/api/metrics'
import { workItemApi, riskApi, riskChangeExcelApi, type WorkItem } from '@/api/workitem'
import { statusLabel, decisionLabel, decisionTypeLabel, testResultLabel } from '@/utils/labels'
import WorkItemDrawer from '@/components/WorkItemDrawer.vue'
import ProjectChips from '@/components/ProjectChips.vue'
import UserSelect from '@/components/UserSelect.vue'
import { useUserStore } from '@/stores/users'

const projectId = ref<number | null>(null)
const activeTab = ref('risk')
const users = useUserStore()
users.load()
const risks = ref<WorkItem[]>([])
const evidences = ref<Evidence[]>([])
const decisions = ref<Decision[]>([])
const matrix = ref<MatrixRow[]>([])
const runType = (r: string | null) => (r === 'PASS' ? 'success' : r === 'FAIL' ? 'danger' : r ? 'warning' : 'info')

const drawerVisible = ref(false)
const currentId = ref<number | null>(null)

const riskDialog = ref(false)
const riskForm = reactive({
  title: '', ownerId: undefined as number | undefined, mitigation: '', dueDate: '',
  probability: undefined as number | undefined, impact: undefined as number | undefined,
  strategy: '' as string,
})
/** 编辑态：非空表示在编辑已有风险（合并写回 ext，保留 wsjf 等既有键） */
const editingRisk = ref<WorkItem | null>(null)

const STRATEGY_LABEL: Record<string, string> = {
  AVOID: '规避', TRANSFER: '转移', MITIGATE: '减轻', ACCEPT: '接受',
}

function openRiskDialog(w?: WorkItem) {
  editingRisk.value = w ?? null
  const ext = w ? riskExt(w) : {}
  riskForm.title = w?.title ?? ''
  riskForm.ownerId = w?.ownerId ?? undefined
  riskForm.mitigation = ext.mitigation ?? ''
  riskForm.dueDate = ext.dueDate ?? ''
  riskForm.probability = ext.probability ?? undefined
  riskForm.impact = ext.impact ?? undefined
  riskForm.strategy = ext.strategy ?? ''
  riskDialog.value = true
}

/** 敞口 = 概率×影响；等级 高≥15/中≥8/低 */
function exposure(w: WorkItem): number | null {
  const e = riskExt(w)
  return e.probability && e.impact ? e.probability * e.impact : null
}
const exposureTag = (v: number | null) =>
  v == null ? 'info' : v >= 15 ? 'danger' : v >= 8 ? 'warning' : 'success'

// 5×5 矩阵：格子 key `p-i`
const matrixCell = ref<{ p: number; i: number } | null>(null)
const matrixCellRisks = computed(() =>
  matrixCell.value
    ? risks.value.filter((w) => {
        const e = riskExt(w)
        return e.probability === matrixCell.value!.p && e.impact === matrixCell.value!.i
          && !['Closed', 'Accepted'].includes(w.status)
      })
    : [])
function cellCount(p: number, i: number): number {
  return risks.value.filter((w) => {
    const e = riskExt(w)
    return e.probability === p && e.impact === i && !['Closed', 'Accepted'].includes(w.status)
  }).length
}
const cellLevel = (p: number, i: number) => (p * i >= 15 ? 'high' : p * i >= 8 ? 'med' : 'low')

async function genMitigationTask(w: WorkItem) {
  const t = await riskApi.mitigationTask(w.id)
  ElMessage.success(`已生成应对任务 ${t.code}（affects 链已建立）`)
  await loadAll()
}

// 风险 Excel 导入
const riskImportDialog = ref(false)
const riskImportFile = ref<File | null>(null)
const riskImportResult = ref<{ created: number; errors: string[] } | null>(null)
function onRiskImportFile(e: Event) {
  const files = (e.target as HTMLInputElement).files
  riskImportFile.value = files && files.length ? files[0] : null
  riskImportResult.value = null
}
async function submitRiskImport() {
  if (!projectId.value || !riskImportFile.value) return ElMessage.warning('请选择 Excel 文件')
  riskImportResult.value = await riskChangeExcelApi.importExcel(projectId.value, 'RISK', riskImportFile.value)
  ElMessage.success(`已导入 ${riskImportResult.value.created} 条`)
  await loadAll()
}
const uploadDialog = ref(false)
const uploadFile = ref<File | null>(null)

const previewDialog = ref(false)
const previewRow = ref<Evidence | null>(null)
const isImage = (e: Evidence) => !!e.mime && e.mime.startsWith('image/') && !e.mime.includes('svg')
const isPdf = (e: Evidence) => e.mime === 'application/pdf'
const canPreview = (e: Evidence) => isImage(e) || isPdf(e)
function openPreview(e: Evidence) { previewRow.value = e; previewDialog.value = true }

const today = new Date().toISOString().slice(0, 10)

function riskExt(w: WorkItem): { mitigation?: string; dueDate?: string; probability?: number; impact?: number; strategy?: string } {
  try { return w.extFields ? JSON.parse(w.extFields) : {} } catch { return {} }
}
function isOverdue(w: WorkItem): boolean {
  const due = riskExt(w).dueDate
  // 与后端预警口径一致：Closed 与 Accepted（已接受）都不算超期
  return !!due && due < today && !['Closed', 'Accepted'].includes(w.status)
}
const riskStatusType = (s: string) =>
  s === 'Closed' ? 'success' : s === 'Accepted' ? 'warning' : s === 'Mitigating' ? '' : 'danger'
const decisionType = (c: string) =>
  ['PASS', 'APPROVED'].includes(c) ? 'success' : ['REJECT', 'REJECTED'].includes(c) ? 'danger' : 'warning'

function fmtSize(n: number) {
  return n < 1024 ? `${n} B` : n < 1048576 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1048576).toFixed(1)} MB`
}

async function loadAll() {
  if (!projectId.value) return
  const pid = projectId.value
  ;[risks.value, evidences.value, decisions.value, matrix.value] = await Promise.all([
    workItemApi.list(pid, 'RISK'),
    evidenceApi.list(pid),
    decisionApi.list(pid),
    metricsApi.traceMatrix(pid),
  ])
}

async function submitRisk() {
  if (!projectId.value || !riskForm.title) return ElMessage.warning('风险标题必填')
  // 合并写回：编辑时以既有 ext 为底保留未知键（如 wsjf），风险键覆盖
  let ext: Record<string, unknown> = {}
  if (editingRisk.value) {
    try { ext = editingRisk.value.extFields ? JSON.parse(editingRisk.value.extFields) : {} } catch { ext = {} }
  }
  ext.mitigation = riskForm.mitigation || null
  ext.dueDate = riskForm.dueDate || null
  ext.probability = riskForm.probability ?? null
  ext.impact = riskForm.impact ?? null
  ext.strategy = riskForm.strategy || null
  const extFields = JSON.stringify(ext)

  if (editingRisk.value) {
    await workItemApi.update(editingRisk.value.id, {
      title: riskForm.title, ownerId: riskForm.ownerId, extFields,
    })
    ElMessage.success('风险已更新')
  } else {
    await workItemApi.create({
      projectId: projectId.value, type: 'RISK', title: riskForm.title,
      ownerId: riskForm.ownerId, extFields,
    })
    ElMessage.success('已登记风险')
  }
  riskDialog.value = false
  editingRisk.value = null
  await loadAll()
}

async function submitUpload() {
  if (!projectId.value || !uploadFile.value) return ElMessage.warning('请选择文件')
  await evidenceApi.upload(projectId.value, uploadFile.value)
  ElMessage.success('已上传证据')
  uploadDialog.value = false
  uploadFile.value = null
  await loadAll()
}
function onFileChange(e: Event) {
  const files = (e.target as HTMLInputElement).files
  uploadFile.value = files && files.length ? files[0] : null
}

function openRisk(w: WorkItem) { currentId.value = w.id; drawerVisible.value = true }

const overdueCount = computed(() => risks.value.filter(isOverdue).length)

</script>

<template>
  <div>
    <div class="toolbar">
      <ProjectChips v-model="projectId" @change="loadAll" />
    </div>

    <el-tabs v-model="activeTab">
      <!-- 追溯矩阵 -->
      <el-tab-pane :label="`追溯矩阵 (${matrix.length})`" name="matrix">
        <el-table :data="matrix" border>
          <el-table-column prop="code" label="需求编号" width="140" />
          <el-table-column prop="title" label="需求" show-overflow-tooltip />
          <el-table-column label="覆盖" width="90">
            <template #default="{ row }">
              <el-tag :type="row.covered ? 'success' : 'danger'" size="small">{{ row.covered ? '已覆盖' : '未覆盖' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="验证用例（最新结果）">
            <template #default="{ row }">
              <span v-if="!row.tests.length" class="muted">—</span>
              <el-tag v-for="t in row.tests" :key="t.testCode" :type="runType(t.latestResult)" size="small" class="tc">
                {{ t.testCode }} {{ testResultLabel(t.latestResult) }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <p class="hint">需求×测试覆盖矩阵（规划§15.1）：未覆盖需求即缺少验证用例，最新结果直接取自测试执行。</p>
      </el-tab-pane>

      <!-- 风险 -->
      <el-tab-pane name="risk">
        <template #label>风险 ({{ risks.length }})<el-badge v-if="overdueCount" :value="overdueCount" class="badge" type="danger" /></template>
        <div class="sub-bar">
          <el-button type="primary" size="small" @click="openRiskDialog()"><el-icon><Plus /></el-icon>登记风险</el-button>
          <el-button size="small" @click="riskImportDialog = true"><el-icon><Upload /></el-icon>Excel 导入</el-button>
        </div>
        <!-- 风险 Excel 导入 -->
        <el-dialog v-model="riskImportDialog" title="风险 Excel 导入" width="520px">
          <p class="hint">
            列：<b>标题(必填)</b>、说明、优先级、责任人ID、处置措施、处置期限(yyyy-MM-dd)。
            <a :href="riskChangeExcelApi.templateUrl('RISK')" style="margin-left:6px">⬇ 下载模板</a>
          </p>
          <input type="file" accept=".xlsx" @change="onRiskImportFile" />
          <div v-if="riskImportResult" style="margin-top:12px">
            <el-alert :type="riskImportResult.errors.length ? 'warning' : 'success'" :closable="false" show-icon>
              成功导入 {{ riskImportResult.created }} 条{{ riskImportResult.errors.length ? `，失败 ${riskImportResult.errors.length} 条` : '' }}
            </el-alert>
            <ul v-if="riskImportResult.errors.length" class="imp-errors">
              <li v-for="(e, i) in riskImportResult.errors" :key="i">{{ e }}</li>
            </ul>
          </div>
          <template #footer>
            <el-button @click="riskImportDialog = false">关闭</el-button>
            <el-button type="primary" @click="submitRiskImport">导入</el-button>
          </template>
        </el-dialog>
        <!-- 5×5 概率×影响矩阵（纯 CSS grid，点击格子下钻；只统计未闭环风险） -->
        <div class="matrix-wrap">
          <div class="matrix">
            <div class="mx-corner">概率↑<br/>影响→</div>
            <div v-for="i in 5" :key="'h' + i" class="mx-axis">{{ i }}</div>
            <template v-for="p in 5" :key="'r' + p">
              <div class="mx-axis">{{ 6 - p }}</div>
              <div v-for="i in 5" :key="`${6 - p}-${i}`" class="mx-cell" :class="cellLevel(6 - p, i)"
                @click="cellCount(6 - p, i) && (matrixCell = { p: 6 - p, i })">
                <span v-if="cellCount(6 - p, i)" class="mx-count">{{ cellCount(6 - p, i) }}</span>
              </div>
            </template>
          </div>
          <div class="mx-legend">
            <span><i class="mx-dot high" />高（敞口≥15）</span>
            <span><i class="mx-dot med" />中（≥8）</span>
            <span><i class="mx-dot low" />低</span>
            <span class="mx-note">仅统计未闭环风险 · 点击格子查看清单</span>
          </div>
        </div>
        <!-- 格子下钻 -->
        <el-dialog :model-value="!!matrixCell" width="560px" @close="matrixCell = null"
          :title="matrixCell ? `概率 ${matrixCell.p} × 影响 ${matrixCell.i}（敞口 ${matrixCell.p * matrixCell.i}）` : ''">
          <el-table :data="matrixCellRisks" size="small" border class="clickable" @row-click="openRisk">
            <el-table-column prop="code" label="编号" width="130" />
            <el-table-column prop="title" label="风险" show-overflow-tooltip />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">{{ statusLabel(row.status, 'RISK') }}</template>
            </el-table-column>
          </el-table>
        </el-dialog>

        <el-table :data="risks" border @row-click="openRisk" class="clickable">
          <el-table-column prop="code" label="编号" width="130" />
          <el-table-column prop="title" label="风险" show-overflow-tooltip />
          <el-table-column label="敞口" width="110" sortable
            :sort-method="(a: WorkItem, b: WorkItem) => (exposure(a) ?? -1) - (exposure(b) ?? -1)">
            <template #default="{ row }">
              <el-tag v-if="exposure(row) != null" size="small" :type="exposureTag(exposure(row))">
                {{ riskExt(row).probability }}×{{ riskExt(row).impact }}={{ exposure(row) }}
              </el-tag>
              <span v-else class="muted">未评估</span>
            </template>
          </el-table-column>
          <el-table-column label="策略" width="70">
            <template #default="{ row }">{{ riskExt(row).strategy ? STRATEGY_LABEL[riskExt(row).strategy!] ?? riskExt(row).strategy : '—' }}</template>
          </el-table-column>
          <el-table-column label="处置措施" show-overflow-tooltip>
            <template #default="{ row }">{{ riskExt(row).mitigation }}</template>
          </el-table-column>
          <el-table-column label="责任人" width="80">
            <template #default="{ row }">{{ users.label(row.ownerId) }}</template>
          </el-table-column>
          <el-table-column label="期限" width="140">
            <template #default="{ row }">
              <span v-if="riskExt(row).dueDate" :class="{ overdue: isOverdue(row) }">
                {{ riskExt(row).dueDate }}<el-tag v-if="isOverdue(row)" type="danger" size="small" class="od-tag">超期</el-tag>
              </span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }"><el-tag size="small" :type="riskStatusType(row.status)">{{ statusLabel(row.status, 'RISK') }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="150">
            <template #default="{ row }">
              <el-button link type="primary" size="small" @click.stop="openRiskDialog(row)">编辑</el-button>
              <el-button link type="primary" size="small" @click.stop="genMitigationTask(row)">生成应对任务</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 证据 -->
      <el-tab-pane :label="`证据 (${evidences.length})`" name="evidence">
        <div class="sub-bar"><el-button type="primary" size="small" @click="uploadDialog = true"><el-icon><Upload /></el-icon>上传证据</el-button></div>
        <el-table :data="evidences" border>
          <el-table-column prop="code" label="编号" width="130" />
          <el-table-column prop="fileName" label="文件名" show-overflow-tooltip />
          <el-table-column label="大小" width="90">
            <template #default="{ row }">{{ fmtSize(row.sizeBytes) }}</template>
          </el-table-column>
          <el-table-column label="SHA-256" width="150">
            <template #default="{ row }"><code class="sha">{{ row.sha256.slice(0, 16) }}…</code></template>
          </el-table-column>
          <el-table-column prop="createdAt" label="上传时间" width="160" />
          <el-table-column label="操作" width="120">
            <template #default="{ row }">
              <a v-if="canPreview(row)" class="dl" @click.prevent="openPreview(row)" href="#">预览</a>
              <a :href="evidenceApi.downloadUrl(row.id)" target="_blank" class="dl">下载</a>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <!-- 决策 -->
      <el-tab-pane :label="`决策 (${decisions.length})`" name="decision">
        <el-table :data="decisions" border>
          <el-table-column prop="code" label="编号" width="130" />
          <el-table-column label="类型" width="90"><template #default="{ row }">{{ decisionTypeLabel(row.decisionType) }}</template></el-table-column>
          <el-table-column label="结论" width="120">
            <template #default="{ row }"><el-tag size="small" :type="decisionType(row.conclusion)">{{ decisionLabel(row.conclusion) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="reason" label="理由" show-overflow-tooltip />
          <el-table-column label="决策人" width="80">
            <template #default="{ row }">#{{ row.decidedBy }}</template>
          </el-table-column>
          <el-table-column prop="decidedAt" label="时间" width="160" />
          <el-table-column label="修订自" width="80">
            <template #default="{ row }"><span v-if="row.prevDecisionId">#{{ row.prevDecisionId }}</span></template>
          </el-table-column>
        </el-table>
        <p class="hint">决策只增不改：修订通过新记录并指向被修订项，历史永不无痕覆盖。</p>
      </el-tab-pane>
    </el-tabs>

    <WorkItemDrawer v-model="drawerVisible" :item-id="currentId" @changed="loadAll" />

    <!-- 登记/编辑风险 -->
    <el-dialog v-model="riskDialog" :title="editingRisk ? `编辑风险 ${editingRisk.code}` : '登记风险'" width="500px">
      <el-form label-width="80px">
        <el-form-item label="风险描述"><el-input v-model="riskForm.title" /></el-form-item>
        <el-form-item label="责任人"><UserSelect v-model="riskForm.ownerId" /></el-form-item>
        <el-form-item label="概率×影响">
          <div class="pi-row">
            <el-select v-model="riskForm.probability" placeholder="概率" clearable style="width:110px">
              <el-option v-for="n in 5" :key="n" :label="`概率 ${n}`" :value="n" />
            </el-select>
            <span class="pi-x">×</span>
            <el-select v-model="riskForm.impact" placeholder="影响" clearable style="width:110px">
              <el-option v-for="n in 5" :key="n" :label="`影响 ${n}`" :value="n" />
            </el-select>
            <el-tag v-if="riskForm.probability && riskForm.impact" size="small"
              :type="exposureTag(riskForm.probability * riskForm.impact)">
              敞口 {{ riskForm.probability * riskForm.impact }}
            </el-tag>
          </div>
        </el-form-item>
        <el-form-item label="应对策略">
          <el-select v-model="riskForm.strategy" clearable placeholder="选择策略" style="width:100%">
            <el-option v-for="(l, k) in STRATEGY_LABEL" :key="k" :label="l" :value="k" />
          </el-select>
        </el-form-item>
        <el-form-item label="处置措施"><el-input v-model="riskForm.mitigation" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="期限"><el-date-picker v-model="riskForm.dueDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="riskDialog = false">取消</el-button>
        <el-button type="primary" @click="submitRisk">{{ editingRisk ? '保存' : '登记' }}</el-button>
      </template>
    </el-dialog>

    <!-- 证据预览（T708 反馈④）：图片/PDF 内联，其余类型走下载 -->
    <el-dialog v-model="previewDialog" :title="previewRow ? `预览：${previewRow.fileName}` : '预览'"
      width="760px" destroy-on-close>
      <template v-if="previewRow">
        <img v-if="isImage(previewRow)" :src="evidenceApi.previewUrl(previewRow.id)" class="pv-img" />
        <iframe v-else-if="isPdf(previewRow)" :src="evidenceApi.previewUrl(previewRow.id)" class="pv-pdf" />
      </template>
      <template #footer>
        <a v-if="previewRow" :href="evidenceApi.downloadUrl(previewRow.id)" target="_blank" class="dl">下载原文件</a>
      </template>
    </el-dialog>

    <!-- 上传证据 -->
    <el-dialog v-model="uploadDialog" title="上传证据" width="440px">
      <input type="file" @change="onFileChange" />
      <p class="hint">上传后系统计算 SHA-256 摘要并入库；文件存本地证据目录。</p>
      <template #footer>
        <el-button @click="uploadDialog = false">取消</el-button>
        <el-button type="primary" @click="submitUpload">上传</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { margin-bottom: 16px; }
.sub-bar { margin-bottom: 12px; }
.clickable :deep(.el-table__row) { cursor: pointer; }
.overdue { color: #f56c6c; font-weight: 600; }
.od-tag { margin-left: 6px; }
.sha { font-family: monospace; font-size: 12px; color: #909399; }
.tc { margin: 2px; }
.muted { color: #c0c4cc; }
.dl { color: #409eff; text-decoration: none; margin-right: 8px; cursor: pointer; }
.pv-img { max-width: 100%; max-height: 70vh; display: block; margin: 0 auto; }
.pv-pdf { width: 100%; height: 70vh; border: none; }
.hint { color: #909399; font-size: 12px; margin-top: 10px; }
.badge { margin-left: 6px; }
.imp-errors { color: #e6a23c; font-size: 12px; margin: 8px 0 0; padding-left: 18px; }

/* 5×5 概率×影响矩阵 */
.matrix-wrap { display: flex; align-items: flex-end; gap: 18px; margin-bottom: 14px; flex-wrap: wrap; }
.matrix { display: grid; grid-template-columns: 56px repeat(5, 44px); grid-auto-rows: 36px; gap: 3px; }
.mx-corner { font-size: 10px; color: #909399; display: flex; align-items: center; justify-content: center; text-align: center; line-height: 1.3; }
.mx-axis { font-size: 12px; color: #909399; display: flex; align-items: center; justify-content: center; }
.mx-cell { border-radius: 4px; display: flex; align-items: center; justify-content: center; cursor: default; transition: transform .1s; }
.mx-cell.low { background: rgba(103, 194, 58, .18); }
.mx-cell.med { background: rgba(230, 162, 60, .28); }
.mx-cell.high { background: rgba(245, 108, 108, .32); }
.mx-cell:has(.mx-count) { cursor: pointer; }
.mx-cell:has(.mx-count):hover { transform: scale(1.08); }
.mx-count { background: #303133; color: #fff; border-radius: 10px; min-width: 20px; height: 20px; display: flex; align-items: center; justify-content: center; font-size: 12px; padding: 0 5px; }
.mx-legend { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: #909399; padding-bottom: 4px; }
.mx-dot { display: inline-block; width: 10px; height: 10px; border-radius: 3px; margin-right: 5px; vertical-align: -1px; }
.mx-dot.low { background: rgba(103, 194, 58, .5); }
.mx-dot.med { background: rgba(230, 162, 60, .6); }
.mx-dot.high { background: rgba(245, 108, 108, .6); }
.mx-note { color: #c0c4cc; }
.muted { color: #c0c4cc; font-size: 12px; }
.pi-row { display: flex; align-items: center; gap: 8px; }
.pi-x { color: #909399; }
</style>
