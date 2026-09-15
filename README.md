# Hmail

本地运行的邮件 Web 客户端：Vue 3 / Vite / TypeScript / Tailwind CSS + FastAPI + PostgreSQL + Redis。界面自行实现，不使用 UI 组件库或图标库。邮箱连接以通用 IMAP＋SMTP 为主，Gmail 另提供 OAuth 方式。

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

保持前端命令运行，打开 Vite 输出的地址（通常为 **http://localhost:5173**），创建平台账户后连接邮箱。5173 被占用时 Vite 自动递增端口。API 文档入口为同一地址下的 `/api/docs`，OpenAPI JSON 位于 `/api/openapi.json`。前端代理 `/api` 到本机 Docker 后端 `127.0.0.1:8184`。

前端由你在终端中手动运行 `npm run dev`。Vite 优先使用 5173；若该端口已占用，会在终端中显示自动递增后的实际地址。

后端构建默认使用 [清华 PyPI 镜像](https://mirrors.tuna.tsinghua.edu.cn/help/pypi/)，并启用 Docker BuildKit 的 pip 下载缓存。可在 `.env` 中通过 `PIP_INDEX_URL` 替换镜像地址，配置生效需要重新构建后端。

当前交付已生成本地 `.env`，包含随机数据库密码、加密密钥，以及 `SMTP_*` 验证码发信配置。新环境先复制 `.env.example` 为 `.env` 并填写配置。注册与找回密码依赖 `SMTP_HOST`、`SMTP_PORT`、`SMTP_USER`、`SMTP_PASS`、`SMTP_FROM`；其中 `SMTP_PASS` 为邮箱 SMTP 授权码（QQ 邮箱需在“设置 → 账户”中开启 SMTP 服务后生成），`SMTP_PORT` 为 `465` 时使用 SSL/TLS。修改 `.env` 后需重新创建 API 容器。可以使用以下命令生成密钥（Python 环境须安装 cryptography）：

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

- 注册：邮箱地址、密码（10–128 字符）和邮箱验证码。注册前点击“获取验证码”，验证码 10 分钟内有效且仅可使用一次，错误尝试超过 5 次即失效。
- 登录：直接使用邮箱地址和密码。邮箱地址不区分大小写。
- 找回密码：输入邮箱获取验证码，验证通过后设置新密码；成功重设会撤销全部已有会话。
- 验证码通过 Redis 保存，同一邮箱 60 秒内不可重复发送，并按 IP 与邮箱限流。平台验证码发信使用 `.env` 中的 `SMTP_*` 配置，与用户连接的邮箱相互独立。
- 右上角头像可管理邮箱、退出登录、修改密码。
- 同一平台账户可连接多个邮箱；同一邮箱地址只允许绑定一个平台账户。Gmail 点号、加号和 googlemail.com 地址会归一化，Google Workspace 请使用主邮箱地址。

## 连接邮箱

连接方式有两种：通用 IMAP＋SMTP 适用于任意开启 IMAP/SMTP 的邮箱；Google OAuth 仅适用于 Gmail，授权体验更好。两者可以并存，重新连接同一邮箱会更新凭据或切换方式。

### Gmail：Google OAuth（推荐 Gmail 用户）

1. 在 [Google Cloud Console](https://console.cloud.google.com/) 创建项目并启用 Gmail API。
2. 配置 OAuth 同意页面，开发时使用外部应用测试模式，将自己的 Google 账户加入测试用户。
3. 创建 **Web 应用**类型的 OAuth 客户端，添加授权回调：
   `http://localhost:5173/api/v1/gmail-accounts/oauth/callback`
4. 在 `.env` 中填写 `GOOGLE_CLIENT_ID`、`GOOGLE_CLIENT_SECRET`，重新创建 API 容器。
5. 在页面点击“连接邮箱” → “使用 Google 连接”。后端申请 `gmail.modify` 和离线授权。

官方 SDK：[Python quickstart](https://developers.google.com/workspace/gmail/api/quickstart/python)。只使用 Google 发布的 API/auth 客户端，不使用第三方 Gmail 封装。

当前是本地测试部署。公开发布涉及受限权限验证及适用的安全评估，需按 [Google 权限说明](https://developers.google.com/workspace/gmail/api/auth/scopes) 另行完成；本地平台允许注册不代表 OAuth 已获公开发布许可。测试模式的授权可能需要定期重新连接。

Google OAuth 回调端口必须与 Cloud Console 完全一致。默认使用 5173；如果 Vite 因端口占用启动到 5174 等端口，请将 `.env` 的 `PUBLIC_URL` 改为实际地址，在 Google Cloud 添加对应回调，然后重新创建 API 容器。IMAP＋SMTP 连接不受此前端端口限制。

### 通用：IMAP＋SMTP（推荐）

无需配置 OAuth 客户端，在“连接邮箱”弹窗中填写：

- **邮箱服务商**：内置 QQ、163、126、Gmail、Outlook、iCloud、新浪、搜狐、139、阿里云、腾讯企业邮、Yahoo 等预置项，选中后自动填充服务器与端口；选择“自定义 / 其他邮箱”可手动填写，也可以直接覆盖自动填充结果。
- **账号信息**：邮箱地址与密码。多数服务商要求先开启 IMAP/SMTP 服务，并使用客户端授权码或应用专用密码，而不是网页登录密码；Gmail 需开启两步验证后创建[应用专用密码](https://support.google.com/accounts/answer/185833?hl=zh-Hans)。
- **服务器信息**：IMAP 与 SMTP 的主机、端口与加密方式（SSL/TLS 或 STARTTLS）。

连接时会同时验证收信（IMAP 登录）与发信（SMTP 登录）两个通道，任一失败都不会保存账号；验证过程不发送邮件。

邮件能力按标准 IMAP 实现，不依赖任何厂商扩展：

- 文件夹即标签，用户文件夹可新增、重命名、删除与应用；系统文件夹按服务器特殊用途标志（`\Sent`、`\Drafts`、`\Trash`、`\Junk`、`\Archive`、`\All`）识别，缺失时按常见文件夹名回退。
- 会话按 `References`/`In-Reply-To` 聚合，缺少线索时回退到规范化主题。
- 星标与已读使用 IMAP 标志位；归档在服务商提供“所有邮件”或“归档”文件夹时可用，否则提示不支持。
- 搜索使用标准 IMAP `SEARCH`，支持 `from:`、`to:`、`subject:` 前缀，其余关键词按正文匹配。
- 移动优先使用 `MOVE` 扩展，不支持时降级为 `COPY` + 删除；草稿使用 `APPEND` 写入草稿箱。
- 发送成功后会把副本 `APPEND` 到已发送文件夹；该步骤失败时界面会提示手动确认。
- 接入使用 Python 标准库 `imaplib`/`smtplib`。服务商管理员策略或账户安全设置可能限制 IMAP 使用。

凭据加密保存在数据库，浏览器不接收明文密码；连接失效时会提示重新连接或更新密码/授权码。

## 邮件功能

- 会话列表、搜索、阅读、已读/未读、星标、归档、垃圾邮件、回收站、批量操作。
- 新邮件、回复、回复全部、转发、纯文本撰写、抄送/密送、附件。
- 停笔约 2 秒后自动保存草稿；关闭写信窗口先保存。重开草稿从服务端草稿箱获取。
- 文件夹即标签，可新增、重命名、删除、应用及移除；系统文件夹不能修改名称或删除。
- 默认不加载远程图片；HTML 来信经过清洗，并在隔离 iframe 中显示。内嵌图片可作为附件下载；不提供富文本编辑器。
- 搜索支持 `from:`、`to:`、`subject:` 前缀，其余关键词按正文匹配，范围为当前文件夹。
- 页面可见时每 60 秒同步当前邮箱，并支持手动刷新。未打开页面时不进行后台轮询。
- 不提供永久删除、联系人、统一收件箱或高级邮箱设置。

附件总大小限制为 **18 MiB**（为 MIME 编码和服务端请求上限留余量），上传临时文件 24 小时过期，过期文件在下一次上传或同步时清理。已成功保存的草稿附件保留在邮箱服务商，临时目录不是永久存储。

发送使用每封撰写会话的去重标识。遇到网络中断等结果不明的情况，页面禁止自动重发，请先检查“已发送”，再决定是否重新撰写。SMTP 部分收件人拒收会提示地址。发送成功后副本会写入服务端“已发送”文件夹；Gmail OAuth 由 Gmail 自动保存。

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

PostgreSQL 保存账户、绑定和同步游标，邮件本体保留在邮箱服务商。Redis 缓存列表/标签 60 秒、正文 5 分钟，同时存储会话、限流计数、发送去重和互斥锁。附件不写入 Redis。

后端所有邮件操作经过用户归属校验。Google SDK 和标准库网络调用在线程池执行；每个邮箱串行处理，避免同步、刷新凭据与草稿更新互相覆盖。通用 IMAP 用不透明的「文件夹 + UIDVALIDITY + UID」标识定位邮件，列表按 UID 分批抓取表头并按会话聚合后分页；草稿清理仅使用定向 UID EXPUNGE。

## 检查范围

遵照要求，最终验收仅执行后端镜像和本机前端构建检查。**没有执行功能、单元、端到端、安全或浏览器操作测试**。Gmail 授权、真实收发、API 操作及其他业务交互由你手动验收；未使用任何真实邮箱凭据或发送邮件。

具体构建及启动结果见 [docs/BUILD-REPORT.md](docs/BUILD-REPORT.md)。
