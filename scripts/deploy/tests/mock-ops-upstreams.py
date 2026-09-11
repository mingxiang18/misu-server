#!/usr/bin/env python3
"""Local HTTP/WS upstreams for the offline Nginx route harness."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import os
import threading

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
        if role == "backend":
            if self.path == "/ops/internal/health":
                self.write(200, "health-ok")
                return
            if self.path == "/ops/internal/proxy-auth":
                self.append("AUTH_REQUEST method=GET original=%s body=%s" % (
                    self.headers.get("X-Original-Method", ""),
                    self.headers.get("Content-Length", ""),
                ))
                if self.headers.get("X-Ops-Proxy-Key") != "test-secret" or "MISU_OPS_SESSION=" not in self.headers.get("Cookie", ""):
                    self.append("AUTH_REJECT console_not_reached")
                    self.write(401, "bad-secret")
                else:
                    self.write(204, headers={
                        "X-Ops-Upstream-Cookie": "UPSTREAM_TOKEN=clean",
                        "X-Ops-Upstream-Authorization": "Bearer upstream",
                    })
                return
            if self.path.startswith("/ops/ws/console/") and self.headers.get("Upgrade", "").lower() == "websocket":
                self.append(
                    "WS_BACKEND path=%s original=%s target=%s cookie=%s upstream-cookie=%s upstream-auth=%s"
                    % (
                        self.path,
                        self.headers.get("X-Ops-Original-URI", ""),
                        self.headers.get("X-Ops-Console-Target", ""),
                        self.headers.get("Cookie", ""),
                        self.headers.get("X-Ops-Upstream-Cookie", ""),
                        self.headers.get("X-Ops-Upstream-Authorization", ""),
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
        self.append("%s_UPSTREAM path=%s host=%s cookie=%s auth=%s" % (
            role.upper(),
            self.path,
            self.headers.get("Host", ""),
            self.headers.get("Cookie", ""),
            self.headers.get("Authorization", ""),
        ))
        self.write(200, "%s_UPSTREAM path=%s cookie=%s auth=%s" % (
            role.upper(),
            self.path,
            self.headers.get("Cookie", ""),
            self.headers.get("Authorization", ""),
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
