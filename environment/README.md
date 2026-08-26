# 环境与部署

- `local/`：开发机运行入口。
- `docker/`：PostgreSQL、图数据库及相关 Docker Compose/Dockerfile。
- `nginx/`：通用 Nginx 生产配置。
- `systemd/`：独立服务的 systemd 单元。
- `baota/`：阿里云宝塔部署所需的精简配置与脚本。

本地后端入口：

```bash
bash environment/local/run-backend.sh
```

基础设施示例：

```bash
docker compose -f environment/docker/docker-compose-infra.yml up -d
docker compose -f environment/docker/docker-compose-graph.yml up -d
```

PostgreSQL 16、TuGraph 4.5.2、CoreNLP 4.5.10 和三个 BGE 模型的一键部署：

```bash
./environment/docker/deploy-stack.sh up
```

完整说明见 `environment/docker/README-一键部署.md`。

构建后端容器时使用仓库根目录作为构建上下文：

```bash
docker build -f backend/Dockerfile -t bankgraph-backend .
```
