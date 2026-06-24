#!/bin/bash
# 服务器端执行:一键从 GitHub Release 拉资源 + 装环境 + 部署 my-blog
# 适用:CentOS 7+ / Ubuntu 18.04+ / Debian 10+ 最小化系统
# 跑法(root 或 sudo):
#   curl -L https://raw.githubusercontent.com/OWNER/REPO/main/scripts/deploy-server.sh -o deploy-server.sh
#   chmod +x deploy-server.sh
#   sudo ./deploy-server.sh v4.3.0
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
TAG="${1:-${RELEASE_TAG:-}}"
GITHUB_REPO="${GITHUB_REPO:-}"
INSTALL_DIR="${INSTALL_DIR:-/opt/myblog}"
SERVER_PORT="${SERVER_PORT:-8080}"
PUBLIC_PORT="${PUBLIC_PORT:-80}"
DB_FILE="${DB_FILE:-$INSTALL_DIR/db/blog.db}"
SKIP_DEPS="${SKIP_DEPS:-0}"
OPEN_FIREWALL="${OPEN_FIREWALL:-1}"
DEPLOY_MODE="${DEPLOY_MODE:-full}"      # init | full | docker-create | docker-init
IMPORT_DB="${IMPORT_DB:-0}"             # 0/1
LOCAL_SIM="${LOCAL_SIM:-0}"             # 0=生产模式  1=本地模拟容器模式

RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# 校验
if [ -z "$TAG" ]; then
    err "Usage: $0 <tag>  e.g. $0 v4.3.0"
    err "Or:RELEASE_TAG=v4.3.0 $0"
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
       err "  Note: code/frontend/backend/sql/data were removed in v4.4.0, use 'full' instead" ;;
esac

# (v4.4.0: 'data' 模式已删除,矛盾检测块随之移除——'full + IMPORT_DB=1' 是合法组合,不构成矛盾)

# init 模式:DB 已存在时警告(可能误操作)
if [ "$DEPLOY_MODE" = "init" ] && [ -f "$DB_FILE" ]; then
    warn "DEPLOY_MODE=init but DB already exists at $DB_FILE"
    warn "  If you want to upgrade, use DEPLOY_MODE=full instead"
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
mkdir -p "$INSTALL_DIR"/{logs,frontend,db,uploads} "$INSTALL_DIR/db/backups" "$INSTALL_DIR/logs/archive"
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
    curl -fsSL -o "$TMP_DIR/SHA256SUMS" "$BASE_URL/SHA256SUMS" 2>/dev/null || warn "No SHA256SUMS, skipping verification"
    if [ -f "$TMP_DIR/SHA256SUMS" ]; then
        if command -v sha256sum >/dev/null; then
            info "Verifying sha256..."
            (cd "$TMP_DIR" && sha256sum -c SHA256SUMS) || err "sha256 verification failed (refusing to deploy corrupted bundle)"
        fi
    fi
else
    warn "No cold-deployment package, downloading files individually"
    download "blog-app.jar"
    download "frontend-static.tar.gz"
    download "schema-sqlite.sql"
    download "deploy-server.sh"
    download "sqlite-import.sh" || warn "sqlite-import.sh download failed (needed when IMPORT_DB=1)"
    # v4.2.0 数据备份脚本：admin 后台「数据备份」菜单由后端 ProcessBuilder 调它
    download "blog-backup.sh" || warn "blog-backup.sh download failed (v4.2.0+ data backup feature will not work)"
    download "sqlite-export.sh" || warn "sqlite-export.sh download failed (v4.3.0+ admin data export feature will not work)"
    download "blog-restore.sh" || warn "blog-restore.sh download failed (v4.3.0+ data restore feature will not work)"
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

# v4.3.0+ 数据恢复脚本：deploy-server.sh 部署到 $INSTALL_DIR/scripts/blog-restore.sh
# 路径固定（RestoreService 写死 /opt/myblog/scripts/blog-restore.sh）
# 注意：脚本本身用 systemd-run --scope 启才能 stop myblog 不自杀（设计文档 §3.2），
#   但脚本路径必须先就位才能被 RestoreService 调到。sudoers/myblog-restore.slice 配置
#   不在本脚本职责范围，需运维手动配（见 scripts/sudoers-myblog-restore.example）。
if [ -f "$TMP_DIR/blog-restore.sh" ]; then
    cp "$TMP_DIR/blog-restore.sh" "$INSTALL_DIR/scripts/blog-restore.sh"
    chmod +x "$INSTALL_DIR/scripts/blog-restore.sh"
    info "[OK] blog-restore.sh installed to $INSTALL_DIR/scripts/"
else
    warn "blog-restore.sh not in release (v4.3.0+ data restore feature will not work)"
fi

# v4.3.0+ 数据导出脚本：deploy-server.sh 部署到 $INSTALL_DIR/scripts/sqlite-export.sh
# 路径固定（BackupService 写死 /opt/myblog/scripts/sqlite-export.sh）
if [ -f "$TMP_DIR/sqlite-export.sh" ]; then
    cp "$TMP_DIR/sqlite-export.sh" "$INSTALL_DIR/scripts/sqlite-export.sh"
    chmod +x "$INSTALL_DIR/scripts/sqlite-export.sh"
    info "[OK] sqlite-export.sh installed to $INSTALL_DIR/scripts/"
else
    warn "sqlite-export.sh not in release (v4.3.0+ admin data export feature will not work)"
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
SKIP_MIGRATION=false

# docker-create / docker-init 是顶层命令,不进入下面的部署流程,直接走 docker 分支
case "$DEPLOY_MODE" in
    docker-create)
        info "=== DEPLOY_MODE=docker-create: 本地容器创建 ==="
        DOCKER_DIR="$ROOT_DIR/docs/deployment/docker/local-sim"
        if [ ! -f "$DOCKER_DIR/Dockerfile" ]; then
            err "未找到 Dockerfile: $DOCKER_DIR/Dockerfile"
            err "  请确认 docs/deployment/docker/local-sim/ 目录完整"
            exit 1
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
        info "  下一步: ./deploy-server.sh docker-init $TAG"
        exit 0
        ;;
    docker-init)
        info "=== DEPLOY_MODE=docker-init: 本地容器业务初始化 ==="
        if ! docker ps -a --format '{{.Names}}' | grep -q '^myblog-sim$'; then
            err "myblog-sim 容器不存在,请先跑: ./deploy-server.sh docker-create"
            exit 1
        fi
        if [ -z "$TAG" ]; then
            err "docker-init 需要指定 tag: ./deploy-server.sh docker-init v4.4.0"
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

# ---- schema 增量兜底:在删旧 DB 之前补齐生产历史库的列 ----
# 2026-06-21 v4.2.0 引入:Commit 5 把 step 5 改成"删旧 DB → 全量重建"后,生产历史 db
# 缺的新列(如 backup_record.trace_id)再也不会被"DEPLOY_MODE=code 保留 db"路径自动补上。
# 在删之前跑幂等 ALTER → 旧 db 备份文件(blog-before-{TAG}-*.db)里也带新列,
# 未来从备份恢复不会缺列。
#
# 通用模式(未来加列照抄):
#   if ! sqlite3 "$DB_FILE" "PRAGMA table_info(表名);" | grep -q "列名"; then
#       info "Patching 表名: adding 列名 column"
#       sqlite3 "$DB_FILE" "ALTER TABLE 表名 ADD COLUMN 列名 类型;"
#   fi
if [ -f "$DB_FILE" ]; then
    if ! sqlite3 "$DB_FILE" "PRAGMA table_info(backup_record);" | grep -q trace_id; then
        info "Patching backup_record: adding trace_id column (v4.2.0)"
        sqlite3 "$DB_FILE" "ALTER TABLE backup_record ADD COLUMN trace_id VARCHAR(64);" \
            || err "failed to ALTER TABLE backup_record ADD COLUMN trace_id"
        # ALTER ADD COLUMN 不会自动建 NOT NULL 默认值的索引(本列允许 NULL,免建)
        info "[OK] backup_record.trace_id column added"
    fi
fi

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

# ============= 5.1 执行增量 SQL migration =============
if [ "$SKIP_MIGRATION" = false ] && [ -f "$DB_FILE" ]; then
    info "=== 5.1 Running incremental SQL migrations ==="
    
    # 确保 _migration_history 表存在(首次可能没有)
    sqlite3 "$DB_FILE" "CREATE TABLE IF NOT EXISTS _migration_history (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        script_name VARCHAR(255) NOT NULL UNIQUE,
        executed_at DATETIME NOT NULL DEFAULT (datetime('now','localtime'))
    );"
    
    # 扫描并执行未执行的 migration
    MIGRATION_DIR="$TMP_DIR/migrations"
    if [ -d "$MIGRATION_DIR" ]; then
        MIGRATION_COUNT=0
        MIGRATION_FAILED=""
        
        for script in $(ls "$MIGRATION_DIR"/*.sql 2>/dev/null | sort); do
            script_name=$(basename "$script")
            
            # 检查是否已执行
            already_executed=$(sqlite3 "$DB_FILE" "SELECT COUNT(*) FROM _migration_history WHERE script_name='$script_name';")
            
            if [ "$already_executed" = "0" ]; then
                info "  Executing migration: $script_name"
                if sqlite3 "$DB_FILE" < "$script"; then
                    sqlite3 "$DB_FILE" "INSERT INTO _migration_history (script_name, executed_at) VALUES ('$script_name', datetime('now','localtime'));"
                    info "    [OK] $script_name executed successfully"
                    MIGRATION_COUNT=$((MIGRATION_COUNT + 1))
                else
                    MIGRATION_FAILED="$script_name"
                    err "    [FAIL] $script_name execution failed"
                    break
                fi
            else
                info "  Skipping migration: $script_name (already executed)"
            fi
        done
        
        if [ -n "$MIGRATION_FAILED" ]; then
            err "Migration failed at: $MIGRATION_FAILED"
            err "  Check the script for errors and fix manually"
        elif [ $MIGRATION_COUNT -gt 0 ]; then
            info "[OK] Executed $MIGRATION_COUNT migration(s)"
        else
            info "  No pending migrations to execute"
        fi
    else
        info "  No migrations directory found at $MIGRATION_DIR, skipping"
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
    try_files    $uri $uri/ /200.html;

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
info "  Version rollback:    $0 v4.3.0   (specify old tag)"
info "=========================================="
