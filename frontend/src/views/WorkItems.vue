<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { workItemApi, batchApi, metaApi, type WorkItem, type BatchItemResult } from '@/api/workitem'
import { iterationApi, type Iteration } from '@/api/agile'
import WorkItemDrawer from '@/components/WorkItemDrawer.vue'
import { statusLabel, typeLabel } from '@/utils/labels'
import ProjectChips from '@/components/ProjectChips.vue'
import UserSelect from '@/components/UserSelect.vue'
import { useUserStore } from '@/stores/users'

const projectId = ref<number | null>(null)
const types = ref<{ value: string; abbr: string; label: string }[]>([])
const typeFilter = ref<string>('')
const list = ref<WorkItem[]>([])
const loading = ref(false)
const users = useUserStore()

const drawerVisible = ref(false)
const currentId = ref<number | null>(null)

const createVisible = ref(false)
const createForm = ref({ type: 'REQUIREMENT', title: '' })

const statusType = (s: string) => {
  if (['Accepted', 'Closed', 'Verified', 'Approved'].includes(s)) return 'success'
  if (['Verification', 'Retesting', 'Impact Analysed'].includes(s)) return 'warning'
  if (['Rejected'].includes(s)) return 'danger'
  return 'info'
}

async function loadList() {
  if (!projectId.value) return
  loading.value = true
  try {
    list.value = await workItemApi.list(projectId.value, typeFilter.value || undefined)
  } finally {
    loading.value = false
  }
}

function openDetail(row: WorkItem, column?: { type?: string }) {
  if (column?.type === 'selection') return // 点勾选框不打开详情
  currentId.value = row.id
  drawerVisible.value = true
}

const importVisible = ref(false)
const importFile = ref<File | null>(null)
const importResult = ref<{ created: number; errors: string[] } | null>(null)

function onImportFile(e: Event) {
  const files = (e.target as HTMLInputElement).files
  importFile.value = files && files.length ? files[0] : null
  importResult.value = null
}
async function submitImport() {
  if (!projectId.value || !importFile.value) return ElMessage.warning('请选择 CSV 文件')
  importResult.value = await workItemApi.importCsv(projectId.value, importFile.value)
  ElMessage.success(`已导入 ${importResult.value.created} 条`)
  await loadList()
}

async function submitCreate() {
  if (!projectId.value || !createForm.value.title) {
    ElMessage.warning('请填写标题')
    return
  }
  const created = await workItemApi.create({
    projectId: projectId.value,
    type: createForm.value.type,
    title: createForm.value.title,
  })
  ElMessage.success(`已创建 ${created.code}`)
  createVisible.value = false
  createForm.value = { type: 'REQUIREMENT', title: '' }
  await loadList()
  openDetail(created)
}

// ---------- 批量操作 ----------
const selection = ref<WorkItem[]>([])
const onSelectionChange = (rows: WorkItem[]) => { selection.value = rows }

/** 各类型状态链（仅作批量流转的目标候选，后端状态机仍是唯一权威） */
const TYPE_CHAINS: Record<string, string[]> = {
  CAPABILITY: ['Backlog', 'Ready', 'In Progress', 'Verification', 'Accepted'],
  REQUIREMENT: ['Backlog', 'Ready', 'In Progress', 'Verification', 'Accepted'],
  STORY: ['Backlog', 'Ready', 'In Progress', 'Verification', 'Accepted'],
  TASK: ['Backlog', 'Ready', 'In Progress', 'Verification', 'Accepted'],
  DEFECT: ['Open', 'Analysing', 'Fixing', 'Retesting', 'Closed'],
  CHANGE: ['Submitted', 'Impact Analysed', 'Approved', 'Rejected', 'Implemented', 'Verified'],
  RISK: ['Open', 'Mitigating', 'Closed', 'Accepted'],
}
const batchStatusOptions = computed(() => {
  const out: string[] = []
  for (const t of new Set(selection.value.map((w) => w.type))) {
    for (const s of TYPE_CHAINS[t] ?? []) {
      if (!out.includes(s)) out.push(s)
    }
  }
  return out
})
// 混选类型时状态标签需带类型语境，取选中集中首个该状态所属类型
const batchStatusLabel = (s: string) => {
  const owner = selection.value.find((w) => (TYPE_CHAINS[w.type] ?? []).includes(s))
  return statusLabel(s, owner?.type)
}

const batchDialog = ref<'' | 'transition' | 'owner' | 'priority' | 'iteration'>('')
const batchForm = ref({ toStatus: '', reason: '', ownerId: undefined as number | undefined, priority: 'P2', iterationId: undefined as number | undefined })
const iterations = ref<Iteration[]>([])
const batchResult = ref<BatchItemResult[] | null>(null)
const batchResultVisible = ref(false)

async function openBatch(kind: 'transition' | 'owner' | 'priority' | 'iteration') {
  batchForm.value = { toStatus: '', reason: '', ownerId: undefined, priority: 'P2', iterationId: undefined }
  if (kind === 'iteration' && projectId.value) {
    iterations.value = (await iterationApi.list(projectId.value)).filter((i) => i.hidden !== 1 && i.status !== 'CLOSED')
  }
  batchDialog.value = kind
}

async function submitBatch() {
  const ids = selection.value.map((w) => w.id)
  const kind = batchDialog.value
  let results: BatchItemResult[]
  if (kind === 'transition') {
    if (!batchForm.value.toStatus) return ElMessage.warning('请选择目标状态')
    results = await batchApi.execute({ ids, action: 'TRANSITION', toStatus: batchForm.value.toStatus, reason: batchForm.value.reason || undefined })
  } else if (kind === 'owner') {
    if (!batchForm.value.ownerId) return ElMessage.warning('请填写责任人ID')
    results = await batchApi.execute({ ids, action: 'UPDATE', patch: { ownerId: batchForm.value.ownerId } })
  } else if (kind === 'priority') {
    results = await batchApi.execute({ ids, action: 'UPDATE', patch: { priority: batchForm.value.priority } })
  } else {
    if (!batchForm.value.iterationId) return ElMessage.warning('请选择迭代')
    results = await batchApi.execute({ ids, action: 'ASSIGN_ITERATION', iterationId: batchForm.value.iterationId })
  }
  batchDialog.value = ''
  batchResult.value = results
  const failed = results.filter((r) => !r.ok)
  if (failed.length) {
    batchResultVisible.value = true
  } else {
    ElMessage.success(`批量操作成功（${results.length} 条）`)
  }
  await loadList()
}

watch([projectId, typeFilter], loadList)

onMounted(async () => {
  users.load()
  types.value = await metaApi.workItemTypes()
})
</script>

<template>
  <div>
    <!-- 第一行：项目 chips 独占；第二行：类型筛选 + 操作 -->
    <ProjectChips v-model="projectId" class="proj-row" />
    <div class="toolbar">
      <el-radio-group v-model="typeFilter">
        <el-radio-button label="">全部</el-radio-button>
        <el-radio-button v-for="t in types" :key="t.value" :label="t.value">{{ t.label }}</el-radio-button>
      </el-radio-group>
      <div class="ops">
        <el-button @click="importVisible = true"><el-icon><Upload /></el-icon>导入 CSV</el-button>
        <el-button type="primary" @click="createVisible = true"><el-icon><Plus /></el-icon>新建工作项</el-button>
      </div>
    </div>

    <!-- CSV 导入 -->
    <el-dialog v-model="importVisible" title="CSV 批量导入工作项" width="520px">
      <p class="hint">列顺序：<b>类型,标题</b>,描述,优先级,验收条件,估算（前两列必填；类型可写枚举名或中文如"需求/任务/缺陷"；UTF-8 编码，首行表头自动跳过）。</p>
      <input type="file" accept=".csv,text/csv" @change="onImportFile" />
      <div v-if="importResult" class="imp-result">
        <el-alert :type="importResult.errors.length ? 'warning' : 'success'" :closable="false" show-icon>
          成功导入 {{ importResult.created }} 条{{ importResult.errors.length ? `，失败 ${importResult.errors.length} 条` : '' }}
        </el-alert>
        <ul v-if="importResult.errors.length" class="imp-errors">
          <li v-for="(e, i) in importResult.errors" :key="i">{{ e }}</li>
        </ul>
      </div>
      <template #footer>
        <el-button @click="importVisible = false">关闭</el-button>
        <el-button type="primary" @click="submitImport">导入</el-button>
      </template>
    </el-dialog>

    <!-- 批量操作条：选中 >0 时浮现 -->
    <div v-if="selection.length" class="batch-bar">
      <span class="batch-count">已选 {{ selection.length }} 项</span>
      <el-button size="small" type="primary" plain @click="openBatch('transition')">流转到…</el-button>
      <el-button size="small" plain @click="openBatch('owner')">改责任人</el-button>
      <el-button size="small" plain @click="openBatch('priority')">改优先级</el-button>
      <el-button size="small" plain @click="openBatch('iteration')">进迭代</el-button>
    </div>

    <el-table :data="list" v-loading="loading" border stripe @row-click="openDetail" class="clickable"
      @selection-change="onSelectionChange">
      <el-table-column type="selection" width="42" />
      <el-table-column prop="code" label="编号" width="140" />
      <el-table-column label="类型" width="100">
        <template #default="{ row }"><el-tag size="small">{{ typeLabel(row.type) }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="title" label="标题" show-overflow-tooltip />
      <el-table-column label="状态" width="130">
        <template #default="{ row }"><el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status, row.type) }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="priority" label="优先级" width="80" />
      <el-table-column label="责任人" width="100">
        <template #default="{ row }">{{ users.label(row.ownerId) }}</template>
      </el-table-column>
    </el-table>

    <WorkItemDrawer v-model="drawerVisible" :item-id="currentId" @changed="loadList" />

    <!-- 批量操作对话框 -->
    <el-dialog :model-value="batchDialog === 'transition'" title="批量流转" width="440px" @close="batchDialog = ''">
      <el-form label-width="88px">
        <el-form-item label="目标状态">
          <el-select v-model="batchForm.toStatus" style="width:100%" placeholder="选择状态">
            <el-option v-for="s in batchStatusOptions" :key="s" :label="batchStatusLabel(s)" :value="s" />
          </el-select>
        </el-form-item>
        <el-form-item label="理由">
          <el-input v-model="batchForm.reason" type="textarea" :rows="2" placeholder="回退时必填，其余可选" />
        </el-form-item>
      </el-form>
      <p class="hint">逐条走状态机与守卫，不满足条件的项会失败并逐条列出。</p>
      <template #footer>
        <el-button @click="batchDialog = ''">取消</el-button>
        <el-button type="primary" @click="submitBatch">执行（{{ selection.length }} 条）</el-button>
      </template>
    </el-dialog>
    <el-dialog :model-value="batchDialog === 'owner'" title="批量改责任人" width="380px" @close="batchDialog = ''">
      <el-form label-width="88px">
        <el-form-item label="责任人"><UserSelect v-model="batchForm.ownerId" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchDialog = ''">取消</el-button>
        <el-button type="primary" @click="submitBatch">执行（{{ selection.length }} 条）</el-button>
      </template>
    </el-dialog>
    <el-dialog :model-value="batchDialog === 'priority'" title="批量改优先级" width="380px" @close="batchDialog = ''">
      <el-form label-width="88px">
        <el-form-item label="优先级">
          <el-select v-model="batchForm.priority" style="width:100%">
            <el-option v-for="p in ['P0','P1','P2','P3']" :key="p" :label="p" :value="p" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="batchDialog = ''">取消</el-button>
        <el-button type="primary" @click="submitBatch">执行（{{ selection.length }} 条）</el-button>
      </template>
    </el-dialog>
    <el-dialog :model-value="batchDialog === 'iteration'" title="批量进迭代" width="420px" @close="batchDialog = ''">
      <el-form label-width="88px">
        <el-form-item label="迭代">
          <el-select v-model="batchForm.iterationId" style="width:100%" placeholder="选择迭代">
            <el-option v-for="i in iterations" :key="i.id" :label="`${i.code} ${i.name}`" :value="i.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="hint">进迭代逐条走 Ready 守卫（验收条件/责任人/估算齐备才放行）。</p>
      <template #footer>
        <el-button @click="batchDialog = ''">取消</el-button>
        <el-button type="primary" @click="submitBatch">执行（{{ selection.length }} 条）</el-button>
      </template>
    </el-dialog>
    <!-- 批量结果（含失败明细） -->
    <el-dialog v-model="batchResultVisible" title="批量操作结果" width="560px">
      <template v-if="batchResult">
        <el-alert :closable="false" show-icon type="warning"
          :title="`成功 ${batchResult.filter(r => r.ok).length} 条，失败 ${batchResult.filter(r => !r.ok).length} 条`" />
        <el-table :data="batchResult.filter(r => !r.ok)" size="small" border style="margin-top:10px">
          <el-table-column prop="code" label="编号" width="140" />
          <el-table-column prop="message" label="失败原因" show-overflow-tooltip />
        </el-table>
      </template>
      <template #footer>
        <el-button type="primary" @click="batchResultVisible = false">知道了</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createVisible" title="新建工作项" width="440px">
      <el-form label-width="72px">
        <el-form-item label="类型">
          <el-select v-model="createForm.type" style="width:100%">
            <el-option v-for="t in types" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="createForm.title" placeholder="工作项标题" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" @click="submitCreate">创建并打开</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.proj-row { margin-bottom: 12px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.ops { display: flex; gap: 10px; flex-shrink: 0; }
.clickable :deep(.el-table__row) { cursor: pointer; }
.hint { color: #909399; font-size: 12px; margin: 0 0 10px; }
.imp-result { margin-top: 12px; }
.imp-errors { color: #e6a23c; font-size: 12px; margin: 8px 0 0; padding-left: 18px; }
.batch-bar { display: flex; align-items: center; gap: 10px; background: #ecf5ff; border: 1px solid #d9ecff; border-radius: 6px; padding: 8px 14px; margin-bottom: 10px; }
.batch-count { font-size: 13px; color: #409eff; font-weight: 600; }
</style>
