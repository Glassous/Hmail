# Gmail 官方接口集成清单

## SDK 与协议

- [Google 官方 Python 客户端](https://github.com/googleapis/google-api-python-client)
- [Google 官方 Python 授权示例](https://developers.google.com/workspace/gmail/api/quickstart/python)
- [完整 Gmail API REST 参考](https://developers.google.com/workspace/gmail/api/reference/rest)
- [Gmail IMAP 扩展](https://developers.google.com/workspace/gmail/imap/imap-extensions)

OAuth 模式请求 `https://www.googleapis.com/auth/gmail.modify`，后端通过 `users/me` 调用 Gmail。IMAP/SMTP 模式以应用专用密码认证，不调用 Gmail REST API。

## 已接入日常功能

| 官方 API | 功能 | 入口 |
|---|---|---|
| [users.getProfile](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users/getProfile) | 身份确认、同步基线 | OAuth 回调、同步 |
| [threads.list/get](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.threads) | 会话列表、搜索、展开 | threads |
| threads.modify/trash/untrash | 会话批量标签和回收站 | messages/modify 的 threadIds |
| [messages.get](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/get) | MIME、正文、附件描述 | messages、threads |
| messages.batchModify/trash/untrash | 单封/多封标志与回收站 | messages/modify 的 ids |
| [messages.send](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages/send) | 新邮件、回复、转发 | send |
| [messages.attachments.get](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.messages.attachments/get) | 下载附件 | messages/{id}/attachments/{part} |
| [drafts.list/get/create/update/delete](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.drafts) | 草稿恢复、自动保存、删除 | drafts |
| drafts.send | 官方适配器保留方法；当前统一发送入口使用 messages.send 后清理旧草稿 | 未单独开放路由 |
| [labels.list/create/patch/delete](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.labels) | 标签查询及管理 | labels |
| [history.list](https://developers.google.com/workspace/gmail/api/reference/rest/v1/users.history/list) | 增量检测、缓存失效 | sync |

`messages.list`、`messages.modify`、`labels.get/update` 有等价的会话列表/批量修改/标签 patch 实现，首版不重复开放相同用途接口。

## 收集但不启用

| API 类别 | 首版状态 |
|---|---|
| users.watch / stop | 不启用 Pub/Sub，使用可见页面定时同步 |
| messages.delete / batchDelete、threads.delete | 不提供邮件永久删除，也不申请完整 mail.google.com 权限 |
| messages.import / insert | 不实现邮件迁移导入 |
| users.settings 基本设置、filters、sendAs、vacation、language、imap、pop | 不实现高级邮箱设置 |
| users.settings forwardingAddresses、delegates 等共享管理 | 不实现；部分操作限 Workspace 管理场景 |

## 统一应用 API

基础路径 `/api/v1`；完整请求字段可查看运行中的 `/api/openapi.json`。

| 路径 | 方法 | 用途 |
|---|---|---|
| /health、/config | GET | 连通性、公开功能配置 |
| /auth/register、/auth/login、/auth/logout | POST | 平台会话 |
| /auth/recovery-question | GET | 获取密保题目 |
| /auth/recover、/auth/reset | POST | 验证答案、一次性密码重设 |
| /me | GET、PATCH | 当前账户、主题偏好 |
| /me/password | POST | 修改密码及可选密保，撤销会话 |
| /gmail-accounts | GET | 当前用户的 Gmail 绑定 |
| /gmail-accounts/imap | POST | 验证 IMAP/SMTP 并绑定 |
| /gmail-accounts/oauth/start | POST | 创建一次性 OAuth 状态 |
| /gmail-accounts/oauth/callback | GET | 接收 Google 授权回调 |
| /gmail-accounts/{aid} | DELETE | 解除绑定 |
| /gmail-accounts/{aid}/threads | GET | folder、q、cursor 分页 |
| /gmail-accounts/{aid}/threads/{tid} | GET | 会话内邮件 |
| /gmail-accounts/{aid}/messages/{mid} | GET | 单封邮件，可选 remote 图片授权 |
| /gmail-accounts/{aid}/messages/modify | POST | 标志、标签、回收站批量操作 |
| /gmail-accounts/{aid}/messages/{mid}/attachments/{part} | GET | 授权下载 |
| /gmail-accounts/{aid}/attachments | POST | 临时上传 |
| /gmail-accounts/{aid}/labels | GET、POST | 标签查询/新建/重命名/删除 |
| /gmail-accounts/{aid}/drafts | GET、PUT | 草稿列表、带版本保存 |
| /gmail-accounts/{aid}/drafts/{did} | GET、DELETE | 获取和删除草稿 |
| /gmail-accounts/{aid}/send | POST | 去重发送 |
| /gmail-accounts/{aid}/sync | POST | 同步当前邮箱文件夹 |

写请求的 `Origin` 必须与 `.env` 中的 `PUBLIC_URL` 一致，默认是 `http://localhost:5173`；已登录写请求还需要 `X-CSRF-Token`，从登录响应或 `/me` 获取。认证使用 HttpOnly Cookie，不使用浏览器本地令牌。错误包含 `code`、`message`、`requestId`。
