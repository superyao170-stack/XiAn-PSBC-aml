# DataGraph Bank

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
JAVA_HOME=$(/usr/libexec/java_home -v 17) mvn -f backend/pom.xml test

# 前端构建
npm --prefix frontend run build

# 生成离线知识图谱
node tools/graph-viewer/kg-render.mjs <输入.json或目录>
```

后端构建会把根目录 `postgresql/` 中的迁移复制进 JAR 的 `db/migration`，源码中不再维护第二份数据库脚本。
