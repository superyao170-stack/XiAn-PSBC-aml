<template>
  <div class="pipeline-page">
    <div class="page-header">
      <div>
        <h2>{{ config.title }}</h2>
        <p>{{ config.subtitle }}</p>
      </div>
      <div class="header-actions">
        <el-dropdown v-if="canManageWorker" @command="handleWorkerCommand">
          <el-button>运行配置</el-button>
          <template #dropdown><el-dropdown-menu>
            <el-dropdown-item command="config">Worker 配置</el-dropdown-item>
            <el-dropdown-item command="code">编辑 Worker 代码</el-dropdown-item>
          </el-dropdown-menu></template>
        </el-dropdown>
        <el-button type="primary" @click="openCreateTask">新建上传任务</el-button>
      </div>
    </div>

    <el-row :gutter="16" class="summary">
      <el-col :span="6"><el-card shadow="never"><el-statistic title="任务总数" :value="allJobTotal" /></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><el-statistic title="运行中" :value="countByStatus('RUNNING')" /></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><el-statistic title="已完成" :value="countByStatus('SUCCEEDED')" /></el-card></el-col>
      <el-col :span="6"><el-card shadow="never"><el-statistic title="异常任务" :value="countByStatus('FAILED')" /></el-card></el-col>
    </el-row>

    <el-card id="job-records" class="job-card">
      <template #header>
        <div class="card-header">
          <span>任务记录</span>
          <div class="record-actions"><el-select v-model="statusFilter" clearable placeholder="全部状态" style="width:130px" @change="filterByStatus"><el-option v-for="item in jobStatuses" :key="item.value" :label="item.label" :value="item.value" /></el-select><span class="status-summary">完成 {{ countByStatus('SUCCEEDED') }} · 取消 {{ countByStatus('CANCELLED') }}</span><el-button text :loading="jobsLoading" @click="loadJobs">刷新</el-button></div>
        </div>
      </template>
      <el-table class="job-table adaptive-list-table" :data="jobs" table-layout="fixed" :fit="true">
        <el-table-column prop="jobId" label="任务ID" min-width="112" show-overflow-tooltip />
        <el-table-column prop="jobName" label="任务名称" min-width="112" show-overflow-tooltip />
        <el-table-column prop="bankCode" label="银行" width="112" show-overflow-tooltip />
        <el-table-column label="处理场景" width="96"><template #default="{ row }">{{ scenarioText(row.scenarioCode) }}</template></el-table-column>
        <el-table-column label="数据批次" min-width="112" show-overflow-tooltip>
          <template #default="{ row }"><span class="compact-cell" :class="{ muted: !row.batchId }" :title="batchText(row)">{{ batchText(row) }}</span></template>
        </el-table-column>
        <el-table-column label="状态 / 进度 / 时间" width="180">
          <template #default="{ row }">
            <div class="status-progress"><el-tag size="small" :type="statusType(row.status)">{{ statusText(row.status) }}</el-tag><el-progress :percentage="row.progress || 0" :stroke-width="8" /></div>
            <div class="created-time" :title="formatDateTime(row.createdAt)">{{ formatDateTime(row.createdAt) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="154" fixed="right" align="right" header-align="right">
          <template #default="{ row }">
            <div class="table-actions" @click.stop>
              <el-button link type="primary" @click="openTaskDetail(row)">详情</el-button>
              <el-dropdown trigger="click" @command="handleTaskCommand($event, row)">
                <el-button link>更多<el-icon class="more-arrow"><ArrowDown /></el-icon></el-button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item command="view" :disabled="row.status !== 'SUCCEEDED'">查看结果</el-dropdown-item>
                    <el-dropdown-item command="stop" :disabled="!['PENDING','RUNNING'].includes(row.status)">停止</el-dropdown-item>
                    <el-dropdown-item command="retry" :disabled="row.status !== 'FAILED'">重试</el-dropdown-item>
                    <el-dropdown-item command="delete" divided :disabled="row.status === 'RUNNING'">删除</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </div>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="page" v-model:page-size="pageSize" :page-sizes="[10,20,50]" layout="total, sizes, prev, pager, next" :total="total" />
    </el-card>

    <el-dialog v-model="createVisible" class="create-task-dialog" :title="`新建${config.title}任务`" width="820px" :close-on-click-modal="false">
      <div class="creation-guide">
        <div v-for="step in createSteps" :key="step.index" :class="{ completed: step.index < createStep, current: step.index === createStep }">
          <i>{{ step.index < createStep ? '✓' : step.index }}</i>
          <span>{{ step.name }}</span>
        </div>
      </div>
      <el-form :model="form" label-width="100px">
        <template v-if="createStep === 1">
        <template v-if="config.mode === 'STRUCTURED'">
          <el-form-item label="处理场景" required>
            <el-radio-group v-model="form.scenarioCode" @change="changeScenario">
              <el-radio-button value="AML">反洗钱</el-radio-button>
              <el-radio-button value="ANTI_FRAUD">反欺诈</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="案例来源" required>
            <el-radio-group v-model="structuredRecognitionMode" class="recognition-mode-buttons" @change="changeStructuredRecognitionMode">
              <el-radio-button value="HISTORICAL">历史案例</el-radio-button>
              <el-radio-button value="NEW">新增案例</el-radio-button>
            </el-radio-group>
            <div class="batch-hint">历史案例抽取后按基本信息中的风险等级定级并进入全景图谱；缺少或无法识别风险等级时转入复核审批。新增案例进入可疑报告处理队列。</div>
          </el-form-item>
          <el-form-item label="处理方式" required>
            <el-radio-group v-model="structuredCaseMode" @change="changeStructuredCaseMode">
              <el-radio-button value="SINGLE">单案例处理</el-radio-button>
              <el-radio-button value="BATCH">批处理</el-radio-button>
            </el-radio-group>
            <div class="batch-hint">单案例使用分角色 JSON 文件；批处理使用 CSV/XLSX，一行对应一个案例。</div>
          </el-form-item>
          <el-alert v-if="isAntiFraud && structuredCaseMode==='SINGLE'" type="info" :closable="false" title="反欺诈使用基础信息、客户、账户、设备四类直映射 JSON；新增案例另含事件链。" />
          <template v-if="structuredCaseMode === 'SINGLE'">
          <el-form-item label="基本信息" required>
            <input type="file" accept=".json,application/json" @change="selectStructuredCaseFile('basicInfo', $event)" />
            <div v-if="structuredCaseFiles.basicInfo" class="selected-file"><el-tag type="success">已选择</el-tag><span>{{ structuredCaseFiles.basicInfo.name }}</span><em>{{ formatFileSize(structuredCaseFiles.basicInfo.size) }}</em></div>
            <div class="batch-hint">上传 basic_info.json，顶层必须是 JSON 对象，并包含 case_id。</div>
          </el-form-item>
          <el-form-item label="客户信息" required>
            <input type="file" accept=".json,application/json" @change="selectStructuredCaseFile('customers', $event)" />
            <div v-if="structuredCaseFiles.customers" class="selected-file"><el-tag type="success">已选择</el-tag><span>{{ structuredCaseFiles.customers.name }}</span><em>{{ formatFileSize(structuredCaseFiles.customers.size) }}</em></div>
            <div class="batch-hint">上传 customers.json，顶层必须是非空 JSON 数组，每个客户包含 entity_id。</div>
          </el-form-item>
          <el-form-item v-if="structuredRecognitionMode === 'NEW' && isAntiFraud" label="事件链" required>
            <input type="file" accept=".json,application/json" @change="selectStructuredCaseFile('eventChain', $event)" />
            <div v-if="structuredCaseFiles.eventChain" class="selected-file"><el-tag type="success">新增案例</el-tag><span>{{ structuredCaseFiles.eventChain.name }}</span><em>{{ formatFileSize(structuredCaseFiles.eventChain.size) }}</em></div>
            <div class="batch-hint">上传 event_chain.json；系统先按“渠道”规则库生成风险事件链，再生成分析文本。</div>
          </el-form-item>
          <el-form-item v-else-if="structuredRecognitionMode === 'HISTORICAL' && isAntiFraud" label="分析文本" required>
            <input type="file" accept=".json,application/json" @change="selectStructuredCaseFile('textAnalysis', $event)" />
            <div v-if="structuredCaseFiles.textAnalysis" class="selected-file"><el-tag type="success">历史复用</el-tag><span>{{ structuredCaseFiles.textAnalysis.name }}</span><em>{{ formatFileSize(structuredCaseFiles.textAnalysis.size) }}</em></div>
            <div class="batch-hint">上传 text_analysis.json，必须包含非空 text 或 analysis_text；框架抽取时直接复用。</div>
          </el-form-item>
          <el-form-item v-else-if="structuredRecognitionMode === 'HISTORICAL'" label="分析文本" required>
            <input type="file" accept=".json,application/json" @change="selectStructuredCaseFile('analysisTexts', $event)" />
            <div v-if="structuredCaseFiles.analysisTexts" class="selected-file"><el-tag type="success">历史复用</el-tag><span>{{ structuredCaseFiles.analysisTexts.name }}</span><em>{{ formatFileSize(structuredCaseFiles.analysisTexts.size) }}</em></div>
            <div class="batch-hint">上传 analysis_texts.json，至少包含一段非空历史分析文本；上传后直接用于框架抽取。</div>
          </el-form-item>
          <el-form-item v-else label="后续处理">
            <el-tag type="success">系统生成</el-tag>
            <div class="batch-hint">上传完成后进入可疑报告处理页面。</div>
          </el-form-item>
          <el-form-item label="账户信息" :required="isAntiFraud">
            <input type="file" accept=".json,application/json" @change="selectStructuredCaseFile('accounts', $event)" />
            <div v-if="structuredCaseFiles.accounts" class="selected-file"><el-tag>{{ isAntiFraud ? '直映射' : '可选' }}</el-tag><span>{{ structuredCaseFiles.accounts.name }}</span><em>{{ formatFileSize(structuredCaseFiles.accounts.size) }}</em></div>
            <div class="batch-hint">{{ isAntiFraud ? '必填 accounts.json，账户信息不经 LLM 改写。' : '可选上传 accounts.json，顶层必须是 JSON 数组。' }}</div>
          </el-form-item>
          <el-form-item v-if="isAntiFraud" label="设备信息" required>
            <input type="file" accept=".json,application/json" @change="selectStructuredCaseFile('devices', $event)" />
            <div v-if="structuredCaseFiles.devices" class="selected-file"><el-tag>直映射</el-tag><span>{{ structuredCaseFiles.devices.name }}</span><em>{{ formatFileSize(structuredCaseFiles.devices.size) }}</em></div>
            <div class="batch-hint">必填 devices.json，每项包含设备号或 device_id。</div>
          </el-form-item>
          <el-form-item v-else label="其他实体">
            <input type="file" accept=".json,application/json" @change="selectStructuredCaseFile('otherEntities', $event)" />
            <div v-if="structuredCaseFiles.otherEntities" class="selected-file"><el-tag>可选</el-tag><span>{{ structuredCaseFiles.otherEntities.name }}</span><em>{{ formatFileSize(structuredCaseFiles.otherEntities.size) }}</em></div>
            <div class="batch-hint">可选上传 other_entities.json，顶层必须是 JSON 数组。</div>
          </el-form-item>
          </template>
          <el-form-item v-else label="案例批次" required>
            <input type="file" accept=".csv,.xlsx,text/csv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" @change="selectStructuredBatchFile" />
            <div v-if="structuredBatchFile" class="selected-file"><el-tag type="success">批处理</el-tag><span>{{ structuredBatchFile.name }}</span><em>{{ formatFileSize(structuredBatchFile.size) }}</em></div>
            <div class="batch-hint">
              {{ batchHeaderHint }}
            </div>
          </el-form-item>
        </template>
        </template>
        <template v-else-if="createStep === 2">
          <el-alert :type="dataValidation?.status === 'PASSED' ? 'success' : dataValidation?.status === 'BLOCKED' ? 'error' : 'warning'" :closable="false" :title="dataValidation?.message || '正在核对数据内容、批次和命名一致性'" />
          <el-descriptions v-if="dataValidation" :column="2" border class="validation-result">
            <el-descriptions-item label="数据来源">{{ dataValidation.source }}</el-descriptions-item>
            <el-descriptions-item label="批次名称">{{ dataValidation.batchName }}</el-descriptions-item>
            <el-descriptions-item label="数据规模">{{ dataValidation.dataScale }}</el-descriptions-item>
            <el-descriptions-item label="命名核对">{{ dataValidation.nameCheck }}</el-descriptions-item>
            <el-descriptions-item label="分析文本策略" :span="2">{{ dataValidation.labelCheck }}</el-descriptions-item>
            <el-descriptions-item label="内容指纹" :span="2"><span class="fingerprint">{{ dataValidation.fingerprint || '已有批次，沿用入库校验结果' }}</span></el-descriptions-item>
          </el-descriptions>
        </template>
        <template v-else-if="createStep === 3">
          <el-form-item label="任务名称" required><el-input v-model="form.jobName" /></el-form-item>
          <el-form-item label="银行" required><el-select v-model="form.bankCode" filterable allow-create default-first-option style="width:100%" placeholder="选择或输入银行"><el-option v-for="bank in bankOptions" :key="bank.value" :label="bank.label" :value="bank.value" /></el-select><div class="batch-hint">已带入默认银行，也可以从列表选择或直接输入。</div></el-form-item>
        </template>
        <el-descriptions v-else :column="2" border class="create-confirmation">
          <el-descriptions-item label="上传任务">{{ config.title }}</el-descriptions-item>
          <el-descriptions-item label="任务名称">{{ form.jobName }}</el-descriptions-item>
          <el-descriptions-item label="输入数据">{{ selectedInputText }}</el-descriptions-item>
          <el-descriptions-item label="业务场景">{{ form.scenarioCode || '未指定' }}</el-descriptions-item>
          <el-descriptions-item label="案例类型">{{ recognitionCaseTypeLabel }}</el-descriptions-item>
          <el-descriptions-item label="所属银行">{{ form.bankCode }}</el-descriptions-item>
          <el-descriptions-item label="数据空间">{{ workspaceDisplay }}</el-descriptions-item>
        </el-descriptions>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button v-if="createStep > 1" @click="createStep--">上一步</el-button>
        <el-button v-if="createStep < 4" type="primary" :loading="validatingData" @click="advanceCreateStep">下一步</el-button>
        <el-button v-else type="primary" :loading="saving" @click="createJob">开始执行</el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="taskDetailVisible" title="" width="1240px" class="job-detail-dialog">
      <div v-if="currentJob" class="trajectory-header">
        <div><h2>任务执行轨迹</h2><p>{{ currentJob.jobName || '未命名任务' }} <span>·</span> {{ currentJob.jobId }}</p></div>
        <div class="trajectory-status" :class="'status-' + String(currentJob.status || '').toLowerCase()"><i></i>{{ statusText(currentJob.status) }} · {{ currentJob.progress || 0 }}%</div>
      </div>
      <section v-if="currentJob" class="upload-progress-card">
        <div class="upload-file-icon">{{ fileExtension(currentJob) }}</div>
        <div class="upload-file-info"><strong :title="sourceFileName(currentJob)">{{ sourceFileName(currentJob) }}</strong><span>{{ processingModeText(currentJob) }}<template v-if="currentJob.batchNo"> · 批次 {{ currentJob.batchNo }}</template><template v-if="currentJob.createdAt"> · 创建于 {{ formatDateTime(currentJob.createdAt) }}</template></span><el-progress :percentage="Number(currentJob.progress || 0)" :stroke-width="8" :status="currentJob.status==='FAILED'?'exception':undefined" /></div>
        <div class="upload-current-count">{{ currentJob.status==='RUNNING' ? '正在处理' : currentJob.status==='SUCCEEDED' ? '已处理' : '输入案例' }}<strong>{{ currentJob.caseCount || caseTotalAcrossJob(currentJob) || 0 }} 个案例</strong></div>
      </section>
      <div class="trajectory-layout">
        <section class="trajectory-main">
          <div class="trajectory-section-heading"><strong>处理阶段</strong><span>{{ timelineProgressHint(currentJob) }}</span></div>
          <el-collapse v-if="currentJob" v-model="expandedStepNames" class="timeline-collapse">
            <div v-for="(stage, index) in timelineStages(currentJob)" :key="stage.id" class="timeline-step" :class="'timeline-' + String(stage.status || '').toLowerCase()">
              <div class="timeline-marker"><span>{{ timelineMarker(stage.status) }}</span><i v-if="index < timelineStages(currentJob).length - 1"></i></div>
              <el-collapse-item :name="stage.id" class="timeline-collapse-item">
                <template #title><div class="timeline-step-content"><div class="timeline-step-heading"><strong>{{ stage.name }}</strong><el-tag size="small" :type="statusType(stage.status)">{{ stageStatusText(stage.status) }}</el-tag><span class="timeline-time">{{ stage.timeLabel }}</span></div><div class="timeline-step-expand">{{ expandedStepNames.includes(stage.id) ? '收起阶段详情' : stage.status==='RUNNING' ? '查看当前阶段详情' : '查看阶段详情' }} <span>{{ expandedStepNames.includes(stage.id) ? '⌃' : '⌄' }}</span></div></div></template>
                <div class="step-drawer-body">
                  <p class="stage-detail-note">{{ stage.detail }}</p>
                  <el-progress v-if="stage.sourceStep" :percentage="stage.sourceStep.progress || 0" :stroke-width="7" :status="stage.sourceStep.status==='FAILED'?'exception':undefined" />
                  <div v-if="stage.sourceStep" class="step-drawer-meta"><span>阶段开始：{{ stage.sourceStep.startedAt ? formatDateTime(stage.sourceStep.startedAt) : '未记录' }}</span><span>阶段完成：{{ stage.sourceStep.completedAt ? formatDateTime(stage.sourceStep.completedAt) : '进行中 / 尚未完成' }}</span></div>
                  <el-alert v-if="stage.sourceStep?.errorMessage" class="step-inline-error" type="error" show-icon :closable="false" :title="stage.sourceStep.errorMessage" />
                  <div v-if="stage.dataStep && isFrameworkStep(stage.dataStep) && stepProgress(stage.dataStep).currentCase && stage.status==='RUNNING'" class="current-case-progress"><div class="current-case-heading"><strong>当前处理位置</strong><el-tag size="small" type="warning">{{ stageStatusText(stepProgress(stage.dataStep).currentStageStatus) }}</el-tag></div><div class="current-case-line">第 {{ stepProgress(stage.dataStep).currentCase.caseIndex || '—' }} / {{ stepProgress(stage.dataStep).caseTotal || stepProgress(stage.dataStep).currentCase.caseTotal || '—' }} 个案例 <span>·</span>{{ stepProgress(stage.dataStep).currentCase.caseId || '案例编号待返回' }} <span>·</span>{{ stageName(stepProgress(stage.dataStep).currentStage) }}</div></div>
                  <el-descriptions v-if="stage.sourceStep && stepResult(stage.sourceStep)?.performance" :column="3" border size="small" class="step-result-summary"><el-descriptions-item label="案例数">{{ stepResult(stage.sourceStep).caseCount || stepResult(stage.sourceStep).results?.length || 0 }}</el-descriptions-item><el-descriptions-item label="总耗时">{{ stepResult(stage.sourceStep).performance.totalSeconds }} 秒</el-descriptions-item><el-descriptions-item label="入库数">{{ stepResult(stage.sourceStep).persistedCaseCount || 0 }}</el-descriptions-item></el-descriptions>
                  <el-descriptions v-else-if="stage.sourceStep && stepResult(stage.sourceStep) && !isFrameworkStep(stage.sourceStep)" :column="2" border size="small" class="step-result-summary"><el-descriptions-item v-for="entry in stepSummaryEntries(stage.sourceStep)" :key="entry.label" :label="entry.label">{{ entry.value }}</el-descriptions-item></el-descriptions>
                  <el-table v-if="stage.rows.length" :data="stage.rows" size="small" max-height="190" class="stage-case-table" row-key="caseId"><el-table-column label="案例" min-width="170" show-overflow-tooltip><template #default="{ row: item }"><span>{{ item.caseName || item.caseId }}</span><small class="case-id-sub">{{ item.caseId }}</small></template></el-table-column><el-table-column label="本阶段状态" width="115"><template #default="{ row: item }"><el-tag size="small" :type="statusType(item[stage.statusKey])">{{ stageStatusText(item[stage.statusKey]) }}</el-tag></template></el-table-column><el-table-column v-if="stage.countKey" :label="stage.countLabel" width="115"><template #default="{ row: item }">{{ item[stage.countKey] ?? 0 }}</template></el-table-column></el-table>
                </div>
              </el-collapse-item>
            </div>
          </el-collapse>
          <el-empty v-else :image-size="48" description="暂无阶段执行记录" />
        </section>
        <aside class="trajectory-aside">
          <section class="trajectory-side-card"><h3>处理结果</h3><div class="result-line"><span>读取记录</span><strong>{{ resultReadCount(currentJob) }} 条</strong></div><div class="result-line"><span>成功入库</span><strong>{{ stageResultCount(currentJob, 'databaseStatus', 'SUCCEEDED') }} 条</strong></div><div class="result-line"><span>跳过记录</span><strong>{{ skippedCaseCount(currentJob) }} 条</strong></div><div class="result-line"><span>失败记录</span><strong>{{ failedCaseCount(currentJob) }} 条</strong></div></section>
          <section class="trajectory-side-card"><h3>任务归属</h3><div class="result-line"><span>银行</span><strong>{{ currentJob.bankCode || '—' }}</strong></div><div class="result-line"><span>场景</span><strong>{{ currentJob.scenarioCode || '未指定' }}</strong></div><div class="result-line"><span>处理方式</span><strong>{{ processingModeText(currentJob) }}</strong></div><div class="result-line"><span>任务类型</span><strong>{{ jobRecognitionMode(currentJob)==='HISTORICAL' ? '历史案例抽取' : jobRecognitionMode(currentJob)==='NEW' ? '新增案例处理' : '案例上传' }}</strong></div></section>
          <div class="trajectory-tip" :class="{ 'tip-failed': currentJob.status==='FAILED' }">{{ currentJob.status==='FAILED' ? '任务执行失败：' + (currentJob.errorMessage || '请展开失败阶段查看原因') : currentJob.status==='SUCCEEDED' ? '任务已完成。展开任一阶段可查看该阶段的处理结果与执行记录。' : '任务处理中。展开当前阶段可查看正在处理的案例与阶段结果。' }}</div>
        </aside>
      </div>
      <section v-if="jobCaseRows(currentJob).length" class="case-progress-block trajectory-case-progress">
        <div class="case-progress-heading"><strong>抽取与入图的逐案例进度</strong><span>{{ caseStageSummary(currentJob) }}</span></div>
        <el-table :data="jobCaseRows(currentJob)" size="small" max-height="300" row-key="caseId" empty-text="等待 Worker 返回案例进度">
          <el-table-column label="案例" min-width="220" show-overflow-tooltip><template #default="{ row: item }"><div class="case-id-cell">{{ item.caseName || item.caseId }}</div><div class="case-id-sub">{{ item.caseId }}</div></template></el-table-column>
          <el-table-column label="事件抽取" min-width="150"><template #default="{ row: item }"><el-tag size="small" :type="statusType(item.eventStatus)">{{ stageStatusText(item.eventStatus) }}</el-tag><span v-if="item.eventCount!=null" class="case-count">{{ item.eventCount }} 个事件</span><span v-else-if="item.eventStatus==='SKIPPED'" class="case-count">本次任务不执行</span></template></el-table-column>
          <el-table-column label="关系抽取" min-width="150"><template #default="{ row: item }"><el-tag size="small" :type="statusType(item.relationshipStatus)">{{ stageStatusText(item.relationshipStatus) }}</el-tag><span v-if="item.relationshipCount!=null" class="case-count">{{ item.relationshipCount }} 条关系</span><span v-else-if="item.relationshipStatus==='SKIPPED'" class="case-count">本次任务不执行</span></template></el-table-column>
          <el-table-column label="案例入库" min-width="130"><template #default="{ row: item }"><el-tag size="small" :type="statusType(item.databaseStatus)">{{ stageStatusText(item.databaseStatus) }}</el-tag></template></el-table-column>
          <el-table-column label="自动入图" min-width="130"><template #default="{ row: item }"><el-tag size="small" :type="statusType(item.graphStatus)">{{ stageStatusText(item.graphStatus) }}</el-tag></template></el-table-column>
        </el-table>
      </section>
      <el-alert v-if="currentJob?.errorMessage" class="job-failure" type="error" show-icon :closable="false" :title="'失败原因：' + currentJob.errorMessage" />
      <template v-if="currentJob?.failureHistory?.length"><h4 class="failure-history-title">失败与重试记录</h4><el-table :data="currentJob.failureHistory" size="small" border><el-table-column label="失败时间" width="170"><template #default="{row}">{{ formatDateTime(row.failedAt) }}</template></el-table-column><el-table-column prop="failedStepName" label="失败阶段" width="160" /><el-table-column prop="errorMessage" label="失败原因" min-width="260" show-overflow-tooltip /><el-table-column label="重试状态" width="170"><template #default="{row}">{{ row.retriedAt ? formatDateTime(row.retriedAt) + ' 已重试' : '尚未重试' }}</template></el-table-column></el-table></template>
      <template #footer><el-button v-if="currentJob?.status==='FAILED'" type="primary" @click="retryJob(currentJob);taskDetailVisible=false">重试上传任务</el-button><el-button @click="taskDetailVisible=false">关闭</el-button></template>
    </el-dialog>
    <el-dialog v-model="configVisible" title="Worker运行配置" width="1100px">
      <el-alert type="info" :closable="false" title="大模型参数已改为 Worker 源码常量，不再通过此处或环境变量配置。" />
      <el-table :data="workerFields" border style="margin-top:12px">
        <el-table-column prop="key" label="参数" width="220" />
        <el-table-column prop="description" label="说明" width="260" />
        <el-table-column label="值">
          <template #default="{ row }"><el-input v-model="row.value" class="config-value" :disabled="!configEditing" type="text" /></template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button v-if="!configEditing" type="primary" @click="configEditing=true">编辑</el-button>
        <template v-else><el-button @click="configEditing=false; loadWorkerConfig()">取消</el-button><el-button type="primary" @click="saveWorkerConfig">确定保存</el-button></template>
      </template>
    </el-dialog>
    <el-dialog v-model="caseSelectorVisible" title="选择该任务生成的案例" width="760px">
      <el-alert :title="`该批量任务生成了 ${caseSelectorIds.length} 个独立案例，请选择要查看的案例。`" type="info" :closable="false" />
      <div class="case-selector-list">
        <el-button v-for="id in caseSelectorIds" :key="id" text type="primary" @click="openCase(id)">{{ id }}</el-button>
      </div>
      <template #footer><el-button @click="openAllTaskCases">查看该任务全部案例</el-button><el-button @click="caseSelectorVisible=false">关闭</el-button></template>
    </el-dialog>
    <el-dialog v-model="codeVisible" title="Worker Python源码" width="900px">
      <el-upload :show-file-list="false" accept=".zip" :before-upload="uploadWorkerZip" style="margin-bottom:12px">
        <el-button type="success">上传 Worker ZIP 并解压</el-button>
        <template #tip><span class="upload-tip">上传后会解压到当前银行 Worker 目录，再刷新文件列表编辑单个 .py 文件。</span></template>
      </el-upload>
      <el-select v-model="codePath" filterable style="width:100%;margin-bottom:12px" placeholder="选择Python文件" @change="loadWorkerFile"><el-option v-for="p in codeFiles" :key="p" :label="p" :value="p" /></el-select>
      <el-input v-model="codeContent" type="textarea" :rows="24" />
      <template #footer><el-button @click="codeVisible=false">取消</el-button><el-button type="primary" @click="saveWorkerFile">保存源码</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowDown } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  createAnalysisJobApi, getAnalysisJobApi, getAnalysisJobsApi,
  retryAnalysisJobApi, cancelAnalysisJobApi, deleteAnalysisJobApi,
  getWorkerConfigApi, updateWorkerConfigApi, getWorkerFilesApi, getWorkerFileApi, updateWorkerFileApi, uploadWorkerZipApi,
  uploadStructuredCaseFilesApi, uploadStructuredCaseBatchFileApi, uploadAntiFraudCaseFilesApi,
  uploadAntiFraudCaseBatchFileApi
} from '@/api/analysis'
import { getCasesApi } from '@/api/case'
import { formatDateTime } from '@/utils/datetime'

const router = useRouter()
const roleCode = localStorage.getItem('roleCode') || ''
const canManageWorker = computed(() => roleCode === 'sadmin')
const isAntiFraud = computed(() => form.scenarioCode === 'ANTI_FRAUD')
const config = computed(() => ({
  mode: 'STRUCTURED' as const,
  workerMode: isAntiFraud.value ? 'ANTI_FRAUD' : 'STRUCTURED',
  title: '案例上传',
  subtitle: '上传历史案例或新增案例，并查看入库与自动处理进度',
  stepCodes: ['VALIDATE','HISTORY_FRAMEWORK'],
  steps: ['上传与数据校验','逐案例抽取、入库与自动入图']
}))
const jobs = ref<any[]>([])
const total = ref(0)
const statusCounts = ref<Record<string,number>>({ PENDING:0, RUNNING:0, SUCCEEDED:0, FAILED:0, CANCELLED:0 })
const statusFilter = ref('')
const jobStatuses = [{label:'待启动',value:'PENDING'},{label:'运行中',value:'RUNNING'},{label:'已完成',value:'SUCCEEDED'},{label:'失败',value:'FAILED'},{label:'已取消',value:'CANCELLED'}]
const allJobTotal = computed(() => Object.values(statusCounts.value).reduce((sum,value)=>sum+Number(value||0),0))
const scenarioOptions = [
  { label: '反洗钱', value: 'AML' },
  { label: '反欺诈', value: 'ANTI_FRAUD' }
]
const page = ref(1)
const pageSize = ref(10)
const currentJob = ref<any>(null)
const expandedStepNames = ref<string[]>([])
const createVisible = ref(false)
const createStep = ref(1)
const taskDetailVisible = ref(false)
const configVisible = ref(false)
const configEditing = ref(false)
const workerConfig = ref('')
const workerFields = ref<any[]>([])
const codeVisible = ref(false), codeFiles = ref<string[]>([]), codePath = ref(''), codeContent = ref('')
const caseSelectorVisible = ref(false), caseSelectorIds = ref<string[]>([]), selectedCaseJobId = ref('')
const completedCases = ref<any[]>([])
const jobsLoading = ref(false)
const workerDescriptions: Record<string,string> = {
}
const saving = ref(false)
const boundBankCode = localStorage.getItem('bankCode') || ''
const form = reactive({ jobName: '', workspaceId: 1, scenarioCode: 'AML', bankCode: boundBankCode || '中国测试银行' })
const recognitionCaseTypeLabel=computed(()=>'结构化案例')
const structuredCaseMode=ref<'SINGLE'|'BATCH'>('SINGLE')
const structuredRecognitionMode=ref<'NEW'|'HISTORICAL'>('NEW')
const structuredBatchFile=ref<File|null>(null)
const structuredBatchCaseCount=ref(0)
const structuredCaseFiles=reactive<{
  basicInfo:File|null
  customers:File|null
  analysisTexts:File|null
  accounts:File|null
  otherEntities:File|null
  devices:File|null
  eventChain:File|null
  textAnalysis:File|null
}>({basicInfo:null,customers:null,analysisTexts:null,accounts:null,otherEntities:null,devices:null,eventChain:null,textAnalysis:null})
const structuredCaseUploadToken=ref('')
const bankOptions = [
  {label:'中国测试银行',value:'中国测试银行'},
  {label:'中国工商银行',value:'ICBC'},
  {label:'中国建设银行',value:'CCB'},
  {label:'中国银行',value:'BOC'},
  {label:'中国农业银行',value:'ABC'},
  {label:'交通银行',value:'BCM'},
  {label:'中国邮政储蓄银行',value:'PSBC'},
  {label:'招商银行',value:'CMB'},
  {label:'兴业银行',value:'CIB'},
  {label:'中信银行',value:'CITIC'}
]
const validatingData=ref(false)
const dataValidation=ref<any>(null)
const validatedInputSignature=ref('')
const createSteps = [
  {index:1,name:'选数据'},
  {index:2,name:'数据校验'},
  {index:3,name:'确认参数'},
  {index:4,name:'开始执行'}
]
const batchHeaderHint=computed(()=>isAntiFraud.value
  ? structuredRecognitionMode.value==='NEW'
    ? '表头必须为 basic_info、customers、accounts、devices、event_chain；每个单元格填写对应 JSON。'
    : '表头必须为 basic_info、customers、accounts、devices、text_analysis；每个单元格填写对应 JSON。'
  : structuredRecognitionMode.value==='NEW'
    ? '新增案例表头：basic_info、customers、transaction_features；每个单元格填写对应 JSON。'
    : '历史案例表头：basic_info、customers、analysis_texts；每个单元格填写对应 JSON。')
const wizardInputReady=computed(()=>Boolean(
  structuredCaseMode.value==='BATCH' ? structuredBatchFile.value
    : isAntiFraud.value
      ? structuredCaseFiles.basicInfo&&structuredCaseFiles.customers&&structuredCaseFiles.accounts&&structuredCaseFiles.devices
        &&(structuredRecognitionMode.value==='HISTORICAL'?structuredCaseFiles.textAnalysis:structuredCaseFiles.eventChain)
      : structuredCaseFiles.basicInfo&&structuredCaseFiles.customers
        &&(structuredRecognitionMode.value==='NEW'||structuredCaseFiles.analysisTexts)
))
const workspaceDisplay=computed(()=>`默认数据空间（ID：${form.workspaceId}）`)
const fileIdentity=(file:File|null)=>file?`${file.name}:${file.size}:${file.lastModified}`:''
const currentInputSignature=computed(()=>[
  config.value.mode,
  form.bankCode,
  structuredCaseMode.value,
  structuredRecognitionMode.value,
  fileIdentity(structuredBatchFile.value),
  fileIdentity(structuredCaseFiles.basicInfo),
  fileIdentity(structuredCaseFiles.customers),
  fileIdentity(structuredCaseFiles.analysisTexts),
  fileIdentity(structuredCaseFiles.accounts),
  fileIdentity(structuredCaseFiles.otherEntities),
  fileIdentity(structuredCaseFiles.devices),
  fileIdentity(structuredCaseFiles.eventChain),
  fileIdentity(structuredCaseFiles.textAnalysis)
].join('|'))
const formatFileSize=(size:number)=>size>=1024**2?`${(size/1024**2).toFixed(2)} MB`:`${Math.max(1,Math.round(size/1024))} KB`
const selectedInputText=computed(()=>{
  const type=structuredRecognitionMode.value==='HISTORICAL'?'历史案例':'新增案例'
  if(structuredCaseMode.value==='BATCH'){
    return `批处理 · ${type}（${structuredBatchFile.value?.name||'未选择文件'}${structuredBatchCaseCount.value?`，${structuredBatchCaseCount.value} 个案例`:''}）`
  }
  if(isAntiFraud.value)return `单案例处理 · ${type}（基础四类 JSON + ${structuredRecognitionMode.value==='NEW'?'event_chain.json':'text_analysis.json'}）`
  const optional=[structuredCaseFiles.accounts,structuredCaseFiles.otherEntities].filter(Boolean).length
  return `单案例处理 · ${type}（${structuredRecognitionMode.value==='HISTORICAL'?3:2} 个必填文件，${optional} 个可选文件）`
})
const advanceCreateStep=async()=>{
  if(createStep.value===1&&!wizardInputReady.value){
    return ElMessage.warning(
      structuredCaseMode.value==='BATCH'
        ? '请选择 CSV/XLSX 批处理文件'
        : isAntiFraud.value
        ? `请选择 basic_info.json、customers.json、accounts.json、devices.json 和 ${structuredRecognitionMode.value==='NEW'?'event_chain.json':'text_analysis.json'}`
        : `请选择 basic_info.json、customers.json${structuredRecognitionMode.value==='HISTORICAL'?' 和 analysis_texts.json':''}`)
  }
  if(createStep.value===1){
    validatingData.value=true
    try{
      if(!await validateSelectedData())return
    }finally{validatingData.value=false}
  }
  if(createStep.value===2&&(dataValidation.value?.status!=='PASSED'||validatedInputSignature.value!==currentInputSignature.value)){
    return ElMessage.warning(dataValidation.value?.status==='BLOCKED'
      ? dataValidation.value.message
      : '数据或批次信息已经变化，请返回上一步重新校验')
  }
  if(createStep.value===3&&(!form.jobName.trim()||!form.bankCode.trim()||!form.scenarioCode))return ElMessage.warning('请填写任务名称、所属银行并选择业务场景')
  if(createStep.value===3&&validatedInputSignature.value!==currentInputSignature.value){
    validatingData.value=true
    try{
      if(!await validateSelectedData())return
      createStep.value=2
      ElMessage.info('银行或数据归属发生变化，已重新校验，请确认校验结果')
      return
    }finally{validatingData.value=false}
  }
  createStep.value++
}
const resetStructuredCaseUpload=()=>{
  structuredCaseUploadToken.value=''
  dataValidation.value=null
  validatedInputSignature.value=''
}
const changeStructuredRecognitionMode=()=>{
  structuredCaseFiles.analysisTexts=null
  structuredCaseFiles.textAnalysis=null
  if(structuredRecognitionMode.value==='HISTORICAL')structuredCaseFiles.eventChain=null
  structuredBatchFile.value=null
  structuredBatchCaseCount.value=0
  resetStructuredCaseUpload()
}
const changeStructuredCaseMode=()=>{
  structuredBatchFile.value=null
  structuredBatchCaseCount.value=0
  resetStructuredCaseUpload()
}
const changeScenario=()=>{
  structuredCaseMode.value='SINGLE'
  structuredBatchFile.value=null
  structuredBatchCaseCount.value=0
  Object.assign(structuredCaseFiles,{basicInfo:null,customers:null,analysisTexts:null,accounts:null,
    otherEntities:null,devices:null,eventChain:null,textAnalysis:null})
  resetStructuredCaseUpload()
}
const scenarioText=(value:string)=>scenarioOptions.find(item=>item.value===value)?.label||value
const selectStructuredBatchFile=(event:Event)=>{
  structuredBatchFile.value=(event.target as HTMLInputElement).files?.[0]||null
  structuredBatchCaseCount.value=0
  resetStructuredCaseUpload()
}
const selectStructuredCaseFile=(role:keyof typeof structuredCaseFiles,event:Event)=>{
  structuredCaseFiles[role]=(event.target as HTMLInputElement).files?.[0]||null
  resetStructuredCaseUpload()
}
const validateSelectedData=async()=>{
  if(dataValidation.value?.status==='PASSED'&&validatedInputSignature.value===currentInputSignature.value)return true
  if(structuredCaseMode.value==='BATCH'){
      const file=structuredBatchFile.value
      if(!file)return false
      if(!/\.(csv|xlsx)$/i.test(file.name)||file.size<=0||file.size>100*1024*1024){
        dataValidation.value={status:'BLOCKED',message:'批处理只支持不超过100MB的非空 CSV/XLSX 文件'}
        validatedInputSignature.value=currentInputSignature.value
        return true
      }
      try{
        const response:any=isAntiFraud.value
          ? await uploadAntiFraudCaseBatchFileApi(file,structuredRecognitionMode.value)
          : await uploadStructuredCaseBatchFileApi(file,structuredRecognitionMode.value)
        const result=response.data||{}
        structuredCaseUploadToken.value=String(result.uploadToken||'')
        structuredBatchCaseCount.value=Number(result.caseCount||0)
        if(!structuredCaseUploadToken.value||!structuredBatchCaseCount.value)throw new Error('批处理文件校验未返回案例数量')
        dataValidation.value={
          status:'PASSED',source:`批处理 · ${structuredRecognitionMode.value==='HISTORICAL'?'历史案例':'新增案例'}`,
          batchName:file.name,dataScale:`${structuredBatchCaseCount.value} 个案例，共 ${formatFileSize(file.size)}`,
          nameCheck:'表头、逐行 JSON、case_id 唯一性及识别类型一致性已确认',
          fingerprint:result.sourceSha256||'',
          labelCheck:structuredRecognitionMode.value==='HISTORICAL'?'历史案例：按基本信息风险等级自动入图，缺失时转复核审批':'新增案例：上传后进入可疑报告队列',
          message:'批处理文件结构与全部案例数据校验通过'
        }
      }catch(error:any){
        dataValidation.value={status:'BLOCKED',message:error?.message||'批处理文件校验失败'}
      }
      validatedInputSignature.value=currentInputSignature.value
      return true
    }
    const required=isAntiFraud.value
      ? [structuredCaseFiles.basicInfo,structuredCaseFiles.customers,structuredCaseFiles.accounts,structuredCaseFiles.devices,
          structuredRecognitionMode.value==='NEW'?structuredCaseFiles.eventChain:structuredCaseFiles.textAnalysis]
      : [structuredCaseFiles.basicInfo,structuredCaseFiles.customers,
          structuredRecognitionMode.value==='HISTORICAL'?structuredCaseFiles.analysisTexts:true]
    if(required.some(file=>!file))return false
    const files=Object.values(structuredCaseFiles).filter((file):file is File=>Boolean(file))
    const invalid=files.find(file=>!file.name.toLowerCase().endsWith('.json')||file.size<=0||file.size>100*1024*1024)
    const totalSize=files.reduce((sum,file)=>sum+file.size,0)
    let contentError=''
    if(!invalid&&totalSize<=100*1024*1024){
      try{
        const basic=JSON.parse(await (structuredCaseFiles.basicInfo as File).text())
        const customers=JSON.parse(await (structuredCaseFiles.customers as File).text())
        if(!basic||Array.isArray(basic)||typeof basic!=='object')contentError='basic_info.json 顶层必须是 JSON 对象'
        else if(!String(basic.case_id||'').trim())contentError='basic_info.json 必须包含非空 case_id'
        else if(!Array.isArray(customers)||!customers.length)contentError='customers.json 顶层必须是非空 JSON 数组'
        else if(customers.some((item:any)=>!item||Array.isArray(item)||typeof item!=='object'||!String(item.entity_id||'').trim()))contentError='customers.json 中每个客户都必须包含非空 entity_id'
        if(!contentError&&structuredCaseFiles.analysisTexts){
          const analysis=JSON.parse(await structuredCaseFiles.analysisTexts.text())
          if(!analysis||Array.isArray(analysis)||typeof analysis!=='object'||!Object.values(analysis).some(value=>typeof value==='string'&&value.trim()))contentError='analysis_texts.json 顶层必须是对象，并至少包含一段非空分析文本'
        }
        for(const [file,name] of [[structuredCaseFiles.accounts,'accounts.json'],[structuredCaseFiles.otherEntities,'other_entities.json']] as const){
          if(!contentError&&file&&!Array.isArray(JSON.parse(await file.text())))contentError=`${name} 顶层必须是 JSON 数组`
        }
        if(isAntiFraud.value&&!contentError){
          const accounts=JSON.parse(await (structuredCaseFiles.accounts as File).text())
          const devices=JSON.parse(await (structuredCaseFiles.devices as File).text())
          if(!Array.isArray(accounts))contentError='accounts.json 顶层必须是 JSON 数组'
          else if(accounts.some((item:any)=>!String(item?.entity_id||'').trim()))contentError='accounts.json 中每个账户必须包含 entity_id'
          else if(!Array.isArray(devices))contentError='devices.json 顶层必须是 JSON 数组'
          else if(devices.some((item:any)=>!String(item?.设备号||item?.device_id||'').trim()))contentError='devices.json 中每个设备必须包含设备号或 device_id'
          else if(structuredRecognitionMode.value==='NEW'){
            const chain=JSON.parse(await (structuredCaseFiles.eventChain as File).text())
            if(!Array.isArray(chain)||!chain.length)contentError='event_chain.json 顶层必须是非空 JSON 数组'
          }else{
            const analysis=JSON.parse(await (structuredCaseFiles.textAnalysis as File).text())
            if(!analysis||Array.isArray(analysis)||typeof analysis!=='object'||!String(analysis.text||analysis.analysis_text||'').trim())contentError='text_analysis.json 必须包含非空 text 或 analysis_text'
          }
        }
      }catch(error:any){contentError=`案例文件不是有效 JSON：${error?.message||'解析失败'}`}
    }
    const blocked=Boolean(invalid)||totalSize>100*1024*1024||Boolean(contentError)
    const caseKind=structuredRecognitionMode.value==='HISTORICAL'?'历史案例':'新增案例'
    dataValidation.value={
      status:blocked?'BLOCKED':'PASSED',source:`单案例处理 · ${caseKind}`,
      batchName:[structuredCaseFiles.basicInfo?.name,structuredCaseFiles.customers?.name].join(' + '),
      dataScale:`${files.length} 个 JSON 文件，共 ${formatFileSize(totalSize)}`,nameCheck:'必填文件与可选文件已确认',
      fingerprint:'上传后由服务端按案例目录计算 SHA-256',
      labelCheck:isAntiFraud.value
        ? structuredRecognitionMode.value==='HISTORICAL'?'历史案例复用 text_analysis，按基本信息风险等级自动入图；缺失时转复核审批':'新增案例进入可疑报告处理队列'
        : structuredRecognitionMode.value==='HISTORICAL'?'历史案例按基本信息风险等级自动入图；缺失时转复核审批':'新增案例进入可疑报告处理队列',
      message:contentError||(
        blocked?'仅支持单个不超过100MB、总计不超过100MB的非空 JSON 文件':'单案例文件组合、内容结构与处理策略校验通过'
      )
    }
  validatedInputSignature.value=currentInputSignature.value
  return true
}
const loadWorkerConfig = async () => {
  const r:any = await getWorkerConfigApi(config.value.workerMode)
  workerConfig.value = r.data || ''
  const rows = String(r.data || '').split(/\r?\n/).filter((x:string)=>{
    if (!x || x.startsWith('#') || !x.includes('=')) return false
    const key = x.slice(0, x.indexOf('=')).trim()
    return !key.startsWith('LLM_') && !key.startsWith('DEEPSEEK_')
  })
  workerFields.value = rows.map((x:string)=>{
    const i=x.indexOf('='), key=x.slice(0,i).trim()
    return { key, value:x.slice(i+1), description:workerDescriptions[key] || 'worker运行参数', secret:key.includes('KEY') || key.includes('TOKEN') }
  })
  configEditing.value = false
}
const saveWorkerConfig = async () => {
  const content = workerFields.value.map(x=>`${x.key}=${x.value}`).join('\n')
  await updateWorkerConfigApi(content, config.value.workerMode); configEditing.value = false; ElMessage.success('Worker配置已保存')
}
const handleWorkerCommand = async (command:string) => {
  if (command === 'config') {
    configVisible.value = true
    await loadWorkerConfig()
  } else if (command === 'code') {
    codeVisible.value = true
    await loadWorkerFiles()
  }
}
const loadWorkerFiles = async () => { const r:any=await getWorkerFilesApi(config.value.workerMode); codeFiles.value=r.data||[]; if(codeFiles.value.length){codePath.value=codeFiles.value[0];await loadWorkerFile()} }
const loadWorkerFile = async () => { if(codePath.value){const r:any=await getWorkerFileApi(codePath.value, config.value.workerMode);codeContent.value=r.data||''} }
const saveWorkerFile = async () => { await updateWorkerFileApi(codePath.value, codeContent.value, config.value.workerMode); ElMessage.success('Worker源码已保存') }
const uploadWorkerZip = async (file: File) => {
  await uploadWorkerZipApi(file, config.value.workerMode)
  await loadWorkerFiles()
  ElMessage.success('Worker ZIP 已解压，文件列表已刷新')
  return false
}
const parsedResult = (value:any) => { try { return typeof value === 'string' ? JSON.parse(value) : value } catch { return value } }
const jobCaseIds = (job:any) => {
  const found = new Set<string>()
  const visit = (value:any, key='') => {
    value = parsedResult(value)
    if (typeof value === 'string') {
      if ((key === 'caseId' || key === 'uid') && /^[A-Z][A-Z0-9_-]{2,63}$/i.test(value)) found.add(value)
      return
    }
    if (Array.isArray(value)) return value.forEach(item => visit(item, key))
    if (!value || typeof value !== 'object') return
    Object.entries(value).forEach(([childKey, child]) => {
      if (childKey === 'caseIds' && Array.isArray(child)) child.forEach(id => visit(id, 'caseId'))
      else visit(child, childKey)
    })
  }
  ;(job?.steps || []).forEach((step:any) => visit(step.result))
  return [...found]
}
const openCase = async (caseId:string) => {
  caseSelectorVisible.value=false
  await router.push({path:`/case/detail/${encodeURIComponent(caseId)}`,query:selectedCaseJobId.value?{jobId:selectedCaseJobId.value}:undefined})
}
const openAllTaskCases = async () => { caseSelectorVisible.value=false; await router.push({path:'/case/list',query:{jobId:selectedCaseJobId.value}}) }
const loadJobs = async () => {
  if (jobsLoading.value) return
  jobsLoading.value = true
  try {
    const response: any = await getAnalysisJobsApi({
      jobType: config.value.mode,
      status: statusFilter.value || undefined,
      pageNum: page.value,
      pageSize: pageSize.value
    })
    jobs.value = response.data?.records || []
    total.value = Number(response.data?.total || 0)
    statusCounts.value = { ...statusCounts.value, ...(response.data?.statusCounts || {}) }
    // 详情弹窗关闭时不再轮询详情接口；表格刷新只更新表格数据。
    if (taskDetailVisible.value && currentJob.value) await loadDetail(currentJob.value.jobId)
  } finally {
    jobsLoading.value = false
  }
}
const loadDetail = async (jobId: string) => {
  const response: any = await getAnalysisJobApi(jobId)
  currentJob.value = response.data
  const activeStage = timelineStages(currentJob.value).find((stage:any) => stage.status==='RUNNING' || stage.status==='FAILED')
  expandedStepNames.value = activeStage ? [activeStage.id] : []
  completedCases.value = []
  selectedCaseJobId.value = currentJob.value.jobId
  if (currentJob.value.status === 'SUCCEEDED') {
    const casesResponse:any = await getCasesApi({ pageNum: 1, pageSize: 200, jobId: currentJob.value.jobId })
    completedCases.value = casesResponse.data?.records || []
  }
}
const stepResult = (step:any) => {
  const value = step?.result
  if (!value) return null
  if (typeof value === 'object') return value
  try { return JSON.parse(value) } catch { return null }
}
const stepProgress = (step:any) => stepResult(step) || {}
const isFrameworkStep = (step:any) => ['HISTORY_FRAMEWORK','FRAMEWORK','GRAPH'].includes(String(step?.stepType || '').toUpperCase())
const caseProgressRows = (step:any) => {
  const data = stepResult(step)
  if (Array.isArray(data?.caseProgress)) return data.caseProgress
  if (!Array.isArray(data?.results)) return []
  return data.results.map((item:any) => {
    const framework = item?.extractionResult?.data || item?.frameworkExtraction || {}
    const hasFramework = Array.isArray(framework.events) || Array.isArray(framework.relationships)
    const persisted = item?.persisted === true || item?.persisted === 'true'
    return {
      caseId: item?.caseId || item?.caseName || '—',
      caseName: item?.caseName || item?.caseId || '—',
      eventCount: Array.isArray(framework.events) ? framework.events.length : 0,
      relationshipCount: Array.isArray(framework.relationships) ? framework.relationships.length : 0,
      eventStatus: hasFramework ? 'SUCCEEDED' : 'SKIPPED',
      relationshipStatus: hasFramework ? 'SUCCEEDED' : 'SKIPPED',
      databaseStatus: persisted ? 'SUCCEEDED' : 'PENDING',
      graphStatus: item?.graphSnapshot ? (persisted ? 'SUCCEEDED' : 'PENDING') : 'SKIPPED'
    }
  })
}
const allJobCaseRows = (job:any) => (job?.steps || []).flatMap((step:any) => caseProgressRows(step))
const caseTotalAcrossJob = (job:any) => allJobCaseRows(job).length
const jobCaseRows = (job:any) => {
  const rows = allJobCaseRows(job)
  const unique = new Map<string,any>()
  rows.forEach((row:any) => unique.set(String(row.caseId || row.caseName || unique.size), { ...unique.get(String(row.caseId || row.caseName || unique.size)), ...row }))
  return [...unique.values()]
}
const jobRecognitionMode = (job:any) => String(job?.recognitionMode || (job?.steps || []).map((step:any) => stepResult(step)?.recognitionMode).find(Boolean) || '').toUpperCase()
const findJobStep = (job:any, matcher:(step:any)=>boolean) => (job?.steps || []).find((step:any) => matcher(step))
const aggregateCaseStage = (rows:any[], key:string, fallback='PENDING') => {
  const values = rows.map((row:any) => String(row?.[key] || '').toUpperCase()).filter(Boolean)
  if (!values.length) return fallback
  if (values.includes('FAILED')) return 'FAILED'
  if (values.includes('RUNNING')) return 'RUNNING'
  if (values.includes('PENDING')) return 'PENDING'
  return values.every((value:string) => value==='SKIPPED') ? 'SKIPPED' : 'SUCCEEDED'
}
const timelineStages = (job:any) => {
  const rows = jobCaseRows(job)
  const validation = findJobStep(job, (step:any) => String(step.stepType || '').toUpperCase()==='VALIDATE' || String(step.stepName || '').includes('校验'))
  const persistence = findJobStep(job, (step:any) => String(step.stepName || '').includes('入库') && !isFrameworkStep(step))
  const framework = findJobStep(job, (step:any) => isFrameworkStep(step) || String(step.stepName || '').includes('逐案例抽取') || String(step.stepName || '').includes('历史案例抽取'))
  const mode = jobRecognitionMode(job)
  const progress = stepProgress(framework)
  const currentStage = String(progress.currentStage || '')
  const currentStatus = String(progress.currentStageStatus || '').toUpperCase()
  const stageFallback = (id:string) => {
    if (job?.status==='FAILED' && framework?.status==='FAILED') return 'FAILED'
    if (job?.status==='SUCCEEDED') return mode==='NEW' && ['event','relationship','graph'].includes(id) ? 'SKIPPED' : 'SUCCEEDED'
    const order:Record<string,number> = { event:1, relationship:2, database:3, graph:4 }
    const current:Record<string,string> = { '06_event_extraction':'event', '07_relationship_extraction':'relationship', PERSISTENCE:'database', PERSISTED:'graph', GRAPH_WRITE:'graph' }
    const activeId = current[currentStage]
    if (activeId && order[id] < order[activeId]) return 'SUCCEEDED'
    if (activeId===id) return currentStatus || 'RUNNING'
    return framework?.status==='FAILED' ? 'FAILED' : framework?.status==='PENDING' ? 'PENDING' : 'PENDING'
  }
  const inferredStatus = (id:string,key:string) => {
    if (id==='event' || id==='relationship') {
      if (mode==='NEW') return 'SKIPPED'
      return aggregateCaseStage(rows,key,stageFallback(id))
    }
    if (id==='graph' && mode==='NEW') return aggregateCaseStage(rows,key,'SKIPPED')
    const fallback = id==='database' ? (persistence?.status || stageFallback(id)) : stageFallback(id)
    return aggregateCaseStage(rows,key,fallback)
  }
  const progressFor = (status:string, stageRows:any[], key:string, sourceStep:any) => {
    if (sourceStep) return Number(sourceStep.progress || 0)
    if (status==='SUCCEEDED' || status==='SKIPPED') return 100
    if (status==='RUNNING' && stageRows.length) return Math.round(stageRows.filter((row:any) => String(row?.[key] || '').toUpperCase()==='SUCCEEDED').length / stageRows.length * 100)
    return 0
  }
  const stage = (id:string,name:string,status:string,options:any={}) => {
    const stageRows = options.rows || []
    const sourceStep = options.sourceStep || null
    let timeLabel = options.timeLabel || (sourceStep ? stepTimeLabel(sourceStep) : '执行时间未单独记录')
    if (status==='SKIPPED') timeLabel='本任务不执行'
    else if (status==='RUNNING' && options.activeCase) timeLabel=`进行中 · 第 ${options.activeCase.caseIndex || '—'} / ${options.activeCase.caseTotal || rows.length || '—'} 个案例`
    return { id,name,status,rows:stageRows,statusKey:options.statusKey || '',countKey:options.countKey || '',countLabel:options.countLabel || '数量',sourceStep,dataStep:options.dataStep || sourceStep,timeLabel,detail:options.detail || '',progress:progressFor(status,stageRows,options.statusKey,sourceStep) }
  }
  const phaseStatus = (id:string,key:string) => inferredStatus(id,key)
  const historical = mode==='HISTORICAL' || (!mode && Boolean(framework))
  const activeCase = progress.currentCase || null
  return [
    stage('receive','文件接收与安全检查',job?.sourceFileName || job?.createdAt ? 'SUCCEEDED' : 'PENDING',{ detail:'上传接口已接收文件，并执行文件类型、大小等上传限制校验；安全扫描结果没有单独记录。',timeLabel:job?.createdAt ? `上传任务创建于 ${stepTimeLabel({startedAt:job.createdAt})}` : '上传时间未记录' }),
    stage('parse','文件解析与字段映射',validation?.status || (job?.status==='SUCCEEDED' ? 'SUCCEEDED' : 'PENDING'),{ sourceStep:null,detail:'文件解析与字段映射随上传校验流程完成；系统没有单独保存本阶段的起止时间。',timeLabel:validation ? '状态随数据校验记录 · 耗时未单独记录' : '未单独记录阶段状态' }),
    stage('validate','数据校验',validation?.status || 'PENDING',{ sourceStep:validation,detail:validation ? '展示上传任务保存的数据校验状态与校验结果。' : '该任务没有独立的数据校验记录。' }),
    stage('database','案例入库',phaseStatus('database','databaseStatus'),{ sourceStep:persistence,rows,statusKey:'databaseStatus',detail:'按逐案例入库状态汇总；展开后可查看每个案例的入库状态。' }),
    stage('event','事件抽取',phaseStatus('event','eventStatus'),{ rows,statusKey:'eventStatus',countKey:'eventCount',countLabel:'事件数',dataStep:framework,activeCase:currentStage==='06_event_extraction' ? activeCase : null,detail:historical ? '按逐案例事件抽取进度汇总；展开可查看每个案例的事件数量与状态。' : '新增案例任务按业务流程跳过历史事件抽取。' }),
    stage('relationship','关系抽取',phaseStatus('relationship','relationshipStatus'),{ rows,statusKey:'relationshipStatus',countKey:'relationshipCount',countLabel:'关系数',dataStep:framework,activeCase:currentStage==='07_relationship_extraction' ? activeCase : null,detail:historical ? '按逐案例关系抽取进度汇总；展开可查看每个案例的关系数量与状态。' : '新增案例任务按业务流程跳过历史关系抽取。' }),
    stage('graph','自动入图',phaseStatus('graph','graphStatus'),{ rows,statusKey:'graphStatus',dataStep:framework,activeCase:['GRAPH_WRITE','PERSISTED'].includes(currentStage) ? activeCase : null,detail:'按逐案例图谱写入状态汇总；展开可查看每个案例的入图结果。' })
  ]
}
const timelineProgressHint = (job:any) => {
  const stages = timelineStages(job)
  const running = stages.find((item:any) => item.status==='RUNNING')
  if (running) return `当前：${running.name} · ${stages.filter((item:any) => item.status==='SUCCEEDED' || item.status==='SKIPPED').length}/${stages.length} 个阶段已完成`
  return `${stages.filter((item:any) => item.status==='SUCCEEDED' || item.status==='SKIPPED').length}/${stages.length} 个阶段已完成`
}
const processingModeText = (job:any) => job?.processingMode==='BATCH' ? '批量上传' : job?.processingMode==='SINGLE' ? '单案例上传' : '—'
const sourceFileName = (job:any) => job?.sourceFileName || job?.batchNo || (job?.batchId ? `批次 #${job.batchId}` : '案例文件')
const fileExtension = (job:any) => {
  const name = String(job?.sourceFileName || '')
  const extension = name.includes('.') ? name.split('.').pop()?.toUpperCase() : ''
  return extension && extension.length <= 5 ? extension : '文'
}
const timelineMarker = (status:string) => status==='SUCCEEDED' ? '✓' : status==='RUNNING' ? '···' : status==='FAILED' ? '!' : '·'
const stepTimeLabel = (step:any) => {
  const clock = (value:any) => {
    if (!value) return ''
    const parsed = new Date(value)
    return Number.isNaN(parsed.getTime()) ? String(value).replace('T',' ').slice(-8) : parsed.toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false})
  }
  const start = clock(step?.startedAt)
  const end = clock(step?.completedAt)
  if (!start) return step?.status==='PENDING' ? '等待执行' : '时间未记录'
  if (!end) return `${start} · 进行中`
  const startMs = new Date(step.startedAt).getTime()
  const endMs = new Date(step.completedAt).getTime()
  const duration = Number.isFinite(startMs) && Number.isFinite(endMs) ? Math.max(0, Math.round((endMs-startMs)/1000)) : null
  return `${start} — ${end}${duration!==null ? ` · ${duration} 秒` : ''}`
}
const stageResultCount = (job:any, key:string, status:string) => jobCaseRows(job).filter((row:any) => String(row?.[key] || '').toUpperCase()===status).length
const resultReadCount = (job:any) => jobCaseRows(job).length || Number(job?.caseCount || 0)
const skippedCaseCount = (job:any) => jobCaseRows(job).filter((row:any) => String(row?.databaseStatus || '').toUpperCase()==='SKIPPED').length
const failedCaseCount = (job:any) => jobCaseRows(job).filter((row:any) => ['eventStatus','relationshipStatus','databaseStatus','graphStatus'].some((key:string) => String(row?.[key] || '').toUpperCase()==='FAILED')).length
const caseStageSummary = (job:any) => {
  const total = jobCaseRows(job).length
  return `事件抽取 ${stageResultCount(job,'eventStatus','SUCCEEDED')}/${total} · 关系抽取 ${stageResultCount(job,'relationshipStatus','SUCCEEDED')}/${total} · 自动入图 ${stageResultCount(job,'graphStatus','SUCCEEDED')}/${total}`
}
const stageName = (stage:string) => ({
  '06_event_extraction':'事件抽取',
  '07_relationship_extraction':'关系抽取',
  PERSISTENCE:'案例入库与图谱写入',
  GRAPH_WRITE:'图谱写入',
  PERSISTED:'案例入库与图谱写入'
} as Record<string,string>)[stage] || stage || '等待阶段信息'
const stageStatusText = (status:string) => ({
  SUCCEEDED:'已完成', RUNNING:'处理中', FAILED:'失败', PENDING:'待处理',
  SKIPPED:'已跳过', CANCELLED:'已取消'
} as Record<string,string>)[String(status || '').toUpperCase()] || '等待数据'
const stepSummaryEntries = (step:any) => {
  const data = stepResult(step)
  if (!data || typeof data !== 'object') return []
  const labels:Record<string,string> = {
    stage:'执行阶段', validated:'校验结果', caseCount:'案例数',
    persistedCaseCount:'已入库案例', completedAt:'完成时间', sourceSha256:'文件摘要'
  }
  return Object.entries(data)
    .filter(([key,value]) => labels[key] && (typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean'))
    .map(([key,value]) => ({ label:labels[key], value:key==='validated'?(value?'通过':'未通过'):String(value) }))
}
const batchText = (job:any) => job.sourceFileName
  ? `${job.processingMode==='BATCH'?'批处理':'单案例'} · ${job.sourceFileName}`
  : job.batchNo ? `${job.batchNo}（#${job.batchId}）` : job.batchId ? `批次 #${job.batchId}` : '直接上传 / 文本输入'
const openTaskDetail=async(job:any)=>{
  await loadDetail(job.jobId)
  taskDetailVisible.value=true
}
const createJob = async () => {
  if (!form.jobName.trim() || !form.bankCode.trim() || !form.scenarioCode) return ElMessage.warning('请填写任务名称、银行编码并选择业务场景')
  if(dataValidation.value?.status!=='PASSED'||validatedInputSignature.value!==currentInputSignature.value){
    createStep.value=1
    return ElMessage.warning('输入数据尚未完成一致性校验，请重新执行数据校验')
  }
  saving.value = true
  try {
    if(structuredCaseMode.value==='SINGLE'){
      if(!structuredCaseFiles.basicInfo||!structuredCaseFiles.customers)throw new Error('请选择 basic_info.json 和 customers.json')
      const uploaded:any=isAntiFraud.value
        ? await uploadAntiFraudCaseFilesApi({
            basicInfo:structuredCaseFiles.basicInfo,
            customers:structuredCaseFiles.customers,
            accounts:structuredCaseFiles.accounts as File,
            devices:structuredCaseFiles.devices as File,
            recognitionMode:structuredRecognitionMode.value,
            eventChain:structuredCaseFiles.eventChain,
            textAnalysis:structuredCaseFiles.textAnalysis
          })
        : await uploadStructuredCaseFilesApi({
            basicInfo:structuredCaseFiles.basicInfo,
            customers:structuredCaseFiles.customers,
            recognitionMode:structuredRecognitionMode.value,
            analysisTexts:structuredCaseFiles.analysisTexts,
            accounts:structuredCaseFiles.accounts,
            otherEntities:structuredCaseFiles.otherEntities
          })
      structuredCaseUploadToken.value=uploaded.data?.uploadToken||''
    }
    if(!structuredCaseUploadToken.value)throw new Error('案例文件上传完成但未返回上传凭据')
    const inputParams={
      workflow:isAntiFraud.value?'ANTI_FRAUD_CASE_PIPELINE':'XI_AN_CASE_PIPELINE',processingMode:structuredCaseMode.value,recognitionMode:structuredRecognitionMode.value,
      uploadToken:structuredCaseUploadToken.value,caseCount:structuredCaseMode.value==='BATCH'?structuredBatchCaseCount.value:1,
      sourceFileName:structuredCaseMode.value==='BATCH' ? structuredBatchFile.value?.name
        : isAntiFraud.value
          ? `basic_info.json + customers.json + accounts.json + devices.json + ${structuredRecognitionMode.value==='NEW'?'event_chain.json':'text_analysis.json'}`
          : `basic_info.json + customers.json${structuredCaseFiles.analysisTexts?' + analysis_texts.json':''}${structuredCaseFiles.accounts?' + accounts.json':''}${structuredCaseFiles.otherEntities?' + other_entities.json':''}`
    }
    const response: any = await createAnalysisJobApi({
      ...form, jobType: config.value.mode,
      inputParams: JSON.stringify(inputParams),
      steps: config.value.steps.map((stepName, index) => ({ stepName, stepType: config.value.stepCodes[index] }))
    })
    if(response?.code!==200||!response?.data)throw new Error(response?.message||'任务创建失败')
    createVisible.value = false
    createStep.value = 1
    ElMessage.success('上传任务已创建并自动开始执行')
    currentJob.value = null
    await loadJobs()
    if (response.data?.jobCount) {
      ElMessage.success(`已按文档拆分创建 ${response.data.jobCount} 个独立案例上传任务`)
      if(response.data.jobIds?.length)await loadDetail(response.data.jobIds[0])
    } else {
      if(!response.data?.jobId)throw new Error('任务已提交，但后端未返回任务编号')
      await loadDetail(response.data.jobId)
    }
  } catch(error:any) {
    ElMessage.error(error?.message||'任务创建失败')
  } finally { saving.value = false }
}
const openCreateTask=()=>{
  createStep.value=1
  form.scenarioCode='AML'
  structuredCaseMode.value='SINGLE'
  structuredRecognitionMode.value='NEW'
  structuredBatchFile.value=null
  structuredBatchCaseCount.value=0
  structuredCaseFiles.basicInfo=null
  structuredCaseFiles.customers=null
  structuredCaseFiles.analysisTexts=null
  structuredCaseFiles.accounts=null
  structuredCaseFiles.otherEntities=null
  structuredCaseFiles.devices=null
  structuredCaseFiles.eventChain=null
  structuredCaseFiles.textAnalysis=null
  structuredCaseUploadToken.value=''
  dataValidation.value=null
  validatedInputSignature.value=''
  const now=new Date()
  const pad=(value:number)=>String(value).padStart(2,'0')
  const timestamp=`${now.getFullYear()}${pad(now.getMonth()+1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`
  form.jobName=`${config.value.title}-${timestamp}`
  createVisible.value=true
}
const openCurrentJobCases=async()=>{
  if(completedCases.value.length)return openCase(completedCases.value[0].caseId)
  const ids=jobCaseIds(currentJob.value)
  if(ids.length)return openCase(ids[0])
  ElMessage.info('当前任务尚未生成可查看案例')
}
const openTaskResult=async(job:any)=>{
  await loadDetail(job.jobId)
  await openCurrentJobCases()
}
const stopJob=async(job:any)=>{
  await cancelAnalysisJobApi(job.jobId)
  ElMessage.success('任务已停止')
  await loadJobs()
}
const retryJob=async(job:any)=>{
  await retryAnalysisJobApi(job.jobId)
  ElMessage.success('任务已重新进入执行队列')
  await loadJobs()
}
const removeJob=async(job:any)=>{
  await ElMessageBox.confirm(
    `确认彻底删除任务“${job.jobName}”吗？该任务独占的案例、信号、事件/事理、证据关系及图谱子图将同步物理删除；输入批次和原始文件保留。`,
    '彻底删除任务',
    {type:'warning',confirmButtonText:'彻底删除',cancelButtonText:'取消'}
  )
  const result:any = await deleteAnalysisJobApi(job.jobId)
  if(currentJob.value?.jobId===job.jobId)currentJob.value=null
  const summary = result?.data || result || {}
  ElMessage.success(`任务已彻底删除，同时删除 ${summary.deletedCaseCount || 0} 个案例、${summary.deletedSignalCount || 0} 条信号`)
  await loadJobs()
}
const handleTaskCommand=async(command:string,job:any)=>{
  if(command==='view')return openTaskResult(job)
  if(command==='stop')return stopJob(job)
  if(command==='retry')return retryJob(job)
  if(command==='delete')return removeJob(job)
}
const countByStatus = (status: string) => Number(statusCounts.value[status] || 0)
const filterByStatus = () => { if (page.value === 1) loadJobs(); else page.value = 1 }
const statusText = (status: string) => ({ PENDING:'待启动', RUNNING:'运行中', SUCCEEDED:'已完成', FAILED:'失败', CANCELLED:'已取消' } as Record<string,string>)[status] || status
const statusType = (status: string) => ({ PENDING:'info', RUNNING:'warning', SUCCEEDED:'success', FAILED:'danger', CANCELLED:'info' } as Record<string,any>)[status] || 'info'

watch([page, pageSize], loadJobs)
let pollTimer: number | undefined
onMounted(() => {
  loadJobs()
  pollTimer = window.setInterval(() => {
    if (!document.hidden && jobs.value.some(job => ['PENDING', 'RUNNING'].includes(job.status))) loadJobs()
  }, 4000)
})
onUnmounted(() => pollTimer && window.clearInterval(pollTimer))
</script>

<style scoped>
.job-failure{margin-top:14px}.step-error{display:block;color:#b42318;white-space:normal;word-break:break-word}.failure-history-title{margin:18px 0 10px;color:#334155}
.trajectory-header{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:0 4px 18px;border-bottom:1px solid #e6ebf2}
.trajectory-header h2{margin:0;color:#263344;font-size:22px;font-weight:650}.trajectory-header p{margin:7px 0 0;color:#77869a;font-size:13px;overflow-wrap:anywhere}.trajectory-header p span{padding:0 5px;color:#aeb8c5}
.trajectory-status{display:flex;align-items:center;gap:8px;padding:9px 14px;border-radius:22px;background:#eaf3ff;color:#347fd1;white-space:nowrap;font-size:13px}.trajectory-status i{width:8px;height:8px;border-radius:50%;background:#3986e3}.trajectory-status.status-succeeded{background:#edf8f1;color:#33865b}.trajectory-status.status-succeeded i{background:#42a36c}.trajectory-status.status-failed{background:#fff0f0;color:#c45656}.trajectory-status.status-failed i{background:#d85b5b}
.upload-progress-card{display:flex;align-items:center;gap:14px;margin:16px 0 18px;padding:16px 18px;border-radius:10px;background:#f5f8fc}.upload-file-icon{display:grid;place-items:center;flex:0 0 42px;height:42px;border-radius:9px;background:#e4effd;color:#347fd1;font-size:12px;font-weight:700}.upload-file-info{display:flex;flex:1;min-width:0;flex-direction:column;gap:5px}.upload-file-info strong{overflow:hidden;color:#28384c;text-overflow:ellipsis;white-space:nowrap;font-size:15px}.upload-file-info>span{overflow:hidden;color:#7b899c;text-overflow:ellipsis;white-space:nowrap;font-size:12px}.upload-file-info :deep(.el-progress){max-width:520px;margin-top:3px}.upload-current-count{display:flex;min-width:200px;flex-direction:column;gap:4px;color:#4384cd;text-align:right;font-size:13px}.upload-current-count strong{font-size:19px;font-weight:650}
.trajectory-layout{display:grid;grid-template-columns:minmax(0,1.65fr) minmax(260px,.9fr);gap:22px}.trajectory-main{min-width:0}.trajectory-section-heading,.case-progress-heading{display:flex;align-items:baseline;justify-content:space-between;gap:12px;flex-wrap:wrap;margin-bottom:8px}.trajectory-section-heading strong,.case-progress-heading strong{color:#2e3949;font-size:15px}.trajectory-section-heading>span,.case-progress-heading>span{color:#8996a8;font-size:12px}
:deep(.job-detail-dialog .el-dialog__body){max-height:calc(100vh - 170px);overflow:auto;padding:12px 22px 16px}
:deep(.job-detail-dialog .el-dialog__header){height:0;padding:0}
:deep(.job-detail-dialog .el-dialog__headerbtn){top:17px;right:18px;z-index:3}
.timeline-collapse{border:0}.timeline-collapse :deep(.el-collapse-item){border:0}.timeline-collapse :deep(.el-collapse-item__header){height:auto;min-height:68px;line-height:1.4;border:0;background:transparent}.timeline-collapse :deep(.el-collapse-item__wrap){border:0}.timeline-collapse :deep(.el-collapse-item__content){padding:0 0 18px}.timeline-step{position:relative;display:grid;grid-template-columns:34px minmax(0,1fr);column-gap:10px}.timeline-marker{position:relative;display:flex;justify-content:center}.timeline-marker span{z-index:1;display:grid;place-items:center;width:27px;height:27px;margin-top:18px;border-radius:50%;background:#e9f6ee;color:#429a69;font-size:14px;font-weight:700}.timeline-marker i{position:absolute;top:42px;bottom:-2px;width:2px;background:#e5ebf2}.timeline-running .timeline-marker span{background:#e8f2ff;color:#3986e3;font-size:11px}.timeline-pending .timeline-marker span{background:#f1f4f8;color:#9aa6b5}.timeline-failed .timeline-marker span{background:#fff0f0;color:#cc5555}.timeline-step-content{width:100%;padding:13px 4px 8px}.timeline-step-heading{display:flex;align-items:center;gap:9px;min-width:0}.timeline-step-heading strong{color:#303b4b;font-size:14px}.timeline-time{margin-left:auto;color:#8794a7;font-size:12px;text-align:right;white-space:nowrap}.timeline-step-expand{margin-top:8px;color:#4388d4;font-size:12px}.timeline-step-expand span{padding-left:5px}.step-drawer-body{padding:2px 4px 0}.step-drawer-meta{display:flex;gap:20px;flex-wrap:wrap;margin:9px 0;color:#78879b;font-size:12px}.step-inline-error{margin-top:10px}.current-case-progress{padding:11px 13px;margin:10px 0;background:#f4f8fd;border-radius:8px}.current-case-heading,.case-progress-heading{display:flex;align-items:center;justify-content:space-between;gap:10px;flex-wrap:wrap}.current-case-heading strong{font-size:13px;color:#303846}.current-case-line{margin-top:7px;color:#536274;font-size:13px;line-height:1.6;overflow-wrap:anywhere}.current-case-line span{padding:0 5px;color:#a0aab7}
.trajectory-aside{display:flex;flex-direction:column;gap:13px;padding-top:31px}.trajectory-side-card{padding:15px 17px;border-radius:10px;background:#f5f8fc}.trajectory-side-card h3{margin:0 0 12px;color:#303b4a;font-size:15px}.result-line{display:flex;align-items:center;justify-content:space-between;gap:12px;padding:10px 0;border-bottom:1px solid #e2e8f0;font-size:12px}.result-line:last-child{border-bottom:0}.result-line span{color:#7c8b9f}.result-line strong{max-width:65%;color:#344255;text-align:right;font-size:13px;font-weight:600;overflow-wrap:anywhere}.trajectory-tip{padding:13px 15px;border-left:4px solid #429b69;border-radius:0 8px 8px 0;background:#eff8f2;color:#476b56;font-size:12px;line-height:1.7}.trajectory-tip.tip-failed{border-left-color:#cc5555;background:#fff3f3;color:#985050}
.trajectory-case-progress{margin-top:12px;padding-top:15px;border-top:1px solid #e4eaf1}.case-progress-heading{margin-bottom:10px}.case-id-cell{font-weight:500;color:#303846;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.case-id-sub,.case-count{display:block;margin-top:3px;color:#8b97a7;font-size:11px;overflow-wrap:anywhere}.step-result-summary{margin-top:12px}
@media(max-width:900px){.trajectory-layout{grid-template-columns:1fr}.trajectory-aside{padding-top:0;display:grid;grid-template-columns:1fr 1fr}.trajectory-tip{grid-column:1/-1}.upload-current-count{min-width:140px}.timeline-time{white-space:normal}}
.config-value { min-width: 420px; }
.upload-tip { color: #909399; margin-left: 10px; font-size: 12px; }
.batch-hint { color:#909399; font-size:12px; line-height:1.5; margin-top:6px; }
.model-picker { margin: 14px 0 4px; }
:deep(.config-value) { width: 760px; }
:deep(.el-table .cell) { padding-left: 8px; padding-right: 8px; }
</style>

<style scoped>
.pipeline-page { display:flex; width:100%; min-width:0; flex-direction:column; }
.page-header,.card-header { display:flex; justify-content:space-between; align-items:center; gap:16px; }
.page-header { order:0; }
.summary { order:1; }
.job-card { order:2; }
.page-header { margin-bottom:18px; }
.page-header h2 { margin:0 0 6px; }
.page-header p,.job-caption { color:#909399; }
.summary,.job-card { margin-bottom:16px; }
.job-card,.job-table { width:100%; }
.status-progress { display:flex; align-items:center; gap:6px; min-width:0; }
.status-progress :deep(.el-tag) { flex:0 0 auto; }
.status-progress :deep(.el-progress) { flex:0 0 72px; width:72px; min-width:0; }
.status-progress :deep(.el-progress__text) { min-width:30px; font-size:11px!important; }
.created-time { max-width:154px; margin-top:7px; color:#64748b; font-size:12px; line-height:1.2; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; }
.compact-cell { display:block; width:100%; overflow:hidden; white-space:nowrap; text-overflow:ellipsis; }
.muted { color:#909399; }
.record-actions { display:flex; align-items:center; gap:12px; }
.header-actions { display:flex; align-items:center; gap:8px; }
.table-actions{display:flex;align-items:center;justify-content:flex-end;gap:10px}.more-arrow{margin-left:3px}
.status-summary { color:#64748b; font-size:12px; white-space:nowrap; }
.creation-guide{display:grid;grid-template-columns:repeat(4,1fr);margin:0 20px 26px}.creation-guide>div{position:relative;display:flex;align-items:center;justify-content:center;gap:8px;color:#94a3b8;font-size:13px}.creation-guide>div:not(:last-child):after{position:absolute;top:14px;left:calc(50% + 54px);right:calc(-50% + 54px);height:2px;background:#dbe3ee;content:''}.creation-guide i{position:relative;z-index:1;display:inline-flex;width:28px;height:28px;align-items:center;justify-content:center;border:2px solid #cbd5e1;border-radius:50%;background:#fff;font-style:normal;font-weight:700}.creation-guide .completed{color:#15803d}.creation-guide .completed i{border-color:#22c55e;background:#22c55e;color:#fff}.creation-guide .completed:after{background:#22c55e!important}.creation-guide .current{color:#1d4ed8;font-weight:700}.creation-guide .current i{border-color:#2563eb;color:#1d4ed8;box-shadow:0 0 0 4px #dbeafe}
.selected-file{display:flex;align-items:center;gap:8px;width:100%;margin-top:8px;padding:7px 10px;border:1px solid #bbf7d0;border-radius:6px;background:#f0fdf4}.selected-file span{min-width:0;overflow:hidden;white-space:nowrap;text-overflow:ellipsis;color:#166534}.selected-file em{margin-left:auto;color:#64748b;font-size:12px;font-style:normal;white-space:nowrap}.validation-result{margin-top:14px}.fingerprint{display:block;overflow-wrap:anywhere;color:#475569;font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:12px}
.recognition-mode-buttons{display:flex}.recognition-mode-buttons :deep(.el-radio-button__inner){min-width:148px}
.create-confirmation{margin-top:8px}.detail-steps{margin-top:18px}
:deep(.create-task-dialog .el-dialog__body){min-height:330px}
.case-selector-list { display:grid; grid-template-columns:repeat(auto-fill,minmax(260px,1fr)); gap:4px 12px; max-height:440px; overflow:auto; margin-top:14px; }
.case-selector-list .el-button { justify-content:flex-start; margin:0; }
:deep(.job-card .el-card__body) { width:100%; box-sizing:border-box; overflow:hidden; }
:deep(.job-table .el-table__inner-wrapper) { width:100%; }
@media (max-width: 1100px) {
  .page-header { align-items:flex-start; flex-wrap:wrap; }
  .summary :deep(.el-col) { min-width:50%; margin-bottom:10px; }
}
</style>
