<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '@/api/http'
import { useAuthStore } from '@/stores/auth'
import { versionApi, stageApi, criterionApi, type ProductVersion, type StageGate, type GateCriterion } from '@/api/catalog'

interface Project {
  id: number
  code: string
  name: string
  goal: string
  lifecycleStatus: string
  createdAt: string
}

const auth = useAuthStore()
const list = ref<Project[]>([])
const loading = ref(false)
const dialogVisible = ref(false)
const form = reactive({ code: '', name: '', goal: '' })

// 管理抽屉
const manageVisible = ref(false)
const current = ref<Project | null>(null)
const activeTab = ref('version')
const versions = ref<ProductVersion[]>([])
const stages = ref<StageGate[]>([])
const criteria = ref<GateCriterion[]>([])
const vForm = reactive({ model: '', versionNo: '', baseline: '' })
const sForm = reactive({ stageName: '', gateName: '', seq: 1 })
const cForm = reactive({ domain: '质量', criterion: '', evidenceReq: '', isRedline: false })
const DOMAINS = ['技术', '质量', '供应', '制造', '商业', '上市']

async function load() {
  loading.value = true
  try {
    list.value = await http.get<any, Project[]>('/projects')
  } finally {
    loading.value = false
  }
}

function openCreate() {
  form.code = ''; form.name = ''; form.goal = ''
  dialogVisible.value = true
}

async function submit() {
  if (!form.code || !form.name) {
    ElMessage.warning('项目代码和名称必填')
    return
  }
  await http.post('/projects', { ...form })
  ElMessage.success('创建成功')
  dialogVisible.value = false
  await load()
}

async function openManage(row: Project) {
  current.value = row
  activeTab.value = 'version'
  manageVisible.value = true
  await refreshCatalog()
}

async function refreshCatalog() {
  if (!current.value) return
  const pid = current.value.id
  ;[versions.value, stages.value, criteria.value] = await Promise.all([
    versionApi.list(pid), stageApi.list(pid), criterionApi.list(pid),
  ])
}

async function addVersion() {
  if (!current.value || !vForm.versionNo) return ElMessage.warning('版本号必填')
  await versionApi.create({ projectId: current.value.id, ...vForm })
  ElMessage.success('已新增版本')
  vForm.model = ''; vForm.versionNo = ''; vForm.baseline = ''
  await refreshCatalog()
}

async function addStage() {
  if (!current.value || !sForm.stageName || !sForm.gateName) return ElMessage.warning('阶段名和DCP名必填')
  await stageApi.create({ projectId: current.value.id, ...sForm })
  ElMessage.success('已新增阶段/DCP')
  sForm.stageName = ''; sForm.gateName = ''
  await refreshCatalog()
}

async function addCriterion() {
  if (!current.value || !cForm.criterion) return ElMessage.warning('准入条件必填')
  await criterionApi.create({
    projectId: current.value.id,
    domain: cForm.domain,
    criterion: cForm.criterion,
    evidenceReq: cForm.evidenceReq,
    isRedline: cForm.isRedline ? 1 : 0,
    stageGateId: stages.value[0]?.id,
  })
  ElMessage.success('已新增准入条件')
  cForm.criterion = ''; cForm.evidenceReq = ''; cForm.isRedline = false
  await refreshCatalog()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="toolbar">
      <span class="hint">项目、产品版本与阶段（对应剧本第 1~3 步）。点击「管理」维护版本与阶段/DCP。</span>
      <el-button v-if="auth.hasRole('PM')" type="primary" @click="openCreate"><el-icon><Plus /></el-icon>新建项目</el-button>
    </div>

    <el-table :data="list" v-loading="loading" border stripe>
      <el-table-column prop="code" label="编号" width="110" />
      <el-table-column prop="name" label="项目名称" width="180" />
      <el-table-column prop="goal" label="商业目标" show-overflow-tooltip />
      <el-table-column prop="lifecycleStatus" label="生命周期" width="110">
        <template #default="{ row }"><el-tag size="small">{{ row.lifecycleStatus }}</el-tag></template>
      </el-table-column>
      <el-table-column label="操作" width="90">
        <template #default="{ row }">
          <el-button link type="primary" @click="openManage(row)">管理</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 新建项目 -->
    <el-dialog v-model="dialogVisible" title="新建项目" width="480px">
      <el-form label-position="top">
        <el-form-item label="项目代码（编号前缀，如 OVN1）" required><el-input v-model="form.code" placeholder="OVN1" /></el-form-item>
        <el-form-item label="项目名称" required><el-input v-model="form.name" placeholder="智能烤箱V1" /></el-form-item>
        <el-form-item label="商业目标"><el-input v-model="form.goal" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submit">创建</el-button>
      </template>
    </el-dialog>

    <!-- 管理抽屉 -->
    <el-drawer v-model="manageVisible" :size="640" :title="current ? `${current.code} · ${current.name}` : ''">
      <el-tabs v-model="activeTab">
        <el-tab-pane :label="`产品版本 (${versions.length})`" name="version">
          <div class="add-row">
            <el-input v-model="vForm.model" placeholder="型号" size="small" style="width:120px" />
            <el-input v-model="vForm.versionNo" placeholder="版本号 V1.0" size="small" style="width:120px" />
            <el-input v-model="vForm.baseline" placeholder="基线" size="small" style="width:100px" />
            <el-button size="small" type="primary" @click="addVersion">新增版本</el-button>
          </div>
          <el-table :data="versions" size="small" border style="margin-top:10px">
            <el-table-column prop="code" label="编号" width="120" />
            <el-table-column prop="model" label="型号" />
            <el-table-column prop="versionNo" label="版本号" />
            <el-table-column prop="baseline" label="基线" />
          </el-table>
        </el-tab-pane>

        <el-tab-pane :label="`阶段/DCP (${stages.length})`" name="stage">
          <div class="add-row">
            <el-input v-model="sForm.stageName" placeholder="阶段名" size="small" style="width:130px" />
            <el-input v-model="sForm.gateName" placeholder="DCP名" size="small" style="width:110px" />
            <el-input-number v-model="sForm.seq" :min="1" size="small" style="width:90px" />
            <el-button size="small" type="primary" @click="addStage">新增阶段</el-button>
          </div>
          <el-table :data="stages" size="small" border style="margin:10px 0">
            <el-table-column prop="code" label="编号" width="120" />
            <el-table-column prop="stageName" label="阶段" />
            <el-table-column prop="gateName" label="DCP" />
            <el-table-column prop="seq" label="次序" width="60" />
          </el-table>

          <el-divider content-position="left">DCP 准入条件（占位录入，完整评审在 DCP 页）</el-divider>
          <div class="add-row">
            <el-select v-model="cForm.domain" size="small" style="width:90px">
              <el-option v-for="d in DOMAINS" :key="d" :label="d" :value="d" />
            </el-select>
            <el-input v-model="cForm.criterion" placeholder="准入条件描述" size="small" style="width:200px" />
            <el-checkbox v-model="cForm.isRedline" size="small">红线</el-checkbox>
            <el-button size="small" type="primary" @click="addCriterion">新增条件</el-button>
          </div>
          <el-table :data="criteria" size="small" border style="margin-top:10px">
            <el-table-column prop="code" label="编号" width="120" />
            <el-table-column prop="domain" label="领域" width="70" />
            <el-table-column prop="criterion" label="准入条件" show-overflow-tooltip />
            <el-table-column label="红线" width="60">
              <template #default="{ row }"><el-tag v-if="row.isRedline" type="danger" size="small">红线</el-tag></template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="100" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </el-drawer>
  </div>
</template>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.hint { color: #909399; font-size: 13px; }
.add-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
</style>
