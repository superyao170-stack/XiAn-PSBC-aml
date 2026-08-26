# 银行图谱基础组件一键 Docker 部署

`docker-compose-stack.yml` 统一管理以下六个组件：

| 组件 | Docker 服务 | 固定版本/用途 |
|---|---|---|
| PostgreSQL | `postgres` | PostgreSQL 16，业务数据库 |
| TuGraph | `tugraph` | TuGraph 4.5.2，图数据库 |
| CoreNLP | `corenlp` | Stanford CoreNLP 4.5.10 中文指代消解 |
| BGE-M3 | `bge-models` | 风险知识库向量检索，1024 维 |
| bge-small-zh-v1.5 | `bge-models` | 中文向量召回，512 维 |
| bge-reranker-v2-m3 | `bge-models` | 候选结果重排 |

三个 BGE 模型封装在同一个镜像中并按请求切换加载，避免三个模型进程同时占用大量内存。

## 一键构建并启动

在仓库根目录执行：

```bash
./environment/docker/deploy-stack.sh up
```

首次构建 CoreNLP 镜像时，默认读取：

```text
/Users/sunnytearlie/Downloads/stanford-corenlp-4.5.10
```

其他构建机可以显式指定解压目录：

```bash
CORENLP_DISTRIBUTION_DIR=/path/to/stanford-corenlp-4.5.10 \
  ./environment/docker/deploy-stack.sh up
```

## 推荐的生产部署方式

不要在每台生产服务器重复下载模型。在一台网络可用、CPU 架构与目标服务器一致的构建机执行：

```bash
./environment/docker/deploy-stack.sh package
```

默认生成：

```text
artifacts/datagraph-bank-stack-<架构>.tar
```

将离线包和 `environment/docker/` 目录复制到目标服务器，然后执行：

```bash
./environment/docker/deploy-stack.sh install /path/to/datagraph-bank-stack-x86_64.tar
```

该操作自动完成 `docker load`、持久化卷创建和 `docker compose up -d`，目标服务器不需要联网下载模型。

长期生产环境建议把以下四个镜像推送到内部 Harbor：

- `datagraph-bank/postgres:16-alpine`
- `datagraph-bank/tugraph:4.5.2`
- `datagraph-bank/corenlp:4.5.10`
- `datagraph-bank/bge-models:e44369c`

## 服务地址

| 服务 | 地址 |
|---|---|
| PostgreSQL | `localhost:5432` |
| TuGraph Web | `http://localhost:7070` |
| TuGraph Bolt | `bolt://localhost:7687` |
| CoreNLP | `http://localhost:9002` |
| BGE 模型健康检查 | `http://localhost:8080/health` |

### BGE-M3 风险知识检索

```bash
curl http://localhost:8080/v1/embeddings \
  -H 'Content-Type: application/json' \
  -d '{"model":"BAAI/bge-m3","input":"可疑交易风险知识检索"}'
```

### bge-small-zh-v1.5 向量召回

```bash
curl http://localhost:8080/v1/embeddings \
  -H 'Content-Type: application/json' \
  -d '{"model":"BAAI/bge-small-zh-v1.5","input":"待召回文本"}'
```

### bge-reranker-v2-m3 重排

```bash
curl http://localhost:8080/v1/rerank \
  -H 'Content-Type: application/json' \
  -d '{"query":"可疑跨境转账","documents":["向多个境外账户转账","本地超市消费"]}'
```

## 运维命令

```bash
./environment/docker/deploy-stack.sh status
./environment/docker/deploy-stack.sh down
./environment/docker/deploy-stack.sh up
```

`down` 只停止容器，不删除 PostgreSQL 和 TuGraph 的持久化数据卷。
