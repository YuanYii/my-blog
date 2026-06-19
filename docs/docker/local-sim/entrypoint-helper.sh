#!/bin/bash
# =============================================================
# 容器内首次进入时的引导脚本
#
# 干什么：
#   1. 检查 SQLite 是否初始化（db 文件是否存在）
#   2. 检查 /opt/myblog/blog-app.jar 是否就位
#   3. 检查 supervisord 是否在跑（redis / nginx 起来了）
#   4. 打印下一步怎么操作（跑部署、初始化 db、看日志）
#
# 不自动执行部署——容器第一次启动时 db 还没建、jar 还没下，得用户显式跑
# 这样能模拟"真实 ECS 首次部署"的感觉
#
# 用法：
#   docker exec -it myblog-sim entrypoint-helper
#   或：docker exec -it myblog-sim bash -c "entrypoint-helper"
# =============================================================

set -e

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; BLUE='\033[0;34m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*"; }
title() { echo -e "${BLUE}== $* ==${NC}"; }

INSTALL_DIR="${INSTALL_DIR:-/opt/myblog}"
DB_FILE="$INSTALL_DIR/db/blog.db"
JAR_FILE="$INSTALL_DIR/blog-app.jar"
DEPLOY_SCRIPT="$INSTALL_DIR/scripts/deploy-server.sh"

title "my-blog 本地模拟生产环境 — 状态检查"

# ---------- 1. SQLite ----------
echo
title "1. SQLite 数据库"
if [ -f "$DB_FILE" ]; then
    info "✅ $DB_FILE 已存在 (size: $(du -h "$DB_FILE" | cut -f1))"
    info "   表列表: $(sqlite3 "$DB_FILE" '.tables' 2>/dev/null | tr '\n' ' ')"
else
    warn "❌ $DB_FILE 不存在（首次部署需要初始化）"
    echo "   手动初始化:"
    echo "     sqlite3 $DB_FILE < /opt/myblog/scripts/schema-sqlite.sql   # 若已挂载 schema"
    echo "   或等 deploy-server.sh 第一次跑完自动建（看 §5 步骤）"
fi

# ---------- 2. jar ----------
echo
title "2. 后端 jar"
if [ -f "$JAR_FILE" ]; then
    info "✅ $JAR_FILE 已就位 (size: $(du -h "$JAR_FILE" | cut -f1))"
else
    warn "❌ $JAR_FILE 不存在（需要 deploy-server.sh 下载/拷贝进来）"
fi

# ---------- 3. supervisord ----------
echo
title "3. supervisord 进程状态"
if pgrep -x supervisord >/dev/null 2>&1; then
    info "✅ supervisord 运行中"
    echo
    supervisorctl status
else
    err "❌ supervisord 未运行（容器异常？）"
    exit 1
fi

# ---------- 4. 下一步建议 ----------
echo
title "下一步怎么操作"

if [ ! -f "$DEPLOY_SCRIPT" ]; then
    warn "⚠️  $DEPLOY_SCRIPT 不存在"
    echo "   容器内还没部署过任何东西。需要从宿主机把 deploy-server.sh 拷进来："
    echo
    echo "     # 在宿主机上跑："
    echo "     docker cp docs/scripts/deploy-server.sh myblog-sim:$INSTALL_DIR/scripts/"
    echo
    echo "   拷完后进容器跑部署："
    echo
    echo "     docker exec -it myblog-sim bash"
    echo "     cd $INSTALL_DIR/scripts"
    echo "     chmod +x deploy-server.sh"
    echo "     GITHUB_REPO=owner/repo ./deploy-server.sh v4.1.0   # 跟生产一样的参数"
    echo
else
    info "✅ $DEPLOY_SCRIPT 已就位"
    echo "   在容器内跑："
    echo
    echo "     cd $INSTALL_DIR/scripts"
    echo "     GITHUB_REPO=owner/repo ./deploy-server.sh v4.1.0"
    echo
fi

echo "   常用命令："
echo "     supervisorctl status                 # 看 redis / nginx / myblog 状态"
echo "     supervisorctl tail -f myblog         # 实时后端日志（替代 journalctl -u myblog -f）"
echo "     supervisorctl restart myblog         # 部署完重启后端"
echo "     curl http://127.0.0.1:8080/api/v1/health   # 后端健康检查"
echo "     curl -I http://127.0.0.1:80/               # nginx 健康检查"
echo
info "完成。容器 ID: $(hostname)"