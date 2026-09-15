# FiaGmail

本地运行的 Gmail Web 客户端：Vue 3 / Vite / TypeScript / Tailwind CSS + FastAPI + PostgreSQL + Redis。界面自行实现，不使用 UI 组件库或图标库。

正式公网部署、Google OAuth 发布验证及受限权限安全评估请参阅 [正式部署指南](docs/PRODUCTION-DEPLOYMENT.md)。

## 启动

需要运行中的 Docker Desktop（Linux 容器）和本机 Node.js 22 或更新版本。**前端不容器化**，Docker 仅运行 FastAPI、PostgreSQL 18 和 Redis。在项目根目录执行：

```powershell
docker compose up -d --build
docker compose ps
cd frontend
npm ci
npm run build
npm run dev
```

保持前端命令运行，打开 Vite 输出的地址（通常为 **http://localhost:5173**），创建平台账户后连接 Gmail。5173 被占用时 Vite 自动递增端口。API 文档入口为同一地址下的 `/api/docs`，OpenAPI JSON 位于 `/api/openapi.json`。前端代理 `/api` 到本机 Docker 后端 `127.0.0.1:8184`。

前端由你在终端中手动运行 `npm run dev`。Vite 优先使用 5173；若该端口已占用，会在终端中显示自动递增后的实际地址。

后端构建默认使用 [清华 PyPI 镜像](https://mirrors.tuna.tsinghua.edu.cn/help/pypi/)，并启用 Docker BuildKit 的 pip 下载缓存。可在 `.env` 中通过 `PIP_INDEX_URL` 替换镜像地址，配置生效需要重新构建后端。

当前交付已生成本地 `.env`，包含随机数据库密码及加密密钥，不包含 Google 凭据。新环境先复制 `.env.example` 为 `.env` 并填写配置。可以使用以下命令生成密钥（Python 环境须安装 cryptography）：

```powershell
python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

不要更换仍在使用的 `TOKEN_ENCRYPTION_KEY`，否则无法解密现有绑定。备份数据库时同时妥善备份该密钥。

前端使用 Vite 默认地址（优先 `localhost:5173`，占用时递增），API 映射 `127.0.0.1:8184`；PostgreSQL 和 Redis 仅在 Compose 内部网络访问。数据存储于 Docker 命名卷，普通停止不会删除数据：

```powershell
docker compose stop
docker compose start
docker compose logs --tail 100 api
```

修改 `.env` 后执行 `docker compose up -d --force-recreate api`。若 8184 被其他服务占用，先处理冲突；不要直接停止不相关服务。

## 账户使用

- 注册：用户名（3–40 位字母、数字、下划线或短横线）、密码（10–128 字符）、一个密保问题及答案。
- 找回密码：输入用户名、回答密保，再设置新密码。凭证有效期 5 分钟且仅可使用一次；答案去除首尾空白、做 NFKC 规范化，区分大小写。
- 用户名不区分大小写。修改密码会撤销全部已有会话。
- 右上角头像可管理邮箱、退出登录、修改密码及密保。
- 同一平台账户可连接多个 Gmail；同一 Gmail 只允许绑定一个平台账户。Gmail 点号、加号和 googlemail.com 地址会归一化，Google Workspace 请使用主邮箱地址。

## 连接 Gmail

### 推荐：Google OAuth

1. 在 [Google Cloud Console](https://console.cloud.google.com/) 创建项目并启用 Gmail API。
2. 配置 OAuth 同意页面，开发时使用外部应用测试模式，将自己的 Google 账户加入测试用户。
3. 创建 **Web 应用**类型的 OAuth 客户端，添加授权回调：
   `http://localhost:5173/api/v1/gmail-accounts/oauth/callback`
4. 在 `.env` 中填写 `GOOGLE_CLIENT_ID`、`GOOGLE_CLIENT_SECRET`，重新创建 API 容器。
5. 在页面点击“连接 Gmail” → “使用 Google 连接”。后端申请 `gmail.modify` 和离线授权。

官方 SDK：[Python quickstart](https://developers.google.com/workspace/gmail/api/quickstart/python)。只使用 Google 发布的 API/auth 客户端，不使用第三方 Gmail 封装。

当前是本地测试部署。公开发布涉及受限权限验证及适用的安全评估，需按 [Google 权限说明](https://developers.google.com/workspace/gmail/api/auth/scopes) 另行完成；本地平台允许注册不代表 OAuth 已获公开发布许可。测试模式的授权可能需要定期重新连接。

Google OAuth 回调端口必须与 Cloud Console 完全一致。默认使用 5173；如果 Vite 因端口占用启动到 5174 等端口，请将 `.env` 的 `PUBLIC_URL` 改为实际地址，在 Google Cloud 添加对应回调，然后重新创建 API 容器。IMAP＋SMTP 连接不受此前端端口限制。

### 备选：IMAP＋SMTP

无需配置 OAuth 客户端。开启 Google 两步验证，在账户允许的情况下创建 [Google 应用专用密码](https://support.google.com/accounts/answer/185833?hl=zh-Hans)。在“连接 Gmail”展开备选方式，填写主邮箱地址和应用专用密码。

- 收信固定：`imap.gmail.com:993`，SSL/TLS。
- 发信固定：`smtp.gmail.com:465` SSL/TLS，或 `587` STARTTLS。
- 同时验证两个通道，全部成功才保存；连接验证不发送邮件。
- 不使用普通 Google 登录密码，不支持自定义 SMTP 主机、POP、别名登录或第三方邮箱。
- IMAP 接入使用 Python 标准库。Workspace 管理员策略或账户安全设置可能限制应用专用密码或 IMAP 使用。

重新连接同一邮箱会更新凭据或切换方式；原邮箱操作结束后才能切换。凭据加密保存在数据库，浏览器不接收 Google 令牌或已保存密码。

## 邮件功能

- 会话列表、搜索、阅读、已读/未读、星标、归档、垃圾邮件、回收站、批量操作。
- 新邮件、回复、回复全部、转发、纯文本撰写、抄送/密送、附件。
- 停笔约 2 秒后自动保存 Gmail 草稿；关闭写信窗口先保存。重开草稿从 Gmail 获取。
- 标签新增、重命名、删除、应用及移除；系统标签不能修改名称或删除。
- 默认不加载远程图片；HTML 来信经过清洗，并在隔离 iframe 中显示。内嵌图片可作为附件下载；不提供富文本编辑器。
- 搜索支持 Gmail 语法，范围为当前文件夹；在“所有邮件”中搜索可扩大范围。
- 页面可见时每 60 秒同步当前邮箱，并支持手动刷新。未打开页面时不进行后台轮询。
- 不提供永久删除、联系人、统一收件箱或高级邮箱设置。

附件总大小限制为 **18 MiB**（为 MIME 编码和 Gmail 请求上限留余量），上传临时文件 24 小时过期，过期文件在下一次上传或同步时清理。已成功保存的草稿附件保留在 Gmail，临时目录不是永久存储。

发送使用每封撰写会话的去重标识。遇到网络中断等结果不明的情况，页面禁止自动重发，请先检查“已发送”，再决定是否重新撰写。SMTP 部分收件人拒收会提示地址。SMTP 发送后由 Gmail 保存已发送副本。

## 结构与运行机制

```text
frontend/          Vue 页面、Tailwind、本机 Vite 服务及 API 代理
backend/app/       API、账户、加密、邮件解析、双通道适配器
backend/app/migrate.py  带版本记录的数据库迁移入口
docs/              API 清单及构建/连通性记录
compose.yaml       后端、PostgreSQL、Redis 容器编排
scripts/           可选的 HTTP 连通性检查脚本
.env.example       无敏感值的配置模板
```

PostgreSQL 保存账户、绑定和同步游标，Gmail 保存邮件本体。Redis 缓存列表/标签 60 秒、正文 5 分钟，同时存储会话、限流计数、发送去重和互斥锁。附件不写入 Redis。

后端所有邮件操作经过用户归属校验。Google SDK 和标准库网络调用在线程池执行；每个邮箱串行处理，避免同步、刷新凭据与草稿更新互相覆盖。IMAP 基于 Gmail 消息 ID 定位文件夹 UID，列表先按会话分组后分页；草稿清理仅使用定向 UID EXPUNGE。

## 检查范围

遵照要求，最终验收仅执行后端镜像和本机前端构建检查。**没有执行功能、单元、端到端、安全或浏览器操作测试**。Gmail 授权、真实收发、API 操作及其他业务交互由你手动验收；未使用任何真实邮箱凭据或发送邮件。

具体构建及启动结果见 [docs/BUILD-REPORT.md](docs/BUILD-REPORT.md)。
