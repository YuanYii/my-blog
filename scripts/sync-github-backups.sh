#!/usr/bin/env bash
# sync-github-backups.sh — 从 GitHub 备份仓库拉取 Release 记录，同步到本地 Docker 数据库
#
# 用法：
#   bash scripts/sync-github-backups.sh                          # 默认 myblog-sim 容器
#   bash scripts/sync-github-backups.sh myblog-sim               # 指定容器名
#   CONTAINER=myblog BACKUP_REPO=owner/repo bash scripts/sync-github-backups.sh
#
# 前提：
#   - 容器内 /opt/myblog/myblog.env 包含 GITHUB_BACKUP_REPO 和 BACKUP_GITHUB_TOKEN
#   - 容器内有 sqlite3 命令
#
# 行为：
#   - 只插入 tag 不存在的记录（幂等，可重复执行）
#   - 跳过 test-* 等非 backup-* tag
#   - 日期格式：2026-06-24T14:18:47.000（SQLite DATETIME 兼容）

set -euo pipefail

CONTAINER="${1:-${CONTAINER:-myblog-sim}}"
DB_PATH="/opt/myblog/db/blog.db"
ENV_FILE="/opt/myblog/myblog.env"

# ---------- 1. 从容器 env 读取 GitHub 配置 ----------
read_env() {
  docker exec "$CONTAINER" sh -c "grep '^${1}=' '$ENV_FILE' 2>/dev/null | cut -d'=' -f2-" | tr -d '\r\n'
}

BACKUP_REPO="${BACKUP_REPO:-$(read_env GITHUB_BACKUP_REPO)}"
BACKUP_TOKEN="${BACKUP_TOKEN:-$(read_env BACKUP_GITHUB_TOKEN)}"

if [[ -z "$BACKUP_REPO" || -z "$BACKUP_TOKEN" ]]; then
  echo "ERROR: GITHUB_BACKUP_REPO 或 BACKUP_GITHUB_TOKEN 未配置（检查 $ENV_FILE）"
  exit 1
fi

echo "Container:  $CONTAINER"
echo "Repo:       $BACKUP_REPO"
echo "DB:         $DB_PATH"

# ---------- 2. 拉取 GitHub Release 列表 ----------
RELEASES_JSON=$(curl -s \
  -H "Authorization: token $BACKUP_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$BACKUP_REPO/releases?per_page=100")

# 检查 API 返回
if echo "$RELEASES_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if isinstance(d,list) else 1)" 2>/dev/null; then
  : # OK
else
  echo "ERROR: GitHub API 返回异常"
  echo "$RELEASES_JSON" | head -5
  exit 1
fi

# ---------- 3. 生成 INSERT SQL（幂等：跳过已存在的 tag） ----------
SQL=$(echo "$RELEASES_JSON" | python3 -c "
import sys, json

data = json.load(sys.stdin)
lines = []

# 先查已存在的 tag
existing_cmd = '''SELECT tag FROM backup_record WHERE tag LIKE 'backup-%';'''

sqls = []
for r in data:
    tag = r['tag_name']
    if not tag.startswith('backup-'):
        continue

    assets = r.get('assets', [])
    db_size = 0
    uploads_size = 0
    asset_count = len(assets)
    asset_names = []

    for a in assets:
        name = a['name']
        size = a.get('size', 0)
        asset_names.append(name)
        if name.endswith('.sql.gz.enc'):
            db_size = size
        elif name.endswith('.tar.gz.enc'):
            uploads_size = size

    # 解析 tag 日期：backup-20260624-141847 → 2026-06-24T14:18:47.000
    parts = tag.replace('backup-', '').split('-')
    if len(parts) == 2:
        d, t = parts[0], parts[1]
        started_at = f'{d[:4]}-{d[4:6]}-{d[6:8]}T{t[:2]}:{t[2:4]}:{t[4:6]}.000'
    else:
        started_at = r['created_at'].replace('Z', '+00:00')

    tag_esc = tag.replace(\"'\", \"''\")
    asset_str = ','.join(asset_names).replace(\"'\", \"''\")

    sqls.append(f\"INSERT OR IGNORE INTO backup_record (tag, status, started_at, finished_at, db_size, uploads_size, asset_count, asset_urls, operator_name) VALUES ('{tag_esc}', 'SUCCESS', '{started_at}', '{started_at}', {db_size}, {uploads_size}, {asset_count}, '{asset_str}', 'github-sync');\")

for s in sqls:
    print(s)
")

if [[ -z "$SQL" ]]; then
  echo "没有 backup-* release 需要同步"
  exit 0
fi

# ---------- 4. 获取已存在 tag 列表，过滤 ----------
EXISTING_TAGS=$(docker exec "$CONTAINER" sqlite3 "$DB_PATH" "SELECT tag FROM backup_record WHERE tag LIKE 'backup-%';" 2>/dev/null || true)

FILTERED_SQL=""
INSERTED=0
SKIPPED=0

while IFS= read -r line; do
  # 提取 tag 值（兼容 macOS grep，无 -P）
  TAG=$(echo "$line" | sed -n "s/.*'\(backup-[^']*\)'.*/\1/p" | head -1)
  if echo "$EXISTING_TAGS" | grep -qF "$TAG"; then
    SKIPPED=$((SKIPPED + 1))
  else
    FILTERED_SQL="${FILTERED_SQL}${line}"$'\n'
    INSERTED=$((INSERTED + 1))
  fi
done <<< "$SQL"

if [[ $INSERTED -eq 0 ]]; then
  echo "所有 $SKIPPED 条记录已存在，无需同步"
  exit 0
fi

# ---------- 5. 执行插入 ----------
echo "$FILTERED_SQL" | docker exec -i "$CONTAINER" sqlite3 "$DB_PATH"

# ---------- 6. 验证 ----------
TOTAL=$(docker exec "$CONTAINER" sqlite3 "$DB_PATH" "SELECT count(*) FROM backup_record WHERE tag LIKE 'backup-%';")
echo ""
echo "同步完成: 新增 $INSERTED 条, 跳过 $SKIPPED 条, 共 $TOTAL 条备份记录"
docker exec "$CONTAINER" sqlite3 "$DB_PATH" -column -header \
  "SELECT id, tag, status, db_size, uploads_size FROM backup_record WHERE tag LIKE 'backup-%' ORDER BY started_at DESC LIMIT 15;"
