#!/bin/bash
# autodev-flow 工作流状态面板
# 用法:
#   bash status.sh                     # 展示阶段状态
#   bash status.sh --watch             # 动态刷新（默认 10 秒）
#   bash status.sh --watch 5           # 自定义刷新间隔（秒）
#   bash status.sh --tasks             # 展示任务状态
#   bash status.sh --watch --tasks     # 动态刷新 + 任务状态

WATCH_MODE=false
SHOW_TASKS=false
REFRESH_INTERVAL=10

for arg in "$@"; do
  case "$arg" in
    --watch|-w) WATCH_MODE=true ;;
    --tasks|-t) SHOW_TASKS=true ;;
    *[!0-9]*) ;;
    *) if [ "$WATCH_MODE" = true ]; then REFRESH_INTERVAL="$arg"; fi ;;
  esac
done

STATUS_FILE="autodev/status.json"

if ! command -v jq &>/dev/null; then
  echo "❌ 需要安装 jq: brew install jq"
  exit 1
fi

if [ ! -f "$STATUS_FILE" ]; then
  echo "❌ 状态文件不存在: $STATUS_FILE"
  exit 1
fi

# 从 status.json 读取 RUN 值
RUN=$(jq -r '.RUN // empty' "$STATUS_FILE" 2>/dev/null)

# 进度条
progress_bar() {
  local current=$1
  local total=$2
  local width=20
  local filled=$((current * width / total))
  local empty=$((width - filled))
  local bar=""
  for ((j=0; j<filled; j++)); do bar+="█"; done
  for ((j=0; j<empty; j++)); do bar+="░"; done
  echo "[$bar]"
}

# 状态映射
status_icon() {
  case "$1" in
    success)  echo "✅" ;;
    failed)   echo "❌" ;;
    skipped)  echo "⏭️ " ;;
    pending)  echo "⏸️ " ;;
    running)  echo "🟠" ;;
    *)        echo "❓" ;;
  esac
}

# 阶段名称映射
stage_name() {
  case "$1" in
    Stage_0) echo "项目检测" ;;
    Stage_1) echo "需求拟定" ;;
    Stage_2) echo "代码实现" ;;
    Stage_3) echo "代码审查" ;;
    Stage_4) echo "静态审计" ;;
    Stage_5) echo "集成测试" ;;
    Stage_6) echo "门控复核" ;;
    Stage_7) echo "文档同步" ;;
    Stage_8) echo "发布" ;;
    *)       echo "未知阶段" ;;
  esac
}

# 获取任务文档路径（优先用 RUN 值）
get_task_file() {
  # 优先用 status.json 中的 RUN 值
  if [ -n "$RUN" ]; then
    local file="autodev/auto_iteration/${RUN}.md"
    if [ -f "$file" ]; then
      echo "$file"
      return 0
    fi
  fi
  # fallback: 当前日期
  local today=$(date +%Y%m%d)
  local file="autodev/auto_iteration/${today}.md"
  if [ -f "$file" ]; then
    echo "$file"
    return 0
  fi
  # 查找最近 7 天的任务文档
  for d in $(seq 1 7); do
    local past=$(date -v-${d}d +%Y%m%d 2>/dev/null || date -d "-${d} days" +%Y%m%d 2>/dev/null)
    file="autodev/auto_iteration/${past}.md"
    if [ -f "$file" ]; then
      echo "$file"
      return 0
    fi
  done
  return 1
}

# 渲染任务状态
render_tasks() {
  local task_file
  task_file=$(get_task_file)
  if [ -z "$task_file" ]; then
    echo "  📋 未找到任务文档"
    return
  fi

  local run_label=$(basename "$task_file" .md)

  echo ""
  echo "──────────────────────────────────────────────────────────────"
  echo "  📋 任务状态 · ${run_label}"
  echo "──────────────────────────────────────────────────────────────"

  local pending_count=0
  local doing_count=0
  local done_count=0
  local failed_count=0

  # 分类收集
  local pending_tasks=""
  local doing_tasks=""
  local done_tasks=""
  local failed_tasks=""

  # 提取任务行
  while IFS= read -r line; do
    local task_id=$(echo "$line" | grep -oE '[0-9]{8}-(BUG|OPT|DEV)-[0-9]+')
    [ -z "$task_id" ] && continue
    if echo "$line" | grep -q "✅"; then
      done_tasks+="    ✅ $task_id\n"
      done_count=$((done_count + 1))
    elif echo "$line" | grep -q "❌"; then
      failed_tasks+="    ❌ $task_id\n"
      failed_count=$((failed_count + 1))
    elif echo "$line" | grep -q "🟠"; then
      doing_tasks+="    🔧 $task_id\n"
      doing_count=$((doing_count + 1))
    else
      pending_tasks+="    ⏳ $task_id\n"
      pending_count=$((pending_count + 1))
    fi
  done < <(grep -E "^### [0-9]{8}-(BUG|OPT|DEV)-[0-9]+" "$task_file" 2>/dev/null)

  # 提取 autopush 卡片
  while IFS= read -r line; do
    local task_id=$(echo "$line" | grep -oE 'autopush-[0-9]{8}-(BUG|OPT|DEV)-[0-9]+')
    if [ -n "$task_id" ]; then
      pending_tasks+="    🔄 $task_id\n"
      pending_count=$((pending_count + 1))
    fi
  done < <(grep -E "^### autopush-[0-9]{8}-(BUG|OPT|DEV)-[0-9]+" "$task_file" 2>/dev/null)

  # 按状态分组输出（待处理优先）
  if [ "$pending_count" -gt 0 ] || [ "$doing_count" -gt 0 ]; then
    echo ""
    echo "    🔴 待处理 ($((pending_count + doing_count)))"
    echo -e "$doing_tasks$pending_tasks"
  fi

  if [ "$done_count" -gt 0 ]; then
    echo "    ✅ 已完成 ($done_count)"
    echo -e "$done_tasks"
  fi

  if [ "$failed_count" -gt 0 ]; then
    echo "    ❌ 失败 ($failed_count)"
    echo -e "$failed_tasks"
  fi

  echo "    ─────────────────────────────────────"
  echo "    📊 待处理: $((pending_count + doing_count)) | 已完成: $done_count | 失败: $failed_count"
}

# 渲染面板
render_panel() {
  local total=0
  local done_count=0
  local fail_count=0
  local skip_count=0
  local active_count=0

  # 动态检测最大 Stage 编号
  local max_stage=7
  for k in $(jq -r '.stages | keys[]' "$STATUS_FILE" 2>/dev/null); do
    local num=$(echo "$k" | grep -oE '[0-9]+')
    if [ -n "$num" ] && [ "$num" -gt "$max_stage" ]; then
      max_stage=$num
    fi
  done

  for i in $(seq 1 $max_stage); do
    stage="Stage_$i"
    status=$(jq -r --arg s "$stage" '.stages[$s].status // "pending"' "$STATUS_FILE" 2>/dev/null)
    total=$((total + 1))
    case "$status" in
      success) done_count=$((done_count + 1)) ;;
      failed)  fail_count=$((fail_count + 1)) ;;
      skipped) skip_count=$((skip_count + 1)) ;;
    esac
  done
  active_count=$((total - skip_count))

  # 清屏（watch 模式）
  if [ "$WATCH_MODE" = true ]; then
    clear
  fi

  local run_label="${RUN:-$(date +%Y%m%d)}"

  echo ""
  echo "╔══════════════════════════════════════════════════════════════╗"
  echo "║              AutoDev Flow 工作流状态 · ${run_label}              ║"
  echo "╚══════════════════════════════════════════════════════════════╝"
  echo ""
  echo "  📊 总览: $active_count 个有效阶段 | ✅ $done_count 完成 | ❌ $fail_count 失败 | ⏭️  $skip_count 跳过"
  if [ "$active_count" -gt 0 ]; then
    echo "  $(progress_bar $done_count $active_count) $((done_count * 100 / active_count))%"
  else
    echo "  [░░░░░░░░░░░░░░░░░░░] 0%"
  fi
  echo ""
  echo "──────────────────────────────────────────────────────────────"

  for i in $(seq 1 $max_stage); do
    stage="Stage_$i"
    name=$(stage_name "$stage")
    status=$(jq -r --arg s "$stage" '.stages[$s].status // "pending"' "$STATUS_FILE" 2>/dev/null)
    message=$(jq -r --arg s "$stage" '.stages[$s].message // "-"' "$STATUS_FILE" 2>/dev/null)
    last_update=$(jq -r --arg s "$stage" '.stages[$s].last_update // "-"' "$STATUS_FILE" 2>/dev/null)
    icon=$(status_icon "$status")
    printf "  %s Stage %d %-8s │ %s" "$icon" "$i" "$name" "$status"
    if [ "$last_update" != "-" ] && [ "$last_update" != "null" ]; then
      printf "  📅 %s" "$last_update"
    fi
    echo ""
  done

  echo ""
  echo "──────────────────────────────────────────────────────────────"
  echo "  📁 来源: $STATUS_FILE"
  if [ "$WATCH_MODE" = true ]; then
    echo "  🔄 刷新中... 每 ${REFRESH_INTERVAL} 秒 (Ctrl+C 退出)"
  fi
  echo ""

  # 展示任务状态
  if [ "$SHOW_TASKS" = true ]; then
    render_tasks
  fi
  echo ""
}

# 主循环
if [ "$WATCH_MODE" = true ]; then
  while true; do
    render_panel
    sleep "$REFRESH_INTERVAL"
  done
else
  render_panel
fi
