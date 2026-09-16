# Hmail Android

## 工程与界面

- 包名 `com.glassous.hmail`，最低 Android 13，固定服务 `https://hmail.fiacloud.top`。
- Kotlin + Material 3 Views；单 Activity、Fragment、Navigation、ViewModel；RecyclerView 展示邮件。
- `MailData.kt`：接口客户端、类型、加密存储。`MailModel.kt`：会话、分页、草稿和操作状态。
- `MainActivity.kt`：覆盖式 DrawerLayout / NavigationView、主题及通用页面组件。
- `AccountScreens.kt`、`MailScreens.kt`、`ComposeFragment.kt`：账户管理、阅读与写信页面。
- 浅色/深色/跟随系统；不使用动态取色。颜色来自当前 Web CSS 和 Tailwind 4.1.5 的 sRGB 映射。主蓝色是 `#155DFC`，并非旧版 Tailwind 的 `#2563EB`；固定背景色与 Web 原值一致。
- 连接邮箱、写信、设置和标签编辑均为独立页面；确认弹窗只用于删除或断开操作。

## 功能对应清单

下表为实现覆盖清单，运行效果由用户手动验收。

| Web 功能 | 后端接口（均位于 `/api/v1`） | Android 入口 |
|---|---|---|
| 服务能力、服务商预设 | GET `/config` | 启动、连接邮箱 |
| 登录、注册、验证码、重置 | POST `/auth/login`、`register`、`send-code`、`reset` | 登录、注册、重设密码 |
| 恢复会话、主题同步 | GET/PATCH `/me` | 启动、设置 |
| 修改密码、退出 | POST `/me/password`、`/auth/logout` | 设置 |
| 多邮箱、连接、重新连接、断开 | GET `/gmail-accounts`，POST `/gmail-accounts/imap`，DELETE `/gmail-accounts/{aid}` | 侧栏、邮箱管理、连接邮箱 |
| Google 连接 | 移动端授权接口，见下节 | 连接邮箱 → 使用 Google 连接 |
| 文件夹、查询、分页 | GET `/gmail-accounts/{aid}/threads` | 邮件首页、搜索 |
| 手动刷新、冷启动同步 | POST `/gmail-accounts/{aid}/sync` | 顶栏刷新；冷启动恢复会话时同步一次 |
| 会话、已读、星标 | GET `/gmail-accounts/{aid}/threads/{tid}`，POST `…/messages/modify` | 阅读页、选中后顶栏星标 |
| 归档、垃圾邮件、回收站、恢复、读/未读、批量操作 | POST `…/messages/modify` | 长按/头像多选、更多菜单 |
| 标签新建、重命名、删除 | GET/POST `…/labels` | 侧栏 → 管理标签 |
| 标签应用与移除 | POST `…/messages/modify` | 选择标签页 |
| HTML、纯文本、图片 | 会话返回的清洗 HTML；`/proxy/image`、签名内嵌附件 | 阅读页 |
| 附件下载 | GET `…/messages/{mid}/attachments/{part}` | 阅读页 → 系统保存位置 |
| 写信、回复、回复全部、转发 | POST `…/send` | 写信页、阅读页 |
| 上传与移除附件 | POST `…/attachments` | 写信页 → 系统文件选择器 |
| 草稿列表、恢复、保存、删除 | GET `…/drafts`、GET/DELETE `…/drafts/{did}`、PUT `…/drafts` | 草稿箱、写信页 |

路由定义：`android/app/src/main/res/navigation/routes.xml`，扁平结构，起始页为 `login`。首屏由本地会话立即决定：有会话时在首次绘制前切换为 `inbox`（主页），否则保持 `login`；启动阶段不等待网络，没有启动页和加载动画，进入页面后再加载数据。认证页与主页同为回退栈栈底，在这些页面按返回键直接退出应用，登录后不会再回退到登录页。路由只传递 account、thread、label 等标识；正文、密码和授权数据不进入路由。

邮件同步只在顶栏手动刷新和冷启动恢复会话时各触发一次，前台不做定时轮询；Google 授权期间的三秒轮询仅针对未完成的授权流程。会话与列表状态属于进程而非 Activity：挂后台被回收后重建界面时沿用原数据与滚动位置，既不清空也不重新拉取。页面导航（返回主页、离开写信页）同样不触发列表拉取；只有归档、标签等改动邮件本身的操作才会同步列表。

## 会话和写信

- 原生请求复用后端 Cookie 会话，写请求带固定 Origin 和 CSRF；失效后返回登录。
- Cookie、主题、授权查询凭证和未完成写信内容通过 Android Keystore AES-GCM 加密存储；禁用云备份和设备迁移。密码仅存在当前进程表单，不保存到视图状态或磁盘。
- 请求关闭自动重试及自动重定向；取消过期列表请求，并校验请求代次和邮箱标识。
- 草稿编辑两秒防抖、串行保存。返回写信页之前的页面时完成保存，正常草稿留在邮箱，允许继续创建其他邮件。
- 发送前持久化“待确认”状态。发送失败结果不明或发送期间进程终止时，恢复后禁止重复发送，提供已发送入口。
- 本地草稿按 Hmail 用户和发件邮箱绑定；退出保留加密内容，下次登录对应用户时恢复。
- HTML WebView 禁用脚本、文件访问、第三方 Cookie 和非白名单资源；原生会话不会注入 WebView。链接由系统浏览器打开。

## 移动端 Google 授权

新增接口：

1. POST `/gmail-accounts/oauth/mobile/start`：需要会话、Origin、CSRF。返回 `{url, ticket, expiresIn: 600}`。
2. GET `/gmail-accounts/oauth/mobile/status`：需要原会话以及 `X-OAuth-Ticket` 请求头。返回 `{status}`，取值 `waiting`、`success`、`cancelled`、`failed`、`expired`。

复用原 Google HTTPS 回调地址。后端的一次性 state 绑定发起用户和会话；移动回调不依赖浏览器 Cookie，在交换凭证前后校验原会话有效性。Google 凭证不返回手机。Web 授权仍使用原登录会话校验。

应用前台每三秒查询未完成的授权，浏览器完成页提示返回 Hmail。无需新增深链或 Google Android 客户端配置。现有 Google Web OAuth 配置仍须有效。

后端改动未自动部署；手机 Google 连接需要服务部署此版本后使用。现有其余功能沿用原接口。

## 构建与手动验收

按本次约定，仅执行一次 `android/gradlew.bat assembleDebug --no-daemon --console=plain`。不运行单元测试、仪器测试、模拟器或设备交互测试。构建失败后停止，不修改并重试。

- 构建日志：`artifacts/android-build.log`。
- 构建成功时的 APK：`android/app/build/outputs/apk/debug/app-debug.apk`。
- 构建结果以本次日志及交付消息为准；此文档不代表运行测试已通过。

供用户手动验收：

- [ ] 登录、重启恢复、注册验证码、重置密码、修改密码和退出。
- [ ] 连接两个邮箱；切换邮箱与文件夹；连接失效后重连；断开邮箱。
- [ ] Google 授权成功、取消、过期；回到应用后邮箱出现。
- [ ] 邮件分页与搜索；阅读后返回保持列表位置；长按多选及全部邮件操作。
- [ ] 标签创建、修改、删除、应用和移除。
- [ ] HTML/纯文本邮件、内嵌图片、外部链接、附件保存。
- [ ] 新邮件、回复、回复全部、带附件转发；添加/移除附件和大小限制。
- [ ] 自动保存、返回保存、草稿箱重开、旋转屏幕、进程退出后恢复未完成邮件。
- [ ] 网络中断和发送结果不明时不重复发送。
- [ ] 浅色、深色、跟随系统；侧栏覆盖、返回键、多选退出与键盘避让。
- [ ] 主页内任意区域右滑都能呼出侧栏，不与列表点击、纵向滚动冲突；侧栏露出时返回键关闭侧栏而不退出应用。
- [ ] 冷启动立刻显示对应页面：已登录进主页、未登录进登录页，无启动页与加载动画；登录后按返回键退出应用。
- [ ] 邮件只响应顶栏手动刷新与冷启动同步，前台停留时不会自动刷新。
