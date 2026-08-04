<script setup lang="ts">
import { ref, watch } from 'vue'
import ProjectChips from '@/components/ProjectChips.vue'
import RoadmapTimeline, { type TimelineLane, type TimelineItem } from '@/components/RoadmapTimeline.vue'
import { versionApi, stageApi, type ProductVersion, type StageGate } from '@/api/catalog'
import { iterationApi, type Iteration } from '@/api/agile'
import { decisionApi, type Decision } from '@/api/governance'
import { decisionLabel } from '@/utils/labels'

const projectId = ref<number | null>(null)
const loading = ref(false)
const lanes = ref<TimelineLane[]>([])
const emptyHint = ref(false)

const today = new Date(new Date().toDateString())

function versionItems(versions: ProductVersion[]): TimelineItem[] {
  const out: TimelineItem[] = []
  for (const v of versions) {
    if (v.actualReleaseDate) {
      out.push({ type: 'point', shape: 'diamond', date: v.actualReleaseDate, label: v.versionNo, status: 'done', tip: '已发布' })
      // 计划日与实际日都存在且不同时，同时呈现计划点便于对比
      if (v.planReleaseDate && v.planReleaseDate !== v.actualReleaseDate) {
        out.push({ type: 'point', shape: 'diamond', date: v.planReleaseDate, label: '', status: 'plan', tip: `${v.versionNo} 计划发布日` })
      }
    } else if (v.planReleaseDate) {
      const late = new Date(v.planReleaseDate) < today
      out.push({ type: 'point', shape: 'diamond', date: v.planReleaseDate, label: v.versionNo, status: late ? 'late' : 'plan', tip: late ? '计划发布日已过，尚未发布' : '计划发布' })
    }
  }
  return out
}

function gateItems(gates: StageGate[], decisions: Decision[]): TimelineItem[] {
  // 每个 gate 的最新决策（decisions 按 id 升序遍历，后者覆盖前者）
  const latest = new Map<number, Decision>()
  for (const d of [...decisions].sort((a, b) => a.id - b.id)) {
    if (d.subjectType === 'STAGE_GATE' && d.subjectId != null) latest.set(d.subjectId, d)
  }
  const out: TimelineItem[] = []
  for (const g of gates) {
    const d = latest.get(g.id)
    const passed = d && ['PASS', 'CONDITIONAL'].includes(d.conclusion)
    const name = `${g.stageName}/${g.gateName}`
    if (passed) {
      const decidedDate = d!.decidedAt?.slice(0, 10) || g.planDate
      out.push({ type: 'point', shape: 'flag', date: decidedDate, label: name, status: 'done', tip: `${d!.code} ${decisionLabel(d!.conclusion)}` })
    } else if (g.planDate) {
      const late = new Date(g.planDate) < today
      out.push({ type: 'point', shape: 'flag', date: g.planDate, label: name, status: late ? 'late' : 'plan', tip: late ? '已逾期未评审' : '计划评审' })
    }
  }
  return out
}

function iterationItems(iterations: Iteration[]): TimelineItem[] {
  return iterations
    .filter((it) => it.hidden !== 1 && it.startDate && it.endDate)
    .map((it) => ({
      type: 'bar' as const,
      start: it.startDate,
      end: it.endDate,
      label: it.name,
      status: it.status === 'ACTIVE' ? 'active' as const
        : ['DONE', 'CLOSED'].includes(it.status) ? 'done' as const : 'plan' as const,
      tip: it.goal ?? '',
    }))
}

async function load() {
  if (!projectId.value) return
  loading.value = true
  try {
    const pid = projectId.value
    const [versions, gates, iterations, decisions] = await Promise.all([
      versionApi.list(pid), stageApi.list(pid), iterationApi.list(pid), decisionApi.list(pid),
    ])
    lanes.value = [
      { label: '产品版本', items: versionItems(versions) },
      { label: '阶段 / DCP', items: gateItems(gates, decisions) },
      { label: '迭代', items: iterationItems(iterations) },
    ]
    emptyHint.value = lanes.value.every((l) => !l.items.length)
  } finally {
    loading.value = false
  }
}

watch(projectId, load)
</script>

<template>
  <div>
    <ProjectChips v-model="projectId" style="margin-bottom:12px" />
    <el-alert v-if="emptyHint" type="info" :closable="false" show-icon style="margin-bottom:12px"
      title="暂无带日期的路标数据：请在「项目·版本·阶段」页为版本填写计划发布日、为阶段/DCP 填写计划评审日，或在看板页维护迭代起止日期。" />
    <div v-loading="loading">
      <RoadmapTimeline :lanes="lanes" />
    </div>
  </div>
</template>
