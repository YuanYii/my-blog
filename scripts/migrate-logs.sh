#!/bin/bash
# =============================================================
# 历史日志迁移脚本
# 将 /opt/myblog/logs/ 根目录下两天前的日志文件移动到 archive/YYYY-MM/ 子目录
#
# 背景：v4.0.0 ~ v4.3.0 期间 Logback 归档文件直接放在根目录，
#       v4.3.0+ 改为 archive/YYYY-MM/ 子目录，但旧文件不会自动移动
#
# 用法：
#   sudo bash scripts/migrate-logs.sh              # 执行迁移
#   sudo bash scripts/migrate-logs.sh --dry-run    # 只预览不执行
#
# 适用文件：
#   - blog-YYYY-MM-DD.N.log / blog-YYYY-MM-DD.N.log.gz
#   - blog-warn-YYYY-MM-DD.N.log / blog-warn-YYYY-MM-DD.N.log.gz
#   - app.log.1 / app.log.2.gz / app-error.log.1 等
# =============================================================

set -euo pipefail

LOG_DIR="${LOG_DIR:-/opt/myblog/logs}"
ARCHIVE_DIR="${LOG_DIR}/archive"
DRY_RUN=0
MOVED=0
SKIPPED=0
ERRORS=0

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
error() { echo -e "${RED}[ERROR]${NC} $*"; }

usage() {
    echo "用法: $0 [--dry-run]"
    echo "  --dry-run    只预览，不实际移动文件"
    exit 1
}

# 解析参数
while [[ $# -gt 0 ]]; do
    case $1 in
        --dry-run)
            DRY_RUN=1
            shift
            ;;
        *)
            usage
            ;;
    esac
done

# 检查目录
if [[ ! -d "$LOG_DIR" ]]; then
    error "日志目录不存在: $LOG_DIR"
    exit 1
fi

if [[ ! -d "$ARCHIVE_DIR" ]]; then
    warn "archive 目录不存在，创建: $ARCHIVE_DIR"
    if [[ $DRY_RUN -eq 0 ]]; then
        mkdir -p "$ARCHIVE_DIR"
    fi
fi

info "开始迁移 $LOG_DIR 下两天前的日志文件..."
[[ $DRY_RUN -eq 1 ]] && warn "=== DRY RUN 模式，只预览不执行 ==="
echo ""

# 计算两天前的日期（用于比较文件日期）
TWO_DAYS_AGO=$(date -d "2 days ago" +%Y-%m-%d 2>/dev/null || date -v-2d +%Y-%m-%d 2>/dev/null)

# 函数：从文件名解析日期
parse_date_from_filename() {
    local filename="$1"
    # 匹配 blog-YYYY-MM-DD 或 blog-warn-YYYY-MM-DD
    if [[ $filename =~ blog(-warn)?-([0-9]{4}-[0-9]{2}-[0-9]{2}) ]]; then
        echo "${BASH_REMATCH[2]}"
    # 匹配 app.log.YYYY-MM-DD (logrotate 格式)
    elif [[ $filename =~ app(-error)?\.log\.([0-9]{4}-[0-9]{2}-[0-9]{2}) ]]; then
        echo "${BASH_REMATCH[2]}"
    # 匹配 app.log.N (无日期，用文件修改时间)
    elif [[ $filename =~ \.(log\.[0-9]+)(\.gz)?$ ]]; then
        # 无法从文件名解析日期，返回空
        echo ""
    else
        echo ""
    fi
}

# 函数：获取文件修改日期
get_file_date() {
    local file="$1"
    if [[ "$(uname)" == "Darwin" ]]; then
        stat -f "%Sm" -t "%Y-%m-%d" "$file" 2>/dev/null
    else
        stat -c "%y" "$file" 2>/dev/null | cut -d' ' -f1
    fi
}

# 函数：判断日期是否早于两天前
is_older_than_two_days() {
    local file_date="$1"
    if [[ -z "$file_date" ]]; then
        return 1  # 无法判断，不移动
    fi
    [[ "$file_date" < "$TWO_DAYS_AGO" ]]
}

# 处理根目录下的日志文件
process_file() {
    local file="$1"
    local filename=$(basename "$file")
    
    # 跳过活跃日志文件
    case "$filename" in
        blog.log|blog-warn.log|app.log|app-error.log)
            SKIPPED=$((SKIPPED + 1))
            return
            ;;
    esac
    
    # 跳过已经是 archive 子目录下的文件
    if [[ "$file" == *"/archive/"* ]]; then
        SKIPPED=$((SKIPPED + 1))
        return
    fi
    
    # 尝试从文件名解析日期
    local file_date=$(parse_date_from_filename "$filename")
    
    # 如果文件名无法解析日期，使用文件修改时间
    if [[ -z "$file_date" ]]; then
        file_date=$(get_file_date "$file")
    fi
    
    # 判断是否早于两天前
    if ! is_older_than_two_days "$file_date"; then
        SKIPPED=$((SKIPPED + 1))
        return
    fi
    
    # 解析目标目录（YYYY-MM 格式）
    local year_month=$(echo "$file_date" | cut -d'-' -f1-2)
    local target_dir="${ARCHIVE_DIR}/${year_month}"
    local target_file="${target_dir}/${filename}"
    
    if [[ $DRY_RUN -eq 1 ]]; then
        info "[预览] $filename -> archive/${year_month}/"
        MOVED=$((MOVED + 1))
    else
        # 创建目标目录
        mkdir -p "$target_dir"
        
        # 移动文件
        if mv "$file" "$target_file" 2>/dev/null; then
            info "[已移动] $filename -> archive/${year_month}/"
            MOVED=$((MOVED + 1))
        else
            error "[失败] 无法移动: $filename"
            ERRORS=$((ERRORS + 1))
        fi
    fi
}

# 遍历根目录下的所有文件
for file in "$LOG_DIR"/*; do
    [[ -f "$file" ]] && process_file "$file"
done

# 处理 archive 根目录下可能存在的文件（v4.3.0 后的遗留）
for file in "$ARCHIVE_DIR"/*; do
    [[ -f "$file" ]] && process_file "$file"
done

echo ""
echo "=========================================="
info "迁移完成统计："
echo "  已移动: $MOVED"
echo "  已跳过: $SKIPPED"
echo "  失败:   $ERRORS"
echo "=========================================="

if [[ $ERRORS -gt 0 ]]; then
    exit 1
fi
