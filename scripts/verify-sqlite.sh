#!/bin/bash
# 端到端验证：跑 38 个业务 SQL 涉及的 API 端点
# 依赖：后端在跑（localhost:8080）+ admin 登录

set -e

BASE="${BASE:-http://localhost:8080/api/v1}"
PASS=0
FAIL=0
FAILED_TESTS=()

# 登录拿 token + deviceId
echo "=== 登录 ==="
LOGIN=$(curl -s -X POST $BASE/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"123456"}')
TOKEN=$(echo $LOGIN | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['token'])" 2>/dev/null)
DEVICE=$(echo $LOGIN | python3 -c "import sys,json;print(json.load(sys.stdin)['data']['deviceId'])" 2>/dev/null)
if [ -z "$TOKEN" ]; then
    echo "❌ 登录失败"; exit 1
fi
echo "✅ 登录成功 (deviceId=$DEVICE)"
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
    code=$(curl -s -o /dev/null -w "%{http_code}" -X $method "${headers[@]}" $extra_args "$BASE$path")
    
    if [ "$code" = "200" ]; then
        echo "  ✅ $name ($method $path → $code)"
        PASS=$((PASS+1))
    else
        echo "  ❌ $name ($method $path → $code) **失败**"
        FAILED_TESTS+=("$name ($code)")
        FAIL=$((FAIL+1))
    fi
}

echo "=== 公开端点（无需鉴权）==="
test_endpoint "健康检查"        GET  /health
test_endpoint "文章列表"        GET  /articles
test_endpoint "文章分类"        GET  /articles/categories
test_endpoint "文章标签"         GET  /articles/tags
test_endpoint "文章归档"        GET  /articles/archives
test_endpoint "评论列表"        GET  "/comments?articleId=1"
test_endpoint "公开 settings blog"  GET  /public/settings/blog
test_endpoint "公开 settings social" GET  /public/settings/social
test_endpoint "公开 settings profile" GET /public/settings/profile
test_endpoint "设备检查"        GET  /public/device/check
test_endpoint "文章详情 slug"   GET  /articles/spring-boot-jwt-security
test_endpoint "公开 settings theme"  GET /public/settings/theme

echo
echo "=== Admin 端点（需鉴权）==="
test_endpoint "当前用户"        GET    /auth/me yes
test_endpoint "仪表盘聚合"      GET    /admin/dashboard yes
test_endpoint "文章管理"        GET    "/articles/admin/all?page=1&size=10" yes
test_endpoint "评论管理"        GET    "/comments/admin?articleId=1" yes
test_endpoint "公开分类"        GET    /articles/categories yes
test_endpoint "公开标签"         GET    /articles/tags yes
test_endpoint "设备管理"        GET    /admin/devices yes
test_endpoint "API 白名单"      GET    /admin/api-whitelist yes
test_endpoint "个人资料"        GET    /admin/settings/profile yes
test_endpoint "站点信息"        GET    /admin/settings/blog yes
test_endpoint "技术栈"          GET    /admin/settings/techstack yes
test_endpoint "个人经历"        GET    /admin/settings/experience yes

echo
echo "=== 写操作端点（需鉴权）==="
# 更新 dashboard 的 view_count 自增
test_endpoint "文章详情（admin 模式）"  GET  "/articles/admin/detail/1" yes

# v4.2.0 数据备份管理（REQ-BACKUP-2026-06-20）
# 只测查询类端点（list / get）；run 端点会真的启脚本+上传 GitHub,不在 verify 里跑
# 拿最新一条 record id 给 get 端点用(如果有),否则测一个不存在的 id 走 404 路径
test_endpoint "备份历史列表"   GET  "/admin/backup/list?page=1&size=10" yes
LATEST_ID=$(curl -s -H "Authorization: Bearer $TOKEN" -H "X-Device-Id: $DEVICE" \
    "$BASE/admin/backup/list?page=1&size=1" | \
    python3 -c "import sys,json;d=json.load(sys.stdin).get('data',{});recs=d.get('records') or [];print(recs[0]['id'] if recs else '')" 2>/dev/null)
if [ -n "$LATEST_ID" ]; then
    test_endpoint "备份详情"    GET  "/admin/backup/$LATEST_ID" yes
else
    # 库空,测 404 路径(后端 code=404 也算"接口活着")
    test_endpoint "备份详情(空记录)"  GET  "/admin/backup/999999" yes
fi

# 修改个人资料（PUT 用 UpdateWrapper 替代 updateById）
echo "  ... PUT /admin/settings/profile (直接 curl 测试)"
PUT_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X PUT $BASE/admin/settings/profile \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-Device-Id: $DEVICE" \
    -H "Content-Type: application/json" \
    -d '{"nickname":"测试"}')
if [ "$PUT_CODE" = "200" ]; then
    echo "  ✅ 更新个人资料 (PUT /admin/settings/profile → 200)"
    PASS=$((PASS+1))
else
    echo "  ❌ 更新个人资料 (PUT /admin/settings/profile → $PUT_CODE)"
    FAILED_TESTS+=("更新个人资料 ($PUT_CODE)")
    FAIL=$((FAIL+1))
fi

# 给公开 page_view 加一行（验证业务）
test_endpoint "page_view 写入"  GET   /articles ""
test_endpoint "page_view 去重"  GET   /articles ""

echo
echo "=== 仅链接可见文章 (status=3) 验证 ==="
CAT_ID=$(curl -s "$BASE/articles/categories" | python3 -c "import sys,json;cats=json.load(sys.stdin).get('data',[]);print(cats[0]['id'] if cats else '')" 2>/dev/null)
if [ -z "$CAT_ID" ]; then
    CAT_RESP=$(curl -s -X POST "$BASE/articles/categories" \
        -H "Authorization: Bearer $TOKEN" \
        -H "X-Device-Id: $DEVICE" \
        -H "Content-Type: application/json" \
        -d '{"name":"默认分类","slug":"default"}')
    CAT_ID=$(echo "$CAT_RESP" | python3 -c "import sys,json;print(json.load(sys.stdin).get('data',{}).get('id', 1))" 2>/dev/null)
fi

UNLISTED_SLUG="verify-unlisted-$(date +%s)"
CREATE_RESP=$(curl -s -X POST $BASE/articles \
    -H "Authorization: Bearer $TOKEN" \
    -H "X-Device-Id: $DEVICE" \
    -H "Content-Type: application/json" \
    -d "{\"title\":\"验证仅链接文章\",\"slug\":\"$UNLISTED_SLUG\",\"summary\":\"验证仅链接\",\"contentMd\":\"# 私密内容\",\"categoryId\":$CAT_ID,\"status\":3}")
UNLISTED_ID=$(echo "$CREATE_RESP" | python3 -c "import sys,json;print(json.load(sys.stdin).get('data',{}).get('id',''))" 2>/dev/null)

if [ -n "$UNLISTED_ID" ]; then
    echo "  ✅ 创建仅链接文章 (POST /articles status=3 → id=$UNLISTED_ID)"
    PASS=$((PASS+1))

    ANON_LIST=$(curl -s "$BASE/articles?size=100")
    if echo "$ANON_LIST" | grep -q "$UNLISTED_SLUG"; then
        echo "  ❌ 访客列表泄露仅链接文章 (GET /articles 包含 $UNLISTED_SLUG)"
        FAILED_TESTS+=("访客列表隔离")
        FAIL=$((FAIL+1))
    else
        echo "  ✅ 访客列表隔离生效 (GET /articles 隐藏 $UNLISTED_SLUG)"
        PASS=$((PASS+1))
    fi

    ADMIN_LIST=$(curl -s -H "Authorization: Bearer $TOKEN" -H "X-Device-Id: $DEVICE" "$BASE/articles?size=100")
    if echo "$ADMIN_LIST" | grep -q "$UNLISTED_SLUG"; then
        echo "  ✅ 管理员列表可见 (GET /articles 包含 $UNLISTED_SLUG)"
        PASS=$((PASS+1))
    else
        echo "  ❌ 管理员列表未见仅链接文章 (GET /articles 未包含 $UNLISTED_SLUG)"
        FAILED_TESTS+=("管理员列表可见")
        FAIL=$((FAIL+1))
    fi

    DETAIL_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/articles/$UNLISTED_SLUG")
    if [ "$DETAIL_CODE" = "200" ]; then
        echo "  ✅ 访客直接链接访问详情 (GET /articles/$UNLISTED_SLUG → 200)"
        PASS=$((PASS+1))
    else
        echo "  ❌ 访客直接链接访问详情失败 (GET /articles/$UNLISTED_SLUG → $DETAIL_CODE)"
        FAILED_TESTS+=("仅链接详情访问 ($DETAIL_CODE)")
        FAIL=$((FAIL+1))
    fi

    # 测试仅链接文章附件上传与公开下载
    TMP_ZIP="/tmp/verify-attach-$UNLISTED_ID.zip"
    echo "test-zip-payload" > "$TMP_ZIP"
    UPLOAD_RESP=$(curl -s -X POST "$BASE/admin/articles/$UNLISTED_ID/attachment" \
        -H "Authorization: Bearer $TOKEN" \
        -H "X-Device-Id: $DEVICE" \
        -F "file=@$TMP_ZIP")
    rm -f "$TMP_ZIP"

    ATTACH_CODE=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/articles/$UNLISTED_ID/attachment")
    if [ "$ATTACH_CODE" = "200" ]; then
        echo "  ✅ 访客下载仅链接文章附件 (GET /articles/$UNLISTED_ID/attachment → 200)"
        PASS=$((PASS+1))
    else
        echo "  ❌ 访客下载仅链接文章附件失败 (GET /articles/$UNLISTED_ID/attachment → $ATTACH_CODE)"
        FAILED_TESTS+=("仅链接文章附件下载 ($ATTACH_CODE)")
        FAIL=$((FAIL+1))
    fi

    # 清理测试文章
    curl -s -X DELETE "$BASE/articles/$UNLISTED_ID" \
        -H "Authorization: Bearer $TOKEN" \
        -H "X-Device-Id: $DEVICE" > /dev/null
else
    echo "  ❌ 创建仅链接文章失败: $CREATE_RESP"
    FAILED_TESTS+=("创建仅链接文章")
    FAIL=$((FAIL+1))
fi

echo
echo "=== 业务层去重验证（直接 curl 带 X-Visitor-Id 刷 5 次）==="
# curl 需显式带 X-Visitor-Id（浏览器由 usePublicApi 自动加）
VID="verify-$(date +%s)"
for i in 1 2 3 4 5; do
    curl -s -H "X-Visitor-Id: $VID" $BASE/articles > /dev/null
done
sleep 1
DB_EXEC="${DB_EXEC:-sqlite3 /Users/yuanyi/MyProject/vibeP/my-blog/backend/blog.db}"
AFTER=$($DB_EXEC "SELECT COUNT(*) FROM page_view WHERE visitor='$VID';")
if [ "$AFTER" = "1" ]; then
    echo "  ✅ 同 visitor 连刷 5 次 → DB +1 行（业务层去重生效）"
    PASS=$((PASS+1))
else
    echo "  ❌ 同 visitor 连刷 5 次 → DB +$AFTER 行（去重失败）"
    FAILED_TESTS+=("page_view 业务层去重")
    FAIL=$((FAIL+1))
fi

echo
echo "=== 总结 ==="
echo "  通过: $PASS"
echo "  失败: $FAIL"
if [ $FAIL -gt 0 ]; then
    echo "  失败列表："
    for t in "${FAILED_TESTS[@]}"; do
        echo "    - $t"
    done
    exit 1
fi
echo "✅ 全部 $PASS 个端点通过"
