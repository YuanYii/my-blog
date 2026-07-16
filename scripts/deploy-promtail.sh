#!/bin/bash
# ============================================================
# VPS Promtail 部署脚本
# 部署博客日志采集到 agent_log_monitoring（Loki）
#
# 用法：
#   chmod +x deploy-promtail.sh
#   sudo ./deploy-promtail.sh
#
# 首次部署：按提示输入 Cloudflare Access Token
# 重复执行：自动检测已安装组件，跳过重复步骤
# ============================================================
set -euo pipefail

# ============ 配置 ============
PROMTAIL_VERSION="3.4.2"
# PROMTAIL_URL 在架构检测后设置
BLOG_LOG_DIR="/opt/myblog/logs"
PROMTAIL_CONFIG="/etc/promtail/promtail-config.yaml"
PROMTAIL_POSITIONS="/var/lib/promtail/positions.yaml"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC} $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# ============ 0. 前置校验 ============
echo "=========================================="
echo "  Promtail 部署脚本 v${PROMTAIL_VERSION}"
echo "=========================================="
echo ""

# 检查 root
if [ "$EUID" -ne 0 ]; then
    log_error "请使用 sudo 执行此脚本"
    exit 1
fi

# 检查系统
log_info "检查系统环境..."
if ! command -v curl &>/dev/null; then
    log_error "缺少 curl，请先安装: apt-get install -y curl"
    exit 1
fi
if ! command -v unzip &>/dev/null; then
    log_warn "缺少 unzip，正在安装..."
    apt-get install -y unzip
fi

ARCH=$(uname -m)
case "$ARCH" in
    x86_64)  PROMTAIL_ARCH="amd64" ;;
    aarch64) PROMTAIL_ARCH="arm64" ;;
    *)       log_error "不支持的架构: $ARCH"; exit 1 ;;
esac
PROMTAIL_URL="https://github.com/grafana/loki/releases/download/v${PROMTAIL_VERSION}/promtail-linux-${PROMTAIL_ARCH}.zip"
log_info "架构: $ARCH → $PROMTAIL_ARCH ✓"

# 检查日志目录
if [ ! -d "$BLOG_LOG_DIR" ]; then
    log_error "博客日志目录不存在: $BLOG_LOG_DIR"
    exit 1
fi
if [ ! -f "$BLOG_LOG_DIR/blog.log" ]; then
    log_warn "blog.log 不存在，Promtail 启动后会自动创建监控"
fi
log_info "日志目录: $BLOG_LOG_DIR ✓"

# ============ 1. 安装 Promtail ============
echo ""
log_info "=== 1. 安装 Promtail ==="

if command -v promtail &>/dev/null; then
    CURRENT_VERSION=$(promtail --version 2>&1 | head -1 | grep -oP '[\d.]+' || echo "unknown")
    if [ "$CURRENT_VERSION" = "$PROMTAIL_VERSION" ]; then
        log_info "Promtail ${PROMTAIL_VERSION} 已安装，跳过"
    else
        log_warn "Promtail 版本不匹配（当前: $CURRENT_VERSION，目标: $PROMTAIL_VERSION），重新安装"
        cd /tmp
        curl -sL -o "promtail-linux-${PROMTAIL_ARCH}.zip" "$PROMTAIL_URL"
        unzip -o "promtail-linux-${PROMTAIL_ARCH}.zip"
        mv -f promtail-linux-${PROMTAIL_ARCH} /usr/local/bin/promtail
        chmod +x /usr/local/bin/promtail
        rm -f "promtail-linux-${PROMTAIL_ARCH}.zip"
        log_info "Promtail 已更新到 ${PROMTAIL_VERSION}"
    fi
else
    log_info "下载 Promtail ${PROMTAIL_VERSION}..."
    cd /tmp
    curl -sL -o "promtail-linux-${PROMTAIL_ARCH}.zip" "$PROMTAIL_URL"
    if [ ! -f "promtail-linux-${PROMTAIL_ARCH}.zip" ]; then
        log_error "下载失败，请检查网络连接"
        exit 1
    fi
    unzip -o "promtail-linux-${PROMTAIL_ARCH}.zip"
    mv promtail-linux-${PROMTAIL_ARCH} /usr/local/bin/promtail
    chmod +x /usr/local/bin/promtail
    rm -f "promtail-linux-${PROMTAIL_ARCH}.zip"
    log_info "Promtail 安装完成"
fi

promtail --version | head -1
log_info "Promtail 二进制 ✓"

# ============ 2. 创建配置目录 ============
echo ""
log_info "=== 2. 创建配置目录 ==="
mkdir -p /etc/promtail
mkdir -p /var/lib/promtail
log_info "配置目录 ✓"

# ============ 3. 生成 Promtail 配置 ============
echo ""
log_info "=== 3. 生成 Promtail 配置 ==="

# 检查是否已有配置（支持重复执行）
if [ -f "$PROMTAIL_CONFIG" ]; then
    # 备份旧配置
    cp "$PROMTAIL_CONFIG" "${PROMTAIL_CONFIG}.bak.$(date +%Y%m%d%H%M%S)"
    log_warn "已备份旧配置到 ${PROMTAIL_CONFIG}.bak.*"
fi

# 检查是否有 Cloudflare Access Token
CF_CLIENT_ID="${CF_ACCESS_CLIENT_ID:-}"
CF_CLIENT_SECRET="${CF_ACCESS_CLIENT_SECRET:-}"

if [ -z "$CF_CLIENT_ID" ] || [ -z "$CF_CLIENT_SECRET" ]; then
    echo ""
    log_warn "未检测到 Cloudflare Access Token 环境变量"
    log_info "如果已配置 Token，请设置环境变量后重新执行："
    log_info "  export CF_ACCESS_CLIENT_ID=<your-client-id>"
    log_info "  export CF_ACCESS_CLIENT_SECRET=<your-client-secret>"
    log_info "  sudo -E ./deploy-promtail.sh"
    echo ""
    log_info "首次部署：使用临时本地模式（推送到 VPS 本地 Loki）..."
    log_info "后续配置 Token 后重新执行即可切换到 Cloudflare Tunnel"
    echo ""

    cat > "$PROMTAIL_CONFIG" << 'PROMTAIL_EOF'
# Promtail 配置 — 博客日志采集
# 首次部署：本地模式（推送到 VPS 本地 Loki）

server:
  http_listen_port: 9080

positions:
  filename: /var/lib/promtail/positions.yaml

clients:
  - url: http://127.0.0.1:3100/loki/api/v1/push

scrape_configs:
  # 博客全量日志（INFO+）
  - job_name: blog-app
    static_configs:
      - targets: [localhost]
        labels:
          job: blog
          source: my-blog
          env: prod
          service: my-blog-backend
          logfile: app
          __path__: /opt/myblog/logs/blog.log
    pipeline_stages:
      - regex:
          expression: '^(?P<timestamp>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(?P<level>\w+)\s+\[(?P<thread>[^\]]*)\]\s+\[(?P<traceId>[^\]]*)\]\s+(?P<logger>\S+)\s+-\s+(?P<msg>.*)$'
      - labels:
          level:
          traceId:
      - timestamp:
          source: timestamp
          format: "2006-01-02 15:04:05.000"

  # 博客 WARN+ 日志
  - job_name: blog-warn
    static_configs:
      - targets: [localhost]
        labels:
          job: blog
          source: my-blog
          env: prod
          service: my-blog-backend
          logfile: warn
          __path__: /opt/myblog/logs/blog-warn.log
    pipeline_stages:
      - regex:
          expression: '^(?P<timestamp>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(?P<level>\w+)\s+\[(?P<thread>[^\]]*)\]\s+\[(?P<traceId>[^\]]*)\]\s+(?P<logger>\S+)\s+-\s+(?P<msg>.*)$'
      - labels:
          level:
          traceId:
      - timestamp:
          source: timestamp
          format: "2006-01-02 15:04:05.000"
PROMTAIL_EOF

    log_info "配置已生成（本地模式）"
else
    log_info "检测到 Cloudflare Access Token，生成 Tunnel 配置..."

    cat > "$PROMTAIL_CONFIG" << PROMTAIL_EOF
# Promtail 配置 — 博客日志采集
# 推送模式：通过 Cloudflare Tunnel 到本地 Loki

server:
  http_listen_port: 9080

positions:
  filename: /var/lib/promtail/positions.yaml

clients:
  - url: https://blog.coreyai.cn/loki/api/v1/push
    headers:
      CF-Access-Client-Id: ${CF_CLIENT_ID}
      CF-Access-Client-Secret: ${CF_CLIENT_SECRET}

scrape_configs:
  - job_name: blog-app
    static_configs:
      - targets: [localhost]
        labels:
          job: blog
          source: my-blog
          env: prod
          service: my-blog-backend
          logfile: app
          __path__: /opt/myblog/logs/blog.log
    pipeline_stages:
      - regex:
          expression: '^(?P<timestamp>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(?P<level>\w+)\s+\[(?P<thread>[^\]]*)\]\s+\[(?P<traceId>[^\]]*)\]\s+(?P<logger>\S+)\s+-\s+(?P<msg>.*)$'
      - labels:
          level:
          traceId:
      - timestamp:
          source: timestamp
          format: "2006-01-02 15:04:05.000"

  - job_name: blog-warn
    static_configs:
      - targets: [localhost]
        labels:
          job: blog
          source: my-blog
          env: prod
          service: my-blog-backend
          logfile: warn
          __path__: /opt/myblog/logs/blog-warn.log
    pipeline_stages:
      - regex:
          expression: '^(?P<timestamp>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(?P<level>\w+)\s+\[(?P<thread>[^\]]*)\]\s+\[(?P<traceId>[^\]]*)\]\s+(?P<logger>\S+)\s+-\s+(?P<msg>.*)$'
      - labels:
          level:
          traceId:
      - timestamp:
          source: timestamp
          format: "2006-01-02 15:04:05.000"
PROMTAIL_EOF

    log_info "配置已生成（Cloudflare Tunnel 模式）"
fi

# 校验配置
log_info "校验 Promtail 配置..."
if ! promtail -config.file="$PROMTAIL_CONFIG" -check-syntax 2>/dev/null; then
    log_error "配置校验失败，请检查 $PROMTAIL_CONFIG"
    exit 1
fi
log_info "配置校验通过 ✓"

# ============ 4. 创建 systemd 服务 ============
echo ""
log_info "=== 4. 创建 systemd 服务 ==="

# 检测运行环境（提前检测，供后续步骤使用）
if [ -f /.dockerenv ] || grep -q docker /proc/1/cgroup 2>/dev/null; then
    IS_DOCKER=true
else
    IS_DOCKER=false
fi

if [ "$IS_DOCKER" = true ]; then
    log_info "Docker 环境：跳过 systemd 服务创建"
else
    cat > /etc/systemd/system/promtail.service << 'SVC_EOF'
[Unit]
Description=Promtail Log Agent
After=network.target

[Service]
Type=simple
ExecStart=/usr/local/bin/promtail -config.file=/etc/promtail/promtail-config.yaml
Restart=always
RestartSec=5
MemoryMax=100M
CPUQuota=10%

[Install]
WantedBy=multi-user.target
SVC_EOF

    systemctl daemon-reload
    log_info "systemd 服务已创建 ✓"
fi

# ============ 5. 启动/重启服务 ============
echo ""
log_info "=== 5. 启动/重启 Promtail ==="

if [ "$IS_DOCKER" = true ]; then
    log_info "Docker 环境：跳过 systemd，手动启动 Promtail..."
    # 停止已有进程
    pkill -f "promtail -config.file" 2>/dev/null || true
    sleep 1
    # 后台启动
    nohup promtail -config.file="$PROMTAIL_CONFIG" > /var/log/promtail.log 2>&1 &
    PROMTAIL_PID=$!
    sleep 2
    if kill -0 $PROMTAIL_PID 2>/dev/null; then
        log_info "Promtail 启动成功 (PID: $PROMTAIL_PID) ✓"
    else
        log_error "启动失败，查看日志: cat /var/log/promtail.log"
        cat /var/log/promtail.log | tail -10
        exit 1
    fi
else
    if systemctl is-active --quiet promtail; then
        log_info "Promtail 已运行，重启以加载新配置..."
        systemctl restart promtail
        sleep 2
        if systemctl is-active --quiet promtail; then
            log_info "重启成功 ✓"
        else
            log_error "重启失败，查看日志: journalctl -u promtail -n 30"
            systemctl status promtail --no-pager
            exit 1
        fi
    else
        systemctl enable promtail
        systemctl start promtail
        sleep 2
        if systemctl is-active --quiet promtail; then
            log_info "启动成功 ✓"
        else
            log_error "启动失败，查看日志: journalctl -u promtail -n 30"
            systemctl status promtail --no-pager
            exit 1
        fi
    fi
fi

# ============ 6. 验证 ============
echo ""
log_info "=== 6. 验证 ==="

# 检查进程
if [ "$IS_DOCKER" = true ]; then
    if pgrep -f "promtail -config.file" >/dev/null; then
        log_info "Promtail 进程运行中 ✓"
    else
        log_error "Promtail 进程未运行"
        exit 1
    fi
else
    if systemctl is-active --quiet promtail; then
        log_info "Promtail 服务运行中 ✓"
    else
        log_error "Promtail 服务未运行"
        exit 1
    fi
fi

# 检查 positions 文件
if [ -f "$PROMTAIL_POSITIONS" ]; then
    log_info "positions 文件存在 ✓"
    log_info "  内容: $(cat $PROMTAIL_POSITIONS | head -5)"
else
    log_warn "positions 文件尚未生成（首次启动需要几秒）"
fi

# 检查日志文件是否被监控
log_info "检查日志文件..."
for f in blog.log blog-warn.log; do
    if [ -f "$BLOG_LOG_DIR/$f" ]; then
        log_info "  $BLOG_LOG_DIR/$f 存在 ✓"
    else
        log_warn "  $BLOG_LOG_DIR/$f 不存在（Promtail 会等待文件创建）"
    fi
done

# 检查最近日志
log_info "最近 Promtail 日志:"
if [ "$IS_DOCKER" = true ]; then
    if [ -f /var/log/promtail.log ]; then
        tail -5 /var/log/promtail.log
    else
        log_warn "日志文件尚未生成"
    fi
else
    journalctl -u promtail -n 5 --no-pager
fi

echo ""
echo "=========================================="
echo "  部署完成！"
echo "=========================================="
echo ""
if [ "$IS_DOCKER" = true ]; then
    log_info "Promtail 进程: pgrep -f 'promtail -config.file'"
    log_info "实时日志: tail -f /var/log/promtail.log"
else
    log_info "服务状态: systemctl status promtail"
    log_info "实时日志: journalctl -u promtail -f"
fi
log_info "配置文件: $PROMTAIL_CONFIG"
log_info "Positions: $PROMTAIL_POSITIONS"
echo ""
if [ -z "$CF_CLIENT_ID" ] || [ -z "$CF_CLIENT_SECRET" ]; then
    log_warn "当前为本地模式（推送到 VPS 本地 Loki）"
    log_info "配置 Cloudflare Token 后重新执行脚本切换到 Tunnel 模式："
    log_info "  export CF_ACCESS_CLIENT_ID=<your-client-id>"
    log_info "  export CF_ACCESS_CLIENT_SECRET=<your-client-secret>"
    log_info "  sudo -E ./deploy-promtail.sh"
else
    log_info "当前为 Cloudflare Tunnel 模式 ✓"
fi
echo ""
log_info "下一步：在本地机器配置 cloudflared 接收 Tunnel 推送"
