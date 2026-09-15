# GitHub Actions 自动部署指南

推送到 `main` 分支后，GitHub Actions 会完成**全部构建**（后端 Docker 镜像 + 前端静态产物），再通过 SSH 把结果送到服务器。服务器资源有限，只负责拉取镜像、接收静态文件与重启容器，**不执行任何编译**。

## 1. 部署架构

```text
开发者 push main
        │
        ▼
GitHub Actions（ubuntu-latest）
   ├── docker buildx 构建 backend 镜像 ──▶ ghcr.io/<owner>/hmail-backend
   │                                        （tag：latest 与 <commit-sha>）
   └── npm ci && npm run build ──▶ frontend/dist
                                        │
                                        ▼ SSH :22
服务器
   ├── /opt/hmail                              后端编排、发布脚本与 .env
   │     ├── compose.yaml                      ← 仓库 compose.prod.yaml
   │     ├── deploy-remote.sh                  ← 仓库 scripts/deploy-remote.sh
   │     └── .env                              ← 手工维护，CI 不覆盖
   ├── /opt/1panel/www/sites/hmail.fiacloud.top/index/dist   ← 前端静态产物
   └── Docker Compose：api(127.0.0.1:8184) + PostgreSQL + Redis
```

对外只暴露 1Panel/Nginx 的 80/443；FastAPI、PostgreSQL、Redis 不直接暴露公网。

## 2. 配置 GitHub Secrets

在仓库 `Settings → Secrets and variables → Actions` 中添加：

| 名称 | 必填 | 说明 |
|---|---|---|
| `SERVER_HOST` | 是 | 服务器地址，域名或 IP |
| `SERVER_USER` | 是 | 部署用户，需 `sudo` 免密且可执行 docker |
| `SERVER_SSH_KEY` | 是 | 该用户的 SSH **私钥**全文（含 `BEGIN/END` 行），对应公钥需在服务器 `~/.ssh/authorized_keys` |

SSH 端口固定为 `22`，如需修改，编辑 `.github/workflows/deploy.yml` 中的 `SSH_PORT`。

可选仓库变量（`Settings → Secrets and variables → Actions → Variables`）：

| 名称 | 说明 |
|---|---|
| `PIP_INDEX_URL` | 后端镜像构建使用的 Python 包索引，默认 `https://pypi.org/simple`（GitHub Runner 网络环境）；国内可改为 `https://pypi.tuna.tsinghua.edu.cn/simple` |

## 3. 服务器准备（只做一次）

```bash
# 1) 目录：后端编排与前端站点
sudo mkdir -p /opt/hmail /opt/1panel/www/sites/hmail.fiacloud.top/index/dist
sudo chown -R "$USER" /opt/hmail /opt/1panel/www/sites/hmail.fiacloud.top/index

# 2) 部署用户加入 docker 组（并确认 sudo 免密可用）
sudo usermod -aG docker "$USER"
sudo -n true && echo "sudo 免密可用"

# 3) 生产环境变量
cp .env.production.example /opt/hmail/.env   # 或手工创建
vi /opt/hmail/.env
chmod 600 /opt/hmail/.env
```

`.env` 至少需要填写（完整字段见 `.env.production.example`）：

```env
HMAIL_IMAGE=ghcr.io/<你的-GitHub-用户名或组织>/hmail-backend:latest
POSTGRES_PASSWORD=<高强度随机密码>
TOKEN_ENCRYPTION_KEY=<Fernet 密钥>
PUBLIC_URL=https://hmail.fiacloud.top
SMTP_HOST=smtp.qq.com
SMTP_PORT=465
SMTP_USER=<验证码发信账号>
SMTP_PASS=<SMTP 授权码>
SMTP_FROM="Hmail <your-account@qq.com>"
```

生成 Fernet 密钥：

```bash
python3 -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

> `HMAIL_IMAGE` 必须与工作流推送的镜像名一致且全小写：`ghcr.io/<owner>/hmail-backend:latest`。
> 这里的 `<owner>` 是**仓库地址里的账号名**（本仓库为 `Glassous`，小写后是 `glassous`），不是本机用户名或邮箱前缀。
> 不确定时查 `build` 任务日志中的 `image.name=`，或 `pushing manifest for` 一行，照抄即可。
>
> 写错 owner 时部署会在镜像校验阶段报「镜像仓库不存在或无权访问」，写错标签则报「镜像标签不存在」。
>
> 不要更换仍在使用的 `TOKEN_ENCRYPTION_KEY`，否则无法解密已连接的邮箱凭据。

### GitHub 环境（可选但推荐）

工作流的 `deploy` 任务引用了 `production` 环境。若希望手动审批后才发布，在
`Settings → Environments` 创建名为 `production` 的环境并添加 Required reviewers；
不创建也能正常执行。

### 镜像仓库可见性

推送的包默认继承仓库可见性：

- 公开包：服务器可直接 `docker pull`，无需登录。
- 私有包：工作流每次部署会自动执行 `sudo docker login ghcr.io`（使用内置 `GITHUB_TOKEN`），无需手工配置。
  若希望手工预配，可在服务器执行 `sudo docker login ghcr.io -u <GitHub 用户名>`，密码填入具有 `read:packages` 权限的 PAT。

## 4. 反向代理配置（1Panel / Nginx）

1Panel 的网站默认由 **OpenResty 容器**提供服务。若该容器使用 `network_mode: host`，用默认的 `http://127.0.0.1:8184` 即可；若为 bridge 网络，容器内的 `127.0.0.1` 指向容器自身，需改用下列任一方案：

```bash
# 确认 OpenResty 容器的网络模式
sudo docker inspect 1Panel-openresty --format '{{.HostConfig.NetworkMode}}'
# 若为 bridge，取宿主网关地址
sudo docker network inspect bridge --format '{{(index .IPAM.Config 0).Gateway}}'
```

- 方案 A（推荐）：把反代目标改为宿主网关地址，例如 `http://172.17.0.1:8184`。
- 方案 B：为 OpenResty 容器添加 `extra_hosts: ["host.docker.internal:host-gateway"]`（1Panel「容器 → 编辑」中追加），随后使用 `http://host.docker.internal:8184`。
- 方案 C：把 OpenResty 容器加入后端网络（`hmail_default`），反代目标写 `http://hmail-api-1:8000`。

在 1Panel「网站 → hmail.fiacloud.top → 配置文件」的 `server { }` 块中加入：

```nginx
    # 前端静态站点根目录（指向 CI 发布的前端产物）
    root /opt/1panel/www/sites/hmail.fiacloud.top/index/dist;
    index index.html;

    # 附件上限 18 MiB，MIME base64 膨胀后请求体可达约 24 MiB
    client_max_body_size 30m;

    # SPA 路由回退
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 带哈希的静态资源长缓存
    location /assets/ {
        expires 1y;
        add_header Cache-Control "public, max-age=31536000, immutable";
    }

    # 后端 API 反向代理
    location /api/ {
        proxy_pass http://172.17.0.1:8184;     # 见上文方案 A/B
        proxy_http_version 1.1;

        # 后端对写请求校验 Origin == PUBLIC_URL，禁止改写 Host/Origin
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;            # 后端限流依赖该头
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;

        # Gmail/IMAP 同步与发送可能较慢，与 vite.config.ts 的 600000ms 保持一致
        proxy_read_timeout 600s;
        proxy_send_timeout 600s;
        proxy_request_buffering off;
    }
```

要点：

- **不要**改写 `Host` 或 `Origin`，否则非 GET 请求会被后端判定为跨源并返回 403。
- 必须透传 `X-Real-IP`，否则所有请求按代理 IP 共享限流额度。
- `client_max_body_size` 需覆盖 18 MiB 附件经 base64 编码后的请求体。
- 1Panel 配置目录位于 `…/sites/hmail.fiacloud.top/`，保存后在面板中重载 OpenResty 即生效。

## 5. 部署与回滚

触发方式：

- 推送（或合并）到 `main` 分支自动部署。
- 或在 `Actions → Deploy → Run workflow` 手动触发。

流水线阶段：构建镜像并推送 GHCR → 构建前端产物 → 准备服务器目录 → 同步编排与脚本 →
发布前端产物（`rsync --delete`）→ 服务器 `docker compose pull` + `up -d --wait`
（容器不健康则整个部署失败，并打印 API 最近日志）。

数据库迁移由容器启动命令 `python -m app.migrate` 自动执行，无需额外步骤。

回滚：

```bash
# 查看当前使用的镜像标签
sudo docker compose -f /opt/hmail/compose.yaml ps

# 指定历史提交的镜像标签（CI 每次都会推送 <commit-sha> 标签）
sudo sed -i 's|^HMAIL_IMAGE=.*|HMAIL_IMAGE=ghcr.io/<owner>/hmail-backend:<commit-sha>|' /opt/hmail/.env
cd /opt/hmail && sudo docker compose -f compose.yaml up -d --wait
```

前端回滚：重新运行目标提交对应的 Actions 任务，或本地构建该提交后
`rsync -a --delete frontend/dist/ user@host:/opt/1panel/www/sites/hmail.fiacloud.top/index/dist/`。

## 6. 常见问题

| 现象 | 原因与处理 |
|---|---|
| 拉取镜像报 `denied` / `unauthorized` | 包为私有且凭据缺失。确认工作流中 `sudo docker login ghcr.io` 步骤成功，或把包改为 Public |
| 部署脚本报“未找到 /opt/hmail/.env” | 需先在服务器按第 3 节创建 `.env`，CI 不会生成也不会覆盖它 |
| 写请求返回 403 `请求来源无效` | `PUBLIC_URL` 与浏览器访问来源不一致，或反向代理改写了 `Origin`/`Host` |
| 上传附件返回 413 | 反向代理 `client_max_body_size` 小于请求体大小 |
| 页面 404 / 白屏 | 站点 `root` 未指向 `.../index/dist`，或 OpenResty 无读取权限（需保持目录可读） |
| 容器一直不健康导致部署失败 | 查看 `sudo docker compose -f /opt/hmail/compose.yaml logs --tail 100 api`，常见为 `TOKEN_ENCRYPTION_KEY` 或数据库密码错误 |
