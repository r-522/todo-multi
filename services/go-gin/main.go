// Go (Gin) ToDo backend.
// Web フレームワーク Gin を使用。PostgreSQL は database/sql + github.com/lib/pq。
package main

import (
	"database/sql"
	"fmt"
	"log"
	"net/http"
	"os"
	"strings"
	"time"

	_ "embed"

	"github.com/gin-gonic/gin"
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
func dsn() string {
	q := func(s string) string {
		s = strings.ReplaceAll(s, `\`, `\\`)
		s = strings.ReplaceAll(s, `'`, `\'`)
		return "'" + s + "'"
	}
	return fmt.Sprintf("host=%s port=%s dbname=%s user=%s password=%s sslmode=disable",
		q(getenv("DB_HOST", "127.0.0.1")), q(getenv("DB_PORT", "5432")),
		q(getenv("DB_NAME", "tododb")), q(getenv("DB_USER", "todo")),
		q(getenv("DB_PASSWORD", "")))
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

func msSince(start time.Time) float64 { return float64(time.Since(start).Microseconds()) / 1000.0 }

func listTodos(c *gin.Context) {
	db, err := connect()
	if err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}
	defer db.Close()
	rows, err := db.Query("SELECT id, title, completed, created_at FROM todos ORDER BY created_at, id")
	if err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}
	defer rows.Close()
	todos := []Todo{}
	for rows.Next() {
		var t Todo
		rows.Scan(&t.ID, &t.Title, &t.Completed, &t.CreatedAt)
		todos = append(todos, t)
	}
	c.JSON(200, todos)
}

// ★ DB 接続時間と INSERT 時間を個別に計測する
func createTodo(c *gin.Context) {
	var in struct {
		Title string `json:"title"`
	}
	c.ShouldBindJSON(&in)
	title := strings.TrimSpace(in.Title)
	if title == "" {
		c.JSON(400, gin.H{"error": "title is required"})
		return
	}

	t0 := time.Now()
	db, err := connect()
	dbConnectMs := msSince(t0)
	if err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
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
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}

	c.Header("X-DB-Connect-Ms", fmt.Sprintf("%.3f", dbConnectMs))
	c.Header("X-Insert-Ms", fmt.Sprintf("%.3f", insertMs))
	c.JSON(201, gin.H{
		"todo":    t,
		"timings": gin.H{"db_connect_ms": dbConnectMs, "insert_ms": insertMs},
	})
}

func updateTodo(c *gin.Context) {
	var in struct {
		Completed bool `json:"completed"`
	}
	c.ShouldBindJSON(&in)
	db, err := connect()
	if err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}
	defer db.Close()
	var t Todo
	err = db.QueryRow(
		"UPDATE todos SET completed = $1 WHERE id = $2 RETURNING id, title, completed, created_at",
		in.Completed, c.Param("id"),
	).Scan(&t.ID, &t.Title, &t.Completed, &t.CreatedAt)
	if err == sql.ErrNoRows {
		c.JSON(404, gin.H{"error": "not found"})
		return
	}
	if err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}
	c.JSON(200, gin.H{"todo": t})
}

func deleteTodo(c *gin.Context) {
	db, err := connect()
	if err != nil {
		c.JSON(500, gin.H{"error": err.Error()})
		return
	}
	defer db.Close()
	db.Exec("DELETE FROM todos WHERE id = $1", c.Param("id"))
	c.Status(204)
}

func main() {
	gin.SetMode(gin.ReleaseMode)
	r := gin.New()
	r.Use(gin.Recovery())

	r.GET("/", func(c *gin.Context) {
		c.Data(http.StatusOK, "text/html; charset=utf-8", []byte(indexHTML))
	})
	r.GET("/source", func(c *gin.Context) {
		c.Data(http.StatusOK, "text/plain; charset=utf-8", []byte(thisSource))
	})
	r.GET("/api/todos", listTodos)
	r.POST("/api/todos", createTodo)
	r.PATCH("/api/todos/:id", updateTodo)
	r.DELETE("/api/todos/:id", deleteTodo)

	port := getenv("PORT", "8080")
	log.Println("go-gin listening on " + port)
	r.Run("0.0.0.0:" + port)
}
