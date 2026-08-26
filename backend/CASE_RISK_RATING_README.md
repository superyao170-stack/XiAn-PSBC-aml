# 案例默认风险评级（EXPLAINABLE_CASE_RISK_V1）

## 用途与边界

该模型只为“复核审批”提供低/中/高风险的默认建议，不自动作出监管或客户处置决定。审批人必须从低风险、中风险、高风险中确认最终等级，也可以覆盖系统建议；最终选择写入 `cf_risk_case`，建议分及分项依据保存在 `case_processing_pool.risk_breakdown`。

设计采用可解释的风险导向方法，而不是让大模型直接输出等级。依据包括：

- FATF 的风险导向原则：识别、理解风险，并采取与风险水平相称的措施。[FATF Risk-Based Approach for the Banking Sector](https://www.fatf-gafi.org/content/dam/fatf-gafi/guidance/Risk-Based-Approach-Banking-Sector.pdf.coredownload.pdf)
- EBA 指引要求综合客户、产品/服务、交易、渠道及地域等风险因素，并保留可说明的评估过程。[EBA Guidelines on ML/TF risk factors](https://www.eba.europa.eu/legacy/regulation-and-policy/regulatory-activities/anti-money-laundering-and-countering-financing-1?version=2021)
- IBM AMLSim 提供 fan-in、fan-out、cycle、scatter-gather 等交易网络类型，支持将事件及关系结构复杂度作为可疑程度线索，但不能单独证明违法。[IBM AMLSim](https://github.com/IBM/AMLSim)
- 图特征与实体特征结合可用于 AML 告警分流，因此模型同时考虑事件、主体、账户、关系与相似案例。[Anti-Money Laundering Alert Optimization Using Machine Learning with Graphs](https://arxiv.org/abs/2112.07508)

## 评分项

总分 0—100，代码位于 `CaseRiskRatingService`：

| 评分项 | 上限 | 当前可用证据 |
|---|---:|---|
| 事件严重性 | 30 | 抽取事件数量，每项 4.5 分并封顶 |
| 风险类型命中 | 22 | 洗钱、诈骗、涉案、团伙、地下钱庄、跑分、黑名单、司法、制裁、高风险等可解释关键词 |
| 网络复杂度 | 16 | 关系数量及关系相对事件的密度 |
| 主体暴露面 | 12 | 客户、账户、其他实体数量 |
| 相似案例风险 | 15 | 与已审核案例的最高相似度 |
| 来源风险提示 | 5 | 上传材料或框架中的原始风险提示 |

映射阈值：

- `0—39.99`：低风险（LOW）
- `40—69.99`：中风险（MEDIUM）
- `70—100`：高风险（HIGH）

## 审批与回退

1. 系统在相似匹配完成后计算建议分和建议等级。
2. 历史案例没有相似匹配结果时，使用框架抽取结果计算建议；相似案例项按 0 分处理。
3. 审批人必须选择最终等级，不允许以“未定级”审核通过。
4. 若审批人编辑可疑报告，案例清除旧框架、旧相似结果和旧建议分，退回“待框架抽取”。
5. 相似案例人工排序永久写入 `case_similarity_ranking.final_rank`；按人工名次计算归一化倒数名次权重并写入 `normalized_weight`，全景图谱直接读取该顺序和边权重；保留算法原始顺序 `algorithm_rank` 以便审计。

## 校准建议

当前阈值是上线前的透明基线，不是经本行标注样本验证的统计模型。正式使用前应以已完成复核的历史样本做回测，至少比较各等级的案例量、人工覆盖率、命中率和漏报情况；修改权重或阈值时应升级 `modelVersion`，不得覆盖既有审计快照。
