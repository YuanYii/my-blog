#!/bin/bash
# 本地执行：打包 my-blog 部署资源并发布到 GitHub Release
# 流程：清理 → 重新构建 → 打包资源 → 创建/更新 Release → 上传 assets
#
# 必读：
#   1. $GITHUB_TOKEN  环境变量（repo 权限）
#   2. $GITHUB_REPO   形如 "owner/repo"，脚本不写死
#
# 可选环境变量：
#   EXPORT_DB=0       是否把 dev db 加密导出到 release（默认 0）
#                     设为 1 后脚本会调 sqlite-export.sh 导出 backend/blog.db，
#                     交互式输入两次密码，加密为 dev-blog-dump.sql.gz.enc
#                     生产端 deploy-server.sh 加 IMPORT_DB=1 会自动解密导入
#
# 产出 assets：
#   - blog-app.jar                 (后端 fat jar)
#   - frontend-static.tar.gz       (Nuxt 生成的 .output/public/)
#   - schema-sqlite.sql            (初始化库)
#   - deploy-server.sh             (服务器端一键部署脚本)
#   - sqlite-import.sh             (服务器端解密脚本,IMPORT_DB=1 时 deploy-server 调用)
#   - dev-blog-dump.sql.gz.enc     (加密 db 导出,EXPORT_DB=1 时才有)
#   - deploy-bundle-vX.Y.Z.zip     (上面几件套的合包，给一次性冷部署)
#   - SHA256SUMS                   (校验文件)
#
# 用法：
#   export GITHUB_TOKEN=ghp_xxxxx
#   export GITHUB_REPO=yourname/your-repo
#   ./docs/scripts/publish-release.sh                          # 仅代码发版
#   EXPORT_DB=1 ./docs/scripts/publish-release.sh             # 代码 + 加密数据一起发版
#
# 行为：
#   - 不接受传参指定 tag（自动）
#   - 前两位 MAJOR.MINOR 由开发者自己维护（写进 pom.xml 的 <revision> 前两位）
#   - 第三位 PATCH 由脚本自动算：看 GitHub 上 vMAJOR.MINOR.* 最大的 patch，+1
#   - 该前缀无 release 时首发 vMAJOR.MINOR.1
#   - pom.xml 的 <revision> 第三位仅作开发期标记，不参与发布号计算（脚本也不回写）

set -euo pipefail

# ============= 0. 准备 =============
# 2026-06-18：脚本搬到 docs/scripts/ 后比原 scripts/ 多一层目录，项目根要往上跳两级
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
cd "$ROOT_DIR"

# 颜色
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'; NC='\033[0m'
info()  { echo -e "${GREEN}[INFO]${NC} $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC} $*"; }
err()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }

# 尝试加载 docs/scripts/deploy.env（本地运维用，gitignored）
# 使用 set -a 让变量自动 export
if [ -f "$SCRIPT_DIR/deploy.env" ]; then
    info "加载 $SCRIPT_DIR/deploy.env"
    set -a
    # shellcheck disable=SC1091
    source "$SCRIPT_DIR/deploy.env"
    set +a
fi

# 校验环境变量
if [ -z "${GITHUB_TOKEN:-}" ]; then
    err "GITHUB_TOKEN 未设置。3 种配置方式（任选一种）："
    err "  1. cp docs/scripts/deploy.env.example docs/scripts/deploy.env，编辑后重跑"
    err "  2. export GITHUB_TOKEN=ghp_xxx 后重跑"
    err "  3. 临时一次性：GITHUB_TOKEN=ghp_xxx ./docs/scripts/publish-release.sh"
    exit 1
fi
if [ -z "${GITHUB_REPO:-}" ]; then
    err "GITHUB_REPO 未设置。export GITHUB_REPO=owner/repo 后重试"
    err "  （参考 docs/scripts/deploy.env.example）"
    exit 1
fi

# 决定 tag：前两位从 pom.xml 的 <revision> 读，第三位按 GitHub release 自动 +1
# 规则：
#   1. MAJOR.MINOR 从 backend/pom.xml 的 <revision> 读（如 4.0.0 → 4.0）
#   2. 拉 GitHub 上所有 release 的 tag_name，过滤出 vMAJOR.MINOR.* 的
#   3. 找该前缀下最大的 patch，+1 = 新 tag
#   4. 该前缀无 release 时首发 vMAJOR.MINOR.1
#   5. 脚本不回写 pom（第三位仅作开发期标记，发版记录由开发者手动维护）
# 例：pom=4.0.5，GitHub 上 v4.0.* 最大是 4.0.9 → 发 v4.0.10
#     pom=4.0.0，GitHub 上 v4.0.* 最大是 4.0.5 → 发 v4.0.6
#     pom=5.0.0，GitHub 上无 v5.0.*           → 发 v5.0.1
if [ -n "${1:-}" ]; then
    err "本脚本版本号完全自动（前两位从 pom 读，第三位从 GitHub 算），不接受手动指定"
    err "  传参 '$1' 被忽略"
    err "  升级 MAJOR.MINOR 请直接改 backend/pom.xml 的 <revision> 前两位"
    err "  强制某 PATCH 请先到 GitHub Releases 删掉对应 tag 后重跑"
    exit 1
fi

info "=== 0. 解析目标 tag（pom 前两位 + GitHub 同前缀最大 patch +1）==="

# 0.1 从 pom.xml 读 <revision>，取前两位
POM_REVISION=$(grep -oE '<revision>[0-9]+\.[0-9]+(\.[0-9]+)?</revision>' \
    "$ROOT_DIR/backend/pom.xml" | head -1 | sed -E 's@</?revision>@@g')
if [ -z "$POM_REVISION" ]; then
    err "backend/pom.xml 里读不到 <revision>X.Y.Z</revision>，无法决定 MAJOR.MINOR"
    err "  请在 <properties> 段维护 <revision>4.1.0</revision> 这样的版本号"
    exit 1
fi
POM_MAJOR=$(echo "$POM_REVISION" | cut -d. -f1)
POM_MINOR=$(echo "$POM_REVISION" | cut -d. -f2)
info "pom <revision>: $POM_REVISION → 前两位 v${POM_MAJOR}.${POM_MINOR}"

# 0.2 拉 GitHub 上所有 release，找 vPOM_MAJOR.POM_MINOR.* 的最大 patch
ALL_RELEASES=$(curl -s -H "Authorization: token $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" \
    "https://api.github.com/repos/$GITHUB_REPO/releases?per_page=100" 2>/dev/null || echo "[]")

MAX_PATCH=$(echo "$ALL_RELEASES" | python3 -c "
import sys, json, re
try:
    rels = json.load(sys.stdin)
except Exception:
    rels = []
patches = []
for r in rels:
    t = (r.get('tag_name') or '')
    m = re.match(r'^v(\d+)\.(\d+)\.(\d+)$', t)
    if not m:
        continue
    major, minor, patch = (int(x) for x in m.groups())
    if major == ${POM_MAJOR} and minor == ${POM_MINOR}:
        patches.append(patch)
if not patches:
    print('0')
else:
    print(max(patches))
" 2>/dev/null)

# 0.3 算新 tag
if [ -z "$MAX_PATCH" ]; then
    err "GitHub release 列表拉取/解析失败（python 输出空）"
    err "  请检查 \$GITHUB_TOKEN / \$GITHUB_REPO 是否正确，网络是否可达 api.github.com"
    exit 1
fi
NEW_PATCH=$((MAX_PATCH + 1))
TAG="v${POM_MAJOR}.${POM_MINOR}.${NEW_PATCH}"

if [ "$MAX_PATCH" = "0" ]; then
    info "GitHub 上无 v${POM_MAJOR}.${POM_MINOR}.* release → 首发 $TAG"
else
    info "GitHub 上 v${POM_MAJOR}.${POM_MINOR}.* 最大 patch: $MAX_PATCH → +1 → $TAG"
fi

info "目标 tag: $TAG"
info "目标仓库: $GITHUB_REPO"

# 准备 staging 目录
STAGE_DIR="$ROOT_DIR/.release-staging"
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR/assets"
info "Staging 目录: $STAGE_DIR"

# ============= 1. 清理旧的 target/.output =============
info "=== 1. 清理旧构建产物 ==="
# 注意：只清 target 下我们关心的 jar，不动 node_modules/.git
rm -rf backend/blog-app/target/blog-app.jar
rm -rf frontend/.output
info "已清理 blog-app/target/blog-app.jar 和 .output"

# ============= 2. 构建后端 jar =============
info "=== 2. 构建后端 fat jar ==="
cd "$ROOT_DIR/backend"
mvn -q clean package -DskipTests
cd "$ROOT_DIR"

JAR_PATH="backend/blog-app/target/blog-app.jar"
if [ ! -f "$JAR_PATH" ]; then
    err "构建后未找到 $JAR_PATH"
    exit 1
fi
JAR_SIZE=$(du -h "$JAR_PATH" | cut -f1)
info "后端 jar 已生成 ($JAR_SIZE)"

# ============= 3. 构建前端静态文件 =============
info "=== 3. 构建前端静态文件（nuxt generate）==="
cd "$ROOT_DIR/frontend"
if [ ! -d node_modules ]; then
    info "首次构建，安装 npm 依赖..."
    npm ci --prefer-offline
fi
# 关掉 Nuxt telemetry 询问("Are you interested in participating..." 会挡 EXPORT_DB 步骤)
NUXT_TELEMETRY_DISABLED=1 npm run generate
cd "$ROOT_DIR"

STATIC_DIR="frontend/.output/public"
if [ ! -f "$STATIC_DIR/index.html" ] && [ ! -f "$STATIC_DIR/200.html" ]; then
    err "前端构建后未找到 $STATIC_DIR/index.html 或 200.html"
    exit 1
fi
info "前端静态文件已生成"

# ============= 4. 打包所有资源到 staging =============
info "=== 4. 打包资源到 staging ==="
cp "$JAR_PATH"                                 "$STAGE_DIR/assets/blog-app.jar"
tar -czf "$STAGE_DIR/assets/frontend-static.tar.gz" -C "$STATIC_DIR" .
cp "$ROOT_DIR/docs/sql/schema-sqlite.sql"      "$STAGE_DIR/assets/schema-sqlite.sql"

# deploy-server.sh 是服务器端唯一能拉到的脚本，缺失就强制失败
if [ ! -f "$ROOT_DIR/docs/scripts/deploy-server.sh" ]; then
    err "$ROOT_DIR/docs/scripts/deploy-server.sh 不存在，无法发布"
    err "  （这是服务器端一键部署脚本，发布包里必须带）"
    exit 1
fi
cp "$ROOT_DIR/docs/scripts/deploy-server.sh"   "$STAGE_DIR/assets/deploy-server.sh"

# sqlite-import.sh 也是服务器端要的(IMPORT_DB=1 时 deploy-server 会调它解密导入)
if [ ! -f "$ROOT_DIR/docs/scripts/sqlite-import.sh" ]; then
    err "$ROOT_DIR/docs/scripts/sqlite-import.sh 不存在，无法发布"
    err "  （deploy-server.sh 在 IMPORT_DB=1 时会调它解密 .enc 导入，发布包里必须带）"
    exit 1
fi
cp "$ROOT_DIR/docs/scripts/sqlite-import.sh"   "$STAGE_DIR/assets/sqlite-import.sh"
chmod +x "$STAGE_DIR/assets/sqlite-import.sh"

# ============= 4.6 数据导出(可选,EXPORT_DB=1 触发)=============
# 把 dev blog.db 加密导出到 staging(随 release 发布)
# 默认关闭,避免每次发版都要敲密码
DUMP_FILE="$STAGE_DIR/assets/dev-blog-dump.sql.gz.enc"
if [ "${EXPORT_DB:-0}" = "1" ]; then
    info "=== 4.6 导出并加密 dev db ==="
    if [ ! -f "$ROOT_DIR/backend/blog.db" ]; then
        warn "backend/blog.db 不存在,跳过数据导出"
        warn "  (EXPORT_DB=1 要求 dev 库存在)"
    else
        # 调 sqlite-export.sh,密码从 stdin 读(交互式 read -s)
        # ⚠️ 必须用 process substitution 而不是 `| sed`,否则 pipe 会偷走 export 的 stdin,
        #    read -rs 拿空值 → 两次空值"不一致"循环死锁(屏幕看着像卡住)
        #    FORCE_EXPORT=1 让 export 跳过"非交互拒绝"门,密码必须操作员手输
        # page_view 不再用 --exclude 整表剔除（那会连 CREATE TABLE 一起丢，IMPORT_DB=1
        # 销毁式重建后应用读 page_view 的后台仪表盘 no such table 500）。
        # 改由 sqlite-export.sh 的 CLEAR_DATA_TABLES 处理：保留表结构、只清数据(同 admin_device)。
        FORCE_EXPORT=1 bash "$ROOT_DIR/docs/scripts/sqlite-export.sh" \
            "$ROOT_DIR/backend/blog.db" \
            -o "$DUMP_FILE" 2> >(sed 's/^/    /' >&2)
        EXPORT_RC=$?

        if [ "$EXPORT_RC" -ne 0 ]; then
            err "数据导出失败(密码错 / 取消 / 加密失败),退出码 $EXPORT_RC"
        fi
        if [ ! -f "$DUMP_FILE" ]; then
            err "数据导出失败(未产出 .enc),发布中止"
        fi
        info "✅ 数据已加密导出: dev-blog-dump.sql.gz.enc ($(du -h "$DUMP_FILE" | cut -f1))"
    fi
else
    info "EXPORT_DB=${EXPORT_DB:-0},跳过数据导出(默认关闭,设 EXPORT_DB=1 启用)"
fi

# 计算每个 asset 的 sha256(包含可选的 .enc)
info "生成 SHA256SUMS..."
cd "$STAGE_DIR/assets"
SUM_FILES="blog-app.jar frontend-static.tar.gz schema-sqlite.sql deploy-server.sh sqlite-import.sh"
[ -f dev-blog-dump.sql.gz.enc ] && SUM_FILES="$SUM_FILES dev-blog-dump.sql.gz.enc"
shasum -a 256 $SUM_FILES > SHA256SUMS 2>/dev/null || \
    sha256sum $SUM_FILES > SHA256SUMS
cd "$ROOT_DIR"

# 打 zip 冷部署包
BUNDLE="deploy-bundle-${TAG}.zip"
cd "$STAGE_DIR/assets"
ZIP_FILES="blog-app.jar frontend-static.tar.gz schema-sqlite.sql deploy-server.sh sqlite-import.sh SHA256SUMS"
[ -f dev-blog-dump.sql.gz.enc ] && ZIP_FILES="$ZIP_FILES dev-blog-dump.sql.gz.enc"
zip -q "$BUNDLE" $ZIP_FILES
cd "$ROOT_DIR"
mv "$STAGE_DIR/assets/$BUNDLE" "$STAGE_DIR/$BUNDLE"
BUNDLE_SIZE=$(du -h "$STAGE_DIR/$BUNDLE" | cut -f1)
info "冷部署包已生成: $BUNDLE ($BUNDLE_SIZE)"

# ============= 4.5 自愈：空仓补 README（GitHub 不允许空仓创建 release）=============
info "=== 4.5 检查仓库是否为空 ==="

# 判据：直接调 Contents API 拿 README.md（200=存在，其他=不存在或空仓）
README_EXISTS=$(curl -s -o /dev/null -w "%{http_code}" -H "Authorization: token $GITHUB_TOKEN" \
    "https://api.github.com/repos/$GITHUB_REPO/contents/README.md")

if [ "$README_EXISTS" != "200" ]; then
    info "仓库是空的（或无 README.md），自动补一个初始 README 解锁 release..."
    # ⚠️ base64 必须去换行！Linux 的 GNU base64 默认按 76 列折行（macOS 不折），
    # 折行产生的 \n 进了 JSON 字符串字面量就是非法 JSON，GitHub Contents API 400/422。
    # 通用兜底：base64 后 | tr -d '\n'，单行输出。
    README_CONTENT=$(printf '# my-blog-prov\n\nmy-blog 部署专用仓库。\n\n本仓只放 GitHub Release assets（jar / static / sql / 部署脚本），代码仓在 [YuanYii/my-blog](https://github.com/YuanYii/my-blog)。\n\n## 部署方式\n\n参见每个 release 的 assets：\n- `deploy-bundle-vX.Y.Z.zip` 冷部署包（推荐，1 个文件拉完）\n- `blog-app.jar` Spring Boot fat jar\n- `frontend-static.tar.gz` Nuxt 生成的静态文件\n- `schema-sqlite.sql` SQLite 初始化\n- `deploy-server.sh` 服务器端一键部署脚本\n- `SHA256SUMS` 校验文件\n\n服务器端：\n```bash\nexport GITHUB_REPO=YuanYii/my-blog-prov\nsudo ./deploy-server.sh v4.1.0\n```\n' | base64 | tr -d '\n')
    # 用 Contents API 创建 README.md（base64 编码 + commit message）
    INITIAL_PAYLOAD=$(printf '{"message":"chore: initial README (unlock release for empty repo)","content":"%s"}' "$README_CONTENT")
    HTTP_CODE=$(curl -s -o "$STAGE_DIR/init.json" -w "%{http_code}" -X PUT \
        -H "Authorization: token $GITHUB_TOKEN" \
        -H "Accept: application/vnd.github+json" \
        -H "Content-Type: application/json" \
        -d "$INITIAL_PAYLOAD" \
        "https://api.github.com/repos/$GITHUB_REPO/contents/README.md")
    if [ "$HTTP_CODE" = "201" ]; then
        info "✅ 初始 README.md 已创建（解锁 release）"
    else
        err "创建初始 README 失败 (HTTP $HTTP_CODE):"
        cat "$STAGE_DIR/init.json"
        exit 1
    fi
else
    info "仓库非空，跳过"
fi

# ============= 5. 创建 GitHub Release =============
info "=== 5. 创建 GitHub Release: $TAG ==="
API="https://api.github.com/repos/$GITHUB_REPO/releases"

# 双重 sanity check：0 步解析出的 tag 必须不存在；否则就是 bug
RELEASE_EXISTS=$(curl -s -o /dev/null -w "%{http_code}" \
    -H "Authorization: token $GITHUB_TOKEN" -H "Accept: application/vnd.github+json" \
    "$API/tags/$TAG")
if [ "$RELEASE_EXISTS" = "200" ]; then
    err "Release $TAG 已存在但 0 步没避让（脚本 bug 或并发冲突）"
    err "  请清理掉 GitHub 上的 $TAG 后重跑"
    exit 1
fi

# body 模板（每次都新建）
RELEASE_PAYLOAD=$(printf '{"tag_name":"%s","name":"my-blog %s","body":"my-blog v%s 部署包\\n\\n- blog-app.jar (Spring Boot fat jar)\\n- frontend-static.tar.gz (Nuxt 静态文件)\\n- schema-sqlite.sql (DB 初始化)\\n- deploy-server.sh (服务器端一键脚本)\\n- SHA256SUMS\\n- %s (冷部署合包)","draft":false,"prerelease":false}' \
    "$TAG" "$TAG" "$TAG" "$BUNDLE")

# 直接 POST 新建（避免 PATCH 旧 release 时的 404 陷阱）
HTTP_CODE=$(curl -s -o "$STAGE_DIR/release.json" -w "%{http_code}" -X POST \
    -H "Authorization: token $GITHUB_TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "Content-Type: application/json" \
    -d "$RELEASE_PAYLOAD" \
    "$API")

if [ "$HTTP_CODE" != "200" ] && [ "$HTTP_CODE" != "201" ]; then
    err "Release 创建/更新失败 (HTTP $HTTP_CODE):"
    cat "$STAGE_DIR/release.json"
    exit 1
fi
RELEASE_ID=$(python3 -c "import json;print(json.load(open('$STAGE_DIR/release.json'))['id'])")
UPLOAD_URL=$(python3 -c "import json;print(json.load(open('$STAGE_DIR/release.json'))['upload_url'])" | sed 's/{?name,label}//')
info "Release ready (id=$RELEASE_ID)"

# ============= 6. 上传 assets =============
info "=== 6. 上传 assets ==="
upload_one() {
    local fpath="$1"
    local fname="$2"
    info "上传 $fname ..."
    HTTP_CODE=$(curl -s -o "$STAGE_DIR/upload-$fname.json" -w "%{http_code}" \
        -H "Authorization: token $GITHUB_TOKEN" \
        -H "Content-Type: application/octet-stream" \
        --data-binary "@$fpath" \
        "$UPLOAD_URL?name=$fname")
    if [ "$HTTP_CODE" != "201" ]; then
        err "上传 $fname 失败 (HTTP $HTTP_CODE):"
        cat "$STAGE_DIR/upload-$fname.json"
        exit 1
    fi
    info "  ✅ $fname 上传成功"
}

upload_one "$STAGE_DIR/assets/blog-app.jar"             "blog-app.jar"
upload_one "$STAGE_DIR/assets/frontend-static.tar.gz"  "frontend-static.tar.gz"
upload_one "$STAGE_DIR/assets/schema-sqlite.sql"       "schema-sqlite.sql"
upload_one "$STAGE_DIR/assets/deploy-server.sh"        "deploy-server.sh"
upload_one "$STAGE_DIR/assets/sqlite-import.sh"        "sqlite-import.sh"
# 可选:加密的 db dump(EXPORT_DB=1 时存在)
[ -f "$STAGE_DIR/assets/dev-blog-dump.sql.gz.enc" ] && \
    upload_one "$STAGE_DIR/assets/dev-blog-dump.sql.gz.enc" "dev-blog-dump.sql.gz.enc"
upload_one "$STAGE_DIR/assets/SHA256SUMS"              "SHA256SUMS"
upload_one "$STAGE_DIR/$BUNDLE"                        "$BUNDLE"

# ============= 7. 收尾 =============
echo
info "=== ✅ 全部完成 ==="
info "Release:    https://github.com/$GITHUB_REPO/releases/tag/$TAG"
info "冷部署包:   $BUNDLE ($BUNDLE_SIZE)"
info "服务器端:   curl -L https://github.com/$GITHUB_REPO/releases/download/$TAG/$BUNDLE -o $BUNDLE"
echo
info "清理 staging 目录..."
rm -rf "$STAGE_DIR"
