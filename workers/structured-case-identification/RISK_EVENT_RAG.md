# 风险事件 BGE-M3 RAG

## 运行链路

1. 从案件来源事实和分析报告中识别风险候选事实；案件来源事实优先，统计汇总不能覆盖具体资金链路。
2. 每个候选使用 `BAAI/bge-m3` 召回知识库 Top-K；文档内使用最大分数与 RRF 合并，只把相关条目的 `id/name/rule/category` 注入 Prompt。
3. 生成模型抽取完整业务事件，不允许用相似度直接判定已有类型。
4. 独立规则裁决调用逐事件输出 `EXISTING / NEW / NOT_EVENT`；服务端校验已有 ID 必须属于本次召回集合。
5. 对政府采购回扣、国有资产侵占、行贿受贿、涉赌、画像不匹配等证据门槛型类型执行服务端直接证据硬门。
6. 按类型、实体集合、日期和资金机制跨报告去重；最终重新生成唯一事件 ID。
7. 若模型错误返回空数组，而 `basic_info.case_facts` 包含明确主体和完整资金链路，则生成一个带“案件来源材料记载、待核验”限定的新增事件，避免无解释的 0 条。

## 后端选择

默认直接读取治理 JSON，并在首次检索时用 BGE-M3 建立进程内向量缓存。模型运行时不可用时会显式记录 `json+lexical-fallback`，不会伪装成向量召回。

生产环境可使用 Qdrant dense+sparse 混合检索：

```bash
python workers/structured-case-identification/scripts/migrate_kb_to_qdrant.py \
  --url http://127.0.0.1:6333

export KB_BACKEND=qdrant
export QDRANT_URL=http://127.0.0.1:6333
export QDRANT_COLLECTION=risk_event_knowledge_base
export BGE_M3_MODEL=BAAI/bge-m3
```

也可用 `--path /absolute/qdrant/path` 构建本地 Qdrant。知识库 JSON 仍是治理源；修改版本后重新执行迁移脚本刷新向量。

## 审计字段

`event_extraction_calls` 记录每份报告的召回查询、注入候选 ID、模型原始事件数、接受数、逐条过滤原因及规则裁决状态。`knowledge_base_recall_results` 保留候选级 Top-K、分数和实际检索方法。
