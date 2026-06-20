#!/bin/bash
# ============================================================
# SQLite 数据库导入脚本(解密 + 导入)
# ============================================================
# 把 sqlite-export.sh 产出的 .sql.gz.enc 解密 → gunzip → 导入到目标 db
#
# 用法:
#   bash docs/scripts/sqlite-import.sh /path/to/target.db /path/to/dump.sql.gz.enc
#   bash docs/scripts/sqlite-import.sh /opt/myblog/blog.db /tmp/blog-20260618.sql.gz.enc
#   bash docs/scripts/sqlite-import.sh --remote myblog@1.2.3.4 /opt/myblog/blog.db /tmp/blog.sql.gz.enc
#
# 算法:AES-256-CBC + PBKDF2 100k 迭代 + salt(与 export 配对)
#
# 行为:
#   1. 校验 dump 是 .enc(明文 .sql 不再支持)
#   2. 交互式输入密码
#   3. 解密 → gunzip 到临时文件(失败则完全不碰目标 db)
#   4. 备份目标 db 为 .bak.YYYYMMDD-HHMMSS
#   5. 删原 db,从 schema 重建
#   6. 应用 dump.sql(全量覆盖)
#   7. 跑完整性校验
# ============================================================

set -e

# ---- 颜色输出 ----
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[OK]${NC} $*"; }
warn()  { echo -e "${YELLOW}[!]${NC} $*"; }
error() { echo -e "${RED}[ERR]${NC} $*"; exit 1; }

# ---- 密码函数(单次输入)----
# 重要:所有提示文字走 stderr(>&2),只 echo 密码到 stdout,
# 否则 $() 捕获会把提示污染进 PASSWORD 变量
prompt_password_once() {
    local pw
    # 优先读环境变量（deploy-server.sh 非交互场景传入）
    if [[ -n "${DB_DECRYPT_PASSWORD:-}" ]]; then
        echo "$DB_DECRYPT_PASSWORD"
        return 0
    fi
    # 非交互场景(stdin 不是终端)且没设 FORCE_IMPORT:直接拒绝,避免"假成功"
    if [[ ! -t 0 ]] && [[ "${FORCE_IMPORT:-0}" != "1" ]]; then
        echo "[ERR] Non-interactive stdin detected and FORCE_IMPORT=1 not set" >&2
        echo "    (deploy-server.sh auto-passes FORCE_IMPORT=1 during deploy)" >&2
        echo "    (set it manually too if running with non-tty stdin)" >&2
        return 3
    fi
    echo -n "Enter decryption password (no echo): " >&2
    read -rs pw; echo >&2
    if [[ -z "$pw" ]]; then
        echo "[ERR] Password cannot be empty" >&2
        return 2
    fi
    echo "$pw"
}

# ---- 安全删除临时文件 ----
secure_rm() {
    local f="$1"
    if command -v shred >/dev/null 2>&1; then
        shred -u -z "$f" 2>/dev/null || rm -f "$f"
    else
        rm -f "$f"
    fi
}

# ---- 参数解析 ----
REMOTE_HOST=""
if [[ "$1" == "--remote" ]]; then
    REMOTE_HOST="$2"
    TARGET_DB="$3"
    DUMP_FILE="$4"
    shift 4
else
    TARGET_DB="$1"
    DUMP_FILE="$2"
    shift 2
fi

[[ -n "$TARGET_DB" && -n "$DUMP_FILE" ]] || {
    echo "Usage:"
    echo "  Local: $0 /path/to/target.db /path/to/dump.sql.gz.enc"
    echo "  Remote: $0 --remote user@host /path/to/target.db /path/to/dump.sql.gz.enc"
    exit 1
}

# ---- Validate dump is .enc(plain .sql no longer supported)----
[[ "$DUMP_FILE" == *.enc ]] || error "Only encrypted .enc files are supported (plain .sql not supported; use export to regenerate .enc)"

# ---- Remote mode:scp + ssh ----
if [[ -n "$REMOTE_HOST" ]]; then
    info "Remote mode: $REMOTE_HOST"
    info "Uploading dump file..."
    REMOTE_TMP="/tmp/sqlite-import-$(date +%s).enc"
    scp "$DUMP_FILE" "$REMOTE_HOST:$REMOTE_TMP" || error "scp failed"
    info "Upload complete, remote decrypt + import..."
    # 通过 stdin 传密码(避免出现在 ssh 命令行)
    PASSWORD=$(prompt_password_once)
    echo "$PASSWORD" | ssh -T "$REMOTE_HOST" "bash -s" -- "$TARGET_DB" "$REMOTE_TMP" <<'REMOTE_EOF'
        # 复用本地导入逻辑(密码从 stdin 读)
        TARGET_DB="$1"
        DUMP_FILE="$2"
        set -e
        RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
        info()  { echo -e "${GREEN}[OK]${NC} $*"; }
        warn()  { echo -e "${YELLOW}[!]${NC} $*"; }
        error() { echo -e "${RED}[ERR]${NC} $*"; exit 1; }

        command -v sqlite3 >/dev/null 2>&1 || error "Remote sqlite3 not installed"
        command -v openssl >/dev/null 2>&1 || error "Remote openssl not installed"
        [[ -f "$DUMP_FILE" ]] || error "Remote dump file not found: $DUMP_FILE"

        # Read password from stdin
        echo -n "Enter decryption password (no echo): "
        read -rs REMOTE_PW; echo
        [[ -z "$REMOTE_PW" ]] && error "Password cannot be empty"

        # 临时文件
        TMP_GZ=$(mktemp -t blog-imp-gz-XXXXXX.gz)
        TMP_SQL=$(mktemp -t blog-imp-sql-XXXXXX.sql)
        trap 'rm -f "$TMP_GZ" "$TMP_SQL"' EXIT

        # 解密
        REMOTE_PW="$REMOTE_PW" openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \
            -pass env:REMOTE_PW \
            -in "$DUMP_FILE" \
            -out "$TMP_GZ" 2>/dev/null
        if [[ $? -ne 0 || ! -s "$TMP_GZ" ]]; then
            error "Decryption failed (wrong password or corrupted file), **target db untouched**"
        fi
        unset REMOTE_PW

        # gunzip
        gunzip -c "$TMP_GZ" > "$TMP_SQL"
        rm -f "$TMP_GZ"

        # Backup + rebuild + import
        if [[ -f "$TARGET_DB" ]]; then
            BAK="${TARGET_DB}.bak.$(date +%Y%m%d-%H%M%S)"
            cp "$TARGET_DB" "$BAK"
            info "Backed up: $BAK"
            rm -f "$TARGET_DB"
        else
            warn "Target db does not exist, creating: $TARGET_DB"
            mkdir -p "$(dirname "$TARGET_DB")"
        fi

        info "Importing SQL..."
        sqlite3 "$TARGET_DB" < "$TMP_SQL" || error "Import failed"
        rm -f "$TMP_SQL" "$DUMP_FILE"

        # Verify
        TABLES=$(sqlite3 "$TARGET_DB" ".tables" | tr -s ' ' '\n' | grep -v '^$' | wc -l | tr -d ' ')
        INTEGRITY=$(sqlite3 "$TARGET_DB" "PRAGMA integrity_check;")
        info "Import complete: $TABLES tables, integrity_check=$INTEGRITY"
REMOTE_EOF
    info "Remote import complete"
    exit 0
fi

# ---- 本地模式 ----
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# 解析相对路径
# 2026-06-18:脚本搬到 docs/scripts/ 后比原 scripts/ 多一层目录,项目根要往上跳两级
if [[ ! "$TARGET_DB" = /* ]]; then
    TARGET_DB="$SCRIPT_DIR/../../$TARGET_DB"
fi
if [[ ! "$DUMP_FILE" = /* ]]; then
    DUMP_FILE="$SCRIPT_DIR/../../$DUMP_FILE"
fi

command -v sqlite3 >/dev/null 2>&1 || error "sqlite3 not installed"
command -v openssl >/dev/null 2>&1 || error "openssl not installed"
[[ -f "$DUMP_FILE" ]] || error "dump file not found: $DUMP_FILE"

TARGET_DIR="$(dirname "$TARGET_DB")"
mkdir -p "$TARGET_DIR"

# ---- 临时文件 + trap ----
TMP_GZ=$(mktemp -t blog-imp-gz-XXXXXX.gz)
TMP_SQL=$(mktemp -t blog-imp-sql-XXXXXX.sql)
trap 'secure_rm "$TMP_GZ"; secure_rm "$TMP_SQL"' EXIT

# ---- 步骤 1:解密 ----
echo
echo "=== Decrypting dump ==="
PASSWORD=$(prompt_password_once)

export DUMP_PASSWORD="$PASSWORD"
openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \
    -pass env:DUMP_PASSWORD \
    -in "$DUMP_FILE" \
    -out "$TMP_GZ" 2>/dev/null
DEC_RC=$?
unset DUMP_PASSWORD

if [[ $DEC_RC -ne 0 || ! -s "$TMP_GZ" ]]; then
    error "Decryption failed (wrong password or corrupted file), **target db untouched**"
fi
GZ_SIZE=$(du -h "$TMP_GZ" | cut -f1)
info "Decryption successful: $GZ_SIZE"

# ---- Step 2:gunzip to SQL ----
echo
echo "=== gunzip decompression ==="
gunzip -c "$TMP_GZ" > "$TMP_SQL"
secure_rm "$TMP_GZ"
SQL_SIZE=$(du -h "$TMP_SQL" | cut -f1)
info "Decompressed: $SQL_SIZE"

# ---- Step 3:Backup target db ----
echo
if [[ -f "$TARGET_DB" ]]; then
    BAK="${TARGET_DB}.bak.$(date +%Y%m%d-%H%M%S)"
    cp "$TARGET_DB" "$BAK"
    info "Backed up target db -> $BAK"
    DB_EXISTED=1
else
    warn "Target db does not exist, creating: $TARGET_DB"
    DB_EXISTED=0
fi

# Force confirm (prevent accidental overwrites)
# FORCE_IMPORT=1 skips interactive prompt (deployment needs this, stdin is not a tty)
if [[ "$DB_EXISTED" == "1" ]]; then
    echo
    echo -e "${YELLOW}About to overwrite:${NC} $TARGET_DB"
    echo -e "${YELLOW}Import file:${NC} $DUMP_FILE"
    echo
    if [[ "${FORCE_IMPORT:-0}" == "1" ]]; then
        info "FORCE_IMPORT=1, skipping y/N confirm (deploy scenario)"
    elif [[ ! -t 0 ]]; then
        # Non-interactive + no FORCE_IMPORT: clear exit 3 to avoid silent "fake success"
        error "stdin is not a tty and FORCE_IMPORT=1 not set (deploy-server.sh auto-adds it)"
    else
        read -p "Confirm import? [y/N] " -n 1 -r
        echo
        # Cancellation gets non-zero exit (3) so upstream PIPESTATUS check detects it
        [[ $REPLY =~ ^[Yy]$ ]] || { warn "Cancelled (temp SQL file will be cleaned by trap)"; exit 3; }
    fi
fi

# ---- Step 4:Remove target db + import ----
if [[ "$DB_EXISTED" == "1" ]]; then
    rm -f "$TARGET_DB"
    info "Removed old db, ready to rebuild"
fi

echo
echo "=== Importing SQL ==="
sqlite3 "$TARGET_DB" < "$TMP_SQL" || error "Import failed"
secure_rm "$TMP_SQL"

# ---- Step 5:Verify ----
echo
info "=== Post-import verification ==="

TABLES=$(sqlite3 "$TARGET_DB" ".tables" | tr -s ' ' '\n' | grep -v '^$' | wc -l | tr -d ' ')
info "Table count: $TABLES"

echo
echo "Table row counts preview:"
for tbl in $(sqlite3 "$TARGET_DB" ".tables" | tr -s ' ' '\n' | grep -v '^$'); do
    CNT=$(sqlite3 "$TARGET_DB" "SELECT COUNT(*) FROM $tbl;" 2>/dev/null || echo "ERR")
    printf "  %-30s %s rows\n" "$tbl" "$CNT"
done

echo
INTEGRITY=$(sqlite3 "$TARGET_DB" "PRAGMA integrity_check;")
if [[ "$INTEGRITY" == "ok" ]]; then
    info "Integrity check: ok"
else
    error "Integrity check failed: $INTEGRITY"
fi

FILE_SIZE=$(du -h "$TARGET_DB" | cut -f1)
echo
info "Import complete"
echo "  Target db:  $TARGET_DB"
echo "  Size:       $FILE_SIZE"
echo
echo "Next steps:"
echo "  # Start backend:cd backend && java -jar blog-app.jar --spring.profiles.active=prod"
echo "  # Verify:bash docs/scripts/verify-sqlite.sh"