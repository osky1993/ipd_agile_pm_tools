<script setup lang="ts">
import { ref, watch, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  workItemApi, traceApi, metaApi,
  type WorkItem, type TraceView, type AuditEvent, type StatusLog,
} from '@/api/workitem'
import { versionApi, type ProductVersion } from '@/api/catalog'
import { evidenceApi, type Evidence } from '@/api/governance'
import { statusLabel, typeLabel, relationLabel } from '@/utils/labels'
import MarkdownView from '@/components/MarkdownView.vue'
import MarkdownEditor from '@/components/MarkdownEditor.vue'

const props = defineProps<{ modelValue: boolean; itemId: number | null }>()
const emit = defineEmits<{
  'update:modelValue': [v: boolean]
  changed: []
}>()

const visible = computed({
  get: () => props.modelValue,
  set: (v) => emit('update:modelValue', v),
})

const item = ref<WorkItem | null>(null)
const loading = ref(false)
const activeTab = ref('basic')
// 说明/验收条件默认只读渲染（Markdown），点「编辑」再切换编辑器，减少误触
const descEditing = ref(false)

const nextStatuses = ref<string[]>([])
const traces = ref<TraceView[]>([])
const audits = ref<AuditEvent[]>([])
const history = ref<StatusLog[]>([])
const relations = ref<string[]>([])

// 关联表单：关系类型中文化（labels.ts）；目标按关系类型切换数据源（工作项/版本/证据）下拉选择
const relLabel = relationLabel

const traceForm = ref({ relation: '', targetId: undefined as number | undefined })
const targetItems = ref<WorkItem[]>([])
const targetVersions = ref<ProductVersion[]>([])
const targetEvidences = ref<Evidence[]>([])

/** 目标对象类型由关系类型决定 */
const targetType = computed(() =>
  traceForm.value.relation === 'released_in' ? 'PRODUCT_VERSION'
    : traceForm.value.relation === 'evidences' ? 'EVIDENCE' : 'WORK_ITEM')

async function onRelationChange() {
  traceForm.value.targetId = undefined
  const pid = item.value?.projectId
  if (!pid) return
  if (targetType.value === 'PRODUCT_VERSION' && !targetVersions.value.length) {
    targetVersions.value = await versionApi.list(pid)
  } else if (targetType.value === 'EVIDENCE' && !targetEvidences.value.length) {
    targetEvidences.value = await evidenceApi.list(pid)
  } else if (targetType.value === 'WORK_ITEM' && !targetItems.value.length) {
    targetItems.value = await workItemApi.list(pid)
  }
}

async function loadAll(id: number) {
  loading.value = true
  try {
    const prevProject = item.value?.projectId
    item.value = await workItemApi.get(id)
    if (prevProject !== item.value.projectId) {
      // 换项目：清空目标候选缓存与表单，避免跨项目串数据
      targetItems.value = []
      targetVersions.value = []
      targetEvidences.value = []
      traceForm.value = { relation: '', targetId: undefined }
    }
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
      descEditing.value = false
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
    ElMessage.warning('请选择关系类型和目标对象')
    return
  }
  await traceApi.create({
    projectId: item.value.projectId,
    sourceType: 'WORK_ITEM',
    sourceId: item.value.id,
    targetType: targetType.value,
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
  <el-drawer v-model="visible" :size="620" :title="item ? `${item.code} · ${typeLabel(item.type)}` : '工作项'">
    <div v-loading="loading">
      <template v-if="item">
        <div class="head">
          <el-tag :type="statusType(item.status)" size="large">{{ statusLabel(item.status, item.type) }}</el-tag>
          <div class="status-actions">
            <span class="label">流转到：</span>
            <el-button
              v-for="s in nextStatuses"
              :key="s"
              size="small"
              type="primary"
              plain
              @click="doTransition(s)"
            >{{ statusLabel(s, item?.type) }}</el-button>
            <span v-if="!nextStatuses.length" class="terminal">（终态）</span>
          </div>
        </div>

        <el-tabs v-model="activeTab">
          <!-- 基本信息 -->
          <el-tab-pane label="基本信息" name="basic">
            <el-form label-width="88px" label-position="right">
              <el-form-item label="标题"><el-input v-model="item.title" /></el-form-item>
              <el-form-item label="说明">
                <template v-if="descEditing">
                  <MarkdownEditor v-model="item.description" :project-id="item.projectId" :rows="4" placeholder="支持 Markdown，可粘贴截图" />
                </template>
                <template v-else>
                  <div class="md-ro">
                    <MarkdownView v-if="item.description" :source="item.description" />
                    <span v-else class="md-empty">（未填写）</span>
                    <el-button link type="primary" size="small" class="md-edit-btn" @click="descEditing = true">编辑</el-button>
                  </div>
                </template>
              </el-form-item>
              <el-form-item label="验收条件">
                <template v-if="descEditing">
                  <MarkdownEditor v-model="item.acceptanceCriteria" :project-id="item.projectId" :rows="3" placeholder="进入 Ready 的前提" />
                </template>
                <template v-else>
                  <div class="md-ro">
                    <MarkdownView v-if="item.acceptanceCriteria" :source="item.acceptanceCriteria" />
                    <span v-else class="md-empty">（未填写）</span>
                  </div>
                </template>
              </el-form-item>
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
              <el-select v-model="traceForm.relation" placeholder="关系类型" size="small" style="width:170px" @change="onRelationChange">
                <el-option v-for="r in relations" :key="r" :label="relLabel(r)" :value="r" />
              </el-select>
              <el-select v-if="targetType === 'PRODUCT_VERSION'" v-model="traceForm.targetId" filterable
                placeholder="选择版本" size="small" style="width:220px">
                <el-option v-for="v in targetVersions" :key="v.id" :label="`${v.code} ${v.versionNo}`" :value="v.id" />
              </el-select>
              <el-select v-else-if="targetType === 'EVIDENCE'" v-model="traceForm.targetId" filterable
                placeholder="选择证据" size="small" style="width:220px">
                <el-option v-for="e in targetEvidences" :key="e.id" :label="`${e.code} ${e.fileName}`" :value="e.id" />
              </el-select>
              <el-select v-else v-model="traceForm.targetId" filterable :disabled="!traceForm.relation"
                placeholder="选择目标工作项（可搜索）" size="small" style="width:220px">
                <el-option v-for="w in targetItems.filter((x) => x.id !== item?.id)" :key="w.id"
                  :label="`${w.code} ${w.title}`" :value="w.id" />
              </el-select>
              <el-button size="small" type="primary" @click="addTrace">建立关联</el-button>
            </div>
            <el-table :data="traces" size="small" border style="margin-top:12px">
              <el-table-column label="方向" width="70">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.direction === 'OUT' ? '' : 'info'">{{ row.direction === 'OUT' ? '出' : '入' }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="关系" width="140">
                <template #default="{ row }">{{ relLabel(row.relation) }}</template>
              </el-table-column>
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
                <b>{{ h.fromStatus ? statusLabel(h.fromStatus, item?.type) : '（新建）' }} → {{ statusLabel(h.toStatus, item?.type) }}</b>
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
.md-ro { width: 100%; position: relative; padding-right: 44px; min-height: 24px; }
.md-empty { color: #c0c4cc; font-size: 13px; }
.md-edit-btn { position: absolute; right: 0; top: 0; }
.accepted { color: #67c23a; font-size: 13px; }
.reason { color: #909399; }
</style>
