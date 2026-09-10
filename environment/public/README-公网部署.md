# BankGraph 公网部署

本系统需要运行后端和数据库，不能只把 `frontend/dist` 上传到静态托管平台。推荐使用一台 Linux 云服务器（至少 4 核、16 GB；若同时运行本地模型，建议 32 GB 以上）、一个域名和 HTTPS。

## 发布流程

1. 在本仓库根目录执行 `bash environment/public/build-release.sh` 生成发布包。
2. 将 `artifacts/bankgraph-public-release.tar.gz` 上传并解压到服务器的 `/www/wwwroot/bankgraph`。
3. 复制 `environment/baota/bankgraph.env.example` 为 `environment/config/.env.production`，至少替换数据库密码、TuGraph 密码和 `JWT_SECRET`。密钥可用 `openssl rand -base64 64` 生成。
4. 按 `environment/baota/README-宝塔部署.md` 初始化基础设施并启动后端。
5. 将 `environment/public/nginx-bankgraph.conf.example` 中的 `example.com` 改成域名后放入 Nginx 站点配置。先开放 80 端口验证，再通过宝塔或 Certbot 签发证书并开启 443。

## 公网边界

- 公网只开放 80/443。
- 后端 3030、PostgreSQL 5432、TuGraph 7070/7687 和模型服务端口不得开放到公网。
- 前端与接口使用同一个域名，浏览器请求 `/api/` 时由 Nginx 转发到本机后端。
- 自助注册账号固定为 `viewer` 角色，只能请求案例列表；上传、删除、审批和管理接口仍只允许管理员。

上线后打开 `https://你的域名/login`，注册新账号。登录后应直接进入案例列表，且页面没有删除等写操作。
