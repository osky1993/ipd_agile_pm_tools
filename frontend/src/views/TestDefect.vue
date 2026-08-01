<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { testApi, type TestCase, type TestRun } from '@/api/agile'
import { versionApi, type ProductVersion } from '@/api/catalog'
import { changeApi, type ImpactItem } from '@/api/governance'
import { workItemApi, type WorkItem } from '@/api/workitem'
import WorkItemDrawer from '@/components/WorkItemDrawer.vue'
import ProjectChips from '@/components/ProjectChips.vue'
import { statusLabel, testResultLabel } from '@/utils/labels'

const projectId = ref<number | null>(null)
const activeTab = ref('cases')
const cases = ref<TestCase[]>([])
const defects = ref<WorkItem[]>([])
const changes = ref<WorkItem[]>([])
const requirements = ref<WorkItem[]>([])
const versions = ref<ProductVersion[]>([])
const runsMap = ref<Record<number, TestRun[]>>({})

const drawerVisible = ref(false)
const currentId = ref<number | null>(null)

// 变更影响分析 / 审批
const impactDialog = ref(false)
const impactItems = ref<ImpactItem[]>([])
const impactChangeId = ref<number | null>(null)
const decideDialog = ref(false)
const decideForm = reactive({ changeId: 0, approve: true, reason: '' })
const changeStatusType = (s: string) =>
  ['Approved', 'Verified', 'Implemented'].includes(s) ? 'success'
    : s === 'Rejected' ? 'danger' : s === 'Impact Analysed' ? 'warning' : 'info'

const caseDialog = ref(false)
const caseForm = reactive({ title: '', steps: '', expected: '', verifiesRequirementId: undefined as number | undefined })

const runDialog = ref(false)
const runForm = reactive({ testCaseId: 0, result: 'FAIL', actual: '', runVersionId: undefined as number | undefined, autoCreateDefect: true })

const statusType = (s: string) =>
  ['Closed'].includes(s) ? 'success' : ['Retesting'].includes(s) ? 'warning' : 'danger'
const resultType = (r: string) => (r === 'PASS' ? 'success' : r === 'FAIL' ? 'danger' : 'warning')

async function loadAll() {
  if (!projectId.value) return
  const pid = projectId.value
  ;[cases.value, defects.value, changes.value, requirements.value, versions.value] = await Promise.all([
    testApi.listCases(pid),
    workItemApi.list(pid, 'DEFECT'),
    workItemApi.list(pid, 'CHANGE'),
    workItemApi.list(pid, 'REQUIREMENT'),
    versionApi.list(pid),
  ])
  for (const c of cases.value) runsMap.value[c.id] = await testApi.listRuns(c.id)
}

// 新建变更：先填表单，确认才创建（不再点开即建）
const changeDialog = ref(false)
const changeForm = reactive({ title: '', description: '' })

function openCreateChange() {
  changeForm.title = ''
  changeForm.description = ''
  changeDialog.value = true
}

async function submitChange() {
  if (!projectId.value || !changeForm.title.trim()) return ElMessage.warning('请填写变更标题')
  const c = await workItemApi.create({
    projectId: projectId.value,
    type: 'CHANGE',
    title: changeForm.title.trim(),
    description: changeForm.description || undefined,
  })
  ElMessage.success(`已创建 ${c.code}，请在详情「关联」页建立"变更涉及"关系到受影响需求`)
  changeDialog.value = false
  await loadAll()
  currentId.value = c.id
  drawerVisible.value = true
}

async function runImpact(c: WorkItem) {
  const r = await changeApi.analyze(c.id)
  impactItems.value = r.items
  impactChangeId.value = c.id
  impactDialog.value = true
  await loadAll()
}

async function confirmImpact() {
  if (!impactChangeId.value) return
  try {
    await workItemApi.transition(impactChangeId.value, 'Impact Analysed')
    ElMessage.success('变更已进入 Impact Analysed')
    impactDialog.value = false
    await loadAll()
  } catch { /* 守卫已提示 */ }
}

function openDecide(c: WorkItem) {
  decideForm.changeId = c.id
  decideForm.approve = true
  decideForm.reason = ''
  decideDialog.value = true
}

async function submitDecide() {
  try {
    await changeApi.decide(decideForm.changeId, decideForm.approve, decideForm.reason)
    ElMessage.success(decideForm.approve ? '变更已批准' : '变更已否决')
    decideDialog.value = false
    await loadAll()
  } catch { /* 权限/状态错误已提示 */ }
}

async function submitCase() {
  if (!projectId.value || !caseForm.title) return ElMessage.warning('用例标题必填')
  await testApi.createCase(
    { projectId: projectId.value, title: caseForm.title, steps: caseForm.steps, expected: caseForm.expected },
    caseForm.verifiesRequirementId,
  )
  ElMessage.success('已创建用例')
  caseDialog.value = false
  caseForm.title = ''; caseForm.steps = ''; caseForm.expected = ''; caseForm.verifiesRequirementId = undefined
  await loadAll()
}

function openRun(c: TestCase) {
  runForm.testCaseId = c.id
  runForm.result = 'FAIL'; runForm.actual = ''; runForm.runVersionId = versions.value[0]?.id; runForm.autoCreateDefect = true
  runDialog.value = true
}

async function submitRun() {
  await testApi.execute(
    { testCaseId: runForm.testCaseId, result: runForm.result, actual: runForm.actual, runVersionId: runForm.runVersionId },
    runForm.result === 'FAIL' && runForm.autoCreateDefect,
  )
  ElMessage.success(runForm.result === 'FAIL' && runForm.autoCreateDefect ? '已记录失败并生成缺陷' : '已记录执行')
  runDialog.value = false
  activeTab.value = runForm.result === 'FAIL' && runForm.autoCreateDefect ? 'defects' : 'cases'
  await loadAll()
}

function openDefect(w: WorkItem) { currentId.value = w.id; drawerVisible.value = true }

</script>

<template>
  <div>
    <div class="toolbar">
      <ProjectChips v-model="projectId" @change="loadAll" />
    </div>

    <el-tabs v-model="activeTab">
      <el-tab-pane :label="`测试用例 (${cases.length})`" name="cases">
        <div class="sub-bar"><el-button type="primary" size="small" @click="caseDialog = true"><el-icon><Plus /></el-icon>新建用例</el-button></div>
        <el-table :data="cases" border>
          <el-table-column type="expand">
            <template #default="{ row }">
              <div class="runs">
                <div class="runs-title">执行记录</div>
                <el-table :data="runsMap[row.id]" size="small" border>
                  <el-table-column prop="code" label="编号" width="130" />
                  <el-table-column label="结果" width="90">
                    <template #default="{ row: r }"><el-tag size="small" :type="resultType(r.result)">{{ testResultLabel(r.result) }}</el-tag></template>
                  </el-table-column>
                  <el-table-column prop="actual" label="实际结果" show-overflow-tooltip />
                  <el-table-column label="生成缺陷" width="90">
                    <template #default="{ row: r }"><span v-if="r.defectId">#{{ r.defectId }}</span></template>
                  </el-table-column>
                  <el-table-column prop="runAt" label="时间" width="160" />
                </el-table>
                <div v-if="!runsMap[row.id]?.length" class="empty">暂无执行记录</div>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="code" label="编号" width="130" />
          <el-table-column prop="title" label="用例标题" show-overflow-tooltip />
          <el-table-column label="操作" width="90">
            <template #default="{ row }"><el-button link type="primary" @click="openRun(row)">执行</el-button></template>
          </el-table-column>
        </el-table>
      </el-tab-pane>

      <el-tab-pane :label="`缺陷 (${defects.length})`" name="defects">
        <el-table :data="defects" border @row-click="openDefect" class="clickable">
          <el-table-column prop="code" label="编号" width="140" />
          <el-table-column prop="title" label="缺陷标题" show-overflow-tooltip />
          <el-table-column label="状态" width="120">
            <template #default="{ row }"><el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column prop="ownerId" label="责任人" width="80" />
        </el-table>
        <p class="hint">点击缺陷行打开详情：可流转 Open→Analysing→Fixing→Retesting→Closed。关闭前需有复测通过（守卫#2）。</p>
      </el-tab-pane>

      <el-tab-pane :label="`变更 (${changes.length})`" name="changes">
        <div class="sub-bar"><el-button type="primary" size="small" @click="openCreateChange"><el-icon><Plus /></el-icon>新建变更</el-button></div>

        <!-- 新建变更表单（确认才创建） -->
        <el-dialog v-model="changeDialog" title="新建变更" width="480px">
          <el-form label-width="72px">
            <el-form-item label="标题" required><el-input v-model="changeForm.title" placeholder="如：集尘座风道重新设计以降低噪音" /></el-form-item>
            <el-form-item label="说明"><el-input v-model="changeForm.description" type="textarea" :rows="3" placeholder="变更背景与内容（可选）" /></el-form-item>
          </el-form>
          <template #footer>
            <el-button @click="changeDialog = false">取消</el-button>
            <el-button type="primary" @click="submitChange">创建并打开详情</el-button>
          </template>
        </el-dialog>
        <el-table :data="changes" border>
          <el-table-column prop="code" label="编号" width="140" />
          <el-table-column prop="title" label="变更标题" show-overflow-tooltip />
          <el-table-column label="状态" width="140">
            <template #default="{ row }"><el-tag size="small" :type="changeStatusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template>
          </el-table-column>
          <el-table-column label="操作" width="240">
            <template #default="{ row }">
              <el-button link type="primary" @click="openDefect(row)">详情</el-button>
              <el-button link type="primary" @click="runImpact(row)">影响分析</el-button>
              <el-button v-if="row.status === 'Impact Analysed'" link type="success" @click="openDecide(row)">审批</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p class="hint">流程：建变更 → 详情「关联」页建 changes 关系到受影响需求 → 影响分析（守卫#5）→ 授权角色审批（生成决策）。</p>
      </el-tab-pane>
    </el-tabs>

    <WorkItemDrawer v-model="drawerVisible" :item-id="currentId" @changed="loadAll" />

    <!-- 新建用例 -->
    <el-dialog v-model="caseDialog" title="新建测试用例" width="480px">
      <el-form label-width="90px">
        <el-form-item label="用例标题"><el-input v-model="caseForm.title" /></el-form-item>
        <el-form-item label="步骤"><el-input v-model="caseForm.steps" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="预期结果"><el-input v-model="caseForm.expected" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="验证需求">
          <el-select v-model="caseForm.verifiesRequirementId" placeholder="verifies 关联(可选)" clearable style="width:100%">
            <el-option v-for="r in requirements" :key="r.id" :label="`${r.code} ${r.title}`" :value="r.id" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="caseDialog = false">取消</el-button>
        <el-button type="primary" @click="submitCase">创建</el-button>
      </template>
    </el-dialog>

    <!-- 执行测试 -->
    <el-dialog v-model="runDialog" title="执行测试" width="440px">
      <el-form label-width="90px">
        <el-form-item label="结果">
          <el-radio-group v-model="runForm.result">
            <el-radio-button label="PASS">通过</el-radio-button>
            <el-radio-button label="FAIL">失败</el-radio-button>
            <el-radio-button label="BLOCKED">阻塞</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="实际结果"><el-input v-model="runForm.actual" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="执行版本">
          <el-select v-model="runForm.runVersionId" placeholder="选择版本" clearable style="width:100%">
            <el-option v-for="v in versions" :key="v.id" :label="`${v.code} ${v.versionNo}`" :value="v.id" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="runForm.result === 'FAIL'" label="失败处理">
          <el-checkbox v-model="runForm.autoCreateDefect">自动生成缺陷并关联需求/用例/版本</el-checkbox>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="runDialog = false">取消</el-button>
        <el-button type="primary" @click="submitRun">提交执行</el-button>
      </template>
    </el-dialog>

    <!-- 影响分析结果 -->
    <el-dialog v-model="impactDialog" title="变更影响分析" width="560px">
      <el-alert type="info" :closable="false" show-icon class="mb">
        系统按追溯关系自动列出受影响对象。确认后变更进入 Impact Analysed（守卫#5 放行）。
      </el-alert>
      <el-table :data="impactItems" border size="small">
        <el-table-column prop="category" label="类别" width="90">
          <template #default="{ row }"><el-tag size="small">{{ row.category }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="code" label="编号" width="140" />
        <el-table-column prop="title" label="对象" show-overflow-tooltip />
        <el-table-column prop="via" label="来源" width="130" />
      </el-table>
      <div v-if="!impactItems.length" class="empty">未发现受影响对象，请先在详情「关联」页建立 changes 关系。</div>
      <template #footer>
        <el-button @click="impactDialog = false">关闭</el-button>
        <el-button type="primary" @click="confirmImpact">确认并进入影响分析</el-button>
      </template>
    </el-dialog>

    <!-- 审批决策 -->
    <el-dialog v-model="decideDialog" title="变更审批决策" width="440px">
      <el-form label-width="72px">
        <el-form-item label="决策">
          <el-radio-group v-model="decideForm.approve">
            <el-radio-button :value="true">批准</el-radio-button>
            <el-radio-button :value="false">否决</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="理由"><el-input v-model="decideForm.reason" type="textarea" :rows="3" /></el-form-item>
      </el-form>
      <p class="hint">决策由授权角色（REVIEWER/ADMIN）作出，生成不可无痕覆盖的决策记录。</p>
      <template #footer>
        <el-button @click="decideDialog = false">取消</el-button>
        <el-button type="primary" @click="submitDecide">提交决策</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { margin-bottom: 16px; }
.sub-bar { margin-bottom: 12px; }
.runs { padding: 8px 16px; }
.runs-title { font-size: 13px; color: #606266; margin-bottom: 8px; font-weight: 600; }
.empty { color: #c0c4cc; font-size: 12px; padding: 8px 0; }
.clickable :deep(.el-table__row) { cursor: pointer; }
.hint { color: #909399; font-size: 12px; margin-top: 10px; }
.mb { margin-bottom: 12px; }
</style>
