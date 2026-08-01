<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  workItemApi, traceApi, metaApi,
  type WorkItem, type TraceView, type AuditEvent, type StatusLog,
} from '@/api/workitem'

const props = defineProps<{ modelValue: boolean; itemId: number | null }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  changed: []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const TYPE_LABEL: Record<string, string> = {
  CAPABILITY: '产品能力', REQUIREMENT: '需求', STORY: '用户故事',
  TASK: '任务', DEFECT: '缺陷', RISK: '风险', CHANGE: '变更',
}

const item = ref<WorkItem | null>(null)
const loading = ref(false)
const activeTab = ref('basic')

const nextStatuses = ref<string[]>([])
const traces = ref<TraceView[]>([])
const audits = ref<AuditEvent[]>([])
const history = ref<StatusLog[]>([])
const relations = ref<string[]>([])

// 关联表单
const traceForm = ref({ relation: '', targetId: undefined as number | undefined })

async function loadAll(id: number) {
  loading.value = true
  try {
    item.value = await workItemApi.get(id)
    const [ns, tr, au, hi] = await Promise.all([
      workItemApi.nextStatuses(id),
      workItemApi.traces(id),
      workItemApi.audit(id),
      workItemApi.statusHistory(id),
    ])
    nextStatuses.value = ns
    traces.value = tr
    audits.value = au
    history.value = hi
  } finally {
    loading.value = false
  }
}

watch(
  () => [props.modelValue, props.itemId] as const,
  async ([open, id]) => {
    if (open && id) {
      activeTab.value = 'basic'
      if (!relations.value.length) {
        relations.value = await metaApi.traceRelations()
      }
      await loadAll(id)
    }
  },
  { immediate: true },
)

async function saveBasic() {
  if (!item.value) return
  await workItemApi.update(item.value.id, {
    title: item.value.title,
    description: item.value.description,
    ownerId: item.value.ownerId,
    priority: item.value.priority,
    acceptanceCriteria: item.value.acceptanceCriteria,
    estimate: item.value.estimate,
    productVersionId: item.value.productVersionId,
    baselineDate: item.value.baselineDate,
    forecastDate: item.value.forecastDate,
  })
  ElMessage.success('已保存')
  await loadAll(item.value.id)
  emit('changed')
}

async function doTransition(to: string) {
  if (!item.value) return
  let reason: string | undefined
  try {
    // 回退（目标已在历史里出现过）时要求填理由；后端也会强制校验
    const seenBefore = history.value.some((h) => h.toStatus === to)
    if (seenBefore) {
      const r = await ElMessageBox.prompt(`将状态回退到「${to}」，请填写理由：`, '状态回退', {
        confirmButtonText: '确认回退',
        cancelButtonText: '取消',
      })
      reason = r.value
    }
  } catch {
    return // 取消
  }
  try {
    await workItemApi.transition(item.value.id, to, reason)
    ElMessage.success(`状态已更新为 ${to}`)
    await loadAll(item.value.id)
    emit('changed')
  } catch {
    // 守卫失败等错误已由 http 拦截器提示
  }
}

async function addTrace() {
  if (!item.value || !traceForm.value.relation || !traceForm.value.targetId) {
    ElMessage.warning('请选择关系类型并填写目标工作项 ID')
    return
  }
  await traceApi.create({
    projectId: item.value.projectId,
    sourceType: 'WORK_ITEM',
    sourceId: item.value.id,
    targetType: 'WORK_ITEM',
    targetId: traceForm.value.targetId,
    relation: traceForm.value.relation,
  })
  ElMessage.success('已建立追溯关系')
  traceForm.value = { relation: '', targetId: undefined }
  traces.value = await workItemApi.traces(item.value.id)
  emit('changed')
}

async function removeTrace(linkId: number) {
  await traceApi.delete(linkId)
  if (item.value) traces.value = await workItemApi.traces(item.value.id)
  ElMessage.success('已删除')
}

const statusType = (s: string) => {
  if (['Accepted', 'Closed', 'Verified', 'Approved'].includes(s)) return 'success'
  if (['Verification', 'Retesting', 'Impact Analysed'].includes(s)) return 'warning'
  if (['Rejected'].includes(s)) return 'danger'
  return 'info'
}
</script>

<template>
  <el-drawer v-model="visible" :size="620" :title="item ? `${item.code} · ${TYPE_LABEL[item.type] || item.type}` : '工作项'">
    <div v-loading="loading">
      <template v-if="item">
        <div class="head">
          <el-tag :type="statusType(item.status)" size="large">{{ item.status }}</el-tag>
          <div class="status-actions">
            <span class="label">流转到：</span>
            <el-button
              v-for="s in nextStatuses"
              :key="s"
              size="small"
              type="primary"
              plain
              @click="doTransition(s)"
            >{{ s }}</el-button>
            <span v-if="!nextStatuses.length" class="terminal">（终态）</span>
          </div>
        </div>

        <el-tabs v-model="activeTab">
          <!-- 基本信息 -->
          <el-tab-pane label="基本信息" name="basic">
            <el-form label-width="88px" label-position="right">
              <el-form-item label="标题"><el-input v-model="item.title" /></el-form-item>
              <el-form-item label="说明"><el-input v-model="item.description" type="textarea" :rows="2" /></el-form-item>
              <el-form-item label="验收条件"><el-input v-model="item.acceptanceCriteria" type="textarea" :rows="2" placeholder="进入 Ready 的前提" /></el-form-item>
              <el-row :gutter="12">
                <el-col :span="12"><el-form-item label="责任人ID"><el-input v-model.number="item.ownerId" placeholder="用户ID" /></el-form-item></el-col>
                <el-col :span="12"><el-form-item label="估算"><el-input v-model="item.estimate" placeholder="如 3" /></el-form-item></el-col>
              </el-row>
              <el-row :gutter="12">
                <el-col :span="12">
                  <el-form-item label="优先级">
                    <el-select v-model="item.priority" placeholder="选择" clearable style="width:100%">
                      <el-option v-for="p in ['P0','P1','P2','P3']" :key="p" :label="p" :value="p" />
                    </el-select>
                  </el-form-item>
                </el-col>
                <el-col :span="12"><el-form-item label="版本ID"><el-input v-model.number="item.productVersionId" placeholder="进 Verification 需版本或证据" /></el-form-item></el-col>
              </el-row>
              <el-row :gutter="12">
                <el-col :span="12"><el-form-item label="基线日期"><el-date-picker v-model="item.baselineDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
                <el-col :span="12"><el-form-item label="预测日期"><el-date-picker v-model="item.forecastDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item></el-col>
              </el-row>
              <el-form-item v-if="item.acceptedBy" label="验收">
                <span class="accepted">验收人 #{{ item.acceptedBy }} · {{ item.acceptedAt }}</span>
              </el-form-item>
              <el-button type="primary" @click="saveBasic">保存</el-button>
            </el-form>
          </el-tab-pane>

          <!-- 关联追溯 -->
          <el-tab-pane :label="`关联 (${traces.length})`" name="trace">
            <div class="add-trace">
              <el-select v-model="traceForm.relation" placeholder="关系类型" size="small" style="width:150px">
                <el-option v-for="r in relations" :key="r" :label="r" :value="r" />
              </el-select>
              <el-input v-model.number="traceForm.targetId" placeholder="目标工作项ID" size="small" style="width:150px" />
              <el-button size="small" type="primary" @click="addTrace">建立关联</el-button>
            </div>
            <el-table :data="traces" size="small" border style="margin-top:12px">
              <el-table-column label="方向" width="70">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.direction === 'OUT' ? '' : 'info'">{{ row.direction === 'OUT' ? '出' : '入' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="relation" label="关系" width="130" />
              <el-table-column label="对象">
                <template #default="{ row }">{{ row.otherCode }} {{ row.otherTitle }}</template>
              </el-table-column>
              <el-table-column label="操作" width="70">
                <template #default="{ row }">
                  <el-button link type="danger" size="small" @click="removeTrace(row.linkId)">删除</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <!-- 审计与时间线 -->
          <el-tab-pane label="审计与时间线" name="audit">
            <el-timeline>
              <el-timeline-item v-for="h in history" :key="'h'+h.id" :timestamp="h.at" placement="top" type="primary">
                <b>{{ h.fromStatus || '（新建）' }} → {{ h.toStatus }}</b>
                <span v-if="h.reason" class="reason">：{{ h.reason }}</span>
              </el-timeline-item>
            </el-timeline>
            <el-divider content-position="left">审计事件</el-divider>
            <el-table :data="audits" size="small" border>
              <el-table-column prop="action" label="动作" width="120" />
              <el-table-column prop="summary" label="摘要" show-overflow-tooltip />
              <el-table-column prop="at" label="时间" width="160" />
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </template>
    </div>
  </el-drawer>
</template>

<style scoped>
.head { display: flex; align-items: center; gap: 16px; margin-bottom: 12px; flex-wrap: wrap; }
.status-actions { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.status-actions .label { color: #909399; font-size: 13px; }
.terminal { color: #909399; font-size: 13px; }
.add-trace { display: flex; gap: 8px; align-items: center; }
.accepted { color: #67c23a; font-size: 13px; }
.reason { color: #909399; }
</style>
