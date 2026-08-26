<template>
  <div class="case-knowledge-explanation-panel">
    <section>
      <div class="cross-domain-title"><b>多阶段事理分析</b></div>
      <div v-if="multiStageMatter" class="multi-stage-matter">
        <div>
          <b>综合事理判断</b>
          <span>{{ multiStageMatter.stageCount }} {{ multiStageMatter.chainLabel || '条战术链' }}</span>
        </div>
        <p>{{ multiStageMatter.summary }}</p>
        <em v-if="multiStageMatter.tactics?.length">{{ multiStageMatter.tactics.join(' → ') }}</em>
        <div class="matter-conclusions">
          <h4>事理结论</h4>
          <p class="matter-conclusion-source">
            {{ multiStageMatter.sourceDescription || '来源：统一案件知识解释链接口。' }}
          </p>
          <article v-for="(conclusion,index) in multiStageMatter.conclusions || []" :key="index">
            <b>{{ multiStageMatter.itemLabel || '战术链' }} {{ index + 1 }}</b>
            <p>{{ conclusion }}</p>
          </article>
        </div>
      </div>
      <el-empty
        v-else
        :image-size="48"
        description="当前案例尚未形成基于 AMLTRIX 技术—战术映射的多阶段事理分析"
      />
    </section>

    <section>
      <div class="cross-domain-title"><b>关联知识链路</b></div>
      <div v-if="chains.length" class="knowledge-chain-table-wrap">
        <table class="knowledge-chain-table">
          <colgroup>
            <col class="col-fact"><col class="col-indicator"><col class="col-technique">
            <col class="col-tactic"><col class="col-pattern"><col class="col-risk">
            <col class="col-relation">
          </colgroup>
          <thead>
            <tr class="knowledge-chain-groups">
              <th colspan="4">AMLTRIX 技术—战术解释链</th>
              <th colspan="3">关联传统推理</th>
            </tr>
            <tr>
              <th>事件与证据</th><th>指标结果</th><th>AMLTRIX 技术</th>
              <th>AMLTRIX 战术</th><th>行为模式</th><th>风险假设</th><th>关联作用</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="chain in chains" :key="chain.key">
              <td class="fact">
                <el-button
                  v-if="interactive && chain.eventRefs?.length"
                  link
                  type="primary"
                  @click="emit('openFact', chain)"
                >{{ chain.fact }}</el-button>
                <span v-else>{{ chain.fact }}</span>
                <small v-if="chain.evidenceCount">文本证据：{{ chain.evidenceCount }}处</small>
              </td>
              <td class="indicator">
                <span>{{ chain.indicator }}</span>
                <small>{{ indicatorModeText(chain.indicatorMode) }}</small>
              </td>
              <td class="technique">{{ chain.technique }}</td>
              <td class="tactic">{{ chain.tactic }}</td>
              <td class="pattern">{{ chain.pattern }}</td>
              <td class="risk">{{ chain.risk }}</td>
              <td class="relation">{{ chain.relation }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <el-empty v-else :image-size="48" description="暂无关联知识链路" />
    </section>
  </div>
</template>

<script setup lang="ts">
withDefaults(defineProps<{
  multiStageMatter?: any
  chains?: any[]
  interactive?: boolean
}>(), {
  multiStageMatter: null,
  chains: () => [],
  interactive: false
})

const emit = defineEmits<{
  openFact: [chain: any]
}>()

const indicatorModeText = (value: any) => ({
  EXECUTED: '正式指标执行',
  SEMANTIC_MATCH: '正式指标语义匹配',
  MIXED: '执行与语义匹配',
  UNKNOWN: '指标来源待核验'
} as Record<string,string>)[String(value || '').toUpperCase()] || '正式指标结果'
</script>

<style scoped>
.case-knowledge-explanation-panel{display:grid;gap:0}
.case-knowledge-explanation-panel>section{min-width:0;padding:12px 14px}
.case-knowledge-explanation-panel>section+section{border-top:1px dashed #dbe3ee}
.cross-domain-title{display:flex;align-items:center;justify-content:space-between;gap:10px;margin-bottom:9px}
.cross-domain-title b{color:#1e3a5f;font-size:13px}
.multi-stage-matter{padding:12px;border-radius:8px;background:linear-gradient(135deg,#eff6ff,#f5f3ff)}
.multi-stage-matter>div:first-child{display:flex;align-items:center;justify-content:space-between;gap:10px}
.multi-stage-matter>p{margin:8px 0;color:#334155;font-size:12px;line-height:1.6}
.multi-stage-matter>em{color:#5b35c9;font-size:11px;font-style:normal}
.matter-conclusions{margin-top:12px;padding-top:10px;border-top:1px solid #d8def0}
.matter-conclusions h4{margin:0 0 5px;color:#334155;font-size:12px}
.matter-conclusion-source{margin:0 0 8px!important;color:#64748b!important;font-size:11px!important}
.matter-conclusions article{margin-top:6px;padding:7px 9px;border-left:3px solid #7c3aed;border-radius:5px;background:rgba(255,255,255,.78)}
.matter-conclusions article b{color:#5b35c9;font-size:10px}
.matter-conclusions article p{margin:3px 0 0;color:#334155;font-size:11px;line-height:1.55}
.knowledge-chain-table-wrap{overflow-x:hidden;border:1px solid #dbe3ee;border-radius:9px;background:#fff}
.knowledge-chain-table{width:100%;border-collapse:collapse;table-layout:fixed}
.knowledge-chain-table .col-fact{width:16%}
.knowledge-chain-table .col-indicator{width:24%}
.knowledge-chain-table .col-technique{width:10%}
.knowledge-chain-table .col-tactic{width:9%}
.knowledge-chain-table .col-pattern{width:16%}
.knowledge-chain-table .col-risk{width:17%}
.knowledge-chain-table .col-relation{width:8%}
.knowledge-chain-table th{padding:8px 6px;border-right:1px solid #e2e8f0;border-bottom:1px solid #cbd5e1;background:#eff6ff;color:#334155;font-size:11px;text-align:center;white-space:nowrap}
.knowledge-chain-table td{position:relative;padding:9px 6px;border-right:1px solid #e2e8f0;border-bottom:1px solid #e2e8f0;color:#334155;font-size:11px;line-height:1.55;vertical-align:top;overflow-wrap:anywhere;word-break:break-word}
.knowledge-chain-table th:last-child,.knowledge-chain-table td:last-child{border-right:0}
.knowledge-chain-table tbody tr:last-child td{border-bottom:0}
.knowledge-chain-table td:not(:last-child)::after{position:absolute;right:-6px;top:50%;z-index:1;width:12px;height:12px;border-radius:50%;background:#fff;color:#94a3b8;content:'›';font-weight:700;line-height:11px;text-align:center;transform:translateY(-50%)}
.knowledge-chain-table td.indicator{background:#f0fdf4}
.knowledge-chain-table td.pattern{background:#ecfeff}
.knowledge-chain-table td.risk{background:#fff7ed}
.knowledge-chain-table td.technique{background:#f5f3ff}
.knowledge-chain-table td.tactic{background:#fef2f2}
.knowledge-chain-table td.relation{background:#f8fafc;color:#475569;text-align:center}
.knowledge-chain-table td small{display:block;margin-top:5px;color:#64748b;line-height:1.45}
.knowledge-chain-table td.fact .el-button{height:auto;padding:0;white-space:normal;text-align:left;line-height:1.55}
@media(max-width:760px){.knowledge-chain-table-wrap{overflow-x:auto}.knowledge-chain-table{min-width:920px}}
</style>
