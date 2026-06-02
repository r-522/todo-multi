#!/usr/bin/env python3
"""Python (Flask) ToDo backend.

Web フレームワーク Flask を使用。PostgreSQL ドライバは psycopg2。
本番は gunicorn で起動する (Dockerfile 参照)。
"""
import os
import time

import psycopg2
from flask import Flask, Response, jsonify, request, send_from_directory

HERE = os.path.dirname(os.path.abspath(__file__))
with open(os.path.abspath(__file__), encoding="utf-8") as f:
    THIS_SOURCE = f.read()

app = Flask(__name__, static_folder="static", static_url_path="")


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
    return {"id": r[0], "title": r[1], "completed": r[2], "created_at": r[3].isoformat()}


@app.get("/")
def index():
    return send_from_directory(HERE + "/static", "index.html")


@app.get("/source")
def source():
    return Response(THIS_SOURCE, mimetype="text/plain")


@app.get("/api/todos")
def list_todos():
    conn = db_connect()
    try:
        cur = conn.cursor()
        cur.execute("SELECT id, title, completed, created_at FROM todos ORDER BY created_at, id")
        rows = [row_to_dict(r) for r in cur.fetchall()]
    finally:
        conn.close()
    return jsonify(rows)


@app.post("/api/todos")
def create_todo():
    title = (request.get_json(silent=True) or {}).get("title", "").strip()
    if not title:
        return jsonify({"error": "title is required"}), 400

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

    resp = jsonify({"todo": row_to_dict(row), "timings": {"db_connect_ms": db_connect_ms, "insert_ms": insert_ms}})
    resp.headers["X-DB-Connect-Ms"] = f"{db_connect_ms:.3f}"
    resp.headers["X-Insert-Ms"] = f"{insert_ms:.3f}"
    return resp, 201


@app.patch("/api/todos/<int:todo_id>")
def update_todo(todo_id):
    completed = bool((request.get_json(silent=True) or {}).get("completed"))
    conn = db_connect()
    try:
        cur = conn.cursor()
        cur.execute(
            "UPDATE todos SET completed = %s WHERE id = %s RETURNING id, title, completed, created_at",
            (completed, todo_id),
        )
        row = cur.fetchone()
        conn.commit()
    finally:
        conn.close()
    if not row:
        return jsonify({"error": "not found"}), 404
    return jsonify({"todo": row_to_dict(row)})


@app.delete("/api/todos/<int:todo_id>")
def delete_todo(todo_id):
    conn = db_connect()
    try:
        cur = conn.cursor()
        cur.execute("DELETE FROM todos WHERE id = %s", (todo_id,))
        conn.commit()
    finally:
        conn.close()
    return ("", 204)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "8080")))
