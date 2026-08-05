<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { workItemApi, type WorkItem } from '@/api/workitem'
import WorkItemDrawer from '@/components/WorkItemDrawer.vue'
import QuickCreateDialog from '@/components/QuickCreateDialog.vue'
import { typeLabel } from '@/utils/labels'
import { useHotkeys } from '@/utils/hotkeys'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

/**
 * 两级菜单：高频入口（我的一天/驾驶舱）保持一级，其余按 IPD 业务域分组。
 * 标题与图标仍取自路由 meta（单一来源）；新增页面须在此登记所属分组。
 */
interface MenuLeaf { kind: 'leaf'; path: string; title: string; icon: string }
interface MenuGroup { kind: 'group'; title: string; icon: string; children: Omit<MenuLeaf, 'kind'>[] }

const MENU_DEF: (string | { title: string; icon: string; children: string[] })[] = [
  '/my',
  '/dashboard',
  { title: '规划与基线', icon: 'Calendar', children: ['/projects', '/roadmap', '/baseline'] },
  { title: '需求与交付', icon: 'Grid', children: ['/requirements', '/board', '/quality', '/workitems'] },
  { title: '决策与治理', icon: 'CircleCheck', children: ['/dcp', '/trace'] },
  { title: '效能与资产', icon: 'TrendCharts', children: ['/performance', '/assets'] },
  { title: '投屏', icon: 'Monitor', children: ['/bigscreen', '/teamboard'] },
]

const menuTree = computed<(MenuLeaf | MenuGroup)[]>(() => {
  const byPath = new Map(router.getRoutes()
    .filter((r) => r.meta?.title)
    .map((r) => [r.path, { title: r.meta!.title as string, icon: r.meta!.icon as string }]))
  const leaf = (p: string) => ({ path: p, title: byPath.get(p)?.title ?? p, icon: byPath.get(p)?.icon ?? 'Menu' })
  return MENU_DEF.map((def) =>
    typeof def === 'string'
      ? { kind: 'leaf' as const, ...leaf(def) }
      : { kind: 'group' as const, title: def.title, icon: def.icon, children: def.children.map(leaf) })
})

const activePath = computed(() => route.path)
/** 当前页所在分组默认展开 */
const defaultOpeneds = computed(() => {
  for (const m of menuTree.value) {
    if (m.kind === 'group' && m.children.some((c) => c.path === route.path)) return [m.title]
  }
  return []
})

// 全局搜索（编号/标题 → 打开详情抽屉）
const searchQ = ref('')
const drawerVisible = ref(false)
const currentId = ref<number | null>(null)

async function fetchSuggestions(q: string, cb: (items: { value: string; item: WorkItem }[]) => void) {
  if (!q || !q.trim()) return cb([])
  const items = await workItemApi.search(q.trim())
  cb(items.map((w) => ({ value: `${w.code} ${w.title}`, item: w })))
}
function onSelect(s: { item: WorkItem }) {
  currentId.value = s.item.id
  drawerVisible.value = true
  searchQ.value = ''
}

function logout() {
  auth.logout()
  router.push('/login')
}

// ---------- 全局快捷键 ----------
const searchRef = ref<{ focus: () => void } | null>(null)
const quickCreateVisible = ref(false)
const hotkeyHelpVisible = ref(false)

function onQuickCreated(item: WorkItem) {
  currentId.value = item.id
  drawerVisible.value = true
}

const GOTO: Record<string, string> = {
  m: '/my', d: '/dashboard', p: '/projects', r: '/requirements',
  b: '/board', q: '/quality', t: '/trace', e: '/performance', w: '/workitems', l: '/roadmap',
}
useHotkeys({
  '/': () => searchRef.value?.focus(),
  n: () => { quickCreateVisible.value = true },
  '?': () => { hotkeyHelpVisible.value = true },
  ...Object.fromEntries(Object.entries(GOTO).map(([k, path]) => [`g ${k}`, () => router.push(path)])),
})

const HOTKEY_HELP: [string, string][] = [
  ['/', '聚焦全局搜索'],
  ['n', '快速新建工作项'],
  ['g m', '我的一天'], ['g d', '项目驾驶舱'], ['g p', '项目·版本·阶段'], ['g l', '路标图'],
  ['g r', '能力与需求树'], ['g b', 'Sprint看板'], ['g q', '测试·缺陷·变更'],
  ['g t', '追溯·风险·证据·决策'], ['g e', '效能改进'], ['g w', '工作项清单'],
  ['?', '本速查表'],
]
</script>

<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="brand">IPD 敏捷工具箱</div>
      <el-menu :default-active="activePath" :default-openeds="defaultOpeneds" router unique-opened
        class="menu" background-color="#1f2d3d" text-color="#c0ccda" active-text-color="#409eff">
        <template v-for="m in menuTree" :key="m.kind === 'leaf' ? m.path : m.title">
          <el-menu-item v-if="m.kind === 'leaf'" :index="m.path">
            <el-icon><component :is="m.icon" /></el-icon>
            <span>{{ m.title }}</span>
          </el-menu-item>
          <el-sub-menu v-else :index="m.title">
            <template #title>
              <el-icon><component :is="m.icon" /></el-icon>
              <span>{{ m.title }}</span>
            </template>
            <el-menu-item v-for="c in m.children" :key="c.path" :index="c.path" class="sub-item">
              <el-icon><component :is="c.icon" /></el-icon>
              <span>{{ c.title }}</span>
            </el-menu-item>
          </el-sub-menu>
        </template>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="page-title">{{ route.meta.title }}</span>
        <div class="right">
          <el-autocomplete ref="searchRef" v-model="searchQ" :fetch-suggestions="fetchSuggestions" placeholder="搜索工作项（ / 聚焦）"
            clearable style="width:260px" :trigger-on-focus="false" @select="onSelect">
            <template #default="{ item }">
              <el-tag size="small" type="info" style="margin-right:6px">{{ typeLabel(item.item.type) }}</el-tag>
              <span class="s-code">{{ item.item.code }}</span> {{ item.item.title }}
            </template>
          </el-autocomplete>
          <span class="user">{{ auth.displayName }}</span>
          <el-tag v-for="r in auth.roles" :key="r" size="small" type="info" class="role-tag">{{ r }}</el-tag>
          <el-button link type="primary" @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
    <WorkItemDrawer v-model="drawerVisible" :item-id="currentId" />
    <QuickCreateDialog v-model="quickCreateVisible" @created="onQuickCreated" />
    <el-dialog v-model="hotkeyHelpVisible" title="键盘快捷键" width="420px">
      <table class="hk-table">
        <tr v-for="[k, desc] in HOTKEY_HELP" :key="k">
          <td class="hk-key"><kbd>{{ k }}</kbd></td>
          <td>{{ desc }}</td>
        </tr>
      </table>
    </el-dialog>
  </el-container>
</template>

<style scoped>
.layout { height: 100vh; }
.aside { background: #1f2d3d; }
.brand { color: #fff; font-size: 16px; font-weight: 600; padding: 18px 16px; letter-spacing: 1px; }
.menu { border-right: none; }
.menu :deep(.el-sub-menu .el-menu-item) { background: #19232f; padding-left: 44px !important; }
.menu :deep(.el-sub-menu .el-menu-item:hover) { background: #223142; }
.menu :deep(.el-sub-menu .el-menu-item.is-active) { background: #223142; }
.header { display: flex; align-items: center; justify-content: space-between; background: #fff; border-bottom: 1px solid #eee; }
.page-title { font-size: 16px; font-weight: 600; }
.right { display: flex; align-items: center; gap: 10px; }
.user { font-size: 14px; color: #333; margin-right: 4px; }
.role-tag { margin-right: 2px; }
.main { background: #f5f7fa; }
.s-code { font-family: monospace; color: #909399; }
.hk-table { width: 100%; border-collapse: collapse; font-size: 13px; }
.hk-table td { padding: 5px 8px; border-bottom: 1px solid #f5f7fa; }
.hk-key { width: 70px; }
.hk-table kbd { background: #f5f7fa; border: 1px solid #dcdfe6; border-radius: 4px; padding: 1px 7px; font-family: monospace; font-size: 12px; }
</style>
