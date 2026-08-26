# Workers

| Worker | 职责 |
| --- | --- |
| `structured-case-identification/` | 数据上传后的可疑报告生成、案例框架抽取、相似度匹配和结果固化 |
| `anti-fraud-case-identification/` | 分渠道事件链规则化、反欺诈分析文本、框架抽取和 Top5 相似案例匹配 |
| `aml-intelligence/` | 反洗钱文本生成与案例图谱相似度算法服务 |
| `risk-analytics-service/` | 关联线索分析和隐蔽风险挖掘 |

Worker 的 `.env`、虚拟环境、上传文件和运行结果均属于本地运行数据，已通过根目录 `.gitignore` 管理。

`structured-case-identification` 与 `aml-intelligence` 共用 `workers/.venv` 和
`workers/requirements.txt`。首次运行执行：

```bash
bash tools/setup-worker-environment.sh
```
