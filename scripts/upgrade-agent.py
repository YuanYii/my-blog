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

        cmd = ["bash", DEPLOY_SCRIPT, version]
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

        process.wait()
        success = process.returncode == 0

        state.append_log(f"[upgrade-agent] 升级{'成功' if success else '失败'} (exit={process.returncode})")
        state.finish(success)

    except Exception as e:
        err_msg = f"[upgrade-agent] 异常: {e}"
        state.append_log(err_msg)
        state.finish(False)
    finally:
        upgrade_lock.release()

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

        # 参数校验
        if not version:
            self._json_response(400, {"code": 400, "message": "version 不能为空"})
            return
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

        # 启动升级
        state.start(version, mode)

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
