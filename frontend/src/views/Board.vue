<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { iterationApi, type Iteration } from '@/api/agile'
import { workItemApi, type WorkItem } from '@/api/workitem'
import WorkItemDrawer from '@/components/WorkItemDrawer.vue'
import ProjectChips from '@/components/ProjectChips.vue'

const COLUMNS = ['Backlog', 'Ready', 'In Progress', 'Verification', 'Accepted']
const GENERIC = ['CAPABILITY', 'REQUIREMENT', 'STORY', 'TASK']
const TYPE_LABEL: Record<string, string> = { CAPABILITY: '能力', REQUIREMENT: '需求', STORY: '故事', TASK: '任务' }
const TYPE_COLOR: Record<string, string> = { CAPABILITY: '', REQUIREMENT: 'success', STORY: 'warning', TASK: 'info' }

const projectId = ref<number | null>(null)
const sprints = ref<Iteration[]>([])
const sprintId = ref<number | null>(null)
const sprintItems = ref<WorkItem[]>([])
const backlog = ref<WorkItem[]>([])
const loading = ref(false)

const drawerVisible = ref(false)
const currentId = ref<number | null>(null)
const dragId = ref<number | null>(null)

const createSprint = ref(false)
const sForm = reactive({ name: '', goal: '', startDate: '', endDate: '' })

const showHidden = ref(false)
const currentSprint = computed(() => sprints.value.find((s) => s.id === sprintId.value))
/** 后端已按时间倒序返回；默认不展示已隐藏的 */
const visibleSprints = computed(() => sprints.value.filter((s) => showHidden.value || !s.hidden))
const hiddenCount = computed(() => sprints.value.filter((s) => s.hidden).length)

function ageDays(w: WorkItem): number {
  if (!w.createdAt) return 0
  const ms = Date.now() - new Date(w.createdAt).getTime()
  return Math.max(0, Math.floor(ms / 86400000))
}
function columnItems(status: string): WorkItem[] {
  return sprintItems.value.filter((w) => GENERIC.includes(w.type) && w.status === status)
}

async function loadSprints() {
  if (!projectId.value) return
  sprints.value = await iterationApi.list(projectId.value)
  // 当前选中项不在可见列表时（切项目/被隐藏），自动切到最新的可见迭代
  if (!visibleSprints.value.find((s) => s.id === sprintId.value)) {
    sprintId.value = visibleSprints.value.length ? visibleSprints.value[0].id : null
  }
  await loadBoard()
}

function selectSprint(id: number) {
  if (sprintId.value === id) return
  sprintId.value = id
  loadBoard()
}

async function toggleHidden(s: Iteration) {
  await iterationApi.update(s.id, { hidden: s.hidden ? 0 : 1 })
  ElMessage.success(s.hidden ? `已恢复展示 ${s.name}` : `已隐藏 ${s.name}`)
  await loadSprints()
}

async function loadBoard() {
  if (!projectId.value) return
  loading.value = true
  try {
    const all = await workItemApi.list(projectId.value)
    backlog.value = all.filter((w) => GENERIC.includes(w.type) && !w.iterationId && w.status === 'Backlog')
    sprintItems.value = sprintId.value ? await iterationApi.items(sprintId.value) : []
  } finally {
    loading.value = false
  }
}

function onDragStart(w: WorkItem) { dragId.value = w.id }

async function onDrop(status: string) {
  const id = dragId.value
  dragId.value = null
  if (!id) return
  const item = sprintItems.value.find((w) => w.id === id)
  if (!item || item.status === status) return

  // 回退（目标状态在链条上更靠前）需填写理由，与详情抽屉一致；后端也会强制校验
  let reason: string | undefined
  const fromIdx = COLUMNS.indexOf(item.status)
  const toIdx = COLUMNS.indexOf(status)
  if (fromIdx >= 0 && toIdx >= 0 && toIdx < fromIdx) {
    try {
      const r = await ElMessageBox.prompt(
        `将「${item.code}」从 ${item.status} 回退到 ${status}，请填写理由：`,
        '状态回退',
        { confirmButtonText: '确认回退', cancelButtonText: '取消', inputValidator: (v) => (v && v.trim() ? true : '理由不能为空') },
      )
      reason = r.value
    } catch {
      return // 取消回退，卡片留在原列
    }
  }

  try {
    await workItemApi.transition(id, status, reason)
    ElMessage.success(`已流转到 ${status}`)
    await loadBoard()
  } catch {
    await loadBoard() // 守卫/非法迁移失败：拦截器已提示，回退看板
  }
}

async function pullIn(w: WorkItem) {
  if (!sprintId.value) return ElMessage.warning('请先选择迭代')
  try {
    await iterationApi.assign(sprintId.value, w.id)
    ElMessage.success('已拉入迭代')
    await loadBoard()
  } catch { /* 守卫已提示 */ }
}

async function submitSprint() {
  if (!projectId.value || !sForm.name) return ElMessage.warning('请填写迭代名')
  await iterationApi.create({ projectId: projectId.value, ...sForm })
  ElMessage.success('已创建迭代')
  createSprint.value = false
  sForm.name = ''; sForm.goal = ''; sForm.startDate = ''; sForm.endDate = ''
  await loadSprints()
}

function openDetail(w: WorkItem) { currentId.value = w.id; drawerVisible.value = true }

</script>

<template>
  <div>
    <ProjectChips v-model="projectId" class="proj-row" @change="loadSprints" />
    <div class="toolbar">
      <div class="toolbar-right">
        <el-checkbox v-if="hiddenCount" v-model="showHidden">显示已隐藏（{{ hiddenCount }}）</el-checkbox>
        <el-button type="primary" @click="createSprint = true"><el-icon><Plus /></el-icon>新建迭代</el-button>
      </div>
    </div>

    <!-- Sprint 按时间倒序平铺展示（最新在前），点击切换看板；可隐藏/恢复 -->
    <div class="sprint-strip">
      <div
        v-for="s in visibleSprints"
        :key="s.id"
        class="sprint-chip"
        :class="{ active: s.id === sprintId, dimmed: s.hidden }"
        @click="selectSprint(s.id)"
      >
        <div class="s-line">
          <span class="s-name">{{ s.name }}</span>
          <el-tag size="small" :type="s.status === 'ACTIVE' ? 'success' : s.status === 'DONE' ? 'info' : 'warning'">{{ s.status }}</el-tag>
        </div>
        <div class="s-range">{{ s.startDate || '未定' }} ~ {{ s.endDate || '未定' }}</div>
        <el-button link size="small" class="s-hide" @click.stop="toggleHidden(s)">
          {{ s.hidden ? '恢复展示' : '隐藏' }}
        </el-button>
      </div>
      <div v-if="!visibleSprints.length" class="empty">暂无迭代，请先新建</div>
    </div>
    <p v-if="currentSprint?.goal" class="goal">🎯 {{ currentSprint.goal }}</p>

    <div class="board-wrap" v-loading="loading">
      <!-- Backlog 未排期 -->
      <div class="backlog">
        <div class="col-head">产品 Backlog（未排期）<el-tag size="small" round>{{ backlog.length }}</el-tag></div>
        <div class="cards">
          <el-card v-for="w in backlog" :key="w.id" shadow="hover" class="card" body-class="card-body">
            <div class="card-top">
              <el-tag size="small" :type="TYPE_COLOR[w.type]">{{ TYPE_LABEL[w.type] }}</el-tag>
              <span class="code">{{ w.code }}</span>
            </div>
            <div class="card-title" @click="openDetail(w)">{{ w.title }}</div>
            <el-button size="small" type="primary" plain class="pull" @click="pullIn(w)">拉入迭代 →</el-button>
          </el-card>
          <div v-if="!backlog.length" class="empty">无未排期项</div>
        </div>
      </div>

      <!-- 状态泳道 -->
      <div
        v-for="col in COLUMNS"
        :key="col"
        class="column"
        @dragover.prevent
        @drop="onDrop(col)"
      >
        <div class="col-head">{{ col }}<el-tag size="small" round>{{ columnItems(col).length }}</el-tag></div>
        <div class="cards">
          <el-card
            v-for="w in columnItems(col)"
            :key="w.id"
            shadow="hover"
            class="card"
            body-class="card-body"
            draggable="true"
            @dragstart="onDragStart(w)"
          >
            <div class="card-top">
              <el-tag size="small" :type="TYPE_COLOR[w.type]">{{ TYPE_LABEL[w.type] }}</el-tag>
              <span class="code">{{ w.code }}</span>
              <el-tag v-if="w.status !== 'Accepted'" size="small" type="info" class="age">{{ ageDays(w) }}d</el-tag>
            </div>
            <div class="card-title" @click="openDetail(w)">{{ w.title }}</div>
            <div class="card-foot">
              <span v-if="w.priority" class="prio">{{ w.priority }}</span>
              <span v-if="w.ownerId" class="owner">#{{ w.ownerId }}</span>
            </div>
          </el-card>
        </div>
      </div>
    </div>
    <p class="hint">拖动卡片到目标状态列即触发流转（走状态机与守卫校验）；点击标题打开详情。</p>

    <WorkItemDrawer v-model="drawerVisible" :item-id="currentId" @changed="loadBoard" />

    <el-dialog v-model="createSprint" title="新建迭代" width="440px">
      <el-form label-width="72px">
        <el-form-item label="名称"><el-input v-model="sForm.name" placeholder="Sprint-1" /></el-form-item>
        <el-form-item label="目标"><el-input v-model="sForm.goal" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="起止">
          <el-date-picker v-model="sForm.startDate" type="date" value-format="YYYY-MM-DD" placeholder="开始" style="width:48%" />
          <el-date-picker v-model="sForm.endDate" type="date" value-format="YYYY-MM-DD" placeholder="结束" style="width:48%;margin-left:4%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createSprint = false">取消</el-button>
        <el-button type="primary" @click="submitSprint">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.proj-row { margin-bottom: 12px; }
.toolbar { display: flex; justify-content: flex-end; align-items: center; gap: 12px; margin-bottom: 14px; }
.toolbar-right { display: flex; gap: 12px; align-items: center; flex-shrink: 0; }
.goal { color: #909399; font-size: 13px; margin: 0 0 12px; }
.sprint-strip { display: flex; gap: 10px; flex-wrap: wrap; margin-bottom: 10px; }
.sprint-chip { position: relative; min-width: 168px; padding: 8px 10px 6px; border: 1px solid #dcdfe6; border-radius: 8px; cursor: pointer; background: #fff; transition: all .15s; }
.sprint-chip:hover { border-color: #409eff; }
.sprint-chip.active { border-color: #409eff; background: #ecf5ff; box-shadow: 0 0 0 1px #409eff inset; }
.sprint-chip.dimmed { opacity: .55; background: #f5f7fa; }
.s-line { display: flex; align-items: center; gap: 8px; }
.s-name { font-weight: 600; font-size: 13px; }
.s-range { font-size: 12px; color: #909399; margin-top: 2px; }
.s-hide { position: absolute; right: 6px; bottom: 4px; font-size: 12px; }
.board-wrap { display: flex; gap: 10px; align-items: flex-start; overflow-x: auto; padding-bottom: 8px; }
.backlog, .column { flex: 1 0 200px; min-width: 200px; background: #f0f2f5; border-radius: 8px; padding: 8px; }
.backlog { background: #eef1f6; }
.col-head { font-weight: 600; font-size: 13px; padding: 4px 6px 10px; display: flex; justify-content: space-between; align-items: center; }
.cards { display: flex; flex-direction: column; gap: 8px; min-height: 60px; }
.card { cursor: grab; }
.card :deep(.card-body) { padding: 10px; }
.card-top { display: flex; align-items: center; gap: 6px; margin-bottom: 6px; }
.card-top .code { font-family: monospace; font-size: 12px; color: #909399; }
.card-top .age { margin-left: auto; }
.card-title { font-size: 13px; line-height: 1.4; cursor: pointer; }
.card-title:hover { color: #409eff; }
.card-foot { display: flex; gap: 8px; margin-top: 6px; font-size: 12px; color: #909399; }
.prio { color: #e6a23c; }
.pull { width: 100%; margin-top: 8px; }
.empty { color: #c0c4cc; font-size: 12px; text-align: center; padding: 12px; }
.hint { color: #909399; font-size: 12px; margin-top: 10px; }
</style>
