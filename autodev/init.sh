#!/bin/bash
# AutoDev 初始化脚本
# 用法：bash autodev/init.sh

set -e

# 工作区根目录（脚本位于 autodev/ 下一级，所以用 .. 回到项目根）
WORKSPACE="$(cd "$(dirname "$0")/.." && pwd)"

echo "正在初始化 AutoDev 环境..."
echo "工作区：$WORKSPACE"

# 1. 创建目录结构
echo ""
echo "=== 1. 创建目录结构 ==="
mkdir -p "$WORKSPACE/autodev/auto_iteration/img"
mkdir -p "$WORKSPACE/autodev/audit_code"
echo "✅ 目录结构已创建："
echo "   - autodev/auto_iteration/img/"
echo "   - autodev/audit_code/"

# 2. 创建首次任务文档（从模板复制）
echo ""
echo "=== 2. 创建任务文档 ==="
TODAY=$(date +%Y%m%d)
TASK_DOC="$WORKSPACE/autodev/auto_iteration/${TODAY}.md"
TEMPLATE="$WORKSPACE/autodev/auto_iteration/20260000tmp.md"

if [ ! -f "$TASK_DOC" ]; then
    if [ -f "$TEMPLATE" ]; then
        cp "$TEMPLATE" "$TASK_DOC"
        echo "✅ 已创建任务文档：$TASK_DOC"
    else
        echo "⚠️  模板文件不存在：$TEMPLATE"
        echo "   请手动创建任务文档：$TASK_DOC"
    fi
else
    echo "⚠️  任务文档已存在，跳过：$TASK_DOC"
fi

# 3. 创建流程状态文件
echo ""
echo "=== 3. 创建流程状态文件 ==="
STATUS_FILE="$WORKSPACE/autodev/status.json"

if [ ! -f "$STATUS_FILE" ]; then
    cat > "$STATUS_FILE" <<EOF
{
  "RUN": "${TODAY}",
  "stages": {
    "Stage_1": {"status": "pending", "last_update": "", "message": ""},
    "Stage_2": {"status": "pending", "last_update": "", "message": ""},
    "Stage_3": {"status": "pending", "last_update": "", "message": ""},
    "Stage_4": {"status": "pending", "last_update": "", "message": ""},
    "Stage_5": {"status": "pending", "last_update": "", "message": ""},
    "Stage_6": {"status": "pending", "last_update": "", "message": ""},
    "Stage_7": {"status": "pending", "last_update": "", "message": ""},
    "Stage_8": {"status": "pending", "last_update": "", "message": ""}
  }
}
EOF
    echo "✅ 已创建流程状态文件：$STATUS_FILE"
    echo "   阶段编号说明（1-8）："
    echo "   - Stage_7: 产品经理"
    echo "   - Stage_7: 开发工程师"
    echo "   - Stage_6: 代码质量审查员"
    echo "   - Stage_7: 测试工程师"
    echo "   - Stage_8: 集成测试员"
    echo "   - Stage_6: 项目经理"
    echo "   - Stage_7: 文档工程师"
    echo "   - Stage_8: 发布管理员"
else
    echo "⚠️  流程状态文件已存在，跳过：$STATUS_FILE"
fi

# 4. 读取当前版本号
echo ""
echo "=== 4. 检查当前版本号 ==="
POM_FILE="$WORKSPACE/backend/pom.xml"
if [ -f "$POM_FILE" ]; then
    VERSION=$(grep -oP '<revision>\K[^<]+' "$POM_FILE" 2>/dev/null || echo "未找到")
    echo "✅ 当前版本号：$VERSION"
else
    echo "⚠️  pom.xml 不存在：$POM_FILE"
fi

# 完成
echo ""
echo "=========================================="
echo "✅ 初始化完成！"
echo "=========================================="
echo ""
echo "下一步："
echo "1. 编辑任务文档：$TASK_DOC"
echo "2. 按需触发各阶段（Stage 0 可随时手动触发）"
echo ""
