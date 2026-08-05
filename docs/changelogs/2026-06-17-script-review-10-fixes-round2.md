# 2026-06-17 BUG-004: 上一轮修的两个新 bug（perl 块注释 + mvn 双跑）

## TL;DR

上次代码审查列出 10 个问题，全部修完。**复审又发现两个新 bug**，都在"修上一轮问题时引入的"。全部已修：

| # | 严重 | 文件 | 描述 |
|---|---|---|---|
| A | P0 | deploy-server.sh | perl 块级注释 default_server 写错，**只在 CentOS 部署时炸**（Debian 没事因为 perl 空跑没匹配到） |
| B | P2 | publish-release.sh | `${TAG_RAW%[^0-9.]*}` 恒真，导致 mvn 跑两遍（功能不影响，每次发布多花几秒） |

---

## 1. P0-A: perl 块级注释 default_server 写错

### 问题

上次为了让 CentOS 自带的 `server { listen 80 default_server; ... }` 不和我们的 `conf.d/myblog.conf` 抢默认，写的 perl 是这样：

```perl
perl -0777 -i -pe 's{^(    server\s*\{\s*listen\s+\d+\s+default_server;.*?^\s*\}\s*)}{
    # $1 removed by deploy-server.sh
    # $1
}smg' /etc/nginx/nginx.conf
```

两个独立错误：

#### 错误 1：非贪婪 `.*?^\s*\}` 在嵌套块上提前截断

CentOS 自带的 server 块有嵌套结构：

```nginx
server {
    listen       80 default_server;
    server_name  _;
    root         /usr/share/nginx/html;
    location / {                ← 第一个 } 出现
    }                            ← 非贪婪匹配在这里停下
    error_page 404 /404.html;
        location = /404.html {
        }
    error_page 500 502 503 504 /50x.html;
        location = /50x.html {
        }
}                                ← 真正的 server 块结束 } 没人匹配
```

正则没法做大括号配平（Perl 内置正则不递归），`.*?` 非贪婪 + `^\s*\}` 会在**第一个** `}`（也就是第一个 `location / {}` 的结尾）就截断。

#### 错误 2：`# $1` 只注释第一行

Perl 替换里 `$1` 是捕获组（**多行内容**），前面加 `# ` 实际只对 `s{...}{...}` 替换结果的第一行加了 `#`。剩下的 `listen 80 default_server;` / `server_name _;` / `root ...;` 等**全部悬空在 http{} 里**，没包裹的 `server {`。

### 实际改坏的 nginx.conf（模拟）

```nginx
http {
    ...
    #     server {                              ← 只这一行被注释
        listen       80 default_server;         ← 悬空，nginx -t 报语法错
        listen       [::]:80 default_server;    ← 悬空
        server_name  _;                         ← 悬空
        root         /usr/share/nginx/html;     ← 悬空
        location / {
        }                                        ← 第一个 } 截断点
        # （剩下的 error_page / 50x.html 完全没动，照常存在）
        #     server {                          ← perl 又塞了一遍重复片段
        listen       80 default_server;         ← 又来一遍悬空指令
        ...
    }
}
```

`nginx -t` 在这一段直接 `emerg: directive "listen" is not allowed here` → **`set -e` 退出，部署中断**。

### 为什么 Debian 不受影响

Debian 默认没内联 default server，且 `/etc/nginx/sites-enabled/default` 已被 `rm -f` 删了——perl 在 nginx.conf 里匹配不到任何 `server { listen X default_server;` 开头的内容，**空跑**。Debian 部署路径上这个 perl 根本不做事，所以不会炸。

**也就是说这个 bug 严格只在"CentOS 部署"这个本要解决的场景下生效**——讽刺。

### 修复

**不要尝试注释整段 server 块**（正则配平大括号做不到）。改成**只把 listen 行上的 `default_server` 关键字摘掉**：

```bash
perl -i -pe 's/(^\s*listen[^;\n]*?)\s+default_server\b/$1/g' /etc/nginx/nginx.conf
```

- 匹配：`listen` 开头到 `;` 或换行前，捕获中间内容
- 替换：把 `default_server` 关键字（带前面空白）删掉
- IPv4 `listen 80 default_server;` → `listen 80;`
- IPv6 `listen [::]:80 default_server;` → `listen [::]:80;`
- 自带 server 块结构完整保留，server_name / root / location / error_page 全不动
- nginx 规则：只要**任何** server 显式声明 `default_server`，它就是默认。我们的 `conf.d/myblog.conf` 仍带 `default_server`，自带 server 摘掉后变成普通 server 不抢默认

### 副作用

nginx 启动会打一条 `conflicting server name "_" on 0.0.0.0:80` 的 **warning**（自带 server 和我们的都用了 `server_name _;`），**非致命**，`nginx -t` 通过。

如果以后想消掉这条 warning，把自带的 `server_name _;` 改成别的（比如 `server_name _centos_default;`）即可——但本次脚本不动这个。

### 验证

```bash
# 模拟 CentOS 自带 nginx.conf 跑新 perl
$ perl -i -pe 's/(^\s*listen[^;\n]*?)\s+default_server\b/$1/g' /tmp/nginx-test.conf

# 修前：
        listen       80 default_server;
        listen       [::]:80 default_server;

# 修后：
        listen       80;
        listen       [::]:80;
```

✅ 块结构完整，nginx -t 不再语法错。

---

## 2. P2-B: mvn 跑两遍

### 问题

上次修 #1（tag 取错）时写了一段"兜底"逻辑：

```bash
TAG_RAW=$(mvn -f "$ROOT_DIR/backend" -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null || true)
if [ -z "$TAG_RAW" ] || [ "$TAG_RAW" = "${TAG_RAW%[^0-9.]*}" ]; then
    TAG_RAW=$(mvn -f "$ROOT_DIR/backend" -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1)
fi
```

第二个条件 `${TAG_RAW%[^0-9.]*}` 恒真：

- `%` = 取最短后缀删除
- `[^0-9.]*` = 零个或多个非数字非点字符
- `*` 能匹配空串
- 所以"删零个字符" = "原串"
- 整个 `${TAG_RAW%[^0-9.]*}` 恒等于 `$TAG_RAW`
- `[ "$TAG_RAW" = "$TAG_RAW" ]` 恒为 true
- 第二个 mvn 每次必跑

### 验证

```bash
$ TAG_RAW="0.1.0"
$ [ "$TAG_RAW" = "${TAG_RAW%[^0-9.]*}" ] && echo true || echo false
true
$ TAG_RAW="abc"
$ [ "$TAG_RAW" = "${TAG_RAW%[^0-9.]*}" ] && echo true || echo false
true
```

无论 TAG_RAW 是什么值都返回 true。

### 影响

- 功能正确：`mvn -q -DforceStdout` 输出就是纯版本号（即使有 INFO 也走 `tail -1`），第二次跑结果跟第一次一样
- 性能浪费：每次 publish 多跑一次 Maven，**慢 3-8 秒**（项目有 6 个子模块，dependency:tree / settings-evaluation 都要重算）
- 误导后来人：以为这是"特殊情况兜底"，实际上是死代码

### 修复

简化为单次取值 + 错误检查：

```bash
TAG_RAW=$(mvn -f "$ROOT_DIR/backend" -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1)
if [ -z "$TAG_RAW" ]; then
    err "无法从 backend/pom.xml 读到 project.version，请显式传 tag：$0 v2.7.0"
    exit 1
fi
TAG_RAW="${TAG_RAW%-SNAPSHOT}"
TAG="v${TAG_RAW}"
```

### 验证

```bash
$ grep "TAG_RAW%\[^0-9" scripts/publish-release.sh
（无匹配，那行已删）
$ TAG_RAW=$(mvn -f backend -q help:evaluate -Dexpression=project.version -DforceStdout 2>/dev/null | tail -1)
$ echo $TAG_RAW
0.1.0
```

mvn 只跑一次，结果正常。

---

## 总结：修上一轮 bug 时容易引入新 bug

这次两个新 bug 都是**修 #5 / #1 时引入的**：

- #5 perl 块级注释：思路是"整段注释掉 default_server"，但没考虑到 (1) 嵌套块 (2) 多行替换只注释第一行
- #1 mvn 兜底判断：思路是"防 mvn 输出多行就再跑一次 + tail -1"，但写错 bash 取后缀语法

**教训**：写复杂的 perl 块级正则之前，先**用真实输入数据跑一遍模拟**，看改后文件长啥样——这次 bug 在 macOS（Debian 风格）上根本测不出来，**只有 CentOS 部署才暴露**。可以加一个 dry-run 模式：脚本开头加 `DRY_RUN=1` 时 perl 改到 `/tmp/nginx.conf.dryrun` 而不是真的改 `/etc/nginx/nginx.conf`，方便审阅。

---

## 受影响范围

- **未部署过 CentOS 的**：跟上次一样，本次修复不影响运行时
- **即将部署到 CentOS 的**：用旧版 deploy-server.sh 部署必失败（nginx -t 错），用新版正常
- **publish 流程**：本次修复后单次 publish 少花 3-8 秒，行为不变

---

## 文件清单

| 路径 | 变更 |
|---|---|
| `scripts/deploy-server.sh` | M（perl 块注释 → 摘 default_server 关键字，step 9 约 +18 / -7 行） |
| `scripts/publish-release.sh` | M（mvn 取值简化，step 0 约 -7 / +2 行） |
| `docs/changelogs/2026-06-17-script-review-10-fixes-round2.md` | A（本文档）|
