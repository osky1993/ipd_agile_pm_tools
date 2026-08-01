<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import http from '@/api/http'
import { workItemApi, metaApi, type WorkItem } from '@/api/workitem'
import WorkItemDrawer from '@/components/WorkItemDrawer.vue'

interface Project { id: number; code: string; name: string }

const projects = ref<Project[]>([])
const projectId = ref<number | null>(null)
const types = ref<{ value: string; abbr: string; label: string }[]>([])
const typeFilter = ref<string>('')
const list = ref<WorkItem[]>([])
const loading = ref(false)

const drawerVisible = ref(false)
const currentId = ref<number | null>(null)

const createVisible = ref(false)
const createForm = ref({ type: 'REQUIREMENT', title: '' })

const TYPE_LABEL: Record<string, string> = {
  CAPABILITY: '产品能力', REQUIREMENT: '需求', STORY: '用户故事',
  TASK: '任务', DEFECT: '缺陷', RISK: '风险', CHANGE: '变更',
}
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

function openDetail(row: WorkItem) {
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

watch([projectId, typeFilter], loadList)

onMounted(async () => {
  projects.value = await http.get<any, Project[]>('/projects')
  types.value = await metaApi.workItemTypes()
  if (projects.value.length) {
    projectId.value = projects.value[0].id
  }
})
</script>

<template>
  <div>
    <div class="toolbar">
      <div class="filters">
        <el-select v-model="projectId" placeholder="选择项目" style="width:200px">
          <el-option v-for="p in projects" :key="p.id" :label="`${p.code} ${p.name}`" :value="p.id" />
        </el-select>
        <el-radio-group v-model="typeFilter">
          <el-radio-button label="">全部</el-radio-button>
          <el-radio-button v-for="t in types" :key="t.value" :label="t.value">{{ t.label }}</el-radio-button>
        </el-radio-group>
      </div>
      <div>
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

    <el-table :data="list" v-loading="loading" border stripe @row-click="openDetail" class="clickable">
      <el-table-column prop="code" label="编号" width="140" />
      <el-table-column label="类型" width="100">
        <template #default="{ row }"><el-tag size="small">{{ TYPE_LABEL[row.type] }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="title" label="标题" show-overflow-tooltip />
      <el-table-column label="状态" width="130">
        <template #default="{ row }"><el-tag size="small" :type="statusType(row.status)">{{ row.status }}</el-tag></template>
      </el-table-column>
      <el-table-column prop="priority" label="优先级" width="80" />
      <el-table-column prop="ownerId" label="责任人" width="80" />
    </el-table>

    <WorkItemDrawer v-model="drawerVisible" :item-id="currentId" @changed="loadList" />

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
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.filters { display: flex; gap: 12px; align-items: center; }
.clickable :deep(.el-table__row) { cursor: pointer; }
.hint { color: #909399; font-size: 12px; margin: 0 0 10px; }
.imp-result { margin-top: 12px; }
.imp-errors { color: #e6a23c; font-size: 12px; margin: 8px 0 0; padding-left: 18px; }
</style>
