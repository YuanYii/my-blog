#!/bin/bash
# ============================================================
# SQLite 数据库导出脚本(加密)
# ============================================================
# 把开发环境 blog.db 导出 → gzip → AES-256-CBC 加密,产出 .sql.gz.enc
# 用途:跨环境数据迁移(防止传输过程中泄露)
#
# 用法:
#   bash docs/scripts/sqlite-export.sh                           # 默认导出 backend/blog.db
#   bash docs/scripts/sqlite-export.sh /path/to/blog.db          # 指定源 db
#   bash docs/scripts/sqlite-export.sh --exclude page_view       # 排除指定表(逗号分隔)
#   bash docs/scripts/sqlite-export.sh -o /tmp/blog-2026.sql.gz.enc  # 指定输出文件
#   bash docs/scripts/sqlite-export.sh --no-data                 # 只导 schema 不导数据(仍加密)
#
# 算法:openssl AES-256-CBC + PBKDF2 100k 迭代 + salt
# 跨平台:macOS LibreSSL / Linux OpenSSL 3.x / Alpine busybox openssl 都支持
#
# 输出: ./backups/blog-YYYYMMDD-HHMMSS.sql.gz.enc(默认)
# 导入: bash docs/scripts/sqlite-import.sh /path/to/blog.db ./backups/blog-*.sql.gz.enc
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

# ---- 密码函数(两遍输入确认)----
# [WARN] 必须用全局变量 PASSWORD 传递密码,不能用 `PASSWORD=$(...)` 命令替换:
#    $() 会创建子 shell,子 shell 的 stdin 是 pipe 不是 tty,
#    read -rs 拿空值 → 两次空值"不一致"循环死锁(屏幕看着像卡住)
# 重要:所有提示文字走 stderr(>&2),echo 密码到 stdout(老 API,保留)
prompt_password_twice() {
    # 非交互场景预防性检查(只用于明确错误的快速失败)
    if [[ ! -t 0 ]] && [[ "${FORCE_EXPORT:-0}" != "1" ]]; then
        echo "[ERR] Non-interactive stdin detected and FORCE_EXPORT=1 not set" >&2
        echo "    (publish-release.sh auto-passes FORCE_EXPORT=1 when piping)" >&2
        echo "    (set it manually too if running with non-tty stdin)" >&2
        return 3
    fi
    # 显式备份 stdin 到 fd 3,然后用 fd 3 读密码
    # [WARN] 关键:在 bash -c "FORCE_EXPORT=1 bash export.sh ... 2> >(sed ...)" 场景下,
    #    export 进程的 stdin 是从 bash -c 传进来的,可能是 TTY 也可能是 expect PTY.
    #    显式 -u 3 让 read 直接从 fd 3 拿(就是 export 自己的 stdin),
    #    避免 read 在某些 shell/pty 组合下被错误地从 stderr (process substitution 走的管道) 读
    exec 3<&0
    local pw1 pw2
    while true; do
        echo -n "Enter encryption password (no echo, 8+ chars recommended): " >&2
        if ! read -rs -u 3 pw1; then
            echo >&2
            echo "[ERR] Failed to read password (EOF or interrupted)" >&2
            exec 3<&-
            return 4
        fi
        echo >&2
        echo -n "Confirm password: " >&2
        if ! read -rs -u 3 pw2; then
            echo >&2
            echo "[ERR] Failed to read confirmation (EOF or interrupted)" >&2
            exec 3<&-
            return 4
        fi
        echo >&2
        if [[ -z "$pw1" ]]; then
            warn "Password cannot be empty, retry"
            continue
        fi
        if [[ "$pw1" != "$pw2" ]]; then
            warn "Two inputs do not match, retry"
            continue
        fi
        if [[ ${#pw1} -lt 8 ]]; then
            warn "Password < 8 chars, weak (continuing)"
        fi
        PROMPT_PASSWORD="$pw1"
        echo "$pw1"  # 老 API 兼容
        exec 3<&-
        return
    done
}

# ---- 安全删除临时文件(shred 优先,失败 fallback rm)----
secure_rm() {
    local f="$1"
    if command -v shred >/dev/null 2>&1; then
        shred -u -z "$f" 2>/dev/null || rm -f "$f"
    else
        rm -f "$f"
    fi
}

# ---- 参数解析 ----
DB_PATH="backend/blog.db"
OUTPUT=""
EXCLUDE_TABLES=""
DATA_ONLY=false
SCHEMA_ONLY=false

# ---- 强制清空的表(保留 schema,但不导出数据)----
# admin_device 是设备授权白名单表,含设备指纹/IP 等敏感信息,
# 导出/迁移时一律清空数据,避免随备份文件泄露。
CLEAR_DATA_TABLES=("admin_device")

while [[ $# -gt 0 ]]; do
    case "$1" in
        --exclude)
            EXCLUDE_TABLES="$2"
            shift 2
            ;;
        --exclude=*)
            EXCLUDE_TABLES="${1#*=}"
            shift
            ;;
        -o|--output)
            OUTPUT="$2"
            shift 2
            ;;
        --no-data)
            SCHEMA_ONLY=true
            shift
            ;;
        --data-only)
            DATA_ONLY=true
            shift
            ;;
        -h|--help)
            sed -n '2,24p' "$0"
            exit 0
            ;;
        *)
            # 第一个非选项参数当作 db path
            if [[ -z "$DB_PATH_SET" ]]; then
                DB_PATH="$1"
                DB_PATH_SET=1
                shift
            else
                error "Unknown argument: $1 (use --help for usage)"
            fi
            ;;
    esac
done

# ---- 校验 ----
command -v sqlite3 >/dev/null 2>&1 || error "sqlite3 not installed, install: brew install sqlite"
command -v openssl >/dev/null 2>&1 || error "openssl not installed"

# 解析 db 路径(支持相对路径,相对项目根)
# 2026-06-18:脚本搬到 docs/scripts/ 后比原 scripts/ 多一层目录,项目根要往上跳两级
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

if [[ ! "$DB_PATH" = /* ]]; then
    DB_PATH="$PROJECT_ROOT/$DB_PATH"
fi

[[ -f "$DB_PATH" ]] || error "Database file not found: $DB_PATH"

# 输出路径(默认 .sql.gz.enc)
if [[ -z "$OUTPUT" ]]; then
    mkdir -p "$PROJECT_ROOT/backups"
    TIMESTAMP=$(date +%Y%m%d-%H%M%S)
    OUTPUT="$PROJECT_ROOT/backups/blog-${TIMESTAMP}.sql.gz.enc"
fi

# 兜底:用户给的 -o 不是 .enc 后缀,提醒一下
if [[ "$OUTPUT" != *.enc ]]; then
    warn "Output file does not have .enc suffix: $OUTPUT"
    warn "This script only supports encrypted export, output must be .enc"
fi

# ---- 排除表处理 ----
EXCLUDE_ARGS=""
if [[ -n "$EXCLUDE_TABLES" ]]; then
    # 校验表是否存在
    for tbl in $(echo "$EXCLUDE_TABLES" | tr ',' ' '); do
        EXISTS=$(sqlite3 "$DB_PATH" "SELECT name FROM sqlite_master WHERE type='table' AND name='$tbl';" 2>/dev/null)
        if [[ -z "$EXISTS" ]]; then
            warn "Table $tbl does not exist, skipping"
            continue
        fi
        EXCLUDE_ARGS+="$tbl "
    done
fi

# ---- 收集所有表 ----
ALL_TABLES=$(sqlite3 "$DB_PATH" ".tables" | tr -s ' ' '\n' | grep -v '^$' | sort)
TOTAL_COUNT=$(echo "$ALL_TABLES" | wc -l | tr -d ' ')

info "Source database: $DB_PATH"
info "Output file:     $OUTPUT"
info "Total tables:    $TOTAL_COUNT"

# 过滤排除表
EXPORT_TABLES=()
for tbl in $ALL_TABLES; do
    SKIP=false
    for ex in $EXCLUDE_ARGS; do
        if [[ "$tbl" == "$ex" ]]; then
            SKIP=true
            break
        fi
    done
    if $SKIP; then
        warn "Skipping: $tbl"
    else
        EXPORT_TABLES+=("$tbl")
    fi
done

EXPORT_COUNT=${#EXPORT_TABLES[@]}
info "Will export: $EXPORT_COUNT / $TOTAL_COUNT tables"

for ctbl in "${CLEAR_DATA_TABLES[@]}"; do
    for tbl in "${EXPORT_TABLES[@]}"; do
        if [[ "$tbl" == "$ctbl" ]]; then
            warn "Table $ctbl contains device authorization data, data will be cleared (schema only)"
            break
        fi
    done
done

# ---- 临时文件(用 trap 保证清理)----
TMP_SQL=$(mktemp -t blog-export-sql-XXXXXX.sql)
TMP_GZ=$(mktemp -t blog-export-gz-XXXXXX.sql.gz)
trap 'secure_rm "$TMP_SQL"; secure_rm "$TMP_GZ"; secure_rm "${TMP_GZ}.enc"' EXIT

# ---- 开始导出 ----
echo
echo "=== Exporting plain SQL to temp file ==="

# 1. PRAGMA(必要:外键关闭,导入时不触发顺序)
{
    echo "PRAGMA foreign_keys=OFF;"
    echo "PRAGMA synchronous=NORMAL;"
    echo "BEGIN TRANSACTION;"
} > "$TMP_SQL"

# 2. Schema(过滤 sqlite_sequence,这是 SQLite 内部表不能手动 INSERT)
for tbl in "${EXPORT_TABLES[@]}"; do
    sqlite3 "$DB_PATH" ".schema $tbl" | grep -v "CREATE TABLE sqlite_sequence" >> "$TMP_SQL"
    echo "" >> "$TMP_SQL"
done

# 3. 数据(INSERT)
# [WARN] 不能用 `sqlite3 ... .mode insert $tbl; SELECT *` —— sqlite 3.50+ (2025-05-29 起)
#    对非 ASCII 字符(中文/换行)会自动包成 unistr('...\u000a...'),但生产 ECS 的系统
#    sqlite3 是 3.22/3.31/3.37(< 3.50),没有 unistr() 函数 → import 时报
#    "no such function: unistr" 一连串错.
# 改用 Python 直接生成 SQL,字符串里所有换行/制表符/单引号都手工转义成 SQL 标准
# (新行 → '\n' 字面两字符,单引号 → '')—— 任意 sqlite 版本都能 import.
if [[ "$SCHEMA_ONLY" != "true" ]]; then
    # 排除表清单 → 数组给 Python(逗号分隔字符串)
    EXCLUDE_PY=$(printf "'%s'," "${EXPORT_TABLES[@]}")
    EXCLUDE_PY="[${EXCLUDE_PY%,}]"

    # 强制清空数据的表(只留 schema,不导出 INSERT)→ 数组给 Python
    CLEAR_PY=$(printf "'%s'," "${CLEAR_DATA_TABLES[@]}")
    CLEAR_PY="[${CLEAR_PY%,}]"

    python3 - "$DB_PATH" "$EXCLUDE_PY" "$CLEAR_PY" >> "$TMP_SQL" <<'PYEOF'
import sqlite3, sys
db_path = sys.argv[1]
tables = eval(sys.argv[2])
clear_tables = set(eval(sys.argv[3]))
con = sqlite3.connect(db_path)
for tbl in tables:
    if tbl in clear_tables:
        print(f'-- === data: {tbl} (cleared: device authorization table, data excluded from export) ===')
        print()
        continue
    print(f'-- === data: {tbl} ===')
    cur = con.execute(f'SELECT * FROM {tbl}')
    cols = [d[0] for d in cur.description]
    col_list = ','.join(f'"{c}"' for c in cols)
    for row in cur:
        vals = []
        for v in row:
            if v is None:
                vals.append('NULL')
            elif isinstance(v, (int, float)):
                vals.append(str(v))
            elif isinstance(v, bytes):
                vals.append("X'" + v.hex() + "'")
            else:
                # SQL 字符串字面量:换行→\n,回车→\r,Tab→\t,单引号→''(SQL 标准转义)
                s = str(v).replace("'", "''").replace('\n', '\\n').replace('\r', '\\r').replace('\t', '\\t')
                vals.append(f"'{s}'")
        print(f'INSERT INTO {tbl} ({col_list}) VALUES ({",".join(vals)});')
    print()
PYEOF
fi

# 4. COMMIT + 恢复 PRAGMA
{
    echo "COMMIT;"
    echo "PRAGMA foreign_keys=ON;"
} >> "$TMP_SQL"

# ---- 编码校验(防 GBK 污染)----
if command -v file >/dev/null 2>&1; then
    FILE_ENC=$(file -b --mime-encoding "$TMP_SQL")
    if [[ "$FILE_ENC" != *"utf-8"* ]] && [[ "$FILE_ENC" != *"us-ascii"* ]] && [[ "$FILE_ENC" != *"binary"* ]]; then
        warn "Non-UTF-8 encoding detected: $FILE_ENC, transcoding"
        if command -v iconv >/dev/null 2>&1; then
            iconv -f GBK -t UTF-8 "$TMP_SQL" > "${TMP_SQL}.utf8" 2>/dev/null && mv "${TMP_SQL}.utf8" "$TMP_SQL"
            info "Transcoded to UTF-8"
        else
            warn "iconv not available, skipping transcode (may affect CJK)"
        fi
    fi
fi

SQL_SIZE=$(du -h "$TMP_SQL" | cut -f1)
info "Plain SQL: $SQL_SIZE"

# ---- 步骤 1:gzip 压缩 ----
echo
echo "=== gzip compression ==="
gzip -c "$TMP_SQL" > "$TMP_GZ"
secure_rm "$TMP_SQL"
GZ_SIZE=$(du -h "$TMP_GZ" | cut -f1)
info "Compressed: $GZ_SIZE"

# ---- 步骤 2:AES-256-CBC 加密(强制要求密码)----
echo
echo "=== AES-256-CBC + PBKDF2 encryption ==="
# [WARN] 不要用 PASSWORD=$(prompt_password_twice)——$() 创建子 shell,stdin 不是 tty,
#    read -rs 拿不到密码,会死循环.改成函数副作用写 PROMPT_PASSWORD 全局变量.
PROMPT_PASSWORD=""
prompt_password_twice
PROMPT_RC=$?
if [[ "$PROMPT_RC" -ne 0 || -z "$PROMPT_PASSWORD" ]]; then
    error "Failed to get password (exit code $PROMPT_RC)"
fi
PASSWORD="$PROMPT_PASSWORD"
unset PROMPT_PASSWORD  # 内存清掉,避免后续 echo 泄漏

# 关键:用 env 传密码,避免进 ps 命令行
export DUMP_PASSWORD="$PASSWORD"
openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -salt \
    -pass env:DUMP_PASSWORD \
    -in "$TMP_GZ" \
    -out "${TMP_GZ}.enc" 2>/dev/null
ENC_RC=$?
unset DUMP_PASSWORD

# 立即擦掉 gz 临时文件(密码已经在内存里了,不需要 gz 残留)
secure_rm "$TMP_GZ"

if [[ $ENC_RC -ne 0 || ! -f "${TMP_GZ}.enc" ]]; then
    error "openssl encryption failed (exit code $ENC_RC)"
fi

# ---- 步骤 3:移到目标路径 ----
mv "${TMP_GZ}.enc" "$OUTPUT"
# trap 清不掉已被 mv 走的文件,无影响

# ---- 报告 ----
FILE_SIZE=$(du -h "$OUTPUT" | cut -f1)
echo
info "Encrypted export complete"
echo "  File:    $OUTPUT"
echo "  Size:    $FILE_SIZE"
echo "  Tables:  $EXPORT_COUNT"
echo "  Cleared: ${CLEAR_DATA_TABLES[*]} (schema only, data excluded)"

echo -e "${YELLOW}[KEY] REMEMBER THIS PASSWORD! You will need the same password to import on production.${NC}"
echo -e "${YELLOW}   Lost password = unrecoverable data (that's the point of encryption)${NC}"
echo
echo "Next steps:"
echo "  # Local import (test)"
echo "  bash docs/scripts/sqlite-import.sh /tmp/test.db $OUTPUT"
echo "  # Publish to GitHub Release (publish-release.sh with EXPORT_DB=1)"
echo "  # Remote import on production (deploy-server.sh with IMPORT_DB=1)"