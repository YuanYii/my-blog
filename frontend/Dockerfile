# =============================================================
# Frontend 多阶段构建：node build → node runtime（Nuxt 3 SSR）
# 上下文：build 时在 frontend/ 目录执行
#   docker build -t myblog/frontend:latest frontend
# =============================================================

# ---------- Stage 1: deps ----------
FROM node:20-alpine AS deps
WORKDIR /app
COPY package.json package-lock.json* ./
RUN npm ci

# ---------- Stage 2: build ----------
FROM node:20-alpine AS build
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
RUN npm run build
# 产物在 .output/ 目录（包含 server + public）

# ---------- Stage 3: runtime ----------
FROM node:20-alpine
WORKDIR /app

# 健康检查工具（curl + wget，compose 选其一）
RUN apk add --no-cache curl wget

# 拷贝构建产物
COPY --from=build /app/.output ./.output

ENV HOST=0.0.0.0
ENV PORT=3000
ENV NODE_ENV=production

EXPOSE 3000

# Nuxt 3 SSR 服务入口
CMD ["node", ".output/server/index.mjs"]
