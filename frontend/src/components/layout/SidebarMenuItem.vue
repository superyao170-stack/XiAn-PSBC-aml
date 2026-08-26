<template>
  <el-sub-menu v-if="menu.children?.length" :index="menu.path">
    <template #title>
      <el-icon v-if="menu.icon"><component :is="getIcon(menu.icon)" /></el-icon>
      <span>{{ menu.menuName }}</span>
    </template>
    <SidebarMenuItem
      v-for="child in menu.children"
      :key="child.id"
      :menu="child"
      :get-icon="getIcon"
    />
  </el-sub-menu>
  <el-menu-item v-else :index="menu.path">
    <el-icon v-if="menu.icon"><component :is="getIcon(menu.icon)" /></el-icon>
    <template #title>{{ menu.menuName }}</template>
  </el-menu-item>
</template>

<script setup lang="ts">
defineOptions({ name: 'SidebarMenuItem' })

defineProps<{
  menu: any
  getIcon: (icon: string) => any
}>()
</script>
