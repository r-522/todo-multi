# デプロイ手順 (GCP / Cloud Run + Cloud SQL)

10 個のバックエンドを **Cloud Run** に、`main.html` を **Cloud Storage** に公開し、
全サービスで 1 つの **Cloud SQL (PostgreSQL)** を共有するまでの手順です。

```
ブラウザ ──> main.html (GCS 公開バケット)
                 │  各サービスの URL リンク
                 v
   10 個の Cloud Run サービス ──> Cloud SQL (PostgreSQL) [共有 todos テーブル]
```

---

## 0. 前提

- 課金が有効な GCP プロジェクト
- ローカルに [`gcloud` CLI](https://cloud.google.com/sdk/docs/install) がインストール済み
- 認証とプロジェクト設定:
  ```bash
  gcloud auth login
  gcloud config set project YOUR_PROJECT_ID
  ```

以降では次の値を使う例で説明します (適宜読み替えてください):

| 変数 | 例 |
|------|----|
| `PROJECT_ID` | `my-todo-project` |
| `REGION` | `asia-northeast1` |
| `SQL_INSTANCE` | `todo-pg` |
| `DB_NAME` | `tododb` |
| `DB_USER` | `todo` |
| `DB_PASSWORD` | `(強いパスワード)` |

```bash
export PROJECT_ID=my-todo-project
export REGION=asia-northeast1
export SQL_INSTANCE=todo-pg
export DB_NAME=tododb
export DB_USER=todo
export DB_PASSWORD='change-me-please'
```

---

## 1. 必要な API を有効化

```bash
gcloud services enable \
  run.googleapis.com \
  sqladmin.googleapis.com \
  cloudbuild.googleapis.com \
  artifactregistry.googleapis.com \
  storage.googleapis.com
```

---

## 2. Cloud SQL (PostgreSQL) インスタンスを作成

```bash
# インスタンス (最小構成の例)
gcloud sql instances create "$SQL_INSTANCE" \
  --database-version=POSTGRES_15 \
  --tier=db-f1-micro \
  --region="$REGION"

# データベース
gcloud sql databases create "$DB_NAME" --instance="$SQL_INSTANCE"

# アプリ用ユーザー
gcloud sql users create "$DB_USER" \
  --instance="$SQL_INSTANCE" \
  --password="$DB_PASSWORD"
```

インスタンス接続名 (`PROJECT:REGION:INSTANCE`) を控えておきます:

```bash
export CONN="$(gcloud sql instances describe "$SQL_INSTANCE" --format='value(connectionName)')"
echo "$CONN"   # 例: my-todo-project:asia-northeast1:todo-pg
```

---

## 3. スキーマを適用 (`db/schema.sql`)

### 方法 A: Cloud SQL Auth Proxy 経由 (推奨)

別ターミナルでプロキシを起動 (TCP `127.0.0.1:5432` に転送):

```bash
# プロキシのダウンロード (1 度だけ)
curl -o cloud-sql-proxy https://storage.googleapis.com/cloud-sql-connectors/cloud-sql-proxy/v2.11.0/cloud-sql-proxy.linux.amd64
chmod +x cloud-sql-proxy

# 起動
./cloud-sql-proxy "$CONN"
```

別ターミナルから `psql` で適用:

```bash
PGPASSWORD="$DB_PASSWORD" psql \
  "host=127.0.0.1 port=5432 dbname=$DB_NAME user=$DB_USER" \
  -f db/schema.sql
```

### 方法 B: Cloud SQL Studio

GCP コンソール > Cloud SQL > 該当インスタンス > 「Cloud SQL Studio」から
`$DB_NAME` に接続し、`db/schema.sql` の内容を貼り付けて実行します。

---

## 4. Cloud Run へデプロイ

### 方法 A: 一括スクリプト (推奨)

```bash
DB_PASSWORD="$DB_PASSWORD" REGION="$REGION" SQL_INSTANCE="$SQL_INSTANCE" \
  ./deploy/deploy.sh
```

`deploy/deploy.sh` は 10 サービスを順にビルド & デプロイし、各 URL を一覧表示します。

各サービスには以下が渡されます:

- `--add-cloudsql-instances $CONN` … Cloud Run が `/cloudsql/$CONN` に unix socket を生成
- `--set-env-vars`:
  - 共通: `DB_NAME` / `DB_USER` / `DB_PASSWORD` / `DB_HOST=/cloudsql/$CONN`
  - **Java のみ** 追加: `INSTANCE_CONNECTION_NAME=$CONN` (Cloud SQL Socket Factory 用)
- `--allow-unauthenticated` … 認証なしで公開 (デモ用)

> Node / Python / Go / Ruby は libpq 系ドライバなので `DB_HOST` に unix socket ディレクトリを
> 渡すだけで接続できます。Java(JDBC) は unix socket を直接話せないため Socket Factory を使います。

### 方法 B: 1 サービスずつ手動

```bash
# 例: node-express
gcloud run deploy node-express \
  --source services/node-express \
  --region "$REGION" --allow-unauthenticated \
  --add-cloudsql-instances "$CONN" \
  --set-env-vars "DB_NAME=$DB_NAME,DB_USER=$DB_USER,DB_PASSWORD=$DB_PASSWORD,DB_HOST=/cloudsql/$CONN"

# 例: java-spring (INSTANCE_CONNECTION_NAME を追加)
gcloud run deploy java-spring \
  --source services/java-spring \
  --region "$REGION" --allow-unauthenticated --memory 1Gi \
  --add-cloudsql-instances "$CONN" \
  --set-env-vars "DB_NAME=$DB_NAME,DB_USER=$DB_USER,DB_PASSWORD=$DB_PASSWORD,DB_HOST=/cloudsql/$CONN,INSTANCE_CONNECTION_NAME=$CONN"
```

---

## 5. 各サービス URL を `main.html` に反映

```bash
gcloud run services list --region "$REGION" --format="table(metadata.name, status.url)"
```

表示された URL を `frontend/main.html` 先頭の `SERVICES` 配列の各 `url` に貼り付けます
(`https://node-vanilla-xxxx.run.app` の形式)。

---

## 6. `main.html` を Cloud Storage で公開

```bash
export BUCKET="gs://${PROJECT_ID}-todo-frontend"

# バケット作成
gcloud storage buckets create "$BUCKET" --location="$REGION" --uniform-bucket-level-access

# アップロード
gcloud storage cp frontend/main.html "$BUCKET/main.html"

# 公開 (全ユーザーに閲覧許可)
gcloud storage buckets add-iam-policy-binding "$BUCKET" \
  --member=allUsers --role=roles/storage.objectViewer
```

公開 URL:

```
https://storage.googleapis.com/PROJECT_ID-todo-frontend/main.html
```

これをブラウザで開けば、各言語の ToDo 画面へ遷移できます。

---

## 7. 接続方式の補足 (Auth Proxy / VPC コネクタ)

本手順では **Cloud Run ネイティブの Cloud SQL 接続** (`--add-cloudsql-instances`) を使い、
unix socket `/cloudsql/$CONN` 経由でパブリック IP に接続しています。要件にある他方式も以下で対応可能です。

- **Cloud SQL Auth Proxy (ローカル/サイドカー)**: 手順 3-A のとおり、ローカルからは Proxy 経由で
  `127.0.0.1:5432` に TCP 接続できます (`DB_HOST=127.0.0.1`)。各サービスはこの TCP 接続にも対応済みです。
- **VPC コネクタ + プライベート IP**: 本番でプライベート IP のみにする場合は、
  1. Cloud SQL をプライベート IP 構成で作成
  2. Serverless VPC Access コネクタを作成
     ```bash
     gcloud compute networks vpc-access connectors create todo-conn \
       --region="$REGION" --range=10.8.0.0/28
     ```
  3. デプロイ時に `--vpc-connector todo-conn` を付け、`DB_HOST` をプライベート IP に設定
     (`--add-cloudsql-instances` の代わり)。

---

## 8. ローカル開発 (任意)

Cloud SQL Auth Proxy を起動した状態 (`127.0.0.1:5432`) で、各サービスを単体起動できます。

```bash
export DB_HOST=127.0.0.1 DB_PORT=5432 DB_NAME=$DB_NAME DB_USER=$DB_USER DB_PASSWORD=$DB_PASSWORD PORT=8080

# Node
cd services/node-express && npm install && node server.js
# Python
cd services/python-flask && pip install -r requirements.txt && gunicorn -b 0.0.0.0:8080 app:app
# Go
cd services/go-gin && go run .
# Java
cd services/java-spring && mvn -DskipTests package && java -jar target/app.jar
# Ruby
cd services/ruby-sinatra && bundle install && ruby app.rb
```

スモークテスト:

```bash
curl localhost:8080/api/todos
curl -X POST localhost:8080/api/todos -H 'Content-Type: application/json' -d '{"title":"test"}'
# -> {"todo":{...},"timings":{"db_connect_ms":12.34,"insert_ms":3.21}}
```

---

## 9. 後片付け

```bash
# Cloud Run サービス削除
for s in node-vanilla node-express python-vanilla python-flask go-vanilla go-gin java-vanilla java-spring ruby-vanilla ruby-sinatra; do
  gcloud run services delete "$s" --region "$REGION" --quiet
done

# Cloud SQL / バケット
gcloud sql instances delete "$SQL_INSTANCE" --quiet
gcloud storage rm -r "$BUCKET"
```

---

## トラブルシュート

| 症状 | 対処 |
|------|------|
| デプロイ時 `--allow-unauthenticated` が拒否される | 組織ポリシー `iam.allowedPolicyMemberDomains` 等で公開が制限されている可能性。管理者に許可を依頼するか、`gcloud run services proxy` でローカル確認 |
| 画面は出るが API が 500 | 接続情報 (env) の誤り。Cloud Run のログ (`gcloud run services logs read SERVICE --region $REGION`) を確認 |
| `created_at` の表示形式が言語で微妙に違う | 仕様。各言語のドライバの既定フォーマットをそのまま返しているため (件数表示には影響なし) |
| java-spring が起動に時間がかかる/メモリ不足 | `--memory 1Gi` を指定 (deploy.sh は設定済み) |
