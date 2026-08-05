<script setup lang="ts">
import { onMounted } from 'vue'
import { useUserStore } from '@/stores/users'

/** 责任人下拉：全站统一的用户选择（替代手填用户 ID）。 */
const props = defineProps<{
  modelValue?: number | null
  placeholder?: string
  size?: 'small' | 'default'
}>()
const emit = defineEmits<{ (e: 'update:modelValue', v: number | undefined): void }>()

const store = useUserStore()
onMounted(() => store.load())
</script>

<template>
  <el-select
    :model-value="props.modelValue ?? undefined"
    clearable filterable style="width:100%"
    :size="size ?? 'default'"
    :placeholder="placeholder ?? '选择责任人'"
    @update:model-value="(v: number | undefined | '') => emit('update:modelValue', v === '' ? undefined : v)"
  >
    <el-option v-for="u in store.users" :key="u.id" :label="u.displayName" :value="u.id" />
  </el-select>
</template>
