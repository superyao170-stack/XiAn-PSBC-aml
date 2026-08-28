# 反欺诈案例识别 Worker

该目录与 `structured-case-identification/`（现有反洗钱结构化流程）平行，业务规则、输入契约和提示词互不混用；两者只复用成熟的底层框架抽取与相似度算法模块。

## 单案例输入

公共必填文件：`basic_info.json`、`customers.json`、`accounts.json`、`devices.json`。

- 新增案例（`NEW`）：另传 `event_chain.json`。Worker 先依据 `basic_info.渠道` 选择规则生成风险事件链，再通过反欺诈版 `aml-analysis-workflow` 生成分析文本。
- 历史案例（`HISTORICAL`）：另传 `text_analysis.json`，其中 `text` 为已有分析报告，直接进入框架抽取。

`basic_info.json` 的 `渠道` 可选值见 `templates/basic_info.json`。旧数据缺少该字段时会根据案例触发点推断并同时写入 `渠道` 与 `channel`。

账户、客户、设备和案例信息直接映射；LLM 仅抽取 `events` 与 `relationships`。相似度匹配复用现有 BGE + reranker + GED 实现，最多返回 5 条。

## 并行分析编排

反欺诈在 `fraud_analysis_workflow.py` 中维护独立事实索引和节点路由，复用反洗钱工作流的通用并行调度、证据校验、复核和定向重写能力，不复用反洗钱的场景事实分组。

- 第一阶段并行执行客户/账户/设备概览、资金链、案例主体、资金与操作、交易对手与外部线索 5 个节点。
- 第二阶段并行执行客户主体综合分析和案例综合分析 2 个节点。
- 最后生成发现情况，并对全部 12 个段落统一复核。

风险事件链会按资金、交易对手、账户操作、外部权威线索和时间等反欺诈维度建立可引用事实，确保每个非 `not_assessable` 段落都能回溯到原始事件证据。

## 风险知识库与规则治理

反欺诈将原始链路规则和语义事件知识库分开治理：

- `data/kb/fraud_rules.json` 是版本化的分渠道命中规则库，只负责把新增案例的原始 `event_chain` 转换为可审计风险事件链。
- `data/kb/fraud_risk_event_knowledge_base.json` 是与反洗钱知识库同契约的语义风险事件库，只保留 `id`、`name`、`rule`、`category` 四字段，供事件候选召回、规则复核和已有/新增事件标记使用。

知识库参考《反电信网络诈骗法》、人民银行银发〔2016〕261号附件中的开户、转账和取现可疑特征，以及 FATF、INTERPOL 关于网络欺诈、钱骡账户和快速资金转移的风险材料。规则命中和知识库分类都只表示待核验风险，不是诈骗或犯罪认定；数值阈值是机构内部默认值，投产前应回测和审批。

默认直接读取治理 JSON。若生产环境使用 Qdrant，应将反欺诈知识迁移到独立集合 `fraud_risk_event_knowledge_base`，并通过 `ANTI_FRAUD_QDRANT_COLLECTION` 指定；不得与反洗钱默认集合混用：

```bash
python workers/structured-case-identification/scripts/migrate_kb_to_qdrant.py \
  --kb workers/anti-fraud-case-identification/data/kb/fraud_risk_event_knowledge_base.json \
  --url http://127.0.0.1:6333 \
  --collection fraud_risk_event_knowledge_base
```

## 分层关系抽取

关系类型和执行顺序与反洗钱共享同一契约，并逐层串行执行：

1. 确定性结构层：`涉及关系`、`包含关系`、`来源关系`。
2. 实体—实体层：`持有关系`、`社会关系`。
3. 实体—事件层：`参与关系`、`涉及关系`。
4. 事件—事件层：`顺承关系`、`上下位关系`、`应对关系`。

每一层只接受当前层允许的节点组合和关系类型，最终按“关系类型 + 源节点ID + 目标节点ID”去重。最终案例的 `processing_metadata` 会保留知识库版本、分层顺序、每层执行报告和完整关系类型契约，便于验证链路是否按层执行。

## JSON-over-stdio

```bash
python framework_extraction.py <<'JSON'
{"sourcePath":"/path/to/case","processingMode":"SINGLE","recognitionMode":"NEW","validateOnly":true}
JSON
```
