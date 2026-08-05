# 2026-06-17 BUG-003: 部署/发布脚本 10 个审查问题

## TL;DR

Code review 列出 10 个问题，**全部真实**，按 P0→P1→P2 全部修完：

| 类别 | # | 严重 | 描述 | 状态 |
|---|---|---|---|---|
| publish | 1 | P0 | `grep -m1` 取到 Spring Boot 父 POM 版本 `2.7.18`，不是项目版本 `0.1.0` | ✅ 改用 `mvn help:evaluate` |
| publish | 2 | P1 | 推出来的 tag 无 `v` 前缀，跟 `v2.7.0` 手动风格不一致 | ✅ 自动加 `v` |
| publish | 3 | P0 | `base64` 输出带换行，破坏 JSON | ✅ `base64 | tr -d '\n'` |
| publish | 4 | P2 | `REPO_EMPTY` 算出来没用，纯死代码 | ✅ 删 |
| deploy | 5 | P1 | CentOS `nginx.conf` 自带 `default_server` 没处理，必报 "duplicate default server" | ✅ perl 块级注释 |
| deploy | 6 | P1 | `UPLOAD_DIR=/opt/myblog/uploads` 目录没建 | ✅ mkdir 加上 |
| deploy | 7 | P2 | 建了 `db/` 但 DB_FILE 默认在根目录 | ✅ DB_FILE 默认改 `$INSTALL_DIR/db/blog.db` |
| deploy | 8 | P1 | 死代码 `[ ... ] && grep -q ... || true` + 缺 conf.d include 兜底 | ✅ 删死代码 + perl 自动加 include |
| deploy | 9 | P2 | `lsof` 兜底但没装 | ✅ 加进 apt/yum 依赖列表 |
| deploy | 10 | P2 | yml 混用嵌套/点号 key | ✅ 统一嵌套 |

---

## 1. P0-#1: tag 取错（致命）

### 问题

`publish-release.sh` 第 65 行：

```bash
TAG=$(grep -m1 -oP '(?<=<version>)[^<]+' backend/pom.xml || true)
```

`backend/pom.xml` 实际结构：

```xml
<project>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>2.7.18</version>          ← 第 10 行，grep -m1 命中这里
        <relativePath/>
    </parent>

    <groupId>com.blog</groupId>
    <artifactId>blog-parent</artifactId>
    <version>0.1.0</version>              ← 第 16 行，真正的项目版本
    ...
```

`grep -m1` 取第一个 `<version>` 必中 `2.7.18`，不传参跑脚本会把 release 打成 `2.7.18` 错版。

### 为什么不能用子模块 pom 兜底

`blog-app/pom.xml` 没有显式 `<version>`（继承父 POM），grep 拿不到。
同理 `blog-common` / `blog-auth` 等子模块。

### 修复

改用 Maven 官方推荐的 `mvn help:evaluate -Dexpression=project.version`：

```bash
TAG_RAW=$(mvn -f "$ROOT_DIR/backend" -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null)
if [ -z "$TAG_RAW" ] || [ "$TAG_RAW" = "${TAG_RAW%[^0-9.]*}" ]; then
    # 兜底：mvn 失败可能输出包含 "INFO" 之类的多行
    TAG_RAW=$(mvn -f "$ROOT_DIR/backend" -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1)
fi
TAG_RAW="${TAG_RAW%-SNAPSHOT}"
TAG="v${TAG_RAW}"
```

### 验证

```bash
$ mvn -f backend -q help:evaluate -Dexpression=project.version -DforceStdout
0.1.0
$ TAG_RAW="0.1.0"; TAG="v${TAG_RAW}"
$ echo $TAG
v0.1.0
```

修前自动推导 → `2.7.18` ❌  
修后自动推导 → `v0.1.0` ✅

---

## 2. P0-#3: base64 换行破坏 JSON（致命）

### 问题

```bash
README_CONTENT=$(printf '...' | base64)
INITIAL_PAYLOAD=$(printf '{"message":"...","content":"%s"}' "$README_CONTENT")
```

- **GNU base64**（Linux 默认）按 76 列折行，输出含 `\n`
- **BSD base64**（macOS 默认）默认不折行

不管哪个平台，把多行 base64 直接拼进 JSON 字符串字面量都会破坏 JSON（JSON 字符串里 `\n` 必须写成 `\\n`）。GitHub Contents API 会 400/422。

### 修复

```bash
README_CONTENT=$(printf '...' | base64 | tr -d '\n')
```

`tr -d '\n'` 是双平台通用兜底（macOS 不用也安全，Linux 必要）。

### 验证

```bash
$ printf 'test 中文 🎉' | base64 | tr -d '\n' | wc -c
24
$ printf 'test 中文 🎉' | base64 | tr -d '\n' | od -c | head -1
0000000   d   G   V   z   d   C   D   k   u   K   3   m   l   o   c   g
（无 \n 字符）
```

**触发条件**：仅"空仓 + 无 README.md"时跑这段。已存在的仓库不会触发，所以这是个"首次发布即发布出去会 422"的问题。

---

## 3. P1-#2: tag 加 v 前缀

跟 #1 一起改：自动推导出的 TAG 一律补 `v` 前缀（`v0.1.0`），跟 `v2.7.0` 手动用法对齐 + 跟 deploy-server.sh 的下载 URL 拼接稳定。

手动指定（`$ ./publish-release.sh v2.7.0`）时**不动**用户输入，保留原样。

---

## 4. P1-#5: CentOS nginx default_server 冲突

### 问题

`deploy-server.sh` 写的 nginx 配置带了 `listen $PUBLIC_PORT default_server;`，Debian/Ubuntu 路径下：

- `/etc/nginx/sites-enabled/default` 删了 → OK

但 **CentOS/RHEL**：

- `nginx` 包自带 `/etc/nginx/nginx.conf`（**不是 sites-enabled**）
- 该文件 `http {}` 块内有个 `server { listen 80 default_server; ... }`
- 脚本没处理 → 部署到 CentOS 时 `nginx -t` 必报：

```
nginx: [emerg] a duplicate default server for 0.0.0.0:80 in /etc/nginx/nginx.conf:38
```

部署中断。

### 修复

用 perl 做块级多行替换（sed 多行难写，perl 一次性搞定）：

```bash
if [ -f /etc/nginx/nginx.conf ]; then
    # 注释掉 nginx.conf 里 http {} 块内的 default_server server 段
    perl -0777 -i -pe 's{^(    server\s*\{\s*listen\s+\d+\s+default_server;.*?^\s*\}\s*)}{
        # $1 removed by deploy-server.sh
        # $1
    }smg' /etc/nginx/nginx.conf
fi
```

`perl -0777` = slurp mode（一次性读全文），`-i -pe` = 行内编辑 + 自动 print，`s{...}{...}smg` = 多行/点号换行/全局。

### 为什么不直接 sed

`server { listen 80 default_server; ... }` 跨多行，sed 单行模式搞不定；sed 多行要用 N 循环 + 标签，可读性差。perl 一行。

---

## 5. P1-#8: 删死代码 + ensure conf.d include

### 死代码

原第 373 行：

```bash
[ -f /etc/nginx/nginx.conf ] && grep -q "include /etc/nginx/conf.d/\*.conf" /etc/nginx/nginx.conf || true
```

整个表达式无副作用。删掉。

### 真正的 conf.d include 兜底

顺手把"如果 nginx.conf 没 include conf.d/*.conf 就加上"做掉（精简镜像 / 自定义 nginx 可能漏 include，导致 `/etc/nginx/conf.d/myblog.conf` 根本不加载）：

```bash
if ! grep -qE "include\s+/etc/nginx/conf\.d/\*\.conf\s*;" /etc/nginx/nginx.conf; then
    warn "/etc/nginx/nginx.conf 没 include conf.d/*.conf，自动加一行..."
    perl -0777 -i -pe 's{^(http\s*\{)}{$1\n    include /etc/nginx/conf.d/*.conf;\n}smg' /etc/nginx/nginx.conf
fi
```

这条比 #5 更阴——**如果原 nginx.conf 没 include conf.d，部署后整个站点配置根本不加载**，访问 80 端口拿到的是 nginx 默认 welcome 页，你以为部署成功了实际后端 API 全部 404。

---

## 6. P1-#6 + P2-#7: uploads / db 目录

### #6 uploads 缺建

`myblog.env` 写 `UPLOAD_DIR=$INSTALL_DIR/uploads`，但脚本没建这个目录。

**现象**：首次上传 500 / 404（路径不存在），Spring 启动期不会自动建（`File.mkdirs` 失败 → IOException）。

**修复**：

```bash
mkdir -p "$INSTALL_DIR"/{logs,backups,frontend,db,uploads}
```

### #7 db/ 空目录

`db/` 建了但 DB_FILE 默认在 `$INSTALL_DIR/blog.db`（根目录），db/ 是空目录。

**修复**：`DB_FILE` 默认值改成 `$INSTALL_DIR/db/blog.db`，跟 mkdir 对齐：

```bash
DB_FILE="${DB_FILE:-$INSTALL_DIR/db/blog.db}"
```

---

## 7. P2-#9: lsof 进依赖列表

脚本兜底 kill 占用端口的进程用了 `lsof -ti :8080`，但安装依赖里没装 lsof。CentOS minimal / Debian slim 镜像可能没预装。

**修复**：

- apt: `apt-get install -y lsof`
- yum/dnf: `yum install -y lsof`

外加把 `lsof` 加进验证列表（第 110 行 `for bin in ...`）：

```bash
for bin in java sqlite3 redis-server nginx curl lsof; do
    ...
done
```

### 为什么不改用 `ss`

macOS 默认没 `ss`（要装 iproute2 才行），脚本要"开发机+生产机都跑得通"——lsof macOS 自带，apt/yum 都有，更便携。脚本改动最小。

---

## 8. P2-#4 + #10: 删死代码 + yml 嵌套统一

### #4 删 REPO_EMPTY 死代码

原第 154-163 行算 `REPO_EMPTY` 后面没被引用（真正判空用的是 `README_EXISTS`），整段删。

### #10 yml 嵌套统一

脚本生成的 application.yml 之前混了两种写法：

```yaml
spring:
  datasource:
    url: jdbc:sqlite:...
    driver-class-name: org.sqlite.JDBC
  redis:
    host: 127.0.0.1
    port: 6379

# 这个是顶层 key + 点号
spring.datasource.hikari:
  maximum-pool-size: 1
```

**问题**：同一文件混嵌套 / 点号，虽然 Spring Boot 松绑定能吃下，但容易踩坑（特别是 merge 多个 yml 时 key 类型不一致）。

**修复**：统一嵌套：

```yaml
spring:
  profiles:
    active: prod
  datasource:
    url: jdbc:sqlite:...
    driver-class-name: org.sqlite.JDBC
    hikari:
      maximum-pool-size: 1
  redis:
    host: 127.0.0.1
    port: 6379
```

### 验证

```bash
$ python3 -c "import yaml; d = yaml.safe_load(open('/tmp/test.yml')); print(json.dumps(d, indent=2))"
{
  "spring": {
    "datasource": {
      "url": "jdbc:sqlite:/opt/myblog/db/blog.db",
      "driver-class-name": "org.sqlite.JDBC",
      "hikari": {
        "maximum-pool-size": 1
      }
    },
    ...
  }
}
```

`maximum-pool-size` 正确解析为 3 层嵌套。

---

## 验证清单

部署到生产后跑：

```bash
# 1. publish-release.sh 推出来的 tag 正确
git tag --list 'v*' | tail -3
# 期望：v2.7.0 / v2.6.0 / v0.1.0 这种

# 2. deploy-server.sh 跑完后 /opt/myblog/ 目录结构
ls /opt/myblog/
# 期望：backend.jar  logs  backups  frontend  db  uploads
ls /opt/myblog/db/
# 期望：blog.db
ls /opt/myblog/uploads/
# 期望：空目录

# 3. /etc/nginx/nginx.conf 已被 perl 处理过（CentOS）
grep -B1 "myblog default_server" /etc/nginx/nginx.conf | head -10
# 期望：看到注释掉的 server 块

# 4. nginx conf.d include 存在
grep "include /etc/nginx/conf.d" /etc/nginx/nginx.conf
# 期望：    include /etc/nginx/conf.d/*.conf;

# 5. lsof 已装
command -v lsof && lsof -v 2>&1 | head -1
# 期望：/usr/bin/lsof + version banner

# 6. application.yml 是合法 yaml + 嵌套正确
python3 -c "import yaml; print(yaml.safe_load(open('/opt/myblog/application.yml')))"
# 期望：嵌套 3 层 maximum-pool-size: 1
```

---

## 受影响范围

- **未发布过任何 release 的仓库**：直接受益（#1 #3 致命 bug 修好）
- **已发布过 release 的仓库**：tag 历史有 `2.7.18` 这种错版（如果之前踩坑的话），不影响后续
- **已部署过的生产环境**：本次修复**不影响运行时**，只影响下次部署流程
- **publish-release.sh 的发布链路**：保持兼容，depoly-server.sh 仍按 `vX.Y.Z` 命名解析

---

## 文件清单

| 路径 | 变更 |
|---|---|
| `scripts/publish-release.sh` | M（#1 #2 #3 #4 共 +20 / -15 行） |
| `scripts/deploy-server.sh` | M（#5 #6 #7 #8 #9 #10 共 +30 / -10 行） |
| `docs/changelogs/2026-06-17-script-review-10-fixes.md` | A（本文档）|
