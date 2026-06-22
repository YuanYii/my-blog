#!/bin/bash
# ============================================================
# my-blog 数据备份脚本(加密 + GitHub Release)
# ============================================================
# 把生产服务器的 SQLite db + uploads 目录打包 → 加密 → 上传到独立 GitHub 仓库的 Release
#
# 用途:
#   1. admin 后台点击"立即备份" → 后端 ProcessBuilder 调本脚本
#   2. 手动应急备份(SSH 上去跑)
#
# 设计依据:
#   docs/设计文档/博客数据备份方案设计.md (REQ-BACKUP-2026-06-20)
#
# 用法:
#   # 通常由后端 ProcessBuilder 调(已设好全部 env,不用传参)
#   sudo -E bash /opt/myblog/scripts/blog-backup.sh
#
#   # 手动应急(密码走 stdin / 交互式)
#   BACKUP_ENCRYPTION_PASSWORD=xxx \
#   BACKUP_GITHUB_TOKEN=ghp_xxx \
#   GITHUB_BACKUP_REPO=owner/my-blog-backup \
#   bash blog-backup.sh
#
#   # dry-run(只打包加密,不上传)— 调试用
#   DRY_RUN=1 bash blog-backup.sh
#
# 必读环境变量(全部由 /etc/myblog/myblog.env 提供,后端 ProcessBuilder 已 export):
#   BACKUP_ENCRYPTION_PASSWORD   加密密码(8+ 位,绝不打日志)
#   BACKUP_GITHUB_TOKEN          GitHub PAT(repo 权限, **只**给 my-blog-backup 仓库用)
#   GITHUB_BACKUP_REPO           备份仓库(私有,owner/my-blog-backup)
# 命名分开：备份 token 与发布 token 互不交叉授权
#
# 可选环境变量:
#   BACKUP_STAGE_DIR             明文中转目录(默认 /tmp/blog-backup-stage)
#   INSTALL_DIR                  部署根(默认 /opt/myblog)
#   SQLITE_PATH                  db 路径(默认 /opt/myblog/db/blog.db, 跟 myblog.env 对齐)
#   UPLOAD_DIR                   上传目录(默认 /opt/myblog/uploads)
#   SKIP_UPLOADS=1               跳过 uploads(只备份 db)
#   SKIP_DB=1                    跳过 db(只备份 uploads)
#   DRY_RUN=1                    不上传 GitHub,只在 stage 目录产出
#   DB_EXCLUDE_TABLES            透传给 sqlite-export.sh 的 --exclude
#
# 退出码:
#   0  成功
#   10 预检失败(必填 env 缺失)
#   11 db dump 失败
#   12 uploads 打包/加密失败
#   13 manifest 生成失败
#   14 SHA256SUMS 生成失败
#   15 GitHub release 创建失败
#   16 GitHub asset 上传失败
#   17 依赖自装失败(jq 装不上,curl 兜底分支不可用)
# ============================================================

set -euo pipefail

# ============= 0. 准备 =============
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# 不假设脚本必须从 INSTALL_DIR/scripts 跑(开发机调试时可能在 git 仓根)
INSTALL_DIR="${INSTALL_DIR:-/opt/myblog}"
SQLITE_PATH="${SQLITE_PATH:-$INSTALL_DIR/db/blog.db}"
UPLOAD_DIR="${UPLOAD_DIR:-$INSTALL_DIR/uploads}"
STAGE_DIR="${BACKUP_STAGE_DIR:-/tmp/blog-backup-stage}"
SCRIPT_DIR_LOCAL="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SQLITE_EXPORT_SH=""
# 优先从 deploy-server 部署的标准位置找,其次开发机 git 仓
for candidate in \
    "$INSTALL_DIR/scripts/sqlite-export.sh" \
    "$SCRIPT_DIR/sqlite-export.sh" \
    "$SCRIPT_DIR/../sqlite-export.sh" \
    "$SCRIPT_DIR/../../docs/scripts/sqlite-export.sh"; do
    if [[ -f "$candidate" ]]; then
        SQLITE_EXPORT_SH="$candidate"
        break
    fi
done

# 颜色
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[OK]${NC}   $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()   { echo -e "${RED}[ERR]${NC}  $*" >&2; }
stage() { echo -e "${GREEN}==>${NC} $*"; }

# 子进程 stdout 一律行前缀 [STEP-x] [OK|WARN|ERR],后端按前缀解析阶段
log_step() {
    local step_name="$1"
    echo "[STEP-$step_name]"
}

# ============= 1. 预检 =============
stage "1/8 预检"

if [[ -z "${BACKUP_ENCRYPTION_PASSWORD:-}" ]]; then
    err "BACKUP_ENCRYPTION_PASSWORD 未设置(应在 /etc/myblog/myblog.env)"
    exit 10
fi
if [[ ${#BACKUP_ENCRYPTION_PASSWORD} -lt 8 ]]; then
    warn "BACKUP_ENCRYPTION_PASSWORD 长度 < 8,弱密码(继续)"
fi
if [[ "${DRY_RUN:-0}" != "1" ]]; then
    if [[ -z "${BACKUP_GITHUB_TOKEN:-}" ]]; then
        err "BACKUP_GITHUB_TOKEN 未设置(非 DRY_RUN 模式必须,在 /etc/myblog/myblog.env 配置,需要 my-blog-backup 仓库的 repo 权限)"
        exit 10
    fi
    if [[ -z "${GITHUB_BACKUP_REPO:-}" ]]; then
        err "GITHUB_BACKUP_REPO 未设置(非 DRY_RUN 模式必须,例 owner/my-blog-backup)"
        exit 10
    fi
fi
if [[ -z "$SQLITE_EXPORT_SH" ]]; then
    err "找不到 sqlite-export.sh(已查 $INSTALL_DIR/scripts/、$SCRIPT_DIR/、../、../../docs/scripts/)"
    exit 10
fi
info "INSTALL_DIR      = $INSTALL_DIR"
info "SQLITE_PATH      = $SQLITE_PATH"
info "UPLOAD_DIR       = $UPLOAD_DIR"
info "STAGE_DIR        = $STAGE_DIR"
info "SQLITE_EXPORT_SH = $SQLITE_EXPORT_SH"
info "GITHUB_BACKUP_REPO = ${GITHUB_BACKUP_REPO:-<DRY_RUN>}"

# 校验 db / uploads 存在
if [[ "${SKIP_DB:-0}" != "1" && ! -f "$SQLITE_PATH" ]]; then
    err "SQLITE_PATH 不存在: $SQLITE_PATH"
    exit 10
fi
if [[ "${SKIP_UPLOADS:-0}" != "1" && ! -d "$UPLOAD_DIR" ]]; then
    warn "UPLOAD_DIR 不存在: $UPLOAD_DIR(将自动跳过 uploads 备份)"
    SKIP_UPLOADS=1
fi

# 校验 gh CLI / curl / jq
# gh 优先(不需要 jq),curl 兜底(需要 jq 解析 create-release 的 upload_url)
# v4.2.2：容器没装 jq 导致 curl 分支 422，此处自装 jq
if [[ "${DRY_RUN:-0}" != "1" ]]; then
    if command -v gh >/dev/null 2>&1; then
        USE_GH=1
        info "检测到 gh CLI,使用 gh release 上传"
    elif command -v curl >/dev/null 2>&1; then
        USE_GH=0
        info "未检测到 gh CLI,fallback 到 curl + GitHub REST API"
        # curl 兜底分支额外需要 jq(解析 create release 响应用)
        if ! command -v jq >/dev/null 2>&1; then
            info "jq 未安装,尝试自动安装"
            if command -v apt-get >/dev/null 2>&1; then
                DEBIAN_FRONTEND=noninteractive apt-get update -qq \
                    && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq jq \
                    || { err "apt-get install jq 失败"; exit 17; }
            elif command -v apk >/dev/null 2>&1; then
                apk add --no-cache jq \
                    || { err "apk add jq 失败"; exit 17; }
            elif command -v dnf >/dev/null 2>&1; then
                dnf install -y jq \
                    || { err "dnf install jq 失败"; exit 17; }
            elif command -v yum >/dev/null 2>&1; then
                yum install -y jq \
                    || { err "yum install jq 失败"; exit 17; }
            else
                err "未找到 apt/apk/dnf/yum 任一包管理器,无法自动安装 jq(请手动装 jq 后重跑)"
                exit 17
            fi
            command -v jq >/dev/null 2>&1 || { err "jq 自装后仍不可用"; exit 17; }
            info "jq 已安装: $(jq --version)"
        fi
    else
        err "gh 和 curl 都没装,无法上传"
        exit 10
    fi
fi

# ============= 2. 准备 stage 目录 =============
stage "2/8 准备 stage 目录"
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR"
chmod 700 "$STAGE_DIR"

# trap 任何退出路径都清理明文
cleanup_on_exit() {
    local rc=$?
    if [[ -d "$STAGE_DIR" ]]; then
        # shred 明文中转(encrypted 文件保留供上传,这里只清 -plain 后缀的)
        find "$STAGE_DIR" -maxdepth 1 -type f \( -name "*-plain*" -o -name "*.plain" -o -name "*-unenc*" -o -name "manifest-tmp.json" \) 2>/dev/null \
            | while read -r f; do
                if command -v shred >/dev/null 2>&1; then
                    shred -u -z "$f" 2>/dev/null || rm -f "$f"
                else
                    rm -f "$f"
                fi
            done
        # 明文 stage 目录整个删(加密文件已不在 stage,在 GitHub upload buffer)
        rm -rf "$STAGE_DIR"
    fi
    exit $rc
}
trap cleanup_on_exit EXIT
trap 'cleanup_on_exit 1' INT TERM

TIMESTAMP=$(date +%Y%m%d-%H%M%S)
TAG="backup-$TIMESTAMP"
info "TAG = $TAG"

# v4.2.0：.result.json 写到 STAGE_DIR 外，避免被 trap 清理掉
STAGE_DIR_PARENT="$(dirname "$STAGE_DIR")"
RESULT_RECORD_ID="${BACKUP_RECORD_ID:-$$}"
RESULT_FILE="$STAGE_DIR_PARENT/.blog-backup-result.$RESULT_RECORD_ID.json"
# 防御:如果 STAGE_DIR_PARENT 不可写,fallback 到 /tmp 根(总是可写)
if ! (echo "test" > "$STAGE_DIR_PARENT/.blog-backup-result.test" 2>/dev/null && rm -f "$STAGE_DIR_PARENT/.blog-backup-result.test"); then
    warn "STAGE_DIR_PARENT 不可写 ($STAGE_DIR_PARENT), result fallback 到 /tmp"
    STAGE_DIR_PARENT="/tmp"
    RESULT_FILE="/tmp/.blog-backup-result.$RESULT_RECORD_ID.json"
fi
info "RESULT_FILE = $RESULT_FILE"

# 进度文件(后端用,识别当前阶段)
PROGRESS_FILE="$STAGE_DIR/.progress"
echo "INIT" > "$PROGRESS_FILE"

# ============= 3. 加密 db dump =============
DB_ENC_FILE=""
DB_PLAIN_FILE=""
if [[ "${SKIP_DB:-0}" != "1" ]]; then
    log_step "DB_DUMP"
    echo "DB_DUMP_START" > "$PROGRESS_FILE"
    stage "3/8 加密 db dump"

    # 临时明文(脚本退出时 shred)— 走 sqlite-export 的 tmp 内部机制
    DB_ENC_FILE="$STAGE_DIR/blog-${TIMESTAMP}.sql.gz.enc"
    info "调用 sqlite-export.sh → $DB_ENC_FILE"

    # sqlite-export.sh 默认会从交互式读密码(无法用);我们已经 patch 成 env 模式,
    # 透传 BACKUP_ENCRYPTION_PASSWORD(它优先用此 env,不交互)
    # 注:FORCE_EXPORT=1 让非交互检查放行
    EXTRA_ARGS=()
    if [[ -n "${DB_EXCLUDE_TABLES:-}" ]]; then
        EXTRA_ARGS+=(--exclude "$DB_EXCLUDE_TABLES")
    fi
    if ! FORCE_EXPORT=1 \
         BACKUP_ENCRYPTION_PASSWORD="$BACKUP_ENCRYPTION_PASSWORD" \
         bash "$SQLITE_EXPORT_SH" "$SQLITE_PATH" -o "$DB_ENC_FILE" "${EXTRA_ARGS[@]}" \
            >> "$STAGE_DIR/db-export.log" 2>&1; then
        err "sqlite-export.sh 失败,日志:"
        cat "$STAGE_DIR/db-export.log" >&2
        echo "DB_DUMP_FAILED" > "$PROGRESS_FILE"
        exit 11
    fi
    DB_SIZE=$(stat -c%s "$DB_ENC_FILE" 2>/dev/null || stat -f%z "$DB_ENC_FILE")
    info "db dump 完成,大小: $DB_SIZE bytes"
    echo "DB_DUMP_OK:$DB_SIZE" > "$PROGRESS_FILE"
else
    info "跳过 db 备份(SKIP_DB=1)"
fi

# ============= 4. 加密 uploads =============
UPLOADS_ENC_FILE=""
UPLOADS_SIZE=0
if [[ "${SKIP_UPLOADS:-0}" != "1" ]]; then
    log_step "UPLOADS_PACK"
    echo "UPLOADS_PACK_START" > "$PROGRESS_FILE"
    stage "4/8 加密 uploads 打包"

    UPLOADS_ENC_FILE="$STAGE_DIR/uploads-${TIMESTAMP}.tar.gz.enc"
    info "tar $UPLOAD_DIR → openssl enc → $UPLOADS_ENC_FILE"

    # 流式打包 + 加密:tar 走 stdout → openssl 走 stdin → 写文件
    # 2026-06-20 修 P3: 边备份边有人上传时 tar 会报 "file changed as we read it" 返 exit 1,
    #   set -e + pipefail 判定整条管道失败 → 备份误杀。GNU tar 抑制用 --warning=no-file-changed;
    #   BSD tar (macOS) 没有该选项,需要单独容忍 exit code 1 (file changed 警告是警告不是错误)
    if ! tar -czf - -C "$(dirname "$UPLOAD_DIR")" \
        --warning=no-file-changed \
        "$(basename "$UPLOAD_DIR")" 2>> "$STAGE_DIR/uploads-enc.log" \
            | openssl enc -aes-256-cbc -pbkdf2 -iter 100000 -salt \
                -pass env:BACKUP_ENCRYPTION_PASSWORD \
                -out "$UPLOADS_ENC_FILE" 2>> "$STAGE_DIR/uploads-enc.log"; then
        # 边备份边改文件 → tar exit 1 但产物可用, 看是不是 "file changed" 警告导致的
        if grep -q "file changed" "$STAGE_DIR/uploads-enc.log" 2>/dev/null \
           && [[ -s "$UPLOADS_ENC_FILE" ]]; then
            warn "uploads 打包出现 'file changed' 警告(边备份边有上传), 产物仍可用, 继续"
        else
            err "uploads 打包/加密失败,日志:"
            cat "$STAGE_DIR/uploads-enc.log" >&2
            echo "UPLOADS_PACK_FAILED" > "$PROGRESS_FILE"
            exit 12
        fi
    fi
    UPLOADS_SIZE=$(stat -c%s "$UPLOADS_ENC_FILE" 2>/dev/null || stat -f%z "$UPLOADS_ENC_FILE")
    info "uploads 加密完成,大小: $UPLOADS_SIZE bytes"
    echo "UPLOADS_PACK_OK:$UPLOADS_SIZE" > "$PROGRESS_FILE"

    # >1.5GB 警告(GitHub release 单 asset 2GB 限制)
    if [[ $UPLOADS_SIZE -gt 1610612736 ]]; then
        warn "uploads 加密包 $UPLOADS_SIZE bytes 接近 2GB,GitHub release 2GB 上限"
        warn "考虑 SKIP_UPLOADS=1 仅备份 db,或加 --exclude 拆分"
    fi
else
    info "跳过 uploads 备份(SKIP_UPLOADS=1)"
fi

# ============= 5. 生成 manifest =============
log_step "MANIFEST"
echo "MANIFEST_START" > "$PROGRESS_FILE"
stage "5/8 生成 manifest"

MANIFEST_FILE="$STAGE_DIR/manifest.json"

# 收集 db 表行数
# 2026-06-20 修 P2-5 + P4: 原版只查 sqlite_stat1 估算(快),但未跑过 ANALYZE 时表为空 → 返 "{}"
# 改为：先尝试 sqlite_stat1 估算(快);fallback count(*) 精确版(慢但完整)
# P4 修: 之前写过一个错的 SQLite 子查询("\"${mname}\"" 引用外层 name 永远返 0),
#   if 兜底永远走不到 — 干错子查询,直接用 for 循环
# 业务表都不大(<10万行),count(*) 在秒级,首次备份可接受
DB_TABLE_COUNTS="{}"
DB_TABLE_COUNTS_EXACT="{}"
if [[ -n "$DB_ENC_FILE" && -f "$SQLITE_PATH" ]]; then
    # 1) 估算版（sqlite_stat1, 没跑过 ANALYZE 时为空）
    DB_TABLE_COUNTS=$(sqlite3 "$SQLITE_PATH" "
        SELECT json_group_object(tbl, stat) FROM sqlite_stat1
    " 2>/dev/null || echo "{}")

    # 2) 精确版：for 循环每张业务表 count(*)
    DB_TABLE_COUNTS_EXACT=$(sqlite3 "$SQLITE_PATH" "
        SELECT name FROM sqlite_master
        WHERE type='table' AND name NOT LIKE 'sqlite_%'
        ORDER BY name
    " 2>/dev/null | while IFS= read -r t; do
        [[ -z "$t" ]] && continue
        cnt=$(sqlite3 "$SQLITE_PATH" "SELECT count(*) FROM \"$t\"" 2>/dev/null || echo 0)
        printf '%s\n' "\"$t\":$cnt"
    done | paste -sd ',' - | { echo -n '{'; cat; echo '}'; })
fi

# uploads 文件数和体积
UPLOADS_FILE_COUNT=0
UPLOADS_PLAIN_SIZE=0
if [[ "${SKIP_UPLOADS:-0}" != "1" && -d "$UPLOAD_DIR" ]]; then
    UPLOADS_FILE_COUNT=$(find "$UPLOAD_DIR" -type f 2>/dev/null | wc -l | tr -d ' ')
    # 原始 uploads 大小(用于元数据)
    UPLOADS_PLAIN_SIZE=$(du -sb "$UPLOAD_DIR" 2>/dev/null | cut -f1 || echo 0)
fi

# 总览 manifest
cat > "$MANIFEST_FILE" <<EOF
{
  "tag": "$TAG",
  "backup_at": "$(date -Iseconds 2>/dev/null || date +%Y-%m-%dT%H:%M:%S%z)",
  "host": "$(hostname)",
  "blog_version": "$(grep -oE '<revision>[0-9.]+</revision>' "$INSTALL_DIR/blog-app.jar" 2>/dev/null | head -1 | sed -E 's@</?revision>@@g' || echo 'unknown')",
  "sqlite_path": "$SQLITE_PATH",
  "db": {
    "encrypted_file": "$(basename "${DB_ENC_FILE:-}")",
    "size_bytes": ${DB_SIZE:-0},
    "table_stats_estimated": $DB_TABLE_COUNTS,
    "table_stats_exact": $DB_TABLE_COUNTS_EXACT
  },
  "uploads": {
    "encrypted_file": "$(basename "${UPLOADS_ENC_FILE:-}")",
    "encrypted_size_bytes": ${UPLOADS_SIZE:-0},
    "plain_size_bytes": ${UPLOADS_PLAIN_SIZE},
    "file_count": ${UPLOADS_FILE_COUNT}
  },
  "encryption": {
    "algorithm": "AES-256-CBC",
    "kdf": "PBKDF2",
    "iterations": 100000,
    "salt": "per-file (openssl default)"
  }
}
EOF

if [[ ! -s "$MANIFEST_FILE" ]]; then
    err "manifest.json 生成失败或为空"
    echo "MANIFEST_FAILED" > "$PROGRESS_FILE"
    exit 13
fi
info "manifest 写完: $MANIFEST_FILE"
echo "MANIFEST_OK" > "$PROGRESS_FILE"

# ============= 6. SHA256SUMS =============
log_step "SHA256"
echo "SHA256_START" > "$PROGRESS_FILE"
stage "6/8 计算 SHA256SUMS"

SHA256SUMS_FILE="$STAGE_DIR/SHA256SUMS"
cd "$STAGE_DIR"
shasum -a 256 *.enc manifest.json > "$SHA256SUMS_FILE" 2>/dev/null \
    || sha256sum *.enc manifest.json > "$SHA256SUMS_FILE"
cd - >/dev/null

if [[ ! -s "$SHA256SUMS_FILE" ]]; then
    err "SHA256SUMS 生成失败"
    echo "SHA256_FAILED" > "$PROGRESS_FILE"
    exit 14
fi
info "SHA256SUMS:"
cat "$SHA256SUMS_FILE"

# ============= 7. 上传 GitHub Release =============
ASSET_URLS_FILE="$STAGE_DIR/.asset_urls"
ASSETS=()
[[ -n "$DB_ENC_FILE" && -f "$DB_ENC_FILE" ]] && ASSETS+=("$DB_ENC_FILE")
[[ -n "$UPLOADS_ENC_FILE" && -f "$UPLOADS_ENC_FILE" ]] && ASSETS+=("$UPLOADS_ENC_FILE")
ASSETS+=("$MANIFEST_FILE" "$SHA256SUMS_FILE")

if [[ "${DRY_RUN:-0}" == "1" ]]; then
    log_step "UPLOAD"
    echo "UPLOAD_SKIPPED_DRY_RUN" > "$PROGRESS_FILE"
    stage "7/8 跳过上传(DRY_RUN=1)"
    info "DRY_RUN=1,资产留在 $STAGE_DIR:"
    ls -lh "$STAGE_DIR"
    # 把 stage 目录留下(不触发 cleanup),跳过 trap
    trap - EXIT INT TERM
    echo "DRY_RUN_OK" > "$PROGRESS_FILE"
    echo "STAGE_DIR=$STAGE_DIR"
    echo "TAG=$TAG"
    exit 0
fi

log_step "UPLOAD"
echo "UPLOAD_START" > "$PROGRESS_FILE"
stage "7/8 创建 GitHub Release 并上传 assets"

RELEASE_NOTE="## my-blog data backup

- **Tag**: \`$TAG\`
- **Time**: $(date -Iseconds 2>/dev/null || date +%Y-%m-%dT%H:%M:%S%z)
- **Host**: $(hostname)

### Assets

| File | Type | Size |
|---|---|---|
$(for a in "${ASSETS[@]}"; do
    bn=$(basename "$a")
    sz=$(du -h "$a" | cut -f1)
    if [[ "$bn" == *.enc ]]; then
        echo "| \`$bn\` | AES-256-CBC encrypted | $sz |"
    elif [[ "$bn" == "manifest.json" ]]; then
        echo "| \`$bn\` | Metadata (plain) | $sz |"
    elif [[ "$bn" == "SHA256SUMS" ]]; then
        echo "| \`$bn\` | Checksums (plain) | $sz |"
    fi
done)

### Restore

\`\`\`bash
# 1. 下载所有 asset
gh release download $TAG --repo $GITHUB_BACKUP_REPO --dir ./restore/

# 2. 解密 db(用 sqlite-import.sh)
bash sqlite-import.sh /path/to/new-blog.db ./restore/blog-*.sql.gz.enc

# 3. 解密 uploads
openssl enc -d -aes-256-cbc -pbkdf2 -iter 100000 \\
    -pass env:BACKUP_ENCRYPTION_PASSWORD \\
    -in ./restore/uploads-*.tar.gz.enc | tar xzf - -C /opt/myblog/

# 4. 校验
cd restore && sha256sum -c SHA256SUMS
\`\`\`
"

# 用 gh CLI(优先)— 在一个新 process group 里跑,避免 trap 拦截不到
if [[ "${USE_GH:-1}" == "1" ]]; then
    info "gh release create $TAG --repo $GITHUB_BACKUP_REPO"
    if ! gh release create "$TAG" \
            --repo "$GITHUB_BACKUP_REPO" \
            --title "Data Backup $TIMESTAMP" \
            --notes "$RELEASE_NOTE" \
            --target main \
            "${ASSETS[@]}" \
            >> "$STAGE_DIR/gh.log" 2>&1; then
        err "gh release create 失败,日志:"
        cat "$STAGE_DIR/gh.log" >&2
        echo "UPLOAD_FAILED" > "$PROGRESS_FILE"
        exit 15
    fi
    # 抓 release URL
    if gh release view "$TAG" --repo "$GITHUB_BACKUP_REPO" --json url --jq '.url' > "$ASSET_URLS_FILE" 2>/dev/null; then
        info "Release URL: $(cat "$ASSET_URLS_FILE")"
    else
        warn "gh release view 失败(不影响上传)"
    fi
else
    # curl + GitHub REST API 兜底
    info "curl GitHub API create release"
    CREATE_RESP=$(mktemp)
    HTTP_CODE=$(curl -s -o "$CREATE_RESP" -w "%{http_code}" \
        -X POST "https://api.github.com/repos/$GITHUB_BACKUP_REPO/releases" \
        -H "Authorization: token $BACKUP_GITHUB_TOKEN" \
        -H "Accept: application/vnd.github+json" \
        -d "$(jq -n --arg tag "$TAG" --arg notes "$RELEASE_NOTE" \
            '{tag_name:$tag,name:("Data Backup "+$tag),body:$notes,target_commitish:"main"}')")
    if [[ "$HTTP_CODE" != "201" ]]; then
        err "create release 失败 HTTP $HTTP_CODE,响应:"
        cat "$CREATE_RESP" >&2
        echo "UPLOAD_FAILED" > "$PROGRESS_FILE"
        exit 15
    fi
    UPLOAD_URL=$(jq -r '.upload_url' < "$CREATE_RESP" | sed 's/{?name,label}//')
    REL_HTML_URL=$(jq -r '.html_url' < "$CREATE_RESP")
    echo "$REL_HTML_URL" > "$ASSET_URLS_FILE"

    # 上传每个 asset
    for asset in "${ASSETS[@]}"; do
        bn=$(basename "$asset")
        info "  upload $bn"
        UP_CODE=$(curl -s -o "$STAGE_DIR/upload-$bn.json" -w "%{http_code}" \
            -X POST "${UPLOAD_URL}?name=$bn" \
            -H "Authorization: token $BACKUP_GITHUB_TOKEN" \
            -H "Content-Type: application/octet-stream" \
            --data-binary "@$asset")
        if [[ "$UP_CODE" != "201" ]]; then
            err "upload $bn 失败 HTTP $UP_CODE"
            cat "$STAGE_DIR/upload-$bn.json" >&2
            echo "UPLOAD_FAILED" > "$PROGRESS_FILE"
            exit 16
        fi
    done
fi

info "GitHub Release 创建完成: $TAG"
echo "UPLOAD_OK" > "$PROGRESS_FILE"

# ============= 8. 输出结果(给后端 ProcessBuilder 解析) =============
# 2026-06-20 修 P0-1 残留：直接用开头 line 183 定义好的 RESULT_FILE（在 STAGE_DIR_PARENT），
# **不要**重新赋值回 $STAGE_DIR/.result.json（旧路径会被 trap rm -rf 删掉 → Java 读到不存在）
# 2026-06-20 修 P1-4: 加上 manifest_file 路径, Java 侧读完 result.json 再读 manifest 原文,
#   存到 backup_record.manifest_json 列（之前该列是死代码）
stage "8/8 完成"
cat > "$RESULT_FILE" <<EOF
{
  "tag": "$TAG",
  "release_url": "$(cat "$ASSET_URLS_FILE" 2>/dev/null || echo '')",
  "db_size": ${DB_SIZE:-0},
  "uploads_size": ${UPLOADS_SIZE:-0},
  "manifest_file": "$MANIFEST_FILE",
  "assets": [
$(for a in "${ASSETS[@]}"; do
    bn=$(basename "$a")
    sz=$(stat -c%s "$a" 2>/dev/null || stat -f%z "$a")
    echo "    {\"name\": \"$bn\", \"size\": $sz},"
done | sed '$ s/,$//')
  ]
}
EOF
cat "$RESULT_FILE"
info "备份完成,tag=$TAG"
# 正常退出,trap 清理 stage
exit 0
