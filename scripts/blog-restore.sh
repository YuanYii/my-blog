#!/bin/bash
# ============================================================
# my-blog 数据恢复脚本（解密 + 导入 + 启服校验）
# ============================================================
# 从 GitHub Release 拉加密 dump → 解密 → 停 myblog → 导入 db
# → (可选)恢复 uploads → 启服 → 健康检查 + 数据校验 → 写 .result.json
#
# 关键设计依据: docs/设计文档/博客数据恢复方案设计.md
# 调用方式: 由 RestoreService 通过 systemd-run 启动（独立 cgroup，不被 systemctl stop 杀到）
#   手动调试: export 全部 env 后 bash 跑
# 算法: 与 sqlite-export.sh 对称 (AES-256-CBC + PBKDF2 10 + salt)
#       通过 sqlite-import.sh 解密导入, 不重新实现加密层
#
# 退出码:
#   0   成功
#   20  预检失败 (env 缺失 / sudoers 没配 / 关键文件不存在)
#   21  gh release download / curl 拉 release 失败
#   22  SHA256SUMS 校验失败
#   23  解密失败 (openssl 报错)
#   24  sqlite-import.sh 导入失败
#   25  chown / systemctl start 失败
#   26  健康检查失败 (新 JVM 起来后 curl 不通)
#   27  uploads 解压失败 (scope=DB_UPLOADS 时)
#   28  数据完整性校验失败 (manifest 行数 vs 实际差异超阈值)
# ============================================================

set -euo pipefail

# ============= 0. 环境检测 + 默认值 =============
IN_DOCKER=false
[[ -f /.dockerenv ]] && IN_DOCKER=true
RESTORE_HEALTH_URL="${RESTORE_HEALTH_URL:-http://localhost:8080/api/v1/health}"
RESTORE_KEEP_BAKS="${RESTORE_KEEP_BAKS:-5}"
RESTORE_RESULT_DIR="${RESTORE_RESULT_DIR:-/var/lib/myblog/restore-results}"
RESTORE_STAGE_DIR="${RESTORE_STAGE_DIR:-/tmp/blog-restore-stage}"

# 颜色
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[OK]${NC}   $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()   { echo -e "${RED}[ERR]${NC}  $*" >&2; }
stage() { echo -e "${GREEN}==>${NC} $*"; }

# 子进程 stdout 加前缀, Java 侧按前缀解析阶段
log_step() {
    local step_name="$1"
    echo "[STEP-$step_name]"
}

# ============= 写 result 函数 =============
# v4.3.1: 用 jq 生成 JSON，避免 verify_diff 含特殊字符时破坏格式
write_result() {
    local status="$1"
    local stage="$2"
    local message="$3"
    local finished_at
    finished_at="$(date -Iseconds 2>/dev/null || date +%Y-%m-%dT%H:%M:%S%z)"

    # 截断 message 到 500 字符
    local msg_truncated="${message:0:500}"

    # jq -n: 用 --arg 安全传参, 自动 JSON-encode (转义 " / \ / 换行 等)
    jq -n \
        --arg status "$status" \
        --arg stage "$stage" \
        --arg message "$msg_truncated" \
        --arg tag "$RESTORE_SOURCE_TAG" \
        --arg scope "$RESTORE_SCOPE" \
        --arg finished_at "$finished_at" \
        '{status: $status, stage: $stage, message: $message, tag: $tag, scope: $scope, finished_at: $finished_at}' \
        > "$RESULT_FILE"
    chmod 600 "$RESULT_FILE"
}

# 成功 + verify_diff 时调用 (verify_diff 是 diff 输出, 多行, 必须用 jq)
write_result_with_verify_diff() {
    local verify_diff="$1"
    local verify_truncated="${verify_diff:0:500}"
    local finished_at
    finished_at="$(date -Iseconds 2>/dev/null || date +%Y-%m-%dT%H:%M:%S%z)"

    jq -n \
        --arg status "SUCCESS" \
        --arg stage "" \
        --arg verify_diff "$verify_truncated" \
        --arg tag "$RESTORE_SOURCE_TAG" \
        --arg scope "$RESTORE_SCOPE" \
        --arg finished_at "$finished_at" \
        '{status: $status, stage: $stage, message: $verify_diff, tag: $tag, scope: $scope, verify_diff: $verify_diff, finished_at: $finished_at}' \
        > "$RESULT_FILE"
    chmod 600 "$RESULT_FILE"
}

# ============= 1. 预检 =============
stage "1/13 预检"

[[ -n "${BACKUP_ENCRYPTION_PASSWORD:-}" ]] \
    || { err "BACKUP_ENCRYPTION_PASSWORD 未配置"; exit 20; }
[[ -n "${BACKUP_GITHUB_TOKEN:-}" ]] \
    || { err "BACKUP_GITHUB_TOKEN 未配置"; exit 20; }
[[ -n "${GITHUB_BACKUP_REPO:-}" ]] \
    || { err "GITHUB_BACKUP_REPO 未配置"; exit 20; }
[[ -n "${RESTORE_SOURCE_TAG:-}" ]] \
    || { err "RESTORE_SOURCE_TAG 未配置"; exit 20; }
[[ -n "${RESTORE_RECORD_ID:-}" ]] \
    || { err "RESTORE_RECORD_ID 未配置"; exit 20; }
[[ -f "${SQLITE_PATH:-}" ]] \
    || { err "SQLITE_PATH 不存在: ${SQLITE_PATH:-<空>}, 请检查 @Value 注入"; exit 20; }
[[ -n "${RESTORE_SCOPE:-}" ]] \
    || { err "RESTORE_SCOPE 未配置"; exit 20; }

if $IN_DOCKER; then
    info "Docker 环境, 跳过 sudoers/systemd 自检"
else
    # v5 自检: 用白名单内的 systemctl is-active 验证 sudoers 配通
    sudo -n systemctl is-active myblog >/dev/null 2>&1
    SUDO_RC=$?
    if [[ $SUDO_RC -eq 1 ]]; then
        err "sudoers 未配置 systemctl, 见设计文档 §12.1"
        exit 20
    fi
    info "sudoers 验证通过 (is-active exit=$SUDO_RC, 0=active / 3=inactive / 1=rejected)"
fi

# sqlite-import.sh 必须在标准位置
SQLITE_IMPORT_SH=""
for candidate in \
    "$INSTALL_DIR/scripts/sqlite-import.sh" \
    "/opt/myblog/scripts/sqlite-import.sh"; do
    if [[ -f "$candidate" ]]; then
        SQLITE_IMPORT_SH="$candidate"
        break
    fi
done
[[ -n "$SQLITE_IMPORT_SH" ]] \
    || { err "找不到 sqlite-import.sh (查了 \$INSTALL_DIR/scripts/ 和 /opt/myblog/scripts/)"; exit 20; }

# tar / sha256sum / openssl / curl 必须有
command -v sha256sum >/dev/null 2>&1 || { err "sha256sum 未装"; exit 20; }
command -v openssl >/dev/null 2>&1 || { err "openssl 未装"; exit 20; }
command -v tar >/dev/null 2>&1 || { err "tar 未装"; exit 20; }
command -v jq >/dev/null 2>&1 || { err "jq 未装, sudo apt install jq"; exit 20; }
command -v curl >/dev/null 2>&1 || { err "curl 未装"; exit 20; }

info "INSTALL_DIR      = ${INSTALL_DIR:-<未设>}"
info "SQLITE_PATH      = $SQLITE_PATH"
info "UPLOAD_DIR       = ${UPLOAD_DIR:-<未设>}"
info "STAGE_DIR        = $RESTORE_STAGE_DIR"
info "RESULT_DIR       = $RESTORE_RESULT_DIR"
info "SOURCE_TAG       = $RESTORE_SOURCE_TAG"
info "SCOPE            = $RESTORE_SCOPE"
info "SQLITE_IMPORT_SH = $SQLITE_IMPORT_SH"
info "HEALTH_URL       = $RESTORE_HEALTH_URL"

# ============= 2. 准备 stage / result 目录 =============
stage "2/13 准备 stage / result 目录"

mkdir -p "$RESTORE_RESULT_DIR"
chmod 700 "$RESTORE_RESULT_DIR"
mkdir -p "$RESTORE_STAGE_DIR"

RESULT_FILE="$RESTORE_RESULT_DIR/.blog-restore-result.$RESTORE_RECORD_ID.json"

# 清理上次残留的 result.json（回填后不再删除，恢复前主动清理）
if [[ -f "$RESULT_FILE" ]]; then
    rm -f "$RESULT_FILE"
    info "已清理旧 result.json: $RESULT_FILE"
fi

# trap: 任何路径退出都清理 stage + 兜底写 result (修复中-1: set -e 下失败要写 FAILED)
cleanup_on_exit() {
    local rc=$?
    if [[ ! -f "$RESULT_FILE" ]]; then
        # 还没写过 result, 兜底补一个 FAILED (v5 修复: trap 也要写 result)
        write_result FAILED "TRAP" "脚本异常退出 (exit code $rc)"
    fi
    if [[ -d "$RESTORE_STAGE_DIR" ]]; then
        # shred 明文中转 (uploads *.sql 等)
        find "$RESTORE_STAGE_DIR" -type f 2>/dev/null | while read -r f; do
            command -v shred >/dev/null 2>&1 && shred -u -z "$f" 2>/dev/null || rm -f "$f"
        done
        rm -rf "$RESTORE_STAGE_DIR"
    fi
    exit $rc
}
trap cleanup_on_exit EXIT
# INT/TERM 不传参，cleanup_on_exit 用 $? 取退出码
trap cleanup_on_exit INT TERM

# ============= 3. 清理旧 .bak + 备份当前 db =============
log_step "BACKUP_CURRENT"
stage "3/13 备份当前 db (恢复前快照)"

# 保留最近 N 份 (v2 中-7 修复: 避免只增不减撑爆磁盘)
if compgen -G "${SQLITE_PATH}.bak.*" > /dev/null; then
    ls -1t "${SQLITE_PATH}.bak."* | tail -n +$((RESTORE_KEEP_BAKS + 1)) | xargs -r rm -f
    info "保留最近 $RESTORE_KEEP_BAKS 份 .bak, 已清理旧的"
fi

if [[ -f "$SQLITE_PATH" ]]; then
    BAK_FILE="${SQLITE_PATH}.bak.$(date +%Y%m%d-%H%M%S)"
    # v4.3.2: 用 sqlite3 .backup 而不是 cp
    # SQLite WAL 模式下 cp 只复制主文件, -wal / -shm 里未 checkpoint 的数据丢失
    # .backup 是 online safe backup: 包含全部一致状态 (含已 WAL 但未 checkpoint 的数据)
    if ! sqlite3 "$SQLITE_PATH" ".backup '$BAK_FILE'" \
            > "$RESTORE_STAGE_DIR/backup-current.log" 2>&1; then
        err ".backup 失败, 日志:"
        cat "$RESTORE_STAGE_DIR/backup-current.log" >&2
        write_result FAILED START ".backup 当前 db 失败"
        exit 25
    fi
    info "当前 db 已 (WAL-safe) 备份: $BAK_FILE"
fi

# ============= 4. 停服 (脚本在独立 cgroup, 杀不到) =============
log_step "STOP"
stage "4/13 停 myblog 服务"

if $IN_DOCKER; then
    info "Docker 环境, 跳过 systemctl stop (容器内无 systemd 服务管理)"
else
    if sudo -n systemctl is-active --quiet myblog 2>/dev/null; then
        sudo -n systemctl stop myblog \
            || { err "systemctl stop myblog 失败"; write_result FAILED STOP "systemctl stop 失败"; exit 25; }
        info "myblog 已停止"
    else
        info "myblog 已是 inactive 状态, 跳过 stop"
    fi
fi

# ============= 5. 拉 release assets (gh 优先, curl fallback) =============
log_step "DOWNLOAD"
stage "5/13 拉 GitHub Release assets"

cd "$RESTORE_STAGE_DIR"

if command -v gh >/dev/null 2>&1; then
    USE_GH=1
    info "使用 gh CLI"
else
    USE_GH=0
    info "gh CLI 未装, fallback 到 curl + REST API"
fi

if [[ "$USE_GH" == "1" ]]; then
    if ! GH_TOKEN="$BACKUP_GITHUB_TOKEN" gh release download "$RESTORE_SOURCE_TAG" \
            --repo "$GITHUB_BACKUP_REPO" \
            --dir "$RESTORE_STAGE_DIR" \
            --pattern "*.enc" --pattern "manifest.json" --pattern "SHA256SUMS" \
            2>"$RESTORE_STAGE_DIR/gh.log"; then
        err "gh release download 失败, 日志:"
        cat "$RESTORE_STAGE_DIR/gh.log" >&2
        write_result FAILED DOWNLOAD "gh release download 失败"
        exit 21
    fi
else
    # curl 兜底: 拉 release 元信息 → 解析 asset URL → 逐个下载
    # 用 process substitution 避免 while 在子 shell 运行
    RELEASE_JSON=$(mktemp)
    if ! curl -fsS \
            -H "Authorization: token $BACKUP_GITHUB_TOKEN" \
            "https://api.github.com/repos/$GITHUB_BACKUP_REPO/releases/tags/$RESTORE_SOURCE_TAG" \
            -o "$RELEASE_JSON"; then
        err "curl 拉 release 元信息失败"
        write_result FAILED DOWNLOAD "curl 拉 release 元信息失败"
        exit 21
    fi
    # 解析每个 asset (.enc / manifest.json / SHA256SUMS) 并下载
    while read -r name url; do
        [[ -z "$name" || -z "$url" ]] && continue
        if ! curl -fsSL -H "Authorization: token $BACKUP_GITHUB_TOKEN" -H "Accept: application/octet-stream" "$url" -o "$name"; then
            err "curl 下载 $name 失败"
            write_result FAILED DOWNLOAD "curl 下载 $name 失败"
            exit 21
        fi
        info "下载 $name 完成"
    done < <(jq -r '
        .assets[]? | select(
            (.name | endswith(".enc")) or .name == "manifest.json" or .name == "SHA256SUMS"
        ) | "\(.name) \(.url)"
    ' "$RELEASE_JSON")
    rm -f "$RELEASE_JSON"
fi

ls -lh "$RESTORE_STAGE_DIR"

# ============= 6. SHA256 校验 =============
log_step "SHA256"
stage "6/13 SHA256SUMS 校验"

if [[ ! -f "$RESTORE_STAGE_DIR/SHA256SUMS" ]]; then
    err "SHA256SUMS 文件不存在 (下载失败?)"
    write_result FAILED SHA256 "SHA256SUMS 文件缺失"
    exit 22
fi

if ! sha256sum -c SHA256SUMS > "$RESTORE_STAGE_DIR/sha256.log" 2>&1; then
    err "SHA256SUMS 校验失败:"
    cat "$RESTORE_STAGE_DIR/sha256.log" >&2
    write_result FAILED SHA256 "SHA256 校验失败"
    exit 22
fi
info "SHA256SUMS 校验通过"

# ============= 7. 读 manifest 拿 db 文件名 + 校验基线 =============
log_step "MANIFEST"
stage "7/13 读 manifest.json"

if [[ ! -f "$RESTORE_STAGE_DIR/manifest.json" ]]; then
    err "manifest.json 不存在 (下载失败?)"
    write_result FAILED SHA256 "manifest.json 缺失"
    exit 22
fi

DB_FILE=$(jq -r '.db.encrypted_file // empty' "$RESTORE_STAGE_DIR/manifest.json")
if [[ -z "$DB_FILE" ]]; then
    err "manifest.json 缺 .db.encrypted_file 字段"
    write_result FAILED SHA256 "manifest.json 格式错误"
    exit 22
fi
info "db encrypted file: $DB_FILE"

EXPECTED_TABLE_COUNTS=$(jq -r '.db.table_stats_exact // {}' "$RESTORE_STAGE_DIR/manifest.json")
info "manifest 解析完成"

# ============= 8. 调 sqlite-import.sh 恢复 db =============
log_step "IMPORT"
stage "8/13 调用 sqlite-import.sh 恢复 db"

# 脚本以 myblog 身份跑 (scope --uid=myblog --gid=myblog), 直接调不需要 sudo
if ! DB_DECRYPT_PASSWORD="$BACKUP_ENCRYPTION_PASSWORD" \
     FORCE_IMPORT=1 \
     bash "$SQLITE_IMPORT_SH" "$SQLITE_PATH" "$RESTORE_STAGE_DIR/$DB_FILE" \
     > "$RESTORE_STAGE_DIR/import.log" 2>&1; then
    err "sqlite-import.sh 失败, 日志:"
    cat "$RESTORE_STAGE_DIR/import.log" >&2
    write_result FAILED IMPORT "sqlite-import.sh 失败"
    exit 24
fi
info "db 恢复完成"

# ============= 9. (可选) 恢复 uploads =============
log_step "UPLOADS"
if [[ "$RESTORE_SCOPE" == "DB_UPLOADS" ]]; then
    stage "9/13 恢复 uploads"

    UPLOADS_FILE=$(jq -r '.uploads.encrypted_file // empty' "$RESTORE_STAGE_DIR/manifest.json")
    if [[ -z "$UPLOADS_FILE" ]]; then
        err "scope=DB_UPLOADS 但 manifest.json 缺 .uploads.encrypted_file 字段"
        write_result FAILED UPLOADS "manifest.json 缺 uploads 字段"
        exit 27
    fi

    # 脚本以 myblog 身份跑, tar 解到属主是 myblog 的目录不需要 sudo
    if ! BACKUP_ENCRYPTION_PASSWORD="$BACKUP_ENCRYPTION_PASSWORD" \
         openssl enc -d -aes-256-cbc -pbkdf2 -iter 10 \
            -pass env:BACKUP_ENCRYPTION_PASSWORD \
            -in "$RESTORE_STAGE_DIR/$UPLOADS_FILE" \
         | tar xzf - -C "${INSTALL_DIR:-/opt/myblog}" \
            > "$RESTORE_STAGE_DIR/uploads.log" 2>&1; then
        err "uploads 解压失败, 日志:"
        cat "$RESTORE_STAGE_DIR/uploads.log" >&2
        write_result FAILED UPLOADS "uploads 解压失败"
        exit 27
    fi
    info "uploads 恢复完成"
else
    info "scope=$RESTORE_SCOPE, 跳过 uploads"
fi

# ============= 10. chown (兜底, 防旧文件属主不对) =============
log_step "CHOWN"
stage "10/13 chown -R myblog:myblog"

UPLOAD_PATH="${UPLOAD_DIR:-/opt/myblog/uploads}"
if $IN_DOCKER; then
    # Docker 内以 root 运行, 直接 chown 无需 sudo
    chown -R myblog:myblog "$SQLITE_PATH" 2>/dev/null || true
    [[ -d "$UPLOAD_PATH" ]] && chown -R myblog:myblog "$UPLOAD_PATH" 2>/dev/null || true
    info "chown 完成 (Docker, 直接 chown)"
else
    # 宿主机: 必须拆成两条独立 sudo 命令 (sudoers 精确匹配)
    if ! sudo -n chown -R myblog:myblog "$SQLITE_PATH" \
            > "$RESTORE_STAGE_DIR/chown-db.log" 2>&1; then
        err "chown $SQLITE_PATH 失败, 日志:"
        cat "$RESTORE_STAGE_DIR/chown-db.log" >&2
        write_result FAILED START "chown $SQLITE_PATH 失败"
        exit 25
    fi
    if [[ -d "$UPLOAD_PATH" ]]; then
        if ! sudo -n chown -R myblog:myblog "$UPLOAD_PATH" \
                > "$RESTORE_STAGE_DIR/chown-uploads.log" 2>&1; then
            err "chown $UPLOAD_PATH 失败, 日志:"
            cat "$RESTORE_STAGE_DIR/chown-uploads.log" >&2
            write_result FAILED START "chown $UPLOAD_PATH 失败"
            exit 25
        fi
    else
        warn "UPLOAD_DIR ($UPLOAD_PATH) 不存在, 跳过 chown uploads"
    fi
    info "chown 完成"
fi

# ============= 11. 启服 =============
log_step "START"
stage "11/13 systemctl start myblog"

if $IN_DOCKER; then
    info "Docker 环境, 跳过 systemctl start (Java 进程由容器 entrypoint 管理)"
else
    if ! sudo -n systemctl start myblog \
            > "$RESTORE_STAGE_DIR/start.log" 2>&1; then
        err "systemctl start myblog 失败, 日志:"
        cat "$RESTORE_STAGE_DIR/start.log" >&2
        write_result FAILED START "systemctl start 失败"
        exit 25
    fi
    info "myblog 已启动"
fi

# ============= 12. 健康检查 + 数据完整性校验 =============
log_step "HEALTH"
stage "12/13 健康检查 + 数据校验"

# 健康检查 (30s 等待新 JVM 起来, URL 从 env 注入避免端口硬编码)
HEALTH_OK=0
for i in {1..30}; do
    if curl -fsS --max-time 3 "$RESTORE_HEALTH_URL" >/dev/null 2>&1; then
        HEALTH_OK=1
        break
    fi
    sleep 1
done

if [[ $HEALTH_OK -ne 1 ]]; then
    err "健康检查失败 (30s 超时, url=$RESTORE_HEALTH_URL)"
    write_result FAILED HEALTH "健康检查 30s 超时 ($RESTORE_HEALTH_URL)"
    exit 26
fi
info "健康检查通过"

# 数据完整性校验 (对比 manifest.table_stats_exact vs 实际行数)
# 差异仅记录不阻断 (写 verify_diff 进 result.json 供前端展示)
# 空表时 awk 输出合法 JSON {}，避免 paste 产无效 `\n`
log_step "VERIFY"
ACTUAL_COUNTS=$(sqlite3 "$SQLITE_PATH" "
    SELECT name FROM sqlite_master
    WHERE type='table' AND name NOT LIKE 'sqlite_%'
    ORDER BY name
" 2>/dev/null | while IFS= read -r t; do
    [[ -z "$t" ]] && continue
    cnt=$(sqlite3 "$SQLITE_PATH" "SELECT count(*) FROM \"$t\"" 2>/dev/null || echo 0)
    printf '%s\n' "\"$t\":$cnt"
done | paste -sd ',' - | awk 'BEGIN{ORS=""; print "{"} {print} END{print "}\n"}')

# 防御: 如果 awk 输出仍不是合法 JSON (极端边界), fallback 到 {}
if ! echo "$ACTUAL_COUNTS" | jq -e . >/dev/null 2>&1; then
    warn "ACTUAL_COUNTS 不是合法 JSON, fallback 到 {}: $ACTUAL_COUNTS"
    ACTUAL_COUNTS="{}"
fi

DIFF=""
if command -v jq >/dev/null 2>&1; then
    DIFF=$(diff <(echo "$EXPECTED_TABLE_COUNTS" | jq -S .) <(echo "$ACTUAL_COUNTS" | jq -S .) || true)
fi

VERIFY_DIFF=""
if [[ -n "$DIFF" ]]; then
    warn "数据校验有差异 (仅记录不阻断):"
    echo "$DIFF" >&2
    # 截断到 500 字符进 result
    VERIFY_DIFF="${DIFF:0:500}"
fi

# ============= 13. 写 .result.json (SUCCESS) =============
log_step "DONE"
stage "13/13 完成"

# v4.3.1: verify_diff 含换行/引号, 必须用 jq (write_result_with_verify_diff) 而非 here-doc
if [[ -n "$VERIFY_DIFF" ]]; then
    write_result_with_verify_diff "$VERIFY_DIFF"
    warn "数据校验有差异, 已写入 verify_diff"
else
    write_result SUCCESS "" ""
    info "数据校验完全通过 (行数一致)"
fi

info "恢复成功, tag=$RESTORE_SOURCE_TAG scope=$RESTORE_SCOPE"
# 正常退出, trap 清 stage
exit 0