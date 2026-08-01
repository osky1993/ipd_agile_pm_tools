<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '@/api/http'
import { treeApi, type TreeNode } from '@/api/catalog'
import WorkItemDrawer from '@/components/WorkItemDrawer.vue'

interface Project { id: number; code: string; name: string }

const projects = ref<Project[]>([])
const projectId = ref<number | null>(null)
const tree = ref<TreeNode[]>([])
const loading = ref(false)

const drawerVisible = ref(false)
const currentId = ref<number | null>(null)

const childDialog = ref(false)
const childForm = reactive({ parentId: null as number | null, type: 'REQUIREMENT', title: '' })
const CHILD_TYPES = [
  { value: 'CAPABILITY', label: '产品能力' },
  { value: 'REQUIREMENT', label: '需求' },
  { value: 'STORY', label: '用户故事' },
  { value: 'TASK', label: '任务' },
]
const TYPE_LABEL: Record<string, string> = {
  CAPABILITY: '能力', REQUIREMENT: '需求', STORY: '故事', TASK: '任务',
}
const TYPE_COLOR: Record<string, string> = {
  CAPABILITY: '', REQUIREMENT: 'success', STORY: 'warning', TASK: 'info',
}
const statusType = (s: string) =>
  ['Accepted', 'Closed', 'Verified'].includes(s) ? 'success'
    : ['Verification'].includes(s) ? 'warning' : 'info'

async function loadTree() {
  if (!projectId.value) return
  loading.value = true
  try {
    tree.value = await treeApi.get(projectId.value)
  } finally {
    loading.value = false
  }
}

function openDetail(node: TreeNode) {
  currentId.value = node.id
  drawerVisible.value = true
}

function openAddChild(parent: TreeNode | null) {
  childForm.parentId = parent ? parent.id : null
  childForm.type = parent ? 'REQUIREMENT' : 'CAPABILITY'
  childForm.title = ''
  childDialog.value = true
}

async function submitChild() {
  if (!projectId.value || !childForm.title) return ElMessage.warning('请填写标题')
  await treeApi.createChild(childForm.parentId, {
    projectId: projectId.value,
    type: childForm.type,
    title: childForm.title,
  })
  ElMessage.success('已创建')
  childDialog.value = false
  await loadTree()
}

onMounted(async () => {
  projects.value = await http.get<any, Project[]>('/projects')
  if (projects.value.length) {
    projectId.value = projects.value[0].id
    await loadTree()
  }
})
</script>

<template>
  <div>
    <div class="toolbar">
      <el-select v-model="projectId" placeholder="选择项目" style="width:220px" @change="loadTree">
        <el-option v-for="p in projects" :key="p.id" :label="`${p.code} ${p.name}`" :value="p.id" />
      </el-select>
      <el-button type="primary" @click="openAddChild(null)"><el-icon><Plus /></el-icon>新建根节点（能力）</el-button>
    </div>

    <el-card shadow="never" v-loading="loading">
      <el-tree
        :data="tree"
        node-key="id"
        default-expand-all
        :expand-on-click-node="false"
        empty-text="暂无能力/需求，点击右上角新建根节点"
      >
        <template #default="{ data }">
          <div class="node">
            <el-tag size="small" :type="TYPE_COLOR[data.type]">{{ TYPE_LABEL[data.type] }}</el-tag>
            <span class="code">{{ data.code }}</span>
            <span class="title">{{ data.title }}</span>
            <el-tag size="small" :type="statusType(data.status)" class="status">{{ data.status }}</el-tag>
            <span class="actions">
              <el-button link type="primary" size="small" @click.stop="openDetail(data)">详情</el-button>
              <el-button link type="primary" size="small" @click.stop="openAddChild(data)">+子项</el-button>
            </span>
          </div>
        </template>
      </el-tree>
    </el-card>

    <WorkItemDrawer v-model="drawerVisible" :item-id="currentId" @changed="loadTree" />

    <el-dialog v-model="childDialog" :title="childForm.parentId ? '新建子项' : '新建根节点'" width="440px">
      <el-form label-width="72px">
        <el-form-item label="类型">
          <el-select v-model="childForm.type" style="width:100%">
            <el-option v-for="t in CHILD_TYPES" :key="t.value" :label="t.label" :value="t.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="标题"><el-input v-model="childForm.title" placeholder="标题" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="childDialog = false">取消</el-button>
        <el-button type="primary" @click="submitChild">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.toolbar { display: flex; justify-content: space-between; align-items: center; margin-bottom: 16px; }
.node { display: flex; align-items: center; gap: 10px; width: 100%; }
.node .code { color: #606266; font-size: 13px; font-family: monospace; }
.node .title { flex: 1; }
.node .status { margin-left: auto; }
.node .actions { display: flex; gap: 2px; }
</style>
