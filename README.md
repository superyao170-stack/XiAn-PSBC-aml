# XiAn-PSBC-aml

本地开发固定使用后端 **3032**、前端 **8032**，可与使用 3030 / 8030 的 `datagraph-bank` 同时运行。
前端入口为 `http://localhost:8032`，API 代理默认指向 `http://127.0.0.1:3032`。
VS Code 的“运行后端（本地源码）”也使用 3032，启动和结束调试时不会操作其他项目的 Docker 后端。
后端编译、测试、运行和容器统一使用 **Java 17**。VS Code 的 Java 语言服务器使用插件自带运行环境，项目 JDK 与启动配置固定为 17；更改配置后执行 `Developer: Reload Window`。

本仓库按技术职责组织。需要查找某类内容时，优先从下列一级目录进入：

| 目录 | 内容 | 常用入口 |
| --- | --- | --- |
| `backend/` | Spring Boot 后端、接口、领域服务与测试 | `backend/src/main/java`、`backend/pom.xml` |
| `frontend/` | Vue 3 前端、页面、组件与接口适配 | `frontend/src`、`frontend/package.json` |
| `postgresql/` | 唯一 PostgreSQL/Flyway 数据库结构来源 | `postgresql/V1__*.sql` 至最新迁移 |
| `workers/` | 所有 Python 分析 Worker | `aml-intelligence/`、`structured-case-identification/`、`risk-analytics-service/` |
| `tools/` | 独立开发和检查工具 | `tools/graph-viewer/`、`tools/database/` |
| `environment/` | 本地运行、Docker、Nginx、systemd 和宝塔配置 | `local/`、`docker/`、`nginx/`、`systemd/`、`baota/` |
| `artifacts/` | 可再生成输出和人工备份，不属于应用源码 | `backups/`、运行时生成的 `graphs/` |

## 常用命令

```bash
# 首次运行：创建两个 AML Worker 共用的 Python 环境
bash tools/setup-worker-environment.sh

# 启动后端（会检查 Java 17 和结构化案例 Worker 环境）
bash environment/local/run-backend.sh

# 前端开发
cd frontend && npm run dev

# 后端测试
JAVA_HOME="$HOME/.local/jdks/temurin-17.jdk/Contents/Home" mvn -f backend/pom.xml clean verify

# 前端构建
npm --prefix frontend run build

# 生成离线知识图谱
node tools/graph-viewer/kg-render.mjs <输入.json或目录>
```

后端构建会把根目录 `postgresql/` 中的迁移复制进 JAR 的 `db/migration`，源码中不再维护第二份数据库脚本。

如需自定义本地端口，后端使用 `SERVER_PORT=3033 bash environment/local/run-backend.sh`，
前端对应使用 `VITE_API_PROXY_TARGET=http://127.0.0.1:3033 VITE_DEV_PORT=8033 npm --prefix frontend run dev`。
前端端口已被占用时会明确报错，避免自动跳到其他项目的端口。
Docker 镜像和宝塔部署配置显式使用 `SERVER_PORT=3030`，与现有生产 Nginx 代理配置配套。

公网域名部署、自助注册和只读案例账号的发布说明见 `environment/public/README-公网部署.md`。
