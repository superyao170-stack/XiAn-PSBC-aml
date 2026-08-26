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

## 规则治理

`data/kb/fraud_rules.json` 是版本化的分渠道规则库。规则命中只生成待核验风险事件，不是诈骗或犯罪认定；其中数值阈值是机构内部默认值，投产前应回测和审批。

## JSON-over-stdio

```bash
python framework_extraction.py <<'JSON'
{"sourcePath":"/path/to/case","processingMode":"SINGLE","recognitionMode":"NEW","validateOnly":true}
JSON
```
