#!/bin/bash
# 服务器端执行:一键从 GitHub Release 拉资源 + 装环境 + 部署 my-blog
# 适用:CentOS 7+ / Ubuntu 18.04+ / Debian 10+ 最小化系统
# 跑法(root 或 sudo):
#   curl -L https://raw.githubusercontent.com/OWNER/REPO/main/scripts/deploy-server.sh -o deploy-server.sh
#   chmod +x deploy-server.sh
#   sudo ./deploy-server.sh v4.1.0
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
#   DB_FILE=/opt/myblog/blog.db  SQLite 文件位置
#   SKIP_DEPS=0                  设为 1 跳过依赖安装(已装过的话)
#   OPEN_FIREWALL=1              设为 1 自动 firewalld/ufw 放行 PUBLIC_PORT(默认 1)
#   DEPLOY_MODE=full             部署模式(默认 full)
#                                 full = 代码+数据可选导入(走完整 step 5/6)
#                                 code = 只装代码,即使 IMPORT_DB=1 也跳过 db
#                                 data = 只导入数据,跳过 jar/schema/前端
#   IMPORT_DB=0                  是否导入 release 中的加密 db dump(默认 0)
#                                 设为 1 后从交互式输入密码,调 sqlite-import.sh 解密导入
#                                 产物:dev-blog-dump.sql.gz.enc (publish-release.sh 加 EXPORT_DB=1 才有)
#
# 部署模式组合示例:
#   默认发版:           ./deploy-server.sh v4.1.0
#   只装代码(保留 db):  DEPLOY_MODE=code ./deploy-server.sh v4.1.0
#   只导入数据:          DEPLOY_MODE=data IMPORT_DB=1 ./deploy-server.sh v4.1.0
#   代码+数据全装:      IMPORT_DB=1 ./deploy-server.sh v4.1.0
# 语义约束:
#   DEPLOY_MODE=data + IMPORT_DB=0  →  报错退出(语义矛盾)

set -euo pipefail

# LOCAL_SIM 自动检测（容器内 /.dockerenv 存在 → 自动 1）
if [ -z "${LOCAL_SIM:-}" ] && [ -f /.dockerenv ]; then
    export LOCAL_SIM=1
    echo "[LOCAL_SIM] /.dockerenv detected, enabling LOCAL_SIM=1 (supervisor mode)"
fi

# ============= 0. 参数解析 =============
TAG="${1:-${RELEASE_TAG:-}}"
GITHUB_REPO="${GITHUB_REPO:-}"
INSTALL_DIR="${INSTALL_DIR:-/opt/myblog}"
SERVER_PORT="${SERVER_PORT:-8080}"
PUBLIC_PORT="${PUBLIC_PORT:-80}"
DB_FILE="${DB_FILE:-$INSTALL_DIR/db/blog.db}"
SKIP_DEPS="${SKIP_DEPS:-0}"
OPEN_FIREWALL="${OPEN_FIREWALL:-1}"
DEPLOY_MODE="${DEPLOY_MODE:-full}"      # full | code | data
IMPORT_DB="${IMPORT_DB:-0}"             # 0/1
LOCAL_SIM="${LOCAL_SIM:-0}"             # 0=生产模式  1=本地模拟容器模式

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# 校验
if [ -z "$TAG" ]; then
    err "Usage: $0 <tag>  e.g. $0 v4.1.0"
    err "Or:RELEASE_TAG=v4.1.0 $0"
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

# DEPLOY_MODE 校验
case "$DEPLOY_MODE" in
    full|code|data) ;;
    *) err "DEPLOY_MODE must be full / code / data, got: $DEPLOY_MODE" ;;
esac

# 矛盾检测
if [ "$DEPLOY_MODE" = "data" ] && [ "$IMPORT_DB" != "1" ]; then
    err "DEPLOY_MODE=data but IMPORT_DB=0, contradictory (use DEPLOY_MODE=full/code, or IMPORT_DB=1)"
fi

# code 模式 + IMPORT_DB=1:warn(语义不强,但允许)
if [ "$DEPLOY_MODE" = "code" ] && [ "$IMPORT_DB" = "1" ]; then
    warn "DEPLOY_MODE=code + IMPORT_DB=1: you explicitly requested data import, will execute"
fi

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
                python3
            ;;
        apt)
            export DEBIAN_FRONTEND=noninteractive
            apt-get update -y
            apt-get install -y \
                openjdk-8-jdk \
                wget curl unzip \
                sqlite3 \
                redis-server \
                nginx \
                python3
            ;;
    esac

    # 验证
    for bin in java sqlite3 redis-server nginx curl lsof; do
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
mkdir -p "$INSTALL_DIR"/{logs,frontend,db,uploads} "$INSTALL_DIR/db/backups"
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
if curl -fsSL -o "$BUNDLE" "$BASE_URL/$BUNDLE" 2>/dev/null; then
    info "Got cold-deployment package $BUNDLE, verifying sha256..."
    curl -fsSL -o SHA256SUMS "$BASE_URL/SHA256SUMS" 2>/dev/null || warn "No SHA256SUMS, skipping verification"
    if [ -f SHA256SUMS ]; then
        if command -v sha256sum >/dev/null; then
            (cd "$TMP_DIR" && sha256sum -c SHA256SUMS) || warn "sha256 verification failed, continuing"
        fi
    fi
    info "Extracting $BUNDLE ..."
    unzip -qo "$BUNDLE"
else
    warn "No cold-deployment package, downloading files individually"
    download "blog-app.jar"
    download "frontend-static.tar.gz"
    download "schema-sqlite.sql"
    download "deploy-server.sh"
    download "sqlite-import.sh" || warn "sqlite-import.sh download failed (needed when IMPORT_DB=1)"
    # v4.2.0 数据备份脚本：admin 后台「数据备份」菜单由后端 ProcessBuilder 调它
    download "blog-backup.sh" || warn "blog-backup.sh download failed (v4.2.0+ data backup feature will not work)"
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

# ============= 5. 部署 jar / schema / 应用配置 =============
if [ "$DEPLOY_MODE" = "data" ]; then
    info "=== 5. Deploying jar / schema / app config ==="
    info "    DEPLOY_MODE=data, skipping jar/schema/frontend/app config (db only)"
else
    info "=== 5. Deploying backend jar ==="
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

cp "$TMP_DIR/blog-app.jar" "$INSTALL_DIR/blog-app.jar"
if [ "$LOCAL_SIM" != "1" ]; then
    chown myblog:myblog "$INSTALL_DIR/blog-app.jar"
fi

# 全量部署:备份旧 DB → 删除 → 用 schema-sqlite.sql 重建（干净的全新库）
# schema-sqlite.sql 包含建表 DDL + 种子数据(admin/123456)
if [ -f "$DB_FILE" ]; then
    cp "$DB_FILE" "$INSTALL_DIR/db/backups/blog-before-${TAG}-$(date +%Y%m%d-%H%M%S).db"
    info "Backed up existing DB before full deploy"
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
info "[OK] SQLite initialized from schema (full deploy)"

# ============= 6. 部署前端静态文件 =============
info "=== 6. Deploying frontend static files ==="
rm -rf "$INSTALL_DIR/frontend"/*
tar -xzf "$TMP_DIR/frontend-static.tar.gz" -C "$INSTALL_DIR/frontend/"
if [ "$LOCAL_SIM" != "1" ]; then
    chown -R myblog:myblog "$INSTALL_DIR/frontend"
fi
info "[OK] Static files deployed to $INSTALL_DIR/frontend"

# ============= 7. 写 application 配置 =============
# 策略:脚本生成的 application.yml 通过 --spring.config.additional-location 叠加在 jar 之上
# 必须显式声明 spring.profiles.active: prod 覆盖 jar 内 application.yml 的 dev 默认值.
#
# 历史踩坑(2026-06-18 真实案例):
#   1. jar 内 application.yml 写死 spring.profiles.active: dev
#   2. 旧版脚本注释认为 "application-prod.yml 文件命名 = 隐式激活 prod" → 错的
#   3. 实际结果:服务跑 dev profile, 连本地 ./blog.db 相对路径, DB 路径配置全失效
#   4. 修复:在 additional-location 的 application.yml 顶部显式写 active: prod
#
# Spring Boot 配置加载顺序(重要):
#   jar:application.yml (含 active: dev)        ← 基础
#   jar:application-{dev,prod,mysql}.yml        ← 按 active 加载 profile-specific
#   additional-location:application.yml          ← 这里覆盖 active
# 叠加规则:后面的覆盖前面的；profile-specific 文件的 spring.profiles.active 会被 Spring 校验报错(不在我们这层)
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
    # 关键设计（修复 #2 + #3）：
    #   1. 生成 run-myblog.sh（被 supervisord.conf [program:myblog] 调用）
    #   2. run-myblog.sh 内 set -a; source env 文件; set +a → exec java
    #      （替代 systemd EnvironmentFile，env 变量全部注入）
    #   3. 写到 /etc/supervisor/conf.d/myblog.conf（注意：supervisord.conf 必须 [include] conf.d）
    #   4. supervisorctl reread/update/restart myblog
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
    supervisorctl reread
    supervisorctl update myblog
    supervisorctl restart myblog
    info "[OK] supervisord program configured and started (LOCAL_SIM)"
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
    systemctl restart myblog
    info "[OK] systemd service configured and started"
fi

# ============= 8.6 logrotate 配置(REQ-LOG-2026-06-18)=============
# 覆盖两类日志:
#   ① Logback 管理的滚动文件(blog.YYYY-MM-DD.NN.log + blog-warn.YYYY-MM-DD.NN.log)
#      —— Logback 自己按 30 天滚动,但 systemd 重启/异常退出可能留下孤儿,兜底
#   ② systemd 重定向的 app.log / app-error.log —— Logback 管不到,必须单独配
# 周期按天,保留 7 天(日志在 prod 仅供 owner 单人排查,7 天足够)
LOGROTATE_FILE="/etc/logrotate.d/myblog"
if [ "$LOCAL_SIM" = "1" ]; then
    info "LOCAL_SIM=1, skipping logrotate (supervisord has stdout_logfile_maxbytes rotation built-in)"
elif [ -w /etc/logrotate.d ] || command -v sudo >/dev/null 2>&1; then
    cat > "$LOGROTATE_FILE" <<'LOGROTATE_EOF'
/opt/myblog/logs/blog-*.log /opt/myblog/logs/blog-warn-*.log {
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
# DEPLOY_MODE=data 模式强制要求 IMPORT_DB=1(已在顶部矛盾检测)
# DEPLOY_MODE=code + IMPORT_DB=1:warn 后仍执行(用户显式要求)
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
        # [WARN] 必须用 PIPESTATUS[0] 拿 sqlite-import.sh 的真实退出码:
        #   1. pipe 末尾的 sed 退出码盖住了前面
        #   2. sqlite-import.sh 在 y/N 门不答时 exit 0(当作"已取消")
        #      旧版只看 $? 永远拿 0, 会打印"[OK] 数据导入完成"假成功
        # 部署期 stdin 不是终端,必须显式传 FORCE_IMPORT=1 跳过交互确认门
        FORCE_IMPORT=1 bash "$INSTALL_DIR/scripts/sqlite-import.sh" "$DB_FILE" "$ENC_FILE" 2>&1 | sed 's/^/    /'
        IMPORT_RC=${PIPESTATUS[0]}
        
        if [ "$IMPORT_RC" -ne 0 ]; then
            err "Data import failed (wrong password, y/N cancel, or corrupted file), exit code $IMPORT_RC"
        fi
        info "[OK] Data import complete"

        # sqlite-import.sh 删除旧 DB 文件并重建,app 进程持有的 fd 指向已删除的 inode
        # 必须重启 app 让它重新打开新 DB 文件并刷新缓存
        info "Restarting app to pick up imported DB..."
        if [ "$LOCAL_SIM" = "1" ]; then
            supervisorctl restart myblog
        elif systemctl is-active --quiet myblog 2>/dev/null; then
            systemctl restart myblog
        fi
    fi
fi

# 关闭 DEPLOY_MODE=data 的 if 块(步骤 5 开启,包裹到 step 8.5)
fi  # DEPLOY_MODE != "data"

# ============= 9. nginx 反代 + 静态文件 =============
# DEPLOY_MODE=data 跳过 nginx 配置(前端没动,nginx 配置也不变)
if [ "$DEPLOY_MODE" = "data" ]; then
    info "=== 9. nginx config ==="
    info "    DEPLOY_MODE=data, skipping nginx config (frontend unchanged)"
else
    info "=== 9. Configuring nginx ==="
NGINX_CONF="/etc/nginx/conf.d/myblog.conf"
cat > "$NGINX_CONF" <<EOF
server {
    listen       $PUBLIC_PORT default_server;
    server_name  _;

    # 前端静态文件(v2.7.0 全静态)
    root         $INSTALL_DIR/frontend;
    index        index.html;
    try_files    \$uri \$uri/ /200.html;

    # Nuxt 生成的 SPA fallback
    location = /200.html { add_header Cache-Control "no-cache"; }

    # 静态资源长缓存
    location ~* \.(js|css|woff2?|ttf|svg|png|jpg|jpeg|gif|ico|webp)$ {
        expires 7d;
        add_header Cache-Control "public, immutable";
    }

    # 后端 API 反代
    location /api/ {
        proxy_pass         http://127.0.0.1:$SERVER_PORT;
        proxy_set_header   Host              \$host;
        proxy_set_header   X-Real-IP         \$remote_addr;
        proxy_set_header   X-Forwarded-For   \$proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto \$scheme;
        proxy_read_timeout 60s;
        client_max_body_size 20m;
    }

    # gzip
    gzip on;
    gzip_types text/plain text/css application/javascript application/json image/svg+xml;
    gzip_min_length 1024;
}
EOF

# 处理可能与本配置冲突的"自带 default_server"
# Debian/Ubuntu:/etc/nginx/sites-enabled/default(含 listen 80 default_server)
# CentOS/RHEL:/etc/nginx/nginx.conf 的 http {} 里直接有个 server { listen 80 default_server; ... }
# 两种都要处理, 否则 nginx -t 会报 "duplicate default server" 部署中断.
#
# [WARN] 不能用 perl 块级正则去注释整段 server ——  块内嵌套的 location / error_page 让非贪婪 `.*?^\s*\}`
#    在第一个 location 的 `}` 就截断, 而且 `$1` 前面加 `#` 只注释第一行, 剩下的 listen / server_name / root
#    全部悬空在 http{} 里, nginx -t 报语法错直接挂掉(Debian 没事因为 perl 空跑没匹配到).
#
# 正确做法:只摘掉自带 listen 行上的 `default_server` 关键字.
# 规则:只要**任何 server 显式声明 default_server**, 它就是默认.我们的 conf.d/myblog.conf 已带 default_server, 
# 把自带的删掉后, 自带 server 变成普通 server(不抢默认), 我们的站点稳定成为默认, 不会再有 duplicate 报错.
# 唯一副作用:自带 server 与我们的 server_name _ 重名, nginx 会打一条 conflicting server name 的 warning(非致命, nginx -t 通过).
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
fi  # DEPLOY_MODE != "data" (close step 9 nginx block)

# ============= 10. Redis 启动 =============
info "=== 10. Starting Redis ==="
if [ "$LOCAL_SIM" = "1" ]; then
    supervisorctl restart redis
else
    systemctl enable redis-server 2>/dev/null || systemctl enable redis 2>/dev/null || true
    systemctl restart redis-server 2>/dev/null || systemctl restart redis 2>/dev/null || true
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

echo
info "=========================================="
info " [OK] Deployment complete"
info "=========================================="
info "  Access:        http://<server-ip>:$PUBLIC_PORT"
info "  Admin:        http://<server-ip>:$PUBLIC_PORT/admin/login"
info "  Default account:    admin / 123456  (change password in production)"
if [ "$LOCAL_SIM" = "1" ]; then
    info "  View logs:    supervisorctl tail -f myblog"
    info "                  tail -f $INSTALL_DIR/logs/app.log"
    info "  Restart service:    supervisorctl restart myblog"
else
    info "  View logs:    journalctl -u myblog -f"
    info "                  tail -f $INSTALL_DIR/logs/app.log"
    info "  Restart service:    systemctl restart myblog"
fi
info "  Version rollback:    $0 v4.1.0   (specify old tag)"
info "=========================================="
