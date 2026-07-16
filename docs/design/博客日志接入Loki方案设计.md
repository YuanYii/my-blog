# 博客日志接入 Loki 方案设计（Phase 1：Promtail → Loki → Grafana）

| 项 | 值 |
|---|---|
| 文档编号 | DESIGN-LOKI-2026-07-07 |
| 文档版本 | v1.0（初稿） |
| 提出日期 | 2026-07-07 |
| 提出人 | 钱架构（系统架构师） |
| 文档状态 | 初稿 |
| 关联文档 | `服务日志体系设计.md`（REQ-LOG-2026-06-18） |
| 关联项目 | `agent_log_monitoring`（日志监控自动化项目） |

---

## 1. 背景

### 1.1 现状

my-blog 项目已部署到生产环境（VPS 1C2G，coreyai.cn），具备完善的日志体系（v4.0.0）：

- **日志框架**：Spring Boot 2.7 + Logback + SLF4J
- **日志文件**：`/opt/myblog/logs/blog.log`（全量）、`blog-warn.log`（WARN+）
- **日志格式**：`%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%X{traceId:-}] %logger{36} - %msg%n`
- **滚动策略**：按天 + 100MB 切割，30 天保留，3GB 上限
- **traceId**：全链路串联（TraceIdFilter 注入 MDC）

### 1.2 需求

将博客项目日志接入 `agent_log_monitoring` 项目的 Loki 日志聚合系统，实现：

1. **日志集中检索**：在 Grafana 中按 traceId / level / 关键词搜索博客日志
2. **日志可视化**：实时 tail 日志、ERROR 统计、趋势图
3. **后续扩展**：为 Phase 2（Sentry SDK → W1 工作流）提供数据基础

### 1.3 约束

| 约束 | 说明 |
|------|------|
| VPS 资源限制 | 1C2G，已跑 Spring Boot + Redis + Nginx，可用资源有限 |
| 无 Docker 部署 | VPS 上不使用 Docker，Promtail 需二进制部署 |
| 零侵入 | 不修改博客项目代码，不修改 Logback 配置 |
| 跨网络传输 | VPS（公网）→ 本地开发环境（Loki 在本地 Docker） |

> **注（Docker 用途澄清）**：仓库中 `backend/blog-app/Dockerfile`、`frontend/Dockerfile` 仅用于**本地模拟生产**（`scripts/deploy-server.sh` 在 `LOCAL_SIM=1` 时 build/run 镜像），**VPS 生产实际以 `blog-app.jar` + systemd（`myblog.service`）直跑，不使用 Docker**。因此本方案 VPS 侧接入组件（Promtail）同样走**二进制部署**，不引入 Docker，与 §1.3「无 Docker 部署」约束一致。

---

## 2. 架构设计

### 2.1 整体架构

```
┌──────────────────────────────────────────────────────────────────┐
│              生产环境 VPS (blog.coreyai.cn, 1C2G)                │
│                                                                  │
│  ┌──────────┐     ┌──────────────────────┐                       │
│  │  Nginx   │────▶│    Spring Boot       │                       │
│  │  (:80)   │     │    (:8080)           │                       │
│  └──────────┘     └──────────┬───────────┘                       │
│                              │ 日志输出                           │
│                              ▼                                    │
│                   ┌──────────────────────┐                       │
│                   │  /opt/myblog/logs/   │                       │
│                   │  ├── blog.log        │ ← 全量(INFO+)         │
│                   │  ├── blog-warn.log   │ ← WARN+               │
│                   │  └── archive/        │ ← 归档                 │
│                   └──────────┬───────────┘                       │
│                              │ 读取(tail -f)                      │
│                              ▼                                    │
│                   ┌──────────────────────┐                       │
│                   │   Promtail (二进制)  │                       │
│                   │   内存: ~30MB        │                       │
│                   │   功能: 解析+推送    │                       │
│                   └──────────┬───────────┘                       │
│                              │                                   │
└──────────────────────────────┼───────────────────────────────────┘
                               │
                               │ HTTPS POST
                               │ https://blog.coreyai.cn
                               │ /loki/api/v1/push
                               │
┌──────────────────────────────┼───────────────────────────────────┐
│              Cloudflare CDN   │                                   │
│                              ▼                                    │
│                   ┌──────────────────────┐                       │
│                   │  Cloudflare CDN      │                       │
│                   │  ├─ TLS终止(HTTPS)   │                       │
│                   │  ├─ 路由规则匹配      │                       │
│                   │  │  /loki/api/v1/push│                       │
│                   │  │  → Tunnel转发     │                       │
│                   │  └─ Access认证       │                       │
│                   └──────────┬───────────┘                       │
│                              │ Cloudflare Tunnel                  │
│                              │ (加密隧道)                         │
└──────────────────────────────┼───────────────────────────────────┘
                               │
┌──────────────────────────────┼───────────────────────────────────┐
│              本地开发环境     │                                   │
│              (agent_log_monitoring Docker)                       │
│                              ▼                                    │
│                   ┌──────────────────────┐                       │
│                   │  cloudflared         │                       │
│                   │  (Tunnel客户端)      │                       │
│                   │  内存: ~30MB         │                       │
│                   │  功能: 接收Tunnel    │                       │
│                   │        转发到Loki    │                       │
│                   └──────────┬───────────┘                       │
│                              │ HTTP转发                           │
│                              ▼                                    │
│                   ┌──────────────────────┐                       │
│                   │   Loki (:3100)       │                       │
│                   │   日志存储+查询      │                       │
│                   │   映射: 30004        │                       │
│                   └──────────┬───────────┘                       │
│                              │ LogQL                             │
│                              ▼                                    │
│                   ┌──────────────────────┐                       │
│                   │  Grafana (:3000)     │                       │
│                   │  日志可视化+告警     │                       │
│                   └──────────────────────┘                       │
└──────────────────────────────────────────────────────────────────┘
```

### 2.2 组件职责

| 组件 | 部署位置 | 职责 | 资源占用 |
|------|---------|------|---------|
| **Promtail** | VPS（二进制） | 采集博客日志文件，解析格式，打标签，推送到Loki | ~30MB 内存 |
| **Cloudflare CDN** | Cloudflare（已有） | TLS终止、路由匹配、Access认证、Tunnel转发 | 0（已有服务） |
| **cloudflared** | 本地开发环境 | 接收Cloudflare Tunnel流量，转发到本地Loki | ~30MB 内存 |
| **Loki** | 本地 Docker | 存储日志，提供 LogQL 查询 API | ~200MB 内存 |
| **Grafana** | 本地 Docker | 日志可视化展示，告警规则 | ~100MB 内存 |

### 2.3 数据流

```
博客应用 → Logback写文件 → Promtail读取 → 解析日志行 → 打标签 → HTTPS推送
            (已有的)         (新增)          (pipeline)   (labels)   ↓
                                                                 Cloudflare CDN
                                                                 (TLS终止+路由+认证)
                                                                      ↓
                                                                 Cloudflare Tunnel
                                                                 (加密隧道)
                                                                      ↓
Grafana查询 ← Loki存储 ← cloudflared转发
  (已有)       (已有)       (新增)
```

---

## 3. 日志格式解析

### 3.1 Logback 日志格式

博客项目的 Logback pattern：

```
%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%thread] [%X{traceId:-}] %logger{36} - %msg%n
```

**实际日志行示例**：

```
2026-07-07 12:00:00.123 INFO  [http-nio-8080-exec-1] [abc123def456] c.b.article.service.ArticleService - 文章创建成功 id=8235
2026-07-07 12:00:01.456 WARN  [http-nio-8080-exec-2] [-] c.blog.security.AdminAuthFilter - 鉴权拒绝 path=/api/admin/articles ip=1.2.3.4 reason=token_expired
2026-07-07 12:00:02.789 ERROR [http-nio-8080-exec-3] [ghi789] c.blog.handler.GlobalExceptionHandler - 500 NullPointerException at ArticleController:45
```

### 3.2 Promtail 解析方案

使用 Promtail 的 `pipeline_stages` 解析日志行：

```yaml
scrape_configs:
  - job_name: blog
    static_configs:
      - targets: [localhost]
        labels:
          job: blog
          source: my-blog
          env: prod
    pipeline_stages:
      # 1. 正则解析日志行，提取字段
      - regex:
          expression: '^(?P<timestamp>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(?P<level>\w+)\s+\[(?P<thread>[^\]]*)\]\s+\[(?P<traceId>[^\]]*)\]\s+(?P<logger>\S+)\s+-\s+(?P<message>.*)$'
      # 2. 提取 level 作为标签
      - labels:
          level:
      # 3. 提取 traceId 作为标签（空值用 "none" 替代）
      - template:
          source: traceId
          template: '{{ if eq .Value "" }}none{{ else }}{{ .Value }}{{ end }}'
      - labels:
          traceId:
      # 4. 提取 timestamp 作为日志时间戳
      - timestamp:
          source: timestamp
          format: '2006-01-02 15:04:05.000'
          location: Asia/Shanghai
```

### 3.3 解析后的标签结构

每条日志推送到 Loki 后，携带以下标签：

| 标签 | 来源 | 示例值 | 说明 |
|------|------|--------|------|
| `job` | 静态配置 | `blog` | 任务名 |
| `source` | 静态配置 | `my-blog` | 数据源 |
| `env` | 静态配置 | `prod` | 环境 |
| `level` | 日志解析 | `INFO` / `WARN` / `ERROR` | 日志级别 |
| `traceId` | 日志解析 | `abc123def456` / `none` | 链路追踪ID |
| `filename` | Promtail自动 | `blog.log` / `blog-warn.log` | 文件名 |

---

## 4. 部署方案

### 4.1 VPS 端：Promtail 二进制部署

**资源评估**：

| 资源 | Promtail 占用 | VPS 可用 | 评估 |
|------|-------------|---------|------|
| 内存 | ~30MB | ~500MB（扣除Spring Boot+Redis+Nginx后） | ✅ 充足 |
| CPU | <1% | 1核 | ✅ 可忽略 |
| 磁盘 | <10MB（二进制+positions文件） | 充足 | ✅ 可忽略 |

**部署步骤**：

```bash
# 1. 下载 Promtail 二进制（ARM64/x86_64 根据VPS架构选择）
# 查看架构
uname -m

# 下载（以 x86_64 为例）
wget https://github.com/grafana/loki/releases/download/v2.9.0/promtail-linux-amd64.zip
unzip promtail-linux-amd64.zip
sudo mv promtail-linux-amd64 /usr/local/bin/promtail
sudo chmod +x /usr/local/bin/promtail

# 2. 创建配置文件
sudo mkdir -p /etc/promtail
sudo vim /etc/promtail/promtail-config.yaml
# （配置内容见 4.2 节）

# 3. 创建 systemd 服务
sudo vim /etc/systemd/system/promtail.service
# （服务配置见 4.3 节）

# 4. 启动服务
sudo systemctl daemon-reload
sudo systemctl enable promtail
sudo systemctl start promtail
sudo systemctl status promtail
```

### 4.2 Promtail 配置文件

**文件路径**：`/etc/promtail/promtail-config.yaml`

```yaml
server:
  http_listen_port: 9080
  grpc_listen_port: 0

positions:
  filename: /var/lib/promtail/positions.yaml
  sync_period: 10s

clients:
  # ⚠️ URL 根据传输方案调整（见 4.4 节）
  - url: http://127.0.0.1:3100/loki/api/v1/push
    batchwait: 5s
    batchsize: 1048576  # 1MB
    max_retries: 3
    backoff_config:
      min_period: 1s
      max_period: 30s

scrape_configs:
  # 博客全量日志
  - job_name: blog-app
    static_configs:
      - targets: [localhost]
        labels:
          job: blog
          source: my-blog
          env: prod
          logfile: app
    file_sd_configs:
      - files:
          - /opt/myblog/logs/blog.log
    pipeline_stages:
      - regex:
          expression: '^(?P<timestamp>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(?P<level>\w+)\s+\[(?P<thread>[^\]]*)\]\s+\[(?P<traceId>[^\]]*)\]\s+(?P<logger>\S+)\s+-\s+(?P<message>.*)$'
      - labels:
          level:
      - template:
          source: traceId
          template: '{{ if eq .Value "" }}none{{ else }}{{ .Value }}{{ end }}'
      - labels:
          traceId:
      - timestamp:
          source: timestamp
          format: '2006-01-02 15:04:05.000'
          location: Asia/Shanghai

  # 博客警告日志（WARN+）
  - job_name: blog-warn
    static_configs:
      - targets: [localhost]
        labels:
          job: blog
          source: my-blog
          env: prod
          logfile: warn
    file_sd_configs:
      - files:
          - /opt/myblog/logs/blog-warn.log
    pipeline_stages:
      - regex:
          expression: '^(?P<timestamp>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3})\s+(?P<level>\w+)\s+\[(?P<thread>[^\]]*)\]\s+\[(?P<traceId>[^\]]*)\]\s+(?P<logger>\S+)\s+-\s+(?P<message>.*)$'
      - labels:
          level:
      - template:
          source: traceId
          template: '{{ if eq .Value "" }}none{{ else }}{{ .Value }}{{ end }}'
      - labels:
          traceId:
      - timestamp:
          source: timestamp
          format: '2006-01-02 15:04:05.000'
          location: Asia/Shanghai
```

### 4.3 Systemd 服务配置

**文件路径**：`/etc/systemd/system/promtail.service`

```ini
[Unit]
Description=Promtail (Log Agent for Loki)
Documentation=https://grafana.com/docs/loki/latest/clients/promtail/
Wants=network-online.target
After=network-online.target

[Service]
Type=simple
User=root
ExecStart=/usr/local/bin/promtail \
  -config.file=/etc/promtail/promtail-config.yaml \
  -config.expand-env=true
Restart=on-failure
RestartSec=10
StandardOutput=journal
StandardError=journal

# 资源限制（防止 Promtail 异常时占用过多资源）
MemoryMax=100M
CPUQuota=10%

[Install]
WantedBy=multi-user.target
```

### 4.4 跨网络传输方案：Cloudflare Tunnel

#### 4.4.1 方案选型

| 方案 | 原理 | 优势 | 劣势 | 推荐度 |
|------|------|------|------|--------|
| ~~SSH隧道~~ | VPS通过SSH反向隧道访问本地Loki | 安全（SSH加密）| 服务器连接受限，SSH隧道无法使用 | ❌ 已排除 |
| ~~公网HTTP~~ | Loki暴露公网端口，Promtail直推 | 简单 | 不安全（明文） | ❌ 不推荐 |
| ~~frp+Nginx~~ | VPS部署Nginx反代+TLS+认证，frp穿透到本地Loki | 安全（TLS+认证）| VPS需额外部署Nginx+frp，配置复杂 | ❌ 已排除 |
| **Cloudflare Tunnel** | 利用博客已有的Cloudflare，通过Tunnel穿透到本地Loki | 零VPS额外组件、官方工具、自动TLS | 需要Cloudflare Dashboard配置 | ✅ **采用** |

**选型理由**：博客项目已使用 Cloudflare 配置 HTTPS（`CDN_SSL=1` 模式），Cloudflare Tunnel 是 Cloudflare 官方内网穿透工具，与现有架构完美兼容，无需在 VPS 上额外部署任何组件。

#### 4.4.2 架构设计

```
┌─ VPS (blog.coreyai.cn) ────────────────┐    ┌─ Cloudflare ─┐    ┌─ 本地开发环境 ──────────┐
│                                        │    │              │    │                        │
│  博客应用 → 日志文件                   │    │  blog.       │    │  cloudflared            │
│  Promtail → 读取日志                   │    │  coreyai.cn  │    │  (Tunnel客户端)         │
│    ↓ HTTPS推送                         │    │              │    │  ├─ 主动连接Cloudflare  │
│    https://blog.coreyai.cn             │───▶│  TLS终止     │───▶│  ├─ 接收Tunnel流量      │
│    /loki/api/v1/push                   │HTTPS│  路由匹配    │Tunnel│  └─ 转发到Loki:3100    │
│                                        │    │  Access认证  │    │                        │
│  (仅Promtail，无其他额外组件)          │    │              │    │  Loki (:3100)          │
│                                        │    │  路由规则:   │    │  Grafana (:3000)       │
│                                        │    │  /loki/api/  │    │                        │
│                                        │    │  v1/push     │    │                        │
│                                        │    │  → Tunnel    │    │                        │
└────────────────────────────────────────┘    └──────────────┘    └────────────────────────┘
```

**数据流**：

```
Promtail(VPS)
  → HTTPS POST https://blog.coreyai.cn/loki/api/v1/push
  → Cloudflare CDN（TLS终止 + 路由匹配 + Access认证）
  → /loki/api/v1/push 匹配 Tunnel 路由规则
  → 通过 Cloudflare Tunnel 加密隧道转发
  → 本地 cloudflared 接收流量
  → 转发到 Loki(:3100)
  → Loki 存储日志
```

#### 4.4.3 组件说明

| 组件 | 部署位置 | 职责 | 资源占用 |
|------|---------|------|---------|
| **Cloudflare CDN** | Cloudflare（已有） | TLS终止、路由匹配、Access认证、Tunnel转发 | 0（已有服务） |
| **cloudflared** | 本地开发环境 | 接收Cloudflare Tunnel流量，转发到本地Loki | ~30MB |
| **Loki** | 本地开发环境(Docker) | 日志存储+查询 | ~200MB |
| **Promtail** | VPS（二进制） | 采集日志，HTTPS推送到Cloudflare | ~30MB |

**关键优势**：VPS 上只需要 Promtail，不需要 Nginx 反代、frp server 或 TLS 证书。

#### 4.4.4 Cloudflare Dashboard 配置（创建Tunnel + 路由规则 + Access）

**Step 1：创建 Cloudflare Tunnel**

1. 登录 Cloudflare Dashboard → Zero Trust → Networks → Tunnels
2. 点击 **Create Tunnel** → 选择 **Cloudflared** 类型
3. 输入 Tunnel 名称：`loki-push-tunnel`
4. 选择环境：`Local`（本地开发环境）
5. 创建后获得 **Tunnel Token**（保存备用）

```
Tunnel Token 示例：
eyJhIjoiNzA3MmMzZmU4ZjQ4NDE4NzZkYzY5YjYxY2JjMzI5ZCIsInQiOiI3YjBkMmE5Yy0...
```

**Step 2：配置 Tunnel 路由规则**

在 Tunnel 详情页 → **Public Hostnames** → 添加路由规则：

| 字段 | 值 | 说明 |
|------|-----|------|
| Subdomain | `blog` | 博客已有子域名 |
| Domain | `coreyai.cn` | 选择已有域名 |
| Path | `/loki/api/v1/push` | **仅此路径走Tunnel** |
| Service Type | `HTTP` | 本地Loki是HTTP |
| Service URL | `localhost:30004` | 本地Loki Docker映射端口 |

**关键**：Path 设为 `/loki/api/v1/push`，只有此路径的请求会通过 Tunnel 转发到本地Loki，其他路径仍走博客正常的Nginx服务。

**Step 3：配置 Cloudflare Access（替代BasicAuth）**

在 Zero Trust → Access → Applications → Add Application：

| 字段 | 值 | 说明 |
|------|-----|------|
| Application type | `Self-hosted` | 自托管应用 |
| Application name | `Loki Push API` | 应用名称 |
| Session duration | `24 hours` | 会话时长 |
| Application domain | `blog.coreyai.cn` | 域名 |
| Path | `/loki/api/v1/push` | 保护的路径 |

**Access Policy 配置**：

| 字段 | 值 | 说明 |
|------|-----|------|
| Policy name | `Allow Promtail` | 策略名称 |
| Action | `Allow` | 允许访问 |
| Include | `Service Token` | 使用Service Token认证 |

**创建 Service Token**（供Promtail使用）：

在 Access → Service Auth → Service Tokens → Create Service Token：

```
生成结果：
Client ID:     abcd1234.service-account.access@coreyai.cn
Client Secret: ef5678901234567890abcdef12345678
```

⚠️ **保存这两个值**，Promtail配置中需要使用。Service Token 只显示一次。

#### 4.4.5 本地端部署：cloudflared

**Step 1：安装 cloudflared（macOS）**

```bash
# 使用Homebrew安装
brew install cloudflared

# 验证安装
cloudflared --version
# 预期: cloudflared 2024.x.x
```

**Step 2：运行 cloudflared 连接 Tunnel**

```bash
# 方式1：命令行运行（测试用）
cloudflared tunnel run \
  --token eyJhIjoiNzA3MmMzZmU4ZjQ4NDE4NzZkYzY5YjYxY2JjMzI5ZCIsInQiOiI3YjBkMmE5Yy0...

# 方式2：使用配置文件（推荐）
# 创建配置文件
mkdir -p ~/.cloudflared
cat > ~/.cloudflared/config.yml << 'EOF'
tunnel: loki-push-tunnel
credentials-file: ~/.cloudflared/<tunnel-id>.json

ingress:
  # /loki/api/v1/push 转发到本地Loki
  - hostname: blog.coreyai.cn
    path: /loki/api/v1/push
    service: http://localhost:30004
  # 其他请求返回404（Tunnel不处理）
  - service: http_status:404
EOF

# 使用配置文件运行
cloudflared tunnel run --config ~/.cloudflared/config.yml
```

**Step 3：创建 launchd 服务（macOS持久化）**

**文件路径**：`~/Library/LaunchAgents/com.cloudflare.cloudflared.plist`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>com.cloudflare.cloudflared</string>
    <key>ProgramArguments</key>
    <array>
        <string>/opt/homebrew/bin/cloudflared</string>
        <string>tunnel</string>
        <string>run</string>
        <string>--token</string>
        <string>eyJhIjoiNzA3MmMzZmU4ZjQ4NDE4NzZkYzY5YjYxY2JjMzI5ZCIsInQiOiI3YjBkMmE5Yy0...</string>
    </array>
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>/tmp/cloudflared.out.log</string>
    <key>StandardErrorPath</key>
    <string>/tmp/cloudflared.err.log</string>
</dict>
</plist>
```

```bash
# 加载并启动服务
launchctl load ~/Library/LaunchAgents/com.cloudflare.cloudflared.plist
launchctl start com.cloudflare.cloudflared

# 验证：查看日志
tail -f /tmp/cloudflared.out.log
# 预期看到: "Registered tunnel connection" (4条连接)

# 验证：Tunnel状态
cloudflared tunnel info loki-push-tunnel
```

#### 4.4.6 更新Promtail配置

Promtail的`clients.url`更新为Cloudflare域名，并配置Service Token认证：

**更新 `/etc/promtail/promtail-config.yaml` 中的clients部分**：

```yaml
clients:
  # ⚠️ 改为Cloudflare域名（HTTPS）
  - url: https://blog.coreyai.cn/loki/api/v1/push
    # Cloudflare Access Service Token认证（替代BasicAuth）
    headers:
      # 格式: CF-Access-Client-Id 和 CF-Access-Client-Secret
      CF-Access-Client-Id: "abcd1234.service-account.access@coreyai.cn"
      CF-Access-Client-Secret: "ef5678901234567890abcdef12345678"
    # TLS配置（Cloudflare自带证书，无需额外配置）
    tls_config:
      insecure_skip_verify: false
    # 批量推送配置
    batchwait: 5s
    batchsize: 1048576  # 1MB
    max_retries: 3
    backoff_config:
      min_period: 1s
      max_period: 30s
```

```bash
# 重启Promtail使配置生效
sudo systemctl restart promtail

# 验证Promtail日志推送
sudo journalctl -u promtail -f --no-pager | head -50
# 预期看到: "Push request sent" 或无错误日志
```

#### 4.4.7 安全加固清单

| 安全项 | 措施 | 验证方法 |
|--------|------|---------|
| **TLS加密** | Cloudflare自动提供HTTPS | `curl https://blog.coreyai.cn/loki/api/v1/push` 返回403（无Token时） |
| **Access认证** | Cloudflare Access + Service Token | 无Token访问返回403，带Token访问通过 |
| **路径限制** | Tunnel路由规则仅匹配 `/loki/api/v1/push` | `curl https://blog.coreyai.cn/loki/api/v1/query` 返回博客404 |
| **Tunnel认证** | cloudflared使用Tunnel Token连接 | Token错误时cloudflared无法连接 |
| **Service Token一次性** | Service Secret只显示一次 | 妥善保存，丢失需重新生成 |
| **本地端口不暴露** | Loki只监听localhost:30004 | `netstat -tlnp \| grep 30004` 显示127.0.0.1 |
| **cloudflared自动重连** | KeepAlive=true，自动重连 | 断网恢复后日志自动恢复推送 |
| **零VPS额外组件** | VPS只有Promtail，无Nginx/frp | 减少攻击面，降低维护成本 |


---

## 5. Grafana 配置方案

### 5.1 数据源配置

在 Grafana 中添加 Loki 数据源：

```
URL: http://loki:3100
（本地 Docker 中，Loki 服务名为 loki）
```

### 5.2 推荐面板设计

| 面板 | 类型 | LogQL 查询 | 用途 |
|------|------|-----------|------|
| **实时日志tail** | Logs | `{job="blog"}` | 实时查看博客日志 |
| **ERROR日志** | Logs | `{job="blog", level="ERROR"}` | 只看错误日志 |
| **WARN+ERROR统计** | Time series | `count_over_time({job="blog", level=~"WARN\|ERROR"}[5m])` | 告警/错误趋势 |
| **traceId搜索** | Logs | `{job="blog", traceId="abc123def456"}` | 按traceId查链路 |
| **鉴权拒绝日志** | Logs | `{job="blog"} |= "鉴权拒绝"` | 安全审计 |
| **日志级别分布** | Pie chart | `sum by (level) (count_over_time({job="blog"}[1h]))` | 级别分布 |
| **TOP错误日志** | Table | `topk(10, sum by (logger) (count_over_time({job="blog", level="ERROR"}[1h])))` | 最常出错的模块 |
| **访问统计** | `{job="blog", logger="c.blog.article.security.PageViewFilter"} |= "page_view"` | 访问日志 |
| **审计日志** | `{job="blog"} |= "AUDIT"` | 管理操作审计 |
| **按IP搜索** | `{job="blog"} |= "ip=1.2.3.4"` | 按IP排查问题 |

---

## 6. 实施步骤

### 6.1 Phase 1 实施计划

```
Step 1: 本地环境准备（agent_log_monitoring项目）
  ├─ 确认 Loki Docker 容器正常运行（端口 30004:3100）
  ├─ 确认 Grafana Docker 容器正常运行（端口 3000）
  └─ 在 Grafana 中添加 Loki 数据源（URL: http://loki:3100）

Step 2: 配置跨网络传输（Cloudflare Tunnel + 本地cloudflared）
  ├─ Cloudflare Dashboard：
  │   ├─ Zero Trust → Networks → Tunnels → 创建Tunnel（loki-push-tunnel）
  │   ├─ 配置路由规则：blog.coreyai.cn/loki/api/v1/push → localhost:30004
  │   ├─ Access → Applications → 创建应用（保护/loki/api/v1/push路径）
  │   └─ Access → Service Tokens → 创建Service Token（供Promtail使用）
  ├─ 本地端：
  │   ├─ brew install cloudflared
  │   ├─ cloudflared tunnel run --token <Tunnel Token>
  │   ├─ 创建launchd服务（持久化，自动重连）
  │   └─ 验证：日志显示"Registered tunnel connection"
  └─ 更新Promtail配置：clients.url改为https://blog.coreyai.cn/loki/api/v1/push + Service Token headers

Step 3: VPS部署Promtail
  ├─ 下载 promtail 二进制文件
  ├─ 创建配置文件 /etc/promtail/promtail-config.yaml
  ├─ 创建 systemd 服务
  ├─ 启动并验证：journalctl -u promtail -f
  └─ 验证日志推送：在 Grafana 中查询 {job="blog"}

Step 4: Grafana创建面板
  ├─ 创建 Dashboard "博客日志监控"
  ├─ 添加日志tail面板
  ├─ 添加ERROR统计面板
  ├─ 添加traceId搜索面板
  └─ 验证面板数据正常
```

### 6.2 验证清单

| 验证项 | 方法 | 预期结果 |
|--------|------|---------|
| Promtail运行 | `systemctl status promtail` | active (running) |
| Promtail读取日志 | `journalctl -u promtail -f` | 看到日志读取记录 |
| Cloudflare Tunnel连通 | 本地cloudflared日志 `/tmp/cloudflared.out.log` | "Registered tunnel connection" (4条连接) |
| Access认证生效 | `curl https://blog.coreyai.cn/loki/api/v1/push` | 返回403（无Service Token时） |
| Service Token认证通过 | `curl -H "CF-Access-Client-Id: xxx" -H "CF-Access-Client-Secret: xxx" https://blog.coreyai.cn/loki/api/v1/push` | 返回400（空请求体，认证通过） |
| 路径限制生效 | `curl https://blog.coreyai.cn/loki/api/v1/query` | 返回博客404（不走Tunnel） |
| Loki收到日志 | Grafana查询 `{job="blog"}` | 看到博客日志 |
| 标签解析正确 | Grafana查询 `{job="blog", level="ERROR"}` | 只显示ERROR日志 |
| traceId解析正确 | Grafana查询 `{job="blog", traceId!="none"}` | 显示带traceId的日志 |

---

## 7. 资源评估与风险

### 7.1 VPS 资源影响

| 组件 | 内存占用 | CPU占用 | 磁盘占用 | 评估 |
|------|---------|---------|---------|------|
| Promtail | ~30MB | <1% | <10MB | ✅ 可接受 |
| **VPS总占用** | Spring Boot(~400MB) + Redis(~50MB) + Nginx(~20MB) + Promtail(~30MB) = ~500MB | ~50% CPU | /opt/myblog ~3GB | ✅ 2GB内存够用 |

### 7.2 风险与应对

| 风险 | 影响 | 应对措施 |
|------|------|---------|
| Tunnel断开 | 日志推送中断 | cloudflared自动重连（KeepAlive=true）；Promtail有positions文件，重连后从断点续传 |
| Service Token泄露 | 未授权推送 | 在Cloudflare Dashboard重新生成Token；定期轮换Token |
| Cloudflare服务故障 | 推送中断 | Cloudflare SLA 99.99%；Promtail缓存日志，恢复后自动重推 |
| Loki不可用 | Promtail无法推送 | Promtail会缓存日志（内存队列），Loki恢复后自动重推 |
| VPS资源不足 | 影响博客性能 | Promtail限制MemoryMax=100M, CPUQuota=10%；监控VPS资源 |
| 日志量过大 | Loki存储压力大 | 博客日均100MB，30天3GB，Loki完全能处理；可配置Loki保留期 |
| 网络延迟 | 日志推送延迟 | 日均100MB日志，按批次推送，延迟<5s，可接受 |

---

## 8. 后续扩展（Phase 2 预告）

Phase 1 完成后，后续可扩展：

| Phase | 内容 | 前提条件 |
|-------|------|---------|
| **Phase 2** | 博客集成Sentry Java SDK → agent_log_monitoring W1工作流 | Phase 1完成，W1工作流稳定 |
| **Phase 3** | W1工作流生成合成告警 → 飞书/邮件通知 | Phase 2完成 |
| **Phase 4** | 博客指标监控（Prometheus + Grafana） | 需要增加Spring Boot Actuator |

Phase 2 的架构方向：

```
博客应用 → Sentry Java SDK → agent_log_monitoring
                                    ↓
                            W1工作流（Temporal）
                            ├─ 入口门控
                            ├─ 去重聚合（Redis）
                            ├─ 风暴识别
                            └─ 告警生成（LLM）
                                    ↓
                            告警通知（飞书/邮件）
```

---

## 9. 附录

### 9.1 参考文档

- Loki官方文档：https://grafana.com/docs/loki/latest/
- Promtail配置文档：https://grafana.com/docs/loki/latest/clients/promtail/configuration/
- LogQL查询语言：https://grafana.com/docs/loki/latest/logql/
- 博客项目日志体系：`服务日志体系设计.md`

### 9.2 术语表

| 术语 | 说明 |
|------|------|
| **Loki** | Grafana Labs 出品的日志聚合系统，类似 Prometheus 但用于日志 |
| **Promtail** | Loki 官方日志采集 agent，读取日志文件并推送到 Loki |
| **LogQL** | Loki 的查询语言，类似 PromQL 但支持全文搜索 |
| **pipeline_stages** | Promtail 的日志解析管道，支持正则、JSON、模板等解析 |
| **labels** | Loki 的日志标签，用于索引和过滤，类似 Prometheus 的标签 |
| **traceId** | 博客项目的链路追踪ID，由 TraceIdFilter 注入 MDC |

---

**文档版本**：v1.2（跨网络传输方案改为Cloudflare Tunnel）  
**创建时间**：2026-07-07  
**创建人**：钱架构（系统架构师）  
**维护人**：钱架构（系统架构师）  
**更新说明**：
- v1.2（2026-07-07）：跨网络传输方案改为Cloudflare Tunnel（利用博客已有的Cloudflare，VPS零额外组件）
  - 去掉VPS上的Nginx反代、frp server、Let's Encrypt证书
  - 改用Cloudflare Tunnel + cloudflared + Cloudflare Access
  - VPS仅需Promtail，资源占用从~60MB降至~30MB
- v1.1（2026-07-07）：跨网络传输方案从SSH隧道改为公网HTTPS+BasicAuth（frp+Nginx）
- v1.0（2026-07-07）：初始版本，基于Promtail→Loki→Grafana架构
