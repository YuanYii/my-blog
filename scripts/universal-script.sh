#!/bin/bash
# universal-script.sh — 通用数据刷数脚本
# 用法: bash universal-script.sh <task> [db-path]
# 任务: slug-migrate（中文 slug → SHA-256 hex）
# 默认 db: /opt/myblog/db/blog.db
# 安全: 先备份再改；DRY_RUN=1 预览模式

set -euo pipefail

TASK="${1:-}"
DB="${2:-/opt/myblog/db/blog.db}"
DRY_RUN="${DRY_RUN:-0}"

usage() {
  echo "用法: bash universal-script.sh <task> [db-path]"
  echo ""
  echo "可用任务:"
  echo "  slug-migrate   将含中文的 article slug 替换为 SHA-256(title) 前 16 位 hex"
  echo ""
  echo "示例:"
  echo "  DRY_RUN=1 bash universal-script.sh slug-migrate"
  echo "  bash universal-script.sh slug-migrate /path/to/blog.db"
  exit 1
}

if [ -z "$TASK" ]; then
  usage
fi

if [ ! -f "$DB" ]; then
  echo "❌ 数据库不存在: $DB"
  exit 1
fi

# 备份
BACKUP="${DB}.bak.$(date +%Y%m%d%H%M%S)"
cp "$DB" "$BACKUP"
echo "✅ 已备份: $BACKUP"

# ============ 任务: slug-migrate ============
task_slug_migrate() {
  RECORDS=$(sqlite3 "$DB" "SELECT id, slug, title FROM article WHERE slug GLOB '*[一-龥]*' AND deleted = 0;")

  if [ -z "$RECORDS" ]; then
    echo "✅ 无中文 slug，无需迁移"
    return 0
  fi

  COUNT=0

  while IFS='|' read -r ID OLD_SLUG TITLE; do
    NEW_SLUG=$(echo -n "$TITLE" | openssl dgst -sha256 | awk '{print $NF}' | cut -c1-16)

    N=2
    CANDIDATE="$NEW_SLUG"
    while sqlite3 "$DB" "SELECT COUNT(*) FROM article WHERE slug = '$CANDIDATE';" | grep -q '^[1-9]'; do
      CANDIDATE="${NEW_SLUG}-${N}"
      N=$((N + 1))
      if [ $N -gt 100 ]; then
        CANDIDATE="${NEW_SLUG}-$(date +%s)"
        break
      fi
    done

    if [ "$DRY_RUN" = "1" ]; then
      echo "[DRY-RUN] id=$ID: $OLD_SLUG → $CANDIDATE"
    else
      sqlite3 "$DB" "UPDATE article SET slug = '$CANDIDATE', updated_at = datetime('now') WHERE id = $ID;"
      echo "[MIGRATED] id=$ID: $OLD_SLUG → $CANDIDATE"
    fi
    COUNT=$((COUNT + 1))
  done <<< "$RECORDS"

  echo ""
  echo "✅ slug-migrate 完成: 共 $COUNT 条"
}

# ============ 任务分发 ============
case "$TASK" in
  slug-migrate)
    task_slug_migrate
    ;;
  *)
    echo "❌ 未知任务: $TASK"
    usage
    ;;
esac

echo "   数据库: $DB"
echo "   备份:   $BACKUP"
