# Ruby (Vanilla) ToDo backend.
# Web フレームワーク (Sinatra/Rails) を使わず、標準的な WEBrick で実装。
# (WEBrick は Ruby 3 では gem だが Ruby 標準系の HTTP サーバ)。PG ドライバは pg。

require 'webrick'
require 'json'
require 'pg'

PORT = (ENV['PORT'] || '8080').to_i
HERE = File.expand_path(File.dirname(__FILE__))
INDEX_HTML = File.read(File.join(HERE, 'public', 'index.html'))
THIS_SOURCE = File.read(__FILE__)

# 新規接続を 1 本張る。
# Cloud Run では DB_HOST=/cloudsql/PROJECT:REGION:INSTANCE (unix socket)。
# ローカル/Auth Proxy では DB_HOST=127.0.0.1。
def db_connect
  PG.connect(
    host: ENV['DB_HOST'] || '127.0.0.1',
    port: ENV['DB_PORT'] || '5432',
    dbname: ENV['DB_NAME'] || 'tododb',
    user: ENV['DB_USER'] || 'todo',
    password: ENV['DB_PASSWORD'] || ''
  )
end

def row_to_h(r)
  { id: r['id'].to_i, title: r['title'], completed: r['completed'] == 't', created_at: r['created_at'] }
end

# service をオーバーライドして全 HTTP メソッド (PATCH/DELETE 含む) を 1 か所で処理する。
class TodoServlet < WEBrick::HTTPServlet::AbstractServlet
  def json(res, status, obj, extra = {})
    res.status = status
    res['Content-Type'] = 'application/json; charset=utf-8'
    extra.each { |k, v| res[k] = v }
    res.body = obj.is_a?(String) ? obj : JSON.generate(obj)
  end

  def service(req, res)
    path = req.path
    m = req.request_method

    if m == 'GET' && (path == '/' || path == '/index.html')
      res.status = 200
      res['Content-Type'] = 'text/html; charset=utf-8'
      res.body = INDEX_HTML
      return
    end
    if m == 'GET' && path == '/source'
      res.status = 200
      res['Content-Type'] = 'text/plain; charset=utf-8'
      res.body = THIS_SOURCE
      return
    end
    if path == '/api/todos'
      return list(res) if m == 'GET'
      return create(req, res) if m == 'POST'
    end
    if path =~ %r{\A/api/todos/(\d+)\z}
      id = $1
      return update(req, res, id) if m == 'PATCH'
      return destroy(res, id) if m == 'DELETE'
    end
    json(res, 404, { error: 'not found' })
  rescue => e
    warn e.message
    json(res, 500, { error: e.message })
  end

  def list(res)
    conn = db_connect
    begin
      rows = conn.exec('SELECT id, title, completed, created_at FROM todos ORDER BY created_at, id')
      json(res, 200, rows.map { |r| row_to_h(r) })
    ensure
      conn.close
    end
  end

  # ★ DB 接続時間と INSERT 時間を個別に計測する
  def create(req, res)
    body = parse_body(req)
    title = (body['title'] || '').strip
    return json(res, 400, { error: 'title is required' }) if title.empty?

    t0 = Process.clock_gettime(Process::CLOCK_MONOTONIC)
    conn = db_connect
    t1 = Process.clock_gettime(Process::CLOCK_MONOTONIC)
    db_connect_ms = (t1 - t0) * 1000.0

    t2 = Process.clock_gettime(Process::CLOCK_MONOTONIC)
    row = conn.exec_params(
      'INSERT INTO todos (title) VALUES ($1) RETURNING id, title, completed, created_at', [title]
    )[0]
    t3 = Process.clock_gettime(Process::CLOCK_MONOTONIC)
    insert_ms = (t3 - t2) * 1000.0
    conn.close

    json(res, 201,
         { todo: row_to_h(row), timings: { db_connect_ms: db_connect_ms, insert_ms: insert_ms } },
         { 'X-DB-Connect-Ms' => format('%.3f', db_connect_ms), 'X-Insert-Ms' => format('%.3f', insert_ms) })
  end

  def update(req, res, id)
    completed = parse_body(req)['completed'] ? 't' : 'f'
    conn = db_connect
    begin
      r = conn.exec_params(
        'UPDATE todos SET completed = $1 WHERE id = $2 RETURNING id, title, completed, created_at',
        [completed, id]
      )
      return json(res, 404, { error: 'not found' }) if r.ntuples.zero?
      json(res, 200, { todo: row_to_h(r[0]) })
    ensure
      conn.close
    end
  end

  def destroy(res, id)
    conn = db_connect
    begin
      conn.exec_params('DELETE FROM todos WHERE id = $1', [id])
      res.status = 204
    ensure
      conn.close
    end
  end

  def parse_body(req)
    JSON.parse(req.body || '{}')
  rescue StandardError
    {}
  end
end

server = WEBrick::HTTPServer.new(
  Port: PORT,
  BindAddress: '0.0.0.0',
  Logger: WEBrick::Log.new(File::NULL),
  AccessLog: []
)
server.mount '/', TodoServlet
trap('INT')  { server.shutdown }
trap('TERM') { server.shutdown }
puts "ruby-vanilla listening on #{PORT}"
server.start
