<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { iterationApi, type Retro } from '@/api/agile'
import { assetsApi, LESSON_CATEGORY, type Lesson } from '@/api/assets'
import ReportSection from '@/components/report/ReportSection.vue'
import StaticChart from '@/components/report/StaticChart.vue'
import { statusLabel, typeLabel } from '@/utils/labels'
import '@/assets/print.css'

const route = useRoute()
const iterationId = Number(route.params.iterationId)
const loading = ref(true)
const data = ref<Retro | null>(null)
const now = new Date().toLocaleString('zh-CN')

const doneItems = computed(() => data.value?.items.filter((i) => i.done) ?? [])
const spillItems = computed(() => data.value?.items.filter((i) => !i.done && !i.movedOut) ?? [])
const movedItems = computed(() => data.value?.items.filter((i) => !i.done && i.movedOut) ?? [])

const velocityOption = computed(() => {
  const v = data.value?.velocity ?? []
  return {
    tooltip: { trigger: 'axis' as const },
    legend: { data: ['承诺', '完成'] },
    grid: { left: 40, right: 20, top: 34, bottom: 24 },
    xAxis: { type: 'category' as const, data: v.map((p) => p.name) },
    yAxis: { type: 'value' as const, minInterval: 1 },
    series: [
      { name: '承诺', type: 'bar' as const, data: v.map((p) => p.committed), itemStyle: { color: '#c6e2ff' } },
      { name: '完成', type: 'bar' as const, data: v.map((p) => p.done), itemStyle: { color: '#409eff' } },
    ],
  }
})

/** 复盘纪要 markdown（可贴进任意笔记/会议记录） */
const retroMarkdown = computed(() => {
  const d = data.value
  if (!d) return ''
  const line = (i: { code: string; title: string; status: string; type: string }) =>
    `- ${i.code} ${i.title}（${statusLabel(i.status, i.type)}）`
  return [
    `# ${d.iteration.name} 迭代复盘`,
    ``,
    `> ${d.iteration.startDate ?? ''} ~ ${d.iteration.endDate ?? ''} · 承诺 ${d.committedCount} 项，完成 ${d.doneCount} 项（${d.completionRate}%），溢出 ${d.spilloverCount}，移出 ${d.movedOutCount}`,
    ``,
    `## ✅ 已完成（${doneItems.value.length}）`,
    ...(doneItems.value.length ? doneItems.value.map(line) : ['（无）']),
    ``,
    `## ⏳ 溢出未完成（${spillItems.value.length}）`,
    ...(spillItems.value.length ? spillItems.value.map(line) : ['（无）']),
    ``,
    `## ↪️ 中途移出（${movedItems.value.length}）`,
    ...(movedItems.value.length ? movedItems.value.map(line) : ['（无）']),
    ``,
    `## 💭 讨论（现场补充）`,
    `- 做得好的：`,
    `- 待改进的：`,
    `- 下迭代行动：`,
  ].join('\n')
})

async function copyMarkdown() {
  await navigator.clipboard.writeText(retroMarkdown.value)
  ElMessage.success('复盘纪要（Markdown）已复制')
}

function doPrint() {
  window.print()
}

// ---------- 复盘现场直接沉淀经验教训（组织资产） ----------
const lessons = ref<Lesson[]>([])
const lessonForm = reactive({ category: 'IMPROVE' as string, title: '', detail: '' })

async function loadLessons() {
  if (!data.value) return
  const all = await assetsApi.lessons({ projectId: data.value.iteration.projectId })
  lessons.value = all.filter((l) => l.sourceType === 'ITERATION' && l.sourceId === iterationId)
}

async function submitLesson() {
  if (!data.value) return
  if (!lessonForm.title.trim()) return ElMessage.warning('请填写标题')
  await assetsApi.createLesson({
    projectId: data.value.iteration.projectId,
    category: lessonForm.category as Lesson['category'],
    title: lessonForm.title.trim(),
    detail: lessonForm.detail || undefined,
    sourceType: 'ITERATION',
    sourceId: iterationId,
  })
  ElMessage.success('已沉淀到组织资产')
  lessonForm.title = ''
  lessonForm.detail = ''
  await loadLessons()
}

onMounted(async () => {
  try {
    data.value = await iterationApi.retro(iterationId)
    await loadLessons()
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div class="report-wrap" v-loading="loading">
    <div class="no-print toolbar">
      <el-button type="primary" @click="doPrint">🖨 打印 / 存为 PDF</el-button>
      <el-button @click="copyMarkdown">📋 复制复盘纪要（Markdown）</el-button>
    </div>

    <div class="report-page" v-if="data">
      <header class="rp-head">
        <h1>{{ data.iteration.name }} · 迭代复盘</h1>
        <div class="rp-meta">
          <span>{{ data.iteration.code }}</span>
          <span v-if="data.iteration.startDate">{{ data.iteration.startDate }} ~ {{ data.iteration.endDate }}</span>
          <span v-if="data.iteration.goal">目标：{{ data.iteration.goal }}</span>
          <span>生成时间：{{ now }}</span>
        </div>
      </header>

      <ReportSection title="一、承诺完成情况">
        <div class="kpi-row">
          <div class="kpi"><b>{{ data.committedCount }}</b><span>承诺（拉入即承诺）</span></div>
          <div class="kpi ok"><b>{{ data.doneCount }}</b><span>完成</span></div>
          <div class="kpi" :class="{ danger: data.spilloverCount }"><b>{{ data.spilloverCount }}</b><span>溢出未完成</span></div>
          <div class="kpi"><b>{{ data.movedOutCount }}</b><span>中途移出</span></div>
          <div class="kpi" :class="data.completionRate >= 80 ? 'ok' : 'danger'"><b>{{ data.completionRate }}%</b><span>承诺完成率</span></div>
        </div>
      </ReportSection>

      <ReportSection title="二、明细（承诺快照口径，只增不删）">
        <table class="rp-table">
          <thead><tr><th style="width:130px">编号</th><th style="width:80px">类型</th><th>标题</th><th style="width:70px">估算</th><th style="width:110px">当前状态</th><th style="width:90px">结局</th></tr></thead>
          <tbody>
            <tr v-for="i in data.items" :key="i.id">
              <td class="mono">{{ i.code }}</td>
              <td>{{ typeLabel(i.type) }}</td>
              <td>{{ i.title }}</td>
              <td>{{ i.estimateSnap ?? '—' }}</td>
              <td>{{ statusLabel(i.status, i.type) }}</td>
              <td>
                <el-tag v-if="i.done" size="small" type="success">完成</el-tag>
                <el-tag v-else-if="i.movedOut" size="small" type="info">移出</el-tag>
                <el-tag v-else size="small" type="danger">溢出</el-tag>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="!data.items.length" class="rp-empty">本迭代无承诺记录。</p>
      </ReportSection>

      <ReportSection title="三、速度趋势（历次迭代 承诺 vs 完成）">
        <StaticChart v-if="data.velocity.length" :option="velocityOption" :height="260" />
        <p v-else class="rp-empty">暂无历史迭代数据。</p>
      </ReportSection>

      <ReportSection title="四、经验教训（沉淀到组织资产）">
        <!-- 录入区不打印 -->
        <div class="no-print lesson-form">
          <el-radio-group v-model="lessonForm.category" size="small">
            <el-radio-button v-for="(l, k) in LESSON_CATEGORY" :key="k" :value="k">{{ l }}</el-radio-button>
          </el-radio-group>
          <el-input v-model="lessonForm.title" placeholder="一句话结论（如：估算前先对齐验收口径）" style="margin:8px 0" @keyup.enter="submitLesson" />
          <el-input v-model="lessonForm.detail" type="textarea" :rows="2" placeholder="背景、根因、下次怎么做（可选）" />
          <el-button type="primary" size="small" style="margin-top:8px" @click="submitLesson">沉淀这条经验</el-button>
        </div>
        <table class="rp-table" v-if="lessons.length">
          <thead><tr><th style="width:90px">类别</th><th style="width:220px">结论</th><th>说明</th></tr></thead>
          <tbody>
            <tr v-for="l in lessons" :key="l.id">
              <td>{{ LESSON_CATEGORY[l.category] ?? l.category }}</td>
              <td>{{ l.title }}</td>
              <td>{{ l.detail }}</td>
            </tr>
          </tbody>
        </table>
        <div v-else class="blank-box">（尚未沉淀经验——上方录入，或打印后现场手填）</div>
        <div class="blank-box">下迭代行动：</div>
      </ReportSection>

      <footer class="rp-foot">IPD 敏捷数字化工具箱 · 承诺口径：拉入即承诺、移出不减（iteration_commitment 快照）</footer>
    </div>
  </div>
</template>

<style scoped>
.report-wrap { min-height: 100vh; background: #f0f2f5; padding: 20px 0 60px; }
.toolbar { max-width: 830px; margin: 0 auto 14px; display: flex; align-items: center; gap: 12px; }
.report-page { max-width: 830px; margin: 0 auto; background: #fff; box-shadow: 0 2px 12px rgba(0,0,0,.08); padding: 34px 40px; }
.rp-head h1 { font-size: 22px; margin: 0 0 8px; color: #1f2d3d; }
.rp-meta { display: flex; gap: 20px; color: #909399; font-size: 13px; flex-wrap: wrap; }
.rp-head { border-bottom: 3px double #409eff; padding-bottom: 14px; margin-bottom: 22px; }
.kpi-row { display: grid; grid-template-columns: repeat(5, 1fr); gap: 10px; }
.kpi { text-align: center; border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 4px; }
.kpi b { display: block; font-size: 20px; color: #409eff; }
.kpi.ok b { color: #67c23a; }
.kpi.danger b { color: #f56c6c; }
.kpi span { font-size: 12px; color: #909399; }
.rp-table { width: 100%; border-collapse: collapse; font-size: 13px; margin-top: 10px; }
.rp-table th, .rp-table td { border: 1px solid #ebeef5; padding: 6px 10px; text-align: left; }
.rp-table th { background: #f5f7fa; color: #606266; }
.mono { font-family: monospace; }
.rp-empty { color: #909399; font-size: 13px; }
.blank-box { border: 1px dashed #dcdfe6; border-radius: 6px; min-height: 56px; padding: 8px 12px; margin-bottom: 10px; color: #909399; font-size: 13px; }
.lesson-form { border: 1px solid #ebeef5; border-radius: 8px; padding: 12px; margin-bottom: 12px; background: #fafbfc; }
.rp-foot { margin-top: 26px; text-align: center; color: #c0c4cc; font-size: 12px; }
</style>
