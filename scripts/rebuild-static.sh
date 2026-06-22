#!/bin/bash
# my-blog 静态文件重新生成脚本
# 用途：每日 cron 跑一次，把新发布的文章生成静态 HTML
# 适用：my-blog v2.7.0+ 全静态部署（nginx serve .output/public/）
#
# 工作流程：
#   1. 拉最新代码（可选）
#   2. 跑 nuxt generate → .output/public/
#   3. rsync 替换 /var/www/blog/
#   4. nginx reload（已经指向新文件，但保险起见 reload）
#   5. 写日志
#
# 前置：
#   1. nginx 已配置 root 指向 /var/www/blog/（或者你自定义的 STATIC_DIR）
#   2. deploy 用户对 /opt/myblog/frontend 和 $STATIC_DIR 有写权限
#
# 用法：
#   # 单次跑
#   sudo -u deploy bash /opt/myblog/scripts/rebuild-static.sh
#
#   # crontab -e（deploy 用户视角）
#   0 3 * * * bash /opt/myblog/scripts/rebuild-static.sh
#
# 环境变量覆盖（可放 /opt/myblog/.env.rebuild）：
#   API_BASE        - 后端 API 地址（默认 https://yourname.com/api/v1）
#   FRONTEND_DIR    - frontend 目录（默认 /opt/myblog/frontend）
#   STATIC_DIR      - nginx 服务的静态目录（默认 /var/www/blog）
#   NGINX_RELOAD    - 是否 reload nginx（默认 yes）
#   LOG_FILE        - 日志文件（默认 /var/log/myblog-rebuild.log）
#   KEEP_BUILDS     - 保留最近几次构建产物（默认 3）

set -e

# ============ 默认配置 ============
API_BASE="${API_BASE:-https://yourname.com/api/v1}"
FRONTEND_DIR="${FRONTEND_DIR:-/opt/myblog/frontend}"
STATIC_DIR="${STATIC_DIR:-/var/www/blog}"
NGINX_RELOAD="${NGINX_RELOAD:-yes}"
LOG_FILE="${LOG_FILE:-/var/log/myblog-rebuild.log}"
KEEP_BUILDS="${KEEP_BUILDS:-3}"
BACKUP_DIR="${FRONTEND_DIR}/.output/.history"

# ============ 颜色输出 ============
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'
log()  { echo -e "${GREEN}[$(date +%Y-%m-%d\ %H:%M:%S)]${NC} $1" | tee -a "$LOG_FILE"; }
warn() { echo -e "${YELLOW}[$(date +%Y-%m-%d\ %H:%M:%S)]${NC} $1" | tee -a "$LOG_FILE"; }
err()  { echo -e "${RED}[$(date +%Y-%m-%d\ %H:%M:%S)]${NC} $1" | tee -a "$LOG_FILE"; exit 1; }

START_TS=$(date +%s)

# ============ 1. 目录检查 ============
[ -d "$FRONTEND_DIR" ] || err "frontend 目录不存在: $FRONTEND_DIR"
[ -f "$FRONTEND_DIR/package.json" ] || err "找不到 package.json: $FRONTEND_DIR/package.json"
mkdir -p "$BACKUP_DIR" "$(dirname "$LOG_FILE")"

# ============ 2. 备份当前构建产物（失败时回滚）============
if [ -d "$FRONTEND_DIR/.output/public" ]; then
    BACKUP_NAME="public-$(date +%Y%m%d-%H%M%S)"
    log "备份当前产物到 $BACKUP_DIR/$BACKUP_NAME ..."
    cp -a "$FRONTEND_DIR/.output/public" "$BACKUP_DIR/$BACKUP_NAME"

    # 清理旧备份（保留最近 KEEP_BUILDS 份）
    if [ "$KEEP_BUILDS" -gt 0 ]; then
        ls -1dt "$BACKUP_DIR"/public-* 2>/dev/null | tail -n +$((KEEP_BUILDS + 1)) | xargs -r rm -rf
    fi
fi

# ============ 3. 拉最新代码（可选）============
cd "$FRONTEND_DIR"
if [ -d .git ]; then
    log "拉取最新代码..."
    # 静默 git pull，只 pull 静态文件相关的内容（不影响后端 jar）
    if ! git pull --ff-only 2>>"$LOG_FILE"; then
        warn "git pull 失败（可能没配置 SSH 或无更新），继续用本地代码构建"
    fi
fi

# ============ 4. 跑 nuxt generate ============
log "开始构建（API_BASE=$API_BASE）..."
log "这一步需要 30-90 秒，吃 200-300MB 内存..."

# npm ci 之前先确保 node_modules 存在
if [ ! -d node_modules ]; then
    log "安装依赖..."
    npm ci --no-audit --no-fund 2>>"$LOG_FILE" || err "npm ci 失败"
fi

# 跑 generate
NUXT_PUBLIC_API_BASE="$API_BASE" npm run build 2>>"$LOG_FILE" || {
    err "nuxt generate 失败！查看日志: $LOG_FILE
如需回滚：cp -a $BACKUP_DIR/$(ls -1t $BACKUP_DIR | head -1) $FRONTEND_DIR/.output/public"
}

# ============ 5. 校验产物 ============
[ -f "$FRONTEND_DIR/.output/public/index.html" ] || err ".output/public/index.html 不存在，构建可能失败"
GENERATED_COUNT=$(find "$FRONTEND_DIR/.output/public" -name "*.html" | wc -l | tr -d ' ')
log "✅ 构建完成，生成 $GENERATED_COUNT 个 HTML 文件"

# ============ 6. 同步到 nginx 服务目录 ============
log "同步到 $STATIC_DIR ..."
mkdir -p "$STATIC_DIR"
# 先同步到临时目录，再原子 rename（避免 nginx 在同步过程中读到半截文件）
TMP_DIR="${STATIC_DIR}.new"
rm -rf "$TMP_DIR"
cp -a "$FRONTEND_DIR/.output/public" "$TMP_DIR"
# 原子切换
mv "$TMP_DIR" "$STATIC_DIR.tmp"
rm -rf "$STATIC_DIR"
mv "$STATIC_DIR.tmp" "$STATIC_DIR"
log "✅ 同步完成"

# ============ 7. reload nginx（保险）============
if [ "$NGINX_RELOAD" = "yes" ]; then
    if command -v nginx >/dev/null 2>&1; then
        if nginx -t 2>>"$LOG_FILE"; then
            nginx -s reload 2>>"$LOG_FILE" || warn "nginx reload 失败（不影响新文件访问）"
            log "✅ nginx reloaded"
        else
            warn "nginx 配置校验失败，跳过 reload"
        fi
    else
        warn "找不到 nginx 命令，跳过 reload"
    fi
fi

# ============ 8. 健康检查 ============
sleep 2
HEALTH_URL="http://localhost${STATIC_PORT:-3000}/healthz"
if command -v curl >/dev/null 2>&1; then
    if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
        log "✅ 健康检查通过: $HEALTH_URL"
    else
        warn "健康检查失败: $HEALTH_URL（如果用了反代，请检查外层 nginx）"
    fi
fi

# ============ 9. 完成统计 ============
ELAPSED=$(( $(date +%s) - START_TS ))
log "=== Rebuild 完成（耗时 ${ELAPSED}s）==="
log "新文章最迟 24h 内可见（下次 cron 跑）"
log ""
