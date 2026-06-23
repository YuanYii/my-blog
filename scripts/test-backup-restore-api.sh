#!/bin/bash
# 备份和恢复功能后端 API 测试脚本
# 测试场景：
# 1. 备份历史列表 API
# 2. 恢复历史列表 API（新增）
# 3. 单条备份详情 API
# 4. 单条恢复详情 API

# 注意：这个脚本假设后端已经在运行，且 Redis 已启动
# 如果 Redis 未启动，登录会失败，需要先启动 Redis

set -e

# 禁用代理
unset http_proxy
unset HTTP_PROXY
unset https_proxy
unset HTTPS_PROXY
export no_proxy=localhost,127.0.0.1

BASE="http://localhost:8080/api/v1"
PASS=0
FAIL=0
FAILED_TESTS=()

# 颜色输出
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo "=== 备份和恢复功能后端 API 测试 ==="
echo

# 登录拿 token + deviceId
echo "=== 登录 ==="
LOGIN=$(curl -s --noproxy localhost -X POST $BASE/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"123456"}')
TOKEN=$(echo $LOGIN | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)
DEVICE=$(echo $LOGIN | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['deviceId'])" 2>/dev/null)
if [ -z "$TOKEN" ]; then
    echo -e "${RED}❌ 登录失败${NC}"
    echo "响应: $LOGIN"
    echo ""
    echo "请检查："
    echo "1. 后端是否在运行？（curl http://localhost:8080/api/v1/health）"
    echo "2. Redis 是否在运行？（docker start blog-redis）"
    echo "3. 用户名密码是否正确？（默认 admin / 123456）"
    exit 1
fi
echo -e "${GREEN}✅ 登录成功${NC} (deviceId=$DEVICE)"
echo ""

# 测试函数
test_endpoint() {
    local name="$1"
    local method="$2"
    local path="$3"
    local expect_code="$4"  # 期望的 HTTP 状态码
    
    local code
    code=$(curl -s --noproxy localhost -o /dev/null -w "%{http_code}" -X $method $BASE$path \
        -H "Authorization: Bearer $TOKEN" \
        -H "X-Device-Id: $DEVICE")
    
    if [ "$code" = "$expect_code" ]; then
        echo -e "  ${GREEN}✅ $name${NC} ($method $path → $code)"
        PASS=$((PASS+1))
        return 0
    else
        echo -e "  ${RED}❌ $name${NC} ($method $path → $code，期望 $expect_code) ${YELLOW}**失败**${NC}"
        FAILED_TESTS+=("$name ($code)")
        FAIL=$((FAIL+1))
        return 1
    fi
}

# 测试函数（带响应体输出）
test_endpoint_with_response() {
    local name="$1"
    local method="$2"
    local path="$3"
    local expect_code="$4"
    
    local response
    response=$(curl -s --noproxy localhost -w "\n%{http_code}" -X $method $BASE$path \
        -H "Authorization: Bearer $TOKEN" \
        -H "X-Device-Id: $DEVICE")
    local body=$(echo "$response" | head -n -1)
    local code=$(echo "$response" | tail -n 1)
    
    if [ "$code" = "$expect_code" ]; then
        echo -e "  ${GREEN}✅ $name${NC} ($method $path → $code)"
        PASS=$((PASS+1))
        echo "$body" | python3 -m json.tool 2>/dev/null || echo "$body"
        return 0
    else
        echo -e "  ${RED}❌ $name${NC} ($method $path → $code，期望 $expect_code) ${YELLOW}**失败**${NC}"
        FAILED_TESTS+=("$name ($code)")
        FAIL=$((FAIL+1))
        echo "$body"
        return 1
    fi
}

echo "=== 1. 备份功能 API 测试 ==="

# 1.1 获取备份历史列表
echo "1.1 获取备份历史列表"
test_endpoint_with_response "备份历史列表" GET "/admin/backup/list?page=1&size=20" "200"
echo

# 1.2 获取单条备份详情（假设 ID=1 存在）
echo "1.2 获取单条备份详情（ID=1）"
test_endpoint_with_response "备份详情" GET "/admin/backup/1" "200" || true
echo

echo "=== 2. 恢复功能 API 测试（新增）==="

# 2.1 获取恢复历史列表
echo "2.1 获取恢复历史列表"
test_endpoint_with_response "恢复历史列表" GET "/admin/restore/list?page=1&size=20" "200"
echo

# 2.2 获取单条恢复详情（假设 ID=1 存在）
echo "2.2 获取单条恢复详情（ID=1）"
test_endpoint_with_response "恢复详情" GET "/admin/restore/1" "200" || true
echo

echo "=== 3. 边界情况测试 ==="

# 3.1 获取不存在的备份详情
echo "3.1 获取不存在的备份详情（ID=99999）"
test_endpoint "备份详情（不存在）" GET "/admin/backup/99999" "404"
echo

# 3.2 获取不存在的恢复详情
echo "3.2 获取不存在的恢复详情（ID=99999）"
test_endpoint "恢复详情（不存在）" GET "/admin/restore/99999" "404"
echo

# 3.3 未授权访问备份列表
echo "3.3 未授权访问备份列表"
code=$(curl -s --noproxy localhost -o /dev/null -w "%{http_code}" -X GET $BASE/admin/backup/list)
if [ "$code" = "401" ] || [ "$code" = "403" ]; then
    echo -e "  ${GREEN}✅ 未授权访问被拒绝${NC} (GET /admin/backup/list → $code)"
    PASS=$((PASS+1))
else
    echo -e "  ${RED}❌ 未授权访问未被拒绝${NC} (GET /admin/backup/list → $code) ${YELLOW}**失败**${NC}"
    FAILED_TESTS+=("未授权访问备份列表 ($code)")
    FAIL=$((FAIL+1))
fi
echo

echo "=== 测试结果 ==="
echo -e "${GREEN}通过: $PASS${NC}"
echo -e "${RED}失败: $FAIL${NC}"
if [ ${#FAILED_TESTS[@]} -gt 0 ]; then
    echo "失败项:"
    for test in "${FAILED_TESTS[@]}"; do
        echo -e "  ${RED}- $test${NC}"
    done
fi
echo ""

if [ $FAIL -eq 0 ]; then
    echo -e "${GREEN}🎉 所有 API 测试通过！${NC}"
    exit 0
else
    echo -e "${RED}❌ 有 $FAIL 个测试失败${NC}"
    exit 1
fi
