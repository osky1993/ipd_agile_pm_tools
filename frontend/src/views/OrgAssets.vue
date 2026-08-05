<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '@/api/http'
import { assetsApi, LESSON_CATEGORY, type Lesson, type RiskPatterns, type RiskRow } from '@/api/assets'
import WorkItemDrawer from '@/components/WorkItemDrawer.vue'
import { statusLabel } from '@/utils/labels'
import { useProjectStore } from '@/stores/project'

/**
 * 组织资产：跨项目沉淀——Tab1 经验教训库（检索/登记），Tab2 风险模式库
 * （哪类风险反复出现、平均处置多久、最终结局分布；高频词点击即过滤）。
 */
interface ProjectLite { id: number; code: string; name: string }

const activeTab = ref('lessons')
const projects = ref<ProjectLite[]>([])
const projectStore = useProjectStore()

// ---------- Tab1 经验教训 ----------
const lessons = ref<Lesson[]>([])
const filter = reactive({ keyword: '', category: '', projectId: undefined as number | undefined })
const lessonDialog = ref(false)
const lessonForm = reactive({
  projectId: undefined as number | undefined,
  category: 'IMPROVE' as string,
  title: '',
  detail: '',
})

async function loadLessons() {
  lessons.value = await assetsApi.lessons({
    keyword: filter.keyword || undefined,
    category: filter.category || undefined,
    projectId: filter.projectId,
  })
}

function openLessonDialog() {
  lessonForm.projectId = projectStore.currentProjectId ?? undefined
  lessonForm.category = 'IMPROVE'
  lessonForm.title = ''
  lessonForm.detail = ''
  lessonDialog.value = true
}

async function submitLesson() {
  if (!lessonForm.projectId) return ElMessage.warning('请选择项目')
  if (!lessonForm.title.trim()) return ElMessage.warning('请填写标题')
  await assetsApi.createLesson({
    ...lessonForm,
    category: lessonForm.category as Lesson['category'],
    title: lessonForm.title.trim(),
  })
  ElMessage.success('已登记经验教训')
  lessonDialog.value = false
  await loadLessons()
}

async function removeLesson(l: Lesson) {
  try {
    await ElMessageBox.confirm(`删除经验教训「${l.title}」？`, '确认', { type: 'warning' })
  } catch {
    return
  }
  await assetsApi.deleteLesson(l.id)
  ElMessage.success('已删除')
  await loadLessons()
}

const projectCode = (id: number) => projects.value.find((p) => p.id === id)?.code ?? `#${id}`

// ---------- Tab2 风险模式库 ----------
const patterns = ref<RiskPatterns | null>(null)
const patternKeyword = ref('')
const patternsLoading = ref(false)

async function loadPatterns() {
  patternsLoading.value = true
  try {
    patterns.value = await assetsApi.riskPatterns(patternKeyword.value || undefined)
  } finally {
    patternsLoading.value = false
  }
}

function filterByWord(word: string) {
  patternKeyword.value = patternKeyword.value === word ? '' : word
  loadPatterns()
}

const drawerVisible = ref(false)
const currentId = ref<number | null>(null)
function openRisk(row: RiskRow) {
  currentId.value = row.id
  drawerVisible.value = true
}

const levelTag = (l?: string | null) => (l === 'HIGH' ? 'danger' : l === 'MED' ? 'warning' : l === 'LOW' ? 'success' : 'info')
const STRATEGY_LABEL: Record<string, string> = { AVOID: '规避', TRANSFER: '转移', MITIGATE: '减轻', ACCEPT: '接受' }

onMounted(async () => {
  projects.value = await http.get<any, ProjectLite[]>('/projects')
  await Promise.all([loadLessons(), loadPatterns()])
})
</script>

<template>
  <div>
    <el-tabs v-model="activeTab">
      <!-- Tab1 经验教训 -->
      <el-tab-pane :label="`经验教训 (${lessons.length})`" name="lessons">
        <div class="toolbar">
          <el-input v-model="filter.keyword" placeholder="搜标题/内容" clearable style="width:200px" @change="loadLessons" />
          <el-select v-model="filter.category" clearable placeholder="全部类别" style="width:130px" @change="loadLessons">
            <el-option v-for="(l, k) in LESSON_CATEGORY" :key="k" :label="l" :value="k" />
          </el-select>
          <el-select v-model="filter.projectId" clearable placeholder="全部项目" style="width:180px" @change="loadLessons">
            <el-option v-for="p in projects" :key="p.id" :label="`${p.code} ${p.name}`" :value="p.id" />
          </el-select>
          <el-button type="primary" @click="openLessonDialog"><el-icon><Plus /></el-icon>登记经验</el-button>
        </div>

        <el-table :data="lessons" border stripe>
          <el-table-column label="项目" width="110">
            <template #default="{ row }"><span class="mono">{{ projectCode(row.projectId) }}</span></template>
          </el-table-column>
          <el-table-column label="类别" width="90">
            <template #default="{ row }">
              <el-tag size="small" :type="row.category === 'WELL' ? 'success' : row.category === 'IMPROVE' ? 'warning' : 'info'">
                {{ LESSON_CATEGORY[row.category] ?? row.category }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="标题" min-width="200" />
          <el-table-column prop="detail" label="内容" show-overflow-tooltip min-width="240" />
          <el-table-column label="登记时间" width="110">
            <template #default="{ row }">{{ row.createdAt.slice(0, 10) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="70">
            <template #default="{ row }">
              <el-button link type="danger" size="small" @click="removeLesson(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <p v-if="!lessons.length" class="empty">还没有经验教训——迭代复盘页和这里都能登记，沉淀属于自己的方法论。</p>
      </el-tab-pane>

      <!-- Tab2 风险模式库 -->
      <el-tab-pane label="风险模式库" name="patterns">
        <div v-loading="patternsLoading">
          <template v-if="patterns">
            <div class="kpi-row">
              <div class="kpi"><b>{{ patterns.total }}</b><span>历史风险（含已结项目）</span></div>
              <div class="kpi ok"><b>{{ patterns.closed }}</b><span>已关闭</span></div>
              <div class="kpi"><b>{{ patterns.accepted }}</b><span>已接受</span></div>
              <div class="kpi" :class="{ danger: patterns.open }"><b>{{ patterns.open }}</b><span>仍开放</span></div>
              <div class="kpi"><b>{{ patterns.avgResolveDays ?? '—' }}</b><span>平均处置天数</span></div>
              <div class="kpi"><b>{{ patterns.byLevel.HIGH ?? 0 }}</b><span>高敞口风险</span></div>
            </div>

            <div class="words" v-if="patterns.topWords.length">
              <span class="words-label">高频词（点击过滤）：</span>
              <el-tag v-for="w in patterns.topWords" :key="w.word" class="word-chip"
                :type="patternKeyword === w.word ? 'danger' : 'info'"
                :effect="patternKeyword === w.word ? 'dark' : 'plain'"
                @click="filterByWord(w.word)">
                {{ w.word }} ×{{ w.count }}
              </el-tag>
            </div>

            <el-table :data="patterns.rows" border stripe class="clickable" @row-click="openRisk">
              <el-table-column label="项目" width="90">
                <template #default="{ row }"><span class="mono">{{ row.projectCode }}</span></template>
              </el-table-column>
              <el-table-column prop="code" label="编号" width="130">
                <template #default="{ row }"><span class="mono">{{ row.code }}</span></template>
              </el-table-column>
              <el-table-column prop="title" label="风险" show-overflow-tooltip min-width="220" />
              <el-table-column label="敞口" width="90">
                <template #default="{ row }">
                  <el-tag v-if="row.exposure != null" size="small" :type="levelTag(row.exposureLevel)">{{ row.exposure }}</el-tag>
                  <span v-else class="muted">—</span>
                </template>
              </el-table-column>
              <el-table-column label="策略" width="70">
                <template #default="{ row }">{{ row.strategy ? STRATEGY_LABEL[row.strategy] ?? row.strategy : '—' }}</template>
              </el-table-column>
              <el-table-column label="结局" width="90">
                <template #default="{ row }">{{ statusLabel(row.status, 'RISK') }}</template>
              </el-table-column>
              <el-table-column label="处置天数" width="90">
                <template #default="{ row }">{{ row.resolveDays ?? '—' }}</template>
              </el-table-column>
            </el-table>
          </template>
        </div>
      </el-tab-pane>
    </el-tabs>

    <!-- 登记经验 -->
    <el-dialog v-model="lessonDialog" title="登记经验教训" width="480px">
      <el-form label-width="64px">
        <el-form-item label="项目">
          <el-select v-model="lessonForm.projectId" style="width:100%">
            <el-option v-for="p in projects" :key="p.id" :label="`${p.code} ${p.name}`" :value="p.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="类别">
          <el-radio-group v-model="lessonForm.category">
            <el-radio-button v-for="(l, k) in LESSON_CATEGORY" :key="k" :value="k">{{ l }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="标题"><el-input v-model="lessonForm.title" /></el-form-item>
        <el-form-item label="内容"><el-input v-model="lessonForm.detail" type="textarea" :rows="3" placeholder="背景、根因、下次怎么做" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="lessonDialog = false">取消</el-button>
        <el-button type="primary" @click="submitLesson">登记</el-button>
      </template>
    </el-dialog>

    <WorkItemDrawer v-model="drawerVisible" :item-id="currentId" />
  </div>
</template>

<style scoped>
.toolbar { display: flex; gap: 10px; margin-bottom: 14px; flex-wrap: wrap; }
.kpi-row { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; margin-bottom: 14px; }
.kpi { text-align: center; background: #fff; border: 1px solid #ebeef5; border-radius: 6px; padding: 10px 4px; }
.kpi b { display: block; font-size: 20px; color: #409eff; }
.kpi.ok b { color: #67c23a; }
.kpi.danger b { color: #f56c6c; }
.kpi span { font-size: 12px; color: #909399; }
.words { margin-bottom: 12px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.words-label { font-size: 13px; color: #909399; }
.word-chip { cursor: pointer; }
.clickable :deep(.el-table__row) { cursor: pointer; }
.mono { font-family: monospace; }
.muted { color: #c0c4cc; }
.empty { color: #909399; font-size: 13px; text-align: center; padding: 18px 0; }
</style>
