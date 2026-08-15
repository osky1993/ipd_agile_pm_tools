<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { myApi, type MyToday, type MyItem, type Alert } from '@/api/my'
import WorkItemDrawer from '@/components/WorkItemDrawer.vue'
import { statusLabel, typeLabel, severityLabel } from '@/utils/labels'

/**
 * 「我的一天」跨项目工作台（默认首页）：
 * 进行中 / 已超期 / 待复测 / 迭代与 DCP 四组卡片 + 今日焦点置顶（localStorage，跨天清空）。
 */
const router = useRouter()
const loading = ref(false)
const data = ref<MyToday | null>(null)

const drawerVisible = ref(false)
const currentId = ref<number | null>(null)

// ---------- 今日焦点（本地置顶） ----------
const FOCUS_KEY = 'myfocus'
const todayStr = new Date().toISOString().slice(0, 10)
const focusIds = ref<number[]>([])

function loadFocus() {
  try {
    const raw = JSON.parse(localStorage.getItem(FOCUS_KEY) || '{}')
    focusIds.value = raw.date === todayStr && Array.isArray(raw.ids) ? raw.ids : []
  } catch {
    focusIds.value = []
  }
}
function toggleFocus(id: number) {
  focusIds.value = focusIds.value.includes(id)
    ? focusIds.value.filter((x) => x !== id)
    : [...focusIds.value, id]
  localStorage.setItem(FOCUS_KEY, JSON.stringify({ date: todayStr, ids: focusIds.value }))
}
const isFocused = (id: number) => focusIds.value.includes(id)

const allItems = computed<MyItem[]>(() =>
  data.value ? [...data.value.inProgress, ...data.value.overdue, ...data.value.retest] : [])
const focusItems = computed(() => {
  const seen = new Set<number>()
  return allItems.value.filter((w) => isFocused(w.id) && !seen.has(w.id) && seen.add(w.id))
})

async function load() {
  loading.value = true
  try {
    data.value = await myApi.today()
  } finally {
    loading.value = false
  }
}

function openItem(w: MyItem) {
  currentId.value = w.id
  drawerVisible.value = true
}

function openAlert(a: Alert) {
  if (a.refType === 'WORK_ITEM') {
    currentId.value = a.refId
    drawerVisible.value = true
  } else if (a.refType === 'GATE_CRITERION' || a.refType === 'STAGE_GATE') {
    router.push('/dcp')
  } else {
    router.push('/trace')
  }
}

const sevType = (s: string) => (s === 'HIGH' ? 'danger' : s === 'MED' ? 'warning' : 'info')

onMounted(() => {
  loadFocus()
  load()
})
</script>

<template>
  <div v-loading="loading">
    <!-- 今日焦点 -->
    <div v-if="focusItems.length" class="focus-zone">
      <div class="zone-title">📌 今日焦点</div>
      <div class="card-list">
        <div v-for="w in focusItems" :key="'f' + w.id" class="card focus" @click="openItem(w)">
          <div class="card-top">
            <span class="code">{{ w.projectCode }} · {{ w.code }}</span>
            <el-icon class="pin active" @click.stop="toggleFocus(w.id)"><Star /></el-icon>
          </div>
          <div class="title">{{ w.title }}</div>
          <div class="meta">
            <el-tag size="small">{{ typeLabel(w.type) }}</el-tag>
            <el-tag size="small" type="info">{{ statusLabel(w.status, w.type) }}</el-tag>
            <span v-if="w.priority" class="prio">{{ w.priority }}</span>
          </div>
        </div>
      </div>
    </div>

    <div class="grid">
      <!-- 进行中 -->
      <section class="col">
        <h3>进行中 <em>{{ data?.inProgress.length ?? 0 }}</em></h3>
        <div class="card-list">
          <div v-for="w in data?.inProgress" :key="w.id" class="card" @click="openItem(w)">
            <div class="card-top">
              <span class="code">{{ w.projectCode }} · {{ w.code }}</span>
              <el-icon class="pin" :class="{ active: isFocused(w.id) }" @click.stop="toggleFocus(w.id)"><Star /></el-icon>
            </div>
            <div class="title">{{ w.title }}</div>
            <div class="meta">
              <el-tag size="small">{{ typeLabel(w.type) }}</el-tag>
              <el-tag size="small" type="info">{{ statusLabel(w.status, w.type) }}</el-tag>
              <span v-if="w.priority" class="prio">{{ w.priority }}</span>
            </div>
          </div>
          <div v-if="!data?.inProgress.length" class="empty">没有进行中的事项 🎉</div>
        </div>
      </section>

      <!-- 已超期 -->
      <section class="col">
        <h3 class="danger">已超期 <em>{{ data?.overdue.length ?? 0 }}</em></h3>
        <div class="card-list">
          <div v-for="w in data?.overdue" :key="w.id" class="card overdue" @click="openItem(w)">
            <div class="card-top">
              <span class="code">{{ w.projectCode }} · {{ w.code }}</span>
              <el-icon class="pin" :class="{ active: isFocused(w.id) }" @click.stop="toggleFocus(w.id)"><Star /></el-icon>
            </div>
            <div class="title">{{ w.title }}</div>
            <div class="meta">
              <el-tag size="small">{{ typeLabel(w.type) }}</el-tag>
              <el-tag size="small" type="danger">期限 {{ w.due }}</el-tag>
            </div>
          </div>
          <div v-if="!data?.overdue.length" class="empty">无超期事项</div>
        </div>
      </section>

      <!-- 待复测 -->
      <section class="col">
        <h3 class="warning">待复测缺陷 <em>{{ data?.retest.length ?? 0 }}</em></h3>
        <div class="card-list">
          <div v-for="w in data?.retest" :key="w.id" class="card" @click="openItem(w)">
            <div class="card-top">
              <span class="code">{{ w.projectCode }} · {{ w.code }}</span>
              <el-icon class="pin" :class="{ active: isFocused(w.id) }" @click.stop="toggleFocus(w.id)"><Star /></el-icon>
            </div>
            <div class="title">{{ w.title }}</div>
            <div class="meta"><el-tag size="small" type="warning">复测中</el-tag></div>
          </div>
          <div v-if="!data?.retest.length" class="empty">无待复测缺陷</div>
        </div>
      </section>

      <!-- 迭代与预警 -->
      <section class="col">
        <h3>迭代与 DCP <em>{{ (data?.endingSoon.length ?? 0) + (data?.projectAlerts.length ?? 0) }}</em></h3>
        <div class="card-list">
          <div v-for="it in data?.endingSoon" :key="'it' + it.id" class="card static">
            <div class="card-top"><span class="code">{{ it.projectCode }} · {{ it.code }}</span></div>
            <div class="title">{{ it.name }}</div>
            <div class="meta">
              <el-tag size="small" :type="it.daysLeft <= 2 ? 'danger' : 'warning'">
                {{ it.daysLeft <= 0 ? '今日截止' : `${it.daysLeft} 天后结束` }}
              </el-tag>
              <span class="sub">我的未完项 {{ it.myOpenCount }}</span>
            </div>
          </div>
          <div v-for="(a, i) in data?.projectAlerts" :key="'a' + i" class="card" @click="openAlert(a)">
            <div class="card-top"><span class="code">{{ a.refCode }}</span></div>
            <div class="title">{{ a.title }}</div>
            <div class="meta">
              <el-tag size="small" :type="sevType(a.severity)">{{ severityLabel(a.severity) }}</el-tag>
              <span class="sub detail">{{ a.detail }}</span>
            </div>
          </div>
          <div v-if="!data?.endingSoon.length && !data?.projectAlerts.length" class="empty">无临近事项与关键预警</div>
        </div>
      </section>
    </div>

    <WorkItemDrawer v-model="drawerVisible" :item-id="currentId" @changed="load" />
  </div>
</template>

<style scoped>
.grid { display: grid; grid-template-columns: repeat(4, 1fr); gap: 14px; align-items: start; }
@media (max-width: 1400px) { .grid { grid-template-columns: repeat(2, 1fr); } }
.col h3 { font-size: 14px; color: #303133; margin: 0 0 10px; }
.col h3 em { font-style: normal; color: #909399; font-weight: 400; margin-left: 4px; }
.col h3.danger { color: #f56c6c; }
.col h3.warning { color: #e6a23c; }

.focus-zone { background: #fdf6ec; border: 1px solid #faecd8; border-radius: 8px; padding: 12px 14px; margin-bottom: 16px; }
.zone-title { font-size: 14px; font-weight: 600; color: #b88230; margin-bottom: 10px; }
.focus-zone .card-list { display: grid; grid-template-columns: repeat(4, 1fr); gap: 10px; }
@media (max-width: 1400px) { .focus-zone .card-list { grid-template-columns: repeat(2, 1fr); } }

.card-list { display: flex; flex-direction: column; gap: 8px; }
.card { background: #fff; border: 1px solid #ebeef5; border-radius: 8px; padding: 10px 12px; cursor: pointer; transition: box-shadow .15s; }
.card:hover { box-shadow: 0 2px 10px rgba(0,0,0,.08); }
.card.static { cursor: default; }
.card.overdue { border-left: 3px solid #f56c6c; }
.card.focus { border-left: 3px solid #e6a23c; }
.card-top { display: flex; justify-content: space-between; align-items: center; }
.code { font-family: monospace; font-size: 12px; color: #909399; }
.pin { color: #dcdfe6; cursor: pointer; }
.pin:hover { color: #e6a23c; }
.pin.active { color: #e6a23c; }
.title { font-size: 13px; color: #303133; margin: 6px 0; line-height: 1.4; }
.meta { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.prio { font-size: 12px; color: #909399; }
.sub { font-size: 12px; color: #909399; }
.sub.detail { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }
.empty { color: #c0c4cc; font-size: 13px; padding: 12px 0; text-align: center; border: 1px dashed #ebeef5; border-radius: 8px; }
</style>
