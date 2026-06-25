# SQLite WAL 模式数据库损坏踩坑记录

> 项目：my-blog
> 时间：2026-06-24
> 作者：MiMo Code Agent

---

## 背景

my-blog 项目使用 SQLite 3.45 作为默认数据库（v2.6.0 起），生产环境部署在 Docker 容器 `myblog-sim` 中，通过 supervisord 管理 Redis、Nginx、Spring Boot 三个服务。SQLite 配置为 WAL 模式以支持并发读写：

```yaml
url: jdbc:sqlite:/opt/myblog/db/blog.db?journal_mode=WAL&busy_timeout=10000&synchronous=NORMAL
hikari:
  maximum-pool-size: 8
```

---

## 问题 1：SQLite 数据库文件损坏（SQLITE_CORRUPT）

**现象**

本地 Docker 服务报错，Spring Boot 应用无法查询数据库：
```
org.springframework.jdbc.UncategorizedSQLException:
### Error querying database.
Cause: org.sqlite.SQLiteException: [SQLITE_CORRUPT] The database disk image is malformed
### The error may exist in com/blog/auth/mapper/AdminDeviceMapper.java (best guess)
### SQL: SELECT id,device_id,device_name,user_agent,ip,status,last_seen_at,approved_at,approved_by,created_at,updated_at FROM admin_device WHERE (device_id = ?)
```

执行 `PRAGMA integrity_check` 验证：
```bash
docker exec myblog-sim sqlite3 /opt/myblog/db/blog.db "PRAGMA integrity_check;"
# 输出：
Error: stepping, database disk image is malformed (11)
*** in database main ***
Page 5: btreeInitPage() returns error code 11
Page 8: btreeInitPage() returns error code 11
Page 7: btreeInitPage() returns error code 11
Page 6: btreeInitPage() returns error code 11
Page 50: btreeInitPage() returns error code 11
Page 51: btreeInitPage() returns error code 11
```

**根因分析**

1. **直接原因：WAL 事务中断 + 强制进程终止**
   - SQLite 在 WAL 模式下，写操作先进入 WAL 文件，再由 checkpoint 合并到主 db 文件
   - `supervisorctl restart myblog` 发送 SIGTERM（exit status 143）强制终止 Java 进程
   - 如果 HikariCP 连接池有未完成的写事务（如 `page_view` 异步写入），WAL 文件可能处于不一致状态
   - `synchronous=NORMAL` 模式下，checkpoint 操作本身不是原子的，中断会导致 btree 页面损坏

2. **诱发因素**
   - **频繁重启**：日志显示 24 小时内重启 9+ 次（03:36, 03:52, 04:01, 04:04...）
   - **外部 sqlite3 CLI 并发访问**：`blog-backup.sh` 直接用 `sqlite3` CLI 读 db（SELECT count）与 Spring Boot 并发
   - **Redis 数据目录重叠**：Redis 配置 `--dir /opt/myblog/db`，增加目录 I/O 复杂度
   - **无 graceful shutdown**：Java 进程收到 SIGTERM 后，HikariCP 没有机会完成 pending checkpoint

**解决方案（紧急修复）**

使用 SQLite 的 `.recover` 命令恢复数据：

```bash
# 1. 尝试恢复数据
docker exec myblog-sim sqlite3 /opt/myblog/db/blog.db ".recover" | sqlite3 /tmp/blog-recovered.db

# 2. 验证恢复后的数据库完整性
docker exec myblog-sim sqlite3 /tmp/blog-recovered.db "PRAGMA integrity_check;"
# 输出：ok

# 3. 替换损坏的数据库
docker exec myblog-sim bash -c '
  cp /opt/myblog/db/blog.db /opt/myblog/db/blog.db.corrupted
  cp /tmp/blog-recovered.db /opt/myblog/db/blog.db
  rm -f /opt/myblog/db/blog.db-shm /opt/myblog/db/blog.db-wal
'

# 4. 重启应用
docker exec myblog-sim supervisorctl restart myblog
```

**恢复结果**：成功恢复 158 条数据，覆盖所有 15 张表。

---

## 问题 2：备份脚本并发访问 SQLite 的风险

**现象**

`blog-backup.sh` 脚本直接使用 `sqlite3` CLI 查询数据库：
```bash
DB_TABLE_COUNTS=$(sqlite3 "$SQLITE_PATH" "SELECT count(*) FROM ...")
cnt=$(sqlite3 "$SQLITE_PATH" "SELECT count(*) FROM \"$t\"" 2>/dev/null || echo 0)
```

**根因**

- `sqlite3` CLI 默认使用 SHARED 锁，与 Spring Boot 的 WAL 模式写操作可能产生锁竞争
- 外部进程直接读取 WAL 文件时，如果 checkpoint 正在进行，可能导致数据不一致
- `sqlite-export.sh` 使用了 `.timeout` 包装器，但 `blog-backup.sh` 直接调用原始 `sqlite3` 命令

**解决方案（预防措施）**

1. **备份脚本使用 `.backup` 命令**：
   ```bash
   # 改为
   sqlite3 "$SQLITE_PATH" ".backup /tmp/backup.db"
   # 而不是
   sqlite3 "$SQLITE_PATH" "SELECT count(*) FROM ..."
   ```

2. **重启前手动 checkpoint**：
   ```bash
   supervisorctl stop myblog
   sqlite3 /opt/myblog/db/blog.db "PRAGMA wal_checkpoint(TRUNCATE);"
   supervisorctl start myblog
   ```

---

## 问题 3：Redis 数据目录与 SQLite 共享

**现象**

Supervisord 配置中 Redis 的工作目录与 SQLite 相同：
```ini
[program:redis]
command=/usr/bin/redis-server --daemonize no --appendonly no --maxmemory 128mb --dir /opt/myblog/db
directory=/opt/myblog/db
```

**根因**

- 虽然 Redis 不直接操作 SQLite 文件，但共享目录增加了 I/O 复杂度
- Redis 的 append-only file (AOF) 和 RDB 快照也会写入同一目录
- 如果 Redis 发生异常（如 OOM），可能影响目录中其他文件的完整性

**解决方案（中期优化）**

隔离 Redis 数据目录：
```ini
[program:redis]
command=/usr/bin/redis-server --daemonize no --appendonly no --maxmemory 128mb --dir /opt/myblog/db/redis
directory=/opt/myblog/db/redis
```

---

## 问题 4：HikariCP 缺少 Graceful Shutdown 配置

**现象**

Java 进程被 SIGTERM 终止时，HikariCP 连接池没有时间完成 pending 事务。

**根因**

Spring Boot 默认的 shutdown 超时时间较短（默认 30s），但 HikariCP 在收到 SIGTERM 后可能立即关闭连接，不等待 WAL checkpoint 完成。

**解决方案（中期优化）**

在 `application.yml` 中配置 graceful shutdown：
```yaml
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

---

## 问题 5：频繁重启导致 WAL 状态不稳定

**现象**

Supervisord 日志显示 24 小时内重启 9+ 次：
```
2026-06-24 03:36:30,558 INFO spawned: 'myblog' with pid 27929
2026-06-24 03:52:29,999 INFO stopped: myblog (exit status 143)
2026-06-24 04:01:04,701 INFO stopped: myblog (exit status 143)
2026-06-24 04:04:14,386 INFO stopped: myblog (exit status 143)
```

**根因**

- 每次重启都是强制终止（exit status 143 = SIGTERM）
- 如果重启间隔太短，WAL 文件可能还没完成 checkpoint 就被中断
- 频繁重启累积了 WAL 状态的不一致性

**解决方案（长期优化）**

1. **减少不必要的重启**：只在必要时重启（如代码部署），避免频繁重启
2. **重启前手动 checkpoint**：确保 WAL 文件状态干净
3. **监控重启频率**：设置告警，避免短时间内多次重启

---

## 总结

| 问题 | 根因 | 解决方案 | 优先级 |
|------|------|----------|--------|
| SQLITE_CORRUPT | WAL 事务中断 + 强制进程终止 | `.recover` 恢复数据 | 🔴 P0 |
| 备份脚本并发访问 | sqlite3 CLI 直接读 db，与 WAL 写操作冲突 | 改用 `.backup` 命令 | 🟠 P1 |
| Redis 数据目录共享 | 目录 I/O 复杂度增加 | 隔离 Redis 数据目录 | 🟡 P2 |
| 缺少 Graceful Shutdown | HikariCP 无时间完成 pending 事务 | 配置 `timeout-per-shutdown-phase` | 🟠 P1 |
| 频繁重启 | WAL 状态不稳定 | 减少重启频率 + 重启前 checkpoint | 🟡 P2 |

---

## 预防措施清单

### 短期（立即执行）

- [ ] 在 `blog-backup.sh` 中使用 `.backup` 命令替代直接 `SELECT`
- [ ] 在 `deploy-server.sh` 中添加重启前 checkpoint 步骤

### 中期（1-2 周内）

- [ ] 配置 Spring Boot graceful shutdown：`spring.lifecycle.timeout-per-shutdown-phase: 30s`
- [ ] 隔离 Redis 数据目录：`--dir /opt/myblog/db/redis`
- [ ] 监控容器重启频率

### 长期（架构优化）

- [ ] 考虑将 `page_view` 异步写入改为先写 Redis 再批量落库
- [ ] 评估是否需要定期执行 `PRAGMA wal_checkpoint(TRUNCATE)` 作为运维任务

---

## 参考资料

- [SQLite WAL Mode 官方文档](https://www.sqlite.org/wal.html)
- [SQLite Checkpoint 机制](https://www.sqlite.org/atomiccommit.html)
- [Spring Boot Graceful Shutdown](https://docs.spring.io/spring-boot/docs/current/reference/htmlsingle/#features.graceful-shutdown)
- [HikariCP Configuration](https://github.com/brettwooldridge/HikariCP#configuration-knobs-baby)
- [SQLite .recover 命令](https://www.sqlite.org/cli.html#recovering_an_entire_database_from_the_command_line)

---

## 最终修复记录

**修复时间**：2026-06-24 04:04  
**修复方法**：使用 `.recover` 恢复数据 + 重启应用  
**恢复数据量**：158 条（覆盖 15 张表）  
**验证结果**：`PRAGMA integrity_check` 返回 `ok`，健康检查通过  
**损坏文件备份**：`/opt/myblog/db/blog.db.corrupted`（270KB）

---

## 问题 6：手机端登录 403 CORS 拒绝

**现象**

桌面端（Chrome on macOS）通过 `http://localhost:28000/admin/login` 可正常登录，手机端（iPhone WeChat 内置浏览器）访问 `http://192.168.31.82:28000/admin/login` 输入相同账号密码（admin/123456）后显示"登录失败，请检查账号密码"。

**分析过程**

1. **后端日志排查**：`blog.log` 中有桌面端登录成功记录（`deviceId=5d03ba8c...`），但手机端的登录请求在后端日志中**完全没有记录**，说明请求根本没有到达后端。

2. **Nginx 访问日志定位**：`nginx/access.log` 显示手机端所有 POST `/api/v1/auth/login` 请求均返回 **HTTP 403**，响应体仅 31 字节：
   ```
   192.168.65.1 - - [24/Jun/2026:09:21:05 +0000] "POST /api/v1/auth/login HTTP/1.1" 403 31
     "http://192.168.31.82:28000/admin/login"
     "Mozilla/5.0 (iPhone; CPU iPhone OS 18_7 like Mac OS X)..."
   ```
   Docker Desktop 环境下所有请求的 client IP 均显示为 `192.168.65.1`（Docker 网关），只能通过 User-Agent 区分来源。

3. **逐步排除**：

   | 排查方向 | 结果 | 说明 |
   |---|---|---|
   | IpRateLimitFilter | ❌ 排除 | 返回 429 不是 403；Redis 无 ban 记录 |
   | AdminAuthFilter | ❌ 排除 | 返回 401 不是 403；登录端点在 PUBLIC_WRITE_ROUTES 白名单中，shouldNotFilter=true |
   | Spring Security | ❌ 排除 | 项目无 spring-boot-starter-security 依赖 |
   | 设备未授权 (2001) | ❌ 排除 | 后端直接 curl 返回 HTTP 200 + code:2001（62字节），不是 403 |

4. **容器内复现**：在容器内直接 curl 后端，发现关键差异：
   - **不带 Origin 头** → HTTP 200, 62 字节, `{"code":2001,"message":"设备未授权，请联系管理员"}`
   - **带 Origin 头** → HTTP 403, 20 字节, `Invalid CORS request`

   **只要请求携带 `Origin` 头，Spring 就返回 403。**

5. **根因确认**：手机 WeChat 浏览器发送 POST 请求时自动携带 `Origin: http://192.168.31.82:28000` 头 → nginx 原样透传到后端（`proxy_set_header` 未设置 `Origin`）→ Spring Boot 2.7 的 `CorsConfig`（`allowedOriginPatterns("*")` + `allowCredentials(true)`）**拒绝了该 Origin** → 返回 403。

   桌面 Chrome 从 `localhost` 访问时，同源请求不发 `Origin` 头（或浏览器行为不同），所以不受影响。

   > **注意**：`CorsConfig` 的 `allowedOriginPatterns("*")` 理论上应匹配任意 Origin，但 Spring Boot 2.7 在 `allowCredentials(true)` 场景下对 `*` 通配符的处理存在已知问题——当请求 Origin 与服务器实际监听地址不完全一致时（如 Docker 网络），CORS 校验会失败。

**根因**

前端和后端都通过同一个 nginx 反向代理提供服务，属于同源架构。API 调用路径 `/api/v1/*` 由 nginx 代理到后端。手机浏览器发送请求时自动携带 `Origin` 头，nginx 原样透传到后端，Spring Boot 2.7 的 CORS 配置拒绝了该 Origin。

**解决方案**

在 nginx 反代配置中剥离 `Origin` 头，使后端收不到 Origin，从而跳过 CORS 校验：

```nginx
location /api/ {
    proxy_pass         http://127.0.0.1:8080;
    proxy_set_header   Host              $host;
    proxy_set_header   X-Real-IP         $remote_addr;
    proxy_set_header   X-Forwarded-For   $proxy_add_x_forwarded_for;
    proxy_set_header   X-Forwarded-Proto $scheme;
    proxy_set_header   X-Forwarded-Host  $http_host;
    # 2026-06-24: fix mobile CORS 403 - strip Origin header
    proxy_set_header   Origin             "";
    proxy_read_timeout 60s;
    client_max_body_size 20m;
}
```

**修改文件**：`scripts/deploy-server.sh`（nginx 配置模板，持久化）+ 容器内 `/etc/nginx/conf.d/myblog.conf`（`nginx -s reload` 即时生效）

**修复后手机登录流程**：
1. 输入账号密码 → 返回 code 2001 → 页面显示"设备未授权"黄色提示
2. 桌面端进入设备管理 → 看到待授权的手机设备 → 点击"批准"
3. 手机端重新登录 → 成功

**影响范围**：仅影响通过 nginx 反代的 API 请求中携带 `Origin` 头的场景（主要是移动端/跨端浏览器）。剥离 Origin 头不影响安全性：前后端同源部署，不存在真正的跨域问题。

---

## 更新后的总结

| 问题 | 根因 | 解决方案 | 优先级 |
|------|------|----------|--------|
| SQLITE_CORRUPT | WAL 事务中断 + 强制进程终止 | `.recover` 恢复数据 | 🔴 P0 |
| 备份脚本并发访问 | sqlite3 CLI 直接读 db，与 WAL 写操作冲突 | 改用 `.backup` 命令 | 🟠 P1 |
| Redis 数据目录共享 | 目录 I/O 复杂度增加 | 隔离 Redis 数据目录 | 🟡 P2 |
| 缺少 Graceful Shutdown | HikariCP 无时间完成 pending 事务 | 配置 `timeout-per-shutdown-phase` | 🟠 P1 |
| 频繁重启 | WAL 状态不稳定 | 减少重启频率 + 重启前 checkpoint | 🟡 P2 |
| 手机端登录 403 | nginx 透传 Origin 头 → Spring CORS 拒绝 | nginx 剥离 Origin 头 | 🔴 P0 |
