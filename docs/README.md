# Swagger / OpenAPI 文档

后端启动后可通过以下地址访问：

- Swagger UI：`http://localhost:3030/swagger-ui.html`
- 完整 OpenAPI JSON：`http://localhost:3030/api-docs`
- 结构化案例识别流程：`http://localhost:3030/api-docs/01-结构化案例识别流程`
- 案例复核审批流程：`http://localhost:3030/api-docs/02-案例复核审批流程`

`swagger.json` 是从正在运行的后端 `/api-docs` 导出的接口快照，可导入 Postman、Apifox 等工具。接口发生变化后，应重新启动后端并更新该文件。

需要鉴权的接口使用 JWT Bearer Token。先调用 `/api/v1/auth/login`，再将响应中的 `data.token` 填入 Swagger UI 的 **Authorize** 对话框。
