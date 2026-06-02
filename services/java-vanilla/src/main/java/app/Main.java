package app;

// Java (Vanilla) ToDo backend.
// Web フレームワークを使わず JDK 内蔵の com.sun.net.httpserver.HttpServer で実装。
// PostgreSQL は JDBC (org.postgresql) を使用。JSON は外部ライブラリを使わず手書き。

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    static final String INDEX_HTML = readResource("/static/index.html");
    static final String SOURCE = readResource("/app/Main.java");
    static final Pattern ID_PATH = Pattern.compile("^/api/todos/(\\d+)$");

    static int port() {
        String p = System.getenv("PORT");
        return (p == null || p.isEmpty()) ? 8080 : Integer.parseInt(p);
    }

    static String env(String k, String def) {
        String v = System.getenv(k);
        return (v == null || v.isEmpty()) ? def : v;
    }

    // 新規接続を 1 本張る。
    // Cloud Run では INSTANCE_CONNECTION_NAME を使い Cloud SQL Socket Factory 経由で接続。
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

    // ---------- 最小限の JSON 補助 ----------
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                default:
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                    else b.append(c);
            }
        }
        return b.toString();
    }

    static String fmt(double d) { return String.format(Locale.US, "%.3f", d); }

    static String todoJson(long id, String title, boolean completed, String createdAt) {
        return "{\"id\":" + id + ",\"title\":\"" + esc(title) + "\",\"completed\":" + completed
                + ",\"created_at\":\"" + esc(createdAt) + "\"}";
    }

    static String jsonStr(String body, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(body);
        return m.find() ? unescape(m.group(1)) : null;
    }

    static boolean jsonBool(String body, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false)").matcher(body);
        return m.find() && m.group(1).equals("true");
    }

    static String unescape(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                switch (n) {
                    case 'n': b.append('\n'); break;
                    case 't': b.append('\t'); break;
                    case 'r': b.append('\r'); break;
                    case 'u': b.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16)); i += 4; break;
                    default:  b.append(n);
                }
            } else {
                b.append(c);
            }
        }
        return b.toString();
    }

    // ---------- HTTP 補助 ----------
    static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static void send(HttpExchange ex, int status, String body, String ctype) throws IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", ctype);
        if (b.length == 0) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(b);
        }
    }

    static void error(HttpExchange ex, Exception e) {
        e.printStackTrace();
        try {
            send(ex, 500, "{\"error\":\"" + esc(String.valueOf(e.getMessage())) + "\"}", "application/json; charset=utf-8");
        } catch (IOException ignored) {
        }
    }

    static String readResource(String p) {
        try (InputStream in = Main.class.getResourceAsStream(p)) {
            if (in == null) return "(missing resource " + p + ")";
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "(error reading " + p + ": " + e + ")";
        }
    }

    // ---------- ハンドラ ----------
    static void rootHandler(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.equals("/index.html")) {
            send(ex, 200, INDEX_HTML, "text/html; charset=utf-8");
        } else {
            send(ex, 404, "{\"error\":\"not found\"}", "application/json; charset=utf-8");
        }
    }

    static void apiHandler(HttpExchange ex) {
        try {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            if (path.equals("/api/todos")) {
                if (method.equals("GET")) { list(ex); return; }
                if (method.equals("POST")) { create(ex); return; }
            }
            Matcher m = ID_PATH.matcher(path);
            if (m.matches()) {
                long id = Long.parseLong(m.group(1));
                if (method.equals("PATCH")) { update(ex, id); return; }
                if (method.equals("DELETE")) { delete(ex, id); return; }
            }
            send(ex, 404, "{\"error\":\"not found\"}", "application/json; charset=utf-8");
        } catch (Exception e) {
            error(ex, e);
        }
    }

    static void list(HttpExchange ex) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT id, title, completed, created_at FROM todos ORDER BY created_at, id");
             ResultSet rs = ps.executeQuery()) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            while (rs.next()) {
                if (!first) sb.append(",");
                first = false;
                sb.append(todoJson(rs.getLong(1), rs.getString(2), rs.getBoolean(3),
                        rs.getObject(4, OffsetDateTime.class).toString()));
            }
            sb.append("]");
            send(ex, 200, sb.toString(), "application/json; charset=utf-8");
        } catch (Exception e) {
            error(ex, e);
        }
    }

    // ★ DB 接続時間と INSERT 時間を個別に計測する
    static void create(HttpExchange ex) {
        try {
            String title = jsonStr(readBody(ex), "title");
            if (title == null || title.trim().isEmpty()) {
                send(ex, 400, "{\"error\":\"title is required\"}", "application/json; charset=utf-8");
                return;
            }
            title = title.trim();

            long t0 = System.nanoTime();
            Connection c = connect();
            long t1 = System.nanoTime();
            double dbConnectMs = (t1 - t0) / 1_000_000.0;

            long t2 = System.nanoTime();
            long id; String savedTitle; boolean completed; String createdAt;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO todos (title) VALUES (?) RETURNING id, title, completed, created_at")) {
                ps.setString(1, title);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                    savedTitle = rs.getString(2);
                    completed = rs.getBoolean(3);
                    createdAt = rs.getObject(4, OffsetDateTime.class).toString();
                }
            }
            long t3 = System.nanoTime();
            double insertMs = (t3 - t2) / 1_000_000.0;
            c.close();

            String json = "{\"todo\":" + todoJson(id, savedTitle, completed, createdAt)
                    + ",\"timings\":{\"db_connect_ms\":" + fmt(dbConnectMs) + ",\"insert_ms\":" + fmt(insertMs) + "}}";
            ex.getResponseHeaders().set("X-DB-Connect-Ms", fmt(dbConnectMs));
            ex.getResponseHeaders().set("X-Insert-Ms", fmt(insertMs));
            send(ex, 201, json, "application/json; charset=utf-8");
        } catch (Exception e) {
            error(ex, e);
        }
    }

    static void update(HttpExchange ex, long id) {
        try {
            boolean completed = jsonBool(readBody(ex), "completed");
            try (Connection c = connect();
                 PreparedStatement ps = c.prepareStatement(
                         "UPDATE todos SET completed = ? WHERE id = ? RETURNING id, title, completed, created_at")) {
                ps.setBoolean(1, completed);
                ps.setLong(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        send(ex, 404, "{\"error\":\"not found\"}", "application/json; charset=utf-8");
                        return;
                    }
                    String json = "{\"todo\":" + todoJson(rs.getLong(1), rs.getString(2), rs.getBoolean(3),
                            rs.getObject(4, OffsetDateTime.class).toString()) + "}";
                    send(ex, 200, json, "application/json; charset=utf-8");
                }
            }
        } catch (Exception e) {
            error(ex, e);
        }
    }

    static void delete(HttpExchange ex, long id) {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("DELETE FROM todos WHERE id = ?")) {
            ps.setLong(1, id);
            ps.executeUpdate();
            send(ex, 204, "", "application/json; charset=utf-8");
        } catch (Exception e) {
            error(ex, e);
        }
    }

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", port()), 0);
        server.createContext("/", Main::rootHandler);
        server.createContext("/source", ex -> send(ex, 200, SOURCE, "text/plain; charset=utf-8"));
        server.createContext("/api/todos", Main::apiHandler);
        server.setExecutor(Executors.newFixedThreadPool(8));
        System.out.println("java-vanilla listening on " + port());
        server.start();
    }
}
