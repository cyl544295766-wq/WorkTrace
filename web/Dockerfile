# ── 阶段 1：Node 20 编译打包 ─────────────────────────
FROM node:20-alpine AS build
WORKDIR /app

# 先拷贝依赖清单并安装：源码变动时命中 Docker 层缓存
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ── 阶段 2：Nginx 托管静态产物 ───────────────────────
FROM nginx:1.27-alpine

# SPA 路由回退 + /api 反代到后端容器
COPY nginx.conf /etc/nginx/conf.d/default.conf

# vite 默认产物目录为 dist（tsconfig 内 outDir 未改写不生效，vite.config.ts 未设 build.outDir）
COPY --from=build /app/dist /usr/share/nginx/html

EXPOSE 80
