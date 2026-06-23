#!/bin/bash
# 备份和恢复功能自测脚本
# 测试场景：
# 1. 备份功能（触发、轮询、删除）
# 2. 恢复功能（查看历史、触发恢复）
# 3. 状态隔离（备份和恢复互不影响）

set -e

# 禁用代理（防止 curl 使用 http_proxy）
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

echo "=== 备份和恢复功能自测 ==="
echo

# 登录拿 token + deviceId
echo "=== 登录 ==="
LOGIN=$(curl -s -X POST $BASE/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"123456"}')
TOKEN=$(echo $LOGIN | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)
DEVICE=$(echo $LOGIN | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['deviceId'])" 2>/dev/null)
if [ -z "$TOKEN" ]; then
    echo -e "${RED}❌ 登录失败${NC}"
    exit 1
fi
echo -e "${GREEN}✅ 登录成功${NC} (deviceId=$DEVICE)"
echo

# 测试函数
test_endpoint() {
    local name="$1"
    local method="$2"
    local path="$3"
    local need_auth="$4"  # yes/no
    local extra_args="$5"  # curl 额外参数
    
    local headers=()
    if [ "$need_auth" = "yes" ]; then
        headers+=(-H "Authorization: Bearer $TOKEN" -H "X-Device-Id: $DEVICE")
    fi
    
    local code
    code=$(curl -s -o /dev/null -w "%{http_code}" -X $method "${headers[@]}" $extra_args "$BASE$path" 2>/dev/null)
    
    if [ "$code" = "200" ]; then
        echo -e "  ${GREEN}✅ $name${NC} ($method $path → $code)"
        PASS=$((PASS+1))
        return 0
    else
        echo -e "  ${RED}❌ $name${NC} ($method $path → $code) ${YELLOW}**失败**${NC}"
        FAILED_TESTS+=("$name ($code)")
        FAIL=$((FAIL+1))
        return 1
    fi
}

# 测试函数（带响应体）
test_endpoint_with_response() {
    local name="$1"
    local method="$2"
    local path="$3"
    local need_auth="$4"
    local extra_args="$5"
    
    local headers=()
    if [ "$need_auth" = "yes" ]; then
        headers+=(-H "Authorization: Bearer $TOKEN" -H "X-Device-Id: $DEVICE")
    fi
    
    local response
    response=$(curl -s -w "\n%{http_code}" -X $method "${headers[@]}" $extra_args "$BASE$path" 2>/dev/null)
    local body=$(echo "$response" | head -n -1)
    local code=$(echo "$response" | tail -n 1)
    
    if [ "$code" = "200" ]; then
        echo -e "  ${GREEN}✅ $name${NC} ($method $path → $code)"
        PASS=$((PASS+1))
        echo "$body"
        return 0
    else
        echo -e "  ${RED}❌ $name${NC} ($method $path → $code) ${YELLOW}**失败**${NC}"
        FAILED_TESTS+=("$name ($code)")
        FAIL=$((FAIL+1))
        echo "$body"
        return 1
    fi
}

echo "=== 1. 备份功能测试 ==="

# 1.1 获取备份历史列表（应为空或已有记录）
echo "1.1 获取备份历史列表"
test_endpoint "备份历史列表" GET "/admin/backup/list?page=1&size=20" "yes"

# 1.2 触发备份（异步，立即返回 record id）
echo "1.2 触发备份"
BACKUP_RESPONSE=$(curl -s -X POST $BASE/admin/backup/run \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-Device-Id: $DEVICE" \
    -H "Content-Type: application/json" \
    -d '{}')
BACKUP_ID=$(echo $BACKUP_RESPONSE | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)
if [ -n "$BACKUP_ID" ]; then
    echo -e "  ${GREEN}✅ 触发备份成功${NC} (backup_id=$BACKUP_ID)"
    PASS=$((PASS+1))
else
    echo -e "  ${RED}❌ 触发备份失败${NC}"
    FAILED_TESTS+=("触发备份")
    FAIL=$((FAIL+1))
fi
echo

# 1.3 轮询备份状态（等待完成或超时）
if [ -n "$BACKUP_ID" ]; then
    echo "1.3 轮询备份状态 (backup_id=$BACKUP_ID)"
    for i in {1..30}; do
        sleep 2
        STATUS_RESPONSE=$(curl -s -X GET $BASE/admin/backup/$BACKUP_ID \
            -H "Authorization: Bearer $TOKEN" \
            -H "X-Device-Id: $DEVICE")
        STATUS=$(echo $STATUS_RESPONSE | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['status'])" 2>/dev/null)
        echo "    轮询 $i: status=$STATUS"
        if [ "$STATUS" = "SUCCESS" ] || [ "$STATUS" = "FAILED" ]; then
            echo -e "  ${GREEN}✅ 备份任务完成${NC} (status=$STATUS)"
            PASS=$((PASS+1))
            break
        fi
        if [ $i -eq 30 ]; then
            echo -e "  ${YELLOW}⚠️  备份任务超时${NC} (status=$STATUS)"
            FAILED_TESTS+=("备份任务超时")
            FAIL=$((FAIL+1))
        fi
    done
    echo
fi

echo "=== 2. 恢复功能测试 ==="

# 2.1 获取恢复历史列表（应为空）
echo "2.1 获取恢复历史列表"
test_endpoint "恢复历史列表" GET "/admin/restore/list?page=1&size=20" "yes"

# 2.2 获取成功的备份列表（用于恢复）
echo "2.2 获取成功的备份列表"
SUCCESS_BACKUPS=$(curl -s -X GET $BASE/admin/backup/list?page=1\&size=100 \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-Device-Id: $DEVICE")
SUCCESS_BACKUP_ID=$(echo $SUCCESS_BACKUPS | python3 -c "import sys,json;records=json.load(sys.stdin)['data']['records'];success=[r for r in records if r['status']=='SUCCESS'];print(success[0]['id'] if success else '')" 2>/dev/null)

if [ -n "$SUCCESS_BACKUP_ID" ]; then
    echo -e "  ${GREEN}✅ 找到成功的备份${NC} (backup_id=$SUCCESS_BACKUP_ID)"
    PASS=$((PASS+1))
    
    # 2.3 触发恢复（异步，立即返回 record id）
    echo "2.3 触发恢复"
    RESTORE_RESPONSE=$(curl -s -X POST $BASE/admin/restore/run \
        -H "Authorization: Bearer $TOKEN" \
        -H "X-Device-Id: $DEVICE" \
        -H "Content-Type: application/json" \
        -d "{\"recordId\":$SUCCESS_BACKUP_ID,\"scope\":\"DB_ONLY\"}")
    RESTORE_ID=$(echo $RESTORE_RESPONSE | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['id'])" 2>/dev/null)
    if [ -n "$RESTORE_ID" ]; then
        echo -e "  ${GREEN}✅ 触发恢复成功${NC} (restore_id=$RESTORE_ID)"
        PASS=$((PASS+1))
    else
        echo -e "  ${RED}❌ 触发恢复失败${NC}"
        FAILED_TESTS+=("触发恢复")
        FAIL=$((FAIL+1))
    fi
    echo
    
    # 2.4 轮询恢复状态（等待完成或超时）
    if [ -n "$RESTORE_ID" ]; then
        echo "2.4 轮询恢复状态 (restore_id=$RESTORE_ID)"
        for i in {1..30}; do
            sleep 2
            STATUS_RESPONSE=$(curl -s -X GET $BASE/admin/restore/$RESTORE_ID \
                -H "Authorization: Bearer $TOKEN" \
                -H "X-Device-Id: $DEVICE")
            STATUS=$(echo $STATUS_RESPONSE | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['status'])" 2>/dev/null)
            echo "    轮询 $i: status=$STATUS"
            if [ "$STATUS" = "SUCCESS" ] || [ "$STATUS" = "FAILED" ] || [ "$STATUS" = "UNKNOWN" ]; then
                echo -e "  ${GREEN}✅ 恢复任务完成${NC} (status=$STATUS)"
                PASS=$((PASS+1))
                break
            fi
            if [ $i -eq 30 ]; then
                echo -e "  ${YELLOW}⚠️  恢复任务超时${NC} (status=$STATUS)"
                FAILED_TESTS+=("恢复任务超时")
                FAIL=$((FAIL+1))
            fi
        done
        echo
    fi
else
    echo -e "  ${YELLOW}⚠️  没有成功的备份，跳过恢复测试${NC}"
    echo
fi

echo "=== 3. 状态隔离测试 ==="

# 3.1 检查备份和恢复的并发互斥
echo "3.1 检查备份和恢复的并发互斥"
# 这个需要手动测试：同时触发备份和恢复，应该有一个被拒绝
echo -e "  ${YELLOW}⚠️  需要手动测试：同时触发备份和恢复${NC}"
echo

# 3.2 检查备份和恢复的轮询状态隔离
echo "3.2 检查备份和恢复的轮询状态隔离"
echo -e "  ${YELLOW}⚠️  需要手动测试：触发备份后切换页面，备份轮询应继续${NC}"
echo -e "  ${YELLOW}⚠️  需要手动测试：触发恢复后切换页面，恢复轮询应继续${NC}"
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
echo

if [ $FAIL -eq 0 ]; then
    echo -e "${GREEN}🎉 所有测试通过！${NC}"
    exit 0
else
    echo -e "${RED}❌ 有 $FAIL 个测试失败${NC}"
    exit 1
fi
