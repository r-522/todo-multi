// Go (Vanilla) ToDo backend.
// Web フレームワークを使わず標準ライブラリ net/http のみで実装。
// PostgreSQL は database/sql + github.com/lib/pq (pure Go ドライバ) を使用。
package main

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	_ "embed"

	_ "github.com/lib/pq"
)

//go:embed public/index.html
var indexHTML string

//go:embed main.go
var thisSource string

// Todo は todos テーブルの 1 行。
type Todo struct {
	ID        int64     `json:"id"`
	Title     string    `json:"title"`
	Completed bool      `json:"completed"`
	CreatedAt time.Time `json:"created_at"`
}

func getenv(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

// lib/pq の keyword DSN を組み立てる。
// Cloud Run では DB_HOST=/cloudsql/PROJECT:REGION:INSTANCE (unix socket)。
// ローカル/Auth Proxy では DB_HOST=127.0.0.1。
func dsn() string {
	q := func(s string) string {
		s = strings.ReplaceAll(s, `\`, `\\`)
		s = strings.ReplaceAll(s, `'`, `\'`)
		return "'" + s + "'"
	}
	return fmt.Sprintf("host=%s port=%s dbname=%s user=%s password=%s sslmode=disable",
		q(getenv("DB_HOST", "127.0.0.1")),
		q(getenv("DB_PORT", "5432")),
		q(getenv("DB_NAME", "tododb")),
		q(getenv("DB_USER", "todo")),
		q(getenv("DB_PASSWORD", "")),
	)
}

// 新規接続を 1 本張る (実接続を強制するため Ping する)。
func connect() (*sql.DB, error) {
	db, err := sql.Open("postgres", dsn())
	if err != nil {
		return nil, err
	}
	if err := db.Ping(); err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

func msSince(start time.Time) float64 {
	return float64(time.Since(start).Microseconds()) / 1000.0
}

func listHandler(w http.ResponseWriter, _ *http.Request) {
	db, err := connect()
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	defer db.Close()
	rows, err := db.Query("SELECT id, title, completed, created_at FROM todos ORDER BY created_at, id")
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	defer rows.Close()
	todos := []Todo{}
	for rows.Next() {
		var t Todo
		rows.Scan(&t.ID, &t.Title, &t.Completed, &t.CreatedAt)
		todos = append(todos, t)
	}
	writeJSON(w, 200, todos)
}

// ★ DB 接続時間と INSERT 時間を個別に計測する
func createHandler(w http.ResponseWriter, r *http.Request) {
	var in struct {
		Title string `json:"title"`
	}
	json.NewDecoder(r.Body).Decode(&in)
	title := strings.TrimSpace(in.Title)
	if title == "" {
		writeJSON(w, 400, map[string]string{"error": "title is required"})
		return
	}

	t0 := time.Now()
	db, err := connect()
	dbConnectMs := msSince(t0)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	defer db.Close()

	t2 := time.Now()
	var t Todo
	err = db.QueryRow(
		"INSERT INTO todos (title) VALUES ($1) RETURNING id, title, completed, created_at",
		title,
	).Scan(&t.ID, &t.Title, &t.Completed, &t.CreatedAt)
	insertMs := msSince(t2)
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}

	w.Header().Set("X-DB-Connect-Ms", fmt.Sprintf("%.3f", dbConnectMs))
	w.Header().Set("X-Insert-Ms", fmt.Sprintf("%.3f", insertMs))
	writeJSON(w, 201, map[string]any{
		"todo":    t,
		"timings": map[string]float64{"db_connect_ms": dbConnectMs, "insert_ms": insertMs},
	})
}

func updateHandler(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	var in struct {
		Completed bool `json:"completed"`
	}
	json.NewDecoder(r.Body).Decode(&in)
	db, err := connect()
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	defer db.Close()
	var t Todo
	err = db.QueryRow(
		"UPDATE todos SET completed = $1 WHERE id = $2 RETURNING id, title, completed, created_at",
		in.Completed, id,
	).Scan(&t.ID, &t.Title, &t.Completed, &t.CreatedAt)
	if err == sql.ErrNoRows {
		writeJSON(w, 404, map[string]string{"error": "not found"})
		return
	}
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	writeJSON(w, 200, map[string]any{"todo": t})
}

func deleteHandler(w http.ResponseWriter, r *http.Request) {
	id := r.PathValue("id")
	db, err := connect()
	if err != nil {
		writeJSON(w, 500, map[string]string{"error": err.Error()})
		return
	}
	defer db.Close()
	db.Exec("DELETE FROM todos WHERE id = $1", id)
	w.WriteHeader(204)
}

func main() {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write([]byte(indexHTML))
	})
	mux.HandleFunc("GET /source", func(w http.ResponseWriter, _ *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		w.Write([]byte(thisSource))
	})
	mux.HandleFunc("GET /api/todos", listHandler)
	mux.HandleFunc("POST /api/todos", createHandler)
	mux.HandleFunc("PATCH /api/todos/{id}", updateHandler)
	mux.HandleFunc("DELETE /api/todos/{id}", deleteHandler)

	port := getenv("PORT", "8080")
	log.Println("go-vanilla listening on " + port)
	log.Fatal(http.ListenAndServe("0.0.0.0:"+port, mux))
}
