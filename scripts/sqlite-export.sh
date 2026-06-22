#!/bin/bash
# ============================================================
# SQLite 数据库导出脚本(加密)
# ============================================================
# 把开发环境 blog.db 导出 → gzip → AES-256-CBC 加密,产出 .sql.gz.enc
# 用途:跨环境数据迁移(防止传输过程中泄露)
#
# 用法:
#   bash docs/scripts/sqlite-export.sh                           # 默认导出 backend/blog.db(项目根)
#   SQLITE_PATH=/opt/myblog/db/blog.db bash ...                  # 通过 env 指定 db 路径(v4.2.1+)
#   bash docs/scripts/sqlite-export.sh /path/to/blog.db          # 指定源 db
#   bash docs/scripts/sqlite-export.sh --exclude page_view       # 排除指定表(逗号分隔)
#   bash docs/scripts/sqlite-export.sh -o /tmp/blog-2026.sql.gz.enc  # 指定输出文件
#   bash docs/scripts/sqlite-export.sh --no-data                 # 只导 schema 不导数据(仍加密)
#   bash docs/scripts/sqlite-export.sh --clear-tables=admin_device,page_view
#                                # 显式清空指定表的数据(保留 schema)
#                                # 默认**不**清空任何表(数据备份场景需保留全量数据)
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
# 必须用全局变量传密码（$() 子 shell 拿不到 tty）
prompt_password_twice() {
    # 2026-06-20 v4.2.0 新增:env 密码模式(非交互自动化场景,如 blog-backup.sh)
    # 当 BACKUP_ENCRYPTION_PASSWORD 或 DB_EXPORT_PASSWORD 环境变量已设置,
    # 直接复用,不再交互式输入两次。优先级 BACKUP_ENCRYPTION_PASSWORD > DB_EXPORT_PASSWORD。
    if [[ -n "${BACKUP_ENCRYPTION_PASSWORD:-}" || -n "${DB_EXPORT_PASSWORD:-}" ]]; then
        local env_pw="${BACKUP_ENCRYPTION_PASSWORD:-${DB_EXPORT_PASSWORD}}"
        if [[ -z "$env_pw" ]]; then
            echo "[ERR] BACKUP_ENCRYPTION_PASSWORD is set but empty" >&2
            return 5
        fi
        if [[ ${#env_pw} -lt 8 ]]; then
            warn "Password from env < 8 chars, weak (continuing)"
        fi
        PROMPT_PASSWORD="$env_pw"
        echo "$env_pw"  # 老 API 兼容
        return 0
    fi
    # 非交互场景预防性检查(只用于明确错误的快速失败)
    if [[ ! -t 0 ]] && [[ "${FORCE_EXPORT:-0}" != "1" ]]; then
        echo "[ERR] Non-interactive stdin detected and FORCE_EXPORT=1 not set" >&2
        echo "    (publish-release.sh auto-passes FORCE_EXPORT=1 when piping)" >&2
        echo "    (set it manually too if running with non-tty stdin)" >&2
        echo "    (or set BACKUP_ENCRYPTION_PASSWORD / DB_EXPORT_PASSWORD to skip prompt)" >&2
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
# 默认 db 路径优先级:SQLITE_PATH env > 位置参数 > 旧默认 backend/blog.db
# 2026-06-21 v4.2.1 修复:blog-backup.sh 调用时如果漏传位置参数,会 fallback 到
# 旧默认 backend/blog.db(项目根下的开发机路径),与 INSTALL_DIR 部署(/opt/myblog/db/blog.db)对不上。
# 加 env 兜底,让上游 blog-backup.sh / 运维脚本可以更直接地传路径。
DB_PATH="${SQLITE_PATH:-backend/blog.db}"
OUTPUT=""
EXCLUDE_TABLES=""
DATA_ONLY=false
SCHEMA_ONLY=false

# ---- 强制清空的表(保留 schema,但不导出数据)----
# 2026-06-21 v4.x.x 行为变更:默认**不**清空任何表,数据备份场景需保留全量数据。
# 需要清空的调用方(全量部署 publish-release.sh / deploy-server.sh 的 IMPORT_DB 链路)
# 显式传 --clear-tables=admin_device,page_view。
#
# 语义说明:
#   - admin_device: 设备授权白名单,含设备指纹/IP 等敏感信息
#   - page_view: 公开页访问统计日志,含 ip/user_agent/referer 等 PII
# 必须用 --clear-tables(保留建表语句、只清数据)而非 --exclude(连 CREATE TABLE 一起剔除)——
# 应用的 PageViewFilter 写库、DashboardController(今日 PV/UV、趋势、热门 TOP10)读库都依赖此表存在,
# 表结构必须随 dump 发布,否则 IMPORT_DB=1 重建后后台仪表盘 no such table: page_view 直接 500。
CLEAR_DATA_TABLES=()

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
        --clear-tables)
            # 逗号分隔的表名列表:保留 schema,但不导出这些表的数据(INSERT 段跳过)
            # 例如: --clear-tables=admin_device,page_view
            if [[ -z "${2:-}" ]]; then
                error "--clear-tables requires a value (e.g. --clear-tables=admin_device,page_view)"
            fi
            for t in $(echo "$2" | tr ',' ' '); do
                CLEAR_DATA_TABLES+=("$t")
            done
            shift 2
            ;;
        --clear-tables=*)
            for t in $(echo "${1#*=}" | tr ',' ' '); do
                CLEAR_DATA_TABLES+=("$t")
            done
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

# sqlite3 包装:统一加 .timeout,避免与生产 SQLite 撞锁
# 2026-06-21：所有 sqlite3 调用走本函数，治标（治本需部署端开 WAL）
SQLITE_TIMEOUT_MS="${SQLITE_TIMEOUT_MS:-30000}"

# 用法:sqlite3_with_timeout <db_path> <sql_or_dotcmd...>
#      sql_or_dotcmd 形如 ".schema api_whitelist" 或 "SELECT 1"
# 注意:CLI 调用 .timeout 之后,所有同进程后续命令都继承这个超时,直到进程退出。
sqlite3_with_timeout() {
    local db_path="$1"; shift
    sqlite3 "$db_path" ".timeout $SQLITE_TIMEOUT_MS" "$@"
}

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
        EXISTS=$(sqlite3_with_timeout "$DB_PATH" "SELECT name FROM sqlite_master WHERE type='table' AND name='$tbl';" 2>/dev/null)
        if [[ -z "$EXISTS" ]]; then
            warn "Table $tbl does not exist, skipping"
            continue
        fi
        EXCLUDE_ARGS+="$tbl "
    done
fi

# ---- 收集所有表 ----
ALL_TABLES=$(sqlite3_with_timeout "$DB_PATH" ".tables" | tr -s ' ' '\n' | grep -v '^$' | sort)
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
# 2026-06-21 v4.2.2：.schema 失败保留现场 + 单表 dump 4次退避重试
schema_dump_failed=0
for tbl in "${EXPORT_TABLES[@]}"; do
    attempt=0
    max_attempts=4
    backoff_seq=(0 1 2 4)  # 第 1 次立即;第 2 次等 1s;第 3 次等 2s;第 4 次等 4s
    dump_ok=0
    while [[ $attempt -lt $max_attempts ]]; do
        sleep_secs="${backoff_seq[$attempt]}"
        if [[ $sleep_secs -gt 0 ]]; then
            sleep "$sleep_secs"
        fi
        if sqlite3_with_timeout "$DB_PATH" ".schema $tbl" 2>/tmp/.schema-err.$$ | grep -v "CREATE TABLE sqlite_sequence" >> "$TMP_SQL"; then
            dump_ok=1
            break
        fi
        attempt=$((attempt + 1))
        err_msg=$(cat /tmp/.schema-err.$$ 2>/dev/null || true)
        if [[ $attempt -lt $max_attempts ]]; then
            warn ".schema $tbl 失败 (第 ${attempt}/${max_attempts} 次): ${err_msg:-unknown error}, ${backoff_seq[$attempt]}s 后重试"
        else
            warn ".schema $tbl 失败 (第 ${attempt}/${max_attempts} 次,放弃): ${err_msg:-unknown error}"
        fi
    done
    rm -f /tmp/.schema-err.$$
    if [[ $dump_ok -ne 1 ]]; then
        # 保留失败时的 dump 现场
        cp "$TMP_SQL" "${TMP_SQL}.failed" 2>/dev/null || true
        echo "[FAIL_AT_TABLE=$tbl] $(date +%s) attempts=${attempt}" >> "${TMP_SQL}.failed"
        schema_dump_failed=1
        break
    fi
    echo "" >> "$TMP_SQL"
done
if [[ $schema_dump_failed -eq 1 ]]; then
    error "schema dump 失败,TMP_SQL 保留在 ${TMP_SQL}.failed"
fi

# 3. 数据(INSERT)
# 不用 .mode insert（旧版 sqlite3 无 unistr 函数），改由 Python 生成 SQL
if [[ "$SCHEMA_ONLY" != "true" ]]; then
    # 排除表清单 → 数组给 Python(逗号分隔字符串)
    EXCLUDE_PY=$(printf "'%s'," "${EXPORT_TABLES[@]}")
    EXCLUDE_PY="[${EXCLUDE_PY%,}]"

    # 强制清空数据的表(只留 schema,不导出 INSERT)→ 数组给 Python
    CLEAR_PY=$(printf "'%s'," "${CLEAR_DATA_TABLES[@]}")
    CLEAR_PY="[${CLEAR_PY%,}]"

    python3 - "$DB_PATH" "$EXCLUDE_PY" "$CLEAR_PY" "$SQLITE_TIMEOUT_MS" >> "$TMP_SQL" <<'PYEOF'
import sqlite3, sys
db_path = sys.argv[1]
tables = eval(sys.argv[2])
clear_tables = set(eval(sys.argv[3]))
# 2026-06-21 BUG 修复:Python 端 sqlite3.connect 默认 timeout=5s,但生产容器 PRAGMA busy_timeout=0 时
#   5s 也不够(5s 内可能还在等 page_view 异步写完)。与脚本 CLI 端对齐 SQLITE_TIMEOUT_MS(默认 30s)。
#   治本仍是部署端开 WAL + busy_timeout=10000;治标是这里手动覆盖 timeout。
timeout_ms = int(sys.argv[4])
con = sqlite3.connect(db_path, timeout=timeout_ms / 1000.0)
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
                # SQL 字符串字面量:只转义单引号('' ),换行/回车/Tab 等空白**保持原样**。
                # ⚠️ 绝不能把换行 escape 成字面 \n —— SQLite 字符串字面量不解析反斜杠转义,
                #    `'...\n...'` 会被原样存成「反斜杠+n」两个字符,导入后 markdown 正文换行全坏
                #    (前端按真换行解析 → 标题/表格/代码块不渲染, \n 当文字显示)。
                #    SQLite 字面量允许直接内嵌真换行(多行字面量是合法 SQL,等同官方 .dump 行为),
                #    sqlite3 CLI 读到闭合单引号+分号才结束语句,多行 INSERT 导入完全正确。
                s = str(v).replace("'", "''")
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
if [[ ${#CLEAR_DATA_TABLES[@]} -gt 0 ]]; then
    echo "  Cleared: ${CLEAR_DATA_TABLES[*]} (schema only, data excluded)"
else
    echo "  Cleared: (none, all tables' data exported)"
fi

echo -e "${YELLOW}[KEY] REMEMBER THIS PASSWORD! You will need the same password to import on production.${NC}"
echo -e "${YELLOW}   Lost password = unrecoverable data (that's the point of encryption)${NC}"
echo
echo "Next steps:"
echo "  # Local import (test)"
echo "  bash docs/scripts/sqlite-import.sh /tmp/test.db $OUTPUT"
echo "  # Publish to GitHub Release (publish-release.sh with EXPORT_DB=1)"
echo "  # Remote import on production (deploy-server.sh with IMPORT_DB=1)"