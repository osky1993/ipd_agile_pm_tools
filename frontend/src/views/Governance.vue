<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '@/api/http'
import { evidenceApi, decisionApi, type Evidence, type Decision } from '@/api/governance'
import { metricsApi, type MatrixRow } from '@/api/metrics'
import { workItemApi, type WorkItem } from '@/api/workitem'
import WorkItemDrawer from '@/components/WorkItemDrawer.vue'

interface Project { id: number; code: string; name: string }

const projects = ref<Project[]>([])
const projectId = ref<number | null>(null)
const activeTab = ref('risk')
const risks = ref<WorkItem[]>([])
const evidences = ref<Evidence[]>([])
const decisions = ref<Decision[]>([])
const matrix = ref<MatrixRow[]>([])
const runType = (r: string | null) => (r === 'PASS' ? 'success' : r === 'FAIL' ? 'danger' : r ? 'warning' : 'info')

const drawerVisible = ref(false)
const currentId = ref<number | null>(null)

const riskDialog = ref(false)
const riskForm = reactive({ title: '', ownerId: undefined as number | undefined, mitigation: '', dueDate: '' })
const uploadDialog = ref(false)
const uploadFile = ref<File | null>(null)

const previewDialog = ref(false)
const previewRow = ref<Evidence | null>(null)
const isImage = (e: Evidence) => !!e.mime && e.mime.startsWith('image/') && !e.mime.includes('svg')
const isPdf = (e: Evidence) => e.mime === 'application/pdf'
const canPreview = (e: Evidence) => isImage(e) || isPdf(e)
function openPreview(e: Evidence) { previewRow.value = e; previewDialog.value = true }

const today = new Date().toISOString().slice(0, 10)

function riskExt(w: WorkItem): { mitigation?: string; dueDate?: string } {
  try { return w.extFields ? JSON.parse(w.extFields) : {} } catch { return {} }
}
function isOverdue(w: WorkItem): boolean {
  const due = riskExt(w).dueDate
  return !!due && due < today && w.status !== 'Closed'
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
  await workItemApi.create({
    projectId: projectId.value,
    type: 'RISK',
    title: riskForm.title,
    ownerId: riskForm.ownerId,
    extFields: JSON.stringify({ mitigation: riskForm.mitigation, dueDate: riskForm.dueDate || null }),
  })
  ElMessage.success('已登记风险')
  riskDialog.value = false
  riskForm.title = ''; riskForm.ownerId = undefined; riskForm.mitigation = ''; riskForm.dueDate = ''
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

onMounted(async () => {
  projects.value = await http.get<any, Project[]>('/projects')
  if (projects.value.length) { projectId.value = projects.value[0].id; await loadAll() }
})
</script>

<template>
  <div>
    <div class="toolbar">
      <el-select v-model="projectId" placeholder="项目" style="width:200px" @change="loadAll">
        <el-option v-for="p in projects" :key="p.id" :label="`${p.code} ${p.name}`" :value="p.id" />
      </el-select>
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
                {{ t.testCode }} {{ t.latestResult || '未执行' }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
        <p class="hint">需求×测试覆盖矩阵（规划§15.1）：未覆盖需求即缺少验证用例，最新结果直接取自测试执行。</p>
      </el-tab-pane>

      <!-- 风险 -->
      <el-tab-pane name="risk">
        <template #label>风险 ({{ risks.length }})<el-badge v-if="overdueCount" :value="overdueCount" class="badge" type="danger" /></template>
        <div class="sub-bar"><el-button type="primary" size="small" @click="riskDialog = true"><el-icon><Plus /></el-icon>登记风险</el-button></div>
        <el-table :data="risks" border @row-click="openRisk" class="clickable">
          <el-table-column prop="code" label="编号" width="130" />
          <el-table-column prop="title" label="风险" show-overflow-tooltip />
          <el-table-column label="处置措施" show-overflow-tooltip>
            <template #default="{ row }">{{ riskExt(row).mitigation }}</template>
          </el-table-column>
          <el-table-column label="责任人" width="80">
            <template #default="{ row }">{{ row.ownerId ? '#' + row.ownerId : '—' }}</template>
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
            <template #default="{ row }"><el-tag size="small" :type="riskStatusType(row.status)">{{ row.status }}</el-tag></template>
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
          <el-table-column prop="decisionType" label="类型" width="90" />
          <el-table-column label="结论" width="120">
            <template #default="{ row }"><el-tag size="small" :type="decisionType(row.conclusion)">{{ row.conclusion }}</el-tag></template>
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

    <!-- 登记风险 -->
    <el-dialog v-model="riskDialog" title="登记风险" width="460px">
      <el-form label-width="80px">
        <el-form-item label="风险描述"><el-input v-model="riskForm.title" /></el-form-item>
        <el-form-item label="责任人ID"><el-input v-model.number="riskForm.ownerId" placeholder="用户ID" /></el-form-item>
        <el-form-item label="处置措施"><el-input v-model="riskForm.mitigation" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="期限"><el-date-picker v-model="riskForm.dueDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="riskDialog = false">取消</el-button>
        <el-button type="primary" @click="submitRisk">登记</el-button>
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
</style>
