# 2026-06-17 BUG-002: 部署脚本会覆盖 jar 里的 application-prod.yml

## TL;DR

`scripts/deploy-server.sh` 用 `--spring.config.location` 启动 jar，触发 Spring Boot "替换模式"——**jar 里的 `application.yml` + `application-prod.yml` 全部失效**，脚本生成的残废 yml 是唯一配置。结果是生产部署**没有 JWT secret**（`${JWT_SECRET}` 解析失败或拿空串），没有 CORS，没有上传目录配置，没有日志文件路径。

修复后：
- 改用 `--spring.config.additional-location`（**叠加**）让 jar 里的 prod.yml 真正生效
- 新增 `/etc/myblog/myblog.env`（systemd EnvironmentFile），让 `${JWT_SECRET}` / `${CORS_ORIGINS}` 等占位符能取到值
- 首次部署自动生成 32 字节强随机 JWT secret；升级部署保留已有 env（不踢用户下线）

---

## 问题复现

### 现象 1：Spring Boot 配置替换 vs 叠加

Spring Boot 启动参数 `--spring.config.location` 和 `--spring.config.additional-location` 行为完全不同：

| 写法 | 行为 | 后果 |
|---|---|---|
| `--spring.config.location=file:/opt/myblog/application.yml` | **替换**：只加载这个文件 | jar 里的 application.yml + application-prod.yml 失效 |
| `--spring.config.additional-location=file:/opt/myblog/application.yml` | **叠加**：在 jar 配置之上补 | jar 配置全保留，外部文件只补差异 |

修复前 deploy-server.sh 用的是 `location`（替换模式），所以脚本生成的 `application.yml` 成了"唯一配置源"——但脚本生成的 yml 本身就缺一大堆 key。

### 现象 2：脚本生成的 `application.yml` 缺斤少两

修复前脚本写的 56 行 yml，对比 jar 里的 `application-prod.yml`（113 行）：

| jar 里有 | 脚本生成的有 |
|---|---|
| `blog.jwt.secret` | ❌ 缺 |
| `blog.cors.allowed-origins` | ❌ 缺 |
| `blog.upload.local.dir` | ❌ 缺 |
| `blog.upload.local.url-prefix` | ❌ 缺 |
| `blog.swagger.enabled: false` | ❌ 缺 |
| `logging.file.name: /var/log/blog/app.log` | ❌ 缺（日志写到 stdout） |
| `logging.pattern.file` | ❌ 缺 |
| `server.compression.*` | ❌ 缺（无响应压缩） |
| `springdoc.api-docs.enabled: false` | ❌ 缺 |
| `spring.jackson.date-format` / `time-zone` | ❌ 缺 |
| `spring.servlet.multipart.max-request-size: 50MB` | 写的 20MB（不一致） |

### 现象 3：动态参数完全没接

jar 里 `application-prod.yml` 大量使用 `${}` 占位符：

```yaml
blog:
  jwt:
    secret: ${JWT_SECRET}              # 启动报 "Could not resolve placeholder"
  cors:
    allowed-origins: ${CORS_ORIGINS:https://yourname.com,...}  # 拿默认值（不是你的真域名！）
  upload:
    local:
      dir: ${UPLOAD_DIR:/data/uploads}
spring:
  datasource:
    url: jdbc:sqlite:${SQLITE_PATH:/data/blog.db}
  redis:
    host: ${REDIS_HOST:localhost}
    port: ${REDIS_PORT:6379}
    password: ${REDIS_PASSWORD:}
```

systemd 服务文件**完全没有 `Environment=` / `EnvironmentFile=`**，这些占位符没人喂。后果：
- 严格模式（`@Validated`）：`JWT_SECRET` 启动直接失败
- 宽松模式：拿到空串 / 占位符默认值（生产用 `yourname.com` 当 CORS 域名 = 前端跨域被拒）

### 现象 4：跟 AGENTS.md §9 安全红线冲突

AGENTS.md 明确写"**JWT_SECRET 部署时必须设 32+ 位强随机**"，但 deploy-server.sh 完全没有生成机制。docs/阿里云部署方案.md 第 228 行也列了同样的要求，但脚本没实现。

---

## 修复

### 改动 1：deploy-server.sh step 7 — 脚本生成的 yml 砍到最小

**before**（覆盖 56 行，包含 servlet.multipart、redis、server.port 等——但**缺 JWT/CORS/upload/logging**）：

```bash
cat > "$APP_YML" <<EOF
server:
  port: $SERVER_PORT
  ...
spring:
  profiles:
    active: prod
  datasource:
    url: jdbc:sqlite:$DB_FILE
    driver-class-name: org.sqlite.JDBC
  redis:
    host: 127.0.0.1
    port: 6379
  servlet:
    multipart:
      max-file-size: 20MB
      max-request-size: 20MB
spring.datasource.hikari:
  maximum-pool-size: 1
EOF
```

**after**（只覆盖"部署期差异"——端口/DB 路径/Redis，JWT/CORS/upload/logging 全交给 jar 里的 prod.yml）：

```bash
cat > "$APP_YML" <<EOF
spring:
  profiles:
    active: prod
  datasource:
    url: jdbc:sqlite:$DB_FILE
    driver-class-name: org.sqlite.JDBC
  redis:
    host: 127.0.0.1
    port: 6379
server:
  port: $SERVER_PORT
spring.datasource.hikari:
  maximum-pool-size: 1
EOF
```

### 改动 2：deploy-server.sh step 7.5 — 新增，自动生成 /etc/myblog/myblog.env

```bash
ENV_FILE="/etc/myblog/myblog.env"
if [ -f "$ENV_FILE" ]; then
    info "检测到已有 $ENV_FILE，保留现有配置（不覆盖，避免 JWT secret 变化）"
else
    # openssl rand -hex 32 生成 32 字节强随机
    JWT_SECRET_GENERATED=$(openssl rand -hex 32)
    cat > "$ENV_FILE" <<EOF
SQLITE_PATH=$DB_FILE
JWT_SECRET=$JWT_SECRET_GENERATED
CORS_ORIGINS=https://yourname.com,https://www.yourname.com
UPLOAD_DIR=$INSTALL_DIR/uploads
REDIS_HOST=127.0.0.1
REDIS_PORT=6379
REDIS_PASSWORD=
REDIS_DB=0
EOF
    chmod 600 "$ENV_FILE"
    chown myblog:myblog "$ENV_FILE"
fi
```

关键设计：
- **幂等**：env 文件已存在 → 保留不动（避免每次升级重启后 JWT secret 变化踢所有用户下线）
- **强随机**：`openssl rand -hex 32` → 64 字符 hex（32 字节），满足 `JwtUtil.validateSecret()` 的 32 字节下限
- **安全**：`chmod 600` + `chown myblog:myblog`（不让其他用户读 secret）

### 改动 3：deploy-server.sh step 8 — systemd 加 EnvironmentFile + 改 location 参数

**before**：
```ini
[Service]
ExecStart=/usr/bin/java ... -jar $INSTALL_DIR/blog-app.jar --spring.config.location=$APP_YML
```

**after**：
```ini
[Service]
ExecStart=/usr/bin/java ... -jar $INSTALL_DIR/blog-app.jar --spring.config.additional-location=$APP_YML
EnvironmentFile=-$ENV_FILE
```

`EnvironmentFile=` 前的 `-` 表示"文件不存在不报错"（首次部署后这个文件一定存在，符号降级到兜底容错）。

### 改动 4：新增 scripts/deploy-server.env.example 模板

给运维提前准备 env 的入口（不用等 deploy-server.sh 跑完再改 CORS），用法：

```bash
# 1. 改 CORS_ORIGINS + JWT_SECRET
$EDITOR scripts/deploy-server.env.example

# 2. 部署前上传到服务器
scp scripts/deploy-server.env.example myblog@<ecs-ip>:/tmp/myblog.env
ssh myblog@<ecs-ip> 'sudo mv /tmp/myblog.env /etc/myblog/myblog.env && sudo chmod 600 /etc/myblog/myblog.env'

# 3. 跑 deploy-server.sh —— 检测到 env 已存在，保留
sudo ./deploy-server.sh v2.7.0
```

或者更省事的"全自动"路径：直接跑 deploy-server.sh，让它首先生成 env，再 ssh 上去改 CORS_ORIGINS：

```bash
sudo ./deploy-server.sh v2.7.0
# 看到 "⚠️  部署完成后请编辑 /etc/myblog/myblog.env，把 CORS_ORIGINS 改成你的真实域名"
ssh myblog@<ecs-ip>
$EDITOR /etc/myblog/myblog.env
sudo systemctl restart myblog
```

---

## 验证

部署后验证三件事：

```bash
# 1. JWT secret 真的生效了（启动日志没 "Could not resolve placeholder" 报错）
journalctl -u myblog -n 50 | grep -E "placeholder|ERROR"

# 2. /actuator/env 能看到 JWT_SECRET 解析后的实际值
curl -s http://localhost:8080/actuator/env/blog.jwt.secret | jq .

# 3. CORS 真的允许你的域名
curl -sI -H "Origin: https://yourname.com" http://localhost:8080/api/v1/articles/categories | grep -i "access-control-allow-origin"
```

期望：
- (1) 无 ERROR，placeholder 全部 resolve
- (2) 返回 `"value":"<64字符hex>"`
- (3) `Access-Control-Allow-Origin: https://yourname.com`

---

## 受影响范围

- **之前用旧 deploy-server.sh 部署的生产环境**：必须手动 `ssh 上去` 补两步
  1. `sudo bash -c 'openssl rand -hex 32 > /tmp/jwt && cat /tmp/jwt'` 拿 secret
  2. `sudo mkdir -p /etc/myblog && sudo tee /etc/myblog/myblog.env` 写 env（含刚生成的 JWT_SECRET）
  3. `sudo systemctl restart myblog` 重启
- **新部署**：deploy-server.sh 自动完成
- **AGENTS.md §4.5 / §4.6 / §8**：本次变更不影响
- **publish-release.sh**：只对 deploy-server.sh 做"必须存在 + 拷贝"两步，本次修改不破坏发布链路

---

## 文件清单

| 路径 | 变更 |
|---|---|
| `scripts/deploy-server.sh` | M（step 7 / 7.5 / 8 三个区块，约 +60 / -15 行） |
| `scripts/deploy-server.env.example` | A（新增 38 行，运维模板） |
