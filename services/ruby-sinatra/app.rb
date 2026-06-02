# Ruby (Sinatra) ToDo backend.
# Web フレームワーク Sinatra を使用 (classic style)。PG ドライバは pg。puma で起動。

require 'sinatra'
require 'json'
require 'pg'

THIS_SOURCE = File.read(__FILE__)

set :environment, :production
set :bind, '0.0.0.0'
set :port, (ENV['PORT'] || '8080').to_i
set :server, 'puma'
set :public_folder, File.join(File.dirname(__FILE__), 'public')
disable :logging

# 新規接続を 1 本張る。
# Cloud Run では DB_HOST=/cloudsql/PROJECT:REGION:INSTANCE (unix socket)。
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

get '/' do
  send_file File.join(settings.public_folder, 'index.html')
end

get '/source' do
  content_type 'text/plain; charset=utf-8'
  THIS_SOURCE
end

get '/api/todos' do
  content_type :json
  conn = db_connect
  begin
    rows = conn.exec('SELECT id, title, completed, created_at FROM todos ORDER BY created_at, id')
    JSON.generate(rows.map { |r| row_to_h(r) })
  ensure
    conn.close
  end
end

# ★ DB 接続時間と INSERT 時間を個別に計測する
post '/api/todos' do
  content_type :json
  body = (JSON.parse(request.body.read) rescue {})
  title = (body['title'] || '').strip
  halt 400, JSON.generate(error: 'title is required') if title.empty?

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

  headers 'X-DB-Connect-Ms' => format('%.3f', db_connect_ms),
          'X-Insert-Ms' => format('%.3f', insert_ms)
  status 201
  JSON.generate(todo: row_to_h(row), timings: { db_connect_ms: db_connect_ms, insert_ms: insert_ms })
end

patch '/api/todos/:id' do
  content_type :json
  completed = ((JSON.parse(request.body.read)['completed'] rescue false) ? 't' : 'f')
  conn = db_connect
  begin
    r = conn.exec_params(
      'UPDATE todos SET completed = $1 WHERE id = $2 RETURNING id, title, completed, created_at',
      [completed, params['id']]
    )
    halt 404, JSON.generate(error: 'not found') if r.ntuples.zero?
    JSON.generate(todo: row_to_h(r[0]))
  ensure
    conn.close
  end
end

delete '/api/todos/:id' do
  conn = db_connect
  begin
    conn.exec_params('DELETE FROM todos WHERE id = $1', [params['id']])
    status 204
  ensure
    conn.close
  end
end
