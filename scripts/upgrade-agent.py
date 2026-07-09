#!/usr/bin/env python3
"""
升级代理（upgrade-agent） — 20260708-DEV-001

宿主机升级代理，监听 127.0.0.1:28081，接收升级请求并调用 deploy-server.sh 执行。
通过 SSE 流式返回升级日志。

使用 Python 标准库（http.server + threading），无需安装额外依赖。

启动：python3 upgrade-agent.py
systemd：systemctl start upgrade-agent
"""

import fcntl
import json
import os
import subprocess
import sys
import time
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler
from socketserver import ThreadingMixIn
from urllib.parse import urlparse, parse_qs

# ============ 配置 ============

HOST = os.environ.get("AGENT_HOST", "127.0.0.1")
PORT = int(os.environ.get("AGENT_PORT", "28081"))
DEPLOY_SCRIPT = os.environ.get("DEPLOY_SCRIPT", "/opt/myblog/scripts/deploy-server.sh")
LOCK_FILE = os.environ.get("LOCK_FILE", "/tmp/upgrade.lock")

# ============ 升级状态 ============

class UpgradeState:
    def __init__(self):
        self._lock = threading.Lock()
        self.status = "idle"
        self.logs = []
        self.version = None
        self.mode = None
        self.started_at = None
        self.finished_at = None

    def start(self, version, mode):
        with self._lock:
            self.status = "running"
            self.logs = []
            self.version = version
            self.mode = mode
            self.started_at = time.time()
            self.finished_at = None

    def append_log(self, line):
        with self._lock:
            self.logs.append(line)

    def finish(self, success):
        with self._lock:
            self.status = "completed" if success else "failed"
            self.finished_at = time.time()

    def snapshot(self):
        with self._lock:
            return {
                "status": self.status,
                "version": self.version,
                "mode": self.mode,
                "startedAt": self.started_at,
                "finishedAt": self.finished_at,
                "logCount": len(self.logs),
            }

state = UpgradeState()

# ============ 升级锁 ============

class UpgradeLock:
    def __init__(self, path):
        self.path = path
        self._fd = None

    def acquire(self):
        try:
            self._fd = open(self.path, "w")
            fcntl.flock(self._fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self._fd.write(str(os.getpid()))
            self._fd.flush()
            return True
        except (IOError, OSError):
            if self._fd:
                self._fd.close()
                self._fd = None
            return False

    def release(self):
        if self._fd:
            try:
                fcntl.flock(self._fd, fcntl.LOCK_UN)
                self._fd.close()
            except Exception:
                pass
            self._fd = None
        try:
            os.unlink(self.path)
        except FileNotFoundError:
            pass

upgrade_lock = UpgradeLock(LOCK_FILE)

# ============ 升级执行 ============

def run_deploy(version, mode, import_db):
    """在后台线程中执行 deploy-server.sh"""
    try:
        env = os.environ.copy()
        env["DEPLOY_MODE"] = mode
        env["GITHUB_REPO"] = env.get("GITHUB_REPO", "YuanYii/my-blog-prov")
        if import_db:
            env["IMPORT_DB"] = "1"

        # 统一方案：version 为空时不传版本号，让 deploy-server.sh 自动获取最新 release
        if version:
            cmd = ["bash", DEPLOY_SCRIPT, version]
        else:
            cmd = ["bash", DEPLOY_SCRIPT]
        state.append_log(f"[upgrade-agent] 开始执行: {' '.join(cmd)}")

        process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            env=env,
            bufsize=1,
        )

        for line in iter(process.stdout.readline, ""):
            line = line.rstrip("\n")
            if line:
                state.append_log(line)
                # 检测 deploy-server.sh 输出的实际版本号（"最新版本: vX.Y.Z"）
                if "最新版本:" in line and version is None:
                    actual_version = line.split("最新版本:")[-1].strip()
                    if actual_version:
                        with state._lock:
                            state.version = actual_version

        process.wait()
        success = process.returncode == 0

        state.append_log(f"[upgrade-agent] 升级{'成功' if success else '失败'} (exit={process.returncode})")
        state.finish(success)

        # 直接更新数据库中的升级记录状态
        mark_upgrade_record(success)

    except Exception as e:
        err_msg = f"[upgrade-agent] 异常: {e}"
        state.append_log(err_msg)
        state.finish(False)
    finally:
        upgrade_lock.release()


def mark_upgrade_record(success):
    """更新数据库中最近一条 RUNNING 的升级记录状态"""
    import sqlite3
    db_path = os.environ.get("DB_PATH", "/opt/myblog/db/blog.db")
    try:
        conn = sqlite3.connect(db_path, timeout=10)
        cursor = conn.cursor()
        cursor.execute("SELECT id FROM upgrade_record WHERE status = 'RUNNING' ORDER BY id DESC LIMIT 1")
        row = cursor.fetchone()
        if row:
            record_id = row[0]
            status = "SUCCESS" if success else "FAILED"
            error_msg = None if success else "升级失败"
            cursor.execute(
                "UPDATE upgrade_record SET status = ?, finished_at = datetime('now', 'localtime'), error_message = ? WHERE id = ?",
                (status, error_msg, record_id)
            )
            conn.commit()
            state.append_log(f"[upgrade-agent] 升级记录 #{record_id} 标记为 {status}")
        else:
            state.append_log("[upgrade-agent] 未找到 RUNNING 状态的升级记录")
        conn.close()
    except Exception as e:
        state.append_log(f"[upgrade-agent] 更新数据库失败: {e}")

# ============ HTTP 处理器 ============

class UpgradeHandler(BaseHTTPRequestHandler):
    """处理 HTTP 请求"""

    def log_message(self, format, *args):
        pass  # 禁用默认日志

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path

        if path == "/status":
            self._json_response(200, state.snapshot())
        elif path == "/logs":
            query = parse_qs(parsed.query)
            limit = int(query.get("limit", ["200"])[0])
            with state._lock:
                logs = state.logs[-limit:]
            self._json_response(200, {"logs": logs, "total": len(state.logs)})
        elif path == "/health":
            self._json_response(200, {"status": "UP", "service": "upgrade-agent"})
        else:
            self._json_response(404, {"error": "not found"})

    def do_POST(self):
        parsed = urlparse(self.path)

        if parsed.path == "/upgrade":
            self._handle_upgrade()
        else:
            self._json_response(404, {"error": "not found"})

    def _handle_upgrade(self):
        # 读取请求体
        content_length = int(self.headers.get("Content-Length", 0))
        body = self.rfile.read(content_length)

        try:
            data = json.loads(body) if body else {}
        except json.JSONDecodeError:
            self._json_response(400, {"code": 400, "message": "请求体必须是 JSON"})
            return

        version = data.get("version", "").strip()
        mode = data.get("mode", "full").strip()
        import_db = data.get("importDb", False)
        confirm = data.get("confirm", "")

        # 统一方案：version 为空时让 deploy-server.sh 自动获取最新版本
        # 不再强制要求传入 version
        if mode not in ("full", "init"):
            self._json_response(400, {"code": 400, "message": "mode 必须是 full 或 init"})
            return
        if import_db and confirm != "确认":
            self._json_response(400, {"code": 400, "message": "importDb=true 时需输入 confirm=\"确认\""})
            return

        # 获取升级锁
        if not upgrade_lock.acquire():
            self._json_response(409, {"code": 409, "message": "升级进行中，请稍后再试"})
            return

        # 启动升级（version 可能为空，run_deploy 会自动获取最新版本）
        state.start(version or "latest", mode)

        # 设置 SSE 响应头
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()

        # 启动升级线程
        deploy_thread = threading.Thread(
            target=run_deploy,
            args=(version, mode, import_db),
            daemon=True
        )
        deploy_thread.start()

        # 流式发送日志
        sent_index = 0
        client_disconnected = False
        while True:
            with state._lock:
                current_logs = list(state.logs)
                current_status = state.status

            # 发送新日志（客户端断开时不阻塞）
            if not client_disconnected:
                while sent_index < len(current_logs):
                    log_line = current_logs[sent_index]
                    try:
                        self.wfile.write(f"event: log\ndata: {log_line}\n\n".encode())
                        self.wfile.flush()
                    except BrokenPipeError:
                        client_disconnected = True
                        break
                    sent_index += 1

            # 检查是否完成
            if current_status in ("completed", "failed"):
                # 发送完成事件（客户端可能已断开）
                if not client_disconnected:
                    try:
                        success = current_status == "completed"
                        done_data = json.dumps({"success": success, "exitCode": 0 if success else 1})
                        self.wfile.write(f"event: done\ndata: {done_data}\n\n".encode())
                        self.wfile.flush()
                    except BrokenPipeError:
                        pass
                break

            time.sleep(0.1)

    def _json_response(self, code, data):
        try:
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(data, ensure_ascii=False).encode())
        except BrokenPipeError:
            pass  # 客户端已断开，忽略

# ============ 多线程 HTTP 服务器 ============

class ThreadingHTTPServer(ThreadingMixIn, HTTPServer):
    """多线程 HTTP 服务器，避免 SSE 连接阻塞其他请求"""
    daemon_threads = True  # 主线程退出时，子线程自动终止

# ============ 应用入口 ============

def main():
    print(f"[upgrade-agent] 启动 {HOST}:{PORT}")
    print(f"[upgrade-agent] deploy script: {DEPLOY_SCRIPT}")
    print(f"[upgrade-agent] lock file: {LOCK_FILE}")

    server = ThreadingHTTPServer((HOST, PORT), UpgradeHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()

if __name__ == "__main__":
    main()
