<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const menus = computed(() =>
  router.getRoutes()
    .filter((r) => r.meta?.title && r.name !== 'login')
    .map((r) => ({ path: r.path, title: r.meta!.title as string, icon: r.meta!.icon as string })),
)

const activePath = computed(() => route.path)

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="brand">IPD 敏捷工具箱</div>
      <el-menu :default-active="activePath" router class="menu" background-color="#1f2d3d" text-color="#c0ccda" active-text-color="#409eff">
        <el-menu-item v-for="m in menus" :key="m.path" :index="m.path">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="page-title">{{ route.meta.title }}</span>
        <div class="right">
          <span class="user">{{ auth.displayName }}</span>
          <el-tag v-for="r in auth.roles" :key="r" size="small" type="info" class="role-tag">{{ r }}</el-tag>
          <el-button link type="primary" @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout { height: 100vh; }
.aside { background: #1f2d3d; }
.brand { color: #fff; font-size: 16px; font-weight: 600; padding: 18px 16px; letter-spacing: 1px; }
.menu { border-right: none; }
.header { display: flex; align-items: center; justify-content: space-between; background: #fff; border-bottom: 1px solid #eee; }
.page-title { font-size: 16px; font-weight: 600; }
.right { display: flex; align-items: center; gap: 6px; }
.user { font-size: 14px; color: #333; margin-right: 4px; }
.role-tag { margin-right: 2px; }
.main { background: #f5f7fa; }
</style>
