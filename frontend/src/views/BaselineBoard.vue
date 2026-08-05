<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import ProjectChips from '@/components/ProjectChips.vue'
import WorkItemDrawer from '@/components/WorkItemDrawer.vue'
import { baselineApi, type Baseline, type Diff, type DiffRow } from '@/api/baseline'
import { statusLabel, typeLabel } from '@/utils/labels'

/**
 * 基线管理：左侧基线列表（DCP 自动固化 + 手动建立），右侧当前 vs 基线对比——
 * 范围蔓延（ADDED）/移除/完成/日期偏差/估算漂移。承诺了什么、后来变成了什么，一眼可见。
 */
const projectId = ref<number | null>(null)
const baselines = ref<Baseline[]>([])
const selectedId = ref<number | null>(null)
const diff = ref<Diff | null>(null)
const loading = ref(false)
const kindFilter = ref<'' | 'ADDED' | 'REMOVED' | 'OPEN' | 'DONE'>('')

const drawerVisible = ref(false)
const currentId = ref<number | null>(null)

const KIND_LABEL: Record<string, string> = { ADDED: '基线外新增', REMOVED: '已移除', DONE: '已完成', OPEN: '进行中' }
const KIND_TAG: Record<string, string> = { ADDED: 'danger', REMOVED: 'info', DONE: 'success', OPEN: '' }

const filteredRows = computed<DiffRow[]>(() => {
  const rows = diff.value?.rows ?? []
  const filtered = kindFilter.value ? rows.filter((r) => r.kind === kindFilter.value) : rows
  // 偏差大的排前面，ADDED 其次，其余按编号
  return [...filtered].sort((a, b) => {
    const sa = a.slipDays ?? (a.kind === 'ADDED' ? -1 : -2)
    const sb = b.slipDays ?? (b.kind === 'ADDED' ? -1 : -2)
    return sb - sa
  })
})

async function loadBaselines() {
  if (!projectId.value) return
  baselines.value = await baselineApi.list(projectId.value)
  if (baselines.value.length && !baselines.value.some((b) => b.id === selectedId.value)) {
    selectedId.value = baselines.value[0].id
  } else if (!baselines.value.length) {
    selectedId.value = null
    diff.value = null
  }
}

async function loadDiff() {
  if (!selectedId.value) return
  loading.value = true
  try {
    diff.value = await baselineApi.diff(selectedId.value)
  } finally {
    loading.value = false
  }
}

async function createBaseline() {
  if (!projectId.value) return
  let name: string | undefined
  try {
    const r = await ElMessageBox.prompt('基线名称（留空自动 B1/B2…）', '建立基线——冻结当前需求域清单', {
      confirmButtonText: '建立', cancelButtonText: '取消', inputPlaceholder: '如 需求冻结-0805',
    })
    name = r.value?.trim() || undefined
  } catch {
    return
  }
  const b = await baselineApi.create(projectId.value, name)
  ElMessage.success(`已建立基线 ${b.name}（${b.itemCount} 项）`)
  await loadBaselines()
  selectedId.value = b.id
}

function openItem(row: DiffRow) {
  currentId.value = row.workItemId
  drawerVisible.value = true
}

watch(projectId, async () => {
  await loadBaselines()
  await loadDiff()
})
watch(selectedId, loadDiff)
</script>

<template>
  <div>
    <ProjectChips v-model="projectId" style="margin-bottom:12px" />
    <el-row :gutter="14">
      <!-- 左：基线列表 -->
      <el-col :span="7">
        <el-card shadow="never">
          <template #header>
            <div class="bl-head">
              <b>基线（{{ baselines.length }}）</b>
              <el-button size="small" type="primary" @click="createBaseline">建立基线</el-button>
            </div>
          </template>
          <div v-if="!baselines.length" class="empty">
            尚无基线。DCP 评审通过时会自动固化，也可手动建立。
          </div>
          <div v-for="b in baselines" :key="b.id" class="bl-item" :class="{ active: b.id === selectedId }"
            @click="selectedId = b.id">
            <div class="bl-name">
              {{ b.name }}
              <el-tag size="small" :type="b.source === 'DCP' ? 'success' : 'info'">
                {{ b.source === 'DCP' ? 'DCP 固化' : '手动' }}
              </el-tag>
            </div>
            <div class="bl-meta">{{ b.itemCount }} 项 · {{ b.createdAt.slice(0, 10) }}</div>
          </div>
        </el-card>
      </el-col>

      <!-- 右：对比 -->
      <el-col :span="17" v-loading="loading">
        <template v-if="diff">
          <div class="kpi-row">
            <div class="kpi"><b>{{ diff.summary.baselineCount }}</b><span>基线范围</span></div>
            <div class="kpi" :class="{ danger: diff.summary.added }"><b>{{ diff.summary.added }}</b><span>蔓延（新增）</span></div>
            <div class="kpi"><b>{{ diff.summary.removed }}</b><span>已移除</span></div>
            <div class="kpi ok"><b>{{ diff.summary.done }}</b><span>已完成</span></div>
            <div class="kpi" :class="{ danger: diff.summary.creepRate > 20 }"><b>{{ diff.summary.creepRate }}%</b><span>蔓延率</span></div>
            <div class="kpi" :class="{ danger: (diff.summary.avgSlipDays ?? 0) > 0 }">
              <b>{{ diff.summary.avgSlipDays ?? '—' }}</b><span>平均偏差(天)</span>
            </div>
            <div class="kpi"><b>{{ diff.summary.estimateDeltaTotal > 0 ? '+' : '' }}{{ diff.summary.estimateDeltaTotal }}</b><span>估算漂移</span></div>
          </div>

          <div class="filter-row">
            <el-radio-group v-model="kindFilter" size="small">
              <el-radio-button label="">全部 ({{ diff.rows.length }})</el-radio-button>
              <el-radio-button v-for="k in ['ADDED', 'OPEN', 'DONE', 'REMOVED']" :key="k" :label="k">
                {{ KIND_LABEL[k] }} ({{ diff.rows.filter(r => r.kind === k).length }})
              </el-radio-button>
            </el-radio-group>
          </div>

          <el-table :data="filteredRows" size="small" border stripe class="clickable" @row-click="openItem">
            <el-table-column prop="code" label="编号" width="130">
              <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
            </el-table-column>
            <el-table-column label="类型" width="86">
              <template #default="{ row }">{{ typeLabel(row.type) }}</template>
            </el-table-column>
            <el-table-column prop="title" label="标题" show-overflow-tooltip />
            <el-table-column label="对比结论" width="104">
              <template #default="{ row }">
                <el-tag size="small" :type="KIND_TAG[row.kind]">{{ KIND_LABEL[row.kind] }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态（基线→当前）" width="170">
              <template #default="{ row }">
                <template v-if="row.kind === 'ADDED'">— → {{ statusLabel(row.currentStatus ?? '', row.type) }}</template>
                <template v-else-if="row.kind === 'REMOVED'">{{ statusLabel(row.baselineStatus ?? '', row.type) }} → 移除</template>
                <template v-else>{{ statusLabel(row.baselineStatus ?? '', row.type) }} → {{ statusLabel(row.currentStatus ?? '', row.type) }}</template>
              </template>
            </el-table-column>
            <el-table-column label="日期偏差" width="120">
              <template #default="{ row }">
                <span v-if="row.slipDays != null" :class="{ slip: row.slipDays > 0, ahead: row.slipDays < 0 }">
                  {{ row.slipDays > 0 ? `+${row.slipDays} 天` : row.slipDays < 0 ? `${row.slipDays} 天` : '按计划' }}
                </span>
                <span v-else class="muted">—</span>
              </template>
            </el-table-column>
            <el-table-column label="估算" width="110">
              <template #default="{ row }">
                <template v-if="row.kind === 'ADDED'">{{ row.currentEstimate ?? '—' }}</template>
                <template v-else>
                  {{ row.baselineEstimate ?? '—' }}<template v-if="row.estimateDelta">
                    → {{ row.currentEstimate }}
                    <span :class="row.estimateDelta > 0 ? 'slip' : 'ahead'">({{ row.estimateDelta > 0 ? '+' : '' }}{{ row.estimateDelta }})</span>
                  </template>
                </template>
              </template>
            </el-table-column>
          </el-table>
        </template>
        <el-empty v-else-if="!loading" description="选择左侧基线查看对比" />
      </el-col>
    </el-row>

    <WorkItemDrawer v-model="drawerVisible" :item-id="currentId" @changed="loadDiff" />
  </div>
</template>

<style scoped>
.bl-head { display: flex; justify-content: space-between; align-items: center; }
.bl-item { padding: 10px 12px; border: 1px solid #ebeef5; border-radius: 8px; margin-bottom: 8px; cursor: pointer; transition: all .15s; }
.bl-item:hover { border-color: #409eff; }
.bl-item.active { border-color: #409eff; background: #ecf5ff; }
.bl-name { font-size: 14px; font-weight: 600; color: #303133; display: flex; justify-content: space-between; align-items: center; }
.bl-meta { font-size: 12px; color: #909399; margin-top: 4px; }
.empty { color: #909399; font-size: 13px; padding: 10px 0; }

.kpi-row { display: grid; grid-template-columns: repeat(7, 1fr); gap: 10px; margin-bottom: 12px; }
.kpi { text-align: center; background: #fff; border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 4px; }
.kpi b { display: block; font-size: 20px; color: #409eff; }
.kpi.ok b { color: #67c23a; }
.kpi.danger b { color: #f56c6c; }
.kpi span { font-size: 12px; color: #909399; }

.filter-row { margin-bottom: 10px; }
.clickable :deep(.el-table__row) { cursor: pointer; }
.mono { font-family: monospace; }
.slip { color: #f56c6c; font-weight: 600; }
.ahead { color: #67c23a; }
.muted { color: #c0c4cc; }
</style>
