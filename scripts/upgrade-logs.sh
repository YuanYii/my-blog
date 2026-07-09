#!/bin/bash
# 系统升级日志查看脚本
# 用法：bash scripts/upgrade-logs.sh [命令]

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

case "${1:-status}" in

status)
    echo -e "${GREEN}=== 服务状态 ===${NC}"
    systemctl status myblog nginx redis-server upgrade-agent --no-pager 2>/dev/null | grep -E "Active:|●" || \
    supervisorctl status 2>/dev/null
    ;;

health)
    echo -e "${GREEN}=== 健康检查 ===${NC}"
    echo -n "后端: "
    curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/health
    echo ""
    echo -n "前端: "
    curl -s -o /dev/null -w "%{http_code}" http://localhost:80
    echo ""
    echo -n "agent: "
    curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:28081/health 2>/dev/null || echo "不可达"
    echo ""
    ;;

upgrade)
    echo -e "${GREEN}=== 升级记录 ===${NC}"
    sqlite3 /opt/myblog/db/blog.db "SELECT id, target_version, status, started_at, finished_at FROM upgrade_record ORDER BY id DESC LIMIT 5;"
    echo ""
    echo -e "${GREEN}=== agent 状态 ===${NC}"
    curl -s http://127.0.0.1:28081/status 2>/dev/null || echo "agent 不可达"
    ;;

backend)
    echo -e "${GREEN}=== 后端日志（最近 20 行）==="
    tail -20 /opt/myblog/logs/blog.log 2>/dev/null || journalctl -u myblog -n 20 --no-pager
    ;;

error)
    echo -e "${RED}=== 错误日志（最近 20 行）==="
    tail -20 /opt/myblog/logs/app-error.log 2>/dev/null || journalctl -u myblog -n 20 --no-pager -p err
    ;;

agent)
    echo -e "${GREEN}=== upgrade-agent 日志 ==="
    tail -20 /opt/myblog/logs/upgrade-agent.log 2>/dev/null
    echo ""
    echo -e "${RED}=== upgrade-agent 错误 ==="
    tail -10 /opt/myblog/logs/upgrade-agent-error.log 2>/dev/null
    ;;

nginx)
    echo -e "${GREEN}=== nginx 访问日志 ==="
    tail -10 /var/log/nginx/access.log 2>/dev/null
    echo ""
    echo -e "${RED}=== nginx 错误日志 ==="
    tail -10 /var/log/nginx/error.log 2>/dev/null
    ;;

all)
    echo -e "${GREEN}========== 服务状态 =========="
    systemctl status myblog nginx redis-server upgrade-agent --no-pager 2>/dev/null | grep -E "Active:|●"
    echo ""
    echo -e "========== 健康检查 =========="
    echo -n "后端: "; curl -s -o /dev/null -w "%{http_code}" http://localhost:8080/api/v1/health; echo ""
    echo -n "前端: "; curl -s -o /dev/null -w "%{http_code}" http://localhost:80; echo ""
    echo -n "agent: "; curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:28081/health 2>/dev/null || echo "不可达"; echo ""
    echo ""
    echo -e "========== 升级记录 =========="
    sqlite3 /opt/myblog/db/blog.db "SELECT id, target_version, status, started_at FROM upgrade_record ORDER BY id DESC LIMIT 3;"
    echo ""
    echo -e "========== 最近错误 =========="
    tail -5 /opt/myblog/logs/app-error.log 2>/dev/null || echo "无错误日志"
    ;;

*)
    echo "用法: $0 [status|health|upgrade|backend|error|agent|nginx|all]"
    echo ""
    echo "  status  - 服务状态"
    echo "  health  - 健康检查"
    echo "  upgrade - 升级记录"
    echo "  backend - 后端日志"
    echo "  error   - 错误日志"
    echo "  agent   - upgrade-agent 日志"
    echo "  nginx   - nginx 日志"
    echo "  all     - 全部信息"
    ;;

esac
