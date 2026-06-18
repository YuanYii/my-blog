# HTTPS 配置文档（待实施）

> **状态**：方案已定，**代码改动留待后续**
> **日期**：2026-06-18
> **作用域**：my-blog v4.0.0+ 1C2G 无 docker 部署
> **唯一域名**：`blog.croeyai.cn`（暂不考虑多域名）

---

## 1. 背景与目标

my-blog 当前（v4.0.0）部署在 1C2G ECS 上，使用 SQLite + Redis + Spring Boot + Nuxt 全静态（nginx:alpine serve `.output/public/`）。当前 `docs/scripts/deploy-server.sh` 只配了 HTTP（80），**完全没有 HTTPS**，存在两个问题：

- **数据明文传输**：管理员登录 token、所有 API 请求全走 HTTP，任意中间人都能抓
- **浏览器安全策略收紧**：现代浏览器对 HTTP 网站持续降级（地址栏"Not Secure"、PWA/Service Worker 等功能受限），CORS 也更严格

**目标**：在保留 HTTP-only 一键部署能力（向后兼容）的前提下，增加可选的 HTTPS 流程：
- Let's Encrypt 自动签发证书（webroot 模式，零停机）
- 强制 80→443 跳转
- 证书自动续期（cron）
- 全流程集成进现有 `deploy-server.sh`，跟现有 `rebuild-static.sh` cron 错开时间

---

## 2. 设计决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 默认开关 | `ENABLE_HTTPS=0`（默认关） | certbot 交互多 + Let's Encrypt 速率限制，首次部署 HTTPS 不该是"必须项" |
| 证书签发方式 | `certbot certonly --webroot` | 利用现有 nginx 的 `/.well-known/acme-challenge/`，零停机申请 |
| 配置占位符处理 | `sed` 替换 `${NGINX_DOMAIN}` 等 | 跟 `deploy-server.sh` 现有风格一致（其他配置都是 sed 替换） |
| 80 端口行为 | 强制 301 跳 HTTPS | 安全 + SEO 标准做法 |
| 证书续期 | cron `30 3 * * *`（凌晨 3:30） | 错开现有 `rebuild-static.sh` 的 `0 3 * * *`（凌晨 3:00），避免并发 |
| HTTPS 模式下 step 9 行为 | **覆盖** `myblog.conf`，改写两个新文件 | 80 端口不能反代（必须留给 certbot 校验 + 301），反代必须走 443 |
| 多域支持 | **暂不支持**，只处理单域 `blog.croeyai.cn` | 简化为字符串，避免 awk/sed 切分坑 |
| 配置文件交付 | 作为 release asset 上传到 GitHub | 比 heredoc 嵌脚本更易维护、更易审计 |

---

## 3. 待改 / 新增文件清单

| 文件 | 类型 | 说明 |
|---|---|---|
| `docs/nginx/nginx-http.conf` | **新增** | 80 端口配置模板：certbot 校验路径 + 301 跳转 |
| `docs/nginx/nginx-https.conf` | **新增** | 443 端口配置模板：证书 + HSTS + 安全头 + 反代 + 静态文件 |
| `docs/scripts/publish-release.sh` | 修改 | step 4.7 打包 nginx 模板；step 6 上传；zip 包含；SHA256SUMS 包含 |
| `docs/scripts/deploy-server.sh` | 修改 | step 0 加 3 个变量；step 9.5 新增 HTTPS 流程；header 注释更新；收尾日志更新 |
| `docs/scripts/deploy.env.example` | 修改 | 加 HTTPS 配置说明 |

---

## 4. nginx 配置模板内容

### 4.1 `docs/nginx/nginx-http.conf`（新增）

```nginx
# =============================================================
# Nginx HTTP 配置（80 端口，HTTPS 模式下用）
# 挂载到 /etc/nginx/conf.d/myblog-http.conf
# HTTPS 配置见 nginx-https.conf（443 端口）
#
# 占位符（部署时 sed 替换）：
#   ${NGINX_DOMAIN}  域名（不带协议），如 blog.croeyai.cn
#   ${PUBLIC_PORT}   HTTP 端口，默认 80
# =============================================================

server {
    listen       ${PUBLIC_PORT} default_server;
    server_name  ${NGINX_DOMAIN};

    # ---------- certbot 校验路径（不能 301，否则校验失败） ----------
    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    # ---------- 其他全部 301 到 HTTPS ----------
    location / {
        return 301 https://$host$request_uri;
    }
}
```

### 4.2 `docs/nginx/nginx-https.conf`（新增）

```nginx
# =============================================================
# Nginx HTTPS 配置（443 端口）
# 挂载到 /etc/nginx/conf.d/myblog-https.conf
# 配套 nginx-http.conf 在同目录（80 配置）
#
# 占位符（部署时 sed 替换）：
#   ${NGINX_DOMAIN}   域名，如 blog.croeyai.cn
#   ${SERVER_PORT}    Spring Boot 端口，默认 8080
#   ${INSTALL_DIR}    部署目录，默认 /opt/myblog
# =============================================================

server {
    listen 443 ssl http2;
    server_name ${NGINX_DOMAIN};

    # ---------- SSL 证书（Let's Encrypt，部署时 sed 替换路径） ----------
    ssl_certificate     /etc/letsencrypt/live/${NGINX_DOMAIN}/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/${NGINX_DOMAIN}/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers 'ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305';
    ssl_prefer_server_ciphers off;
    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 1d;
    ssl_session_tickets off;

    # ---------- 安全头 ----------
    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Frame-Options           "SAMEORIGIN" always;
    add_header X-Content-Type-Options    "nosniff" always;
    add_header X-XSS-Protection          "1; mode=block" always;
    add_header Referrer-Policy           "strict-origin-when-cross-origin" always;

    # 客户端 body 大小（图片上传限制）
    client_max_body_size 20m;

    # ---------- gzip ----------
    gzip on;
    gzip_vary on;
    gzip_min_length 1024;
    gzip_types text/plain text/css text/xml text/javascript application/json application/javascript application/xml+rss image/svg+xml;

    # ---------- 限流（防刷） ----------
    limit_req_zone $binary_remote_addr zone=api_limit:10m  rate=20r/s;
    limit_req_zone $binary_remote_addr zone=login_limit:10m rate=5r/m;

    # ---------- 前端静态文件（v2.7.0 全静态） ----------
    root         ${INSTALL_DIR}/frontend;
    index        index.html;
    try_files    $uri $uri/ /200.html;

    # Nuxt 生成的 SPA fallback
    location = /200.html { add_header Cache-Control "no-cache"; }

    # 静态资源长缓存
    location ~* \.(js|css|woff2?|ttf|svg|png|jpg|jpeg|gif|ico|webp)$ {
        expires 7d;
        add_header Cache-Control "public, immutable";
    }

    # ---------- API 反代（限流 + 反代到 Spring Boot） ----------
    location /api/ {
        limit_req zone=api_limit burst=40 nodelay;
        proxy_pass         http://127.0.0.1:${SERVER_PORT};
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_http_version 1.1;
        proxy_set_header   Connection        "";
        proxy_read_timeout 60s;
        proxy_send_timeout 60s;
        proxy_buffering    off;
        client_max_body_size 20m;
    }

    # ---------- 登录接口单独限流 ----------
    location ~ ^/api/v1/auth/login {
        limit_req zone=login_limit burst=3 nodelay;
        proxy_pass         http://127.0.0.1:${SERVER_PORT};
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
    }

    # ---------- 健康检查 ----------
    location /healthz {
        access_log off;
        return 200 "ok\n";
        add_header Content-Type text/plain;
    }
}
```

---

## 5. `publish-release.sh` 改动（精确 diff）

### 5.1 step 4.7 新增（紧跟现有 step 4 之后，line 195 区域）

```bash
# ============= 4.7 打包 nginx 配置模板 =============
# 服务器端 HTTPS 流程(ENABLE_HTTPS=1 触发)需要这两个模板
# 配置文件来源:docs/nginx/ 是设计文档,这里是部署物料
info "=== 4.7 打包 nginx 配置模板 ==="
if [ ! -f "$ROOT_DIR/docs/nginx/nginx-http.conf" ]; then
    err "$ROOT_DIR/docs/nginx/nginx-http.conf 不存在,无法发布"
    err "  (HTTPS 流程依赖这个模板)"
    exit 1
fi
if [ ! -f "$ROOT_DIR/docs/nginx/nginx-https.conf" ]; then
    err "$ROOT_DIR/docs/nginx/nginx-https.conf 不存在,无法发布"
    err "  (HTTPS 流程依赖这个模板)"
    exit 1
fi
cp "$ROOT_DIR/docs/nginx/nginx-http.conf"   "$STAGE_DIR/assets/nginx-http.conf"
cp "$ROOT_DIR/docs/nginx/nginx-https.conf"  "$STAGE_DIR/assets/nginx-https.conf"
info "已加入 nginx-http.conf + nginx-https.conf"
```

### 5.2 SUM_FILES 和 ZIP_FILES 更新

```bash
# 原: SUM_FILES="blog-app.jar frontend-static.tar.gz schema-sqlite.sql deploy-server.sh sqlite-import.sh"
SUM_FILES="blog-app.jar frontend-static.tar.gz schema-sqlite.sql deploy-server.sh sqlite-import.sh nginx-http.conf nginx-https.conf"

# 原: ZIP_FILES="blog-app.jar frontend-static.tar.gz schema-sqlite.sql deploy-server.sh sqlite-import.sh SHA256SUMS"
ZIP_FILES="$SUM_FILES SHA256SUMS"
```

### 5.3 upload_one 列表更新

```bash
# 在 sqlite-import.sh 后,dev-blog-dump.sql.gz.enc 前(如果有)或 SHA256SUMS 前插入:
upload_one "$STAGE_DIR/assets/nginx-http.conf"   "nginx-http.conf"
upload_one "$STAGE_DIR/assets/nginx-https.conf"  "nginx-https.conf"
```

---

## 6. `deploy-server.sh` 改动（精确 diff）

### 6.1 header 注释（line 16 后）加 HTTPS 变量说明

```bash
#   OPEN_FIREWALL=1              设为 1 自动 firewalld/ufw 放行 PUBLIC_PORT(默认 1)
#   ENABLE_HTTPS=0               设为 1 启用 Let's Encrypt HTTPS 证书 + 80→443 跳转(默认 0)
#                                 开启需同时设 HTTPS_DOMAIN 和 HTTPS_EMAIL
#   HTTPS_DOMAIN=blog.croeyai.cn 域名(不带协议,单域场景,多域暂不支持)
#   HTTPS_EMAIL=your@email.com   Let's Encrypt 通知邮箱(过期/吊销提醒)
```

### 6.2 step 0 参数解析加 3 个变量（line 43 后）

```bash
ENABLE_HTTPS="${ENABLE_HTTPS:-0}"
HTTPS_DOMAIN="${HTTPS_DOMAIN:-}"
HTTPS_EMAIL="${HTTPS_EMAIL:-}"
```

### 6.3 step 0 校验段加 ENABLE_HTTPS 配套校验（line 67 后）

```bash
# ENABLE_HTTPS=1 时必填 HTTPS_DOMAIN + HTTPS_EMAIL
if [ "$ENABLE_HTTPS" = "1" ]; then
    if [ -z "$HTTPS_DOMAIN" ] || [ -z "$HTTPS_EMAIL" ]; then
        err "ENABLE_HTTPS=1 requires HTTPS_DOMAIN and HTTPS_EMAIL"
        err "  HTTPS_DOMAIN=blog.croeyai.cn  (no protocol)"
        err "  HTTPS_EMAIL=your@email.com"
        exit 1
    fi
    # 域名不能含协议
    if echo "$HTTPS_DOMAIN" | grep -qE '^https?://'; then
        err "HTTPS_DOMAIN must NOT contain protocol, got: $HTTPS_DOMAIN"
        exit 1
    fi
    # 域名不能含路径
    if echo "$HTTPS_DOMAIN" | grep -q '/'; then
        err "HTTPS_DOMAIN must NOT contain path, got: $HTTPS_DOMAIN"
        exit 1
    fi
fi
```

### 6.4 **新增 step 9.5**（紧跟现有 step 9 之后，line 575 前）

```bash
# ============= 9.5 HTTPS 配置(可选,ENABLE_HTTPS=1 触发) =============
# DEPLOY_MODE=data 跳过(nginx 配置不变)
if [ "$DEPLOY_MODE" = "data" ]; then
    info "=== 9.5 HTTPS config ==="
    info "    DEPLOY_MODE=data, skipping HTTPS config"
elif [ "$ENABLE_HTTPS" != "1" ]; then
    info "=== 9.5 HTTPS config ==="
    info "    ENABLE_HTTPS=0, skipping HTTPS (HTTP-only deploy)"
else
    info "=== 9.5 HTTPS config (ENABLE_HTTPS=1) ==="

    # 9.5.1 准备 certbot webroot 目录
    mkdir -p /var/www/certbot
    chown -R www-data:www-data /var/www/certbot 2>/dev/null || \
        chown -R nginx:nginx /var/www/certbot 2>/dev/null || true

    # 9.5.2 装 certbot(已装跳过)
    if ! command -v certbot >/dev/null 2>&1; then
        info "Installing certbot..."
        if command -v apt-get >/dev/null 2>&1; then
            DEBIAN_FRONTEND=noninteractive apt-get update -qq && \
                DEBIAN_FRONTEND=noninteractive apt-get install -y -qq certbot
        elif command -v yum >/dev/null 2>&1; then
            yum install -y -q certbot
        else
            err "Neither apt-get nor yum found, please install certbot manually"
            exit 1
        fi
    else
        info "certbot already installed: $(certbot --version 2>&1 | head -1)"
    fi

    # 9.5.3 写 HTTP 配置(只放 certbot 校验 + 301 跳转,不反代)
    #      这一步**覆盖** step 9 写的完整配置 —— HTTPS 模式下 80 端口不能反代
    HTTP_CONF="/etc/nginx/conf.d/myblog-http.conf"
    info "Writing HTTP-only nginx config to $HTTP_CONF ..."
    sed -e "s|\${NGINX_DOMAIN}|$HTTPS_DOMAIN|g" \
        -e "s|\${PUBLIC_PORT}|$PUBLIC_PORT|g" \
        "$TMP_DIR/nginx-http.conf" > "$HTTP_CONF"
    # 移除 step 9 写的"完整版"配置(避免和 HTTP-only 冲突)
    rm -f /etc/nginx/conf.d/myblog.conf
    info "  Removed old /etc/nginx/conf.d/myblog.conf (full config replaced by HTTP-only+HTTPS)"

    # 9.5.4 先 reload nginx 让 certbot 校验路径生效
    nginx -t && systemctl reload nginx

    # 9.5.5 申请证书(webroot 模式,80 端口已跑不影响用户)
    info "Requesting Let's Encrypt certificate for: $HTTPS_DOMAIN"
    info "(non-interactive: --agree-tos --no-eff-email)"
    if certbot certonly --webroot -w /var/www/certbot \
        -d "$HTTPS_DOMAIN" \
        --email "$HTTPS_EMAIL" \
        --agree-tos --no-eff-email --non-interactive 2>&1 | tee /tmp/certbot.log; then
        CERT_PATH="/etc/letsencrypt/live/$HTTPS_DOMAIN"
        if [ ! -f "$CERT_PATH/fullchain.pem" ]; then
            err "certbot reported success but cert not found at $CERT_PATH"
            err "  check /tmp/certbot.log"
            exit 1
        fi
        info "  ✅ Certificate issued: $CERT_PATH"
    else
        err "certbot failed, see /tmp/certbot.log"
        err "  常见原因:"
        err "  1. 域名未解析到本机公网 IP(DNS A 记录)"
        err "  2. 80 端口被防火墙/安全组拦截"
        err "  3. Let's Encrypt 速率限制(同域名每周 50 张)"
        exit 1
    fi

    # 9.5.6 写 HTTPS 配置
    HTTPS_CONF="/etc/nginx/conf.d/myblog-https.conf"
    info "Writing HTTPS nginx config to $HTTPS_CONF ..."
    sed -e "s|\${NGINX_DOMAIN}|$HTTPS_DOMAIN|g" \
        -e "s|\${SERVER_PORT}|$SERVER_PORT|g" \
        -e "s|\${INSTALL_DIR}|$INSTALL_DIR|g" \
        "$TMP_DIR/nginx-https.conf" > "$HTTPS_CONF"

    # 9.5.7 校验 + reload
    nginx -t
    systemctl reload nginx
    info "[OK] HTTPS enabled, HTTP traffic auto-redirects to HTTPS"

    # 9.5.8 更新 ENV_FILE 的 CORS_ORIGINS(加 https 域名)
    if [ -f "$ENV_FILE" ]; then
        HTTPS_ORIGIN="https://$HTTPS_DOMAIN"
        if ! grep -qF "$HTTPS_ORIGIN" "$ENV_FILE"; then
            sed -i.bak "s|^CORS_ORIGINS=.*|CORS_ORIGINS=$HTTPS_ORIGIN|" "$ENV_FILE"
            # 重启服务让新 ENV 生效
            systemctl restart myblog
            info "[OK] CORS_ORIGINS updated: $HTTPS_ORIGIN (service restarted)"
        else
            info "CORS_ORIGINS already contains $HTTPS_ORIGIN, skipping"
        fi
    fi

    # 9.5.9 证书自动续期 cron(错开 rebuild-static.sh 的 0 3 * * *)
    if ! crontab -l 2>/dev/null | grep -q "certbot renew"; then
        info "Adding certbot auto-renewal cron (daily 03:30, 避开 rebuild-static.sh 的 03:00)..."
        ( crontab -l 2>/dev/null; echo "30 3 * * * certbot renew --quiet --post-hook 'systemctl reload nginx'" ) | crontab -
        info "  ✅ cron installed"
    else
        info "certbot renew cron already exists, skipping"
    fi
fi  # ENABLE_HTTPS block
```

### 6.5 step 13 收尾日志（line 635 后）加 HTTPS 状态

```bash
info "  Access:        http://<server-ip>:$PUBLIC_PORT"
if [ "$ENABLE_HTTPS" = "1" ]; then
    info "  HTTPS:         https://$HTTPS_DOMAIN (HTTP→HTTPS 301 forced)"
    info "  Cert renew:    certbot renew (auto cron installed, daily 03:30)"
fi
```

---

## 7. `deploy.env.example` 改动（追加）

```bash
# ===== HTTPS / Let's Encrypt (可选,单域) =====
# 开启 HTTPS 前必须先做两件事:
#   1. 域名 DNS A 记录解析到 ECS 公网 IP(等 5-10 分钟生效)
#   2. 阿里云/腾讯云安全组放行 80 和 443 端口
#
# ENABLE_HTTPS=0         默认关闭,HTTP-only 部署
# ENABLE_HTTPS=1         启用 Let's Encrypt 自动签发证书 + 80→443 强制跳转
#
# HTTPS_DOMAIN=blog.croeyai.cn
# 注意:不要带 https:// 前缀,不要带路径
# 当前只支持单域场景(暂不考虑多域)
#
# HTTPS_EMAIL=your@email.com
# Let's Encrypt 会在证书快过期时发邮件提醒(90 天 → 30 天前)
```

---

## 8. 完整部署示例

### 8.1 HTTPS 首次部署

```bash
# 前提: 域名 blog.croeyai.cn 的 DNS A 记录已解析到 ECS 公网 IP,安全组已放行 80+443
export GITHUB_REPO=yourname/your-repo
export ENABLE_HTTPS=1
export HTTPS_DOMAIN=blog.croeyai.cn
export HTTPS_EMAIL=you@email.com
sudo ./deploy-server.sh v4.0.1
```

### 8.2 HTTP-only 部署（向后兼容）

```bash
sudo ./deploy-server.sh v4.0.1
```

### 8.3 验证

```bash
# HTTP 自动跳转 HTTPS
curl -I http://blog.croeyai.cn
# 期望: HTTP/1.1 301 Moved Permanently + Location: https://blog.croeyai.cn

# HTTPS 正常 + HSTS
curl -I https://blog.croeyai.cn
# 期望: HTTP/2 200 + Strict-Transport-Security: max-age=31536000

# 证书信息
echo | openssl s_client -connect blog.croeyai.cn:443 -servername blog.croeyai.cn 2>/dev/null | openssl x509 -noout -dates -subject
# 期望: 显示 cert 有效期(~90 天)和 CN=blog.croeyai.cn
```

---

## 9. 风险与缓解

| 风险 | 缓解 |
|---|---|
| Let's Encrypt 速率限制（同域名 50 张/周，重复签失败） | 脚本失败时**保留** `/tmp/certbot.log`，提示手动排查；不强制重试 |
| DNS 解析未生效 → certbot 校验失败 | 部署前用 `dig +short blog.croeyai.cn` 自检；脚本里提示但不阻塞（DNS 可能刚加） |
| 80 端口被安全组拦截 → certbot 校验超时 | 阿里云/腾讯云控制台放行 80；脚本失败时**给排查清单**（DNS / 安全组 / 速率限制） |
| `sed -i.bak` 在 CentOS 5 极老版本不识别 `-i` 参数 | 系统最低 Ubuntu 18.04 / CentOS 7（deploy-server.sh 已声明），GNU sed 全部支持 |
| cron `30 3` 和 rebuild-static `0 3` 并发冲突 | 故意错开 30 分钟；两个任务都是 CPU/IO 轻量，且 certbot renew 多数天是 no-op |
| HTTPS 切换瞬间老 token 因 secure cookie 失效（如果有） | 当前 token 存 localStorage（非 cookie），**不受影响**；未来若改 cookie 需同步加 `Secure` flag |
| `/var/www/certbot` 权限问题 | `chown -R www-data:www-data` 或 `nginx:nginx`（按发行版）；脚本里两套都尝试 |
| 覆盖 step 9 的 `myblog.conf` 后回滚麻烦 | GitHub release 里保留旧版 deploy-server.sh，回滚 `sudo ./deploy-server.sh v4.0.0` 自动恢复 |

---

## 10. 后续可选增强（本次不做）

- 多域名支持（`HTTPS_DOMAIN="a.com b.com"`，certbot 一次签多域）
- HTTP/3 (QUIC) 实验性启用
- OCSP Stapling 配置
- 证书透明度（CT）日志监控
- 自签证书 fallback（IP 直访场景）

---

## 11. 实施步骤（待开工）

1. 在 `docs/nginx/` 下新建 `nginx-http.conf` + `nginx-https.conf`
2. 修改 `docs/scripts/publish-release.sh`：
   - 加 step 4.7 打包
   - 更新 SUM_FILES / ZIP_FILES / upload_one
3. 修改 `docs/scripts/deploy-server.sh`：
   - header 注释
   - step 0 参数 + 校验
   - 新增 step 9.5 HTTPS 全流程
   - step 13 收尾日志
4. 修改 `docs/scripts/deploy.env.example`：追加 HTTPS 配置说明
5. 测试：
   - `bash docs/scripts/verify-sqlite.sh` 仍然通过（HTTP 模式无回归）
   - 本地 dry-run：手动把 step 9.5 的 sed/certbot 命令在容器里跑一遍（无证书签发，只看 nginx -t 通过）
6. 写 changelog：`docs/changelogs/YYYY-MM-DD-vX.Y.Z-https-optional.md`
