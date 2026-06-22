# =============================================================
# Frontend 全静态构建：node build → nginx serve
# 2026-06-17 v2.7.0：改 nuxt generate（产出 .output/public/ 静态文件），
#                  运行时换成 nginx alpine（不再需要 node 服务）
# 1C2G 收益：省 node 运行时 150-250MB 内存
#
# 上下文：build 时在 frontend/ 目录执行
#   docker build -t myblog/frontend:latest frontend
#   # 或带 API base：
#   docker build --build-arg NUXT_PUBLIC_API_BASE=https://yourname.com/api/v1 \
#     -t myblog/frontend:latest frontend
# =============================================================

# ---------- Stage 1: deps ----------
FROM node:20-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci

# ---------- Stage 2: build (nuxt generate) ----------
FROM node:20-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .

# API base 注入（默认 localhost:8080 用于本地 dev，部署时 --build-arg 覆盖）
ARG NUXT_PUBLIC_API_BASE=http://localhost:8080/api/v1
ENV NUXT_PUBLIC_API_BASE=$NUXT_PUBLIC_API_BASE

# 跑 nuxt generate，产物在 .output/public/
RUN npm run build

# ---------- Stage 3: runtime (nginx alpine 静态服务) ----------
FROM nginx:alpine
WORKDIR /usr/share/nginx/html

# 删掉 nginx 默认配置
RUN rm /etc/nginx/conf.d/default.conf

# 拷贝 generate 出来的静态产物
COPY --from=build /app/.output/public/ .

# 写最小 nginx 配置：SPA fallback + 缓存策略 + 健康检查
COPY <<'EOF' /etc/nginx/conf.d/default.conf
server {
    listen 3000;
    server_name _;
    root /usr/share/nginx/html;
    index index.html;

    # 健康检查（部署脚本 / docker-compose healthcheck 用）
    location = /healthz {
        access_log off;
        return 200 "ok\n";
        add_header Content-Type text/plain;
    }

    # SPA fallback：所有非已知文件的请求 → index.html（让 Vue Router 处理）
    # 覆盖所有路由，包括 /admin/*、/post/*、/articles/* 等
    location / {
        try_files $uri $uri/ /index.html;
    }

    # 静态资源长缓存（带 hash 的文件名，immutable）
    location ~* \.(js|css|woff2?|ttf|svg|png|jpg|jpeg|gif|webp|ico)$ {
        expires 7d;
        add_header Cache-Control "public, immutable";
        access_log off;
    }

    # index.html 强制不缓存（部署后立即生效）
    location = /index.html {
        add_header Cache-Control "no-cache, no-store, must-revalidate";
        expires 0;
    }

    # 隐藏 nginx 版本号
    server_tokens off;
}
EOF

EXPOSE 3000

# 前台跑 nginx（容器友好）
CMD ["nginx", "-g", "daemon off;"]
