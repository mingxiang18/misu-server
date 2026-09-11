#!/usr/bin/env python3
"""Local HTTP/WS upstreams for the offline Nginx route harness."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
import threading
import time

LOG = os.environ.get("OPS_MOCK_LOG", "/tmp/misu-ops-mock.log")


class Handler(BaseHTTPRequestHandler):
    server_version = "misu-ops-test"

    def log_message(self, *_args):
        pass

    def write(self, status, body="", headers=None):
        self.send_response(status)
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        if body:
            self.wfile.write(body.encode())

    def append(self, message):
        with open(LOG, "a", encoding="utf-8") as output:
            output.write(message + "\n")

    def do_GET(self):
        role = self.server.role
        if role == "headlamp" and "/pods/demo/log" in self.path:
            self.append("HEADLAMP_LOG_STREAM path=%s" % self.path)
            self.send_response(200)
            self.send_header("Content-Type", "text/plain")
            self.send_header("Cache-Control", "no-cache")
            self.end_headers()
            self.wfile.write(b"log-line-1\n")
            self.wfile.flush()
            time.sleep(1)
            self.wfile.write(b"log-line-2\n")
            self.wfile.flush()
            return
        if role == "backend":
            if self.path == "/ops/internal/health":
                self.write(200, "health-ok")
                return
            if self.path == "/ops/internal/proxy-auth":
                original_uri = self.headers.get("X-Original-URI", "")
                target = self.headers.get("X-Ops-Target", "")
                original_uri_valid = original_uri.startswith(("/nacos/", "/ops/headlamp/"))
                target_valid = target in ("nacos", "headlamp")
                forwarding_headers_cleared = not any(
                    self.headers.get(header, "") for header in
                    ("Forwarded", "X-Forwarded-For", "X-Real-IP", "X-Forwarded-Port")
                )
                self.append("AUTH_REQUEST method=GET original=%s body=%s" % (
                    self.headers.get("X-Original-Method", ""),
                    self.headers.get("Content-Length", ""),
                ))
                self.append("AUTH_REQUEST original_uri_present=%s target_present=%s original_uri_valid=%s target_valid=%s forwarding_headers_cleared=%s" % (
                    bool(original_uri), bool(target), original_uri_valid, target_valid, forwarding_headers_cleared
                ))
                if (self.headers.get("X-Ops-Proxy-Key") != "test-secret"
                        or "MISU_OPS_SESSION=" not in self.headers.get("Cookie", "")
                        or not original_uri_valid or not target_valid or not forwarding_headers_cleared):
                    self.append("AUTH_REJECT console_not_reached")
                    self.write(401, "bad-secret")
                else:
                    self.write(204, headers={
                        "X-Ops-User": "admin",
                        "X-Ops-Upstream-Cookie": "UPSTREAM_TOKEN=clean",
                        "X-Ops-Upstream-Authorization": "Bearer upstream",
                    })
                return
            if self.path.startswith("/ops/ws/console/") and self.headers.get("Upgrade", "").lower() == "websocket":
                self.append(
                    "WS_BACKEND_FORWARDING forwarded=%s xff=%s real=%s port=%s"
                    % (
                        self.headers.get("Forwarded", ""),
                        self.headers.get("X-Forwarded-For", ""),
                        self.headers.get("X-Real-IP", ""),
                        self.headers.get("X-Forwarded-Port", ""),
                    )
                )
                self.append(
                    "WS_BACKEND path=%s original=%s target=%s cookie=%s upstream-cookie=%s upstream-auth=%s user=%s groups=%s group=%s email=%s id_token=%s"
                    % (
                        self.path,
                        self.headers.get("X-Ops-Original-URI", ""),
                        self.headers.get("X-Ops-Console-Target", ""),
                        self.headers.get("Cookie", ""),
                        self.headers.get("X-Ops-Upstream-Cookie", ""),
                        self.headers.get("X-Ops-Upstream-Authorization", ""),
                        self.headers.get("X-Forwarded-User", ""),
                        self.headers.get("X-Forwarded-Groups", ""),
                        self.headers.get("X-Forwarded-Group", ""),
                        self.headers.get("X-Forwarded-Email", ""),
                        self.headers.get("X-Forwarded-Id-Token", ""),
                    )
                )
                self.write(101, headers={
                    "Upgrade": "websocket",
                    "Connection": "Upgrade",
                    "Sec-WebSocket-Accept": "mock",
                })
                return
            if self.path.startswith("/ops/api/"):
                self.write(200, "API_BACKEND path=%s host=%s auth=%s cookie=%s" % (
                    self.path,
                    self.headers.get("Host", ""),
                    self.headers.get("Authorization", ""),
                    self.headers.get("Cookie", ""),
                ))
                return
        self.append("%s_UPSTREAM path=%s host=%s cookie=%s auth=%s user=%s groups=%s group=%s email=%s id_token=%s" % (
            role.upper(),
            self.path,
            self.headers.get("Host", ""),
            self.headers.get("Cookie", ""),
            self.headers.get("Authorization", ""),
            self.headers.get("X-Forwarded-User", ""),
            self.headers.get("X-Forwarded-Groups", ""),
            self.headers.get("X-Forwarded-Group", ""),
            self.headers.get("X-Forwarded-Email", ""),
            self.headers.get("X-Forwarded-Id-Token", ""),
        ))
        self.write(200, "%s_UPSTREAM path=%s cookie=%s auth=%s user=%s groups=%s group=%s email=%s id_token=%s" % (
            role.upper(),
            self.path,
            self.headers.get("Cookie", ""),
            self.headers.get("Authorization", ""),
            self.headers.get("X-Forwarded-User", ""),
            self.headers.get("X-Forwarded-Groups", ""),
            self.headers.get("X-Forwarded-Group", ""),
            self.headers.get("X-Forwarded-Email", ""),
            self.headers.get("X-Forwarded-Id-Token", ""),
        ))

    def do_POST(self):
        role = self.server.role
        length = int(self.headers.get("Content-Length", "0"))
        body = self.rfile.read(length).decode("utf-8", errors="replace")
        if role == "backend" and self.path == "/ops/api/console-sessions/exchange":
            forwarded = ",".join(self.headers.get(header, "") for header in
                                  ("Forwarded", "X-Forwarded-For", "X-Real-IP", "X-Forwarded-Port"))
            self.append("EXCHANGE headers=%s" % forwarded)
            if any(self.headers.get(header, "") for header in
                   ("Forwarded", "X-Forwarded-For", "X-Real-IP", "X-Forwarded-Port")):
                self.write(403, "proxy-remote-address-rewritten")
            elif "ticket=valid" in body:
                self.write(303, headers={
                    "Location": "/nacos/",
                    "Set-Cookie": "MISU_OPS_SESSION=nacos-session; Path=/nacos/; HttpOnly; Secure; SameSite=None",
                    "X-Frame-Options": "DENY",
                    "Content-Security-Policy": "frame-ancestors https://evil.example",
                })
            else:
                self.write(401, "invalid-ticket")
            return
        self.append("%s_UPSTREAM_POST method=POST path=%s body=%s cookie=%s auth=%s" % (
            role.upper(),
            self.path,
            body,
            self.headers.get("Cookie", ""),
            self.headers.get("Authorization", ""),
        ))
        self.write(200, "%s_UPSTREAM_POST path=%s body=%s cookie=%s auth=%s" % (
            role.upper(),
            self.path,
            body,
            self.headers.get("Cookie", ""),
            self.headers.get("Authorization", ""),
        ))


def serve(port, role):
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    server.role = role
    server.serve_forever()


disabled = {role.strip() for role in os.environ.get("OPS_MOCK_DISABLE", "").split(",") if role.strip()}
for port, role in ((30264, "backend"), (18848, "nacos"), (18080, "headlamp")):
    if role in disabled:
        continue
    threading.Thread(target=serve, args=(port, role), daemon=True).start()
threading.Event().wait()
