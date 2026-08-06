#!/bin/bash
# 服务器端执行:一键从 GitHub Release 拉资源 + 装环境 + 部署 my-blog
# 适用:CentOS 7+ / Ubuntu 18.04+ / Debian 10+ 最小化系统
# 跑法(root 或 sudo):
#   curl -L https://raw.githubusercontent.com/OWNER/REPO/main/scripts/deploy-server.sh -o deploy-server.sh
#   chmod +x deploy-server.sh
#   sudo ./deploy-server.sh v6.0.2
#
# ----- LOCAL_SIM 模式（2026-06-19 本地模拟容器用，docs/docker/local-sim）-----
# 当 LOCAL_SIM=1 时，自动跳过 systemd/apt/防火墙等生产专属步骤，
# 改用 supervisord 管理 redis/nginx/myblog 三个进程。
# 自动检测：容器内 /.dockerenv 文件存在 → 自动 LOCAL_SIM=1。
# 手动覆盖：LOCAL_SIM=1 ./deploy-server.sh 显式开启；LOCAL_SIM=0 强制关闭（即便在容器内）。
# 详见 docs/docker/local-sim/deploy-server-local.patch.txt
#
# 自定义参数(环境变量):
#   GITHUB_REPO=owner/repo       必填
#   INSTALL_DIR=/opt/myblog      部署目录(默认)
#   SERVER_PORT=8080             Spring Boot 端口
#   PUBLIC_PORT=80               nginx 端口
#   DB_FILE=/opt/myblog/db/blog.db  SQLite 文件位置
#   SKIP_DEPS=0                  设为 1 跳过依赖安装(已装过的话)
#   OPEN_FIREWALL=1              设为 1 自动 firewalld/ufw 放行 PUBLIC_PORT(默认 1)
#   ENABLE_HTTPS=0               设为 1 启用 Let's Encrypt HTTPS 证书 + 80→443 跳转(默认 0)
#                                 开启需同时设 HTTPS_DOMAIN 和 HTTPS_EMAIL
#   HTTPS_DOMAIN=blog.croeyai.cn 域名(不带协议,单域场景,多域暂不支持)
#   HTTPS_EMAIL=your@email.com   Let's Encrypt 通知邮箱(过期/吊销提醒)
#   DEPLOY_MODE=full             部署模式(默认 full)
#                                 init = 首次初始化(schema 建库 + 种子数据,不导入业务数据)
#                                 full = 全量代码升级(保留 DB + 增量 SQL migration)
#                                 docker-create = 本地容器创建(build + run myblog-sim,复用 docs/deployment/docker/local-sim/)
#                                 docker-init = 本地容器业务初始化(docker cp + exec 进容器跑 deploy-server.sh)
#   IMPORT_DB=0                  是否导入 release 中的加密 db dump(默认 0)
#                                 设为 1 后从交互式输入密码,调 sqlite-import.sh 解密导入
#                                 产物:dev-blog-dump.sql.gz.enc (publish-release.sh 加 EXPORT_DB=1 才有)
#
# v4.4.0 模式集合精简(7 → 4):
#   - 删除: code / frontend / backend / sql / data
#   - 新增: docker-create / docker-init(本地模拟生产,本地 dev 机用)
#   - 替代: frontend/backend/sql → full(全量升级)
#           code → full(等价)
#           data → full + IMPORT_DB=1(显式开环境变量)

set -euo pipefail

# LOCAL_SIM 自动检测（容器内 /.dockerenv 存在 → 自动 1）
if [ -z "${LOCAL_SIM:-}" ] && [ -f /.dockerenv ]; then
    export LOCAL_SIM=1
    echo "[LOCAL_SIM] /.dockerenv detected, enabling LOCAL_SIM=1 (supervisor mode)"
fi

# ============= 0. 参数解析 =============
# 加载 deploy.env（可选，命令行 export 优先）
SCRIPT_DIR_DEPLOY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR_DEPLOY/deploy.env" ]; then
    _CL_EXPORT_DB="${EXPORT_DB:-}"; _CL_IMPORT_DB="${IMPORT_DB:-}"
    _CL_DEPLOY_MODE="${DEPLOY_MODE:-}"; _CL_ENABLE_HTTPS="${ENABLE_HTTPS:-}"
    _CL_HTTPS_DOMAIN="${HTTPS_DOMAIN:-}"; _CL_HTTPS_EMAIL="${HTTPS_EMAIL:-}"
    _CL_CDN_SSL="${CDN_SSL:-}"
    set -a; source "$SCRIPT_DIR_DEPLOY/deploy.env"; set +a
    [ -n "$_CL_EXPORT_DB" ] && EXPORT_DB="$_CL_EXPORT_DB"
    [ -n "$_CL_IMPORT_DB" ] && IMPORT_DB="$_CL_IMPORT_DB"
    [ -n "$_CL_DEPLOY_MODE" ] && DEPLOY_MODE="$_CL_DEPLOY_MODE"
    [ -n "$_CL_ENABLE_HTTPS" ] && ENABLE_HTTPS="$_CL_ENABLE_HTTPS"
    [ -n "$_CL_HTTPS_DOMAIN" ] && HTTPS_DOMAIN="$_CL_HTTPS_DOMAIN"
    [ -n "$_CL_HTTPS_EMAIL" ] && HTTPS_EMAIL="$_CL_HTTPS_EMAIL"
    [ -n "$_CL_CDN_SSL" ] && CDN_SSL="$_CL_CDN_SSL"
    unset _CL_EXPORT_DB _CL_IMPORT_DB _CL_DEPLOY_MODE _CL_ENABLE_HTTPS _CL_HTTPS_DOMAIN _CL_HTTPS_EMAIL _CL_CDN_SSL
fi

TAG="${1:-${RELEASE_TAG:-}}"
TAG_WAS_AUTO=0
GITHUB_REPO="${GITHUB_REPO:-}"
INSTALL_DIR="${INSTALL_DIR:-/opt/myblog}"

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# 如果未指定版本号，自动获取 GitHub 最新 release
if [ -z "$TAG" ] && [ -n "$GITHUB_REPO" ] && [ "${LOCAL_SIM:-0}" != "1" ]; then
    info "未指定版本号，正在获取 GitHub 最新 release..."
    TAG=$(curl -fsSL "https://api.github.com/repos/$GITHUB_REPO/releases/latest" | grep -o '"tag_name":"[^"]*"' | cut -d'"' -f4)
    if [ -z "$TAG" ]; then
        err "无法获取最新版本，请指定版本号"
        err "Usage: $0 <tag>  e.g. $0 v6.0.2"
        exit 1
    fi
    info "最新版本: $TAG"
    TAG_WAS_AUTO=1
fi
SERVER_PORT="${SERVER_PORT:-8080}"
PUBLIC_PORT="${PUBLIC_PORT:-80}"
DB_FILE="${DB_FILE:-$INSTALL_DIR/db/blog.db}"
SKIP_DEPS="${SKIP_DEPS:-0}"
OPEN_FIREWALL="${OPEN_FIREWALL:-1}"
_DEPLOY_MODE_FROM_ENV=${DEPLOY_MODE:+1}
DEPLOY_MODE="${DEPLOY_MODE:-full}"      # init | full | docker-create | docker-init
IMPORT_DB="${IMPORT_DB:-0}"             # 0/1
LOCAL_SIM="${LOCAL_SIM:-0}"             # 0=生产模式  1=本地模拟容器模式
ENABLE_HTTPS="${ENABLE_HTTPS:-0}"       # 0/1
HTTPS_DOMAIN="${HTTPS_DOMAIN:-}"
HTTPS_EMAIL="${HTTPS_EMAIL:-}"
CDN_SSL="${CDN_SSL:-0}"                 # 0/1

# 交互式确认：提示风险，要求用户输入确认词
# 用法: confirm_deploy "确认词" "风险描述"
confirm_deploy() {
    local word="$1" risk="$2" input
    echo ""
    warn "=========================================="
    warn " $risk"
    warn "=========================================="
    echo ""
    read -r -p "  输入 \"$word\" 确认操作: " input
    if [ "$input" != "$word" ]; then
        err "输入不匹配，操作已取消"
        exit 1
    fi
    info "确认通过，继续执行..."
}

# 校验
if [ -z "$TAG" ]; then
    err "Usage: $0 <tag>  e.g. $0 <current-tag>"
    err "Or:RELEASE_TAG=<tag> $0"
    exit 1
fi
if [ -z "$GITHUB_REPO" ] && [ "$LOCAL_SIM" != "1" ]; then
    err "GITHUB_REPO not set. export GITHUB_REPO=owner/repo and retry"
    exit 1
fi

# 必须 root（容器内 LOCAL_SIM=1 默认就是 root，跳过这个校验）
if [ "$EUID" -ne 0 ] && [ "$LOCAL_SIM" != "1" ]; then
    err "Please run as root or with sudo"
    exit 1
fi

# DEPLOY_MODE 校验 (v4.4.0 重构:7 模式 → 4 模式)
case "$DEPLOY_MODE" in
    init|full|docker-create|docker-init) ;;
    *) err "DEPLOY_MODE must be init / full / docker-create / docker-init, got: $DEPLOY_MODE"
       err "  Note: code/frontend/backend/sql/data were removed in v4.4.0, use 'full' instead"
       exit 1 ;;
esac

# (v4.4.0: 'data' 模式已删除,矛盾检测块随之移除——'full + IMPORT_DB=1' 是合法组合,不构成矛盾)

# 无参数部署（TAG 自动获取 + DEPLOY_MODE 未显式指定）→ 默认 init，先检查是否已有部署
# 注意: upgrade-agent 调用时会显式设 DEPLOY_MODE=full, _DEPLOY_MODE_FROM_ENV=1 → 此块跳过
if [ "$TAG_WAS_AUTO" = "1" ] && [ "${_DEPLOY_MODE_FROM_ENV:-0}" = "0" ]; then
    ALREADY_DEPLOYED=0
    if [ -f "$DB_FILE" ]; then
        ALREADY_DEPLOYED=1
    fi
    if [ "$LOCAL_SIM" != "1" ] && systemctl is-active --quiet myblog 2>/dev/null; then
        ALREADY_DEPLOYED=1
    fi
    if [ "$ALREADY_DEPLOYED" = "1" ]; then
        err "=========================================="
        err " 检测到已有部署，拒绝覆盖初始化"
        err "=========================================="
        err ""
        err "  $DB_FILE 已存在 或 myblog 服务正在运行"
        err ""
        err "  如需升级到最新版本:"
        err "    export GITHUB_REPO=$GITHUB_REPO"
        err "    sudo -E $0 $TAG"
        err ""
        err "  或显式指定升级模式:"
        err "    export GITHUB_REPO=$GITHUB_REPO"
        err "    export DEPLOY_MODE=full"
        err "    sudo -E $0 $TAG"
        err ""
        err "  如需强制重新初始化（清空所有数据）:"
        err "    export GITHUB_REPO=$GITHUB_REPO"
        err "    export DEPLOY_MODE=init"
        err "    sudo -E $0 $TAG"
        exit 1
    fi
    DEPLOY_MODE="init"
    info "未指定版本号 → 全新部署，默认 DEPLOY_MODE=init"
fi

# ENABLE_HTTPS=1 时必填 HTTPS_DOMAIN + HTTPS_EMAIL
if [ "$ENABLE_HTTPS" = "1" ]; then
    if [ -z "$HTTPS_DOMAIN" ] || [ -z "$HTTPS_EMAIL" ]; then
        err "ENABLE_HTTPS=1 requires HTTPS_DOMAIN and HTTPS_EMAIL"
        err "  HTTPS_DOMAIN=blog.croeyai.cn  (no protocol)"
        err "  HTTPS_EMAIL=your@email.com"
        exit 1
    fi
    if echo "$HTTPS_DOMAIN" | grep -qE '^https?://'; then
        err "HTTPS_DOMAIN must NOT contain protocol, got: $HTTPS_DOMAIN"
        exit 1
    fi
    if echo "$HTTPS_DOMAIN" | grep -q '/'; then
        err "HTTPS_DOMAIN must NOT contain path, got: $HTTPS_DOMAIN"
        exit 1
    fi
fi

# 已有部署 + init 模式 → 交互式确认（防误操作）
ALREADY_DEPLOYED=0
[ -f "$DB_FILE" ] && ALREADY_DEPLOYED=1
if [ "$LOCAL_SIM" != "1" ] && systemctl is-active --quiet myblog 2>/dev/null; then
    ALREADY_DEPLOYED=1
fi

if [ "$ALREADY_DEPLOYED" = "1" ] && [ "$DEPLOY_MODE" = "init" ]; then
    confirm_deploy "INIT" \
        "⚠️  危险操作: DEPLOY_MODE=init 将清空 $DB_FILE 的全部数据（会自动备份旧 DB 到 $INSTALL_DIR/db/backups/）"
fi

# (v4.4.0: sql/code 模式已删除,这两个 if 块随之移除)
#   - sql 模式:功能被 full 模式覆盖
#   - code 模式 + IMPORT_DB=1:等价 full + IMPORT_DB=1,合法组合,无需警告

info "=========================================="
info " my-blog one-click deployment"
info " Tag:        $TAG"
info " Repo:       $GITHUB_REPO"
info " InstallDir: $INSTALL_DIR"
info " Ports:      app=$SERVER_PORT, public=$PUBLIC_PORT"
info " Mode:       DEPLOY_MODE=$DEPLOY_MODE, IMPORT_DB=$IMPORT_DB"
info "=========================================="
echo

# ============= 1. 检测系统 =============
info "=== 1. Detecting system ==="
. /etc/os-release
OS_ID="${ID:-unknown}"
OS_VER="${VERSION_ID:-unknown}"
info "System: ${PRETTY_NAME:-$OS_ID $OS_VER}"

PKG=""
case "$OS_ID" in
    centos|rhel|rocky|almalinux|amzn)
        PKG="yum"
        [ -x "$(command -v dnf 2>/dev/null)" ] && PKG="dnf"
        ;;
    ubuntu|debian)
        PKG="apt"
        ;;
    *)
        err "Unrecognized system: $OS_ID.Only CentOS/RHEL/Ubuntu/Debian are supported."
        exit 1
        ;;
esac
info "Package manager: $PKG"

# ============= 2. 安装依赖 =============
install_deps() {
    info "=== 2. Installing dependencies ==="
    case "$PKG" in
        yum|dnf)
            $PKG install -y epel-release || true
            $PKG install -y \
                java-1.8.0-openjdk java-1.8.0-openjdk-devel \
                wget curl unzip \
                sqlite \
                redis \
                nginx \
                python3 \
                lsof \
                perl
            ;;
        apt)
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -y

            # 核心依赖（不含 JDK，先装以确保 wget/gnupg 可用于 JDK 回退安装）
            apt-get install -y \
                wget curl unzip gnupg \
                sqlite3 \
                redis-server \
                nginx \
                python3 \
                lsof \
                perl

            # JDK 8：优先系统包 → Ubuntu 22.04+ / Debian 12+ 回退到 Adoptium Temurin
            if apt-get install -y openjdk-8-jdk 2>/dev/null; then
                info "Installed openjdk-8-jdk from system repos"
            else
                warn "openjdk-8-jdk not in system repos (Ubuntu 22.04+ / Debian 12+), falling back to Eclipse Temurin JDK 8"
                wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
                    | gpg --dearmor --yes -o /usr/share/keyrings/adoptium.gpg 2>/dev/null \
                    || { err "Failed to add Adoptium GPG key"; exit 1; }
                local os_codename
                os_codename=$(awk -F= '/^VERSION_CODENAME/{print $2}' /etc/os-release)
                echo "deb [signed-by=/usr/share/keyrings/adoptium.gpg] https://packages.adoptium.net/artifactory/deb $os_codename main" \
                    > /etc/apt/sources.list.d/adoptium.list
                apt-get update -y -qq
                apt-get install -y temurin-8-jdk \
                    || { err "temurin-8-jdk install failed"; exit 1; }
                info "Installed Eclipse Temurin JDK 8"
            fi
            ;;
    esac

    # 安装 gh CLI（备份脚本 gh release 需要）
    if ! command -v gh >/dev/null 2>&1; then
        info "Installing gh CLI..."
        if command -v apt-get >/dev/null 2>&1; then
            curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg \
                | dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg 2>/dev/null \
                && echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" \
                | tee /etc/apt/sources.list.d/github-cli.list > /dev/null \
                && apt-get update -y -qq \
                && apt-get install -y -qq gh \
                || warn "gh CLI install failed, backup will fallback to curl+jq"
        elif command -v yum >/dev/null 2>&1; then
            yum install -y 'dnf-command(config-manager)' || true
            yum config-manager --add-repo https://cli.github.com/packages/rpm/gh-cli.repo \
                && yum install -y gh \
                || warn "gh CLI install failed, backup will fallback to curl+jq"
        fi
    fi

    # 验证
    for bin in java sqlite3 redis-server nginx curl lsof python3; do
        if ! command -v $bin >/dev/null 2>&1; then
            err "Dependency $bin failed to install"
            exit 1
        fi
    done
    info "All dependencies ready"
}

if [ "$SKIP_DEPS" = "1" ]; then
    info "SKIP_DEPS=1, skipping dependency install"
elif [ "$LOCAL_SIM" = "1" ]; then
    info "LOCAL_SIM=1, skipping §2 install_deps (Dockerfile already installs JDK 8/redis/nginx/sqlite3/supervisor)"
else
    install_deps
fi

# ============= 3. 创建部署目录 + 用户 =============
info "=== 3. Preparing directory structure ==="
mkdir -p "$INSTALL_DIR"/{logs,frontend,db,uploads,attachments} "$INSTALL_DIR/db/backups" "$INSTALL_DIR/logs/archive"
if [ "$LOCAL_SIM" = "1" ]; then
    info "LOCAL_SIM=1, skipping myblog user creation (running as root in container)"
else
    # app 用户
    if ! id -u myblog >/dev/null 2>&1; then
        useradd -r -s /bin/false myblog
        info "Creating user myblog"
    fi
    chown -R myblog:myblog "$INSTALL_DIR"
fi

# ============= 4. 下载资源 =============
info "=== 4. Downloading deployment package ==="
BASE_URL="https://github.com/$GITHUB_REPO/releases/download/$TAG"
TMP_DIR="$(mktemp -d)"
cd "$TMP_DIR"

download() {
    local f="$1"
    info "Downloading $f ..."
    if ! curl -fsSL -o "$f" "$BASE_URL/$f"; then
        err "Download failed: $BASE_URL/$f"
        err "Please check:"
        err "  1. tag $TAG exists"
        err "  2. GITHUB_REPO=$GITHUB_REPO is correct"
        err "  3. release assets contains $f"
        exit 1
    fi
}

# 优先下冷部署包, 没的话就单个下
BUNDLE="deploy-bundle-${TAG}.zip"
# 2026-06-22 修复:BUNDLE / SHA256SUMS 必须下到 TMP_DIR 里,否则 sha256sum -c 找不到文件会全 FAIL
#   原代码下到当前目录,但 sha256sum -c 在 TMP_DIR 跑,导致"沉默失败"(warn continue 掩盖了真错)
if curl -fsSL -o "$TMP_DIR/$BUNDLE" "$BASE_URL/$BUNDLE" 2>/dev/null; then
    info "Got cold-deployment package $BUNDLE, extracting first..."
    (cd "$TMP_DIR" && unzip -qo "$BUNDLE")
    # v5.3.0+ 确保 upgrade-agent 文件存在（旧版 bundle 可能不包含）
    if [ ! -f "$TMP_DIR/upgrade-agent.py" ]; then
        download "upgrade-agent.py" || warn "upgrade-agent.py download failed"
        download "upgrade-agent.service" || warn "upgrade-agent.service download failed"
    fi
    curl -fsSL -o "$TMP_DIR/SHA256SUMS" "$BASE_URL/SHA256SUMS" 2>/dev/null || warn "No SHA256SUMS, skipping verification"
    if [ -f "$TMP_DIR/SHA256SUMS" ]; then
        if command -v sha256sum >/dev/null; then
            info "Verifying sha256..."
            if ! (cd "$TMP_DIR" && sha256sum -c SHA256SUMS); then
                err "sha256 校验失败，部署包可能已损坏，拒绝部署"
                exit 1
            fi
            info "sha256 校验通过"
        fi
    fi
else
    warn "No cold-deployment package, downloading files individually"
    download "blog-app.jar"
    download "frontend-static.tar.gz"
    download "schema-sqlite.sql"
    download "upgrade.sql" || warn "upgrade.sql download failed (incremental upgrades will be skipped)"
    download "deploy-server.sh"
    download "sqlite-import.sh" || warn "sqlite-import.sh download failed (needed when IMPORT_DB=1)"
    # v4.2.0 数据备份脚本：admin 后台「数据备份」菜单由后端 ProcessBuilder 调它
    download "blog-backup.sh" || warn "blog-backup.sh download failed (v4.2.0+ data backup feature will not work)"
    download "sqlite-export.sh" || warn "sqlite-export.sh download failed (v4.3.0+ admin data export feature will not work)"
    # v5.3.0 系统升级代理
    download "upgrade-agent.py" || warn "upgrade-agent.py download failed (v5.3.0+ upgrade feature will not work)"
    download "upgrade-agent.service" || warn "upgrade-agent.service download failed (v5.3.0+ upgrade feature will not work)"
    # 通用数据刷数脚本
    download "universal-script.sh" || warn "universal-script.sh download failed"
    download "migrate-logs.sh" || warn "migrate-logs.sh download failed"
    download "nginx-http.conf" || warn "nginx-http.conf download failed (ENABLE_HTTPS=1 时需要)"
    download "nginx-https.conf" || warn "nginx-https.conf download failed (ENABLE_HTTPS=1 时需要)"
    # v5.0: blog-restore.sh 已废弃,RestoreExecutor 在同 JVM 内执行恢复,不再需要此脚本
    # IMPORT_DB=1 才尝试下 .enc(可选,不存在说明纯代码发版)
    if [ "$IMPORT_DB" = "1" ]; then
        download "dev-blog-dump.sql.gz.enc" || warn "dev-blog-dump.sql.gz.enc download failed (required when IMPORT_DB=1)"
    fi
fi

# sqlite-import.sh 是服务器端 IMPORT_DB=1 时调用的解密脚本,必须放到 $INSTALL_DIR/scripts/
mkdir -p "$INSTALL_DIR/scripts"
if [ -f "$TMP_DIR/sqlite-import.sh" ]; then
    cp "$TMP_DIR/sqlite-import.sh" "$INSTALL_DIR/scripts/sqlite-import.sh"
    chmod +x "$INSTALL_DIR/scripts/sqlite-import.sh"
    info "[OK] sqlite-import.sh installed to $INSTALL_DIR/scripts/"
else
    warn "sqlite-import.sh not in release (cannot decrypt .enc when IMPORT_DB=1)"
fi

# v4.2.0 数据备份脚本：deploy-server.sh 部署到 $INSTALL_DIR/scripts/blog-backup.sh
# 路径固定（BackupService 写死 /opt/myblog/scripts/blog-backup.sh）
if [ -f "$TMP_DIR/blog-backup.sh" ]; then
    cp "$TMP_DIR/blog-backup.sh" "$INSTALL_DIR/scripts/blog-backup.sh"
    chmod +x "$INSTALL_DIR/scripts/blog-backup.sh"
    info "[OK] blog-backup.sh installed to $INSTALL_DIR/scripts/"
else
    warn "blog-backup.sh not in release (v4.2.0+ data backup feature will not work)"
fi

# v5.0 数据恢复改为同 JVM 进程内执行 (RestoreExecutor),不再需要 blog-restore.sh 脚本
# 详见 docs/design/博客数据恢复方案设计.md

# v4.3.0+ 数据导出脚本：deploy-server.sh 部署到 $INSTALL_DIR/scripts/sqlite-export.sh
# 路径固定（BackupService 写死 /opt/myblog/scripts/sqlite-export.sh）
if [ -f "$TMP_DIR/sqlite-export.sh" ]; then
    cp "$TMP_DIR/sqlite-export.sh" "$INSTALL_DIR/scripts/sqlite-export.sh"
    chmod +x "$INSTALL_DIR/scripts/sqlite-export.sh"
    info "[OK] sqlite-export.sh installed to $INSTALL_DIR/scripts/"
else
    warn "sqlite-export.sh not in release (v4.3.0+ admin data export feature will not work)"
fi

# 历史日志迁移脚本：部署到 $INSTALL_DIR/logs/migrate-logs.sh
# 用于将根目录下旧日志归档到 archive/YYYY-MM/ 子目录
if [ -f "$TMP_DIR/migrate-logs.sh" ]; then
    cp "$TMP_DIR/migrate-logs.sh" "$INSTALL_DIR/logs/migrate-logs.sh"
    chmod +x "$INSTALL_DIR/logs/migrate-logs.sh"
    info "[OK] migrate-logs.sh installed to $INSTALL_DIR/logs/"
else
    warn "migrate-logs.sh not in release (legacy log migration feature will not work)"
fi

# universal-script.sh 通用数据刷数脚本：部署到 $INSTALL_DIR/scripts/
if [ -f "$TMP_DIR/universal-script.sh" ]; then
    cp "$TMP_DIR/universal-script.sh" "$INSTALL_DIR/scripts/universal-script.sh"
    chmod +x "$INSTALL_DIR/scripts/universal-script.sh"
    info "[OK] universal-script.sh installed to $INSTALL_DIR/scripts/"
else
    warn "universal-script.sh not in release"
fi

# v5.3.0 系统升级代理：部署到 $INSTALL_DIR/scripts/upgrade-agent.py
if [ -f "$TMP_DIR/upgrade-agent.py" ]; then
    cp "$TMP_DIR/upgrade-agent.py" "$INSTALL_DIR/scripts/upgrade-agent.py"
    chmod +x "$INSTALL_DIR/scripts/upgrade-agent.py"
    info "[OK] upgrade-agent.py installed to $INSTALL_DIR/scripts/"
else
    warn "upgrade-agent.py not in release (v5.3.0+ upgrade feature will not work)"
fi

# ============= 4.5 启动 Redis（提前：让 §5 stop_app / flush_redis 有 redis 可用）=============
# 2026-06-22 改动：原本在 §10 启动 Redis,但 §5 stop_app 之前需要 flush_redis,
#   要求 redis 已起. 故把 enable+restart 提前到 §4.5 (download 之后).
#   §10 保留为"验证 redis 在线"的兜底（idempotent, 多启动一次无害）.
info "=== 4.5 Starting Redis ==="
if [ "$LOCAL_SIM" = "1" ]; then
    supervisorctl restart redis
else
    systemctl enable redis-server 2>/dev/null || systemctl enable redis 2>/dev/null || true
    systemctl restart redis-server 2>/dev/null || systemctl restart redis 2>/dev/null || true
fi

# ============= 4.6 flush_redis 函数定义 =============
# 每次重启 myblog 之前清空 redis 缓存, 避免:
#   - 旧版本写入的 key schema 与新代码不兼容（序列化格式/字段名变更）
#   - 旧 prod 数据残留（限流计数器 / token 黑名单 / 配置缓存）与导入的新 db 不一致
#   - schema 改了字段名 / 类型后, 旧 cache value 反序列化报错
# 规则:
#   - FLUSH_REDIS=1 (默认) → 执行 FLUSHDB
#   - FLUSH_REDIS=0 → 跳过 (用户显式要求保留缓存, 比如只想 reload 代码不动数据)
#   - redis-cli 不存在 / redis ping 不通 → warn + skip, 不致命 (脚本不应被辅助步骤打断)
#   - REDIS_PASSWORD 从 env_file 读, 读不到用 fallback 无密码 (对齐 §7.5 默认值)
FLUSH_REDIS="${FLUSH_REDIS:-1}"

flush_redis() {
    if [ "$FLUSH_REDIS" != "1" ]; then
        info "  FLUSH_REDIS=0, skipping redis flush (cache preserved across restart)"
        return 0
    fi
    if ! command -v redis-cli >/dev/null 2>&1; then
        warn "  redis-cli not found, skipping redis flush (install redis-tools to enable)"
        return 0
    fi

    # 从 env_file 拿 REDIS_* 配置, 不存在用 fallback (127.0.0.1:6379 无密码, 对齐 §7.5 默认值)
    # [FIX] 2026-06-22 dryrun 撞墙:§4.6 在 §7.5 之前被调用,ENV_FILE 此时未定义 → set -u 触发 unbound variable
    # 修法:用 ${ENV_FILE:-} 兜底
    local host="127.0.0.1"
    local port="6379"
    local pass=""
    if [ -n "${ENV_FILE:-}" ] && [ -f "$ENV_FILE" ]; then
        # 用 . 拿而不是 source, 避免 env_file 里 set -e 干扰 / 副作用
        pass=$(. "$ENV_FILE" 2>/dev/null && echo "${REDIS_PASSWORD:-}")
        # host/port 在 env_file 里也是写死的 127.0.0.1:6379, 这里尊重 env_file 的设置但兜底
        host=$(. "$ENV_FILE" 2>/dev/null && echo "${REDIS_HOST:-$host}")
        port=$(. "$ENV_FILE" 2>/dev/null && echo "${REDIS_PORT:-$port}")
    fi

    # 先 PING 验证连通性 (连接失败 / auth 错都不会致命 flush, 只 warn)
    if ! redis-cli -h "$host" -p "$port" ${pass:+-a "$pass"} PING >/dev/null 2>&1; then
        warn "  redis at $host:$port not reachable, skipping flush"
        return 0
    fi

    # FLUSHDB 清当前 db (默认 db 0), 不用 FLUSHALL (会清掉所有 db, 风险大)
    if redis-cli -h "$host" -p "$port" ${pass:+-a "$pass"} FLUSHDB >/dev/null 2>&1; then
        info "  [OK] redis cache flushed ($host:$port, db=${REDIS_DB:-0})"
    else
        warn "  redis FLUSHDB failed (non-fatal), service may start with stale cache"
        return 0
    fi
}

# ============= 5. 部署 jar / schema / 应用配置 =============
# 根据 DEPLOY_MODE 决定执行哪些步骤
SKIP_JAR=false
SKIP_SCHEMA=false
SKIP_FRONTEND=false

# docker-create / docker-init 是顶层命令,不进入下面的部署流程,直接走 docker 分支
case "$DEPLOY_MODE" in
    docker-create)
        info "=== DEPLOY_MODE=docker-create: 容器创建与镜像构建 ==="
        DOCKER_DIR="$(dirname "$SCRIPT_DIR_DEPLOY")/docs/deployment/docker/local-sim"
        if [ ! -f "$DOCKER_DIR/Dockerfile" ]; then
            info "未在本地找到 Dockerfile，正在从 GitHub 自动下载构建所需物料..."
            DOCKER_DIR="$(mktemp -d)"
            curl -fsSL "https://raw.githubusercontent.com/YuanYii/my-blog/main/docs/deployment/docker/local-sim/Dockerfile" -o "$DOCKER_DIR/Dockerfile" || { err "下载 Dockerfile 失败"; exit 1; }
            curl -fsSL "https://raw.githubusercontent.com/YuanYii/my-blog/main/docs/deployment/docker/local-sim/supervisord.conf" -o "$DOCKER_DIR/supervisord.conf" || { err "下载 supervisord.conf 失败"; exit 1; }
            curl -fsSL "https://raw.githubusercontent.com/YuanYii/my-blog/main/docs/deployment/docker/local-sim/entrypoint-helper.sh" -o "$DOCKER_DIR/entrypoint-helper.sh" || { err "下载 entrypoint-helper.sh 失败"; exit 1; }
            chmod +x "$DOCKER_DIR/entrypoint-helper.sh"
        fi
        info "构建镜像 myblog-local-sim:latest ..."
        docker build -t myblog-local-sim:latest "$DOCKER_DIR"
        # 容器已存在则跳过创建(幂等)
        if docker ps -a --format '{{.Names}}' | grep -q '^myblog-sim$'; then
            info "myblog-sim 容器已存在,跳过 docker run"
            info "  如需重建: docker rm -f myblog-sim 后重跑"
        else
            info "创建并启动容器 myblog-sim ..."
            docker run -d --name myblog-sim \
                -p 28080:8080 \
                -p 28000:80 \
                -v myblog-sim-data:/opt/myblog/db \
                -v myblog-sim-uploads:/opt/myblog/uploads \
                myblog-local-sim:latest
        fi
        info "✅ myblog-sim 容器已就绪"
        info "  端口映射: 28080→8080 (后端) / 28000→80 (nginx 前端)"
        info "  下一步: ./deploy-server.sh docker-init ${TAG:-}"
        exit 0
        ;;
    docker-init)
        info "=== DEPLOY_MODE=docker-init: 容器业务部署初始化 ==="
        if ! docker ps -a --format '{{.Names}}' | grep -q '^myblog-sim$'; then
            err "myblog-sim 容器不存在,请先跑: ./deploy-server.sh docker-create"
            exit 1
        fi
        if [ -z "$TAG" ] && [ -n "$GITHUB_REPO" ]; then
            info "docker-init 未指定版本号，正在获取最新 release..."
            TAG=$(curl -fsSL "https://api.github.com/repos/$GITHUB_REPO/releases/latest" | grep -o '"tag_name":"[^"]*"' | cut -d'"' -f4)
        fi
        if [ -z "$TAG" ]; then
            err "docker-init 无法获取 tag，请指定版本号: ./deploy-server.sh docker-init <tag>"
            exit 1
        fi
        info "把 deploy-server.sh 拷进容器 ..."
        docker cp "$0" myblog-sim:/opt/myblog/scripts/deploy-server.sh
        docker exec myblog-sim bash -c "chmod +x /opt/myblog/scripts/deploy-server.sh"
        info "在容器内执行部署(容器内 /.dockerenv 自动 LOCAL_SIM=1) ..."
        docker exec -e GITHUB_REPO="$GITHUB_REPO" myblog-sim \
            bash -c "cd /opt/myblog/scripts && ./deploy-server.sh $TAG"
        info "✅ 容器内部署完成"
        info "  健康检查: curl http://localhost:28080/api/v1/health"
        exit 0
        ;;
esac

case "$DEPLOY_MODE" in
    init|full)
        # init:首次全量;full:全量升级(保留 DB + 增量 SQL)
        ;;
esac
# 2026-06-22 新增:stop_app 之前清空 redis,避免旧版本写入的 cache (限流计数/token 黑名单/配置缓存)
#   与新版本 jar 不兼容 (key schema/序列化格式/字段名变更 等). flush_redis 自身有 redis-cli/连通性检测,失败非致命.
flush_redis
stop_app() {
    if [ "$LOCAL_SIM" = "1" ]; then
        # 容器内：supervisord 管 myblog，stop 由 supervisorctl 负责（§8 后做）
        info "LOCAL_SIM=1, stop_app skipped (supervisord will restart myblog in §8)"
    elif systemctl is-active --quiet myblog 2>/dev/null; then
        info "Stopping existing myblog service..."
        systemctl stop myblog || true
    fi
    # 兜底:直接 kill 占用端口的进程
    if command -v lsof >/dev/null 2>&1; then
        PIDS=$(lsof -ti :$SERVER_PORT || true)
        if [ -n "$PIDS" ]; then
            warn "Port $SERVER_PORT in use (pid=$PIDS), killing"
            kill -9 $PIDS || true
        fi
    fi
}
stop_app

# 部署 jar (init/full 都部署)
if [ "$SKIP_JAR" = true ]; then
    info "    DEPLOY_MODE=$DEPLOY_MODE, skipping jar deployment"
else
    cp "$TMP_DIR/blog-app.jar" "$INSTALL_DIR/blog-app.jar"
    if [ "$LOCAL_SIM" != "1" ]; then
        chown myblog:myblog "$INSTALL_DIR/blog-app.jar"
    fi
    info "[OK] Backend jar deployed"
fi

# ---- schema 增量兜底已迁移至 upgrade.sql（§5.1 执行） ----

# 根据 DEPLOY_MODE 处理 DB
if [ "$SKIP_SCHEMA" = true ]; then
    # 完全跳过 schema (init/full 模式都执行 schema 步骤,本分支只在 SKIP_SCHEMA=true 时进入,v4.4.0 已无此场景,保留兜底)
    info "    DEPLOY_MODE=$DEPLOY_MODE, skipping schema (DB untouched)"
elif [ "$DEPLOY_MODE" = "init" ]; then
    # init 模式:删旧 DB → 全量重建(仅种子数据)
    if [ -f "$DB_FILE" ]; then
        cp "$DB_FILE" "$INSTALL_DIR/db/backups/blog-before-${TAG}-$(date +%Y%m%d-%H%M%S).db"
        info "Backed up existing DB before init (will be replaced)"
        rm -f "$DB_FILE"
    fi
    cp "$TMP_DIR/schema-sqlite.sql" "$INSTALL_DIR/schema-sqlite.sql"
    if [ "$LOCAL_SIM" != "1" ]; then
        chown myblog:myblog "$INSTALL_DIR/schema-sqlite.sql"
    fi
    sqlite3 "$DB_FILE" < "$INSTALL_DIR/schema-sqlite.sql"
    if [ "$LOCAL_SIM" != "1" ]; then
        chown myblog:myblog "$DB_FILE"
    fi
    info "[OK] SQLite initialized from schema (init mode - seed data only)"
else
    # full 模式:保留 DB + 跑增量 SQL migration (init 模式见上方 init 分支)
    cp "$TMP_DIR/schema-sqlite.sql" "$INSTALL_DIR/schema-sqlite.sql"
    if [ "$LOCAL_SIM" != "1" ]; then
        chown myblog:myblog "$INSTALL_DIR/schema-sqlite.sql"
    fi
    if [ ! -f "$DB_FILE" ]; then
        # DB 不存在:从 schema 建库(等价 init)
        sqlite3 "$DB_FILE" < "$INSTALL_DIR/schema-sqlite.sql"
        if [ "$LOCAL_SIM" != "1" ]; then
            chown myblog:myblog "$DB_FILE"
        fi
        info "[OK] SQLite created from schema (DB did not exist)"
    else
        info "DB exists at $DB_FILE, preserving business data"
    fi
fi

# ============= 5.1 执行增量升级脚本 =============
if [ -f "$DB_FILE" ]; then
    info "=== 5.1 Running upgrade.sql ==="
    UPGRADE_SQL="$TMP_DIR/upgrade.sql"
    if [ -f "$UPGRADE_SQL" ]; then
        # 执行 upgrade.sql 幂等部分（CREATE TABLE/INDEX IF NOT EXISTS）
        BASIC_SQL=$(sed '/^-- /d;/^$/d;/ALTER TABLE/d' "$UPGRADE_SQL")
        if [ -n "$BASIC_SQL" ]; then
            echo "$BASIC_SQL" | sqlite3 "$DB_FILE" && info "[OK] upgrade.sql (幂等部分) executed"
        fi
        # ALTER TABLE 逐条 PRAGMA 检查后执行（非幂等，防止重复添加报错）
        # backup_record.trace_id（v4.2.0+）
        if ! sqlite3 "$DB_FILE" "PRAGMA table_info(backup_record);" | grep -q trace_id; then
            info "  Patching backup_record: adding trace_id column"
            sqlite3 "$DB_FILE" "ALTER TABLE backup_record ADD COLUMN trace_id VARCHAR(64);" \
                && info "  [OK] backup_record.trace_id column added"
        fi
        # article.is_pinned（v6.0.2+）
        if ! sqlite3 "$DB_FILE" "PRAGMA table_info(article);" | grep -q is_pinned; then
            info "  Patching article: adding is_pinned column"
            sqlite3 "$DB_FILE" "ALTER TABLE article ADD COLUMN is_pinned TINYINT NOT NULL DEFAULT 0;" \
                && sqlite3 "$DB_FILE" "CREATE INDEX IF NOT EXISTS idx_article_pinned ON article(is_pinned);" \
                && info "  [OK] article.is_pinned column added"
        fi
    else
        info "  No upgrade.sql found, skipping"
    fi
fi

# ============= 6. 部署前端静态文件 =============
if [ "$SKIP_FRONTEND" = true ]; then
    info "=== 6. Deploying frontend static files ==="
    info "    DEPLOY_MODE=$DEPLOY_MODE, skipping frontend deployment"
else
    info "=== 6. Deploying frontend static files ==="
    rm -rf "$INSTALL_DIR/frontend"/*
    tar -xzf "$TMP_DIR/frontend-static.tar.gz" -C "$INSTALL_DIR/frontend/"
    if [ "$LOCAL_SIM" != "1" ]; then
        chown -R myblog:myblog "$INSTALL_DIR/frontend"
    fi
    info "[OK] Static files deployed to $INSTALL_DIR/frontend"
fi

# ============= 7. 写 application 配置 =============
# 策略:脚本生成的 application.yml 通过 --spring.config.additional-location 叠加在 jar 之上
# 历史坑：jar 内 active: dev 需由 additional-location 显式覆盖，否则跑 dev profile
info "=== 7. Writing Spring Boot config ==="
APP_YML="$INSTALL_DIR/application.yml"
cat > "$APP_YML" <<EOF
# 部署期叠加配置(叠加在 jar 里的 application-prod.yml 之上, **不替换**)
# [WARN] 必须显式覆盖 spring.profiles.active=dev, 激活 prod profile 加载 application-prod.yml
spring:
  profiles:
    active: prod
  datasource:
    # 2026-06-21 v4.0.1 后:加 WAL + busy_timeout + synchronous=NORMAL(对齐 jar 内 application-prod.yml)
    # 历史 bug:本步写裸 URL,导致 prod PRAGMA journal_mode=delete + busy_timeout=0 + pool-size=1
    #          → page_view 异步写持 PENDING 锁时,backup 进程 .schema 拿 SHARED 被拒
    #          → "database is locked" 反复出现。改后单写多读并发 + 锁竞争自动等。
    url: jdbc:sqlite:$DB_FILE?journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL
    driver-class-name: org.sqlite.JDBC
    hikari:
      # v4.0.1:开 WAL + busy_timeout 后连接池才可安全 >1,1 会让 page_view 写串行排队阻塞首页读
      maximum-pool-size: 8
  redis:
    host: 127.0.0.1
    port: 6379

server:
  port: $SERVER_PORT
EOF
if [ "$LOCAL_SIM" != "1" ]; then
    chown myblog:myblog "$APP_YML"
fi
info "[OK] $APP_YML generated (overlay only)"

# ============= 7.5 写 /etc/myblog/myblog.env =============
# 关键修复:jar 里的 application-prod.yml 用了一堆 \${} 占位符(JWT_SECRET / CORS_ORIGINS / SQLITE_PATH / UPLOAD_DIR / REDIS_*), 
# 这些值必须由 systemd EnvironmentFile 提供, 否则 Spring 启动报 "Could not resolve placeholder" 或拿到空串/默认值.
# 规则:
#   - 首次部署(/etc/myblog/myblog.env 不存在)→ 自动生成, JWT_SECRET 32+ 位强随机
#   - 升级部署(/etc/myblog/myblog.env 已存在)→ **保留不动**(避免重启后 JWT secret 变化踢所有用户下线)
info "=== 7.5 Writing env file ==="
if [ "$LOCAL_SIM" = "1" ]; then
    # 容器内：env 文件放在 $INSTALL_DIR 下（更符合容器习惯，避免污染 /etc）
    mkdir -p "$INSTALL_DIR"
    ENV_FILE="$INSTALL_DIR/myblog.env"
else
    mkdir -p /etc/myblog
    ENV_FILE="/etc/myblog/myblog.env"
fi
if [ -f "$ENV_FILE" ]; then
    info "Detected existing $ENV_FILE, keeping existing config (no overwrite, to preserve JWT secret)"
    info "  To rotate: stop service -> edit $ENV_FILE -> restart"
else
    # 生成 32 字节(64 hex 字符)强随机 JWT secret
    if command -v openssl >/dev/null 2>&1; then
        JWT_SECRET_GENERATED=$(openssl rand -hex 32)
    else
        # 兜底:head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n'
        JWT_SECRET_GENERATED=$(head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n')
    fi
    cat > "$ENV_FILE" <<EOF
# my-blog 运行时环境变量
# 首次部署自动生成；后续升级不会覆盖
# 修改后需:sudo systemctl restart myblog
#
# [WARN]  JWT_SECRET 是已签发 token 的签名密钥, **绝对不要改**, 改完所有用户会被踢下线
# [WARN]  CORS_ORIGINS 必须包含你的真实域名(逗号分隔), 否则前端跨域被拒
# [WARN]  REDIS_PASSWORD 如启用密码, 填这里(apt 装的 redis-server 默认无密码)

# --- 数据库 ---
SQLITE_PATH=$DB_FILE

# --- JWT ---
JWT_SECRET=$JWT_SECRET_GENERATED

# --- CORS(**改成你的真实域名**, 多个用逗号分隔)---
CORS_ORIGINS=$([ "$LOCAL_SIM" = "1" ] && echo "http://localhost:28000,http://localhost:28080,https://yourname.com,https://www.yourname.com" || echo "https://yourname.com,https://www.yourname.com")

# --- 文件上传 ---
UPLOAD_DIR=$INSTALL_DIR/uploads

# --- 文章附件（2026-07-01 DEV-001）---
# 区别于图片上传 /uploads/ 静态服务，附件走应用层流式响应（不直暴露路径）
# blog-backup.sh 独立打包此处（不混 uploads）
ATTACHMENT_DIR=$INSTALL_DIR/attachments

# --- Redis(apt 装的 redis-server 默认 localhost:6379 无密码)---
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_DB=0

# --- 数据备份（v4.2.0，REQ-BACKUP-2026-06-20）---
# [WARN] 首次部署 **必须** ssh 上来手填以下 3 个值, 默认 REPLACE_ME 占位会让 admin 后台
#   "数据备份"按钮触发时报"BACKUP_ENCRYPTION_PASSWORD 未配置"。改成真实值后:
#     sudo systemctl restart myblog
# 生成密码: openssl rand -base64 24
BACKUP_ENCRYPTION_PASSWORD=REPLACE_ME_WITH_STRONG_RANDOM
# GitHub 备份仓库(**独立**于发布仓库, 建议私有), 例 yourname/my-blog-backup
GITHUB_BACKUP_REPO=REPLACE_ME_WITH_GITHUB_BACKUP_REPO
# GitHub PAT(repo 权限, **只**给 my-blog-backup 用)
#   2026-06-20 重命名: GITHUB_TOKEN → BACKUP_GITHUB_TOKEN
#   发布链路的 GITHUB_TOKEN 走 deploy.env / CI 临时注入, **不**进这个 env
#   强烈建议用 Fine-grained PAT, 只勾 my-blog-backup 仓库的 Contents: Read and write
BACKUP_GITHUB_TOKEN=REPLACE_ME_WITH_GITHUB_PAT
EOF
    chmod 600 "$ENV_FILE"
    if [ "$LOCAL_SIM" != "1" ]; then
        chown myblog:myblog "$ENV_FILE"
    fi
    info "[OK] $ENV_FILE generated (JWT_SECRET=$(echo "$JWT_SECRET_GENERATED" | cut -c1-8)..., chmod 600 done)"
    if [ "$LOCAL_SIM" = "1" ]; then
        info "[OK] LOCAL_SIM: CORS_ORIGINS set to localhost:28000/28080 (browser login ready)"
    else
        warn "[WARN]  After deploy, please edit $ENV_FILE, and change CORS_ORIGINS to your real domain!"
    fi
fi

# ============= 8. 服务管理（systemd / supervisord 二选一）=============
# LOCAL_SIM=1 → supervisord（容器内）
# LOCAL_SIM=0 → systemd（生产 ECS，原样保留）
info "=== 8. Writing service config (LOCAL_SIM=$LOCAL_SIM) ==="
if [ "$LOCAL_SIM" = "1" ]; then
    # ---------- LOCAL_SIM: supervisord ----------
    # LOCAL_SIM: 生成 run-myblog.sh（supervisord 调用）+ myblog.conf
    # run-myblog.sh 内 source env 文件注入环境变量（替代 systemd EnvironmentFile）
    info "LOCAL_SIM=1, writing supervisord program + run-myblog.sh"
    
    # 1) 生成 run-myblog.sh —— supervisord 的 [program:myblog] command 指向这个文件
    cat > "$INSTALL_DIR/scripts/run-myblog.sh" <<EOF
#!/bin/bash
# 由 deploy-server.sh §8 LOCAL_SIM 模式自动生成，请勿手动改
set -e
cd $INSTALL_DIR
# 关键：source env 文件注入 JWT_SECRET / UPLOAD_DIR / REDIS_* / CORS_ORIGINS / SQLITE_PATH
# 替代生产 systemd 的 EnvironmentFile 机制（supervisord 不支持 EnvironmentFile）
set -a
. $ENV_FILE
set +a
# -Duser.timezone=Asia/Shanghai: 容器/生产时区统一（logs 时间戳稳定）
exec /usr/bin/java -Xms256m -Xmx512m -Duser.timezone=Asia/Shanghai \\
    -jar $INSTALL_DIR/blog-app.jar \\
    --spring.config.additional-location=$APP_YML
EOF
    chmod +x "$INSTALL_DIR/scripts/run-myblog.sh"
    info "  generated: $INSTALL_DIR/scripts/run-myblog.sh"
    
    # 2) 写 supervisord 程序配置（被 supervisord.conf 的 [include] 读取）
    SUPERVISOR_CONF="/etc/supervisor/conf.d/myblog.conf"
    cat > "$SUPERVISOR_CONF" <<EOF
; 由 deploy-server.sh §8 LOCAL_SIM 模式自动生成，请勿手动改
[program:myblog]
command=$INSTALL_DIR/scripts/run-myblog.sh
directory=$INSTALL_DIR
autostart=false
autorestart=true
startsecs=10
startretries=3
environment=SPRING_PROFILES_ACTIVE="prod",TZ="Asia/Shanghai"
stdout_logfile=$INSTALL_DIR/logs/app.log
stderr_logfile=$INSTALL_DIR/logs/app-error.log
stdout_logfile_maxbytes=50MB
stderr_logfile_maxbytes=50MB
stdout_logfile_backups=3
stderr_logfile_backups=3
EOF
    info "  generated: $SUPERVISOR_CONF"
    
    # 3) 让 supervisord 重新读取配置 + 拉起 myblog
    # 2026-06-22 新增:restart 前 flush redis,确保新进程从干净缓存启动（详见 §4.6 函数定义）
    flush_redis
    supervisorctl reread
    supervisorctl update myblog
    supervisorctl restart myblog
    info "[OK] supervisord program configured and started (LOCAL_SIM)"

    # 4) 配置 upgrade-agent supervisord（v5.3.0+）
    if [ -f "$TMP_DIR/upgrade-agent.py" ]; then
        UPGRADE_SUPERVISOR_CONF="/etc/supervisor/conf.d/upgrade-agent.conf"
        cat > "$UPGRADE_SUPERVISOR_CONF" <<UPGRADE_EOF
[program:upgrade-agent]
command=python3 $INSTALL_DIR/scripts/upgrade-agent.py
directory=$INSTALL_DIR
autostart=true
autorestart=true
startsecs=3
startretries=3
environment=AGENT_HOST="127.0.0.1",AGENT_PORT="28081",DEPLOY_SCRIPT="$INSTALL_DIR/scripts/deploy-server.sh",GITHUB_REPO="$GITHUB_REPO"
stdout_logfile=$INSTALL_DIR/logs/upgrade-agent.log
stderr_logfile=$INSTALL_DIR/logs/upgrade-agent-error.log
stdout_logfile_maxbytes=10MB
stderr_logfile_maxbytes=10MB
UPGRADE_EOF
        info "  generated: $UPGRADE_SUPERVISOR_CONF"
        supervisorctl reread
        supervisorctl update upgrade-agent

        # ---------- 更新升级记录状态（v5.3.14+）----------
        # 在重启 upgrade-agent 之前更新数据库，避免旧进程被杀后无法更新
        if [ -f "$DB_FILE" ] && command -v sqlite3 >/dev/null 2>&1; then
            RUNNING_ID=$(sqlite3 "$DB_FILE" "SELECT id FROM upgrade_record WHERE status = 'RUNNING' ORDER BY id DESC LIMIT 1" 2>/dev/null)
            if [ -n "$RUNNING_ID" ]; then
                sqlite3 "$DB_FILE" "UPDATE upgrade_record SET status = 'SUCCESS', finished_at = datetime('now', 'localtime') WHERE id = $RUNNING_ID;" 2>/dev/null
                info "[OK] 升级记录 #$RUNNING_ID 标记为 SUCCESS"
            else
                sqlite3 "$DB_FILE" "INSERT INTO upgrade_record (target_version, mode, import_db, status, started_at, finished_at, operator_name) VALUES ('$TAG', '$DEPLOY_MODE', $IMPORT_DB, 'SUCCESS', datetime('now', 'localtime'), datetime('now', 'localtime'), 'CLI');" 2>/dev/null
                info "[OK] 新增 CLI 部署记录到 upgrade_record (target_version=$TAG, mode=$DEPLOY_MODE)"
            fi
        fi

        supervisorctl start upgrade-agent
        info "[OK] upgrade-agent supervisord configured and started (LOCAL_SIM)"
    else
        warn "upgrade-agent.py not in release, skipping upgrade-agent setup"
    fi
else
    # ---------- 生产：systemd unit（原样保留）----------
    info "=== 8. Writing systemd service ==="
    SERVICE_FILE="/etc/systemd/system/myblog.service"
    cat > "$SERVICE_FILE" <<EOF
[Unit]
Description=my-blog backend (Spring Boot)
After=network.target redis-server.service

[Service]
Type=simple
User=myblog
Group=myblog
WorkingDirectory=$INSTALL_DIR
# 关键:用 --spring.config.additional-location(**叠加**)而不是 --spring.config.location(**替换**)
# 这样 jar 里的 application.yml + application-prod.yml 都会保留, 脚本生成的 application.yml 只补差异
# -Duser.timezone=Asia/Shanghai: 生产 ECS 在洛杉矶, 固定 JVM 时区为北京, 否则 Java 端取时间偏 15-16h
ExecStart=/usr/bin/java -Xms256m -Xmx512m -Duser.timezone=Asia/Shanghai -jar $INSTALL_DIR/blog-app.jar --spring.config.additional-location=$APP_YML
# 运行时环境变量(JWT_SECRET / CORS_ORIGINS / SQLITE_PATH / UPLOAD_DIR / REDIS_*)
EnvironmentFile=-$ENV_FILE
Restart=always
RestartSec=5
StandardOutput=append:$INSTALL_DIR/logs/app.log
StandardError=append:$INSTALL_DIR/logs/app-error.log
LimitNOFILE=65536

[Install]
WantedBy=multi-user.target
EOF
    systemctl daemon-reload
    systemctl enable myblog
    # 2026-06-22 新增:restart 前 flush redis,确保新进程从干净缓存启动（详见 §4.6 函数定义）
    flush_redis
    systemctl restart myblog
    info "[OK] systemd service configured and started"

    # ---------- 更新升级记录状态（v5.3.14+）----------
    # 在重启 upgrade-agent 之前更新数据库，避免旧进程被杀后无法更新
    if [ -f "$DB_FILE" ] && command -v sqlite3 >/dev/null 2>&1; then
        RUNNING_ID=$(sqlite3 "$DB_FILE" "SELECT id FROM upgrade_record WHERE status = 'RUNNING' ORDER BY id DESC LIMIT 1" 2>/dev/null)
        if [ -n "$RUNNING_ID" ]; then
            sqlite3 "$DB_FILE" "UPDATE upgrade_record SET status = 'SUCCESS', finished_at = datetime('now', 'localtime') WHERE id = $RUNNING_ID;" 2>/dev/null
            info "[OK] 升级记录 #$RUNNING_ID 标记为 SUCCESS"
        else
            sqlite3 "$DB_FILE" "INSERT INTO upgrade_record (target_version, mode, import_db, status, started_at, finished_at, operator_name) VALUES ('$TAG', '$DEPLOY_MODE', $IMPORT_DB, 'SUCCESS', datetime('now', 'localtime'), datetime('now', 'localtime'), 'CLI');" 2>/dev/null
            info "[OK] 新增 CLI 部署记录到 upgrade_record (target_version=$TAG, mode=$DEPLOY_MODE)"
        fi
    fi

    # ---------- upgrade-agent systemd unit（v5.3.0+）----------
    UPGRADE_AGENT_SERVICE="/etc/systemd/system/upgrade-agent.service"
    if [ -f "$TMP_DIR/upgrade-agent.py" ]; then
        info "=== 8.1 Writing upgrade-agent systemd service ==="
        cp "$TMP_DIR/upgrade-agent.py" "$INSTALL_DIR/scripts/upgrade-agent.py"
        chmod +x "$INSTALL_DIR/scripts/upgrade-agent.py"
        cp "$TMP_DIR/upgrade-agent.service" "$UPGRADE_AGENT_SERVICE"
        systemctl daemon-reload
        systemctl enable upgrade-agent
        systemctl restart upgrade-agent
        info "[OK] upgrade-agent systemd service configured and started"
    else
        warn "upgrade-agent.py not found in release, skipping upgrade-agent setup"
    fi
fi

# ============= 8.6 logrotate 配置(REQ-LOG-2026-06-18)=============
# 覆盖两类日志:
#   ① Logback 管理的滚动文件(archive/YYYY-MM/blog.YYYY-MM-DD.NN.log + archive/YYYY-MM/blog-warn.YYYY-MM-DD.NN.log)
#      —— Logback 自己按 30 天滚动,但 systemd 重启/异常退出可能留下孤儿,兜底
#   ② systemd 重定向的 app.log / app-error.log —— Logback 管不到,必须单独配
# 周期按天,保留 7 天(日志在 prod 仅供 owner 单人排查,7 天足够)
LOGROTATE_FILE="/etc/logrotate.d/myblog"
if [ "$LOCAL_SIM" = "1" ]; then
    info "LOCAL_SIM=1, skipping logrotate (supervisord has stdout_logfile_maxbytes rotation built-in)"
elif [ -w /etc/logrotate.d ] || command -v sudo >/dev/null 2>&1; then
    cat > "$LOGROTATE_FILE" <<'LOGROTATE_EOF'
/opt/myblog/logs/archive/*/blog-*.log /opt/myblog/logs/archive/*/blog-warn-*.log {
    daily
    rotate 30
    missingok
    notifempty
    compress
    delaycompress
    copytruncate
}

/opt/myblog/logs/app.log /opt/myblog/logs/app-error.log {
    daily
    rotate 7
    missingok
    notifempty
    compress
    delaycompress
    copytruncate
}
LOGROTATE_EOF
    # 权限 644,root 拥有
    chmod 644 "$LOGROTATE_FILE" 2>/dev/null || sudo chmod 644 "$LOGROTATE_FILE" 2>/dev/null || true
    info "[OK] logrotate configured: $LOGROTATE_FILE"
else
    warn "[WARN] cannot write /etc/logrotate.d, log rotation not configured (systemd-redirected logs may grow unbounded)"
fi

# ============= 8.5 数据导入(IMPORT_DB=1 触发)=============
# 调用 sqlite-import.sh 解密 .enc 并导入到 $DB_FILE
# (v4.4.0: data/code 模式已删除,这两条约束随之移除——full + IMPORT_DB=1 是合法组合)
# DEPLOY_MODE=full + IMPORT_DB=1:正常执行
if [ "$IMPORT_DB" = "1" ]; then
    info "=== 8.5 Data import (IMPORT_DB=1) ==="
    
    # 找 .enc 文件(可能在 TMP_DIR 里,解压后或单文件下载)
    ENC_FILE=""
    if [ -f "$TMP_DIR/dev-blog-dump.sql.gz.enc" ]; then
        ENC_FILE="$TMP_DIR/dev-blog-dump.sql.gz.enc"
    fi
    
    if [ -z "$ENC_FILE" ]; then
        warn "dev-blog-dump.sql.gz.enc not found"
        warn "  (not in release assets, skipping data import)"
        warn "  maybe publish-release.sh was not run with EXPORT_DB=1"
    else
        if [ ! -f "$INSTALL_DIR/scripts/sqlite-import.sh" ]; then
            err "$INSTALL_DIR/scripts/sqlite-import.sh not found, cannot decrypt"
            err "  re-run publish-release.sh (it bundles sqlite-import.sh into release)"
        fi
        
        info "Calling $INSTALL_DIR/scripts/sqlite-import.sh to decrypt + import"
        info "  .enc: $ENC_FILE"
        info "  target: $DB_FILE"
        # 必须用 PIPESTATUS[0] 拿真实退出码（pipe 会被 sed 掩盖）
        FORCE_IMPORT=1 bash "$INSTALL_DIR/scripts/sqlite-import.sh" "$DB_FILE" "$ENC_FILE" 2>&1 | sed 's/^/    /'
        IMPORT_RC=${PIPESTATUS[0]}
        
        if [ "$IMPORT_RC" -ne 0 ]; then
            err "Data import failed (wrong password, y/N cancel, or corrupted file), exit code $IMPORT_RC"
        fi
        info "[OK] Data import complete"

        # sqlite-import.sh 删除旧 DB 文件并重建,app 进程持有的 fd 指向已删除的 inode
        # 必须重启 app 让它重新打开新 DB 文件并刷新缓存
        info "Restarting app to pick up imported DB..."
        # 2026-06-22 新增:IMPORT_DB 把 dev 数据灌进来,旧的 redis 缓存(限流/token/配置)与新 db 不一致,
        #   必须 flush 后重启,否则新进程从脏缓存启动会拿到与 db 错位的状态
        flush_redis
        if [ "$LOCAL_SIM" = "1" ]; then
            supervisorctl restart myblog
        elif systemctl is-active --quiet myblog 2>/dev/null; then
            systemctl restart myblog
        fi
    fi
fi

# (v4.4.0: 'data' 模式已删除,开头的 if/else + 闭合 fi 一并移除)

# ============= 9. nginx 反代 + 静态文件 =============
info "=== 9. Configuring nginx ==="
NGINX_CONF="/etc/nginx/conf.d/myblog.conf"
# 2026-06-22 [FIX] 用 'EOF' (带引号) 禁止 bash 变量展开,避免 set -u 下 \$http_host 等 nginx 变量被当作 bash 变量求值触发 unbound variable
# nginx 占位符 NGINX_PORT / NGINX_ROOT / NGINX_UPLOADS / NGINX_APP_PORT 在写完后做替换
cat > "$NGINX_CONF" <<'NGINX_EOF'
server {
    listen       NGINX_PORT default_server;
    server_name  _;

    # 前端静态文件(v2.7.0 全静态)
    root         NGINX_ROOT/frontend;
    index        index.html;
    # 2026-06-30 BUG-001：try_files 顺序调整避免目录 301 redirect
    # 原：try_files $uri $uri/ /200.html;   ← $uri/ 命中目录时 nginx 触发 301 加 slash，
    #                                          Location 用 server_name 拼 host 不带端口（如 http://localhost/search/），
    #                                          浏览器跟随后连接失败。本地 docker (myblog-sim 28000 → 80)、
    #                                          生产 ECS (443) 都受影响。
    # 新：先试 $uri/index.html（直接命中 search/index.html），跳过 $uri/ 的 301 行为。
    #    同时覆盖 /about /archives /tags /search 等所有"目录形"路由。
    try_files    $uri $uri/index.html /200.html;

    # Nuxt 生成的 SPA fallback
    location = /200.html { add_header Cache-Control "no-cache"; }

    # 2026-06-22 修复 BUG：上传文件 404
    # 原配置没 /uploads/ 段,nginx 把 /uploads/2026/06/xxx.png 走到 root $INSTALL_DIR/frontend/ 下找
    # → open() "/opt/myblog/frontend/uploads/2026/06/xxx.png" failed (No such file or directory)
    # → 404。文件其实写在 $INSTALL_DIR/uploads/ 下。
    # ^~ 表示"优先最长匹配,不再走正则 location",避免被下面 \.(png|jpg) 抢走或被 /api/ 误命中。
    # alias 直接指向 uploads 目录,不走 Spring(Spring 的 StaticResourceConfig 走 /api/v1/uploads/,由 /api/ 反代接管)。
    location ^~ /uploads/ {
        alias NGINX_UPLOADS/;
        expires 7d;
        add_header Cache-Control "public, immutable";
        try_files $uri =404;
    }

    # 静态资源长缓存
    location ~* \.(js|css|woff2?|ttf|svg|png|jpg|jpeg|gif|ico|webp)$ {
        expires 7d;
        add_header Cache-Control "public, immutable";
    }

    # SEO 文章页：转后端返回含内容的 HTML（渐进增强方案）
    # 后端 context-path /api/v1 → /api/v1/seo/post/{slug}
    # 2026-07-07 修复：SeoController 动态注入 Nuxt CSS/JS，浏览器+爬虫统一走此端点
    location /post/ {
        rewrite ^/post/(.*)$ /api/v1/seo/post/$1 break;
        proxy_pass         http://127.0.0.1:NGINX_APP_PORT;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_read_timeout 60s;
        proxy_cache_valid 200 10m;
    }

    # 动态 Sitemap：转后端生成包含所有文章的 sitemap.xml
    location = /sitemap.xml {
        proxy_pass         http://127.0.0.1:NGINX_APP_PORT/api/v1/sitemap.xml;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_cache_valid 200 1h;
    }

    # robots.txt：确保应用的 robots.txt 不被 Cloudflare 劫持
    location = /robots.txt {
        alias /opt/myblog/frontend/robots.txt;
        add_header Content-Type text/plain;
        add_header Cache-Control "public, max-age=3600";
    }

    # 后端 API 反代
    location /api/ {
        proxy_pass         http://127.0.0.1:NGINX_APP_PORT;
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        # 2026-06-22 修复 BUG:上传头像/封面后,前端用返回的 url 加载图片报 ERR_CONNECTION_REFUSED
        #   根因:UploadController 构造绝对 URL 时读 X-Forwarded-Host 头拿外部 host,
        #   nginx conf 之前没透传,fallback 到 request.getServerName()="localhost"(没端口)→ 浏览器访问 http://localhost/... 默认 80 端口被拒。
        #   透传 X-Forwarded-Host 后,后端能拿到浏览器实际访问的 host(含端口),拼出正确 URL。
        #   用 $http_host 而不是 $host:$server_port:前者直接是 HTTP Host 头原值(含端口)，
        #   后者在"浏览器→非标端口(28000)→nginx:80→后端:8080"两次反代场景下永远是 nginx 自己的 80。
        proxy_set_header   X-Forwarded-Host  $http_host;
        # 2026-06-24 修复：手机浏览器带 Origin 头 → Spring CorsConfig 的
        # allowedOriginPatterns("*")+allowCredentials(true) 在 SB 2.7 下拒 403。
        # 前后端同 nginx 同源，proxy 层剥掉 Origin 即可。
        proxy_set_header   Origin             "";
        proxy_read_timeout 60s;
        client_max_body_size 20m;
    }

    # gzip
    gzip on;
    gzip_types text/plain text/css application/javascript application/json image/svg+xml;
    gzip_min_length 1024;
}
NGINX_EOF
# bash 变量替换占位符（heredoc 用 'EOF' 禁了 bash 展开,这里手动替换）
sed -i "s|NGINX_PORT|$PUBLIC_PORT|g; s|NGINX_ROOT|$INSTALL_DIR|g; s|NGINX_UPLOADS|$INSTALL_DIR/uploads|g; s|NGINX_APP_PORT|$SERVER_PORT|g" "$NGINX_CONF"

# 处理可能与本配置冲突的"自带 default_server"
# Debian/Ubuntu:/etc/nginx/sites-enabled/default(含 listen 80 default_server)
# CentOS/RHEL:/etc/nginx/nginx.conf 的 http {} 里直接有个 server { listen 80 default_server; ... }
# 两种都要处理, 否则 nginx -t 会报 "duplicate default server" 部署中断.
# 摘除自带 server 的 default_server 关键字，避免与 conf.d/myblog.conf 冲突
# 不用 perl 块级正则（嵌套 location 会截断），只摘 listen 行上的关键字
rm -f /etc/nginx/sites-enabled/default 2>/dev/null || true
if [ -f /etc/nginx/nginx.conf ]; then
    # 摘 default_server 关键字(IPv4 `listen 80 default_server;` 和 IPv6 `listen [::]:80 default_server;` 都覆盖)
    perl -i -pe 's/(^\s*listen[^;\n]*?)\s+default_server\b/$1/g' /etc/nginx/nginx.conf 2>/dev/null || \
        warn "perl removal of default_server failed (does not affect Debian), please manually check /etc/nginx/nginx.conf"
    info "Removed default_server from nginx.conf's built-in listen line (let conf.d/myblog.conf take over default)"
    # 兜底:确保 http {} 里有 include conf.d/*.conf(精简镜像可能没带)
    if ! grep -qE "include\s+/etc/nginx/conf\.d/\*\.conf\s*;" /etc/nginx/nginx.conf; then
        warn "/etc/nginx/nginx.conf has no include conf.d/*.conf, auto-adding..."
        # 在 http { 块内塞一行 include
        perl -0777 -i -pe 's{^(http\s*\{)}{$1\n    include /etc/nginx/conf.d/*.conf;\n}smg' /etc/nginx/nginx.conf 2>/dev/null || \
            err "Cannot auto-add conf.d include, please manually edit /etc/nginx/nginx.conf"
    fi
fi

nginx -t
if [ "$LOCAL_SIM" = "1" ]; then
    supervisorctl restart nginx
else
    systemctl enable nginx
    systemctl restart nginx
fi
info "[OK] nginx configured and started"

# ============= 9.5 HTTPS 配置(可选,ENABLE_HTTPS=1 触发) =============
if [ "$ENABLE_HTTPS" != "1" ]; then
    info "=== 9.5 HTTPS config ==="
    info "    ENABLE_HTTPS=0, skipping HTTPS (HTTP-only deploy)"
else
    info "=== 9.5 HTTPS config (ENABLE_HTTPS=1) ==="

    # 9.5.1 certbot webroot 目录
    mkdir -p /var/www/certbot
    chown -R www-data:www-data /var/www/certbot 2>/dev/null || \
        chown -R nginx:nginx /var/www/certbot 2>/dev/null || true

    # 9.5.2 安装 certbot（已装跳过）
    if ! command -v certbot >/dev/null 2>&1; then
        info "Installing certbot..."
        if command -v apt-get >/dev/null 2>&1; then
            DEBIAN_FRONTEND=noninteractive apt-get update -qq && \
                DEBIAN_FRONTEND=noninteractive apt-get install -y -qq certbot
        elif command -v yum >/dev/null 2>&1; then
            yum install -y -q certbot
        else
            err "Neither apt-get nor yum found, please install certbot manually"
            exit 1
        fi
    else
        info "certbot already installed: $(certbot --version 2>&1 | head -1)"
    fi

    # 9.5.3 写 HTTP-only 配置（覆盖 step 9 的 myblog.conf）
    #      HTTPS 模式下 80 端口不能反代，必须留给 certbot 校验 + 301 跳转
    HTTP_CONF="/etc/nginx/conf.d/myblog-http.conf"
    rm -f /etc/nginx/conf.d/myblog.conf
    info "Writing HTTP-only nginx config to $HTTP_CONF ..."
    sed -e "s|\${NGINX_DOMAIN}|$HTTPS_DOMAIN|g" \
        -e "s|\${PUBLIC_PORT}|$PUBLIC_PORT|g" \
        "$TMP_DIR/nginx-http.conf" > "$HTTP_CONF"

    # 9.5.4 reload nginx 让 certbot 校验路径生效
    if [ "$LOCAL_SIM" = "1" ]; then
        nginx -t && supervisorctl restart nginx
    else
        nginx -t && systemctl reload nginx
    fi

    # 9.5.5 申请证书（webroot 模式，80 端口已跑不影响用户）
    info "Requesting Let's Encrypt certificate for: $HTTPS_DOMAIN"
    if certbot certonly --webroot -w /var/www/certbot \
        -d "$HTTPS_DOMAIN" \
        --email "$HTTPS_EMAIL" \
        --agree-tos --no-eff-email --non-interactive 2>&1 | tee /tmp/certbot.log; then
        CERT_PATH="/etc/letsencrypt/live/$HTTPS_DOMAIN"
        if [ ! -f "$CERT_PATH/fullchain.pem" ]; then
            err "certbot reported success but cert not found at $CERT_PATH"
            err "  check /tmp/certbot.log"
            exit 1
        fi
        info "  Certificate issued: $CERT_PATH"
    else
        err "certbot failed, see /tmp/certbot.log"
        err "  Common causes:"
        err "  1. DNS A record not resolved to this server's public IP"
        err "  2. Port 80 blocked by firewall/security group"
        err "  3. Let's Encrypt rate limit (50 certs/week per domain)"
        exit 1
    fi

    # 9.5.6 写 HTTPS 配置
    HTTPS_CONF="/etc/nginx/conf.d/myblog-https.conf"
    info "Writing HTTPS nginx config to $HTTPS_CONF ..."
    sed -e "s|\${NGINX_DOMAIN}|$HTTPS_DOMAIN|g" \
        -e "s|\${SERVER_PORT}|$SERVER_PORT|g" \
        -e "s|\${INSTALL_DIR}|$INSTALL_DIR|g" \
        "$TMP_DIR/nginx-https.conf" > "$HTTPS_CONF"

    # 9.5.7 校验 + reload
    if [ "$LOCAL_SIM" = "1" ]; then
        nginx -t && supervisorctl restart nginx
    else
        nginx -t && systemctl reload nginx
    fi
    info "[OK] HTTPS enabled, HTTP auto-redirects to HTTPS"

    # 9.5.8 更新 CORS_ORIGINS（加 https 域名）
    if [ -f "$ENV_FILE" ]; then
        HTTPS_ORIGIN="https://$HTTPS_DOMAIN"
        if ! grep -qF "$HTTPS_ORIGIN" "$ENV_FILE"; then
            sed -i.bak "s|^CORS_ORIGINS=.*|&,$HTTPS_ORIGIN|" "$ENV_FILE"
            if [ "$LOCAL_SIM" = "1" ]; then
                supervisorctl restart myblog
            else
                systemctl restart myblog
            fi
            info "[OK] CORS_ORIGINS updated: $HTTPS_ORIGIN (service restarted)"
        else
            info "CORS_ORIGINS already contains $HTTPS_ORIGIN, skipping"
        fi
    fi

    # 9.5.9 证书自动续期 cron（错开 rebuild-static.sh 的 0 3 * * *）
    if ! crontab -l 2>/dev/null | grep -q "certbot renew"; then
        info "Adding certbot auto-renewal cron (daily 03:30)..."
        ( crontab -l 2>/dev/null; echo "30 3 * * * certbot renew --quiet --post-hook 'systemctl reload nginx'" ) | crontab -
        info "  cron installed"
    else
        info "certbot renew cron already exists, skipping"
    fi
fi  # ENABLE_HTTPS block

# ============= 10. Redis 健康检查（启动已在 §4.5 完成）=============
# 2026-06-22 改动:redis 启动逻辑提前到 §4.5,确保 §5 stop_app / flush_redis 可用.
#   本步骤改为"验证 redis 在线"兜底,失败给 warn (不致命,运行时也会暴露).
info "=== 10. Verifying Redis health ==="
if command -v redis-cli >/dev/null 2>&1; then
    if redis-cli -h 127.0.0.1 -p 6379 PING >/dev/null 2>&1; then
        info "[OK] Redis is up (127.0.0.1:6379)"
    else
        warn "Redis ping failed at 127.0.0.1:6379, runtime will fail until redis recovers"
    fi
else
    warn "redis-cli not installed, skip redis health check"
fi

# ============= 11. 等待服务起来 =============
info "=== 11. Waiting for my-blog to start ==="
RETRIES=30
until curl -fsS "http://127.0.0.1:$SERVER_PORT/api/v1/health" >/dev/null 2>&1; do
    RETRIES=$((RETRIES-1))
    if [ $RETRIES -le 0 ]; then
        err "my-blog startup timeout, see logs:"
        if [ "$LOCAL_SIM" = "1" ]; then
            err "  supervisorctl tail myblog"
        else
            err "  journalctl -u myblog -n 100"
        fi
        err "  tail -n 100 $INSTALL_DIR/logs/app-error.log"
        exit 1
    fi
    sleep 2
done
info "[OK] my-blog is ready"

# ============= 12. 防火墙放行(默认执行, 可关)=============
# 部署链路里这一步**故意不让它致命失败**——前 11 步全成功了, nginx 起来了、jar 跑起来了、
# 健康检查也通过了, 防火墙放行失败不应该让整个部署退出 1(容易让人误以为服务没起来, 
# 实际只是外网访问被拒).
# - 成功:info 一行
# - 失败:warn 一行 + 给出可手动执行的命令
# - 不想自动放行:OPEN_FIREWALL=0
info "=== 12. Firewall rules ==="
if [ "$LOCAL_SIM" = "1" ]; then
    info "LOCAL_SIM=1, skipping firewall (container has no iptables; expose ports via -p when running container)"
elif [ "$OPEN_FIREWALL" = "0" ]; then
    info "OPEN_FIREWALL=0, skipping auto-open (please manually open $PUBLIC_PORT/tcp)"
elif command -v firewall-cmd >/dev/null 2>&1 && systemctl is-active --quiet firewalld; then
    info "firewalld detected, auto-opening $PUBLIC_PORT/tcp..."
    if firewall-cmd --permanent --add-port="$PUBLIC_PORT/tcp" >/dev/null 2>&1 \
        && firewall-cmd --reload >/dev/null 2>&1; then
        info "[OK] firewalld $PUBLIC_PORT/tcp opened"
    else
        warn "firewalld open failed (does not affect deploy, please run manually:"
        warn "  firewall-cmd --permanent --add-port=$PUBLIC_PORT/tcp && firewall-cmd --reload"
    fi
elif command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "active"; then
    info "ufw detected, auto-opening $PUBLIC_PORT/tcp..."
    if ufw allow "$PUBLIC_PORT/tcp" >/dev/null 2>&1; then
        info "[OK] ufw $PUBLIC_PORT/tcp opened"
    else
        warn "ufw open failed (does not affect deploy), please run manually: sudo ufw allow $PUBLIC_PORT/tcp"
    fi
else
    info "No firewalld / ufw detected, skipping (configure cloud security group manually)"
fi

# ============= 13. 清理 + 收尾 =============
cd /root
rm -rf "$TMP_DIR"

# 自动获取外网 IP（超时 2 秒兜底）
SERVER_IP=$(curl -s -m 2 ifconfig.me 2>/dev/null || curl -s -m 2 api.ipify.org 2>/dev/null || echo "<server-ip>")

echo
info "=========================================="
info " 🎉 [OK] 部署成功 / Deployment Complete"
info "=========================================="
info "  前台访问地址 (Web):     http://$SERVER_IP:$PUBLIC_PORT"
if [ "$ENABLE_HTTPS" = "1" ]; then
    info "  HTTPS 访问地址:         https://$HTTPS_DOMAIN (HTTP→HTTPS 301)"
    info "  证书自动续期:           certbot renew (cron 03:30)"
fi
info "  后台管理地址 (Admin):   http://$SERVER_IP:$PUBLIC_PORT/admin/login"
info "  初始管理员账号:         admin"
info "  初始管理员密码:         123456 (⚠️ 请登录后立即修改密码)"
info "------------------------------------------"
if [ "$LOCAL_SIM" = "1" ]; then
    info "  查看日志:               supervisorctl tail -f myblog"
    info "                          tail -f $INSTALL_DIR/logs/app.log"
    info "  重启服务:               supervisorctl restart myblog"
else
    info "  查看日志:               journalctl -u myblog -f"
    info "                          tail -f $INSTALL_DIR/logs/app.log"
    info "  重启服务:               systemctl restart myblog"
fi
info "  版本回滚:               $0 <old-tag> (当前版本: $TAG)"
if [ "$LOCAL_SIM" != "1" ] && ! command -v gh >/dev/null 2>&1; then
    warn "  [WARN] 未检测到 gh CLI — 数据备份功能将自动回退到 curl+jq"
fi
info "=========================================="
