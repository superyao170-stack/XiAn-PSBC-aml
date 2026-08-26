# AML 智能分析 Worker

该目录集成两项算法：

- `AML_ANALYSIS_TEXT_GENERATION`：`aml_analysis_workflow` 分段生成、审查与定向重写流程。
- `CASE_GRAPH_SIMILARITY_GED`：`case_similarity.py` 使用 BGE-small 中文向量召回、BGE-reranker 事件精排、异构案例图近似 GED 与 Top-5 命中子图匹配。

Spring Boot 通过 `app.py` 的 JSON 标准输入/输出协议调用 Worker，无需单独启动 HTTP 服务。相似度算法默认加载 `BAAI/bge-small-zh-v1.5` 与 `BAAI/bge-reranker-v2-m3`；Docker 镜像内使用 `/opt/models` 的离线副本，最终排序仍以归一化 GED 为准。

可通过 `AML_EMBEDDING_MODEL`、`AML_RERANKER_MODEL`、`AML_EMBEDDING_DEVICE`、`AML_RERANKER_DEVICE` 与 `AML_MODEL_LOCAL_FILES_ONLY` 配置部署路径和推理设备。生产 Docker 默认只允许加载镜像内模型，不在请求时联网下载。

文本生成通过 HTTP 调用 OpenAI 兼容的模型服务。运行时从 Worker 目录下不入库的 `.env` 读取配置，页面请求不能覆盖模型：

```text
DEEPSEEK_API_KEY=...
DEEPSEEK_BASE_URL=https://api.deepseek.com
DEEPSEEK_CHAT_MODEL=deepseek-v4-flash
```

本地检查：

```text
python3 -m unittest -v test_app.py
```
