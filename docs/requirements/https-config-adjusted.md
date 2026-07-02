# HTTPS 配置方案（调整版）

> **状态**：方案调整完成，待实施
> **基于**：`https配置文档.md`（原始方案 v4.0.0），针对 v5.0.0 代码库实际状态修正
> **日期**：2026-07-02

---

## 1. 原方案问题清单

| # | 问题 | 影响 | 严重度 |
|---|---|---|---|
| P1 | `deploy-server.sh` **不读 `deploy.env`** | `ENABLE_HTTPS` / `HTTPS_DOMAIN` / `HTTPS_EMAIL` 写在 deploy.env 里但脚本不消费，必须命令行传 | 🔴 功能阻塞 |
| P2 | `docs/nginx/` **目录不存在** | nginx 模板文件无处放置 | 🔴 功能阻塞 |
| P3 | `deploy-server.sh` **无 step 9.5** | HTTPS 流程完全未实现 | 🔴 功能阻塞 |
| P4 | `publish-release.sh` **不打包 nginx 模板** | 服务器端拉不到模板文件 | 🔴 功能阻塞 |
| P5 | step 9.5 用 `systemctl reload nginx` **不兼容 LOCAL_SIM** | 本地 docker 模式下 nginx reload 失败 | 🟠 兼容性 |
| P6 | HTTPS 模式下 **default_server 冲突** | HTTP-only 配置带 `listen 80 default_server`，与 step 9 的 myblog.conf 冲突 | 🟠 部署失败 |
| P7 | CORS_ORIGINS **未随 HTTPS 更新** | HTTPS 启用后前端跨域被拒 | 🟡 运行时 |
| P8 | **CDN 场景未覆盖** | CDN 终结 SSL 时 origin 不需要 HTTPS，但当前方案无法灵活切换 | 🟡 扩展性 |

---

## 2. 调整后的方案

### 2.1 问题 P1：deploy-server.sh 读取 deploy.env

**现状**：`deploy-server.sh` 只读命令行环境变量，不 source `deploy.env`。`deploy.env` 是给 `publish-release.sh` 用的。

**调整**：在 `deploy-server.sh` 的 step 0 参数解析之前，增加 deploy.env 加载（与 `publish-release.sh` 同样模式：先 snapshot 命令行值，source 完再 restore）。

```bash
# ============= 0. 参数解析 =============
# 加载 deploy.env（可选，命令行 export 优先）
SCRIPT_DIR_DEPLOY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR_DEPLOY/deploy.env" ]; then
    _CL_EXPORT_DB="${EXPORT_DB:-}"; _CL_IMPORT_DB="${IMPORT_DB:-}"
    _CL_DEPLOY_MODE="${DEPLOY_MODE:-}"; _CL_ENABLE_HTTPS="${ENABLE_HTTPS:-}"
    _CL_HTTPS_DOMAIN="${HTTPS_DOMAIN:-}"; _CL_HTTPS_EMAIL="${HTTPS_EMAIL:-}"
    set -a; source "$SCRIPT_DIR_DEPLOY/deploy.env"; set +a
    [ -n "$_CL_EXPORT_DB" ] && EXPORT_DB="$_CL_EXPORT_DB"
    [ -n "$_CL_IMPORT_DB" ] && IMPORT_DB="$_CL_IMPORT_DB"
    [ -n "$_CL_DEPLOY_MODE" ] && DEPLOY_MODE="$_CL_DEPLOY_MODE"
    [ -n "$_CL_ENABLE_HTTPS" ] && ENABLE_HTTPS="$_CL_ENABLE_HTTPS"
    [ -n "$_CL_HTTPS_DOMAIN" ] && HTTPS_DOMAIN="$_CL_HTTPS_DOMAIN"
    [ -n "$_CL_HTTPS_EMAIL" ] && HTTPS_EMAIL="$_CL_HTTPS_EMAIL"
    unset _CL_EXPORT_DB _CL_IMPORT_DB _CL_DEPLOY_MODE _CL_ENABLE_HTTPS _CL_HTTPS_DOMAIN _CL_HTTPS_EMAIL
fi
```

**影响范围**：`deploy-server.sh` 一处改动，约 15 行。

---

### 2.2 问题 P2：创建 docs/nginx/ 目录

新增两个文件：

**`docs/nginx/nginx-http.conf`**（80 端口，HTTPS 模式下用）

```nginx
# 占位符：${NGINX_DOMAIN}、${PUBLIC_PORT}
server {
    listen       ${PUBLIC_PORT} default_server;
    server_name  ${NGINX_DOMAIN};

    # certbot 校验路径（不能 301）
    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }

    # 其他全部 301 到 HTTPS
    location / {
        return 301 https://$host$request_uri;
    }
}
```

**`docs/nginx/nginx-https.conf`**（443 端口）

```nginx
# 占位符：${NGINX_DOMAIN}、${SERVER_PORT}、${INSTALL_DIR}
server {
    listen 443 ssl http2;
    server_name ${NGINX_DOMAIN};

    ssl_certificate     /etc/letsencrypt/live/${NGINX_DOMAIN}/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/${NGINX_DOMAIN}/privkey.pem;
    ssl_protocols       TLSv1.2 TLSv1.3;
    ssl_ciphers 'ECDHE-ECDSA-AES256-GCM-SHA384:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-CHACHA20-POLY1305:ECDHE-RSA-CHACHA20-POLY1305';
    ssl_prefer_server_ciphers off;
    ssl_session_cache   shared:SSL:10m;
    ssl_session_timeout 1d;
    ssl_session_tickets off;

    add_header Strict-Transport-Security "max-age=31536000; includeSubDomains" always;
    add_header X-Frame-Options           "SAMEORIGIN" always;
    add_header X-Content-Type-Options    "nosniff" always;
    add_header X-XSS-Protection          "1; mode=block" always;
    add_header Referrer-Policy           "strict-origin-when-cross-origin" always;

    client_max_body_size 20m;

    gzip on;
    gzip_vary on;
    gzip_min_length 1024;
    gzip_types text/plain text/css text/xml text/javascript application/json application/javascript application/xml+rss image/svg+xml;

    root         ${INSTALL_DIR}/frontend;
    index        index.html;
    try_files    $uri $uri/index.html /200.html;

    location = /200.html { add_header Cache-Control "no-cache"; }

    location ~* \.(js|css|woff2?|ttf|svg|png|jpg|jpeg|gif|ico|webp)$ {
        expires 7d;
        add_header Cache-Control "public, immutable";
    }

    location ^~ /uploads/ {
        alias ${INSTALL_DIR}/uploads/;
        expires 7d;
        add_header Cache-Control "public, immutable";
        try_files $uri =404;
    }

    location /api/ {
        proxy_pass         http://127.0.0.1:${SERVER_PORT};
        proxy_set_header   Host              $host;
        proxy_set_header   X-Real-IP         $remote_addr;
        proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
        proxy_set_header   X-Forwarded-Proto $scheme;
        proxy_set_header   X-Forwarded-Host  $http_host;
        proxy_set_header   Origin             "";
        proxy_http_version 1.1;
        proxy_set_header   Connection        "";
        proxy_read_timeout 60s;
        client_max_body_size 20m;
    }

    location /healthz {
        access_log off;
        return 200 "ok\n";
        add_header Content-Type text/plain;
    }
}
```

**与原方案差异**：
- `try_files` 用 `$uri $uri/index.html /200.html`（与 step 9 一致，修复了 BUG-001）
- 加了 `X-Forwarded-Host $http_host` 和 `Origin ""`（与 step 9 一致）
- 去掉了原方案的 `limit_req_zone`（与 step 9 保持一致，原方案多加了限流但 step 9 没有）
- 保留 HSTS + 安全头

---

### 2.3 问题 P3：deploy-server.sh 新增 step 9.5

插入位置：step 9 之后、step 10 之前。

关键调整点（对比原方案）：

**a) LOCAL_SIM 兼容（解决 P5）**

```bash
# nginx reload 必须区分 LOCAL_SIM（supervisorctl vs systemctl）
if [ "$LOCAL_SIM" = "1" ]; then
    nginx -t && supervisorctl restart nginx
else
    nginx -t && systemctl reload nginx
fi
```

**b) default_server 冲突处理（解决 P6）**

HTTPS 模式下，step 9 已写入 `myblog.conf`（含 `listen 80 default_server`）。step 9.5 的 HTTP-only 配置也带 `listen 80 default_server` → 冲突。

解决方案：**step 9.5 先删除 step 9 写的 `myblog.conf`**，再写两个新配置。与原方案一致。

```bash
# HTTPS 模式下 80 端口不能反代（必须留给 certbot + 301）
# 删除 step 9 写的完整配置，替换为 HTTP-only + HTTPS 两个文件
rm -f /etc/nginx/conf.d/myblog.conf
```

**c) 完整 step 9.5 逻辑**

```bash
# ============= 9.5 HTTPS 配置(可选,ENABLE_HTTPS=1 触发) =============
if [ "$ENABLE_HTTPS" != "1" ]; then
    info "=== 9.5 HTTPS config ==="
    info "    ENABLE_HTTPS=0, skipping HTTPS (HTTP-only deploy)"
else
    info "=== 9.5 HTTPS config (ENABLE_HTTPS=1) ==="

    # 前提校验
    if [ -z "${HTTPS_DOMAIN:-}" ] || [ -z "${HTTPS_EMAIL:-}" ]; then
        err "ENABLE_HTTPS=1 requires HTTPS_DOMAIN and HTTPS_EMAIL"
        exit 1
    fi
    if echo "$HTTPS_DOMAIN" | grep -qE '^https?://|/'; then
        err "HTTPS_DOMAIN must NOT contain protocol or path, got: $HTTPS_DOMAIN"
        exit 1
    fi

    # 9.5.1 certbot webroot 目录
    mkdir -p /var/www/certbot
    chown -R www-data:www-data /var/www/certbot 2>/dev/null || \
        chown -R nginx:nginx /var/www/certbot 2>/dev/null || true

    # 9.5.2 安装 certbot
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
    fi

    # 9.5.3 写 HTTP-only 配置（覆盖 step 9 的 myblog.conf）
    rm -f /etc/nginx/conf.d/myblog.conf
    HTTP_CONF="/etc/nginx/conf.d/myblog-http.conf"
    sed -e "s|\${NGINX_DOMAIN}|$HTTPS_DOMAIN|g" \
        -e "s|\${PUBLIC_PORT}|$PUBLIC_PORT|g" \
        "$TMP_DIR/nginx-http.conf" > "$HTTP_CONF"

    # 9.5.4 reload nginx 让 certbot 校验路径生效
    if [ "$LOCAL_SIM" = "1" ]; then
        nginx -t && supervisorctl restart nginx
    else
        nginx -t && systemctl reload nginx
    fi

    # 9.5.5 申请证书（webroot 模式）
    info "Requesting Let's Encrypt certificate for: $HTTPS_DOMAIN"
    if certbot certonly --webroot -w /var/www/certbot \
        -d "$HTTPS_DOMAIN" \
        --email "$HTTPS_EMAIL" \
        --agree-tos --no-eff-email --non-interactive 2>&1 | tee /tmp/certbot.log; then
        CERT_PATH="/etc/letsencrypt/live/$HTTPS_DOMAIN"
        if [ ! -f "$CERT_PATH/fullchain.pem" ]; then
            err "certbot reported success but cert not found at $CERT_PATH"
            exit 1
        fi
        info "  Certificate issued: $CERT_PATH"
    else
        err "certbot failed, see /tmp/certbot.log"
        err "  Common causes: DNS not resolved, port 80 blocked, rate limit"
        exit 1
    fi

    # 9.5.6 写 HTTPS 配置
    HTTPS_CONF="/etc/nginx/conf.d/myblog-https.conf"
    sed -e "s|\${NGINX_DOMAIN}|$HTTPS_DOMAIN|g" \
        -e "s|\${SERVER_PORT}|$SERVER_PORT|g" \
        -e "s|\${INSTALL_DIR}|$INSTALL_DIR|g" \
        "$TMP_DIR/nginx-https.conf" > "$HTTPS_CONF"

    # 9.5.7 校验 + reload
    if [ "$LOCAL_SIM" = "1" ]; then
        nginx -t && supervisorctl restart nginx
    else
        nginx -t && systemctl reload nginx
    fi
    info "[OK] HTTPS enabled, HTTP auto-redirects to HTTPS"

    # 9.5.8 更新 CORS_ORIGINS（加 https 域名）
    if [ -f "$ENV_FILE" ]; then
        HTTPS_ORIGIN="https://$HTTPS_DOMAIN"
        if ! grep -qF "$HTTPS_ORIGIN" "$ENV_FILE"; then
            sed -i.bak "s|^CORS_ORIGINS=.*|CORS_ORIGINS=$HTTPS_ORIGIN|" "$ENV_FILE"
            if [ "$LOCAL_SIM" = "1" ]; then
                supervisorctl restart myblog
            else
                systemctl restart myblog
            fi
            info "[OK] CORS_ORIGINS updated: $HTTPS_ORIGIN (service restarted)"
        fi
    fi

    # 9.5.9 证书自动续期 cron
    if ! crontab -l 2>/dev/null | grep -q "certbot renew"; then
        info "Adding certbot auto-renewal cron (daily 03:30)..."
        ( crontab -l 2>/dev/null; echo "30 3 * * * certbot renew --quiet --post-hook 'systemctl reload nginx'" ) | crontab -
    fi
fi
```

---

### 2.4 问题 P4：publish-release.sh 打包 nginx 模板

在 step 4（打包资源）之后新增 step 4.7：

```bash
# ============= 4.7 打包 nginx 配置模板 =============
info "=== 4.7 Packing nginx templates ==="
if [ ! -f "$ROOT_DIR/docs/nginx/nginx-http.conf" ] || [ ! -f "$ROOT_DIR/docs/nginx/nginx-https.conf" ]; then
    err "docs/nginx/nginx-http.conf or nginx-https.conf not found"
    exit 1
fi
cp "$ROOT_DIR/docs/nginx/nginx-http.conf"   "$STAGE_DIR/assets/nginx-http.conf"
cp "$ROOT_DIR/docs/nginx/nginx-https.conf"  "$STAGE_DIR/assets/nginx-https.conf"
info "  nginx-http.conf + nginx-https.conf added"
```

更新 SUM_FILES、ZIP_FILES、upload_one：

```bash
# SUM_FILES 追加
SUM_FILES="blog-app.jar frontend-static.tar.gz schema-sqlite.sql deploy-server.sh sqlite-import.sh sqlite-export.sh blog-backup.sh nginx-http.conf nginx-https.conf"

# upload_one 追加
upload_one "$STAGE_DIR/assets/nginx-http.conf"   "nginx-http.conf"
upload_one "$STAGE_DIR/assets/nginx-https.conf"  "nginx-https.conf"
```

---

### 2.5 问题 P7：CORS_ORIGINS 更新

已在 step 9.5.8 中覆盖（见 2.3 节）。原方案只处理首次部署，调整后每次 `ENABLE_HTTPS=1` 都会检查并更新。

---

### 2.6 问题 P5 补充：LOCAL_SIM + certbot

LOCAL_SIM（Docker 容器）内没有真实域名解析，certbot webroot 校验必然失败。

**调整**：step 9.5 开头增加 LOCAL_SIM 判断，容器内只写 nginx 配置（验证语法），跳过 certbot：

```bash
if [ "$LOCAL_SIM" = "1" ]; then
    warn "LOCAL_SIM=1: skipping certbot (no real DNS in container)"
    warn "  Only writing nginx config templates for syntax validation"
    # 只做 sed 替换 + nginx -t，不申请证书
    # （没有证书文件，443 配置 nginx -t 会报错——这是预期的，仅验证 80 端口配置）
else
    # 完整 certbot 流程
fi
```

---

### 2.7 问题 P8：CDN 场景兼容

**调整**：在 `deploy.env` 中新增 `CDN_SSL` 开关：

```bash
# CDN_SSL=0       默认关闭（origin 直接终结 SSL）
# CDN_SSL=1       CDN 终结 SSL，origin 只监听 80 端口（不申请 Let's Encrypt 证书）
#                  适用于：Cloudflare / 阿里云 CDN 等"灵活"或"完全" HTTPS 模式
#                  需同时设 CDN_ORIGIN_PORT（CDN 回源端口，默认 80）
```

`deploy-server.sh` step 9.5 中增加判断：

```bash
if [ "${CDN_SSL:-0}" = "1" ]; then
    info "CDN_SSL=1: CDN terminates SSL, origin serves HTTP only"
    info "  Skipping certbot, nginx stays on port 80"
    # 只需确保 80 端口正常监听（step 9 已配好），不需要 443 配置
else
    # 原有的 certbot + HTTPS 流程
fi
```

这样三种场景都能覆盖：
1. **无 CDN，origin 终结 SSL**：`ENABLE_HTTPS=1, CDN_SSL=0`（默认 HTTPS 流程）
2. **有 CDN，CDN 终结 SSL**：`ENABLE_HTTPS=0, CDN_SSL=1`（origin 只监听 80）
3. **无 CDN，HTTP-only**：`ENABLE_HTTPS=0, CDN_SSL=0`（当前默认行为）

---

## 3. 实施文件清单

| 文件 | 改动类型 | 说明 |
|---|---|---|
| `docs/nginx/nginx-http.conf` | **新增** | 80 端口配置模板（certbot 校验 + 301） |
| `docs/nginx/nginx-https.conf` | **新增** | 443 端口配置模板（SSL + 安全头 + 反代） |
| `scripts/deploy-server.sh` | 修改 | step 0 加 deploy.env 加载 + HTTPS 变量；step 9.5 新增 HTTPS 流程 |
| `scripts/publish-release.sh` | 修改 | step 4.7 打包 nginx 模板；SUM_FILES / upload_one 更新 |
| `scripts/deploy.env` | 修改 | 新增 CDN_SSL 变量 |

---

## 4. 验证计划

### 4.1 静态验证（代码审查）

- [ ] `deploy-server.sh` 能正确 source `deploy.env` 且命令行优先
- [ ] step 9.5 的 nginx reload 区分 LOCAL_SIM
- [ ] step 9.5 先删 `myblog.conf` 再写两个新文件（无 default_server 冲突）
- [ ] `publish-release.sh` 的 SUM_FILES 包含 nginx 模板
- [ ] nginx 模板的 `try_files` / `X-Forwarded-Host` / `Origin` 与 step 9 一致

### 4.2 动态验证（本地 docker 模拟）

```bash
# 1. 构建 release 包（含 nginx 模板）
cd /path/to/my-blog
GITHUB_REPO=test/test ./scripts/publish-release.sh  # 会失败（无 GITHUB_TOKEN），但能验证打包逻辑

# 2. 本地 docker 模拟 HTTPS（无真实证书，只验证 nginx 配置写入）
# 在 myblog-sim 容器内手动执行 step 9.5 的 sed 命令，验证 nginx -t 通过
docker exec -it myblog-sim bash
# 手动写 nginx-http.conf + nginx-https.conf（无证书文件时 nginx -t 会报错，这是预期的）
# 重点验证：sed 替换后配置语法正确 + 无 default_server 冲突
```

### 4.3 生产验证（ECS 真实环境）

```bash
# 前提：DNS A 记录已解析，安全组已放行 80+443
sudo ENABLE_HTTPS=1 HTTPS_DOMAIN=blog.croeyai.cn HTTPS_EMAIL=your@email.com \
    DEPLOY_MODE=full ./deploy-server.sh v5.0.0

# 验证
curl -I http://blog.croeyai.cn          # → 301 + Location: https://
curl -I https://blog.croeyai.cn         # → 200 + HSTS header
openssl s_client -connect blog.croeyai.cn:443 2>/dev/null | openssl x509 -noout -dates
```

---

## 5. 回滚方案

| 场景 | 操作 |
|---|---|
| HTTPS 部署失败（证书申请失败） | `rm -f /etc/nginx/conf.d/myblog-http.conf /etc/nginx/conf.d/myblog-https.conf` + 恢复 `myblog.conf` + `nginx -t && systemctl reload nginx` |
| 升级后 HTTPS 出问题 | 回滚 deploy-server.sh 到旧版：`sudo ./deploy-server.sh <old-tag>` |
| CDN 场景切换 | 改 `deploy.env` 的 `CDN_SSL` 值，重新跑 `deploy-server.sh` |
