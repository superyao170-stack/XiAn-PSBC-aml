﻿﻿﻿﻿﻿<template>
  <div class="case-detail">
    <div class="case-action-bar">
      <div>
        <b>{{ displayCaseName || '案例详情' }}</b>
        <span>{{ getStatusText(caseDetail.caseStatus) }} · {{ getRiskText(caseDetail.riskLevel) }}</span>
      </div>
    </div>
    <Teleport v-if="headerActionsReady" to="#case-header-actions">
      <div class="case-header-buttons">
        <el-button v-if="caseDetail.caseStatus === 'DRAFT'" class="case-header-button" size="small" type="success" :loading="submitting" @click="submitForReview">提交复核</el-button>
        <el-select v-if="taskCases.length > 1" v-model="selectedTaskCaseId" class="case-selector" size="small" @change="switchTaskCase">
          <el-option v-for="item in taskCases" :key="item.caseId" :value="item.caseId" :label="`${item.id} · ${item.caseName}`" />
        </el-select>
      </div>
    </Teleport>
    <el-tabs v-model="activeTab" class="tabs">
      <el-tab-pane label="可疑报告" name="report">
        <el-card class="report-card">
          <template #header>
            <div class="report-heading">
              <div><b>可疑报告</b><el-tag :type="reportSourceTagType">{{ reportSourceLabel }}</el-tag></div>
              <div>
                <template v-if="reportEditing"><el-button size="small" @click="cancelReportEdit">取消</el-button><el-button size="small" type="primary" :loading="reportSaving" @click="saveReport">保存</el-button></template>
                <el-button v-else-if="workerJobId" size="small" type="primary" plain @click="startReportEdit">编辑</el-button>
              </div>
            </div>
          </template>
          <el-input v-if="reportEditing" v-model="reportDraft" type="textarea" :rows="18" resize="vertical" class="report-editor" />
          <div v-else class="report-content">{{ suspiciousReportText || '当前案例暂无可疑报告' }}</div>
        </el-card>
      </el-tab-pane>
      <el-tab-pane v-if="false" label="案例分析" name="analysis">
        <el-card class="summary product-case-summary">
          <template #header><div class="case-summary-title"><b>案例分析对象</b></div></template>
          <table class="case-heading" aria-label="案例基本信息">
            <thead><tr><th>案例ID</th><th>案例名称</th><th>风险等级</th><th>案例状态</th><th>业务场景</th><th>所属银行</th><th>图谱节点</th><th>关系</th><th>事件</th><th>交易</th></tr></thead>
            <tbody>
              <tr><td class="case-id"><span class="truncate-value" :title="displayCaseSequenceId">{{ displayCaseSequenceId }}</span></td><td class="case-name"><span class="truncate-value" :title="caseDetail.caseName">{{ caseDetail.caseName || '案例名称待补充' }}</span></td><td><el-tag size="small" :type="getRiskTagType(caseDetail.riskLevel)">{{ getRiskText(caseDetail.riskLevel) }}</el-tag></td><td><el-tag size="small" :type="getStatusTagType(caseDetail.caseStatus)">{{ getStatusText(caseDetail.caseStatus) }}</el-tag></td><td><span class="truncate-value" :title="caseDetail.scenarioCode">{{ displayScenario }}</span></td><td><span class="truncate-value" :title="displayBankCode">{{ displayBankCode }}</span></td><td class="metric">{{ graphNodes.length }}</td><td class="metric">{{ graphEdges.length }}</td><td class="metric">{{ eventModels.length }}</td><td class="metric">{{ caseDetail.transactionCount || 0 }}</td></tr>
            </tbody>
          </table>
        </el-card>
        <el-card class="product-overview">
          <template #header>
            <div class="product-header">
              <div><b>案例分析摘要</b></div>
              <el-tag :type="productReady ? 'success' : 'warning'">{{ productReady ? '已形成' : '待分析' }}</el-tag>
            </div>
          </template>
          <el-alert v-if="analysisLoadErrorText" class="analysis-load-alert" type="error" :closable="false" :title="analysisLoadErrorText" />
          <div class="product-grid">
            <button @click="openOverviewLayer('event')"><span>标准化事件</span><b>{{ eventModels.length }}</b><small>事件模型数量</small></button>
            <button @click="scrollToProduct('matter-products')"><span>{{ analysisConclusionLabel }}</span><b>{{ explanationMatterCount }}</b><small>{{ analysisConclusionHint }}</small></button>
            <button @click="scrollToProduct('pattern-products')"><span>行为模式</span><b>{{ behaviorPatterns.length }}</b><small>案例行为结构</small></button>
            <button @click="scrollToProduct('risk-products')"><span>风险判断</span><b>{{ riskEvents.length }}</b><small>非确定性假设</small></button>
            <button @click="scrollToProduct('technique-products')"><span>技术映射</span><b>{{ techniques.length }}</b><small>AMLTRIX 归类</small></button>
            <button @click="activeTab='graph'"><span>图谱依据</span><b>{{ graphNodes.length }}</b><small>{{ graphEdges.length }} 条关系</small></button>
          </div>
        </el-card>
        <div class="matter-layout">
          <el-card v-if="displayedMatters.length" id="matter-products" class="matter-lead">
            <div class="lead-label">事实归纳摘要</div>
            <div class="lead-summary">{{ displayedMatters[0].summary }}</div>
            <div class="lead-meta">
              <el-tag :type="certaintyType(displayedMatters[0].certainty)">{{ certaintyText(displayedMatters[0].certainty) }}</el-tag>
              <span>{{ displayedMatters.length }} 条事实归纳 · {{ hasAmltrixExplanation ? `${knowledgeExplanationChains.length} 条 AMLTRIX 解释链、${techniques.length} 个技术解释` : 'AMLTRIX 暂未形成解释' }}</span>
            </div>
          </el-card>
          <el-card class="base-fact-card">
            <template #header>
              <div class="selected-header">
                <b>事实归纳</b>
                <div class="fact-header-actions">
                  <el-button v-if="atomicMatters.length>displayedMatters.length" link type="primary" @click="showAtomicFacts=!showAtomicFacts">
                    {{ showAtomicFacts ? `返回核心事实（${convergedMatters.length}）` : `查看全部原子事实（${atomicMatters.length}）` }}
                  </el-button>
                </div>
              </div>
            </template>
            <el-table :data="displayedMatters" size="small" empty-text="当前案例尚未形成事实归纳">
              <el-table-column prop="summary" label="事实内容" min-width="360" />
              <el-table-column label="事实类型" width="180"><template #default="{row}">{{ row.matterTypeName||factTypeText(row.matterType) }}</template></el-table-column>
              <el-table-column label="事实性质" width="110">
                <template #default="{row}"><el-tag :type="certaintyType(row.certainty)">{{ certaintyText(row.certainty) }}</el-tag></template>
              </el-table-column>
              <el-table-column label="事实依据" min-width="280">
                <template #default="{row}">
                  <div class="fact-basis-links">
                    <el-button v-if="row.eventRefs?.length" link type="primary" @click.stop="openFactBasis(row,'EVENT')">标准化事件：{{ row.eventRefs.length }}项</el-button>
                    <span v-if="row.eventRefs?.length && row.evidenceRefs?.length">；</span>
                    <el-button v-if="row.evidenceRefs?.length" link type="primary" @click.stop="openFactBasis(row,'EVIDENCE')">文本证据：{{ row.evidenceRefs.length }}处</el-button>
                    <span v-if="(row.eventRefs?.length||row.evidenceRefs?.length) && row.indicatorResultRefs?.length">；</span>
                    <el-button v-if="row.indicatorResultRefs?.length" link type="success" @click.stop="openFactBasis(row,'INDICATOR')">指标结果：{{ row.indicatorResultRefs.length }}项</el-button>
                    <span v-if="!row.eventRefs?.length && !row.evidenceRefs?.length && !row.indicatorResultRefs?.length">依据待补充</span>
                  </div>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
          <el-card class="reasoning-chain-card">
            <template #header>
              <b>分析推理路线</b>
            </template>
            <div class="reasoning-routes">
              <div class="reasoning-route">
                <strong>传统事实推理</strong>
                <div class="reasoning-chain">
                  <div class="reasoning-stage stage-evidence"><span>证据对象</span><b>{{ evidenceModels.length }}</b></div>
                  <div class="flow-arrow">→</div>
                  <div class="reasoning-stage stage-base"><span>标准化事件</span><b>{{ eventModels.length }}</b></div>
                  <div class="flow-arrow">→</div>
                  <div class="reasoning-stage stage-matter"><span>事实归纳</span><b>{{ explanationMatterCount }}</b></div>
                  <div class="flow-arrow">→</div>
                  <div class="reasoning-stage stage-pattern"><span>行为模式</span><b>{{ behaviorPatterns.length }}</b></div>
                  <div class="flow-arrow">→</div>
                  <div class="reasoning-stage stage-risk"><span>风险假设</span><b>{{ riskEvents.length }}</b></div>
                </div>
              </div>
              <div class="reasoning-route">
                <strong>AMLTRIX 技术—战术推理</strong>
                <div class="reasoning-chain">
                  <div class="reasoning-stage stage-base"><span>指标结果</span><b>{{ indicatorResults.length }}</b></div>
                  <div class="flow-arrow">→</div>
                  <div class="reasoning-stage stage-technique"><span>AMLTRIX 技术</span><b>{{ techniques.length }}</b></div>
                  <div class="flow-arrow">→</div>
                  <div class="reasoning-stage stage-tactic"><span>AMLTRIX 战术</span><b>{{ amltrixTactics.length }}</b></div>
                  <div class="flow-arrow supplement-arrow">补强</div>
                  <div class="reasoning-stage stage-risk"><span>关联风险假设</span><b>{{ amltrixLinkedRiskCount }}</b></div>
                </div>
              </div>
            </div>
            <div id="pattern-products" class="product-section">
              <div class="product-section-title"><b>模式识别</b></div>
              <el-table :data="behaviorPatterns" size="small" empty-text="当前案例尚未识别出行为模式">
                <el-table-column label="模式名称" min-width="190">
                  <template #default="{row}"><b>{{ row.patternName||patternNameText(row.patternCode) }}</b></template>
                </el-table-column>
                <el-table-column prop="formalText" label="正式文本" min-width="360" class-name="pattern-formal-text">
                  <template #default="{row}">{{ cleanPatternText(row.formalText||row.patternDescription||patternFormalText(row.patternCode),row) }}</template>
                </el-table-column>
                <el-table-column label="模式依据" min-width="230">
                  <template #default="{row}">
                    <div class="fact-basis-links">
                      <el-button v-if="row.eventRefs?.length" link type="primary" @click.stop="openFactBasis(row,'EVENT')">标准化事件：{{ row.eventRefs.length }}项</el-button>
                      <span v-if="row.eventRefs?.length&&row.indicatorResultRefs?.length">；</span>
                      <el-button v-if="row.indicatorResultRefs?.length" link type="success" @click.stop="openFactBasis(row,'INDICATOR')">指标结果：{{ row.indicatorResultRefs.length }}项</el-button>
                    </div>
                  </template>
                </el-table-column>
                <el-table-column label="置信度" width="90"><template #default="{row}"><el-tooltip :content="confidenceCalculationText(row,'模式置信度')" placement="top"><span class="confidence-value">{{ confidenceText(row.patternConfidence) }}</span></el-tooltip></template></el-table-column>
                <el-table-column label="状态" width="90"><template #default="{row}"><el-tag type="success">{{ businessEnumText(row.status) }}</el-tag></template></el-table-column>
                <el-table-column label="操作" width="75"><template #default="{row}"><el-button link type="primary" @click="openLayerRowDetail('模式识别',row)">查看</el-button></template></el-table-column>
              </el-table>
            </div>
            <div id="risk-products" class="product-section">
            <div class="product-section-title"><b>风险判断</b></div>
            <el-table :data="riskEvents" size="small" empty-text="当前数据未形成风险事件假设">
              <el-table-column prop="title" label="风险事件" min-width="220" />
              <el-table-column label="事实置信度" width="115"><template #default="{row}"><el-tooltip :content="confidenceCalculationText(row,'事实置信度')" placement="top"><span class="confidence-value">{{ confidenceText(row.factConfidence) }}</span></el-tooltip></template></el-table-column>
              <el-table-column label="风险置信度" width="115"><template #default="{row}"><el-tooltip :content="confidenceCalculationText(row,'风险置信度')" placement="top"><span class="confidence-value">{{ confidenceText(row.riskConfidence) }}</span></el-tooltip></template></el-table-column>
              <el-table-column label="证据强度" width="100"><template #default="{row}">{{ businessEnumText(row.evidenceStrength) }}</template></el-table-column>
              <el-table-column label="审核状态" width="110"><template #default="{row}">{{ businessEnumText(row.reviewStatus) }}</template></el-table-column>
            </el-table>
            </div>
          </el-card>
          <el-card class="alternative-card">
            <template #header><b>竞争性解释与证伪条件</b></template>
            <el-table :data="alternativeExplanations" size="small" empty-text="暂无竞争性解释">
              <el-table-column prop="title" label="竞争性解释" min-width="220" />
              <el-table-column prop="summary" label="成立与排除条件" min-width="420" />
              <el-table-column label="解释支持度" width="110"><template #default="{row}"><el-tooltip :content="confidenceCalculationText(row,'替代解释支持度')" placement="top"><span class="confidence-value">{{ confidenceText(row.confidence) }}</span></el-tooltip></template></el-table-column>
              <el-table-column label="解释类型" width="140"><template #default="{row}">{{ businessEnumText(row.alternativeType) }}</template></el-table-column>
            </el-table>
          </el-card>
          <el-card class="investigation-card">
            <template #header><b>调查假设与下一步行动</b></template>
            <el-table :data="investigationHypotheses" size="small" empty-text="暂无调查假设">
              <el-table-column prop="hypothesis" label="待验证问题" min-width="300" />
              <el-table-column label="需要的材料" min-width="260"><template #default="{row}">{{ (row.evidenceNeeded || []).join('、') || '—' }}</template></el-table-column>
              <el-table-column label="下一步行动" min-width="300"><template #default="{row}">{{ (row.recommendedActions || []).join('；') || '—' }}</template></el-table-column>
              <el-table-column label="状态" width="110"><template #default="{row}">{{ businessEnumText(row.status) }}</template></el-table-column>
              <el-table-column label="操作" width="150"><template #default="{row}"><el-button v-if="row.status==='OPEN'" link type="primary" @click="acknowledgeHypothesis(row)">受理调查假设</el-button></template></el-table-column>
            </el-table>
          </el-card>
          <el-card id="technique-products" class="technique-card">
            <template #header><b>AMLTRIX 技术映射</b></template>
            <el-table :data="techniques" empty-text="当前数据没有足够依据应用技术编码">
              <el-table-column prop="techniqueCode" label="编码" width="130" />
              <el-table-column label="指标结果" min-width="260"><template #default="{row}">{{ techniqueIndicatorNames(row) }}</template></el-table-column>
              <el-table-column label="匹配方式" width="110"><template #default="{row}">{{ techniqueIndicatorMode(row) }}</template></el-table-column>
              <el-table-column label="当前案例中的解释" min-width="360"><template #default="{row}">{{ localizedNarrative(row.explanation) }}</template></el-table-column>
              <el-table-column label="映射置信度" width="110"><template #default="{row}"><el-tooltip :content="confidenceCalculationText(row,'AMLTRIX 映射置信度')" placement="top"><span class="confidence-value">{{ confidenceText(row.mappingConfidence) }}</span></el-tooltip></template></el-table-column>
              <el-table-column label="风险置信度" width="110"><template #default="{row}"><el-tooltip :content="confidenceCalculationText(row,'技术风险置信度')" placement="top"><span class="confidence-value">{{ confidenceText(row.riskConfidence) }}</span></el-tooltip></template></el-table-column>
              <el-table-column label="证据" width="80"><template #default="{row}">{{ businessEnumText(row.evidenceStrength) }}</template></el-table-column>
              <el-table-column prop="decision" label="结论强度" width="130"><template #default="{row}"><el-tag :type="row.decision==='REPORTED'?'warning':'info'">{{ businessEnumText(row.decision) }}</el-tag></template></el-table-column>
            </el-table>
          </el-card>
          <el-card class="review-card">
            <template #header><b>建议后续关注</b></template>
            <el-table :data="reviewSuggestions" empty-text="暂无复核建议">
              <el-table-column prop="topic" label="建议" min-width="240" />
              <el-table-column prop="reason" label="原因" min-width="300" />
              <el-table-column prop="expectedMaterial" label="核验材料" min-width="260" />
              <el-table-column label="状态" width="120"><template #default="{row}">{{ businessEnumText(row.status) }}</template></el-table-column>
              <el-table-column label="操作" width="150"><template #default="{row}"><el-button v-if="row.status==='OPEN'" link type="primary" @click="acknowledgeSuggestion(row)">已知悉，暂不可补充</el-button></template></el-table-column>
            </el-table>
          </el-card>
        </div>
      </el-tab-pane>
      <el-tab-pane label="抽取结果" name="extraction">
        <div class="overview-layer-map">
          <div class="overview-layer-map-title">
            <b>案例标准五层结构</b>
            <el-tag :type="extractionGenerated ? 'success' : 'info'">{{ extractionSourceLabel }}</el-tag>
          </div>
          <div class="overview-layer-path" aria-label="案例标准五层层级关系">
            <a href="#overview-case"><i>01</i><span>案例基本信息</span></a><em>→</em>
            <a href="#overview-entity"><i>02</i><span>实体层</span></a><em>→</em>
            <a href="#overview-event"><i>03</i><span>事件层</span></a><em>→</em>
            <a href="#overview-relation"><i>04</i><span>关系层</span></a><em>→</em>
            <a href="#overview-evidence"><i>05</i><span>证据层</span></a>
          </div>
        </div>
        <a-collapse v-model:activeKey="activeOverviewLayers" class="framework-layers" :bordered="false">
          <a-collapse-panel key="case">
            <template #header><div id="overview-case" class="layer-title"><i>01</i><div><b>案例基本信息</b><span>案例识别、报送、风险与处置信息</span></div><em>14 个标准字段</em><el-button size="small" type="primary" plain :disabled="caseOverviewEditing" @click.stop="startCaseOverviewEdit">编辑</el-button></div></template>
            <CaseBasicInfoDescriptions v-if="!caseOverviewEditing" :fields="caseOverviewFields" />
            <el-form v-else :model="caseOverviewForm" label-position="top" class="case-overview-form">
              <div class="case-overview-readonly">
                <div><span>案例ID</span><b>{{ displayCaseSequenceId }}</b></div>
                <div><span>案例名称</span><b>{{ caseDetail.caseName || '未命名案例' }}</b></div>
              </div>
              <div class="case-overview-form-grid">
                <el-form-item label="业务领域"><el-select v-model="caseOverviewForm.businessDomain" placeholder="请选择"><el-option label="反洗钱" value="01-反洗钱" /><el-option label="反欺诈" value="02-反欺诈" /></el-select></el-form-item>
                <el-form-item label="案例类型"><el-input v-model="caseOverviewForm.businessCaseType" placeholder="待补充" maxlength="64" /></el-form-item>
                <el-form-item label="报送方向"><el-select v-model="caseOverviewForm.reportingDirection" clearable placeholder="待补充"><el-option v-for="item in reportingDirectionOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item>
                <el-form-item label="案例触发点"><el-select v-model="caseOverviewForm.triggerPoint" clearable placeholder="待补充"><el-option v-for="item in triggerPointOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item>
                <el-form-item label="紧急程度"><el-select v-model="caseOverviewForm.urgencyLevel" clearable placeholder="待补充"><el-option v-for="item in urgencyLevelOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item>
                <el-form-item label="案例上报时间"><el-date-picker v-model="caseOverviewForm.reportedAt" type="date" value-format="YYYY-MM-DD[T]00:00:00" format="YYYY-MM-DD" clearable placeholder="待补充" /></el-form-item>
                <el-form-item label="案例状态"><el-select v-model="caseOverviewForm.businessCaseStatus" clearable placeholder="待补充"><el-option v-for="item in businessCaseStatusOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item>
                <el-form-item label="风险等级"><el-select v-model="caseOverviewForm.businessRiskLevel" clearable placeholder="待补充"><el-option v-for="item in businessRiskLevelOptions" :key="item" :label="item" :value="item" /></el-select></el-form-item>
                <el-form-item class="wide" label="案例描述"><el-input v-model="caseOverviewForm.description" type="textarea" :rows="3" placeholder="待补充" maxlength="8000" show-word-limit /></el-form-item>
                <el-form-item class="wide" label="疑似涉罪类型"><el-input v-model="caseOverviewForm.suspectedCrimeType" placeholder="待补充" maxlength="255" /></el-form-item>
                <el-form-item class="wide" label="可疑交易特征代码"><el-input v-model="caseOverviewForm.suspiciousTransactionFeatureCode" type="textarea" :rows="2" placeholder="待补充" maxlength="2000" /></el-form-item>
                <el-form-item class="wide" label="处置措施"><el-input v-model="caseOverviewForm.disposalMeasure" type="textarea" :rows="2" placeholder="待补充" maxlength="2000" /></el-form-item>
              </div>
              <div class="case-overview-form-actions"><el-button @click="cancelCaseOverviewEdit">取消</el-button><el-button type="primary" :loading="caseOverviewSaving" @click="saveCaseOverview">保存</el-button></div>
            </el-form>
          </a-collapse-panel>

          <a-collapse-panel key="entity">
            <template #header><div id="overview-entity" class="layer-title"><i>02</i><div><b>实体层</b><span>客户、账户及其他主体</span></div><em>{{ customerModels.length }} 客户 · {{ accountModels.length }} 账户 · {{ otherEntityModels.length }} 其他</em></div></template>
            <section class="entity-block">
              <div class="subsection-heading"><i>客</i><div><b>客户实体</b><span>{{ customerModels.length }} 个真实客户实体</span></div></div>
              <a-collapse v-if="customerModels.length" class="record-list" :bordered="false">
                <a-collapse-panel v-for="(customer,index) in customerModels" :key="`customer-${index}`">
                  <template #header><div class="record-title"><i>客</i><b>{{ customer.name || customer.customer_name || '未命名客户' }}</b><span>{{ customer.customer_no || customer.customerNo || customer.businessId || 'ID待补充' }}</span><a-tag color="cyan">{{ customer.risk_level || customer.riskLevel ? getRiskText(customer.risk_level || customer.riskLevel) : '风险待补充' }}</a-tag></div></template>
                  <div class="overview-description-stack">
                    <a-descriptions v-if="compactOverviewFields(customerOverviewFields(customer)).length" :column="{ xs: 1, sm: 2, lg: 3 }" bordered size="small" class="overview-descriptions">
                      <a-descriptions-item v-for="field in compactOverviewFields(customerOverviewFields(customer))" :key="field.label" :label="field.label">{{ field.value }}</a-descriptions-item>
                    </a-descriptions>
                    <a-descriptions v-if="expandedOverviewFields(customerOverviewFields(customer)).length" :column="1" bordered size="small" class="overview-descriptions overview-long-descriptions">
                      <a-descriptions-item v-for="field in expandedOverviewFields(customerOverviewFields(customer))" :key="field.label" :label="field.label">{{ field.value }}</a-descriptions-item>
                    </a-descriptions>
                  </div>
                </a-collapse-panel>
              </a-collapse>
              <a-empty v-else :image="AEmpty.PRESENTED_IMAGE_SIMPLE" description="当前案例未取得真实客户主数据" />
            </section>
            <section class="entity-block">
              <div class="subsection-heading"><i>户</i><div><b>账户实体</b><span>{{ accountModels.length }} 个账户实体</span></div></div>
              <a-collapse v-if="accountModels.length" class="record-list" :bordered="false">
                <a-collapse-panel v-for="(account,index) in accountModels" :key="`account-${index}`">
                  <template #header><div class="record-title"><i>户</i><b>{{ accountInstanceDisplayName(account) }}</b><span>持有人：{{ account.holder_name || account.holderName || '待确认' }}</span><a-tag color="blue">{{ account.type || account.account_type ? businessEnumText(account.type || account.account_type) : '类型待补充' }}</a-tag></div></template>
                  <div class="overview-description-stack">
                    <a-descriptions v-if="compactOverviewFields(accountOverviewFields(account)).length" :column="{ xs: 1, sm: 2, lg: 3 }" bordered size="small" class="overview-descriptions">
                      <a-descriptions-item v-for="field in compactOverviewFields(accountOverviewFields(account))" :key="field.label" :label="field.label">{{ field.value }}</a-descriptions-item>
                    </a-descriptions>
                    <a-descriptions v-if="expandedOverviewFields(accountOverviewFields(account)).length" :column="1" bordered size="small" class="overview-descriptions overview-long-descriptions">
                      <a-descriptions-item v-for="field in expandedOverviewFields(accountOverviewFields(account))" :key="field.label" :label="field.label">{{ field.value }}</a-descriptions-item>
                    </a-descriptions>
                  </div>
                </a-collapse-panel>
              </a-collapse>
              <a-empty v-else :image="AEmpty.PRESENTED_IMAGE_SIMPLE" description="当前案例未解析出账户实体" />
            </section>
            <section class="entity-block">
              <div class="subsection-heading"><i>其</i><div><b>其他实体</b><span>{{ otherEntityModels.length }} 个其他实体</span></div></div>
              <div v-if="otherEntityModels.length" class="wide-table-wrap">
              <el-table :data="otherEntityModels" class="framework-table" table-layout="fixed" :tooltip-options="overviewTooltipOptions">
                  <el-table-column prop="entityId" label="实体ID" width="170" show-overflow-tooltip />
                  <el-table-column label="实体类型" width="130" show-overflow-tooltip><template #default="{row}">{{ businessEnumText(row.entityType) }}</template></el-table-column>
                  <el-table-column prop="attribute1" label="实体属性1" min-width="180" show-overflow-tooltip />
                  <el-table-column prop="attribute2" label="实体属性2" min-width="180" show-overflow-tooltip />
                  <el-table-column prop="attribute3" label="实体属性3" min-width="180" show-overflow-tooltip />
                  <el-table-column prop="attribute4" label="实体属性4" min-width="180" show-overflow-tooltip />
                </el-table>
              </div>
              <a-empty v-else :image="AEmpty.PRESENTED_IMAGE_SIMPLE" description="当前案例没有其他实体" />
            </section>
          </a-collapse-panel>

          <a-collapse-panel key="event">
            <template #header><div id="overview-event" class="layer-title"><i>03</i><div><b>事件层</b><span>事件事实、规则、风险指标与处置</span></div><em>{{ eventModels.length }} 条</em></div></template>
            <div class="wide-table-wrap">
              <el-table :data="eventModels" class="framework-table clickable-detail-table" table-layout="fixed" :tooltip-options="overviewTooltipOptions" empty-text="当前案例没有已落库事件" @row-click="openEventRowDetail">
                <el-table-column prop="businessId" label="事件ID" width="150" show-overflow-tooltip />
                <el-table-column prop="name" label="事件名称" min-width="180" show-overflow-tooltip />
                <el-table-column label="事件类型" min-width="150" show-overflow-tooltip><template #default="{row}">{{ businessEnumText(row.type) }}</template></el-table-column>
                <el-table-column prop="event_text" label="事件描述" min-width="300" show-overflow-tooltip />
                <el-table-column label="开始日期" width="155"><template #default="{row}">{{ formatDateTime(row.started_at) }}</template></el-table-column>
                <el-table-column label="结束日期" width="155"><template #default="{row}">{{ formatDateTime(row.ended_at) }}</template></el-table-column>
                <el-table-column label="识别规则" min-width="190" show-overflow-tooltip><template #default="{row}">{{ overviewValue(row.rule_name, row.ruleName) }}</template></el-table-column>
                <el-table-column label="产品服务" min-width="150" show-overflow-tooltip><template #default="{row}">{{ overviewValue(row.product_service, row.productService) }}</template></el-table-column>
                <el-table-column label="价值工具" min-width="140" show-overflow-tooltip><template #default="{row}">{{ overviewValue(row.value_instrument, row.valueInstrument) }}</template></el-table-column>
                <el-table-column label="风险类型" min-width="130" show-overflow-tooltip><template #default="{row}">{{ overviewValue(row.risk_type, row.riskType) }}</template></el-table-column>
                <el-table-column label="风险指标" min-width="160" show-overflow-tooltip><template #default="{row}">{{ overviewValue(row.risk_indicator, row.riskIndicator) }}</template></el-table-column>
                <el-table-column label="处置措施" min-width="180" show-overflow-tooltip><template #default="{row}">{{ overviewValue(row.disposal_measure, row.disposalMeasure) }}</template></el-table-column>
              </el-table>
            </div>
          </a-collapse-panel>

          <a-collapse-panel key="relation">
            <template #header><div id="overview-relation" class="layer-title"><i>04</i><div><b>关系层</b><span>源节点—关系—目标节点的可追溯表达</span></div><em>{{ overviewRelations.length }} 条</em></div></template>
            <div class="wide-table-wrap">
              <el-table :data="overviewRelations" class="framework-table clickable-detail-table" table-layout="fixed" :tooltip-options="overviewTooltipOptions" empty-text="当前案例没有已解析关系" @row-click="openRelationRowDetail">
                <el-table-column prop="relationId" label="关系ID" width="150" show-overflow-tooltip />
                <el-table-column label="关系类型" width="150" show-overflow-tooltip><template #default="{row}">{{ businessEnumText(row.type) }}</template></el-table-column>
                <el-table-column prop="description" label="关系描述" min-width="260" show-overflow-tooltip />
                <el-table-column prop="sourceId" label="源节点ID" width="160" show-overflow-tooltip />
                <el-table-column prop="source" label="源节点名称" min-width="170" show-overflow-tooltip />
                <el-table-column label="源节点类型" width="120" show-overflow-tooltip><template #default="{row}">{{ businessEnumText(row.sourceType) }}</template></el-table-column>
                <el-table-column prop="targetId" label="目标节点ID" width="160" show-overflow-tooltip />
                <el-table-column prop="target" label="目标节点名称" min-width="170" show-overflow-tooltip />
                <el-table-column label="目标节点类型" width="120" show-overflow-tooltip><template #default="{row}">{{ businessEnumText(row.targetType) }}</template></el-table-column>
              </el-table>
            </div>
          </a-collapse-panel>

          <a-collapse-panel key="evidence">
            <template #header><div id="overview-evidence" class="layer-title"><i>05</i><div><b>证据层</b><span>证据对象、关联范围与原始出处</span></div><em>{{ evidenceModels.length }} 条</em></div></template>
            <div class="wide-table-wrap">
              <el-table :data="evidenceModels" class="framework-table clickable-detail-table" table-layout="fixed" :tooltip-options="overviewTooltipOptions" empty-text="当前案例没有已解析证据" @row-click="openEvidenceRowDetail">
                <el-table-column prop="businessId" label="证据ID" width="155" show-overflow-tooltip />
                <el-table-column label="证据关联类型" min-width="150" show-overflow-tooltip><template #default="{row}">{{ businessEnumText(row.associationType) }}</template></el-table-column>
                <el-table-column prop="associationId" label="证据关联ID" min-width="170" show-overflow-tooltip />
                <el-table-column label="证据类型" min-width="150" show-overflow-tooltip><template #default="{row}">{{ businessEnumText(row.type) }}</template></el-table-column>
                <el-table-column label="证据来源" min-width="180" show-overflow-tooltip><template #default="{row}">{{ overviewValue(row.source, row.data_source, row.dataSource) }}</template></el-table-column>
                <el-table-column label="原文/原始数据" min-width="360" show-overflow-tooltip><template #default="{row}">{{ overviewValue(row.raw_text, row.rawText, row.original_data, row.originalData, row.summary) }}</template></el-table-column>
              </el-table>
            </div>
          </a-collapse-panel>
        </a-collapse>

        <el-card class="provenance-card">
          <template #header><span>数据来源与一致性校验</span></template>
          <div class="consistency-alerts">
            <el-alert v-if="consistencyAssessment.processing.length" type="error" :closable="false" show-icon
              :title="`处理逻辑问题：${consistencyAssessment.processing.join('；')}`" />
            <el-alert v-if="consistencyAssessment.data.length" type="warning" :closable="false" show-icon
              :title="`数据自身问题：${consistencyAssessment.data.join('；')}`" />
            <el-alert v-if="!consistencyAssessment.processing.length && !consistencyAssessment.data.length" type="success" :closable="false" show-icon
              title="案例详情、模型页签与图谱快照的有效节点数量一致，未发现数据缺失" />
          </div>
          <el-descriptions :column="2" border class="provenance-list balanced-descriptions">
            <el-descriptions-item label="案例模型">PostgreSQL · cf_risk_case</el-descriptions-item>
            <el-descriptions-item label="证据模型">{{ modelProvenance.evidence }}</el-descriptions-item>
            <el-descriptions-item label="事件模型">{{ modelProvenance.event }}</el-descriptions-item>
            <el-descriptions-item label="账户模型">{{ modelProvenance.account }}</el-descriptions-item>
            <el-descriptions-item label="客户模型">{{ modelProvenance.customer }}</el-descriptions-item>
            <el-descriptions-item label="图谱快照">TuGraph · BankGraph 案例子图</el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-tab-pane>
      <el-tab-pane label="图谱快照" name="graph">
        <el-card>
          <div class="snapshot-toolbar">
            <div class="snapshot-legend">
              <div v-for="group in visibleSnapshotGroups" :key="group.key" class="legend-group">
                <button class="plane-toggle"
                  :class="{active:isGraphPlaneEnabled(group.key),partial:isGraphPlanePartiallyEnabled(group.key)}"
                  :aria-pressed="isGraphPlaneEnabled(group.key)"
                  @click="toggleGraphPlane(group.key)">
                  <i></i><b>{{ group.name }}</b>
                </button>
                <button v-for="item in group.items" :key="item.key" class="legend-item"
                  :class="{muted:!visibleSnapshotTypes[item.key]}"
                  :aria-pressed="visibleSnapshotTypes[item.key]"
                  @click="toggleSnapshotType(item.key)">
                  <i :style="{backgroundColor:item.color}">{{ item.icon }}</i>
                  <span :title="item.name">{{ item.name }}</span>
                  <em>{{ snapshotTypeCount(item.key) }}</em>
                </button>
              </div>
            </div>
          </div>
          <div class="snapshot-workbench">
            <G6GraphCanvas
              ref="graphCanvas"
              class="graph-canvas"
              :nodes="visibleGraphNodes"
              :edges="visibleGraphEdges"
              :type-meta="g6SnapshotTypeMeta"
              :layout="snapshotLayout"
              :highlighted-ids="[...highlightedEventIds]"
              :height="650"
              @select="selectedGraphNode=$event"
              @select-edge="selectedGraphEdge=$event"
            />
            <aside class="snapshot-inspector">
              <template v-if="selectedGraphEdge">
                <div class="edge-detail-title">
                  <i :class="selectedGraphEdge.properties?.edgeCategory==='INFERENCE'?'inference':'fact'">边</i>
                  <div><b>{{ relationDisplayName(selectedGraphEdge) }}</b><small>{{ edgeCategoryName(selectedGraphEdge.properties?.edgeCategory) }}</small></div>
                </div>
                <h4>{{ selectedEdgeSourceName }} → {{ selectedEdgeTargetName }}</h4>
                <div class="edge-business-explanation">
                  <b>关系含义与方向</b>
                  <p>{{ selectedGraphEdge.properties?.directionSemantics || '起点实例指向终点实例' }}</p>
                  <small>{{ selectedGraphEdge.properties?.description || selectedGraphEdge.properties?.descriptionText || '该关系已通过起止节点类型契约校验。' }}</small>
                </div>
                <dl>
                  <div><dt>起点实例</dt><dd :title="selectedEdgeSourceName">{{ selectedEdgeSourceName }}</dd></div>
                  <div><dt>终点实例</dt><dd :title="selectedEdgeTargetName">{{ selectedEdgeTargetName }}</dd></div>
                  <div><dt>语义层级</dt><dd>{{ selectedGraphEdge.properties?.semanticLevel || '—' }}</dd></div>
                  <div><dt>关系来源</dt><dd>{{ edgeOriginName(selectedGraphEdge.properties?.edgeOrigin) }}</dd></div>
                </dl>
              </template>
              <template v-else-if="selectedGraphNode">
                <div class="node-detail-title">
                  <i :style="{backgroundColor:graphTypeMeta(selectedGraphNode).color}">{{ graphTypeMeta(selectedGraphNode).icon }}</i>
                  <div><b>{{ graphNodeRoleName(selectedGraphNode) }}</b><small>{{ graphTypeMeta(selectedGraphNode).planeName }}</small></div>
                </div>
                <h4>{{ graphNodeTitle(selectedGraphNode) }}</h4>
                <div v-if="selectedReasoningSemantics" class="reasoning-node-semantics">
                  <div><b>该实例回答</b><p>{{ selectedReasoningSemantics.question }}</p></div>
                  <div><b>当前含义</b><p>{{ selectedReasoningSemantics.meaning }}</p></div>
                  <div><b>形成依据</b><p>{{ selectedReasoningSemantics.support }}</p></div>
                  <div class="semantic-boundary"><b>结论边界</b><p>{{ selectedReasoningSemantics.boundary }}</p></div>
                </div>
                <div v-else-if="selectedGraphExplanation" class="node-business-explanation">
                  <b>{{ nodeSnapshotType(selectedGraphNode)==='EVIDENCE' ? '这项证据证明什么' : '补充属性（业务解释）' }}</b>
                  <p>{{ selectedGraphExplanation }}</p>
                </div>
                <dl>
                  <div v-for="item in selectedGraphProperties" :key="item.key">
                    <dt>{{ item.label }}</dt><dd :title="item.value">{{ item.value }}</dd>
                  </div>
                </dl>
              </template>
              <template v-else>
                <h4>节点详情</h4>
                <p>点击节点查看属性；悬停节点可快速预览并高亮一度关系。</p>
              </template>
            </aside>
          </div>
          <CaseKnowledgeExplanationPanel
            :multi-stage-matter="multiStageMatter"
            :chains="knowledgeExplanationChains"
            interactive
            @open-fact="openKnowledgeFact"
          />
        </el-card>
      </el-tab-pane>
      <el-tab-pane label="相似度匹配" name="similarity">
        <el-card class="similarity-card">
          <template #header><div class="report-heading"><div><b>相似度匹配</b><span>{{ similarCases.length }} 条</span></div></div></template>
          <el-alert :title="similaritySummary" type="success" :closable="false" />
          <el-table :data="similarCases" empty-text="历史案例库暂无可匹配案例" class="similarity-table">
            <el-table-column prop="rank" label="排名" width="80" />
            <el-table-column prop="caseId" label="历史案例ID" min-width="190" />
            <el-table-column prop="caseName" label="案例名称" min-width="260" />
            <el-table-column label="相似度" width="110"><template #default="{row}">{{ Math.round(Number(row.similarity || 0) * 100) }}%</template></el-table-column>
            <el-table-column label="共享事件类型" min-width="240"><template #default="{row}">{{ (row.sharedEventTypes || []).join('、') || '—' }}</template></el-table-column>
          </el-table>
        </el-card>
      </el-tab-pane>
    </el-tabs>
    <el-dialog v-model="factBasisVisible" :title="factBasisTitle" width="920px" append-to-body>
      <el-table v-if="factBasisMode==='EVENT'" :data="factBasisEvents" border max-height="560" empty-text="未找到关联的标准化事件">
        <el-table-column prop="businessId" label="事件ID" width="120" show-overflow-tooltip />
        <el-table-column prop="name" label="事件名称" min-width="210" show-overflow-tooltip />
        <el-table-column label="事件类型" width="145" show-overflow-tooltip><template #default="{row}">{{ businessEnumText(row.type) }}</template></el-table-column>
        <el-table-column label="发生时间" width="170"><template #default="{row}">{{ row.started_at ? formatDateTime(row.started_at) : '—' }}</template></el-table-column>
        <el-table-column label="金额" width="150"><template #default="{row}">{{ factEventAmount(row) }}</template></el-table-column>
      </el-table>
      <el-table v-else-if="factBasisMode==='EVIDENCE'" :data="factBasisEvidence" border max-height="560" empty-text="未找到关联的文本证据">
        <el-table-column type="index" label="序号" width="70" />
        <el-table-column prop="term" label="命中词" width="145" show-overflow-tooltip />
        <el-table-column prop="quote" label="原文片段" min-width="480">
          <template #default="{row}"><div class="evidence-quote">{{ row.quote || '—' }}</div></template>
        </el-table-column>
        <el-table-column label="原文位置" width="120"><template #default="{row}">{{ evidencePosition(row) }}</template></el-table-column>
      </el-table>
      <el-table v-else :data="factBasisIndicators" border max-height="560" empty-text="未找到关联的指标结果">
        <el-table-column prop="indicatorCode" label="指标编码" width="180" show-overflow-tooltip />
        <el-table-column prop="indicatorName" label="指标名称" min-width="330" show-overflow-tooltip />
        <el-table-column label="匹配方式" width="110"><template #default="{row}">{{ indicatorResultModeText(row) }}</template></el-table-column>
        <el-table-column label="结果说明" min-width="320"><template #default="{row}">{{ indicatorExplanationText(row) }}</template></el-table-column>
        <el-table-column label="风险等级" width="100"><template #default="{row}">{{ getRiskText(row.riskLevel) }}</template></el-table-column>
      </el-table>
    </el-dialog>
    <el-dialog v-model="rowDetailVisible" :title="`${rowDetailLayer}详情`" width="780px" append-to-body>
      <el-table :data="rowDetailFields" border class="row-detail-table" max-height="560">
        <el-table-column prop="label" label="字段" width="190" />
        <el-table-column label="内容">
          <template #default="{row}"><pre>{{ row.value }}</pre></template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Collapse as ACollapse,
  CollapsePanel as ACollapsePanel,
  Descriptions as ADescriptions,
  DescriptionsItem as ADescriptionsItem,
  Empty as AEmpty,
  Tag as ATag
} from 'ant-design-vue'
import G6GraphCanvas from '@/components/graph/G6GraphCanvas.vue'
import CaseKnowledgeExplanationPanel from '@/components/graph/CaseKnowledgeExplanationPanel.vue'
import CaseBasicInfoDescriptions from '@/components/case/CaseBasicInfoDescriptions.vue'
import { getCaseApi, getCasesApi, getCaseEventsApi, getCaseWorkflowApi, getCaseGraphApi, getCaseWorkerResultApi, getCaseSignalsApi, getCaseMattersApi, getCaseTechniquesApi, getCaseReviewSuggestionsApi, getCaseReasoningApi, getCaseCoreChainApi, getCaseKnowledgeExplanationChainsApi, updateCaseReviewSuggestionApi, updateInvestigationHypothesisApi, updateCaseOverviewApi, submitCaseApi } from '@/api/case'
import { updateStructuredCaseAnalysisTextApi } from '@/api/analysis'
import { formatDateTime } from '@/utils/datetime'
const route = useRoute(), router = useRouter()
const submitting = ref(false)
const allowedTabs=new Set(['report','extraction','graph','similarity'])
const legacyTabMap:Record<string,string>={
  overview:'extraction',
  analysis:'report',
  history:'similarity',
  matters:'report',
  description:'extraction',
  'case-model':'extraction',
  'evidence-model':'extraction',
  'event-model':'extraction',
  'customer-model':'extraction',
  'account-model':'extraction',
  'evidence-chain':'graph',
  'worker-json':'extraction'
}
const normalizeDetailTab=(value:any)=>{
  const requested=String(value||'report')
  const normalized=legacyTabMap[requested]||requested
  return allowedTabs.has(normalized)?normalized:'overview'
}
const activeTab = ref(normalizeDetailTab(route.query.tab)), graphCanvas = ref<InstanceType<typeof G6GraphCanvas>>()
const activeOverviewLayers = ref(['case','entity','event','relation','evidence'])
const caseDetail = ref<any>({}), events = ref<any[]>([]), signals = ref<any[]>([]), history = ref<any[]>([])
const taskCases=ref<any[]>([]),selectedTaskCaseId=ref('')
const reportEditing=ref(false),reportSaving=ref(false),reportDraft=ref('')
const caseOverviewEditing=ref(false),caseOverviewSaving=ref(false)
const caseOverviewForm=ref<Record<string,string>>({})
const frameworkOptionFallbacks:Record<string,string[]>={
  reportingDirection:['LOCAL','HEAD_OFFICE','REGULATOR'],
  triggerPoint:['SYSTEM_IDENTIFICATION','MANUAL_REVIEW'],
  urgencyLevel:['NORMAL','URGENT'],
  businessCaseStatus:['DRAFT','IN_REVIEW','PENDING_APPROVAL','APPROVED','REJECTED','CLOSED'],
  businessRiskLevel:['LOW','MEDIUM','HIGH','CRITICAL']
}
const optionsFor=(fieldCode:string)=>frameworkOptionFallbacks[fieldCode]||[]
const reportingDirectionOptions=computed(()=>optionsFor('reportingDirection'))
const triggerPointOptions=computed(()=>optionsFor('triggerPoint'))
const urgencyLevelOptions=computed(()=>optionsFor('urgencyLevel'))
const businessCaseStatusOptions=computed(()=>optionsFor('businessCaseStatus'))
const businessRiskLevelOptions=computed(()=>optionsFor('businessRiskLevel'))
const workerResult = ref<any>(null), rawWorkerResult=ref<any>(null), workerResultSource=ref(''), workerJobId = ref(''), selectedGraphNode=ref<any>(null), selectedGraphEdge=ref<any>(null), rawGraphNodes=ref<any[]>([]), graphNodes = ref<any[]>([]), graphEdges = ref<any[]>([])
const cleanCaseName=(value:any)=>String(value||'').trim()
  .replace(/^.*?可疑案例[0-9０-９]+\s*[—–-]+\s*/,'')
  .replace(/^[\s—–-]+/,'')
const displayCaseName=computed(()=>cleanCaseName(caseDetail.value.caseName))
const structuredCaseResult=computed<any>(()=>{
  const rows=Array.isArray(workerResult.value?.results)?workerResult.value.results:[]
  const caseId=String(route.params.caseId||route.params.id||'')
  return rows.find((item:any)=>String(item?.caseId||'')===caseId)||null
})
const suspiciousReport=computed(()=>structuredCaseResult.value?.suspiciousReport
  ||structuredCaseResult.value?.analysisReport||{})
const suspiciousReportText=computed(()=>{
  const direct=String(suspiciousReport.value?.analysisText||'').trim()
  if(direct)return direct
  const texts=suspiciousReport.value?.analysisTexts
  if(texts&&typeof texts==='object'){
    const joined=Object.values(texts).filter(value=>typeof value==='string'&&value.trim()).join('\n\n')
    if(joined)return joined
  }
  return ''
})
const recognitionMode=computed(()=>String(structuredCaseResult.value?.recognitionMode
  ||workerResult.value?.recognitionMode||'').toUpperCase())
const reportSourceGenerated=computed(()=>recognitionMode.value==='NEW')
const recognitionSourceLabel=computed(()=>recognitionMode.value==='HISTORICAL'
  ?'历史复用':recognitionMode.value==='NEW'?'系统生成':'来源未知')
const reportSourceLabel=computed(()=>suspiciousReportText.value
  ?recognitionSourceLabel.value:'报告缺失')
const reportSourceTagType=computed(()=>suspiciousReportText.value
  ?(reportSourceGenerated.value?'success':'info'):'warning')
const extractionGenerated=computed(()=>structuredCaseResult.value?.extractionResult?.generated
  ??reportSourceGenerated.value)
const extractionSourceLabel=computed(()=>structuredCaseResult.value?.extractionResult?.sourceLabel
  ||recognitionSourceLabel.value)
const similarCases=computed<any[]>(()=>structuredCaseResult.value?.similarityMatch?.matches
  ||structuredCaseResult.value?.caseAnalysis?.similarCases||[])
const similaritySummary=computed(()=>structuredCaseResult.value?.similarityMatch?.summary
  ||structuredCaseResult.value?.caseAnalysis?.summary||'当前案例暂无相似度匹配结果')
const matters=ref<any[]>([]),techniques=ref<any[]>([]),reviewSuggestions=ref<any[]>([])
const reasoning=ref<any>({})
const coreChain=ref<any>({})
const knowledgeExplanation=ref<any>({chains:[],multiStageMatter:null})
const analysisLoadErrors=ref<Record<string,string>>({})
const analysisLoadErrorText=computed(()=>{
  const labels:Record<string,string>={
    events:'事件',signals:'交易信号',workflow:'处理记录',graph:'案例图谱',
    worker:'识别结果',matters:'事实归纳',techniques:'AMLTRIX技术',
    suggestions:'后续建议',reasoning:'推理结果',coreChain:'核心链',
    knowledge:'知识解释链',metadata:'案例元数据'
  }
  const failed=Object.keys(analysisLoadErrors.value).map(key=>labels[key]||key)
  return failed.length?`${failed.join('、')}加载失败，请稍后重试`:''
})
const knowledgeExplanationChains=computed<any[]>(()=>knowledgeExplanation.value?.chains||[])
const multiStageMatter=computed<any>(()=>knowledgeExplanation.value?.multiStageMatter||null)
const explanationMatters=computed<any[]>(()=>Array.isArray(knowledgeExplanation.value?.matters)
  ?knowledgeExplanation.value.matters:[])
const atomicMatters=computed<any[]>(()=>explanationMatters.value.length
  ?explanationMatters.value:matters.value)
const showAtomicFacts=ref(false)
const convergedMatters=computed<any[]>(()=>Array.isArray(knowledgeExplanation.value?.convergedMatters)
  ?knowledgeExplanation.value.convergedMatters:atomicMatters.value)
const displayedMatters=computed<any[]>(()=>showAtomicFacts.value
  ?atomicMatters.value:convergedMatters.value)
const explanationMatterCount=computed(()=>displayedMatters.value.length)
const hasAmltrixExplanation=computed(()=>knowledgeExplanationChains.value.length>0
  &&multiStageMatter.value?.sourceType==='TACTIC')
const analysisConclusionLabel=computed(()=>'事实归纳')
const analysisConclusionHint=computed(()=>'收敛事实')
const indicatorResults=computed<any[]>(()=>reasoning.value?.indicatorResults||[])
const indicatorById=computed(()=>new Map(indicatorResults.value.map((item:any)=>[
  String(item.calculationId||item.indicatorResultId||item.id||''),item
])))
const indicatorMode=(item:any)=>String(
  item?.assertionMode||item?.explanation?.assertionMode
  ||(item?.semanticScore!=null||item?.explanation?.semanticScore!=null?'SEMANTIC_MATCH':'UNKNOWN')
)
const indicatorModeText=(value:any)=>({
  SEMANTIC_MATCH:'语义匹配',
  RULE_EXECUTION:'规则执行命中',
  FORMAL_EXECUTION:'正式执行命中',
  MODEL_INFERENCE:'模型推断',
  INDICATOR_KNOWLEDGE_BINDING:'指标知识映射',
  MIXED:'多种匹配方式',
  UNKNOWN:'匹配方式待确认'
} as Record<string,string>)[String(value||'UNKNOWN')]||businessEnumText(value)
const indicatorResultModeText=(item:any)=>indicatorModeText(indicatorMode(item))
const indicatorExplanationText=(item:any)=>String(
  item?.explanation?.decisionBoundary||item?.description||item?.indicatorName||'—'
)
const linkedIndicators=(row:any)=>(row?.indicatorResultRefs||[])
  .map((id:any)=>indicatorById.value.get(String(id))).filter(Boolean)
const techniqueIndicatorNames=(row:any)=>{
  const names=[...new Set(linkedIndicators(row).map((item:any)=>
    String(item.indicatorName||item.indicatorCode||'')).filter(Boolean))]
  return names.join('、')||'—'
}
const techniqueIndicatorMode=(row:any)=>{
  const modes=[...new Set(linkedIndicators(row).map(indicatorResultModeText))]
  return modes.join('、')||'—'
}
const factBasisVisible=ref(false)
const factBasisMode=ref<'EVENT'|'EVIDENCE'|'INDICATOR'>('EVENT')
const factBasisMatter=ref<any>({})
const factBasisTitle=computed(()=>{
  const type={EVENT:'标准化事件',EVIDENCE:'文本证据',INDICATOR:'指标结果'}[factBasisMode.value]
  return `${factBasisMatter.value?.summary||factBasisMatter.value?.patternName||'分析依据'} · ${type}`
})
const factBasisEvents=computed<any[]>(()=>{
  const refs=(factBasisMatter.value?.eventRefs||[]).map((item:any)=>String(item))
  return refs.map((eventId:string)=>{
    const matched=eventModels.value.find((event:any)=>
      [event.internalId,event.eventId,event.uid,event.id].some(value=>String(value||'')===eventId))
    return matched||{internalId:eventId,businessId:eventId,name:'关联事件详情待加载',type:'—'}
  })
})
const factBasisEvidence=computed<any[]>(()=>Array.isArray(factBasisMatter.value?.evidenceRefs)
  ?factBasisMatter.value.evidenceRefs:[])
const factBasisIndicators=computed<any[]>(()=>{
  const refs=new Set((factBasisMatter.value?.indicatorResultRefs||[]).map((item:any)=>String(item)))
  return indicatorResults.value.filter((item:any)=>
    refs.has(String(item.calculationId||item.indicatorResultId||item.id||'')))
})
const openFactBasis=(matter:any,mode:'EVENT'|'EVIDENCE'|'INDICATOR')=>{
  factBasisMatter.value=matter
  factBasisMode.value=mode
  factBasisVisible.value=true
}
const openKnowledgeFact=(chain:any)=>openFactBasis({
  summary:chain?.technique,
  eventRefs:chain?.eventRefs||[],
  evidenceRefs:chain?.evidenceRefs||[],
  indicatorResultRefs:chain?.indicatorResultRefs||[]
},'EVENT')
const evidencePosition=(evidence:any)=>{
  const start=Number(evidence?.start)
  const end=Number(evidence?.end)
  return Number.isFinite(start)&&Number.isFinite(end)?`${start + 1}–${end}`:'—'
}
const factEventAmount=(event:any)=>{
  if(event?.amount===''||event?.amount==null)return '—'
  const amount=Number(event.amount)
  const value=Number.isFinite(amount)
    ?amount.toLocaleString('zh-CN',{minimumFractionDigits:2,maximumFractionDigits:2})
    :String(event.amount)
  return `${value} ${event?.currency||''}`.trim()
}
const factTypeText=(value:string)=>({
  CROSS_SUBJECT_TRANSFER:'跨主体转移',
  ASSET_RECONFIGURATION:'同账户资金动作',
  SHORT_WINDOW_COMPOSITE:'短窗口资金汇总',
  ORGANIZED_ROLE_ARRANGEMENT:'组织与角色安排',
  THRESHOLD_AMOUNT_ACTIVITY:'阈值附近资金活动',
  THRESHOLD_STRUCTURING:'阈值下拆分交易',
  ROUND_AMOUNT_STRUCTURING:'整数金额拆分交易',
  HIGH_FREQUENCY_DIGITAL_PAYMENT:'高频数字支付',
  HIGH_FREQUENCY_PASS_THROUGH:'高频收付后快速转出',
  CONTEXTUAL_RISK_MISMATCH:'交易行为与客户画像不符',
  CASH_DEPOSIT:'现金存入',
  ACCOUNT_MULE_USAGE:'银行卡提供与代持',
  CASH_WITHDRAWAL:'取现',
  CASH_DELIVERY:'现金交付',
  ONWARD_FUNDS_TRANSFER:'后续资金转移',
  PRECIOUS_METAL_CONVERSION:'贵金属转换',
  DIGITAL_ASSET_CONVERSION:'数字资产转换',
  DEFI_ACTIVITY:'DeFi 活动',
  CROSS_CHAIN_TRANSFER:'跨链转移',
  MULTI_ACCOUNT_TRANSFER:'多账户转移',
  MULTI_ACCOUNT_LAYERING:'多账户分层转移',
  CROSS_BORDER_MULTI_ACCOUNT_TRANSFER:'跨境多账户转移',
  LOAN_JUSTIFICATION:'贷款名义',
  FICTITIOUS_TRADE:'虚构交易',
  SERVICE_FEE_JUSTIFICATION:'服务费名义',
  LEGITIMATE_ASSET_INVESTMENT:'合法资产投资',
  OPERATIONAL_EVASION:'操作规避',
  STRUCTURING_FACT_GROUP:'阈值及测试支付',
  TRANSFER_FACT_GROUP:'多账户资金转移',
  CASH_FACT_GROUP:'现金资金路径',
  ASSET_ROUTE_FACT_GROUP:'资产转换路径',
  ORGANIZATION_FACT_GROUP:'组织与操作规避',
  JUSTIFICATION_FACT_GROUP:'资金名义解释'
} as Record<string,string>)[value]||value||'事实归纳'
const patternNameText=(code:string)=>({
  TEXT_THRESHOLD_STRUCTURING:'阈值下分笔存入行为',
  TEXT_CROSS_BORDER_MULTI_ACCOUNT:'多账户分散后跨境转移行为',
  TEXT_HIGH_FREQUENCY_DIGITAL_PAYMENT:'高频第三方支付活动'
} as Record<string,string>)[code]||code||'待命名模式'
const patternFormalText=(code:string)=>({
  TEXT_THRESHOLD_STRUCTURING:'低于报告阈值的分笔操作与指标结果共同支持阈值下拆分行为模式。',
  TEXT_CROSS_BORDER_MULTI_ACCOUNT:'多账户分散及跨区域转移事件与指标结果共同支持跨境多账户转移行为模式。',
  TEXT_HIGH_FREQUENCY_DIGITAL_PAYMENT:'高频交易与第三方支付事件和指标结果共同支持高频数字支付行为模式。'
} as Record<string,string>)[code]||'该模式由标准化事件与指标结果共同识别。'
const patternIndicatorModeText=(row:any)=>{
  const modes:string[]=[...new Set<string>(linkedIndicators(row).map(indicatorResultModeText))]
  return modes.length===1?modes[0]:'指标结果'
}
const cleanPatternText=(value:any,row?:any)=>String(value||'—')
  .replace(/^材料中的/,'')
  .replace(/^材料反映的?/,'')
  .replace(/正式指标/g,patternIndicatorModeText(row))
const behaviorPatterns=computed<any[]>(()=>reasoning.value?.behaviorPatternOccurrences||[])
const riskEvents=computed<any[]>(()=>reasoning.value?.riskEvents||[])
const amltrixTactics=computed<string[]>(()=>[...new Set(knowledgeExplanationChains.value
  .flatMap((chain:any)=>String(chain.tactic||'').split(/[、,]/))
  .map((item:string)=>item.trim()).filter((item:string)=>item&&!/待配置|尚未/.test(item)))])
const amltrixLinkedRiskCount=computed(()=>new Set(knowledgeExplanationChains.value
  .filter((chain:any)=>chain.relation==='补强解释')
  .map((chain:any)=>String(chain.risk||'')).filter(Boolean)).size)
const productReady=computed(()=>eventModels.value.length>0&&(behaviorPatterns.value.length>0||matters.value.length>0||riskEvents.value.length>0))
const alternativeExplanations=computed<any[]>(()=>reasoning.value?.alternativeExplanations||[])
const investigationHypotheses=computed<any[]>(()=>reasoning.value?.investigationHypotheses||[])
const displayBankCode = computed(() => ['中国邮储银行','中国邮政储蓄银行','BANK001'].includes(String(caseDetail.value.bankCode || '')) ? '中国测试银行' : (caseDetail.value.bankCode || '银行待补充'))
const displayScenario = computed(() => ({
  AML:'反洗钱',
  ANTI_FRAUD:'反欺诈'
} as Record<string,string>)[String(caseDetail.value.scenarioCode||'')] || caseDetail.value.scenarioCode || '场景待补充')
const overviewTooltipOptions={popperClass:'case-overview-cell-tooltip',placement:'top',showAfter:250,hideAfter:0}
const structuredFramework=computed<any>(()=>structuredCaseResult.value?.extractionResult?.data
  ||structuredCaseResult.value?.frameworkExtraction||null)
const workerCase = computed(() => structuredFramework.value?.basic_info
  ||workerResult.value?.nodes?.cases?.[0]||null)
const graphByType = (type:string) => graphNodes.value.filter((n:any)=>String(n.properties?.canonicalType || n.properties?.nodeType || n.label || '').toUpperCase()===type).map((n:any)=>({ uid:n.uid||n.id, ...(n.properties||{}), ...n }))
const persistedBusinessId = (item:any) => {
  const direct=item?.businessId||item?.business_id||item?.properties?.businessId
  if(direct)return String(direct)
  const itemType=String(item?.properties?.canonicalType||item?.properties?.nodeType||item?.canonicalType||item?.nodeType||item?.label||'').toUpperCase()
  if(itemType==='CASE')return displayCaseSequenceId.value
  const candidates=[item?.uid,item?.id,item?.eventId,item?.event_id,item?.signalId,item?.signal_id,
    item?.entityId,item?.entity_id,item?.instanceId].filter(Boolean).map(String)
  const graphNode=graphNodes.value.find((node:any)=>{
    const nodeIds=[node?.uid,node?.id,node?.properties?.instanceId].filter(Boolean).map(String)
    return candidates.some(candidate=>nodeIds.includes(candidate))
  })
  return String(graphNode?.properties?.businessId||'业务ID待同步')
}
const displayReferenceId=(value:any)=>{
  if(value===null||value===undefined||String(value).trim()==='')return '未建立业务关联'
  const id=String(value)
  if(/^\d+(?:-[A-Z]{3}-\d+)?$/i.test(id))return id
  const graphNode=graphNodes.value.find((node:any)=>
    [node?.uid,node?.id,node?.properties?.instanceId].filter(Boolean).map(String).includes(id))
  return String(graphNode?.properties?.businessId||'业务关联待同步')
}
const workerSourceText = computed(() => workerResult.value?.texts?.normalized_text || workerResult.value?.texts?.canonical_text || workerResult.value?.texts?.raw_text || workerCase.value?.raw_text || '')
const caseModel = computed(() => ({ ...(workerCase.value||{}), uid:String(caseDetail.value.id||'待补充'), name:caseDetail.value.caseName || '未命名案例', type:caseDetail.value.caseType || '待补充', business_domain:caseDetail.value.scenarioCode || '待补充', risk_level:caseDetail.value.riskLevel || '待补充', suspected_crime_type:workerCase.value?.suspected_crime_type || caseDetail.value.suspectedCrimeType || '', case_source:caseDetail.value.caseSource || '未知', raw_text:workerSourceText.value || caseDetail.value.description || '未保存原始文本' }))
const eventMatchKey=(event:any)=>`${String(event?.name||event?.eventName||event?.event_name||'').trim()}|${String(event?.type||event?.eventType||event?.event_type||'').trim()}`
const meaningfulEventName=(event:any)=>{
  const raw=String(event?.name||event?.eventName||event?.event_name||'').trim()
  if(raw&&raw.toUpperCase()!=='UNNAMED_WORKER_EVENT')return raw
  const typeName=String(event?.type||event?.eventType||event?.event_type||'').replace(/^\d{2}-/,'').trim()
  const text=`${event?.event_text||event?.eventText||event?.event_description||''} ${event?.description||''}`
  if(typeName.includes('收款')&&/(?:多笔|三笔|归集)/.test(text))return '多笔资金收取'
  if((typeName.includes('转账')||typeName.includes('付款'))&&/(?:分拆|分散).*转出/.test(text)){
    return `${String(event?.channel||'').includes('网银')||text.includes('网银')?'网银':''}分拆资金转出`
  }
  return typeName||'交易事件'
}
const trimBusinessListItem=(value:any)=>String(value||'')
  .trim()
  .replace(/[。；;、，,\s]+$/g,'')
  .replace(/。+[、，,]+/g,'、')
  .replace(/[、，,]+。+/g,'。')
  .replace(/([。；、，])\1+/g,'$1')
const dbEventFor=(event:any)=>{
  const workerId=String(event?.uid||event?.eventId||'')
  return events.value.find((item:any)=>String(item.eventStandardCode||'')===workerId)
    ||events.value.find((item:any)=>eventMatchKey(item)===eventMatchKey(event))
    ||null
}
const indicatorNamesForEvent=(eventIds:any[])=>{
  const targets=new Set(eventIds.filter(Boolean).map(String))
  if(!targets.size)return []
  return [...new Set(indicatorResults.value
    .filter((item:any)=>{
      const refs=item?.eventRefs||item?.explanation?.eventRefs||[]
      return Array.isArray(refs)&&refs.some((ref:any)=>targets.has(String(ref)))
    })
    .map((item:any)=>trimBusinessListItem(item.indicatorName||item.definitionName||item.indicatorCode))
    .filter(Boolean))]
}
const eventRecognitionRule=(event:any)=>{
  const explicit=trimBusinessListItem(event?.recognitionRule||event?.recognition_rule)
  if(explicit)return explicit
  const typeName=trimBusinessListItem(String(event?.type||event?.eventType||'其他事件').replace(/^\d{2}-/,''))
  const method=String(event?.definitionMatchMethod||event?.definition_match_method||'').toUpperCase()
  if(method==='EVENT_TYPE_DICTIONARY'){
    return `按事件类型字典匹配：根据案例文本中的主体、行为和时间语义识别为“${typeName}”`
  }
  if(method==='PENDING_EVENT_CLASSIFICATION'){
    return `按案例文本中的主体、行为和时间语义抽取为事件，当前归入“${typeName}”`
  }
  return `根据案例文本中的主体、行为和时间语义识别为“${typeName}”`
}
const inferredProductService=(event:any)=>{
  const explicit=event?.product_service||event?.productService
  if(explicit)return explicit
  const text=workerSourceText.value
  const eventText=`${event?.name||event?.eventName||''}${event?.type||event?.eventType||''}`
  const values:string[]=[]
  if(/开户/.test(eventText))values.push('银行账户服务')
  if(/支付宝|微信支付|第三方支付/.test(eventText)){
    if(text.includes('支付宝'))values.push('支付宝')
    if(text.includes('微信支付'))values.push('微信支付')
    if(!values.length)values.push('第三方支付')
  }else if(text.includes('电子银行')&&/交易|收款|付款|转账/.test(eventText)){
    values.push('电子银行')
  }
  return [...new Set(values)].join('、')||null
}
const inferredRiskType=(event:any)=>{
  const explicit=event?.risk_type||event?.riskType
  if(explicit)return explicit
  const text=workerSourceText.value
  const eventText=`${event?.name||event?.eventName||''}${event?.type||event?.eventType||''}`
  const values:string[]=[]
  if(/开户|账户|收款|付款|转账|交易/.test(eventText))values.push('客户风险')
  if(/支付宝|微信支付|第三方支付/.test(eventText))values.push('渠道风险')
  if((text.includes('跨区域')||text.includes('多个省份')||text.includes('多省'))&&/交易|收款|付款|转账/.test(eventText))values.push('地域风险')
  return [...new Set(values)].join('、')||null
}
const normalizedEvent = (source:any) => {
  const dbEvent=dbEventFor(source)
  const event={...(source||{}),...(dbEvent||{})}
  const internalId=dbEvent?.eventId||source?.eventId||source?.event_id||source?.uid
  const indicators=indicatorNamesForEvent([source?.uid,source?.eventId,internalId,dbEvent?.eventStandardCode])
  const businessRule=eventRecognitionRule({...event,...source})
  return {
    ...event,
    internalId,
    businessId:dbEvent?.businessId||persistedBusinessId(source),
    uid:source?.uid||source?.event_id||internalId,
    name:meaningfulEventName({...event,...source}),
    type:source?.type||source?.event_type||event.eventType||'未分类事件',
    event_text:source?.event_text||source?.event_description||source?.description||source?.eventText||source?.name||source?.event_name||event.eventName||'',
    started_at:source?.started_at||source?.event_start_date||event.eventTime||source?.occurredAt,
    ended_at:source?.ended_at||source?.event_end_date||source?.endedAt,
    amount:source?.amount??eventEvidence(source).amount??'',
    currency:source?.currency||eventEvidence(source).currency||'',
    rule_name:businessRule||'未形成可解释识别规则',
    product_service:source?.product_service||inferredProductService({...event,...source}),
    value_instrument:source?.value_tool||event.valueInstrument||event.value_instrument
      ||(/交易|收款|付款|转账/.test(`${source?.name||event.eventName||''}${source?.type||event.eventType||''}`)?'银行账户资金':'银行账户'),
    risk_type:source?.risk_type||inferredRiskType({...event,...source}),
    risk_indicator:indicators.join('、')||source?.risk_indicator||event.riskIndicator||event.risk_indicator||'未命中正式风险指标',
    disposal_measure:source?.disposition_measures||event.disposalMeasure||event.disposal_measure||caseDetail.value.disposalMeasure
      ||'原文未提供，待人工研判'
  }
}
const eventDisplayKey=(event:any)=>[
  event?.type||event?.eventType||event?.event_type,
  event?.name||event?.eventName||event?.event_name,
  event?.event_text||event?.eventText||event?.description||event?.event_description,
  event?.started_at||event?.eventTime||event?.event_start_date,
  event?.ended_at||event?.endedAt||event?.event_end_date
].map(value=>String(value||'').replace(/\s+/g,'').replace(/[，。；、,.;]+$/g,'')).join('|')
const eventModels = computed(() => {
  const source=structuredFramework.value?.events?.length
    ? structuredFramework.value.events
    : workerResult.value?.nodes?.events?.length ? workerResult.value.nodes.events : events.value
  const unique=new Map<string,any>()
  source.map(normalizedEvent).forEach((event:any)=>{
    const key=eventDisplayKey(event)
    if(!unique.has(key))unique.set(key,event)
  })
  return [...unique.values()]
})
const evidenceAssociation=(evidence:any)=>{
  const evidenceId=String(evidence?.uid||evidence?.id||evidence?.instanceId||'')
  const graphEvidence=graphNodes.value.find((node:any)=>
    String(node.uid||node.id||node.properties?.instanceId||'')===evidenceId)
  const outgoing=graphEdges.value.filter((edge:any)=>
    String(edge.source||edge.from||edge.start||'')===evidenceId
    &&['EVIDENCE_SUPPORTS','EVIDENCE_CONTRADICTS'].includes(String(edge.type||edge.label||'')))
  const fallbackTarget=graphEvidence?.properties?.relatedEventId
    ||graphEvidence?.properties?.relatedRiskHypothesisId
  const targetIds=[...new Set([
    ...outgoing.map((edge:any)=>String(edge.target||edge.to||edge.end||'')),
    ...(fallbackTarget?[String(fallbackTarget)]:[])
  ].filter(Boolean))]
  const targets=targetIds.map(targetId=>graphNodes.value.find((node:any)=>
    String(node.uid||node.id||'')===targetId)).filter(Boolean)
  return {
    graphEvidence,
    associationType:[...new Set(targets.map((node:any)=>localizedNodeType(
      node.properties?.canonicalType||node.properties?.nodeType||node.label)))].join('、')
      ||evidence?.association_type||evidence?.associationType||'未建立业务关联',
    associationId:[...new Set(targets.map((node:any)=>String(node.properties?.businessId||''))
      .filter(Boolean))].join('、')||'未建立业务关联'
  }
}
const evidenceModels = computed(() => {
  const source=structuredFramework.value?.evidences?.length ? structuredFramework.value.evidences
    : workerResult.value?.nodes?.evidences?.length ? workerResult.value.nodes.evidences
      : (graphByType('EVIDENCE').length ? graphByType('EVIDENCE') : signals.value.map((s:any)=>({uid:s.signalId,businessId:s.businessId,type:s.signalType,name:s.scenarioCode,summary:(s.reasonCodes||[]).join('、')||s.decision,source:s.algorithmId})))
  return source.map((e:any)=>{
    const association=evidenceAssociation(e)
    return {
      ...e,...(association.graphEvidence?.properties||{}),
      businessId:association.graphEvidence?.properties?.businessId||persistedBusinessId(e),
      type:e.type||e.source||'未分类证据',
      summary:e.summary||e.description||'',
      associationType:association.associationType,
      associationId:association.associationId
    }
  })
})
const isStructuredCase = computed(() => /STRUCT/i.test(String(caseDetail.value.caseSource||'')))
const graphCustomersWithPhysicalIdentity = computed(() => graphByType('CUSTOMER').filter((item:any) =>
  item.name || item.customer_no || item.customerNo || item.id_number || item.idNumber || item.entity_id || item.entityId))
const graphCustomersWithoutPhysicalIdentity = computed(() => graphByType('CUSTOMER').filter((item:any) =>
  !item.name && !item.customer_no && !item.customerNo && !item.id_number && !item.idNumber && !item.entity_id && !item.entityId))
const generatedCustomerPlaceholders = computed(() => graphCustomersWithoutPhysicalIdentity.value.filter((item:any) =>
  item.accountHash || item.account_hash || /^CUST-CASE-/i.test(String(item.graphId || item.uid || ''))))
const incompletePhysicalCustomers = computed(() => graphCustomersWithoutPhysicalIdentity.value.filter((item:any) =>
  !generatedCustomerPlaceholders.value.includes(item)))
const customerModels = computed(() => structuredFramework.value?.customers?.length
  ? structuredFramework.value.customers.map((item:any)=>({...item,businessId:item.entity_id||persistedBusinessId(item)}))
  : workerResult.value?.nodes?.customers?.length
    ? workerResult.value.nodes.customers.map((item:any)=>({...item,businessId:persistedBusinessId(item)}))
  : (isStructuredCase.value ? [] : graphCustomersWithPhysicalIdentity.value.map((item:any)=>({...item,businessId:persistedBusinessId(item)}))))
const accountModels = computed(() => (structuredFramework.value?.accounts?.length
  ? structuredFramework.value.accounts
  : workerResult.value?.nodes?.accounts?.length ? workerResult.value.nodes.accounts : graphByType('ACCOUNT'))
  .map((item:any)=>({...item,businessId:persistedBusinessId(item)})))
const otherEntityTypeNames:Record<string,string>={
  ORGANIZATION:'机构',MERCHANT:'商户',WALLET:'钱包',
  DEVICE:'设备',IPADDRESS:'IP地址',ADDRESS:'地址'
}
const otherEntityInternalKeys=new Set([
  'uid','id','namespace_uid','namespaceUid','nodeType','canonicalType',
  'graphPlane','graphDomain','objectSemantics','instanceId','caseId',
  'businessId','contentSha256','sourceRef','epistemicType','visualLabel','visualType'
])
const otherEntityModels=computed(()=>structuredFramework.value?.other_entities?.length
  ? structuredFramework.value.other_entities.map((entity:any)=>({
      entityId:entity.entity_id||entity.id||'待补充',entityType:entity.entity_type||entity.type||'其他实体',
      attribute1:entity.entity_attr_1||entity.name||'待补充',attribute2:entity.entity_attr_2||'待补充',
      attribute3:entity.entity_attr_3||'待补充',attribute4:entity.entity_attr_4||'待补充'
    }))
  : Object.entries(otherEntityTypeNames).flatMap(([type,typeName])=>
  graphByType(type).map((entity:any)=>{
    const attributes=Object.entries(entity)
      .filter(([key,value])=>!otherEntityInternalKeys.has(key)&&value!==''&&value!==null&&value!==undefined)
      .map(([key,value])=>`${graphPropertyLabels[key]||key}：${displayGraphValue(value)}`)
    return {
      entityId:persistedBusinessId(entity),
      entityType:typeName,
      attribute1:attributes[0]||'待补充',
      attribute2:attributes[1]||'待补充',
      attribute3:attributes[2]||'待补充',
      attribute4:attributes.slice(3).join('；')||'待补充'
    }
  })))
const rawOverviewValue = (...values:any[]) => {
  const value=values.find(item=>item!==undefined&&item!==null&&String(item).trim()!=='')
  if(Array.isArray(value))return value.length?value.join('、'):null
  if(value&&typeof value==='object')return JSON.stringify(value)
  return value===undefined?null:value
}
const overviewValue = (...values:any[]) => {
  const value=rawOverviewValue(...values)
  return value===null?'待补充':String(value)
}
const overviewField = (label:string,value:any,wide=false) => {
  const normalized=rawOverviewValue(value)
  return {label,value:normalized===null?'待补充':String(normalized),wide,missing:normalized===null}
}
const overviewDate = (value:any) => {
  const normalized=rawOverviewValue(value)
  return normalized===null?null:String(normalized).slice(0,10)
}
const dedupeOverviewFields = (fields:any[]) => {
  const seen=new Set<string>()
  return fields.filter(field=>{
    const normalized=String(field.label||'').trim()
    if(!normalized||seen.has(normalized))return false
    seen.add(normalized)
    return true
  })
}
const compactOverviewFields = (fields:any[]) => dedupeOverviewFields(fields).filter(field=>!field.wide)
const expandedOverviewFields = (fields:any[]) => dedupeOverviewFields(fields).filter(field=>field.wide)
const displayCaseSequenceId=computed(()=>{
  const value=Number(caseDetail.value.id)
  return Number.isInteger(value)&&value>=1&&value<=1_000_000?String(value):'待补充'
})
const displayBusinessDomain=(value:any)=>{
  const normalized=String(value||'').trim()
  if(/欺诈|FRAUD/i.test(normalized))return '反欺诈'
  if(/洗钱|AML|医疗腐败/i.test(normalized))return '反洗钱'
  return normalized||'待补充'
}
const caseOverviewFields = computed(() => {
  const item=caseModel.value
  return [
    overviewField('案例ID',displayCaseSequenceId.value),
    overviewField('案例名称',displayCaseName.value),
    overviewField('案例描述',rawOverviewValue(caseDetail.value.description,item.description,item.raw_text),true),
    overviewField('业务领域',displayBusinessDomain(rawOverviewValue(caseDetail.value.businessDomain,workerCase.value?.business_domain,workerCase.value?.businessDomain,'01-反洗钱'))),
    overviewField('案例类型',rawOverviewValue(caseDetail.value.businessCaseType,item.business_case_type,item.businessCaseType)),
    overviewField('报送方向',rawOverviewValue(caseDetail.value.reportingDirection,item.reporting_direction,item.reportingDirection,item.report_direction,item.reportDirection),true),
    overviewField('案例触发点',rawOverviewValue(caseDetail.value.triggerPoint,item.trigger_point,item.triggerPoint,item.case_trigger,item.caseTrigger),true),
    overviewField('紧急程度',rawOverviewValue(caseDetail.value.urgencyLevel,item.urgency,item.urgency_level,item.urgencyLevel)),
    overviewField('案例上报时间',overviewDate(rawOverviewValue(caseDetail.value.reportedAt,item.reported_at,item.reportedAt,item.report_time,item.reportTime))),
    overviewField('案例状态',rawOverviewValue(caseDetail.value.businessCaseStatus,item.business_case_status,item.businessCaseStatus)),
    overviewField('风险等级',rawOverviewValue(caseDetail.value.businessRiskLevel,item.business_risk_level,item.businessRiskLevel)),
    overviewField('疑似涉罪类型',rawOverviewValue(caseDetail.value.suspectedCrimeType,item.suspected_crime_type,item.suspectedCrimeType),true),
    overviewField('可疑交易特征代码',rawOverviewValue(
      caseDetail.value.suspiciousTransactionFeatureCode,
      item.suspicious_transaction_feature_code,item.suspiciousTransactionFeatureCode,
      item.suspicious_feature_code,item.suspiciousFeatureCode),true),
    overviewField('处置措施',rawOverviewValue(caseDetail.value.disposalMeasure,item.disposal_measure,item.disposalMeasure),true)
  ]
})
const customerOverviewFields = (customer:any) => [
  overviewField('实体ID',rawOverviewValue(customer.businessId,persistedBusinessId(customer))),
  overviewField('客户名称',rawOverviewValue(customer.name,customer.customer_name,customer.customerName)),
  overviewField('客户号',rawOverviewValue(customer.customer_no,customer.customerNo)),
  overviewField('证件类型',rawOverviewValue(customer.id_type,customer.idType)),
  overviewField('证件号码',rawOverviewValue(customer.id_number,customer.idNumber)),
  overviewField('职业（对私）/行业（对公）',rawOverviewValue(customer.occupation,customer.industry),true),
  overviewField('国籍',rawOverviewValue(customer.nationality)),
  overviewField('归属省份',rawOverviewValue(customer.province,customer.owning_province,customer.owningProvince)),
  overviewField('归属机构',rawOverviewValue(customer.institution,customer.organization,customer.owning_institution,customer.owningInstitution)),
  overviewField('地址',rawOverviewValue(customer.address),true),
  overviewField('客户风险等级',rawOverviewValue(customer.risk_level,customer.riskLevel)),
  overviewField('法定代表人姓名',rawOverviewValue(customer.legal_representative_name,customer.legalRepresentativeName)),
  overviewField('法定代表人身份证件类型',rawOverviewValue(customer.legal_representative_id_type,customer.legalRepresentativeIdType)),
  overviewField('法定代表人证件号码',rawOverviewValue(customer.legal_representative_id_number,customer.legalRepresentativeIdNumber)),
  overviewField('控股股东/实际控制人姓名',rawOverviewValue(customer.controller_name,customer.controllerName),true),
  overviewField('控股股东/实际控制人身份证件类型',rawOverviewValue(customer.controller_id_type,customer.controllerIdType)),
  overviewField('控股股东/实际控制人证件号码',rawOverviewValue(customer.controller_id_number,customer.controllerIdNumber))
]
const accountOverviewFields = (account:any) => [
  overviewField('实体ID',rawOverviewValue(account.businessId,persistedBusinessId(account))),
  overviewField('账户类型',rawOverviewValue(account.account_type,account.accountType,account.type)),
  overviewField('持有人姓名',rawOverviewValue(account.holder_name,account.holderName,account.name)),
  overviewField('持有人身份证件类型',rawOverviewValue(account.holder_id_type,account.holderIdType)),
  overviewField('持有人证件号码',rawOverviewValue(account.holder_id_number,account.holderIdNumber)),
  overviewField('客户开户时间',rawOverviewValue(account.opened_at,account.openedAt,account.open_date,account.openDate)),
  overviewField('客户销户时间',rawOverviewValue(account.closed_at,account.closedAt,account.close_date,account.closeDate)),
  overviewField('归属省份',rawOverviewValue(account.province,account.owning_province,account.owningProvince)),
  overviewField('归属机构',rawOverviewValue(account.institution,account.organization,account.owning_institution,account.owningInstitution)),
  overviewField('账号',rawOverviewValue(account.account_no,account.accountNo),true),
  overviewField('银行卡类型',rawOverviewValue(account.card_type,account.cardType)),
  overviewField('开户行信息',rawOverviewValue(account.opening_bank,account.openingBank,account.bank_name,account.bankName),true),
  overviewField('银行卡号码',rawOverviewValue(account.card_no,account.cardNo),true)
]
const graphCount = (type:string) => graphNodes.value.filter((node:any) => String(node.label || node.properties?.nodeType || '').toUpperCase() === type).length
const rawTuGraphCount = (type:string) => rawGraphNodes.value.filter((node:any) =>
  String(node.properties?.canonicalType || node.properties?.nodeType || node.label || '').toUpperCase() === type).length
const consistencyAssessment = computed(() => {
  const processing:string[]=[]
  const data:string[]=[]
  const declaredEvents=Number(caseDetail.value.eventCount||0)
  if(declaredEvents!==eventModels.value.length) processing.push(`案例主表事件数${declaredEvents}与事件模型${eventModels.value.length}不同，需同步案例统计字段`)
  if(rawGraphNodes.value.length && rawTuGraphCount('EVENT')!==eventModels.value.length) processing.push(`事件模型${eventModels.value.length}个、TuGraph原始子图${rawTuGraphCount('EVENT')}个，需检查Worker事件映射、去重或图谱写入`)
  // 统一解释链会按展示边界裁剪账户，账户一致性必须和 TuGraph 原始案例子图比较。
  if(rawGraphNodes.value.length && rawTuGraphCount('ACCOUNT')!==accountModels.value.length) processing.push(`账户模型${accountModels.value.length}个、TuGraph原始子图${rawTuGraphCount('ACCOUNT')}个，需检查账户映射或图谱写入`)
  if(graphNodes.value.length && graphCount('EVIDENCE')!==evidenceModels.value.length) processing.push(`证据模型${evidenceModels.value.length}个、统一解释链${graphCount('EVIDENCE')}个，需检查证据归并或解释链投影`)
  // “有效客户”必须使用同一套物理身份过滤口径，不能拿全部占位节点与客户模型比较。
  if(graphCustomersWithPhysicalIdentity.value.length!==customerModels.value.length) processing.push(`有效客户模型${customerModels.value.length}个、TuGraph有效客户${graphCustomersWithPhysicalIdentity.value.length}个，需检查KYC映射或图谱写入`)
  if(generatedCustomerPlaceholders.value.length) processing.push(`TuGraph残留${generatedCustomerPlaceholders.value.length}个由账户哈希生成的占位客户节点，应使用当前建图规则重建清理`)
  if(incompletePhysicalCustomers.value.length) data.push(`源数据中${incompletePhysicalCustomers.value.length}个客户实体缺少姓名、客户号或证件号，已排除且不计入有效客户模型`)
  return {processing,data}
})
const isRelationalProjection = computed(() => workerResult.value?.meta?.transformer === 'STRUCTURED_RELATIONAL_PROJECTION')
const modelProvenance = computed(() => ({
  evidence:isRelationalProjection.value ? 'PostgreSQL · risk_signal + risk_transaction_materialized' : 'Worker 原始结果 · nodes.evidences',
  event:isRelationalProjection.value ? 'PostgreSQL · cf_risk_event + evidence_refs' : 'Worker 原始结果 · nodes.events',
  account:isRelationalProjection.value ? 'PostgreSQL · risk_transaction_materialized 关联账户' : 'Worker 原始结果 · nodes.accounts',
  customer:customerModels.value.length ? 'Worker/KYC 中具备姓名、客户号或证件号的真实实体' : '未取得客户主数据，不生成账户哈希占位客户'
}))
const eventEvidence = (event:any) => {
  const value=event?.evidence_refs ?? event?.evidenceRefs ?? {}
  if(typeof value==='string'){try{return JSON.parse(value)}catch{return {}}}
  return value && typeof value==='object' && !Array.isArray(value) ? value : {}
}
const workerNodes = computed(() => Object.values(workerResult.value?.nodes||{}).flatMap((items:any)=>Array.isArray(items)?items:[]))
const nodeName = (id: any) => workerNodes.value.find((n:any)=>n.uid===id||n.id===id)?.name || graphNodes.value.find(n => n.uid === id || n.id === id)?.name || id || '—'
const nodeTypeName = (id:any) => {
  const node:any=workerNodes.value.find((n:any)=>n.uid===id||n.id===id)||graphNodes.value.find((n:any)=>n.uid===id||n.id===id)
  return node?.type||node?.label||node?.properties?.nodeType||'待补充'
}
const localizedNodeType=(value:any)=>({
  CASE:'案件',CUSTOMER:'客户',ORGANIZATION:'机构',MERCHANT:'商户',ACCOUNT:'账户',
  WALLET:'钱包',DEVICE:'设备',IP_ADDRESS:'IP地址',IPADDRESS:'IP地址',ADDRESS:'地址',
  EVIDENCE:'证据',EVENT:'事件',INDICATOR_RESULT:'指标结果',INDICATORRESULT:'指标结果',
  BEHAVIOR_PATTERN:'行为模式',BEHAVIORPATTERN:'行为模式',RISK_HYPOTHESIS:'风险假设',
  RISKHYPOTHESIS:'风险假设',INVESTIGATION_HYPOTHESIS:'调查假设',
  INVESTIGATIONHYPOTHESIS:'调查假设'
} as Record<string,string>)[String(value||'').toUpperCase()]||'其他'
const chineseRelationName=(edge:any)=>{
  const value=String(edge?.properties?.relationName||edge?.properties?.displayName||edge?.relationName||'')
  return value&&/[\u4e00-\u9fff]/.test(value)?value:'关联关系'
}
const relations = computed(() => {
  const edges: any = workerResult.value?.edges || {}
  return Object.values(edges).flatMap((list:any) => (Array.isArray(list) ? list : []).map((e:any) => {
    const sourceId=e.source_uid||e.source||e.from||e.start
    const targetId=e.target_uid||e.target||e.to||e.end
    return {relationId:e.businessId||'未建立业务ID',type:chineseRelationName(e),sourceId:displayReferenceId(sourceId),targetId:displayReferenceId(targetId),source:nodeName(sourceId),target:nodeName(targetId),sourceType:localizedNodeType(nodeTypeName(sourceId)),targetType:localizedNodeType(nodeTypeName(targetId)),description:e.description||e.role||e.relation_text||''}
  }))
})
const overviewRelations = computed(() => structuredFramework.value?.relationships?.length
  ? structuredFramework.value.relationships.map((edge:any)=>({
      relationId:edge.relationship_id||edge.relation_id||'待补充',type:edge.relationship_type||edge.type||'关联关系',
      description:edge.description||'',sourceId:edge.source_node_id||'待补充',targetId:edge.target_node_id||'待补充',
      source:edge.source_node_name||edge.source_name||'待补充',target:edge.target_node_name||edge.target_name||'待补充',
      sourceType:edge.source_node_type||'待补充',targetType:edge.target_node_type||'待补充'
    }))
  : graphEdges.value.length ? graphEdges.value.map((edge:any)=>{
  const sourceId=edge.source||edge.from||edge.start
  const targetId=edge.target||edge.to||edge.end
  return {
    relationId:edge.properties?.businessId||edge.businessId||'未建立业务ID',
    type:chineseRelationName(edge),
    description:edge.description||edge.properties?.description||edge.properties?.relationCategory||'',
    sourceId:edge.properties?.sourceBusinessId||displayReferenceId(sourceId),
    targetId:edge.properties?.targetBusinessId||displayReferenceId(targetId),
    source:nodeName(sourceId),target:nodeName(targetId),
    sourceType:localizedNodeType(edge.properties?.sourceType||nodeTypeName(sourceId)),
    targetType:localizedNodeType(edge.properties?.targetType||nodeTypeName(targetId))
  }
}) : relations.value)
type SnapshotPlane = 'EVENT_GRAPH'|'REASONING_GRAPH'
type SnapshotType = {key:string;name:string;icon:string;color:string;plane:SnapshotPlane;planeName:string;formal?:boolean}
const snapshotTypes:SnapshotType[]=[
  {key:'CASE',name:'案件',icon:'案',color:'#13233a',plane:'EVENT_GRAPH',planeName:'事件图谱',formal:true},
  {key:'EVENT',name:'事件',icon:'事',color:'#1769e0',plane:'EVENT_GRAPH',planeName:'事件图谱',formal:true},
  {key:'EVIDENCE',name:'证据',icon:'证',color:'#e9a400',plane:'EVENT_GRAPH',planeName:'事件图谱',formal:true},
  {key:'CUSTOMER',name:'客户',icon:'客',color:'#00a870',plane:'EVENT_GRAPH',planeName:'事件图谱',formal:true},
  {key:'ORGANIZATION',name:'机构',icon:'机',color:'#0d9488',plane:'EVENT_GRAPH',planeName:'事件图谱',formal:true},
  {key:'MERCHANT',name:'商户',icon:'商',color:'#059669',plane:'EVENT_GRAPH',planeName:'事件图谱',formal:true},
  {key:'ACCOUNT',name:'账户',icon:'户',color:'#7c3aed',plane:'EVENT_GRAPH',planeName:'事件图谱',formal:true},
  {key:'WALLET',name:'钱包',icon:'包',color:'#8b5cf6',plane:'EVENT_GRAPH',planeName:'事件图谱',formal:true},
  {key:'DEVICE',name:'设备',icon:'设',color:'#0369a1',plane:'EVENT_GRAPH',planeName:'事件图谱',formal:true},
  {key:'IPADDRESS',name:'IP地址',icon:'IP',color:'#0284c7',plane:'EVENT_GRAPH',planeName:'事件图谱',formal:true},
  {key:'ADDRESS',name:'地址',icon:'址',color:'#0891b2',plane:'EVENT_GRAPH',planeName:'事件图谱',formal:true},
  {key:'INDICATORRESULT',name:'指标结果',icon:'指',color:'#22a447',plane:'REASONING_GRAPH',planeName:'事理图谱',formal:true},
  {key:'BEHAVIORPATTERN',name:'行为模式',icon:'模',color:'#00a3c7',plane:'REASONING_GRAPH',planeName:'事理图谱',formal:true},
  {key:'RISKHYPOTHESIS',name:'风险假设',icon:'险',color:'#f97316',plane:'REASONING_GRAPH',planeName:'事理图谱',formal:true},
  {key:'INVESTIGATIONHYPOTHESIS',name:'调查假设',icon:'查',color:'#be123c',plane:'REASONING_GRAPH',planeName:'事理图谱',formal:true}
]
const snapshotTypeMap=Object.fromEntries(snapshotTypes.map(item=>[item.key,item])) as Record<string,SnapshotType>
const coreSnapshotTypes=new Set(snapshotTypes.map(item=>item.key))
const g6SnapshotTypeMeta=computed(()=>Object.fromEntries(snapshotTypes.map(item=>[item.key,{name:item.name,color:item.color,icon:item.icon,planeName:item.planeName}]).concat([
  ['OTHER',{name:'其他节点',color:'#64748b',icon:'其',planeName:'其他'}]
])))
const normalizeSnapshotType=(value:any)=>String(value||'UNKNOWN').replace(/[^A-Za-z0-9]/g,'').toUpperCase()
const nodeSnapshotType=(node:any)=>{
  const raw=normalizeSnapshotType(node?.properties?.canonicalType||node?.properties?.nodeType||node?.type||node?.label)
  return snapshotTypeMap[raw]?raw:'OTHER'
}
const visibleSnapshotTypes=ref<Record<string,boolean>>(Object.fromEntries(snapshotTypes.map(item=>[item.key,item.plane==='EVENT_GRAPH'])))
const snapshotTypeCounts=computed(()=>{
  const counts:Record<string,number>={}
  graphNodes.value.forEach((node:any)=>{const key=nodeSnapshotType(node);counts[key]=(counts[key]||0)+1})
  return counts
})
const snapshotTypeCount=(key:string)=>snapshotTypeCounts.value[key]||0
const visibleSnapshotGroups=computed<any[]>(()=>{
  return [
    {key:'EVENT_GRAPH',name:'事件图谱',items:snapshotTypes.filter(item=>item.plane==='EVENT_GRAPH')},
    {key:'REASONING_GRAPH',name:'事理图谱',items:snapshotTypes.filter(item=>item.plane==='REASONING_GRAPH')}
  ]
})
const visibleGraphNodes=computed(()=>graphNodes.value
  .filter((node:any)=>visibleSnapshotTypes.value[nodeSnapshotType(node)]===true)
  .map((node:any)=>{
    const currentCaseId=String(route.params.caseId||route.params.id||'')
    const nodeCaseId=String(node.properties?.caseId||node.caseId||'')
    const visualPrimary=nodeSnapshotType(node)==='CASE'&&nodeCaseId===currentCaseId
    return {...node,visualPrimary,visualLabel:graphNodeTitle(node)}
  }))
const visibleGraphEdges=computed(()=>{
  const ids=new Set(visibleGraphNodes.value.map((node:any)=>String(node.uid||node.id)))
  return graphEdges.value
    .filter((edge:any)=>ids.has(String(edge.source||edge.from||edge.start))&&ids.has(String(edge.target||edge.to||edge.end)))
    .map((edge:any)=>{
      const visualLabel=relationDisplayName(edge)
      return {...edge,visualLabel,properties:{...(edge.properties||{}),relationName:visualLabel}}
    })
})
const snapshotLayout=computed(()=>'grid' as const)
function typeMeta(type:string):SnapshotType {
  return snapshotTypeMap[normalizeSnapshotType(type)]||{key:normalizeSnapshotType(type),name:'其他节点',icon:'其',color:'#64748b',plane:'EVENT_GRAPH',planeName:'其他'}
}
const graphTypeMeta=(node:any)=>typeMeta(nodeSnapshotType(node))
const graphNodeTitle=(node:any)=>String(node?.name||node?.properties?.caseName||node?.properties?.caseId||node?.properties?.eventName||node?.properties?.name||node?.properties?.summary||node?.uid||node?.id||'未命名节点')
const graphNodeRoleName=(node:any)=>({
  INDICATORRESULT:'指标计算结果',
  BEHAVIORPATTERN:'组合行为判断',
  RISKHYPOTHESIS:'风险解释',
  INVESTIGATIONHYPOTHESIS:'待核验调查问题'
} as Record<string,string>)[nodeSnapshotType(node)]||graphTypeMeta(node).name
// 关系名称、方向和端点约束统一由 graph_relation_type_registry 下发；
// 前端不再维护第二套中文关系字典，避免展示语义与元数据漂移。
const relationDisplayName=(edge:any)=>String(
  edge?.properties?.relationName
  || edge?.properties?.displayName
  || '关联关系'
)
const edgeCategoryName=(value:string)=>value==='INFERENCE'?'事理推理关系':value==='FACT'?'事件事实关系':value||'关系'
const edgeOriginName=(value:string)=>({
  WORKER_EXTRACTION:'材料抽取',CASE_ANALYSIS_SCOPE:'调查范围',
  SOURCE_REF:'来源记录',EVENT_TIME_ORDER:'事件时间顺序',
  RULE:'规则计算',MODEL:'模型推理',HUMAN_REVIEW:'人工复核'
} as Record<string,string>)[value]||value||'—'
const graphNodeNameById=computed(()=>{
  const result:Record<string,string>={}
  graphNodes.value.forEach((node:any)=>{result[String(node.uid||node.id)]=graphNodeTitle(node)})
  return result
})
const selectedEdgeSourceName=computed(()=>graphNodeNameById.value[String(selectedGraphEdge.value?.source||'')]||String(selectedGraphEdge.value?.source||'—'))
const selectedEdgeTargetName=computed(()=>graphNodeNameById.value[String(selectedGraphEdge.value?.target||'')]||String(selectedGraphEdge.value?.target||'—'))
const graphPropertyLabels:Record<string,string>={
  businessId:'业务实例ID',
  businessMeaning:'业务含义',evidenceSummary:'证据摘要',proves:'证明事项',
  instanceQuestion:'该实例回答',supportSummary:'形成依据',decisionBoundary:'结论边界',
  displayName:'账户实例名称',identityStatus:'账户识别状态',
  relatedEventId:'关联事件ID',relatedRiskHypothesisId:'关联风险判断ID',
  name:'名称',type:'业务类型',source:'证据来源',
  summary:'摘要',description:'说明',confidence:'置信度',risk_level:'风险等级',
  customer_no:'客户号',nationality:'国籍',address:'地址',id_type:'证件类型',
  id_number:'证件号码',occupation_or_industry:'职业或行业',
  account_no:'账号',holder_name:'账户持有人',opened_at:'开户日期',
  card_no:'卡号',card_type:'卡类型',event_name:'事件名称',event_type:'事件类型',
  event_time:'事件时间',event_text:'事件原文',started_at:'开始时间',ended_at:'结束时间',
  amount:'金额',channel:'渠道',location:'地点',status:'状态',
  currency:'币种',dataSource:'数据来源',data_source:'数据来源',
  transaction_type:'交易类型',transactionType:'交易类型',
  counterparty:'交易对手',counterparty_name:'交易对手',
  direction:'收付方向',balance:'账户余额',
  pattern_code:'模式编码',pattern_name:'模式名称',pattern_confidence:'模式置信度',
  title:'标题',risk_event_type:'风险事件类型',risk_confidence:'风险置信度',
  review_status:'复核状态',hypothesisId:'调查假设ID',hypothesisType:'调查假设类型',
  hypothesis:'调查假设',evidence_needed:'所需证据',recommended_actions:'建议行动',
  priority:'优先级',reason:'判断理由',canonicalType:'节点类型',
  epistemicType:'认知属性',graphPlane:'图谱层面',graphDomain:'所属图谱',
  objectSemantics:'对象语义',instanceId:'实例ID',caseId:'案例ID',
  sourceRef:'来源引用',evidenceClass:'证据类别',contentSha256:'内容指纹',
  quote:'材料原文',page:'所在页',role:'业务角色',term:'识别内容',
  caseName:'案例名称',scenarioCode:'场景编码',bankCode:'所属银行',caseStatus:'案例状态',
  riskLevel:'风险等级',eventName:'事件名称',eventType:'事件类型',eventTime:'事件时间',
  accountNo:'账号',holderName:'账户持有人',customerNo:'客户号',
  patternCode:'模式编码',hypothesisCode:'假设编码',certainty:'确定性',
  event_frame_code:'事件类型编码',event_frame_version:'事件类型版本',
  definition_binding_status:'定义绑定状态',identity_resolution_status:'身份识别状态',
  event_quality_score:'事件质量评分',fact_level:'事实层级'
}
const hiddenGraphPropertyKeys=new Set([
  'uid','namespace_uid','evidence_index','customer_index','account_index',
  'id','caseId','nodeType','canonicalType','graphPlane','graphDomain','objectSemantics',
  'instanceId','sourceRef','contentSha256','epistemicType',
  'businessMeaning','instanceQuestion','supportSummary','decisionBoundary'
])
const commonGraphPropertyKeys=['name','title','summary','description']
const graphPropertyKeysByType:Record<string,string[]>={
  CASE:['caseName','scenarioCode','bankCode','caseStatus','riskLevel','risk_level','summary','description'],
  EVENT:['eventName','event_name','eventType','event_type','eventTime','event_time','started_at','ended_at','amount','channel','location','riskLevel','risk_level','status','summary','description'],
  EVIDENCE:['name','summary','evidenceSummary','source','evidenceClass'],
  CUSTOMER:['name','customerNo','customer_no','id_type','nationality','address','occupation_or_industry','riskLevel','risk_level'],
  ACCOUNT:['accountNo','account_no','holderName','holder_name','opened_at','card_no','card_type','riskLevel','risk_level'],
  ORGANIZATION:['name','address','occupation_or_industry'],
  MERCHANT:['name','address'],
  WALLET:['name','address'],
  DEVICE:['name','description'],
  IPADDRESS:['name','address'],
  ADDRESS:['name','address'],
  INDICATORRESULT:['name','title','summary','confidence','certainty','status'],
  BEHAVIORPATTERN:['name','title','patternCode','pattern_code','pattern_name','pattern_confidence','confidence','certainty','status','summary'],
  RISKHYPOTHESIS:['name','title','hypothesisCode','risk_event_type','risk_confidence','riskLevel','risk_level','confidence','certainty','review_status','status','summary','reason'],
  INVESTIGATIONHYPOTHESIS:['name','title','hypothesisCode','hypothesisType','priority','confidence','certainty','review_status','status','summary','reason']
}
const businessEnumText=(value:any)=>{
  const raw=String(value??'').trim()
  if(!raw)return '—'
  return ({
    ACTIVE:'有效',INACTIVE:'未启用',SUPERSEDED:'已被替代',
    OPEN:'待处理',PENDING:'待复核',APPROVED:'已通过',REJECTED:'已驳回',
    ACKNOWLEDGED:'已受理',RESOLVED:'已解决',DISMISSED:'已排除',
    CONFIRMED:'已确认',CANDIDATE:'候选结论',NOT_REQUIRED:'无需复核',
    OBSERVED:'已观测',REPORTED:'材料陈述',EXTRACTED:'文本抽取',
    DERIVED:'推理形成',HYPOTHESIS:'候选解释',DETECTED:'模式识别',
    IDENTIFIED:'账号已识别',PARTIAL_IDENTITY:'账号未披露，待核实',
    HIGH:'高风险',CRITICAL:'严重风险',MEDIUM:'中风险',LOW:'低风险',
    STRONG:'强',MODERATE:'中',WEAK:'弱',
    E1:'一级证据',E2:'二级证据',E3:'三级证据',
    NORMAL_BUSINESS:'正常业务解释',ALTERNATIVE_RISK:'其他风险解释',
    DATA_QUALITY:'数据质量问题',CONTRADICTION:'矛盾证据',
    CASE:'案例',EVENT:'事件',TRANSACTION:'交易',EVIDENCE:'证据',
    CUSTOMER:'客户',ACCOUNT:'账户',ORGANIZATION:'机构',MERCHANT:'商户',
    WALLET:'虚拟资产钱包',DEVICE:'设备',IP_ADDRESS:'网络地址',IPADDRESS:'网络地址',
    ADDRESS:'地址',BEHAVIOR_PATTERN:'行为模式',BEHAVIORPATTERN:'行为模式',
    RISK_HYPOTHESIS:'风险假设',RISKHYPOTHESIS:'风险假设',
    INVESTIGATION_HYPOTHESIS:'调查假设',INVESTIGATIONHYPOTHESIS:'调查假设',
    INDICATOR_RESULT:'指标结果',INDICATORRESULT:'指标结果',
    TECHNIQUE:'AMLTRIX 技术',TACTIC:'AMLTRIX 战术',
    TEXT:'文本',TEXT_SPAN:'文本片段',DOCUMENT:'文档',
    BANK_ACCOUNT:'银行账户',BANK_CARD:'银行卡',CRYPTO_WALLET:'虚拟资产地址',
    FACT:'事实关系',INFERENCE:'推理关系',
    RULE:'规则计算',MODEL:'模型推理',HUMAN_REVIEW:'人工复核',
    WORKER_EXTRACTION:'材料抽取',CASE_ANALYSIS_SCOPE:'案例分析范围',
    SOURCE_REF:'来源记录',EVENT_TIME_ORDER:'事件时间顺序'
  } as Record<string,string>)[raw]||raw
}
const localizedNarrative=(value:any)=>String(value??'—')
  .split('TECHNIQUE_HAS_INDICATOR').join('“技术具有指标”')
const displayGraphValue=(value:any)=>{
  if(Array.isArray(value))return value.length?value.join('、'):'—'
  if(value&&typeof value==='object')return JSON.stringify(value)
  return businessEnumText(value)
}
const rowDetailVisible=ref(false)
const rowDetailLayer=ref('')
const rowDetailRecord=ref<Record<string,any>>({})
const rowDetailLabels:Record<string,string>={
  internalId:'内部记录ID',businessId:'业务ID',uid:'节点标识',
  relationId:'关系ID',sourceId:'源节点ID',source:'源节点名称',sourceType:'源节点类型',
  targetId:'目标节点ID',target:'目标节点名称',targetType:'目标节点类型',
  associationType:'证据关联类型',associationId:'证据关联ID',
  rule_name:'识别规则',ruleName:'识别规则',
  product_service:'产品服务',productService:'产品服务',
  value_instrument:'价值工具',valueInstrument:'价值工具',
  risk_type:'风险类型',riskType:'风险类型',
  risk_indicator:'风险指标',riskIndicator:'风险指标',
  disposal_measure:'处置措施',disposalMeasure:'处置措施',
  raw_text:'原文/原始数据',rawText:'原文/原始数据',
  original_data:'原始数据',originalData:'原始数据',
  started_at:'开始日期',startedAt:'开始日期',
  ended_at:'结束日期',endedAt:'结束日期'
}
const formatRowDetailValue=(value:any)=>{
  if(value===null||value===undefined||value==='')return '—'
  if(typeof value==='object'){
    try{return JSON.stringify(value,null,2)}catch{return String(value)}
  }
  return displayGraphValue(value)
}
const rowDetailFields=computed(()=>Object.entries(rowDetailRecord.value)
  .filter(([key,value])=>(rowDetailLabels[key]||graphPropertyLabels[key])
    &&value!==undefined&&value!==null&&value!=='')
  .map(([key,value])=>({
    key,
    label:rowDetailLabels[key]||graphPropertyLabels[key],
    value:formatRowDetailValue(value)
  })))
const openLayerRowDetail=(layer:string,row:any)=>{
  rowDetailLayer.value=layer
  rowDetailRecord.value={...row}
  rowDetailVisible.value=true
}
const openEventRowDetail=(row:any)=>openLayerRowDetail('事件',row)
const openRelationRowDetail=(row:any)=>openLayerRowDetail('关系',row)
const openEvidenceRowDetail=(row:any)=>openLayerRowDetail('证据',row)
const selectedGraphProperties=computed(()=>{
  const type=nodeSnapshotType(selectedGraphNode.value)
  const sourceProperties=selectedGraphNode.value?.properties||selectedGraphNode.value||{}
  const properties=type==='CASE'
    ?{
      ...sourceProperties,
      caseName:graphNodeTitle(selectedGraphNode.value),
      scenarioCode:caseDetail.value.scenarioCode,
      caseStatus:caseDetail.value.caseStatus,
      riskLevel:caseDetail.value.riskLevel
    }
    :sourceProperties
  const preferred=graphPropertyKeysByType[type]||commonGraphPropertyKeys
  const orderedKeys=[
    'businessId',
    ...preferred,
    ...Object.keys(properties).filter(key=>!preferred.includes(key))
  ]
  const seen=new Set<string>()
  return orderedKeys
    .filter(key=>{
      const value=properties[key]
      if(hiddenGraphPropertyKeys.has(key)||!graphPropertyLabels[key]
        ||value===''||value===null||value===undefined)return false
      const signature=`${graphPropertyLabels[key]}::${displayGraphValue(value)}`
      if(seen.has(signature))return false
      seen.add(signature)
      return true
    })
    .map(key=>({
      key,
      label:graphPropertyLabels[key],
      value:displayGraphValue(properties[key])
    }))
})
const selectedGraphExplanation=computed(()=>{
  const properties=selectedGraphNode.value?.properties||{}
  if(nodeSnapshotType(selectedGraphNode.value)==='EVIDENCE'){
    return String(properties.proves||'')
  }
  return String(properties.businessMeaning||'')
})
const selectedReasoningSemantics=computed(()=>{
  const type=nodeSnapshotType(selectedGraphNode.value)
  if(!['INDICATORRESULT','BEHAVIORPATTERN','RISKHYPOTHESIS','INVESTIGATIONHYPOTHESIS'].includes(type))return null
  const properties=selectedGraphNode.value?.properties||{}
  const defaults:any={
    INDICATORRESULT:['指标计算得出了什么结果？','该实例是针对本案例数据执行指标后得到的数值或命中结果。'],
    BEHAVIORPATTERN:['这些已发生事件共同呈现了什么组合行为？','该实例描述事件之间形成的行为组合，不直接作风险定性。'],
    RISKHYPOTHESIS:['上述行为组合可能意味着什么风险？','该实例是待复核的风险解释，不是已确认违法事实。'],
    INVESTIGATIONHYPOTHESIS:['下一步需要核验什么？','该实例是由风险假设转化而来的可执行调查问题。']
  }
  const fallback=defaults[type]
  return {
    question:String(properties.instanceQuestion||fallback[0]),
    meaning:String(properties.businessMeaning||properties.summary||properties.hypothesis||fallback[1]),
    support:String(properties.supportSummary||'请查看该节点的入边及来源引用。'),
    boundary:String(properties.decisionBoundary||'该节点属于案例推理实例，需结合证据和人工复核使用。')
  }
})
const highlightedEventIds=computed<Set<string>>(()=>new Set<string>())
const certaintyText=(value:string)=>businessEnumText(value)
const certaintyType=(value:string)=>value==='OBSERVED'||value==='CONFIRMED'?'success':value==='REPORTED'||value==='EXTRACTED'?'warning':'info'
const confidenceText=(value:any)=>value==null||value===''?'—':`${Math.round(Number(value)*100)}%`
const confidenceCalculationText=(row:any,scoreName:string)=>{
  const scores=Array.isArray(row?.confidenceBreakdown?.scores)?row.confidenceBreakdown.scores:[]
  const score=scores.find((item:any)=>String(item?.name||'')===scoreName)
  if(!score)return '暂无评分分解'
  const factors=(Array.isArray(score.factors)?score.factors:[]).map((factor:any)=>
    `${factor.name} ${confidenceText(factor.value)}×${Math.round(Number(factor.weight||0)*100)}%`)
  return `${scoreName} ${confidenceText(score.score)}：${factors.join('＋')||'暂无评分因子'}`
}
const accountInstanceDisplayName=(account:any)=>{
  const holder=String(account?.holder_name||account?.holderName||'持有人待确认')
  const accountNo=String(account?.account_no||account?.accountNo||account?.account_hash||'')
  if(!accountNo)return `待核账户（${holder}，账号未披露）`
  const visible=accountNo.replace(/[^A-Za-z0-9]/g,'')
  const suffix=visible.slice(-4)||'号待核'
  return `账户${suffix}（${holder}）`
}
const graphPlaneTypeKeys=(plane:string)=>snapshotTypes.filter(item=>item.plane===plane).map(item=>item.key)
const isGraphPlaneEnabled=(plane:string)=>graphPlaneTypeKeys(plane).every(key=>visibleSnapshotTypes.value[key])
const isGraphPlanePartiallyEnabled=(plane:string)=>{
  const keys=graphPlaneTypeKeys(plane)
  const enabled=keys.filter(key=>visibleSnapshotTypes.value[key]).length
  return enabled>0&&enabled<keys.length
}
function toggleGraphPlane(plane:string){
  const keys=graphPlaneTypeKeys(plane)
  const enabled=!keys.every(key=>visibleSnapshotTypes.value[key])
  keys.forEach(key=>{visibleSnapshotTypes.value[key]=enabled})
  if(selectedGraphNode.value&&!visibleSnapshotTypes.value[nodeSnapshotType(selectedGraphNode.value)])selectedGraphNode.value=null
  selectedGraphEdge.value=null
}
function toggleSnapshotType(type:string){
  visibleSnapshotTypes.value[type]=!visibleSnapshotTypes.value[type]
  if(selectedGraphNode.value&&nodeSnapshotType(selectedGraphNode.value)===type&&!visibleSnapshotTypes.value[type])selectedGraphNode.value=null
  selectedGraphEdge.value=null
}
const editValue=(value:any)=>value===undefined||value===null?'':String(value)
function startCaseOverviewEdit(){
  const reported=editValue(caseDetail.value.reportedAt)
  caseOverviewForm.value={
    description:editValue(caseDetail.value.description),
    businessDomain:editValue(caseDetail.value.businessDomain||'01-反洗钱'),
    businessCaseType:editValue(caseDetail.value.businessCaseType),
    reportingDirection:editValue(caseDetail.value.reportingDirection),
    triggerPoint:editValue(caseDetail.value.triggerPoint),
    urgencyLevel:editValue(caseDetail.value.urgencyLevel),
    reportedAt:reported?`${reported.slice(0,10)}T00:00:00`:'',
    businessCaseStatus:editValue(caseDetail.value.businessCaseStatus),
    businessRiskLevel:editValue(caseDetail.value.businessRiskLevel),
    suspectedCrimeType:editValue(caseDetail.value.suspectedCrimeType),
    suspiciousTransactionFeatureCode:editValue(caseDetail.value.suspiciousTransactionFeatureCode),
    disposalMeasure:editValue(caseDetail.value.disposalMeasure)
  }
  caseOverviewEditing.value=true
}
function cancelCaseOverviewEdit(){
  caseOverviewEditing.value=false
  caseOverviewForm.value={}
}
async function saveCaseOverview(){
  caseOverviewSaving.value=true
  try{
    const payload=Object.fromEntries(Object.entries(caseOverviewForm.value)
      .map(([key,value])=>[key,String(value||'').trim()||null]))
    const response:any=await updateCaseOverviewApi(
      String(route.params.caseId||route.params.id||''),payload)
    caseDetail.value={...caseDetail.value,...(response.data||{})}
    caseOverviewEditing.value=false
    caseOverviewForm.value={}
    ElMessage.success('案例基本信息已保存')
  }catch(error:any){
    ElMessage.error(error?.message||'案例基本信息保存失败')
  }finally{
    caseOverviewSaving.value=false
  }
}
async function load() {
  const id = String(route.params.caseId || route.params.id || ''); if (!id) return
  try {
    analysisLoadErrors.value={}
    const optional = (key:string,request: Promise<any>, fallback: any) => request.catch((error:any) => {
      analysisLoadErrors.value={...analysisLoadErrors.value,[key]:error?.message||'加载失败'}
      return { data: fallback }
    })
    const [c,e,s,w,g,wr,m,t,rs,rr,cc,kc]: any[] = await Promise.all([
      getCaseApi(id),
      optional('events',getCaseEventsApi(id), []),
      optional('signals',getCaseSignalsApi(id), []),
      optional('workflow',getCaseWorkflowApi(id), []),
      optional('graph',getCaseGraphApi(id), { nodes: [], edges: [] }),
      optional('worker',getCaseWorkerResultApi(id), {}),
      optional('matters',getCaseMattersApi(id), []),
      optional('techniques',getCaseTechniquesApi(id), []),
      optional('suggestions',getCaseReviewSuggestionsApi(id), []),
      optional('reasoning',getCaseReasoningApi(id), {}),
      optional('coreChain',getCaseCoreChainApi(id), {}),
      optional('knowledge',getCaseKnowledgeExplanationChainsApi(id), {nodes:[],edges:[],chains:[],multiStageMatter:null})
    ])
    caseDetail.value = c.data || {}; events.value = e.data || []; signals.value = s.data || []
    selectedTaskCaseId.value=String(caseDetail.value.caseId||id)
    taskCases.value=caseDetail.value.caseId?[caseDetail.value]:[]
    history.value = (w.data || []).map((x:any) => ({ time: formatDateTime(x.completedAt || x.startedAt), title: x.stepName || x.workflowStatus, description: x.opinion || x.result || x.status }))
    const payload = wr.data || {}; workerJobId.value = payload.jobId || ''; workerResultSource.value=payload.source||payload.jobType||''; workerResult.value = typeof payload.workerResult === 'string' ? JSON.parse(payload.workerResult) : payload.workerResult; rawWorkerResult.value=typeof payload.raw==='string' ? JSON.parse(payload.raw) : (payload.raw||payload.workerResult)
    // 图谱快照只读取当前核心链；TuGraph 全量案例子图仅在核心链尚未生成时作为边界过滤后的降级数据源。
    coreChain.value=cc.data||{}
    knowledgeExplanation.value=kc.data||{nodes:[],edges:[],chains:[],multiStageMatter:null}
    const presentationNodes=Array.isArray(knowledgeExplanation.value?.nodes)
      ?knowledgeExplanation.value.nodes:[]
    const workerSnapshot=structuredCaseResult.value?.graphSnapshot||{}
    const baseNodes=Array.isArray(g.data?.nodes)&&g.data.nodes.length
      ?g.data.nodes:Array.isArray(workerSnapshot.nodes)?workerSnapshot.nodes:[]
    rawGraphNodes.value=baseNodes
    const chainNodes=Array.isArray(coreChain.value?.nodes)?coreChain.value.nodes:[]
    const filteredChainNodes=chainNodes.filter((node:any)=>coreSnapshotTypes.has(nodeSnapshotType(node)))
    const sourceNodes=presentationNodes.length
      ?presentationNodes
      :filteredChainNodes.length
        ?filteredChainNodes
        :baseNodes.filter((node:any)=>coreSnapshotTypes.has(nodeSnapshotType(node)))
    const nodeMap=new Map<string,any>()
    sourceNodes.forEach((node:any)=>{
      const nodeType=nodeSnapshotType(node)
      const properties={...(node.properties||{})}
      if(nodeType==='CASE'&&!properties.businessId)properties.businessId=displayCaseSequenceId.value
      if(nodeType==='EVENT'){
        const dbEvent=events.value.find((item:any)=>
          String(item.eventStandardCode||'')===String(node.uid||node.id||properties.instanceId||''))
        if(dbEvent?.businessId&&!properties.businessId)properties.businessId=dbEvent.businessId
        const resolvedName=meaningfulEventName({...properties,...node,...(dbEvent||{})})
        if(!node.name||String(node.name).toUpperCase()==='UNNAMED_WORKER_EVENT')node={...node,name:resolvedName}
        if(!properties.eventName||String(properties.eventName).toUpperCase()==='UNNAMED_WORKER_EVENT')properties.eventName=resolvedName
      }
      nodeMap.set(String(node.uid||node.id),{...node,properties})
    })
    graphNodes.value=[...nodeMap.values()]
    const presentationEdges=Array.isArray(knowledgeExplanation.value?.edges)
      ?knowledgeExplanation.value.edges:[]
    const baseEdges=Array.isArray(g.data?.edges)&&g.data.edges.length
      ?g.data.edges:Array.isArray(workerSnapshot.edges)?workerSnapshot.edges:[]
    const chainEdges=Array.isArray(coreChain.value?.edges)?coreChain.value.edges:[]
    const sourceEdges=presentationNodes.length
      ?presentationEdges
      :filteredChainNodes.length?chainEdges:baseEdges
    const nodeIds=new Set(graphNodes.value.map((node:any)=>String(node.uid||node.id)))
    const edgeMap=new Map<string,any>()
    sourceEdges.forEach((edge:any)=>{
      const source=String(edge.source||edge.from||edge.start)
      const target=String(edge.target||edge.to||edge.end)
      if(!nodeIds.has(source)||!nodeIds.has(target))return
      const key=String(edge.uid||edge.id||`${source}->${target}:${edge.type||edge.label}`)
      edgeMap.set(key,edge)
    })
    graphEdges.value=[...edgeMap.values()]
    matters.value=m.data||[];techniques.value=t.data||[];reviewSuggestions.value=rs.data||[];reasoning.value=rr.data||{}
    const siblingJobId=workerJobId.value||String(route.query.jobId||'')
    if(siblingJobId){
      try{
        const siblings:any=await getCasesApi({pageNum:1,pageSize:200,jobId:siblingJobId})
        taskCases.value=siblings.data?.records||taskCases.value
      }catch(error:any){
        analysisLoadErrors.value={...analysisLoadErrors.value,taskCases:error?.message||'同批案例加载失败'}
      }
    }
  } catch (err:any) { ElMessage.error(err.message || '案例详情加载失败') }
}
async function scrollToProduct(id:string){
  activeTab.value='analysis'
  await nextTick()
  document.getElementById(id)?.scrollIntoView({behavior:'smooth',block:'start'})
}
async function openOverviewLayer(layer:string){
  activeTab.value='overview'
  if(!activeOverviewLayers.value.includes(layer))activeOverviewLayers.value.push(layer)
  await nextTick()
  document.getElementById(`overview-${layer}`)?.scrollIntoView({behavior:'smooth',block:'start'})
}
async function submitForReview(){
  const id=String(route.params.caseId||route.params.id||'')
  submitting.value=true
  try{
    await submitCaseApi(id)
    ElMessage.success('已提交复核')
    await load()
  }catch(error:any){
    ElMessage.error(error?.message||'提交复核失败')
  }finally{submitting.value=false}
}
const switchTaskCase=async(caseId:string)=>{
  if(!caseId||caseId===String(route.params.caseId||''))return
  await router.push({path:`/case/detail/${encodeURIComponent(caseId)}`,query:{...route.query,jobId:workerJobId.value||route.query.jobId,tab:activeTab.value}})
}
const startReportEdit=()=>{reportDraft.value=suspiciousReportText.value;reportEditing.value=true}
const cancelReportEdit=()=>{reportDraft.value=suspiciousReportText.value;reportEditing.value=false}
const saveReport=async()=>{
  const text=reportDraft.value.trim()
  if(!text)return ElMessage.warning('可疑报告不能为空')
  const caseId=String(route.params.caseId||'')
  if(!workerJobId.value||!caseId)return ElMessage.error('未找到该案例对应的识别任务')
  reportSaving.value=true
  try{
    await updateStructuredCaseAnalysisTextApi(workerJobId.value,caseId,text)
    const current=structuredCaseResult.value
    if(current){
      current.suspiciousReport={...(current.suspiciousReport||{}),analysisText:text,analysisTexts:{analysis_text:text},source:'USER_EDITED_ANALYSIS_TEXT'}
      current.analysisReport={...(current.analysisReport||{}),analysisText:text,analysisTexts:{analysis_text:text},source:'USER_EDITED_ANALYSIS_TEXT'}
    }
    reportEditing.value=false
    ElMessage.success('可疑报告已保存')
  }catch(error:any){ElMessage.error(error?.message||'可疑报告保存失败')}
  finally{reportSaving.value=false}
}
async function acknowledgeSuggestion(row:any){
  try{await updateCaseReviewSuggestionApi(String(route.params.caseId||route.params.id||''),row.suggestionId,{status:'ACKNOWLEDGED',resolution:'当前无法补充，已知悉该建议'});ElMessage.success('已记录，不影响当前案例解释');await load()}catch(e:any){ElMessage.error(e.message||'更新失败')}
}
async function acknowledgeHypothesis(row:any){
  try{await updateInvestigationHypothesisApi(String(route.params.caseId||route.params.id||''),row.hypothesisId,{status:'ACKNOWLEDGED',resolution:'已受理，等待可用材料'});ElMessage.success('调查假设已受理');await load()}catch(e:any){ElMessage.error(e.message||'更新失败')}
}
const headerActionsReady=ref(false)
onMounted(()=>{
  headerActionsReady.value=true
  load()
})
const nextAnimationFrame=()=>new Promise<void>(resolve=>requestAnimationFrame(()=>resolve()))
watch(activeTab, async (tab)=>{
  if(String(route.query.tab||'')!==tab){
    await router.replace({query:{...route.query,tab}})
  }
  if(tab!=='graph')return
  await nextTick()
  // Element Plus 的 tab 内容在过渡结束前仍可能是 0 宽；连续两帧后再按真实尺寸适配。
  await nextAnimationFrame()
  await nextAnimationFrame()
  await graphCanvas.value?.fitView()
})
watch(()=>route.query.tab,(value)=>{activeTab.value=normalizeDetailTab(value)})
watch(()=>route.params.caseId,async(value,previous)=>{
  if(!previous||value===previous)return
  reportEditing.value=false
  activeOverviewLayers.value=['case','entity','event','relation','evidence']
  await load()
})
const getRiskText = (v:string) => ({ LOW:'低风险', MEDIUM:'中风险', HIGH:'高风险', CRITICAL:'严重风险' } as any)[v] || v || '—'
const getRiskTagType = (v:string) => ({ LOW:'info', MEDIUM:'warning', HIGH:'danger', CRITICAL:'danger' } as any)[v] || 'info'
const getStatusText = (v:string) => ({ DRAFT:'待提交复核', IN_REVIEW:'复核中', PENDING_APPROVAL:'待审批', APPROVED:'已通过', REJECTED:'已驳回', CLOSED:'已结案' } as any)[v] || v || '—'
const getStatusTagType = (v:string) => ({ APPROVED:'success', HIGH:'danger', REJECTED:'danger', IN_REVIEW:'warning' } as any)[v] || 'info'
</script>

<style scoped>
.case-action-bar{position:sticky;top:0;z-index:5;display:flex;align-items:center;justify-content:space-between;gap:14px;padding:9px 12px;border-bottom:1px solid #dbe3ee;background:rgba(255,255,255,.96);backdrop-filter:blur(8px)}.case-action-bar>div{display:flex;align-items:center;gap:9px}.case-action-bar span{color:#64748b;font-size:12px}
.case-header-buttons{display:flex;align-items:center;gap:8px}.case-selector{width:380px}.report-card,.similarity-card{border-color:#d8e4f1;border-radius:12px}.report-heading,.report-heading>div{display:flex;align-items:center;justify-content:space-between;gap:10px}.report-heading{width:100%}.report-heading b{color:#17314f;font-size:17px}.report-heading span{color:#64748b}.report-content{min-height:420px;color:#334155;font-size:13px;line-height:1.82;white-space:pre-wrap;overflow-wrap:anywhere}.report-editor :deep(textarea){font-size:13px;line-height:1.75}.similarity-table{margin-top:14px}@media(max-width:1000px){.case-selector{width:260px}}
.fact-header-actions{display:flex;align-items:center;gap:10px}.fact-basis-links{display:flex;align-items:center;white-space:nowrap}.fact-basis-links .el-button{height:auto;padding:0;font-size:12px}.fact-basis-links>span{color:#94a3b8}.evidence-quote{line-height:1.65;white-space:pre-wrap;overflow-wrap:anywhere}.pattern-code{display:block;margin-top:3px;color:#64748b;font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:10px;font-weight:400}
.pattern-formal-text :deep(.cell){white-space:normal;overflow:visible;text-overflow:clip;overflow-wrap:anywhere;word-break:break-word;line-height:1.6}
.analysis-load-alert{margin-bottom:12px}
.reasoning-routes{display:grid;gap:14px}
.reasoning-route{padding:12px;border:1px solid #dbe3ee;border-radius:10px;background:#fff}
.reasoning-route>strong{display:block;margin-bottom:10px;color:#334155;font-size:13px}
.reasoning-route .reasoning-chain{padding-bottom:0}
.reasoning-route .reasoning-stage{min-width:130px;min-height:84px;justify-content:center}
.reasoning-stage.stage-evidence{border-color:#cbd5e1;background:#f8fafc}
.reasoning-stage.stage-evidence span{color:#475569}
.reasoning-stage.stage-tactic{border-color:#fecdd3;background:#fff1f2}
.reasoning-stage.stage-tactic span{color:#be123c}
.supplement-arrow{min-width:38px;color:#7c3aed;font-size:11px}
.knowledge-chain-table .col-fact{width:16%}
.knowledge-chain-table .col-indicator{width:24%}
.knowledge-chain-table .col-technique{width:10%}
.knowledge-chain-table .col-tactic{width:9%}
.knowledge-chain-table .col-pattern{width:16%}
.knowledge-chain-table .col-risk{width:17%}
.knowledge-chain-table .col-relation{width:8%}
.knowledge-chain-table .knowledge-chain-groups th{text-align:center;background:#eaf2ff;font-weight:700}
.knowledge-chain-table .knowledge-chain-groups th:last-child{background:#ecfeff}
.knowledge-chain-table td small{display:block;margin-top:5px;color:#64748b;line-height:1.45}
.knowledge-chain-table td.fact .el-button{height:auto;padding:0;white-space:normal;text-align:left;line-height:1.55}
.knowledge-chain-table td.relation{background:#f8fafc;color:#475569;text-align:center}
.case-header-buttons{display:flex;align-items:center;gap:8px}.case-header-button{min-width:88px}
.product-overview{margin-bottom:14px;border-top:3px solid #0f766e}.product-header,.product-header>div{display:flex;align-items:center;gap:12px}.product-header{justify-content:space-between}.product-header span{color:#64748b;font-size:13px}.product-grid{display:grid;grid-template-columns:repeat(6,minmax(120px,1fr));gap:10px}.product-grid button{border:1px solid #dbe3ee;border-radius:9px;background:#f8fafc;padding:12px;text-align:left;cursor:pointer;color:#334155;display:flex;flex-direction:column;gap:5px}.product-grid button:hover{border-color:#0f766e;background:#f0fdfa}.product-grid button span,.product-grid button small{color:#64748b}.product-grid button b{font-size:24px;color:#0f766e}.product-lineage{display:flex;flex-wrap:wrap;gap:8px 18px;margin-top:12px;padding-top:12px;border-top:1px solid #eef2f7;color:#64748b;font-size:12px}.product-section{scroll-margin-top:12px;margin-top:14px}.product-section-title{display:flex;justify-content:space-between;align-items:center;margin-bottom:8px;color:#64748b}.product-section-title b{color:#334155}.matter-grid,.technique-card{scroll-margin-top:12px}@media(max-width:1100px){.product-grid{grid-template-columns:repeat(3,1fr)}}@media(max-width:650px){.product-grid{grid-template-columns:repeat(2,1fr)}.product-header{align-items:flex-start}.product-header>div{align-items:flex-start;flex-direction:column}}
.case-detail{height:calc(100% + 12px);margin-top:-12px;overflow:auto}.summary{overflow:hidden}.product-case-summary{margin-bottom:14px;border-top:3px solid #2563eb}.case-summary-title{display:flex;align-items:center;gap:12px}.case-summary-title span{color:#64748b;font-size:12px}.case-heading{width:100%;margin:0 auto;border-collapse:collapse;table-layout:fixed;color:#334155}.case-heading th,.case-heading td{width:10%;border:1px solid #e5e7eb;padding:8px 5px;text-align:center;vertical-align:middle;line-height:1.35;overflow:hidden}.case-heading th{background:#f8fafc;color:#64748b;font-size:14px;font-weight:500}.truncate-value,.detail-ellipsis{display:block;width:100%;min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.detail-ellipsis{max-width:260px}.id-value{font-family:ui-monospace,SFMono-Regular,Menlo,monospace;font-size:12px}.case-id{font-size:12px;color:#475569;font-family:ui-monospace,SFMono-Regular,Menlo,monospace}.case-name{font-size:14px;font-weight:600;color:#1e293b}.metric{font-size:18px;font-weight:600;color:#303133}.case-model-table-wrap{width:100%;overflow:hidden}.case-model-table,.raw-text-table{width:100%;border-collapse:collapse;table-layout:fixed;color:#334155}.case-model-table th,.case-model-table td,.raw-text-table th,.raw-text-table td{border:1px solid #dcdfe6;padding:10px 8px;vertical-align:middle}.case-model-table th{background:#f5f7fa;color:#606266;font-weight:600;text-align:center;white-space:nowrap}.case-model-table td{text-align:center;line-height:1.45;overflow:hidden}.model-value{display:block;width:100%;min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.raw-text-table{margin-top:-1px}.raw-text-table th{width:96px;background:#f5f7fa;color:#606266;font-weight:600;text-align:center;white-space:nowrap}.raw-text-table td{white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-word;line-height:1.75;text-align:left}.model-table{width:100%}.model-table :deep(.cell){overflow:hidden;white-space:nowrap;text-overflow:ellipsis}.tabs{margin-top:0}.tabs :deep(.el-tabs__header){margin:0 0 8px}.tabs :deep(.el-tabs__nav-wrap){padding:0 10px}.tabs :deep(.el-tabs__nav-scroll){display:flex}.tabs :deep(.el-tabs__nav){display:flex;justify-content:space-between;min-width:100%}.tabs :deep(.el-tabs__item){height:34px;flex:1;min-width:max-content;padding:0 10px;line-height:34px;text-align:center}.tabs :deep(#tab-overview){order:1}.tabs :deep(#tab-graph){order:2}.tabs :deep(#tab-analysis){order:3}.tabs :deep(#tab-history){order:4}.description{padding:16px 0;white-space:pre-wrap}.narrative{font-size:15px;line-height:1.9;color:#334155}.description-source{color:#64748b;font-size:12px}.provenance-card{margin-top:14px}.consistency-alerts{display:grid;gap:8px}.provenance-list{margin-top:12px}.json{margin-top:18px;font-family:monospace}.graph-title{font-weight:600;margin-bottom:12px}.snapshot-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;margin:0 0 10px}.snapshot-legend{display:flex;align-items:center;flex-wrap:wrap;gap:8px 16px}.legend-item{display:inline-flex;align-items:center;gap:6px;white-space:nowrap;color:#606266;font-size:13px;border:0;background:transparent;padding:3px 5px;cursor:pointer;border-radius:5px}.legend-item:hover{background:#f1f5f9}.legend-item.muted{opacity:.35}.legend-item i{display:inline-flex;align-items:center;justify-content:center;width:24px;height:24px;border-radius:50%;color:#fff;font-size:12px;font-style:normal;font-weight:600}.graph-canvas{height:650px;border:1px solid #ebeef5;margin-bottom:16px;background:#fafcff}.info-card{margin-top:12px}.matter-layout{display:grid;gap:14px}.matter-lead{border-left:4px solid #2563eb}.lead-label{font-size:12px;color:#64748b}.lead-summary{font-size:22px;font-weight:600;line-height:1.5;margin:8px 0;color:#1e293b}.lead-meta{display:flex;align-items:center;gap:12px;color:#64748b}.reasoning-chain{display:flex;align-items:stretch;overflow-x:auto;padding-bottom:12px}.reasoning-stage{min-width:170px;flex:1;border:1px solid #dbeafe;border-radius:9px;background:#f8fbff;padding:12px;display:flex;flex-direction:column;gap:6px}.reasoning-stage span{font-size:12px;color:#2563eb}.reasoning-stage b{font-size:22px}.reasoning-stage small{color:#64748b;line-height:1.4}.flow-header{display:flex;justify-content:space-between;gap:16px}.flow-header span{font-size:12px;color:#64748b}.business-flow-card :deep(.el-card__body){overflow-x:auto}.business-flow-track{display:flex;align-items:stretch;min-width:max-content;padding:4px}.flow-terminal,.flow-matter{width:210px;min-height:142px;border:1px solid #dbe3ee;border-radius:10px;padding:13px;display:flex;flex-direction:column;text-align:left;color:#334155;background:#fff}.flow-terminal span,.flow-matter>span{font-size:12px;color:#2563eb;font-weight:700}.flow-terminal b,.flow-matter b{margin:8px 0;line-height:1.4}.flow-terminal small,.flow-matter small{line-height:1.5;color:#64748b}.flow-source{background:#eff6ff;border-color:#bfdbfe}.flow-result{background:#ecfdf5;border-color:#a7f3d0}.flow-matter{cursor:pointer}.flow-matter:hover,.flow-matter.selected{border-color:#2563eb;box-shadow:0 0 0 2px rgba(37,99,235,.1)}.flow-matter em{margin-top:auto;padding-top:9px;color:#2563eb;font-size:12px;font-style:normal}.matter-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:12px}.matter-card{border:1px solid #dbe3ee;background:#fff;border-radius:8px;padding:15px;text-align:left;cursor:pointer;color:#334155}.matter-card:hover,.matter-card.selected{border-color:#2563eb;box-shadow:0 0 0 2px rgba(37,99,235,.1)}.matter-card-head,.selected-header{display:flex;align-items:center;justify-content:space-between;gap:10px}.matter-card p{line-height:1.6;min-height:48px}.matter-value{font-size:13px;line-height:1.5;color:#64748b}.matter-value span{color:#2563eb;margin-right:8px}.matter-actions{display:flex;gap:14px;margin-top:12px;font-size:12px;color:#2563eb}.matter-actions span:first-child{color:#64748b;margin-right:auto}.selected-summary{font-size:17px;line-height:1.7}.selected-actions{margin-top:14px}.technique-card,.review-card{margin-top:2px}.lineage-meta{margin-top:18px}@media(max-width:900px){.tabs :deep(.el-tabs__nav){justify-content:flex-start}.tabs :deep(.el-tabs__item){flex:none}.case-heading{min-width:960px}.product-case-summary :deep(.el-card__body){overflow-x:auto}.case-model-table-wrap{overflow-x:auto}.case-model-table{min-width:760px}.lead-summary{font-size:18px}.flow-header{flex-direction:column}}
.snapshot-toolbar{display:flex;align-items:flex-start;justify-content:space-between;gap:6px;margin-bottom:5px}.snapshot-legend{display:grid;min-width:0;flex:1;gap:2px}.legend-group{display:flex;min-width:0;align-items:center;flex-wrap:wrap;gap:2px}.plane-toggle{display:inline-flex;flex:0 0 auto;align-items:center;gap:4px;height:24px;padding:0 8px;border:1px solid #cbd5e1;border-radius:6px;background:#f8fafc;color:#475569;font-size:12px;cursor:pointer}.plane-toggle i{width:7px;height:7px;border-radius:50%;background:#94a3b8}.plane-toggle.active{border-color:#2563eb;background:#eff6ff;color:#1d4ed8}.plane-toggle.active i{background:#2563eb;box-shadow:0 0 0 3px #dbeafe}.plane-toggle.partial{border-color:#60a5fa;background:#f8fbff;color:#2563eb}.plane-toggle.partial i{background:linear-gradient(90deg,#2563eb 50%,#cbd5e1 50%)}.legend-item{display:inline-flex;align-items:center;gap:3px;height:22px;padding:1px 5px 1px 3px;border:1px solid #e2e8f0;border-radius:11px;background:#fff;color:#334155;font-size:11px;line-height:1;cursor:pointer}.legend-item i{display:inline-flex;width:16px;height:16px;align-items:center;justify-content:center;border-radius:50%;color:#fff;font-size:9px;font-style:normal}.legend-item.instance{max-width:420px;border-radius:10px;padding-right:8px;text-align:left}.legend-item.instance span{white-space:normal;line-height:1.35}.legend-item.muted{opacity:.38;filter:saturate(.45)}.legend-item em{min-width:15px;padding:1px 4px;border-radius:8px;background:#f1f5f9;color:#475569;font-size:10px;font-style:normal;text-align:center}.graph-canvas{border-color:#dbe3ee;border-radius:8px;background:linear-gradient(180deg,#fbfdff 0%,#f8fafc 100%)}.node-detail-title{display:flex;align-items:center;gap:9px;margin-bottom:12px}.node-detail-title i{display:inline-flex;align-items:center;justify-content:center;width:28px;height:28px;border-radius:50%;color:#fff;font-size:12px;font-style:normal;font-weight:700}.node-detail-title div{display:flex;flex-direction:column;gap:2px}.node-detail-title small{color:#64748b}.reasoning-stage.stage-base{border-color:#bfdbfe;background:#eff6ff}.reasoning-stage.stage-base span{color:#2563eb}.reasoning-stage.stage-pattern{border-color:#a5f3fc;background:#ecfeff}.reasoning-stage.stage-pattern span{color:#0891b2}.reasoning-stage.stage-risk{border-color:#fed7aa;background:#fff7ed}.reasoning-stage.stage-risk span{color:#ea580c}.reasoning-stage.stage-technique{border-color:#e9d5ff;background:#faf5ff}.reasoning-stage.stage-technique span{color:#9333ea}.reasoning-stage.stage-matter{border-color:#99f6e4;background:#f0fdfa}.reasoning-stage.stage-matter span{color:#0f766e}@media(max-width:900px){.snapshot-toolbar{align-items:stretch;flex-direction:column}.legend-group{align-items:flex-start}.plane-toggle{min-width:82px;justify-content:center}}
.framework-layers{margin-top:0}.framework-layers:deep(>.ant-collapse-item){margin-bottom:7px}.framework-layers:deep(>.ant-collapse-item>.ant-collapse-header){min-height:52px;padding:8px 14px!important}.framework-layers:deep(>.ant-collapse-item>.ant-collapse-content>.ant-collapse-content-box){padding:12px 14px}.layer-title>i{width:27px;height:27px}.layer-title b{font-size:14px}.overview-description-stack{gap:8px}.entity-block+.entity-block{margin-top:14px;padding-top:12px}.record-list:deep(.ant-collapse-item){margin-bottom:6px}.record-list:deep(.ant-collapse-header){min-height:46px!important;padding-top:7px!important;padding-bottom:7px!important}
.framework-overview-card{border-top:4px solid #eab308}.framework-heading{display:flex;align-items:flex-start;justify-content:space-between;gap:24px}.framework-kicker{display:block;color:#a16207;font-size:12px;font-weight:700;letter-spacing:.08em}.framework-heading h3{margin:5px 0 7px;color:#1e293b;font-size:22px}.framework-heading p{margin:0;color:#64748b;line-height:1.6}.framework-coverage{width:170px;flex:none;padding:9px 12px;border-radius:9px;background:#fffbeb}.framework-coverage b{display:block;color:#a16207;font-size:24px}.framework-coverage span{display:block;margin:2px 0 8px;color:#78716c;font-size:12px}.framework-layer-strip{display:grid;grid-template-columns:repeat(5,1fr);gap:8px;margin-top:18px}.framework-layer-strip a{display:grid;grid-template-columns:32px 1fr auto;align-items:center;gap:8px;min-width:0;padding:10px;border:1px solid #e2e8f0;border-radius:9px;background:#f8fafc;color:#334155;text-decoration:none}.framework-layer-strip a:hover{border-color:#eab308;background:#fffbeb}.framework-layer-strip i,.layer-title>i{display:inline-flex;align-items:center;justify-content:center;width:30px;height:30px;border-radius:50%;background:#fef08a;color:#854d0e;font-size:11px;font-style:normal;font-weight:700}.framework-layer-strip span{overflow:hidden;white-space:nowrap;text-overflow:ellipsis;font-size:12px}.framework-layer-strip b{color:#0f172a;font-size:16px}.framework-layers{margin-top:14px;border:0}.framework-layers :deep(.el-collapse-item){margin-bottom:10px;border:1px solid #dbe3ee;border-radius:10px;background:#fff;overflow:hidden}.framework-layers :deep(.el-collapse-item__header){height:auto;min-height:68px;padding:0 18px;border:0;background:#f8fafc}.framework-layers :deep(.el-collapse-item__wrap){border:0}.framework-layers :deep(.el-collapse-item__content){padding:18px}.layer-title{display:flex;align-items:center;width:100%;gap:11px;scroll-margin-top:12px}.layer-title>div{display:flex;flex-direction:column;align-items:flex-start;gap:3px}.layer-title b{color:#1e293b;font-size:15px}.layer-title span{color:#64748b;font-size:12px;font-weight:400}.layer-title em{margin-left:auto;padding-right:14px;color:#64748b;font-size:12px;font-style:normal;font-weight:400}.overview-field-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:1px;padding:1px;background:#dbe3ee;border:1px solid #dbe3ee;border-radius:8px;overflow:hidden}.overview-field{min-height:72px;padding:11px 13px;background:#fff}.overview-field.wide{grid-column:span 2}.overview-field span{display:block;margin-bottom:6px;color:#64748b;font-size:12px}.overview-field div{color:#1e293b;line-height:1.55;white-space:pre-wrap;overflow-wrap:anywhere}.overview-field.missing div{color:#94a3b8}.overview-field.missing{background:#fafafa}.overview-field-grid.compact{grid-template-columns:repeat(3,minmax(0,1fr));margin:0 6px 8px}.overview-field-grid.compact .overview-field{min-height:64px}.entity-block+.entity-block{margin-top:22px;padding-top:20px;border-top:1px solid #e2e8f0}.subsection-heading{display:flex;align-items:baseline;gap:9px;margin-bottom:10px}.subsection-heading b{color:#1e293b;font-size:16px}.subsection-heading span{color:#64748b;font-size:12px}.record-list{border:0}.record-list :deep(.el-collapse-item){margin:0 0 8px;border-color:#e2e8f0}.record-list :deep(.el-collapse-item__header){min-height:54px;background:#fff}.record-title{display:flex;align-items:center;gap:10px;width:100%;min-width:0}.record-title>i{display:inline-flex;align-items:center;justify-content:center;width:28px;height:28px;flex:none;border-radius:50%;background:#ecfdf5;color:#047857;font-size:12px;font-style:normal;font-weight:700}.record-title b{max-width:240px;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;color:#334155}.record-title>span{min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;color:#64748b;font-size:12px}.record-title .el-tag{margin-left:auto;margin-right:12px;flex:none}.wide-table-wrap{width:100%;min-width:0;overflow:hidden;border:1px solid #e2e8f0;border-radius:8px}.framework-table{width:100%;min-width:0}.framework-table :deep(th.el-table__cell){background:#f8fafc;color:#475569}.framework-table :deep(.cell){white-space:nowrap}.framework-table :deep(.el-table__empty-block){min-width:100%}.clickable-detail-table :deep(.el-table__row){cursor:pointer}.clickable-detail-table :deep(.el-table__row:hover>td.el-table__cell){background:#eff6ff}.row-detail-table pre{margin:0;color:#334155;font:inherit;line-height:1.6;white-space:pre-wrap;overflow-wrap:anywhere}.framework-layers :deep(.el-empty){padding:18px 0}.provenance-card{border-top:3px solid #94a3b8}
@media(max-width:1100px){.framework-layer-strip{grid-template-columns:repeat(3,1fr)}.overview-field-grid,.overview-field-grid.compact{grid-template-columns:repeat(2,minmax(0,1fr))}}
@media(max-width:700px){.framework-heading{flex-direction:column}.framework-coverage{width:100%;box-sizing:border-box}.framework-layer-strip{grid-template-columns:1fr}.overview-field-grid,.overview-field-grid.compact{grid-template-columns:1fr}.overview-field.wide{grid-column:span 1}.layer-title em{display:none}.framework-layers :deep(.el-collapse-item__content){padding:12px}.record-title .el-tag{display:none}}
.snapshot-workbench{display:grid;grid-template-columns:minmax(0,1fr);gap:12px;align-items:stretch}.snapshot-workbench .graph-canvas{min-width:0;margin:0}.snapshot-inspector{box-sizing:border-box;max-height:280px;overflow:auto;padding:16px 18px;border:1px solid #dbe3ee;border-radius:10px;background:linear-gradient(180deg,#fff 0,#f8fafc 100%)}.snapshot-inspector h4{margin:10px 0;color:#1e293b;line-height:1.45}.node-business-explanation,.edge-business-explanation{margin:10px 0 4px;padding:11px 13px;border-left:3px solid #e9a400;border-radius:7px;background:#fffbeb}.node-business-explanation b,.edge-business-explanation b{color:#92400e;font-size:12px}.node-business-explanation p,.edge-business-explanation p{margin:5px 0 0;color:#5f4b24;font-size:13px;line-height:1.65}.edge-business-explanation{border-left-color:#0ea5e9;background:#eff6ff}.edge-business-explanation b{color:#075985}.edge-business-explanation p{color:#164e63}.edge-business-explanation small{display:block;margin-top:5px;color:#64748b}.edge-detail-title{display:flex;align-items:center;gap:9px;margin-bottom:12px}.edge-detail-title>i{display:inline-flex;width:30px;height:30px;align-items:center;justify-content:center;border-radius:9px;background:#0ea5e9;color:#fff;font-size:12px;font-style:normal;font-weight:700}.edge-detail-title>i.inference{background:#f97316}.edge-detail-title>div{display:flex;flex-direction:column;gap:2px}.edge-detail-title small{color:#64748b}.snapshot-inspector dl{display:grid;grid-template-columns:repeat(auto-fit,minmax(190px,1fr));gap:10px 18px;margin:14px 0 0}.snapshot-inspector dl>div{min-width:0}.snapshot-inspector dt{color:#8493a7;font-size:11px}.snapshot-inspector dd{margin:3px 0 0;overflow:hidden;color:#334155;font-size:12px;line-height:1.5;text-overflow:ellipsis;white-space:nowrap}.inspector-empty-icon{display:flex;width:42px;height:42px;align-items:center;justify-content:center;margin:0 auto 8px;border-radius:12px;background:#e8f2ff;color:#1769e0;font-size:18px;font-weight:700}.snapshot-inspector>p{margin:4px 0;color:#64748b;font-size:13px;line-height:1.6;text-align:center}.snapshot-inspector>h4{margin:5px 0;text-align:center}.inspector-metrics{display:flex;justify-content:center;gap:8px;margin:10px 0 0}.inspector-metrics span{min-width:90px;padding:7px 12px;border-radius:8px;background:#edf4fc;color:#64748b;font-size:12px;text-align:center}.inspector-metrics b{display:inline;margin-right:5px;color:#1e3a5f;font-size:16px}.snapshot-inspector>small{display:block;color:#8493a7;text-align:center}@media(max-width:900px){.snapshot-inspector{max-height:340px}}
.case-explanation-panel{display:grid;gap:12px;margin-top:14px}.case-explanation-panel>section{min-width:0;padding:14px;border:1px solid #dbe3ee;border-radius:10px;background:#fbfdff}.cross-domain-title{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:10px}.cross-domain-title b{color:#1e3a5f}.cross-domain-title span{color:#8493a7;font-size:11px}
.knowledge-explainer{margin:-3px 0 9px;color:#64748b;font-size:12px;line-height:1.55}.knowledge-ref-list article{display:block;padding:10px 12px;border-left:3px solid #7c3aed}.knowledge-ref-heading{display:flex;min-width:0;align-items:center;gap:7px}.knowledge-ref-heading i{flex:none;padding:2px 6px;border-radius:10px;background:#f3e8ff;color:#7c3aed}.knowledge-ref-heading b{min-width:0;flex:1}.knowledge-ref-heading small{flex:none}.knowledge-ref-list article>p{margin:6px 0;color:#334155;font-size:12px;line-height:1.55}.knowledge-ref-meta{display:flex;flex-wrap:wrap;gap:4px 14px}.knowledge-ref-meta span{white-space:normal}
.knowledge-tactics{display:flex;flex-wrap:wrap;gap:6px;margin:7px 0}.knowledge-tactics>span{display:inline-flex;align-items:center;gap:3px;padding:4px 7px;border:1px solid #fed7aa;border-radius:6px;background:#fff7ed;color:#9a3412;font-size:11px}.knowledge-tactics b{color:#c2410c}.knowledge-tactics em{max-width:360px;margin-left:4px;overflow:hidden;color:#7c2d12;font-style:normal;text-overflow:ellipsis;white-space:nowrap}
.multi-stage-matter{margin:8px 0 0;padding:11px 13px;border:1px solid #c4b5fd;border-radius:9px;background:linear-gradient(135deg,#f5f3ff,#faf5ff)}.multi-stage-matter>div:first-child{display:flex;align-items:center;justify-content:space-between;gap:8px}.multi-stage-matter b{color:#5b21b6;font-size:13px}.multi-stage-matter span{color:#7c3aed;font-size:11px}.multi-stage-matter>p{margin:5px 0;color:#475569;font-size:12px;line-height:1.5}.multi-stage-matter>em{color:#6d28d9;font-size:11px;font-style:normal;font-weight:600}.matter-conclusions{display:grid!important;grid-template-columns:1fr!important;gap:6px!important;margin-top:10px;padding-top:9px;border-top:1px solid #ddd6fe}.matter-conclusions h4{margin:0;color:#4c1d95;font-size:12px}.matter-conclusion-source{margin:0!important;padding:6px 8px;border-radius:6px;background:#ede9fe;color:#6d28d9!important;font-size:11px!important}.matter-conclusions article{padding:8px 10px;border-left:3px solid #7c3aed;border-radius:6px;background:rgba(255,255,255,.78)}.matter-conclusions article b{font-size:11px}.matter-conclusions article p{margin:3px 0 0;color:#475569;font-size:11px;line-height:1.5}
.knowledge-chain-table-wrap{overflow-x:hidden;border:1px solid #dbe3ee;border-radius:9px;background:#fff}.knowledge-chain-table{width:100%;border-collapse:collapse;table-layout:fixed}.knowledge-chain-table .col-fact{width:15%}.knowledge-chain-table .col-indicator{width:34%}.knowledge-chain-table .col-pattern{width:24%}.knowledge-chain-table .col-risk{width:9%}.knowledge-chain-table .col-technique{width:9%}.knowledge-chain-table .col-tactic{width:9%}.knowledge-chain-table th{padding:8px 6px;border-right:1px solid #e2e8f0;border-bottom:1px solid #cbd5e1;background:#eff6ff;color:#334155;font-size:11px;text-align:left;white-space:nowrap}.knowledge-chain-table td{position:relative;padding:9px 6px;border-right:1px solid #e2e8f0;border-bottom:1px solid #e2e8f0;color:#334155;font-size:11px;line-height:1.55;vertical-align:top;overflow-wrap:anywhere;word-break:break-word}.knowledge-chain-table th:last-child,.knowledge-chain-table td:last-child{border-right:0}.knowledge-chain-table tbody tr:last-child td{border-bottom:0}.knowledge-chain-table td:not(:last-child)::after{position:absolute;right:-6px;top:50%;z-index:1;width:12px;height:12px;border-radius:50%;background:#fff;color:#94a3b8;content:'›';font-weight:700;line-height:11px;text-align:center;transform:translateY(-50%)}.knowledge-chain-table td.indicator{background:#f0fdf4}.knowledge-chain-table td.pattern{background:#ecfeff}.knowledge-chain-table td.risk{background:#fff7ed}.knowledge-chain-table td.technique{background:#f5f3ff}.knowledge-chain-table td.tactic{background:#fef2f2}.knowledge-chain-limit{margin:0;padding:7px 10px;border-top:1px solid #e2e8f0;background:#f8fafc;color:#64748b;font-size:11px}

/* Ant Design Vue case overview */
.framework-overview-card{position:relative;isolation:isolate;overflow:hidden;border:1px solid rgba(56,189,248,.22)!important;border-top:0;background:linear-gradient(125deg,#061b39 0%,#0b2c58 52%,#123e69 100%);box-shadow:0 18px 42px rgba(9,30,66,.18)}
.framework-overview-card:deep(.ant-card-body){position:relative;padding:28px 30px 24px}
.overview-hero-glow{position:absolute;z-index:-1;inset:-70% -18% auto auto;width:520px;height:420px;border-radius:50%;background:radial-gradient(circle,rgba(34,211,238,.3),rgba(37,99,235,.08) 50%,transparent 70%);pointer-events:none}
.framework-heading{position:relative;align-items:center}.framework-heading-copy{max-width:760px}.framework-kicker{display:flex;align-items:center;gap:8px;color:#67e8f9;font-size:11px;font-weight:700;letter-spacing:.16em}.framework-kicker i{width:7px;height:7px;border-radius:50%;background:#22d3ee;box-shadow:0 0 14px #22d3ee}.framework-heading h3{margin:7px 0 8px;color:#f8fafc;font-size:27px;letter-spacing:.02em}.framework-heading p{max-width:720px;color:#bfd1e5}.framework-heading-tags{display:flex;flex-wrap:wrap;gap:7px;margin-top:15px}.framework-heading-tags :deep(.ant-tag){margin:0;border-color:rgba(255,255,255,.16);background:rgba(255,255,255,.09);color:#e6f6ff;backdrop-filter:blur(8px)}
.framework-coverage{display:flex;width:auto;align-items:center;gap:14px;padding:12px 16px;border:1px solid rgba(103,232,249,.24);background:rgba(4,25,53,.48);backdrop-filter:blur(10px)}.framework-coverage :deep(.ant-progress-text){color:#fff}.framework-coverage strong{font-size:16px}.framework-coverage b{color:#67e8f9;font-size:22px}.framework-coverage span{margin:2px 0 0;color:#a8bed5;white-space:nowrap}
.framework-layer-strip{gap:10px;margin-top:25px}.framework-layer-strip a{position:relative;grid-template-columns:34px minmax(0,1fr) auto;overflow:hidden;padding:13px 12px;border-color:rgba(148,163,184,.2);background:rgba(4,25,53,.44);color:#dbeafe;backdrop-filter:blur(10px);transition:transform .2s ease,border-color .2s ease,background .2s ease}.framework-layer-strip a:before{position:absolute;right:-12px;bottom:-22px;width:70px;height:70px;border-radius:50%;background:var(--layer-glow);content:"";filter:blur(24px);opacity:.45}.framework-layer-strip a:hover{transform:translateY(-3px);border-color:var(--layer-accent);background:rgba(14,53,91,.78)}.framework-layer-strip a[data-tone="blue"]{--layer-accent:#60a5fa;--layer-glow:#2563eb}.framework-layer-strip a[data-tone="cyan"]{--layer-accent:#22d3ee;--layer-glow:#06b6d4}.framework-layer-strip a[data-tone="violet"]{--layer-accent:#c084fc;--layer-glow:#9333ea}.framework-layer-strip a[data-tone="amber"]{--layer-accent:#fbbf24;--layer-glow:#f59e0b}.framework-layer-strip a[data-tone="green"]{--layer-accent:#34d399;--layer-glow:#10b981}.framework-layer-strip i{border:1px solid color-mix(in srgb,var(--layer-accent) 45%,transparent);background:color-mix(in srgb,var(--layer-accent) 18%,transparent);color:var(--layer-accent)}.framework-layer-strip span{display:flex;flex-direction:column;gap:2px;color:#f8fafc;font-size:13px;font-weight:600}.framework-layer-strip small{color:#7895b4;font-size:9px;font-weight:500;letter-spacing:.1em}.framework-layer-strip b{position:relative;color:var(--layer-accent);font-size:20px}
.framework-layers{margin-top:16px;background:transparent}.framework-layers:deep(>.ant-collapse-item){margin-bottom:12px;overflow:hidden;border:1px solid #d8e4f1!important;border-radius:12px!important;background:#fff;box-shadow:0 7px 22px rgba(32,56,85,.06)}.framework-layers:deep(>.ant-collapse-item>.ant-collapse-header){min-height:72px;align-items:center;padding:12px 18px!important;background:linear-gradient(90deg,#f7faff 0%,#fff 72%)}.framework-layers:deep(>.ant-collapse-item-active>.ant-collapse-header){border-bottom:1px solid #e6edf5;background:linear-gradient(90deg,#edf6ff 0%,#f9fcff 70%,#fff 100%)}.framework-layers:deep(>.ant-collapse-item>.ant-collapse-content){border-top:0;background:#fff}.framework-layers:deep(>.ant-collapse-item>.ant-collapse-content>.ant-collapse-content-box){padding:20px}
.layer-title>i{width:36px;height:36px;border-radius:10px;background:linear-gradient(145deg,#1d4ed8,#0ea5e9);box-shadow:0 6px 15px rgba(37,99,235,.2);color:#fff}.layer-title b{color:#17314f;font-size:16px}.layer-title span{color:#7b8ea5}.layer-title em{padding:5px 10px;border:1px solid #d7e5f4;border-radius:16px;background:#f6faff;color:#486581}
.overview-description-stack{display:grid;gap:13px}.overview-descriptions{overflow:hidden;border-radius:9px}.overview-descriptions:deep(.ant-descriptions-view){border-color:#dce6f0;border-radius:9px}.overview-descriptions:deep(.ant-descriptions-item-label){width:132px;background:#f3f7fc!important;color:#57708c;font-size:12px;font-weight:600}.overview-descriptions:deep(.ant-descriptions-item-content){min-width:0;color:#18324f;line-height:1.65;white-space:pre-wrap;overflow-wrap:anywhere}.overview-long-descriptions:deep(.ant-descriptions-item-label){background:linear-gradient(90deg,#eaf3ff,#f4f8fc)!important;color:#285d94}.overview-long-descriptions:deep(.ant-descriptions-item-content){padding-top:12px;padding-bottom:12px;background:#fcfdff}
.entity-block+.entity-block{margin-top:24px;padding-top:22px}.subsection-heading{align-items:center;margin-bottom:12px}.subsection-heading>i{display:flex;width:38px;height:38px;align-items:center;justify-content:center;border-radius:10px;background:linear-gradient(145deg,#dbeafe,#cffafe);color:#0369a1;font-size:11px;font-style:normal;font-weight:800;letter-spacing:.05em}.subsection-heading>div{display:flex;flex-direction:column;gap:2px}.subsection-heading b{color:#193854}.subsection-heading span{color:#8091a5}.record-list{background:transparent}.record-list:deep(>.ant-collapse-item){margin-bottom:9px;overflow:hidden;border:1px solid #dfe8f1!important;border-radius:9px!important;background:#fff}.record-list:deep(>.ant-collapse-item>.ant-collapse-header){min-height:56px;align-items:center;padding:9px 14px!important;background:#fbfdff}.record-list:deep(>.ant-collapse-item-active>.ant-collapse-header){border-bottom:1px solid #e7edf4;background:#f3f8fd}.record-list:deep(.ant-collapse-content-box){padding:14px}.record-title>i{background:linear-gradient(145deg,#d1fae5,#cffafe);color:#047857}.record-title :deep(.ant-tag){margin-left:auto;margin-right:10px;flex:none}.wide-table-wrap{border-color:#dce6f0;border-radius:9px}.framework-table:deep(th.el-table__cell){background:#eef5fc;color:#46627f}.framework-layers:deep(.ant-empty){margin:22px 0}
@media(max-width:1100px){.framework-heading{align-items:flex-start}.framework-layer-strip{grid-template-columns:repeat(3,1fr)}}
@media(max-width:760px){.framework-overview-card:deep(.ant-card-body){padding:22px 18px}.framework-heading{flex-direction:column}.framework-coverage{width:100%;box-sizing:border-box;justify-content:center}.framework-layer-strip{grid-template-columns:1fr}.framework-layers:deep(>.ant-collapse-item>.ant-collapse-content>.ant-collapse-content-box){padding:12px}.layer-title em{display:none}.overview-descriptions:deep(.ant-descriptions-item-label){width:108px}.record-title :deep(.ant-tag){display:none}}
.reasoning-node-semantics{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px;margin:11px 0}.reasoning-node-semantics>div{padding:10px 12px;border:1px solid #dbeafe;border-radius:8px;background:#f8fbff}.reasoning-node-semantics>div:first-child{border-color:#a5f3fc;background:#ecfeff}.reasoning-node-semantics>div.semantic-boundary{border-color:#fed7aa;background:#fff7ed}.reasoning-node-semantics b{color:#1d4ed8;font-size:11px}.reasoning-node-semantics .semantic-boundary b{color:#c2410c}.reasoning-node-semantics p{margin:5px 0 0;color:#334155;font-size:12px;line-height:1.65}@media(max-width:760px){.reasoning-node-semantics{grid-template-columns:1fr}}
.snapshot-inspector dd{overflow:visible;text-overflow:clip;white-space:normal;overflow-wrap:anywhere;line-height:1.55}
.snapshot-inspector{max-height:none;overflow:visible}
.overview-layer-map{margin-bottom:10px;padding:12px 14px;border:1px solid #d7e5f4;border-radius:10px;background:linear-gradient(90deg,#f5f9ff,#fbfdff)}.overview-layer-map-title{display:flex;align-items:baseline;justify-content:space-between;gap:12px;margin-bottom:9px}.overview-layer-map-title b{color:#17314f;font-size:15px}.overview-layer-map-title span{color:#7b8ea5;font-size:11px}.overview-layer-path{display:grid;grid-template-columns:minmax(150px,1.35fr) 18px repeat(3,minmax(100px,1fr) 18px) minmax(100px,1fr);align-items:center;gap:4px}.overview-layer-path a{display:flex;min-width:0;align-items:center;gap:7px;padding:7px 9px;border:1px solid #dbe7f3;border-radius:8px;background:#fff;color:#334e68;text-decoration:none}.overview-layer-path a:hover{border-color:#60a5fa;background:#eff6ff}.overview-layer-path a i{display:inline-flex;width:23px;height:23px;flex:none;align-items:center;justify-content:center;border-radius:7px;background:#dbeafe;color:#1d4ed8;font-size:9px;font-style:normal;font-weight:700}.overview-layer-path a span{min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;font-size:12px;font-weight:600}.overview-layer-path em{color:#60a5fa;font-style:normal;font-weight:700;text-align:center}@media(max-width:850px){.overview-layer-map-title{align-items:flex-start;flex-direction:column}.overview-layer-path{display:flex;align-items:stretch;flex-direction:column}.overview-layer-path em{transform:rotate(90deg)}}
.case-overview-form{padding:14px;border:1px solid #dbe7f3;border-radius:10px;background:#fbfdff}.case-overview-readonly{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin-bottom:13px}.case-overview-readonly>div{padding:9px 11px;border:1px solid #dbe3ee;border-radius:8px;background:#f1f5f9}.case-overview-readonly span{display:block;margin-bottom:4px;color:#64748b;font-size:11px}.case-overview-readonly b{color:#1e3a5f;font-size:13px}.case-overview-form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 14px}.case-overview-form-grid .wide{grid-column:1/-1}.case-overview-form :deep(.el-form-item){margin-bottom:12px}.case-overview-form :deep(.el-form-item__label){padding-bottom:5px;color:#52677e;font-size:12px}.case-overview-form :deep(.el-select),.case-overview-form :deep(.el-date-editor){width:100%}.case-overview-form-actions{display:flex;justify-content:flex-end;gap:8px;padding-top:4px}@media(max-width:700px){.case-overview-readonly,.case-overview-form-grid{grid-template-columns:1fr}}
:global(.case-overview-cell-tooltip.el-popper){max-width:min(520px,calc(100vw - 32px));white-space:normal;overflow-wrap:anywhere;word-break:break-word;line-height:1.6}
.knowledge-chain-table thead th{text-align:center}
.confidence-value{cursor:help;border-bottom:1px dotted #94a3b8}
</style>
<style scoped src="@/styles/case-overview-shared.scss"></style>
