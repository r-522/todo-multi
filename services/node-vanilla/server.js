// Node.js (Vanilla) ToDo backend
// Web フレームワークを使わず、標準モジュール node:http だけで実装。
// PostgreSQL ドライバは pg を使用 (Node 標準には PG ドライバが無いため)。

const http = require("node:http");
const fs = require("node:fs");
const path = require("node:path");
const { Client } = require("pg");

const PORT = process.env.PORT || 8080;
const INDEX_HTML = fs.readFileSync(path.join(__dirname, "public", "index.html"), "utf8");
const THIS_SOURCE = fs.readFileSync(__filename, "utf8");

// 接続情報は環境変数から取得。
// Cloud Run では DB_HOST=/cloudsql/PROJECT:REGION:INSTANCE (unix socket)。
// ローカル/Auth Proxy では DB_HOST=127.0.0.1。
function dbConfig() {
  return {
    host: process.env.DB_HOST || "127.0.0.1",
    port: parseInt(process.env.DB_PORT || "5432", 10),
    database: process.env.DB_NAME || "tododb",
    user: process.env.DB_USER || "todo",
    password: process.env.DB_PASSWORD || "",
  };
}

// 計測しないエンドポイント用: 新規接続 -> クエリ -> 切断
async function withClient(fn) {
  const client = new Client(dbConfig());
  await client.connect();
  try {
    return await fn(client);
  } finally {
    await client.end();
  }
}

function send(res, status, body, headers) {
  res.writeHead(status, Object.assign({ "Content-Type": "application/json; charset=utf-8" }, headers || {}));
  res.end(typeof body === "string" ? body : JSON.stringify(body));
}

function readBody(req) {
  return new Promise((resolve) => {
    let data = "";
    req.on("data", (c) => (data += c));
    req.on("end", () => {
      try { resolve(data ? JSON.parse(data) : {}); } catch { resolve({}); }
    });
  });
}

async function listTodos(res) {
  const rows = await withClient((c) =>
    c.query("SELECT id, title, completed, created_at FROM todos ORDER BY created_at, id")
  );
  send(res, 200, rows.rows);
}

// ★ DB 接続時間と INSERT 時間を個別に計測する
async function createTodo(req, res) {
  const body = await readBody(req);
  const title = (body.title || "").toString().trim();
  if (!title) return send(res, 400, { error: "title is required" });

  const t0 = process.hrtime.bigint();
  const client = new Client(dbConfig());
  await client.connect();
  const t1 = process.hrtime.bigint();
  const dbConnectMs = Number(t1 - t0) / 1e6;

  const t2 = process.hrtime.bigint();
  const result = await client.query(
    "INSERT INTO todos (title) VALUES ($1) RETURNING id, title, completed, created_at",
    [title]
  );
  const t3 = process.hrtime.bigint();
  const insertMs = Number(t3 - t2) / 1e6;
  await client.end();

  send(res, 201, { todo: result.rows[0], timings: { db_connect_ms: dbConnectMs, insert_ms: insertMs } }, {
    "X-DB-Connect-Ms": dbConnectMs.toFixed(3),
    "X-Insert-Ms": insertMs.toFixed(3),
  });
}

async function updateTodo(res, id, req) {
  const body = await readBody(req);
  const completed = !!body.completed;
  const rows = await withClient((c) =>
    c.query("UPDATE todos SET completed = $1 WHERE id = $2 RETURNING id, title, completed, created_at", [completed, id])
  );
  if (!rows.rows.length) return send(res, 404, { error: "not found" });
  send(res, 200, { todo: rows.rows[0] });
}

async function deleteTodo(res, id) {
  await withClient((c) => c.query("DELETE FROM todos WHERE id = $1", [id]));
  res.writeHead(204).end();
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, "http://localhost");
    const p = url.pathname;

    if (req.method === "GET" && (p === "/" || p === "/index.html")) {
      return send(res, 200, INDEX_HTML, { "Content-Type": "text/html; charset=utf-8" });
    }
    if (req.method === "GET" && p === "/source") {
      return send(res, 200, THIS_SOURCE, { "Content-Type": "text/plain; charset=utf-8" });
    }
    if (p === "/api/todos") {
      if (req.method === "GET") return await listTodos(res);
      if (req.method === "POST") return await createTodo(req, res);
    }
    const m = p.match(/^\/api\/todos\/(\d+)$/);
    if (m) {
      const id = m[1];
      if (req.method === "PATCH") return await updateTodo(res, id, req);
      if (req.method === "DELETE") return await deleteTodo(res, id);
    }
    send(res, 404, { error: "not found" });
  } catch (err) {
    console.error(err);
    send(res, 500, { error: String(err.message || err) });
  }
});

server.listen(PORT, () => console.log("node-vanilla listening on " + PORT));
