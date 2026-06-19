# my-blog 本地模拟生产环境容器

> 在 Docker 容器里模拟一个完整的"假 ECS"，跑**同一份** `deploy-server.sh` 模拟生产部署流程。
>
> 适用：本地复刻生产部署、调试部署脚本、回归测试 deploy-server.sh 的改动。

---

## 1. 它跟生产环境的差异

| 维度 | 生产（ECS 1C2G） | 本地容器 |
|---|---|---|
| 后端 | `java -jar` + systemd | `java -jar` + supervisord |
| Redis | apt 装 redis-server 系统服务 | supervisord 管（apt 装同款二进制）|
| nginx | apt 装 nginx + Let's Encrypt | supervisord 管（apt 装同款，无 HTTPS）|
| SQLite | WAL + busy_timeout | 同款 |
| JDK | 8 | **8**（一致）|
| CORS | 收紧到生产域名 | 默认占位域名 `yourname.com`（部署后改 `$INSTALL_DIR/myblog.env` 的 `CORS_ORIGINS`）|
| HTTPS / certbot | 有 | 无 |
| 防火墙 | 自动放行 | 无 |
| 部署脚本 | `deploy-server.sh` 完整流程 | **同一份** `deploy-server.sh`，靠 `LOCAL_SIM=1` 自动跳过 systemd/apt/防火墙步骤 |

**核心目标**：本地能完整复刻**部署链路**（下载 jar、初始化 db、写配置、启服务、健康检查），不模拟生产基础设施（HTTPS / 防火墙）。

---

## 2. 快速开始

### 2.1 构建镜像

```bash
cd docs/docker/local-sim
docker build -t myblog-local-sim:latest .
```

首次构建 ~3 分钟（要 apt-get 装一堆东西）。之后秒级 cache。

> Dockerfile 已把 apt 源换成清华镜像（`mirrors.tuna.tsinghua.edu.cn`），解决国内访问 `ports.ubuntu.com` / `archive.ubuntu.com`（经 Cloudflare）慢且 502 的问题。换其他源（阿里云 `mirrors.aliyun.com`、中科大 `mirrors.ustc.edu.cn`）改 Dockerfile 第 0 节那段 `sed` 即可。

### 2.2 启动"假 ECS"

```bash
docker run -d --name myblog-sim \
  -p 8080:8080 \              # Spring Boot 端口（跟生产 SERVER_PORT 默认值一致）
  -p 8000:80 \                # nginx 端口（宿主 8000 → 容器 80，避免跟本地 nginx 撞）
  -v myblog-sim-data:/opt/myblog/db \         # SQLite 文件持久化
  -v myblog-sim-uploads:/opt/myblog/uploads \ # 上传文件持久化
  myblog-local-sim:latest
```

**首次启动**：db 和 jar 都不存在，supervisord 只起 redis + nginx，myblog 是 `EXITED`。

### 2.3 进容器，看状态

```bash
docker exec -it myblog-sim entrypoint-helper
```

会打印：db 是否初始化、jar 是否就位、supervisord 三个进程状态、下一步建议。

### 2.4 跑一次完整部署（模拟生产）

**先把生产 deploy-server.sh 拷进容器**：

```bash
docker cp docs/scripts/deploy-server.sh myblog-sim:/opt/myblog/scripts/
```

**进容器跑部署**：

```bash
docker exec -it myblog-sim bash
# 容器内：
cd /opt/myblog/scripts
chmod +x deploy-server.sh

# 跟生产一模一样的命令（容器内 /.dockerenv 存在，自动开 LOCAL_SIM=1）
GITHUB_REPO=你的-owner/repo ./deploy-server.sh v4.1.0
```

### 2.5 验收

容器内或宿主机上：

```bash
curl http://localhost:8080/api/v1/health      # 后端健康
curl -I http://localhost:8000/                 # nginx 反代（前端静态）
```

---

## 3. 容器内常用命令速查

| 命令 | 干什么 | 对应生产命令 |
|---|---|---|
| `supervisorctl status` | 看三个进程 | `systemctl status myblog redis nginx` |
| `supervisorctl restart myblog` | 重启后端 | `systemctl restart myblog` |
| `supervisorctl tail -f myblog` | 实时后端日志 | `journalctl -u myblog -f` |
| `supervisorctl tail -f redis` | Redis 日志 | `journalctl -u redis -f` |
| `supervisorctl tail -f nginx` | nginx 日志 | `nginx -s reload + tail -f /var/log/nginx/*` |
| `supervisorctl reread && supervisorctl update` | 修改 supervisord.conf 后重载 | `systemctl daemon-reload` |

---

## 4. 数据持久化

| 数据 | 容器路径 | 宿主机位置 | 备注 |
|---|---|---|---|
| SQLite db | `/opt/myblog/db/blog.db` | docker volume `myblog-sim-data` | 销毁容器不丢 |
| 上传文件 | `/opt/myblog/uploads/` | docker volume `myblog-sim-uploads` | 销毁容器不丢 |
| 日志 | `/opt/myblog/logs/` | 容器内（销毁即丢） | 本地不持久化，要保留就 `-v myblog-sim-logs:/opt/myblog/logs` |

**清空重来**：

```bash
docker rm -f myblog-sim
docker volume rm myblog-sim-data myblog-sim-uploads
```

---

## 5. deploy-server.sh 的 LOCAL_SIM 模式

容器内跑 `deploy-server.sh` 会**自动跳过**以下步骤（生产机器上会执行）：

- ❌ §1 系统检测（容器内必然是 Ubuntu 22.04）
- ❌ §2 apt-get install（Dockerfile 已装齐）
- ❌ §3 创建 myblog 用户（容器内用 root 跑）
- ❌ §6.5 logrotate 配置（supervisord 自带日志轮转）
- ❌ §8 写 systemd unit（改为写 supervisord 配置 + supervisorctl 重启）
- ❌ §12 防火墙（容器无 iptables）

**保留**的步骤（跟生产完全一致）：

- ✅ §4 下载资源（从 GitHub Release 拉 jar / schema / 前端）
- ✅ §5 部署 jar / schema / 应用配置
- ✅ §7 写 application.yml
- ✅ §8.5 数据导入（IMPORT_DB=1 时）
- ✅ §9 nginx 配置
- ✅ §10 Redis 启动（改为 supervisorctl restart redis）
- ✅ §11 健康检查
- ✅ §13 收尾

触发条件：容器内 `/.dockerenv` 文件存在（Docker 容器必有）→ 自动 `LOCAL_SIM=1`。  
宿主机跑 `deploy-server.sh` 时 `/.dockerenv` 不存在 → 自动走生产模式（**完全不受影响**）。

---

## 6. 故障排查

### 6.1 容器秒退

`docker logs myblog-sim` 看最后几行。99% 是 supervisord 配置写错了。

### 6.2 后端启动失败

```bash
docker exec -it myblog-sim supervisorctl tail -f myblog
```

或看应用日志：

```bash
docker exec -it myblog-sim tail -f /opt/myblog/logs/app-error.log
```

### 6.3 db 文件锁了

SQLite 单写者锁，重启后端释放：

```bash
docker exec -it myblog-sim supervisorctl restart myblog
```

### 6.4 想看 JDK 版本确认

```bash
docker exec -it myblog-sim java -version
# 期望:openjdk version "1.8.0_xxx"
```

---

## 7. 文件清单

```
docs/docker/local-sim/
├── Dockerfile                    # 镜像构建（JDK 8 + redis + nginx + supervisor）
├── supervisord.conf              # 容器 init 配置（管 redis/nginx，myblog 由 deploy 脚本管）
├── entrypoint-helper.sh          # 首次进容器时的状态检查 + 引导
├── README.md                     # 本文件
├── .dockerignore                 # 构建排除
└── deploy-server-local.patch.txt # LOCAL_SIM 改动的可视化 diff（参考用，不参与构建）
```

`deploy-server.sh` 本体在 `../../scripts/deploy-server.sh`，**不在本目录**——本地模拟和生产共用同一份脚本。