#!/usr/bin/env bash
# 服务器端发布脚本：由 GitHub Actions 通过 SSH 调用，不执行任何构建。
# 自定义环境变量：
#   DEPLOY_DIR   编排目录，默认 /opt/hmail
#   COMPOSE_FILE 编排文件名，默认 compose.yaml
#   WAIT_TIMEOUT docker compose --wait 超时秒数，默认 180
set -Eeuo pipefail

DEPLOY_DIR="${DEPLOY_DIR:-/opt/hmail}"
COMPOSE_FILE="${COMPOSE_FILE:-compose.yaml}"
WAIT_TIMEOUT="${WAIT_TIMEOUT:-180}"

log() { printf '[deploy] %s\n' "$*"; }
fail() { printf '[deploy] 错误：%s\n' "$*" >&2; exit 1; }

[ -d "$DEPLOY_DIR" ] || fail "编排目录 $DEPLOY_DIR 不存在"
cd "$DEPLOY_DIR"
[ -f "$COMPOSE_FILE" ] || fail "未找到 $DEPLOY_DIR/$COMPOSE_FILE"
[ -f ".env" ] || fail "未找到 $DEPLOY_DIR/.env，请先按 $DEPLOY_DIR/.env.example 创建生产配置"

# .env 中的镜像地址是部署的基准：CI 推送的是 <sha> 与 latest 两个标签，这里必须与之匹配
env_value() { sed -n "s/^[[:space:]]*$1[[:space:]]*=[[:space:]]*//p" .env | tail -n 1 | sed -e 's/^"//' -e 's/"$//' -e "s/^'//" -e "s/'$//" -e 's/[[:space:]]*$//'; }
HMAIL_IMAGE_VALUE="$(env_value HMAIL_IMAGE)"
[ -n "$HMAIL_IMAGE_VALUE" ] || fail "在 $DEPLOY_DIR/.env 中未找到 HMAIL_IMAGE，请填写 ghcr.io/<owner>/hmail-backend:latest"
case "$HMAIL_IMAGE_VALUE" in
  *[A-Z]*) fail "HMAIL_IMAGE 含大写字母（$HMAIL_IMAGE_VALUE），GHCR 要求全小写" ;;
  *:*) ;;
  *) fail "HMAIL_IMAGE（$HMAIL_IMAGE_VALUE）缺少标签，应形如 ghcr.io/<owner>/hmail-backend:latest" ;;
esac

dc() { sudo docker compose -f "$COMPOSE_FILE" "$@"; }

if sudo test -s /root/.docker/config.json || [ -s "$HOME/.docker/config.json" ]; then
  log "检测到 Docker 凭据，私有 GHCR 镜像可直接拉取"
else
  log "提示：未发现 Docker 凭据，若拉取失败请先执行 sudo docker login ghcr.io -u <github-user>"
fi

# 拉取前确认远端确实存在该镜像与标签，避免 compose 抛难以定位的 manifest unknown
image_repo="${HMAIL_IMAGE_VALUE%%:*}"
image_tag="${HMAIL_IMAGE_VALUE##*:}"
log "校验远端镜像 ${image_repo}:${image_tag}"
if ! sudo docker manifest inspect "$HMAIL_IMAGE_VALUE" >/dev/null 2>&1; then
  sudo docker manifest inspect "$image_repo:latest" >/dev/null 2>&1 \
    && fail "镜像标签不存在：$HMAIL_IMAGE_VALUE（仓库存在，标签可能写错；可用标签：latest 或某个 commit sha）" \
    || fail "镜像仓库不存在或无权访问：$image_repo（请核对 owner 是否全小写、与仓库地址一致，以及该包是否可见）"
fi

log "拉取镜像（服务器不构建）"
dc pull

log "启动服务并等待健康检查（最长 ${WAIT_TIMEOUT}s）"
set +e
dc up -d --remove-orphans --wait --wait-timeout "$WAIT_TIMEOUT"
status=$?
set -e

log "当前容器状态"
dc ps || true

if [ "$status" -ne 0 ]; then
  log "部署未通过健康检查，输出 API 最近日志"
  dc logs --tail 80 api || true
  fail "docker compose up 返回非零状态，部署失败"
fi

log "镜像与应用（API 迁移在容器启动时自动执行）"
dc ps --format 'table {{.Service}}\t{{.Image}}\t{{.Status}}' || true
log "后端部署完成"
