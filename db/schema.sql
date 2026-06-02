-- 多言語 ToDo アプリ 共有スキーマ (PostgreSQL)
-- すべてのバックエンド (Node / Python / Go / Java / Ruby) がこの 1 テーブルを共有する。
--
-- 適用方法 (例: Cloud SQL Auth Proxy 経由):
--   ./cloud-sql-proxy PROJECT:REGION:INSTANCE &
--   psql "host=127.0.0.1 port=5432 dbname=tododb user=todo password=*****" -f db/schema.sql

CREATE TABLE IF NOT EXISTS todos (
    id         BIGSERIAL   PRIMARY KEY,
    title      TEXT        NOT NULL,
    completed  BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_todos_created_at ON todos (created_at);

-- 動作確認用の初期データ (任意。不要なら削除可)
INSERT INTO todos (title, completed) VALUES
    ('牛乳を買う', FALSE),
    ('レポートを書く', FALSE),
    ('Cloud Run にデプロイする', TRUE)
ON CONFLICT DO NOTHING;
