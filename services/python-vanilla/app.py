#!/usr/bin/env python3
"""Python (Vanilla) ToDo backend.

Web フレームワークを使わず、標準ライブラリ http.server だけで実装。
PostgreSQL ドライバは psycopg2 を使用 (標準には PG ドライバが無いため)。
"""
import json
import os
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import psycopg2

HERE = os.path.dirname(os.path.abspath(__file__))
PORT = int(os.environ.get("PORT", "8080"))

with open(os.path.join(HERE, "public", "index.html"), encoding="utf-8") as f:
    INDEX_HTML = f.read()
with open(os.path.abspath(__file__), encoding="utf-8") as f:
    THIS_SOURCE = f.read()

ID_PATH = re.compile(r"^/api/todos/(\d+)$")


def db_connect():
    """新規接続を 1 本張って返す。

    Cloud Run では DB_HOST=/cloudsql/PROJECT:REGION:INSTANCE (unix socket)。
    ローカル/Auth Proxy では DB_HOST=127.0.0.1。
    """
    return psycopg2.connect(
        host=os.environ.get("DB_HOST", "127.0.0.1"),
        port=os.environ.get("DB_PORT", "5432"),
        dbname=os.environ.get("DB_NAME", "tododb"),
        user=os.environ.get("DB_USER", "todo"),
        password=os.environ.get("DB_PASSWORD", ""),
    )


def row_to_dict(r):
    return {
        "id": r[0],
        "title": r[1],
        "completed": r[2],
        "created_at": r[3].isoformat(),
    }


class Handler(BaseHTTPRequestHandler):
    # ログを簡潔に
    def log_message(self, *args):
        pass

    def _send(self, status, body, ctype="application/json; charset=utf-8", extra=None):
        data = body if isinstance(body, (bytes, str)) else json.dumps(body)
        if isinstance(data, str):
            data = data.encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        self.wfile.write(data)

    def _body_json(self):
        length = int(self.headers.get("Content-Length", "0") or "0")
        if not length:
            return {}
        try:
            return json.loads(self.rfile.read(length) or b"{}")
        except Exception:
            return {}

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            return self._send(200, INDEX_HTML, "text/html; charset=utf-8")
        if self.path == "/source":
            return self._send(200, THIS_SOURCE, "text/plain; charset=utf-8")
        if self.path == "/api/todos":
            conn = db_connect()
            try:
                cur = conn.cursor()
                cur.execute("SELECT id, title, completed, created_at FROM todos ORDER BY created_at, id")
                rows = [row_to_dict(r) for r in cur.fetchall()]
            finally:
                conn.close()
            return self._send(200, rows)
        return self._send(404, {"error": "not found"})

    def do_POST(self):
        if self.path != "/api/todos":
            return self._send(404, {"error": "not found"})
        title = (self._body_json().get("title") or "").strip()
        if not title:
            return self._send(400, {"error": "title is required"})

        # ★ DB 接続時間と INSERT 時間を個別に計測する
        t0 = time.perf_counter()
        conn = db_connect()
        t1 = time.perf_counter()
        db_connect_ms = (t1 - t0) * 1000.0

        t2 = time.perf_counter()
        cur = conn.cursor()
        cur.execute(
            "INSERT INTO todos (title) VALUES (%s) RETURNING id, title, completed, created_at",
            (title,),
        )
        row = cur.fetchone()
        conn.commit()
        t3 = time.perf_counter()
        insert_ms = (t3 - t2) * 1000.0
        conn.close()

        self._send(
            201,
            {"todo": row_to_dict(row), "timings": {"db_connect_ms": db_connect_ms, "insert_ms": insert_ms}},
            extra={"X-DB-Connect-Ms": f"{db_connect_ms:.3f}", "X-Insert-Ms": f"{insert_ms:.3f}"},
        )

    def do_PATCH(self):
        m = ID_PATH.match(self.path)
        if not m:
            return self._send(404, {"error": "not found"})
        completed = bool(self._body_json().get("completed"))
        conn = db_connect()
        try:
            cur = conn.cursor()
            cur.execute(
                "UPDATE todos SET completed = %s WHERE id = %s RETURNING id, title, completed, created_at",
                (completed, m.group(1)),
            )
            row = cur.fetchone()
            conn.commit()
        finally:
            conn.close()
        if not row:
            return self._send(404, {"error": "not found"})
        return self._send(200, {"todo": row_to_dict(row)})

    def do_DELETE(self):
        m = ID_PATH.match(self.path)
        if not m:
            return self._send(404, {"error": "not found"})
        conn = db_connect()
        try:
            cur = conn.cursor()
            cur.execute("DELETE FROM todos WHERE id = %s", (m.group(1),))
            conn.commit()
        finally:
            conn.close()
        self._send(204, b"")


if __name__ == "__main__":
    print(f"python-vanilla listening on {PORT}")
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
