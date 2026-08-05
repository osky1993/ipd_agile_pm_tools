<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ProjectChips from '@/components/ProjectChips.vue'
import WorkItemDrawer from '@/components/WorkItemDrawer.vue'
import { timeMachineApi, type Timeline, type AsOf, type AsOfItem, type EventPoint, type Compare, type CompareRow } from '@/api/timemachine'
import { statusLabel, typeLabel } from '@/utils/labels'

/**
 * 时光机：把项目状态回溯到任意历史时点（复盘用）。
 * 事件轨道（决策⚑/基线🔖/迭代▶◀ 可点击跳转）+ 日期滑杆 → 时点重建面板。
 * 状态与存在性为精确回放（状态时间线无旁路写入）；含"后已删除"项如实呈现。
 */
const projectId = ref<number | null>(null)
const timeline = ref<Timeline | null>(null)
const asOf = ref<AsOf | null>(null)
const loading = ref(false)
const typeFilter = ref('')

const drawerVisible = ref(false)
const currentId = ref<number | null>(null)

const DAY = 86400000
const parse = (s: string) => new Date(s + 'T00:00:00').getTime()
const fmt = (t: number) => new Date(t).toISOString().slice(0, 10)

const totalDays = computed(() =>
  timeline.value ? Math.max(1, Math.round((parse(timeline.value.end) - parse(timeline.value.start)) / DAY)) : 1)
const sliderDay = ref(0)
const currentDate = computed(() =>
  timeline.value ? fmt(parse(timeline.value.start) + sliderDay.value * DAY) : '')

const KIND_META: Record<string, { icon: string; color: string; label: string }> = {
  DECISION: { icon: '⚑', color: '#67c23a', label: '决策' },
  BASELINE: { icon: '🔖', color: '#409eff', label: '基线' },
  ITER_START: { icon: '▶', color: '#909399', label: '迭代开始' },
  ITER_END: { icon: '◀', color: '#909399', label: '迭代结束' },
}
const DAY_EVENT_ICON: Record<string, string> = {
  CREATE: '✚', TRANSITION: '⇢', DECISION: '⚑', BASELINE: '🔖', EVIDENCE: '📎',
}

const eventPos = (e: EventPoint) =>
  timeline.value ? ((parse(e.date) - parse(timeline.value.start)) / DAY / totalDays.value) * 100 : 0

function jumpTo(dateStr: string) {
  if (!timeline.value) return
  sliderDay.value = Math.min(totalDays.value,
    Math.max(0, Math.round((parse(dateStr) - parse(timeline.value.start)) / DAY)))
  loadAsOf()
}

/** 前/后一个事件跳转 */
function jumpEvent(dir: 1 | -1) {
  if (!timeline.value) return
  const cur = currentDate.value
  const dates = [...new Set(timeline.value.events.map((e) => e.date))].sort()
  const next = dir === 1 ? dates.find((d) => d > cur) : [...dates].reverse().find((d) => d < cur)
  if (next) jumpTo(next)
}

async function loadTimeline() {
  if (!projectId.value) return
  timeline.value = await timeMachineApi.timeline(projectId.value)
  sliderDay.value = totalDays.value // 默认停在今天
  initCompareDefaults()
  await (mode.value === 'compare' ? loadCompare() : loadAsOf())
}

// ---------- A/B 双时点对比（V2） ----------
const mode = ref<'single' | 'compare'>('single')
const compareFrom = ref('')
const compareTo = ref('')
const compare = ref<Compare | null>(null)
const compareKind = ref<'' | 'NEW' | 'COMPLETED' | 'CHANGED'>('')

function initCompareDefaults() {
  if (!timeline.value) return
  // 默认 A=最近一次决策/基线日（"上次承诺以来发生了什么"），无则项目起点；B=今天
  const anchor = [...timeline.value.events].reverse()
    .find((e) => e.kind === 'DECISION' || e.kind === 'BASELINE')
  compareFrom.value = anchor?.date ?? timeline.value.start
  compareTo.value = timeline.value.end
}

async function loadCompare() {
  if (!projectId.value || !compareFrom.value || !compareTo.value) return
  loading.value = true
  try {
    compare.value = await timeMachineApi.compare(projectId.value, compareFrom.value, compareTo.value)
  } finally {
    loading.value = false
  }
}

function switchMode(m: 'single' | 'compare') {
  mode.value = m
  if (m === 'compare') {
    if (!compareFrom.value) initCompareDefaults()
    loadCompare()
  } else {
    loadAsOf()
  }
}

const KIND_TAG: Record<string, { label: string; type: string }> = {
  NEW: { label: '新增', type: 'danger' },
  COMPLETED: { label: '完成', type: 'success' },
  CHANGED: { label: '推进', type: '' },
}
const filteredCompareRows = computed<CompareRow[]>(() => {
  const rows = compare.value?.rows ?? []
  return compareKind.value ? rows.filter((r) => r.kind === compareKind.value) : rows
})
const delta = (a: number, b: number) => {
  const d = b - a
  return d > 0 ? `+${d}` : `${d}`
}

async function loadAsOf() {
  if (!projectId.value || !currentDate.value) return
  loading.value = true
  try {
    asOf.value = await timeMachineApi.asOf(projectId.value, currentDate.value)
  } finally {
    loading.value = false
  }
}

// ---------- 状态分布条 ----------
const BUCKET: Record<string, string> = {
  Accepted: 'done', Closed: 'done', Verified: 'done', Approved: 'done', Implemented: 'done',
  'In Progress': 'doing', Analysing: 'doing', Fixing: 'doing', Mitigating: 'doing',
  Verification: 'verify', Retesting: 'verify', 'Impact Analysed': 'verify',
  Rejected: 'bad',
}
const BUCKET_COLOR: Record<string, string> = {
  done: '#67c23a', doing: '#409eff', verify: '#e6a23c', bad: '#f56c6c', todo: '#c0c4cc',
}
const bucketColor = (status: string) => BUCKET_COLOR[BUCKET[status] ?? 'todo']

const typeBars = computed(() => {
  if (!asOf.value) return []
  return Object.entries(asOf.value.byTypeStatus).map(([type, byStatus]) => {
    const total = Object.values(byStatus).reduce((a, b) => a + b, 0)
    return {
      type, total,
      segments: Object.entries(byStatus).map(([status, count]) => ({
        status, count, pct: (count / total) * 100, color: bucketColor(status),
      })),
    }
  })
})

const filteredItems = computed<AsOfItem[]>(() => {
  const list = asOf.value?.items ?? []
  return typeFilter.value ? list.filter((i) => i.type === typeFilter.value) : list
})

function openItem(row: AsOfItem) {
  currentId.value = row.id
  drawerVisible.value = true
}

watch(projectId, loadTimeline)
</script>

<template>
  <div>
    <ProjectChips v-model="projectId" style="margin-bottom:12px" />

    <template v-if="timeline">
      <!-- 事件轨道 + 滑杆 -->
      <el-card shadow="never" class="rail-card">
        <div class="rail">
          <el-tooltip v-for="(e, i) in timeline.events" :key="i" placement="top"
            :content="`${e.date} · ${e.label}`">
            <span class="rail-dot" :style="{ left: eventPos(e) + '%', color: KIND_META[e.kind]?.color }"
              @click="jumpTo(e.date)">{{ KIND_META[e.kind]?.icon }}</span>
          </el-tooltip>
        </div>
        <el-slider v-if="mode === 'single'" v-model="sliderDay" :min="0" :max="totalDays" :show-tooltip="false" @change="loadAsOf" />
        <div class="rail-ctrl">
          <span class="rail-range">{{ timeline.start }}</span>
          <div class="rail-mid">
            <el-radio-group :model-value="mode" size="small" @change="(v: any) => switchMode(v)">
              <el-radio-button value="single">单点回溯</el-radio-button>
              <el-radio-button value="compare">A/B 对比</el-radio-button>
            </el-radio-group>
            <template v-if="mode === 'single'">
              <el-button size="small" @click="jumpEvent(-1)">◀ 上一事件</el-button>
              <el-date-picker :model-value="currentDate" type="date" value-format="YYYY-MM-DD" size="small"
                style="width:140px" :clearable="false"
                :disabled-date="(d: Date) => d.getTime() < parse(timeline!.start) || d.getTime() > parse(timeline!.end)"
                @update:model-value="(v: string) => jumpTo(v)" />
              <el-button size="small" @click="jumpEvent(1)">下一事件 ▶</el-button>
              <el-button size="small" type="primary" plain @click="jumpTo(timeline.end)">回到今天</el-button>
            </template>
            <template v-else>
              <el-date-picker v-model="compareFrom" type="date" value-format="YYYY-MM-DD" size="small"
                style="width:140px" :clearable="false" placeholder="时点 A" @change="loadCompare" />
              <span class="cmp-arrow">→</span>
              <el-date-picker v-model="compareTo" type="date" value-format="YYYY-MM-DD" size="small"
                style="width:140px" :clearable="false" placeholder="时点 B" @change="loadCompare" />
              <el-button size="small" plain @click="initCompareDefaults(); loadCompare()">上次承诺→今天</el-button>
            </template>
          </div>
          <span class="rail-range">{{ timeline.end }}（今天）</span>
        </div>
      </el-card>

      <!-- A/B 对比面板 -->
      <div v-if="mode === 'compare'" v-loading="loading">
        <template v-if="compare">
          <div class="kpi-row five">
            <div class="kpi">
              <b>{{ compare.kpisFrom.reqAccepted }}/{{ compare.kpisFrom.reqTotal }} → {{ compare.kpisTo.reqAccepted }}/{{ compare.kpisTo.reqTotal }}</b>
              <span>需求验收（{{ delta(compare.kpisFrom.reqAccepted, compare.kpisTo.reqAccepted) }}）</span>
            </div>
            <div class="kpi">
              <b>{{ compare.kpisFrom.defectsOpen }} → {{ compare.kpisTo.defectsOpen }}</b>
              <span>未关缺陷（{{ delta(compare.kpisFrom.defectsOpen, compare.kpisTo.defectsOpen) }}）</span>
            </div>
            <div class="kpi">
              <b>{{ compare.kpisFrom.wip }} → {{ compare.kpisTo.wip }}</b>
              <span>WIP（{{ delta(compare.kpisFrom.wip, compare.kpisTo.wip) }}）</span>
            </div>
            <div class="kpi">
              <b>{{ compare.kpisFrom.risksOpen }} → {{ compare.kpisTo.risksOpen }}</b>
              <span>开放风险（{{ delta(compare.kpisFrom.risksOpen, compare.kpisTo.risksOpen) }}）</span>
            </div>
            <div class="kpi"><b>{{ compare.transitionCount }}</b><span>期间流转次数</span></div>
          </div>

          <el-row :gutter="14">
            <el-col :span="15">
              <el-card shadow="never">
                <template #header>
                  <div class="list-head">
                    <b>{{ compare.from }} → {{ compare.to }} 变化明细</b>
                    <el-radio-group v-model="compareKind" size="small">
                      <el-radio-button label="">全部 ({{ compare.rows.length }})</el-radio-button>
                      <el-radio-button label="NEW">新增 ({{ compare.rows.filter(r => r.kind === 'NEW').length }})</el-radio-button>
                      <el-radio-button label="COMPLETED">完成 ({{ compare.completed }})</el-radio-button>
                      <el-radio-button label="CHANGED">推进 ({{ compare.changed }})</el-radio-button>
                    </el-radio-group>
                  </div>
                </template>
                <el-table :data="filteredCompareRows" size="small" border stripe class="clickable"
                  max-height="480" @row-click="openItem">
                  <el-table-column prop="code" label="编号" width="130">
                    <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
                  </el-table-column>
                  <el-table-column label="类型" width="86">
                    <template #default="{ row }">{{ typeLabel(row.type) }}</template>
                  </el-table-column>
                  <el-table-column prop="title" label="标题" show-overflow-tooltip />
                  <el-table-column label="结论" width="80">
                    <template #default="{ row }">
                      <el-tag size="small" :type="KIND_TAG[row.kind]?.type">{{ KIND_TAG[row.kind]?.label }}</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="状态（A → B）" width="180">
                    <template #default="{ row }">
                      {{ row.statusFrom ? statusLabel(row.statusFrom, row.type) : '（不存在）' }} → {{ statusLabel(row.statusTo, row.type) }}
                    </template>
                  </el-table-column>
                </el-table>
                <p v-if="!compare.rows.length" class="ev-empty">两个时点之间没有任何变化（未变 {{ compare.unchanged }} 项）。</p>
              </el-card>
            </el-col>
            <el-col :span="9">
              <el-card shadow="never">
                <template #header><b>期间大事（{{ compare.periodEvents.length }}）</b></template>
                <div v-if="compare.periodEvents.length" class="ev-list">
                  <div v-for="(e, i) in compare.periodEvents" :key="i" class="ev-row">
                    <span class="ev-icon">{{ DAY_EVENT_ICON[e.kind] ?? '·' }}</span>
                    <span class="ev-text">{{ e.text }}</span>
                  </div>
                </div>
                <div v-else class="ev-empty">期间无决策与基线事件。</div>
                <p class="cmp-note">未变 {{ compare.unchanged }} 项不在明细中；对比同样含"后已删除"项。</p>
              </el-card>
            </el-col>
          </el-row>
        </template>
      </div>

      <div v-else v-loading="loading">
        <template v-if="asOf">
          <!-- 时点 KPI -->
          <div class="kpi-row">
            <div class="kpi"><b>{{ asOf.reqAccepted }}/{{ asOf.reqTotal }}</b><span>需求验收</span></div>
            <div class="kpi" :class="{ danger: asOf.defectsOpen }"><b>{{ asOf.defectsOpen }}</b><span>未关缺陷</span></div>
            <div class="kpi"><b>{{ asOf.wip }}</b><span>在制 WIP</span></div>
            <div class="kpi" :class="{ danger: asOf.risksOpen }"><b>{{ asOf.risksOpen }}</b><span>开放风险</span></div>
            <div class="kpi"><b>{{ asOf.decisionCount }}</b><span>累计决策</span></div>
            <div class="kpi"><b>{{ asOf.evidenceCount }}</b><span>累计证据</span></div>
          </div>

          <el-row :gutter="14">
            <!-- 左：状态分布 + 清单 -->
            <el-col :span="15">
              <el-card shadow="never" class="mb">
                <template #header><b>{{ asOf.date }} 收盘时的状态分布</b></template>
                <div v-for="bar in typeBars" :key="bar.type" class="tb-row">
                  <span class="tb-label">{{ typeLabel(bar.type) }}（{{ bar.total }}）</span>
                  <div class="tb-bar">
                    <el-tooltip v-for="seg in bar.segments" :key="seg.status"
                      :content="`${statusLabel(seg.status, bar.type)} ${seg.count}`" placement="top">
                      <span class="tb-seg" :style="{ width: seg.pct + '%', background: seg.color }">
                        {{ seg.count }}
                      </span>
                    </el-tooltip>
                  </div>
                </div>
                <div class="tb-legend">
                  <span v-for="(c, k) in { 待办: BUCKET_COLOR.todo, 进行中: BUCKET_COLOR.doing, 验证中: BUCKET_COLOR.verify, 完成: BUCKET_COLOR.done }"
                    :key="k"><i class="lg" :style="{ background: c }" />{{ k }}</span>
                </div>
              </el-card>

              <el-card shadow="never">
                <template #header>
                  <div class="list-head">
                    <b>工作项清单（当天状态）</b>
                    <el-radio-group v-model="typeFilter" size="small">
                      <el-radio-button label="">全部</el-radio-button>
                      <el-radio-button v-for="t in Object.keys(asOf.byTypeStatus)" :key="t" :label="t">
                        {{ typeLabel(t) }}
                      </el-radio-button>
                    </el-radio-group>
                  </div>
                </template>
                <el-table :data="filteredItems" size="small" border stripe class="clickable"
                  max-height="420" @row-click="openItem">
                  <el-table-column prop="code" label="编号" width="130">
                    <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
                  </el-table-column>
                  <el-table-column label="类型" width="86">
                    <template #default="{ row }">{{ typeLabel(row.type) }}</template>
                  </el-table-column>
                  <el-table-column prop="title" label="标题" show-overflow-tooltip>
                    <template #default="{ row }">
                      {{ row.title }}<el-tag v-if="row.deletedNow" size="small" type="info" class="del-tag">后已删除</el-tag>
                    </template>
                  </el-table-column>
                  <el-table-column label="当天状态" width="110">
                    <template #default="{ row }">
                      <el-tag size="small" :color="bucketColor(row.statusAtDate)" class="st-tag">
                        {{ statusLabel(row.statusAtDate, row.type) }}
                      </el-tag>
                    </template>
                  </el-table-column>
                </el-table>
              </el-card>
            </el-col>

            <!-- 右：当日事件 -->
            <el-col :span="9">
              <el-card shadow="never">
                <template #header><b>{{ asOf.date }} 当日事件（{{ asOf.dayEvents.length }}）</b></template>
                <div v-if="asOf.dayEvents.length" class="ev-list">
                  <div v-for="(e, i) in asOf.dayEvents" :key="i" class="ev-row">
                    <span class="ev-icon">{{ DAY_EVENT_ICON[e.kind] ?? '·' }}</span>
                    <span class="ev-text">{{ e.text }}</span>
                  </div>
                </div>
                <div v-else class="ev-empty">这一天风平浪静。拖动滑杆或点击轨道上的 ⚑🔖 标记跳到大事发生的日子。</div>
              </el-card>
            </el-col>
          </el-row>
        </template>
      </div>
    </template>

    <WorkItemDrawer v-model="drawerVisible" :item-id="currentId" />
  </div>
</template>

<style scoped>
.rail-card { margin-bottom: 14px; }
.rail { position: relative; height: 26px; margin: 0 8px; }
.rail-dot { position: absolute; top: 0; transform: translateX(-50%); cursor: pointer; font-size: 14px; transition: transform .1s; }
.rail-dot:hover { transform: translateX(-50%) scale(1.35); }
.rail-ctrl { display: flex; justify-content: space-between; align-items: center; margin-top: 2px; }
.rail-mid { display: flex; gap: 8px; align-items: center; }
.rail-range { font-size: 12px; color: #909399; font-family: monospace; }

.kpi-row { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; margin-bottom: 14px; }
.kpi-row.five { grid-template-columns: repeat(5, 1fr); }
.kpi-row.five .kpi b { font-size: 15px; }
.cmp-arrow { color: #909399; }
.cmp-note { font-size: 12px; color: #c0c4cc; margin-top: 10px; }
.kpi { text-align: center; background: #fff; border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 4px; }
.kpi b { display: block; font-size: 20px; color: #409eff; }
.kpi.danger b { color: #f56c6c; }
.kpi span { font-size: 12px; color: #909399; }

.mb { margin-bottom: 14px; }
.tb-row { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; }
.tb-label { width: 110px; flex: none; font-size: 13px; color: #606266; text-align: right; }
.tb-bar { flex: 1; display: flex; height: 22px; border-radius: 4px; overflow: hidden; background: #f5f7fa; }
.tb-seg { display: flex; align-items: center; justify-content: center; color: #fff; font-size: 11px; min-width: 18px; }
.tb-legend { display: flex; gap: 14px; font-size: 12px; color: #909399; margin-top: 4px; padding-left: 120px; }
.tb-legend .lg { display: inline-block; width: 10px; height: 10px; border-radius: 3px; margin-right: 4px; vertical-align: -1px; }

.list-head { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 8px; }
.clickable :deep(.el-table__row) { cursor: pointer; }
.mono { font-family: monospace; }
.del-tag { margin-left: 6px; }
.st-tag { color: #fff; border: none; }

.ev-list { display: flex; flex-direction: column; gap: 8px; max-height: 560px; overflow-y: auto; }
.ev-row { display: flex; gap: 8px; font-size: 13px; line-height: 1.5; }
.ev-icon { flex: none; width: 20px; text-align: center; }
.ev-text { color: #606266; }
.ev-empty { color: #909399; font-size: 13px; padding: 8px 0; }
</style>
