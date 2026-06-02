package app;

// Java (Spring Boot) ToDo backend の REST コントローラ。
// DB は Spring のコネクションプールを使わず DriverManager で毎回新規接続する。
// (他言語と条件をそろえ「DB 接続時間」を計測できるようにするため)

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

@RestController
public class TodoController {

    static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isEmpty()) ? def : v;
    }

    // 新規接続を 1 本張る。
    // Cloud Run では INSTANCE_CONNECTION_NAME を使い Cloud SQL Socket Factory 経由。
    // ローカル/Auth Proxy では DB_HOST:DB_PORT への TCP 接続。
    static Connection connect() throws Exception {
        String dbName = env("DB_NAME", "tododb");
        Properties props = new Properties();
        props.setProperty("user", env("DB_USER", "todo"));
        props.setProperty("password", env("DB_PASSWORD", ""));
        String conn = System.getenv("INSTANCE_CONNECTION_NAME");
        String url;
        if (conn != null && !conn.isEmpty()) {
            url = "jdbc:postgresql:///" + dbName;
            props.setProperty("cloudSqlInstance", conn);
            props.setProperty("socketFactory", "com.google.cloud.sql.postgres.SocketFactory");
        } else {
            url = "jdbc:postgresql://" + env("DB_HOST", "127.0.0.1") + ":" + env("DB_PORT", "5432") + "/" + dbName;
        }
        return DriverManager.getConnection(url, props);
    }

    public record Todo(long id, String title, boolean completed, OffsetDateTime created_at) {}

    static Todo readTodo(ResultSet rs) throws SQLException {
        return new Todo(rs.getLong(1), rs.getString(2), rs.getBoolean(3),
                rs.getObject(4, OffsetDateTime.class));
    }

    static String fmt(double d) {
        return String.format(Locale.US, "%.3f", d);
    }

    @GetMapping("/api/todos")
    public List<Todo> list() throws Exception {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT id, title, completed, created_at FROM todos ORDER BY created_at, id");
             ResultSet rs = ps.executeQuery()) {
            List<Todo> out = new ArrayList<>();
            while (rs.next()) out.add(readTodo(rs));
            return out;
        }
    }

    // ★ DB 接続時間と INSERT 時間を個別に計測する
    @PostMapping("/api/todos")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) throws Exception {
        String title = body.get("title") == null ? "" : body.get("title").toString().trim();
        if (title.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title is required"));
        }

        long t0 = System.nanoTime();
        Connection c = connect();
        long t1 = System.nanoTime();
        double dbConnectMs = (t1 - t0) / 1_000_000.0;

        long t2 = System.nanoTime();
        Todo todo;
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO todos (title) VALUES (?) RETURNING id, title, completed, created_at")) {
            ps.setString(1, title);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                todo = readTodo(rs);
            }
        }
        long t3 = System.nanoTime();
        double insertMs = (t3 - t2) / 1_000_000.0;
        c.close();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("todo", todo);
        resp.put("timings", Map.of("db_connect_ms", dbConnectMs, "insert_ms", insertMs));
        return ResponseEntity.status(201)
                .header("X-DB-Connect-Ms", fmt(dbConnectMs))
                .header("X-Insert-Ms", fmt(insertMs))
                .body(resp);
    }

    @PatchMapping("/api/todos/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody Map<String, Object> body) throws Exception {
        boolean completed = "true".equals(String.valueOf(body.get("completed")));
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE todos SET completed = ? WHERE id = ? RETURNING id, title, completed, created_at")) {
            ps.setBoolean(1, completed);
            ps.setLong(2, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return ResponseEntity.status(404).body(Map.of("error", "not found"));
                }
                return ResponseEntity.ok(Map.of("todo", readTodo(rs)));
            }
        }
    }

    @DeleteMapping("/api/todos/{id}")
    public ResponseEntity<Void> delete(@PathVariable long id) throws Exception {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("DELETE FROM todos WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/source", produces = "text/plain; charset=utf-8")
    public String source() {
        try (InputStream in = getClass().getResourceAsStream("/app/TodoController.java")) {
            if (in == null) return "(source not bundled)";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(source error: " + e + ")";
        }
    }
}
