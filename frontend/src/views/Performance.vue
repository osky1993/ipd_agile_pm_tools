<script setup lang="ts">
import { computed, nextTick, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts'
import { perfApi, type PerfOverview, type Metric, type Improvement, type TrendPoint, type CfdPoint } from '@/api/perf'
import WorkItemDrawer from '@/components/WorkItemDrawer.vue'
import ProjectChips from '@/components/ProjectChips.vue'
import { useAuthStore } from '@/stores/auth'
import { statusLabel } from '@/utils/labels'

const auth = useAuthStore()
const projectId = ref<number | null>(null)
const overview = ref<PerfOverview | null>(null)
const improvements = ref<Improvement[]>([])
const activeTab = ref('metrics')
const impFilter = ref('')

const drawerVisible = ref(false)
const currentId = ref<number | null>(null)

// 目标行内编辑
const editingKey = ref<string | null>(null)
const editingValue = ref<number | null>(null)

// 改进项对话框
const impDialog = ref(false)
const editingImp = ref<Improvement | null>(null)
const impForm = reactive({ metricKey: '', title: '', measure: '', targetValue: null as number | null, dueDate: '' })

// 验证对话框
const verifyDialog = ref(false)
const verifyImp = ref<Improvement | null>(null)
const verifyForm = reactive({ resultValue: null as number | null, conclusion: '' })

const weeklyEl = ref<HTMLElement | null>(null)
const stageEl = ref<HTMLElement | null>(null)
const typeEl = ref<HTMLElement | null>(null)
const cfdEl = ref<HTMLElement | null>(null)
let weeklyChart: echarts.ECharts | null = null
let stageChart: echarts.ECharts | null = null
let typeChart: echarts.ECharts | null = null
let cfdChart: echarts.ECharts | null = null

// 指标趋势弹窗（含改进项前后对比佐证）
const trendDialog = ref(false)
const trendKey = ref('')
const trendImp = ref<Improvement | null>(null)
const trendEl = ref<HTMLElement | null>(null)
let trendChart: echarts.ECharts | null = null
let trendsCache: Record<string, TrendPoint[]> | null = null
const cfd = ref<CfdPoint[]>([])

/** CFD 阶段色：单色系浅→深（越接近完成越深），面积堆叠完成在下 */
const CFD_COLORS: Record<string, string> = {
  Backlog: '#c6dbef', Ready: '#9ecae1', 'In Progress': '#6baed6',
  Verification: '#3182bd', Accepted: '#0b4c8c',
}
const CFD_ORDER = ['Accepted', 'Verification', 'In Progress', 'Ready', 'Backlog']

async function openTrend(key: string, imp?: Improvement) {
  if (!projectId.value || !key) return
  if (!trendsCache) trendsCache = await perfApi.trends(projectId.value, 90)
  trendKey.value = key
  trendImp.value = imp ?? null
  trendDialog.value = true
  await nextTick()
  renderTrend()
}

function renderTrend() {
  if (!trendEl.value) return
  if (!trendChart) trendChart = echarts.init(trendEl.value)
  const pts = trendsCache?.[trendKey.value] ?? []
  const m = allMetrics.value.find((x) => x.key === trendKey.value)
  const imp = trendImp.value
  const markLines: object[] = []
  if (imp?.baselineValue !== null && imp?.baselineValue !== undefined)
    markLines.push({ yAxis: imp.baselineValue, name: '基线', label: { formatter: '基线 {c}' }, lineStyle: { color: '#909399', type: 'dashed' } })
  const target = imp?.targetValue ?? m?.target
  if (target !== null && target !== undefined)
    markLines.push({ yAxis: target, name: '目标', label: { formatter: '目标 {c}' }, lineStyle: { color: '#67c23a', type: 'dashed' } })
  const markPoints: object[] = []
  if (imp?.createdAt) {
    const d = imp.createdAt.slice(0, 10)
    if (pts.some((p) => p.date === d)) markPoints.push({ coord: [d, pts.find((p) => p.date === d)?.value], name: '发起改进', value: '发起' })
  }
  trendChart.clear()
  trendChart.setOption({
    tooltip: { trigger: 'axis', valueFormatter: (v: number | null) => (v === null ? '—' : `${v}${m?.unit ?? ''}`) },
    grid: { top: 32, left: 44, right: 40, bottom: 28 },
    xAxis: { type: 'category', data: pts.map((p) => p.date.slice(5)) },
    yAxis: { type: 'value' },
    series: [{
      name: m?.name ?? trendKey.value,
      type: 'line',
      connectNulls: true,
      data: pts.map((p) => p.value),
      itemStyle: { color: '#409eff' },
      markLine: { symbol: 'none', data: markLines },
      markPoint: { data: markPoints, symbolSize: 46, itemStyle: { color: '#e6a23c' } },
    }],
  })
}

function renderCfd() {
  if (!cfdEl.value || !cfd.value.length) return
  if (!cfdChart) cfdChart = echarts.init(cfdEl.value)
  cfdChart.setOption({
    tooltip: { trigger: 'axis' },
    legend: { bottom: 0 },
    grid: { top: 16, left: 36, right: 16, bottom: 36 },
    xAxis: { type: 'category', boundaryGap: false, data: cfd.value.map((p) => p.date.slice(5)) },
    yAxis: { type: 'value', minInterval: 1 },
    series: CFD_ORDER.map((s) => ({
      name: statusLabel(s),
      type: 'line',
      stack: 'cfd',
      areaStyle: { color: CFD_COLORS[s] },
      lineStyle: { width: 1, color: CFD_COLORS[s] },
      itemStyle: { color: CFD_COLORS[s] },
      showSymbol: false,
      data: cfd.value.map((p) => p.byStatus[s] ?? 0),
    })),
  })
}

const IMP_FLOW = ['OPEN', 'DOING', 'DONE', 'VERIFIED']
const IMP_LABEL: Record<string, string> = { OPEN: '待启动', DOING: '进行中', DONE: '已落地', VERIFIED: '已验证' }
const IMP_TAG: Record<string, string> = { OPEN: 'info', DOING: 'warning', DONE: '', VERIFIED: 'success' }

/** 全部指标平铺（含 L3），供改进项下拉与名称映射 */
const allMetrics = computed<Metric[]>(() => overview.value?.groups.flatMap((g) => g.metrics) ?? [])
const metricName = (key: string | null) => allMetrics.value.find((m) => m.key === key)?.name ?? key ?? '通用改进'

/** 树形数据：L3 挂到 parent 的 children */
function treeData(metrics: Metric[]): (Metric & { children?: Metric[] })[] {
  const l2: (Metric & { children?: Metric[] })[] = metrics.filter((m) => m.level === 2).map((m) => ({ ...m }))
  for (const m of metrics.filter((x) => x.level === 3)) {
    const p = l2.find((x) => x.key === m.parent)
    if (p) (p.children ??= []).push({ ...m })
  }
  return l2
}
function groupStats(metrics: Metric[]) {
  return {
    good: metrics.filter((m) => m.status === 'good').length,
    warn: metrics.filter((m) => m.status === 'warn').length,
  }
}
const fmt = (m: Metric) => (m.value === null ? '—' : `${m.value}${m.unit}`)

async function load() {
  if (!projectId.value) return
  trendsCache = null
  ;[overview.value, improvements.value, cfd.value] = await Promise.all([
    perfApi.metrics(projectId.value),
    perfApi.improvements(projectId.value),
    perfApi.cfd(projectId.value),
  ])
  await nextTick()
  renderCharts()
  renderCfd()
}

function renderCharts() {
  const c = overview.value?.charts
  if (!c) return
  if (weeklyEl.value) {
    if (!weeklyChart) weeklyChart = echarts.init(weeklyEl.value)
    weeklyChart.setOption({
      tooltip: { trigger: 'axis' },
      grid: { top: 20, left: 32, right: 16, bottom: 24 },
      xAxis: { type: 'category', data: c.weeklyThroughput.map((w) => w.weekStart.slice(5)) },
      yAxis: { type: 'value', minInterval: 1 },
      series: [{ name: '验收数', type: 'bar', data: c.weeklyThroughput.map((w) => w.count), itemStyle: { color: '#409eff' } }],
    })
  }
  if (stageEl.value) {
    if (!stageChart) stageChart = echarts.init(stageEl.value)
    stageChart.setOption({
      tooltip: { trigger: 'axis', valueFormatter: (v: number | null) => (v === null ? '数据不足' : `${v} 天`) },
      grid: { top: 20, left: 84, right: 24, bottom: 24 },
      xAxis: { type: 'value' },
      yAxis: { type: 'category', inverse: true, data: c.stageDurations.map((s) => s.stage) },
      series: [{ name: '平均停留(天)', type: 'bar', data: c.stageDurations.map((s) => s.avgDays), itemStyle: { color: '#e6a23c' } }],
    })
  }
  if (typeEl.value) {
    if (!typeChart) typeChart = echarts.init(typeEl.value)
    typeChart.setOption({
      tooltip: { trigger: 'item' },
      legend: { bottom: 0 },
      series: [{ name: '近4周验收', type: 'pie', radius: ['40%', '65%'],
        data: c.throughputByType.map((t) => ({ name: t.label, value: t.count })), label: { formatter: '{b}: {c}' } }],
    })
  }
}

// ---------- 目标编辑 ----------
function startEdit(m: Metric) {
  if (!auth.hasRole('PM')) return
  editingKey.value = m.key
  editingValue.value = m.target
}
async function confirmTarget(m: Metric) {
  if (!projectId.value) return
  const updated = await perfApi.setTarget(projectId.value, m.key, editingValue.value)
  // 原位替换
  for (const g of overview.value?.groups ?? []) {
    const idx = g.metrics.findIndex((x) => x.key === m.key)
    if (idx >= 0) g.metrics[idx] = updated
  }
  editingKey.value = null
  ElMessage.success(updated.target === null ? '已清除目标' : '目标已更新')
}

// ---------- 改进项 ----------
const filteredImps = computed(() =>
  impFilter.value ? improvements.value.filter((i) => i.status === impFilter.value) : improvements.value)

function openCreateImp(metricKey?: string) {
  editingImp.value = null
  impForm.metricKey = metricKey ?? ''
  impForm.title = ''; impForm.measure = ''; impForm.targetValue = null; impForm.dueDate = ''
  impDialog.value = true
  activeTab.value = 'improve'
}
function openEditImp(imp: Improvement) {
  editingImp.value = imp
  impForm.metricKey = imp.metricKey ?? ''
  impForm.title = imp.title
  impForm.measure = imp.measure ?? ''
  impForm.targetValue = imp.targetValue
  impForm.dueDate = imp.dueDate ?? ''
  impDialog.value = true
}
const baselineHint = computed(() => {
  const m = allMetrics.value.find((x) => x.key === impForm.metricKey)
  return m ? (m.value === null ? '当前无数据' : `当前基线值：${m.value}${m.unit}`) : ''
})
async function submitImp() {
  if (!projectId.value || !impForm.title.trim()) return ElMessage.warning('请填写改进项标题')
  const payload = {
    metricKey: impForm.metricKey || null,
    title: impForm.title,
    measure: impForm.measure,
    targetValue: impForm.targetValue,
    dueDate: impForm.dueDate || null,
  }
  if (editingImp.value) {
    await perfApi.updateImprovement(editingImp.value.id, payload as Partial<Improvement>)
    ElMessage.success('改进项已更新')
  } else {
    await perfApi.createImprovement({ projectId: projectId.value, ...payload } as Partial<Improvement>)
    ElMessage.success('改进项已发起（基线已固化）')
  }
  impDialog.value = false
  improvements.value = await perfApi.improvements(projectId.value)
}
function nextStatus(imp: Improvement): string | null {
  const idx = IMP_FLOW.indexOf(imp.status)
  return idx >= 0 && idx < IMP_FLOW.length - 1 ? IMP_FLOW[idx + 1] : null
}
async function advance(imp: Improvement) {
  const to = nextStatus(imp)
  if (!to || !projectId.value) return
  if (to === 'VERIFIED') {
    verifyImp.value = imp
    const m = allMetrics.value.find((x) => x.key === imp.metricKey)
    verifyForm.resultValue = m?.value ?? null
    verifyForm.conclusion = ''
    verifyDialog.value = true
    return
  }
  await perfApi.transitionImprovement(imp.id, to)
  improvements.value = await perfApi.improvements(projectId.value)
  ElMessage.success(`已推进到 ${IMP_LABEL[to]}`)
}
async function submitVerify() {
  if (!verifyImp.value || !projectId.value) return
  if (verifyForm.resultValue === null) return ElMessage.warning('必须填写实际值')
  await perfApi.transitionImprovement(verifyImp.value.id, 'VERIFIED', verifyForm.resultValue, verifyForm.conclusion)
  verifyDialog.value = false
  improvements.value = await perfApi.improvements(projectId.value)
  ElMessage.success('改进效果已验证')
}
async function removeImp(imp: Improvement) {
  await ElMessageBox.confirm(`删除改进项 ${imp.code}？`, '确认', { type: 'warning' })
  await perfApi.deleteImprovement(imp.id)
  if (projectId.value) improvements.value = await perfApi.improvements(projectId.value)
}
const overdue = (imp: Improvement) =>
  !!imp.dueDate && imp.dueDate < new Date().toISOString().slice(0, 10) && imp.status !== 'VERIFIED'

function openStale(row: { id: number }) { currentId.value = row.id; drawerVisible.value = true }

</script>

<template>
  <div>
    <div class="toolbar">
      <ProjectChips v-model="projectId" @change="load" />
    </div>

    <el-tabs v-model="activeTab">
      <!-- 指标体系 -->
      <el-tab-pane label="指标体系" name="metrics">
        <template v-if="overview">
          <el-row :gutter="12">
            <el-col v-for="g in overview.groups" :key="g.key" :span="8" class="gcol">
              <el-card shadow="never">
                <template #header>
                  <div class="ghead">
                    <b>{{ g.name }}</b>
                    <span class="gtags">
                      <el-tag v-if="groupStats(g.metrics).good" type="success" size="small">达标 {{ groupStats(g.metrics).good }}</el-tag>
                      <el-tag v-if="groupStats(g.metrics).warn" type="warning" size="small">偏离 {{ groupStats(g.metrics).warn }}</el-tag>
                    </span>
                  </div>
                </template>
                <el-table :data="treeData(g.metrics)" size="small" row-key="key"
                  :tree-props="{ children: 'children' }" :show-header="false">
                  <el-table-column label="指标" min-width="150">
                    <template #default="{ row }">
                      <span class="m-name" title="点击查看趋势" @click="openTrend(row.key)">{{ row.name }}</span>
                    </template>
                  </el-table-column>
                  <el-table-column label="当前" width="72" align="right">
                    <template #default="{ row }"><b>{{ fmt(row) }}</b></template>
                  </el-table-column>
                  <el-table-column label="目标" width="104" align="right">
                    <template #default="{ row }">
                      <template v-if="editingKey === row.key">
                        <el-input-number v-model="editingValue" size="small" :controls="false" style="width:64px"
                          @keyup.enter="confirmTarget(row)" />
                        <el-button link type="primary" size="small" @click="confirmTarget(row)">√</el-button>
                      </template>
                      <span v-else class="target" :class="{ editable: auth.hasRole('PM') }" @click="startEdit(row)">
                        {{ row.target === null ? '设定' : row.target + row.unit }}
                      </span>
                    </template>
                  </el-table-column>
                  <el-table-column width="52" align="center">
                    <template #default="{ row }">
                      <el-tag v-if="row.status !== 'none'" size="small"
                        :type="row.status === 'good' ? 'success' : 'warning'">
                        {{ row.status === 'good' ? '达标' : '偏离' }}
                      </el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column width="46" align="center">
                    <template #default="{ row }">
                      <el-button link type="primary" size="small" title="发起改进" @click="openCreateImp(row.key)">改进</el-button>
                    </template>
                  </el-table-column>
                </el-table>
              </el-card>
            </el-col>
          </el-row>

          <el-row :gutter="12" class="crow">
            <el-col :span="8"><el-card shadow="never"><template #header><b>周吞吐（近8周验收数）</b></template><div ref="weeklyEl" class="chart" /></el-card></el-col>
            <el-col :span="8"><el-card shadow="never"><template #header><b>阶段平均停留（天）</b></template><div ref="stageEl" class="chart" /></el-card></el-col>
            <el-col :span="8"><el-card shadow="never"><template #header><b>近4周吞吐按类型</b></template><div ref="typeEl" class="chart" /></el-card></el-col>
          </el-row>

          <el-card shadow="never" class="crow">
            <template #header><b>累积流图（CFD · 近 8 周各状态存量）</b>——面积平行说明流动顺畅；某层持续变厚即该阶段在堆积</template>
            <div ref="cfdEl" class="cfd-chart" />
          </el-card>

          <el-card shadow="never" class="crow" v-if="overview.charts.staleTop.length">
            <template #header><b>停滞工作项 TOP{{ overview.charts.staleTop.length }}</b>（按最后状态变更距今天数）</template>
            <el-table :data="overview.charts.staleTop" size="small" @row-click="openStale" class="clickable">
              <el-table-column prop="code" label="编号" width="140" />
              <el-table-column prop="title" label="标题" show-overflow-tooltip />
              <el-table-column prop="status" label="当前状态" width="120" />
              <el-table-column label="停滞" width="90" align="right">
                <template #default="{ row }"><span class="stale-days">{{ row.days }} 天</span></template>
              </el-table-column>
            </el-table>
          </el-card>

          <p class="hint">指标全部由业务数据自动计算，不含任何个人绩效口径（规划§13）。承诺完成率以工作项当前所属迭代为口径；估算按数字解析，非数字按 0 计。点击目标列可设定/修改目标值（清空后确认=移除目标）。</p>
        </template>
      </el-tab-pane>

      <!-- 改进项 -->
      <el-tab-pane :label="`改进项 (${improvements.length})`" name="improve">
        <div class="sub-bar">
          <el-radio-group v-model="impFilter" size="small">
            <el-radio-button value="">全部</el-radio-button>
            <el-radio-button v-for="s in IMP_FLOW" :key="s" :value="s">{{ IMP_LABEL[s] }}</el-radio-button>
          </el-radio-group>
          <el-button type="primary" size="small" @click="openCreateImp()"><el-icon><Plus /></el-icon>新建改进</el-button>
        </div>
        <el-table :data="filteredImps" border>
          <el-table-column prop="code" label="编号" width="130" />
          <el-table-column label="关联指标" width="170">
            <template #default="{ row }">{{ metricName(row.metricKey) }}</template>
          </el-table-column>
          <el-table-column prop="title" label="改进项" show-overflow-tooltip />
          <el-table-column label="基线 → 目标 → 实际" width="160" align="center">
            <template #default="{ row }">
              {{ row.baselineValue ?? '—' }} → {{ row.targetValue ?? '—' }} →
              <b :class="{ 'v-good': row.resultValue !== null }">{{ row.resultValue ?? '—' }}</b>
            </template>
          </el-table-column>
          <el-table-column label="期限" width="110">
            <template #default="{ row }">
              <span :class="{ overdue: overdue(row) }">{{ row.dueDate || '—' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="IMP_TAG[row.status]">{{ IMP_LABEL[row.status] }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="220">
            <template #default="{ row }">
              <el-button v-if="row.metricKey" link size="small" @click="openTrend(row.metricKey, row)">趋势</el-button>
              <el-button v-if="nextStatus(row)" link type="primary" size="small" @click="advance(row)">
                {{ nextStatus(row) === 'VERIFIED' ? '验证效果' : '推进→' + IMP_LABEL[nextStatus(row)!] }}
              </el-button>
              <el-button v-if="['OPEN', 'DOING'].includes(row.status)" link size="small" @click="openEditImp(row)">编辑</el-button>
              <el-button link type="danger" size="small" @click="removeImp(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p class="hint">改进闭环：发现指标偏离 → 发起改进（自动固化基线）→ 落地 → 验证实际值与基线对比。状态只前进，验证必须回填实际值。</p>
      </el-tab-pane>
    </el-tabs>

    <WorkItemDrawer v-model="drawerVisible" :item-id="currentId" @changed="load" />

    <!-- 新建/编辑改进 -->
    <el-dialog v-model="impDialog" :title="editingImp ? `编辑改进 ${editingImp.code}` : '发起改进'" width="500px">
      <el-form label-width="84px">
        <el-form-item label="关联指标">
          <el-select v-model="impForm.metricKey" clearable placeholder="通用改进（不关联指标）" style="width:100%" :disabled="!!editingImp">
            <el-option v-for="m in allMetrics" :key="m.key" :label="m.name" :value="m.key" />
          </el-select>
          <div v-if="baselineHint && !editingImp" class="hint">{{ baselineHint }}（发起时自动固化为基线）</div>
        </el-form-item>
        <el-form-item label="改进项" required><el-input v-model="impForm.title" placeholder="如：压缩需求验证等待时间" /></el-form-item>
        <el-form-item label="措施"><el-input v-model="impForm.measure" type="textarea" :rows="3" placeholder="具体怎么改" /></el-form-item>
        <el-form-item label="目标值"><el-input-number v-model="impForm.targetValue" :controls="false" style="width:140px" /></el-form-item>
        <el-form-item label="期限"><el-date-picker v-model="impForm.dueDate" type="date" value-format="YYYY-MM-DD" style="width:100%" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="impDialog = false">取消</el-button>
        <el-button type="primary" @click="submitImp">{{ editingImp ? '保存' : '发起' }}</el-button>
      </template>
    </el-dialog>

    <!-- 指标趋势（含改进前后对比） -->
    <el-dialog v-model="trendDialog" :title="`趋势：${metricName(trendKey)}${trendImp ? '（改进 ' + trendImp.code + ' 佐证）' : ''}`" width="720px" destroy-on-close @opened="renderTrend">
      <div ref="trendEl" class="trend-dlg-chart" />
      <p v-if="trendImp" class="hint">虚线为改进基线/目标；曲线为该指标每日快照（打开效能页即记录当天）。改进 {{ trendImp.status === 'VERIFIED' ? `已验证：实际 ${trendImp.resultValue}` : '进行中' }}。</p>
      <p v-else class="hint">曲线为每日快照，历史随使用累积；缺快照的日期为断点。</p>
    </el-dialog>

    <!-- 验证效果 -->
    <el-dialog v-model="verifyDialog" :title="`验证改进效果：${verifyImp?.code ?? ''}`" width="460px">
      <el-form label-width="84px">
        <el-form-item label="基线值"><b>{{ verifyImp?.baselineValue ?? '—' }}</b></el-form-item>
        <el-form-item label="目标值">{{ verifyImp?.targetValue ?? '—' }}</el-form-item>
        <el-form-item label="实际值" required>
          <el-input-number v-model="verifyForm.resultValue" :controls="false" style="width:140px" />
          <span class="hint" style="margin-left:8px">已预填指标当前值，可修改</span>
        </el-form-item>
        <el-form-item label="结论"><el-input v-model="verifyForm.conclusion" type="textarea" :rows="2" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="verifyDialog = false">取消</el-button>
        <el-button type="primary" @click="submitVerify">确认验证</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { margin-bottom: 14px; }
.gcol { margin-bottom: 12px; }
.ghead { display: flex; align-items: center; justify-content: space-between; }
.gtags { display: flex; gap: 6px; }
.target { color: #909399; cursor: default; }
.target.editable { color: #409eff; cursor: pointer; text-decoration: underline dotted; }
.crow { margin-bottom: 12px; }
.chart { height: 200px; }
.cfd-chart { height: 260px; }
.trend-dlg-chart { height: 320px; }
.m-name { cursor: pointer; }
.m-name:hover { color: #409eff; text-decoration: underline dotted; }
.clickable :deep(.el-table__row) { cursor: pointer; }
.stale-days { color: #e6a23c; font-weight: 600; }
.sub-bar { display: flex; justify-content: space-between; margin-bottom: 12px; }
.overdue { color: #f56c6c; font-weight: 600; }
.v-good { color: #67c23a; }
.hint { color: #909399; font-size: 12px; margin-top: 8px; }
</style>
