# PostgreSQL migrations

本目录是项目唯一的数据库结构来源，所有文件均按 Flyway `V<版本>__<说明>.sql` 规则命名。

- 不要在 `backend/`、部署目录或其他位置维护迁移副本。
- 新结构变更只允许追加更高版本迁移，已经执行过的历史迁移不要修改。
- Maven 会通过 `backend/pom.xml` 将本目录复制到 JAR 的 `db/migration`。
