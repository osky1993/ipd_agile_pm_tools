<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { treeApi, type TreeNode } from '@/api/catalog'
import WorkItemDrawer from '@/components/WorkItemDrawer.vue'
import ProjectChips from '@/components/ProjectChips.vue'
import { statusLabel } from '@/utils/labels'
import { workItemApi } from '@/api/workitem'

const projectId = ref<number | null>(null)
const tree = ref<TreeNode[]>([])
const loading = ref(false)

const importVisible = ref(false)
const importFile = ref<File | null>(null)
const importResult = ref<{ created: number; errors: string[] } | null>(null)

function onImportFile(e: Event) {
  const files = (e.target as HTMLInputElement).files
  importFile.value = files && files.length ? files[0] : null
  importResult.value = null
}
async function submitImport() {
  if (!projectId.value || !importFile.value) return ElMessage.warning('请选择 Excel 文件')
  importResult.value = await workItemApi.importTree(projectId.value, importFile.value)
  ElMessage.success(`已导入 ${importResult.value.created} 条`)
  await loadTree()
}

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

</script>

<template>
  <div>
    <div class="toolbar">
      <ProjectChips v-model="projectId" class="chips-flex" @change="loadTree" />
      <el-button @click="importVisible = true"><el-icon><Upload /></el-icon>导入 Excel</el-button>
      <el-button type="primary" @click="openAddChild(null)"><el-icon><Plus /></el-icon>新建根节点（能力）</el-button>
    </div>

    <!-- Excel 导入（层级序号表达树） -->
    <el-dialog v-model="importVisible" title="Excel 导入能力与需求树" width="560px">
      <p class="hint">
        用「序号」列表达层级：<code>1</code>=能力 → <code>1.1</code>=需求 → <code>1.1.1</code>=故事（更深为任务）；
        类型列可留空按层级自动推断，也可写中文覆盖（如"任务"）。父级行须先于子级出现。
        <a :href="workItemApi.treeTemplateUrl()" class="tpl">⬇ 下载模板</a>
      </p>
      <input type="file" accept=".xlsx" @change="onImportFile" />
      <div v-if="importResult" class="imp-result">
        <el-alert :type="importResult.errors.length ? 'warning' : 'success'" :closable="false" show-icon>
          成功导入 {{ importResult.created }} 条{{ importResult.errors.length ? `，失败 ${importResult.errors.length} 条` : '' }}
        </el-alert>
        <ul v-if="importResult.errors.length" class="imp-errors">
          <li v-for="(e, i) in importResult.errors" :key="i">{{ e }}</li>
        </ul>
      </div>
      <template #footer>
        <el-button @click="importVisible = false">关闭</el-button>
        <el-button type="primary" @click="submitImport">导入</el-button>
      </template>
    </el-dialog>

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
            <el-tag size="small" :type="statusType(data.status)" class="status">{{ statusLabel(data.status) }}</el-tag>
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
.hint { color: #909399; font-size: 12px; margin: 0 0 10px; line-height: 1.8; }
.hint code { color: #409eff; background: #f0f7ff; padding: 1px 5px; border-radius: 4px; }
.tpl { color: #409eff; text-decoration: none; margin-left: 8px; }
.imp-result { margin-top: 12px; }
.imp-errors { color: #e6a23c; font-size: 12px; margin: 8px 0 0; padding-left: 18px; }
.toolbar { display: flex; gap: 12px; align-items: flex-start; margin-bottom: 16px; }
.chips-flex { flex: 1; }
.node { display: flex; align-items: center; gap: 10px; width: 100%; }
.node .code { color: #606266; font-size: 13px; font-family: monospace; }
.node .title { flex: 1; }
.node .status { margin-left: auto; }
.node .actions { display: flex; gap: 2px; }
</style>
