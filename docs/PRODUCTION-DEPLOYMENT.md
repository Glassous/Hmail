# Hmail 正式部署与 Google OAuth 发布指南

本文说明如何将 Hmail 从本地测试环境迁移到公网生产环境，并把 Google OAuth 应用从“测试中”推进为可供外部用户使用的正式应用。

> 当前项目定位仍是本地原型。正式发布前需要完成本文“上线前必须补充的能力”，并根据所在地法律和实际数据处理方式审阅隐私政策及服务条款。

## 1. 目标架构

假设正式域名为 `mail.example.com`：

```text
用户浏览器
    │ HTTPS :443
    ▼
mail.example.com
    ├── /                 前端静态文件（frontend/dist）
    └── /api/*            反向代理到 FastAPI :8000
                              ├── PostgreSQL
                              ├── Redis
                              └── Google Gmail API
```

生产环境只向公网开放 80（跳转 HTTPS）和 443。FastAPI、PostgreSQL、Redis 均应位于私有网络，不直接暴露公网端口。

推荐将生产环境与本地开发环境完全分开：使用独立服务器、数据库、加密密钥和 Google Cloud 项目。Google 也要求生产 OAuth 项目中不要保留仅开发人员可访问的测试来源或回调地址。

## 2. 上线前准备

准备以下资源：

- 一个自己拥有并可管理 DNS 的域名，例如 `example.com`。
- 一台能够稳定访问 Google API 的 Linux 服务器。
- HTTPS 证书，可使用 Let's Encrypt 或云厂商证书服务。
- 独立的生产 Google Cloud 项目。
- 用于接收审核通知的长期有效邮箱。
- 隐私政策、服务条款和用户支持渠道。
- PostgreSQL 与加密密钥的安全备份位置。

服务器必须能够访问至少以下 Google 服务：

```text
accounts.google.com
oauth2.googleapis.com
gmail.googleapis.com
```

如果服务器所在地区无法稳定访问这些服务，OAuth 授权、令牌刷新和邮箱同步都会失败。部署前应先确认网络和 DNS 可用。

## 3. 域名、DNS 与 HTTPS

1. 为 `mail.example.com` 添加指向服务器公网 IP 的 A/AAAA 记录。
2. 在服务器上配置 Nginx、Caddy 或同类反向代理。
3. 为 `mail.example.com` 申请可信 HTTPS 证书。
4. 将 HTTP 请求永久重定向到 HTTPS。
5. 配置 HSTS 前，先确认所有页面和子资源都能通过 HTTPS 正常访问。

生产地址统一使用：

```text
应用首页：https://mail.example.com/
API：https://mail.example.com/api/v1/
OAuth 回调：https://mail.example.com/api/v1/gmail-accounts/oauth/callback
```

反向代理应至少设置：

- `/` 返回 `frontend/dist` 中的静态文件，并为 Vue 路由回退到 `index.html`。
- `/api/` 代理到 FastAPI 容器的 8000 端口。
- 请求体上限不低于项目的附件限制。
- 将原始 Host、客户端 IP 和 HTTPS 协议传递给后端。
- 为长时间 Gmail 请求配置合理的代理超时。
- 添加 CSP、`X-Content-Type-Options`、`Referrer-Policy` 和点击劫持防护响应头。

不要在生产环境运行 Vite 开发服务器。前端应构建为静态资源：

```bash
cd frontend
npm ci
npm run build
```

构建产物位于 `frontend/dist`。

## 4. 生产环境变量

使用专门的密钥管理服务或只允许服务账号读取的环境配置文件，不要把生产密钥提交到 Git。

```env
POSTGRES_PASSWORD=<高强度随机数据库密码>
TOKEN_ENCRYPTION_KEY=<Fernet加密密钥>
GOOGLE_CLIENT_ID=<生产OAuth客户端ID>
GOOGLE_CLIENT_SECRET=<生产OAuth客户端密钥>
PUBLIC_URL=https://mail.example.com
PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
```

说明：

- `POSTGRES_PASSWORD`：只用于生产数据库。数据库初始化后不能只修改环境变量，修改密码时必须同时更新数据库账户。
- `TOKEN_ENCRYPTION_KEY`：加密 OAuth 令牌和 Gmail 应用专用密码。丢失后无法恢复已有邮箱连接。
- `GOOGLE_CLIENT_ID` 和 `GOOGLE_CLIENT_SECRET`：必须来自生产 Google Cloud 项目。
- `PUBLIC_URL`：必须是用户实际访问的 HTTPS 来源，不带结尾 `/`。
- `PIP_INDEX_URL`：后端镜像构建使用的 Python 包索引，可根据生产网络调整。

生成 Fernet 密钥：

```bash
python -c "from cryptography.fernet import Fernet; print(Fernet.generate_key().decode())"
```

对 `TOKEN_ENCRYPTION_KEY` 做加密备份。更换密钥前必须实现密钥轮换和重新加密流程，否则现有绑定全部失效。

## 5. 生产容器部署

当前 `compose.yaml` 面向本地运行，API 映射到 `127.0.0.1:8184`。生产部署可以继续让反向代理访问该本地端口，也可以将反向代理加入同一个容器网络并完全取消 API 主机端口映射。

部署原则：

- Docker Compose 只运行 FastAPI、PostgreSQL 和 Redis。
- PostgreSQL 与 Redis 不配置公网端口。
- 为数据库和 Redis 使用独立持久化数据卷。
- 设置容器自动重启与健康检查。
- 在启动 API 前运行数据库迁移。
- 定期备份 PostgreSQL，并演练恢复过程。
- 日志不得记录密码、密保答案、OAuth 令牌、应用专用密码和邮件正文。

典型启动命令：

```bash
docker compose build api
docker compose up -d
docker compose ps
```

升级时先备份数据库，再更新代码、构建镜像并执行迁移。不要使用会删除命名卷的命令，除非已经确认要永久清空生产数据。

## 6. 创建独立的生产 Google Cloud 项目

不要直接把本地测试项目当作长期生产项目。推荐流程：

1. 创建 `Hmail Production` Google Cloud 项目。
2. 为项目配置长期有效的 Owner、Editor 和联系邮箱。
3. 启用 Gmail API。
4. 在 Google Auth Platform 中将用户类型设置为“外部”。
5. 创建“Web 应用”类型的 OAuth 客户端。
6. 只添加生产域名，不添加 `localhost` 或内部测试地址。
7. 将生产 Client ID 和 Client Secret 安全写入服务器配置。

OAuth 客户端配置：

| Google Cloud 字段 | 配置值 |
|---|---|
| 已获授权的 JavaScript 来源 | `https://mail.example.com` |
| 已获授权的重定向 URI | `https://mail.example.com/api/v1/gmail-accounts/oauth/callback` |

“JavaScript 来源”不能包含路径。“重定向 URI”必须包含完整回调路径，协议、主机、端口和路径必须与应用生成的地址完全一致。

## 7. 验证域名所有权

在 [Google Search Console](https://search.google.com/search-console/) 验证根域名 `example.com`。推荐使用 DNS TXT 记录验证。

执行验证的 Google 账号必须同时是生产 Google Cloud 项目的 Owner 或 Editor。OAuth 配置涉及的首页、隐私政策、服务条款、JavaScript 来源和回调地址所使用的域名都必须由你拥有并能够验证。

## 8. 准备公开页面

在提交 Google 审核前，以下页面必须通过公网 HTTPS 访问，且不能要求审核人员先登录：

```text
https://mail.example.com/
https://mail.example.com/privacy
https://mail.example.com/terms
https://mail.example.com/support
```

### 首页

首页不能只有登录表单，应清楚展示：

- Hmail 的开发者或运营主体。
- 应用用于连接、阅读、发送和整理 Gmail 邮件。
- 用户能获得的实际功能。
- 隐私政策、服务条款和支持页面链接。
- 用户如何连接、断开 Gmail 以及删除数据。

### 隐私政策

隐私政策必须准确反映真实实现，至少说明：

- 获取哪些 Google 用户数据。
- 为什么需要访问邮件、标签、草稿和附件。
- 数据如何使用、缓存、存储、加密和删除。
- OAuth 令牌和应用专用密码如何保存。
- 是否与第三方共享或传输数据。
- 数据保留期限和缓存过期时间。
- 用户如何撤销授权和申请删除账户及数据。
- 安全事件的联系和通知方式。
- 应用遵守 Google API Services User Data Policy，包括 Limited Use 要求。

隐私政策页面必须与 OAuth 同意页面填写的链接完全一致，并从首页清晰可达。

### 服务条款与支持页面

服务条款说明用户责任、可接受使用、服务中断、账户终止和责任范围。支持页面提供真实有效的联系渠道、常见问题、数据删除和授权撤销说明。

## 9. OAuth 同意页面配置

在生产项目的 Google Auth Platform 中填写：

- 应用名称：Hmail。
- 用户类型：外部。
- 应用首页。
- 隐私政策地址。
- 服务条款地址。
- 用户支持邮箱。
- 开发者联系邮箱。
- 已验证的授权域名。
- 与正式网站一致的 Logo 和品牌信息。

项目当前申请：

```text
https://www.googleapis.com/auth/gmail.modify
```

该权限用于读取、撰写、发送和管理 Gmail 邮件，属于受限权限。提交审核时应只声明已经上线且可供审核人员操作的功能，不要为未来功能预先申请权限。

权限用途说明应包含：

- 读取并展示邮件正文和会话。
- 搜索用户邮箱。
- 标记已读、星标、归档、垃圾邮件和回收站。
- 管理 Gmail 标签。
- 创建、更新和删除 Gmail 草稿。
- 发送、回复和转发邮件。

同时解释为什么只读或只发送等更窄权限无法覆盖完整邮箱客户端功能。

## 10. 从测试切换为正式应用

应用、公网页面和生产 OAuth 客户端准备好之后：

1. 打开 Google Auth Platform 的“受众群体”。
2. 点击“发布应用”，将状态改为“In production”。
3. 检查“数据访问”中只保留实际使用的权限。
4. 点击“Prepare for verification”或“提交验证”。
5. 填写品牌信息、权限用途和数据处理说明。
6. 上传或提交演示视频链接。
7. 提供审核人员使用的平台测试账户。
8. 提供从登录到 Google 授权以及使用受限权限功能的操作步骤。
9. 提交后及时回复项目 Owner/Editor 邮箱收到的审核问题。

切换到“In production”只会改变发布状态，并不表示受限权限已经验证。验证完成之前，用户仍可能看到“未经验证的应用”提示，并受到累计 100 个新用户限制。

## 11. 审核演示材料

建议录制连续、清晰且可识别域名的视频，演示：

1. 打开 Hmail 公网首页及隐私政策。
2. 注册或登录平台账户。
3. 点击“使用 Google 连接”。
4. 展示 OAuth 同意页面中的应用名称和申请权限。
5. 完成授权并返回 Hmail。
6. 阅读邮件、搜索、修改标签或归档邮件。
7. 创建草稿并发送测试邮件。
8. 断开 Gmail 连接。
9. 删除平台账户和服务器保存的数据。

为审核人员提供：

- 登录地址。
- 可使用的平台测试账号。
- 密保或其他登录步骤。
- 每个权限对应功能的具体入口。
- 必要的 Gmail 测试数据和操作说明。

不要向审核人员提供真实用户账户或生产用户数据。

## 12. 受限权限安全评估

`gmail.modify` 是 Gmail 受限权限。应用服务器会接收、处理或传输邮件数据，并保存 OAuth 刷新令牌，因此 Google 可能要求由认可评估机构执行安全评估并出具 Letter of Assessment。受限权限应用还可能需要定期或年度复评。

安全评估通常关注：

- HTTPS、TLS 和安全响应头。
- OAuth 令牌、应用专用密码和备份的加密。
- 密钥管理、访问控制和轮换流程。
- 平台用户与 Gmail 绑定的严格隔离。
- 数据删除与保留期限。
- 数据库、Redis、临时附件和日志的保护。
- 漏洞扫描、依赖升级和补丁管理。
- 安全事件监控、响应、报告和用户通知。
- 生产访问权限和管理员操作审计。
- 备份恢复和业务连续性。

Google 会在受限权限验证过程中告知何时启动安全评估。费用由第三方评估机构根据范围报价，应提前预留时间和预算。

## 13. 上线前必须补充的项目能力

当前代码在正式公开发布前至少需要增加或完善：

- 生产静态站点与 HTTPS 反向代理配置。
- 首页产品介绍、隐私政策、服务条款和支持页面。
- 用户自主删除平台账户、绑定、令牌、缓存和临时附件的完整流程。
- 明确的数据保留、到期和清理机制。
- Google 授权撤销或断开后的彻底凭据清理。
- 生产密钥管理和可审计的密钥轮换机制。
- PostgreSQL 加密备份、恢复演练和访问控制。
- 管理员最小权限、审计日志和安全事件响应流程。
- 依赖漏洞扫描、镜像扫描和常规升级流程。
- 按 Google 品牌规范实现授权按钮和应用品牌。
- 审核专用环境、测试账号、操作说明和演示视频。
- 比单个密保问题更可靠的公开账户找回方案，例如已验证邮箱、恢复码或多因素认证。

IMAP＋SMTP 应用专用密码虽然不经过本项目的 Google OAuth 同意页面，但属于高价值长期凭据。正式公开提供该入口前，需要在隐私政策中明确披露，并采用与 OAuth 令牌同等级别的加密、访问控制、撤销和删除措施。也应评估是否只在自托管或管理员允许的环境中开放该备选功能。

## 14. 上线检查表

### 基础设施

- [ ] DNS 已生效。
- [ ] HTTPS 证书有效并支持自动续期。
- [ ] HTTP 自动跳转 HTTPS。
- [ ] PostgreSQL、Redis 和 FastAPI 未直接暴露公网。
- [ ] 数据库备份和恢复演练完成。
- [ ] 生产密钥未进入源码、日志或镜像层。
- [ ] 容器健康检查、重启策略和日志轮转已配置。

### 应用

- [ ] 首页、隐私政策、条款和支持页面可公开访问。
- [ ] 注册、登录、找回、注销和账户删除流程完整。
- [ ] Gmail 连接、撤销和删除数据流程完整。
- [ ] 用户之间的数据、缓存和附件完全隔离。
- [ ] 日志不包含敏感数据或邮件内容。
- [ ] 生产环境不运行 Vite 开发服务器。

### Google OAuth

- [ ] 使用独立生产 Google Cloud 项目。
- [ ] Gmail API 已启用。
- [ ] 域名已通过 Search Console 验证。
- [ ] JavaScript 来源和回调地址全部使用正式 HTTPS 域名。
- [ ] OAuth 同意页面信息与网站一致。
- [ ] 只申请 `gmail.modify` 等实际使用的权限。
- [ ] 应用已切换到“In production”。
- [ ] 权限用途、演示视频和审核测试账户准备完成。
- [ ] 已提交 OAuth 验证并及时处理审核反馈。
- [ ] 已按 Google 要求完成受限权限安全评估。

## 15. 官方参考资料

- [Google OAuth 生产合规指南](https://developers.google.com/identity/protocols/oauth2/production-readiness/policy-compliance)
- [Google OAuth 验证要求](https://support.google.com/cloud/answer/13464321)
- [提交 OAuth 应用验证](https://support.google.com/cloud/answer/13461325)
- [OAuth 应用受众与发布状态](https://support.google.com/cloud/answer/15549945)
- [Gmail API 权限说明](https://developers.google.com/workspace/gmail/api/auth/scopes)
- [Google Workspace 用户数据与开发者政策](https://developers.google.com/workspace/workspace-api-user-data-developer-policy)
- [Google Search Console](https://search.google.com/search-console/)

Google 的控制台页面、审核表单和政策可能更新。正式提交前应重新核对上述官方文档，并以 Google Cloud Console 当时显示的要求为准。
