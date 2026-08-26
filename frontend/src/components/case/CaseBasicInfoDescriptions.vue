<template>
  <div class="overview-description-stack">
    <a-descriptions
      v-if="compactFields.length"
      :column="{ xs: 1, sm: 2, lg: 3 }"
      bordered
      size="small"
      class="overview-descriptions"
    >
      <a-descriptions-item
        v-for="field in compactFields"
        :key="field.key || field.label"
        :label="field.label"
      >
        {{ field.value }}
      </a-descriptions-item>
    </a-descriptions>
    <a-descriptions
      v-if="wideFields.length"
      :column="1"
      bordered
      size="small"
      class="overview-descriptions overview-long-descriptions"
    >
      <a-descriptions-item
        v-for="field in wideFields"
        :key="field.key || field.label"
        :label="field.label"
      >
        {{ field.value }}
      </a-descriptions-item>
    </a-descriptions>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import {
  Descriptions as ADescriptions,
  DescriptionsItem as ADescriptionsItem
} from 'ant-design-vue'

type BasicInfoField = {
  key?: string
  label: string
  value: unknown
  wide?: boolean
}

const props = defineProps<{ fields: BasicInfoField[] }>()
const deduplicatedFields = computed(() => {
  const labels = new Set<string>()
  return props.fields.filter(field => {
    const label = String(field.label || '').trim()
    if (!label || labels.has(label)) return false
    labels.add(label)
    return true
  })
})
const compactFields = computed(() => deduplicatedFields.value.filter(field => !field.wide))
const wideFields = computed(() => deduplicatedFields.value.filter(field => field.wide))
</script>

<style scoped src="@/styles/case-overview-shared.scss"></style>
