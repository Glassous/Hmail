# 构建记录

日期：2026-09-15

## 已完成的构建检查

- 后端 Docker 镜像 `hmail-api:latest` 构建成功。
- 后端依赖通过清华 PyPI 镜像安装成功。
- 前端在 Windows 本机执行 `vue-tsc -b && vite build` 成功。
- Vite 生产构建生成 HTML、Tailwind CSS 和 JavaScript 静态资源。
- Python 后端源文件通过 `compileall` 语法编译检查。

## 当前运行方式

- 前端不使用容器，由用户在 `frontend` 目录手动执行 `npm run dev`。
- Vite 未指定开发端口，默认从 5173 开始，端口占用时自动递增。
- Docker Compose 仅包含 FastAPI、PostgreSQL 18 和 Redis。
- 后端映射到 `127.0.0.1:8184`，前端开发服务器将 `/api` 代理至该地址。

## 未执行的测试

根据用户要求，本轮最终验收不执行功能测试、单元测试、端到端测试、安全测试、浏览器自动化或真实 Gmail 收发测试。OAuth、IMAP/SMTP、注册登录、密保找回及邮件操作均由用户手动验证。

Google OAuth 的默认 `PUBLIC_URL` 为 `http://localhost:5173`。若 Vite 实际使用递增端口，需要同步修改 `.env` 和 Google Cloud OAuth 回调配置，并重新创建 API 容器。
