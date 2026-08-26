
<template>
  <router-view />
</template>

<script setup lang="ts">
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

body {
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', Arial, sans-serif;
}

/* Element Plus 的行内 el-select 会按内容收缩，统一保证筛选项可完整显示。 */
.el-form--inline .el-form-item .el-select {
  width: 190px;
  min-width: 190px;
}

/* 弹窗业务表单中的下拉框使用整行宽度，避免长名称被过度截断。 */
.el-dialog .el-form:not(.el-form--inline) .el-select {
  width: 100%;
}

/* 业务列表统一占满容器；操作列贴右、按按钮内容收紧。 */
.adaptive-list-table {
  width: 100%;
}

.table-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  width: 100%;
  white-space: nowrap;
}

.operation-column .cell {
  padding-left: 6px !important;
  padding-right: 8px !important;
  text-align: right;
}

/* 详情键值表保持等宽分栏，长ID、哈希和URL只在自身单元格内换行。 */
.balanced-descriptions .el-descriptions__table {
  width: 100%;
  table-layout: fixed;
}

.balanced-descriptions .el-descriptions__label {
  width: 92px;
  min-width: 92px;
  white-space: nowrap;
}

.balanced-descriptions .el-descriptions__content {
  min-width: 0;
  overflow-wrap: anywhere;
  word-break: break-word;
}

.long-detail-value {
  display: block;
  max-width: 100%;
  overflow-wrap: anywhere;
  word-break: break-all;
  line-height: 1.45;
}
</style>
