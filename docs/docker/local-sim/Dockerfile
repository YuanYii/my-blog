# =============================================================
# my-blog 本地模拟生产环境容器镜像
#
# 目的：在 Docker 容器内模拟一个"假 ECS"，本地复刻生产的部署流程。
#   - JDK 8 + redis-server + nginx + sqlite3 + supervisor
#   - supervisord 替代 systemd（容器内启动简单，macOS Docker Desktop 友好）
#   - deploy-server.sh 跑同一份，靠 LOCAL_SIM=1 分支跳过 systemd/apt/防火墙
#
# 基础镜像选 ubuntu:22.04（与生产 ECS 保持一致，2026-06-19 v4.1.0 决策）：
#   - 生产 ECS 是 Ubuntu 22.04，本地模拟要跟生产 100% 一致，避免出现"本地过、生产挂"的版本差
#   - Ubuntu 22.04 (jammy) 官方源仍有 openjdk-8-jdk 包
#
# 构建：
#   docker build -t myblog-local-sim:latest .
# 运行（详见 README.md）：
#   docker run -d --name myblog-sim \
#     -p 8080:8080 -p 8000:80 \
#     -v myblog-sim-data:/opt/myblog/db \
#     -v myblog-sim-uploads:/opt/myblog/uploads \
#     myblog-local-sim:latest
# =============================================================

FROM ubuntu:22.04

# 避免 apt 交互卡住
ENV DEBIAN_FRONTEND=noninteractive

# ---------- 0. 换国内 apt 镜像源 ----------
# 官方源 ports.ubuntu.com（arm64）/ archive.ubuntu.com（amd64）经 Cloudflare 托管，
# 国内访问极慢且常 502 Bad Gateway，导致 apt-get install 中途连接失败、build 退出码 100。
# 换成清华源解决：arm64 走 ubuntu-ports，amd64 走 ubuntu，三条都替换以兼容两种架构。
RUN sed -i \
        -e 's@http://archive.ubuntu.com/ubuntu/@https://mirrors.tuna.tsinghua.edu.cn/ubuntu/@g' \
        -e 's@http://security.ubuntu.com/ubuntu/@https://mirrors.tuna.tsinghua.edu.cn/ubuntu/@g' \
        -e 's@http://ports.ubuntu.com/ubuntu-ports/@https://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/@g' \
        /etc/apt/sources.list

# ---------- 1. 装生产同款二进制 ----------
# 与生产 deploy-server.sh §2 的"apt 列表"完全一致
# （除 wget/unzip/python3 本地可省，但保留以防用户后续想用 publish-release 类似的发布链路在容器内做实验）
RUN apt-get update && apt-get install -y --no-install-recommends \
        openjdk-8-jdk-headless \
        redis-server \
        nginx \
        sqlite3 \
        supervisor \
        curl \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# ---------- 2. 容器内目录布局（照搬生产 /opt/myblog）----------
# 注意:不能用 {a,b,c} 花括号展开——ubuntu:22.04 的 /bin/sh 是 dash,
# 不支持 brace expansion,会创建一个字面 "{a,b,c}" 的目录而不是 a/b/c 三个目录.
# 拆成显式多目录,Dockerfile 这一层 RUN 也走 dash,不能依赖 bash.
RUN mkdir -p \
    /opt/myblog/logs \
    /opt/myblog/frontend \
    /opt/myblog/db \
    /opt/myblog/db/backups \
    /opt/myblog/uploads \
    /opt/myblog/scripts \
    /opt/myblog/releases

# ---------- 3. 复制容器专属配置 ----------
# supervisord.conf：容器 init 进程配置（管 redis + nginx；myblog 由 deploy 脚本管，autostart=false）
COPY supervisord.conf /etc/supervisor/supervisord.conf
# entrypoint-helper.sh：首次进容器时的引导（提示初始化 + 跑部署）
COPY entrypoint-helper.sh /usr/local/bin/entrypoint-helper
RUN chmod +x /usr/local/bin/entrypoint-helper

# ---------- 4. 暴露端口 ----------
# 8080 = Spring Boot（跟生产 SERVER_PORT 默认一致）
# 80   = nginx（容器内由 supervisord 监听，跟生产 PUBLIC_PORT 默认一致）
EXPOSE 8080 80

# ---------- 5. 健康检查 ----------
# 容器视角：8080 是后端，80 是 nginx。健康检查打后端就够了
HEALTHCHECK --interval=30s --timeout=5s --retries=3 --start-period=60s \
    CMD curl -fsS http://127.0.0.1:8080/api/v1/health || exit 1

# ---------- 6. 入口：supervisord 当容器 PID 1 ----------
# -n = 前台运行（容器 PID 1 必须前台，否则容器秒退）
# -c = 指定配置文件
CMD ["/usr/bin/supervisord", "-n", "-c", "/etc/supervisor/supervisord.conf"]