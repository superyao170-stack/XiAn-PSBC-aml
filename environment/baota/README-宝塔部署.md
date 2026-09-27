# BankGraph 宝塔部署

部署根目录固定为 `/www/wwwroot/bankgraph`，目录与仓库职责保持一致：

```text
/www/wwwroot/bankgraph
├── backend/app.jar
├── frontend/dist
├── workers
│   ├── requirements.txt
│   ├── .venv
│   ├── aml-intelligence
│   ├── structured-case-identification
│   └── risk-analytics-service
└── environment
    ├── config/.env.production
    └── baota
        ├── docker-compose-infra.yml
        ├── nginx-bankgraph.conf
        └── scripts
```

## 初始化

1. 将 `bankgraph.env.example` 复制为 `environment/config/.env.production` 并填写密码。
2. 确认后端 JAR、前端 `dist` 和三个 Worker 已上传。
3. 执行：

```bash
cd /www/wwwroot/bankgraph
bash environment/baota/scripts/install.sh
```

脚本会启动 PostgreSQL 16、创建数据库，并根据 `workers/requirements.txt` 创建供两个 AML Worker 共用的 `workers/.venv`，随后初始化 TuGraph。数据库表结构由后端 JAR 内打包的 Flyway 迁移自动创建。

## Java 项目

- 运行目录：`/www/wwwroot/bankgraph/backend`
- JAR：`/www/wwwroot/bankgraph/backend/app.jar`
- Java：25
- 端口：3030
- 运行用户：`www`

启动参数：

```bash
java -Xmx2048M -Xms512M -jar /www/wwwroot/bankgraph/backend/app.jar \
  --server.port=3030 \
  --spring.config.import=optional:file:/www/wwwroot/bankgraph/environment/config/.env.production[.properties]
```

## Nginx 与验证

宝塔站点根目录设置为 `/www/wwwroot/bankgraph/frontend/dist`，站点配置参考 `environment/baota/nginx-bankgraph.conf`。

```bash
bash /www/wwwroot/bankgraph/environment/baota/scripts/status.sh
curl -I http://127.0.0.1:8030/
```

生产环境仅应对公网开放前端端口（或域名的 80/443）；3030、5432、6379、7687、9092 和 18082 保持内网访问。
