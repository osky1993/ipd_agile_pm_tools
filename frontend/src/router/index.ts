import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

// 第一版 7 个主要页面（规划§11）
const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('@/views/Login.vue'), meta: { public: true } },
  // 多项目聚合大屏：独立于 MainLayout 的投屏页（菜单由 getRoutes 自动收录）
  { path: '/bigscreen', name: 'bigscreen', component: () => import('@/views/ExecBoard.vue'), meta: { title: '数据大屏', icon: 'DataBoard' } },
  // 团队协作屏：上下游依赖 + 阻塞 + 交接（同为独立投屏页）
  { path: '/teamboard', name: 'teamboard', component: () => import('@/views/Teamboard.vue'), meta: { title: '团队协作屏', icon: 'Connection' } },
  // 打印友好报告页（hidden：不进侧边栏菜单；独立于 MainLayout 便于 A4 排版）
  { path: '/report/project/:projectId', name: 'report-project', component: () => import('@/views/report/ProjectReport.vue'), meta: { title: '项目状态报告', hidden: true } },
  { path: '/report/dcp/:gateId', name: 'report-dcp', component: () => import('@/views/report/DcpReport.vue'), meta: { title: 'DCP 决策包', hidden: true } },
  { path: '/report/weekly/:projectId', name: 'report-weekly', component: () => import('@/views/report/WeeklyReport.vue'), meta: { title: '项目周报', hidden: true } },
  { path: '/report/retro/:iterationId', name: 'report-retro', component: () => import('@/views/report/RetroReport.vue'), meta: { title: '迭代复盘', hidden: true } },
  {
    path: '/',
    component: () => import('@/layout/MainLayout.vue'),
    redirect: '/my',
    children: [
      { path: 'my', name: 'my', component: () => import('@/views/MyToday.vue'), meta: { title: '我的一天', icon: 'Sunny' } },
      { path: 'dashboard', name: 'dashboard', component: () => import('@/views/Dashboard.vue'), meta: { title: '项目驾驶舱', icon: 'DataLine' } },
      { path: 'projects', name: 'projects', component: () => import('@/views/Projects.vue'), meta: { title: '项目·版本·阶段', icon: 'Folder' } },
      { path: 'roadmap', name: 'roadmap', component: () => import('@/views/Roadmap.vue'), meta: { title: '路标图', icon: 'Calendar' } },
      { path: 'baseline', name: 'baseline', component: () => import('@/views/BaselineBoard.vue'), meta: { title: '基线管理', icon: 'Collection' } },
      { path: 'dcp', name: 'dcp', component: () => import('@/views/DcpBoard.vue'), meta: { title: 'DCP准入条件', icon: 'CircleCheck' } },
      { path: 'requirements', name: 'requirements', component: () => import('@/views/RequirementTree.vue'), meta: { title: '能力与需求树', icon: 'Share' } },
      { path: 'board', name: 'board', component: () => import('@/views/Board.vue'), meta: { title: 'Sprint看板', icon: 'Grid' } },
      { path: 'quality', name: 'quality', component: () => import('@/views/TestDefect.vue'), meta: { title: '测试·缺陷·变更', icon: 'Warning' } },
      { path: 'trace', name: 'trace', component: () => import('@/views/Governance.vue'), meta: { title: '追溯·风险·证据·决策', icon: 'Connection' } },
      { path: 'performance', name: 'performance', component: () => import('@/views/Performance.vue'), meta: { title: '效能改进', icon: 'TrendCharts' } },
      { path: 'workitems', name: 'workitems', component: () => import('@/views/WorkItems.vue'), meta: { title: '工作项清单', icon: 'Tickets' } },
    ],
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach((to) => {
  const token = localStorage.getItem('token')
  if (!to.meta.public && !token) {
    return { path: '/login' }
  }
  if (to.path === '/login' && token) {
    return { path: '/my' }
  }
  return true
})

export default router
