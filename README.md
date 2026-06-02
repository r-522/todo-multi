# todo-multi — 多言語 ToDo アプリ (Win95 UI / Cloud Run)

同じ **ToDo アプリ**を **5 言語 × (バニラ / フレームワーク) = 10 通り**のバックエンドで実装した
ショーケースです。1 枚の `main.html` (Windows 95 風ランチャー) から各実装の画面へ遷移し、実際に
CRUD を行いながら **「DB 接続」と「INSERT」にかかった時間 (ms)** を計測・比較できます。各画面は
そのバックエンドのソースコードも表示します。

全バックエンドは独立した **Cloud Run** サービスとしてデプロイされ、1 つの
**Cloud SQL (PostgreSQL)** の `todos` テーブルを共有します。

> デザインは「AI が作った感じ」を避け、**Windows 95 風 (グレーのベベル枠・紺のタイトルバー)** で
> 統一しています。CSS は各 HTML に埋め込み、**ビルドツール・CDN・Web フォントは一切不使用**。
> フロントの JS は **バニラ JS + fetch のみ**です。

## バックエンド一覧 (10 サービス)

| 言語 | バニラ (標準ライブラリ) | フレームワーク |
|------|------------------------|----------------|
| **Node.js** | `node:http` (`node-vanilla`) | Express (`node-express`) |
| **Python** | `http.server` (`python-vanilla`) | Flask (`python-flask`) |
| **Go** | `net/http` (`go-vanilla`) | Gin (`go-gin`) |
| **Java** | `com.sun.net.httpserver` (`java-vanilla`) | Spring Boot (`java-spring`) |
| **Ruby** | WEBrick (`ruby-vanilla`) | Sinatra (`ruby-sinatra`) |

DB ドライバ: Node=`pg` / Python=`psycopg2` / Go=`database/sql`+`lib/pq` /
Java=PG JDBC (+Cloud SQL Socket Factory) / Ruby=`pg`。

## ディレクトリ構成

```
todo-multi/
├── README.md
├── db/
│   └── schema.sql              # 共有スキーマ (todos テーブル)
├── frontend/
│   └── main.html               # GCS にホストする Win95 ランチャー (先頭の SERVICES を編集)
├── services/
│   ├── node-vanilla/   ・ node-express/
│   ├── python-vanilla/ ・ python-flask/
│   ├── go-vanilla/     ・ go-gin/
│   ├── java-vanilla/   ・ java-spring/
│   └── ruby-vanilla/   ・ ruby-sinatra/
│        各サービス: バックエンド本体 + (public|static)/index.html + 依存定義 + Dockerfile
├── deploy/
│   ├── deploy.sh               # 10 サービスを Cloud Run へ一括デプロイ
│   └── cloudbuild.yaml         # 単一サービス用 (任意・CI)
└── docs/
    └── DEPLOY.md               # GCP 準備〜Cloud SQL〜Cloud Run〜公開までの手順
```

## 共通 API (全サービス同一・同一オリジン)

| Method | Path | 内容 |
|--------|------|------|
| `GET` | `/` | ToDo 画面 (Win95 UI) |
| `GET` | `/source` | そのバックエンドの主要ソース (text/plain) |
| `GET` | `/api/todos` | 一覧 |
| `POST` | `/api/todos` | 追加 — **新規 DB 接続を張って `db_connect_ms`、INSERT で `insert_ms` を計測**して返す |
| `PATCH` | `/api/todos/{id}` | 完了切替 |
| `DELETE` | `/api/todos/{id}` | 削除 |

`POST` のレスポンス例:

```json
{
  "todo": { "id": 4, "title": "牛乳を買う", "completed": false, "created_at": "2026-06-02T07:20:00Z" },
  "timings": { "db_connect_ms": 12.34, "insert_ms": 3.21 }
}
```

同じ値はレスポンスヘッダ `X-DB-Connect-Ms` / `X-Insert-Ms` にも入ります。

> **計測について**: 「DB 接続」時間を測るため、各サービスは **リクエストごとに新しい接続を張ります**
> (コネクションプールは使いません)。これはデモ用の意図的な実装で、本番ではプールを推奨します。

## デプロイ

[`docs/DEPLOY.md`](docs/DEPLOY.md) を参照してください。おおまかな流れ:

1. GCP の API を有効化
2. Cloud SQL (PostgreSQL) を作成し `db/schema.sql` を適用
3. `./deploy/deploy.sh` で 10 サービスを Cloud Run にデプロイ
4. 各サービス URL を `frontend/main.html` の `SERVICES` に貼り付け
5. `main.html` を Cloud Storage の公開バケットにアップロード

## ローカルでの動作確認

Cloud SQL Auth Proxy (または任意の PostgreSQL) を `127.0.0.1:5432` で用意し、
`DB_HOST=127.0.0.1` などの環境変数を渡して各サービスを単体起動できます
(手順は DEPLOY.md「8. ローカル開発」)。
