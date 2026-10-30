# 环境与部署

- `local/`：开发机运行入口。
- `docker/`：PostgreSQL、图数据库及相关 Docker Compose/Dockerfile。
- `nginx/`：通用 Nginx 生产配置。
- `systemd/`：独立服务的 systemd 单元。
- `baota/`：阿里云宝塔部署所需的精简配置与脚本。

后端编译、开发运行和生产部署统一使用 Java 17。先安装 JDK 17；如需显式指定，设置 `JAVA_HOME` 为 JDK 17 的安装目录。

本地后端入口：

```bash
bash environment/local/run-backend.sh
```

本地默认后端端口为 3032，前端 `npm --prefix frontend run dev` 使用 8032 并代理到 3032。
这组端口独立于 `datagraph-bank` 的 3030 / 8030。Docker 镜像和宝塔配置显式使用 3030，
本目录的生产 Nginx 示例也按 3030 配置；直接运行 JAR 并使用这些示例时需设置 `SERVER_PORT=3030`。

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
