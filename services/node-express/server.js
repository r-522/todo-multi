// Node.js (Express) ToDo backend
// Web フレームワーク Express を使用。PostgreSQL ドライバは pg。

const fs = require("node:fs");
const path = require("node:path");
const express = require("express");
const { Client } = require("pg");

const PORT = process.env.PORT || 8080;
const THIS_SOURCE = fs.readFileSync(__filename, "utf8");

function dbConfig() {
  return {
    host: process.env.DB_HOST || "127.0.0.1",
    port: parseInt(process.env.DB_PORT || "5432", 10),
    database: process.env.DB_NAME || "tododb",
    user: process.env.DB_USER || "todo",
    password: process.env.DB_PASSWORD || "",
  };
}

async function withClient(fn) {
  const client = new Client(dbConfig());
  await client.connect();
  try {
    return await fn(client);
  } finally {
    await client.end();
  }
}

const app = express();
app.use(express.json());
app.use(express.static(path.join(__dirname, "public")));

app.get("/source", (_req, res) => {
  res.type("text/plain; charset=utf-8").send(THIS_SOURCE);
});

app.get("/api/todos", async (_req, res, next) => {
  try {
    const rows = await withClient((c) =>
      c.query("SELECT id, title, completed, created_at FROM todos ORDER BY created_at, id")
    );
    res.json(rows.rows);
  } catch (e) { next(e); }
});

// ★ DB 接続時間と INSERT 時間を個別に計測する
app.post("/api/todos", async (req, res, next) => {
  try {
    const title = (req.body.title || "").toString().trim();
    if (!title) return res.status(400).json({ error: "title is required" });

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

    res.set("X-DB-Connect-Ms", dbConnectMs.toFixed(3));
    res.set("X-Insert-Ms", insertMs.toFixed(3));
    res.status(201).json({ todo: result.rows[0], timings: { db_connect_ms: dbConnectMs, insert_ms: insertMs } });
  } catch (e) { next(e); }
});

app.patch("/api/todos/:id", async (req, res, next) => {
  try {
    const completed = !!req.body.completed;
    const rows = await withClient((c) =>
      c.query("UPDATE todos SET completed = $1 WHERE id = $2 RETURNING id, title, completed, created_at",
        [completed, req.params.id])
    );
    if (!rows.rows.length) return res.status(404).json({ error: "not found" });
    res.json({ todo: rows.rows[0] });
  } catch (e) { next(e); }
});

app.delete("/api/todos/:id", async (req, res, next) => {
  try {
    await withClient((c) => c.query("DELETE FROM todos WHERE id = $1", [req.params.id]));
    res.status(204).end();
  } catch (e) { next(e); }
});

app.use((err, _req, res, _next) => {
  console.error(err);
  res.status(500).json({ error: String(err.message || err) });
});

app.listen(PORT, () => console.log("node-express listening on " + PORT));
