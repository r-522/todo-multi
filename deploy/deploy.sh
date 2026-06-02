#!/usr/bin/env bash
#
# 10 個の ToDo バックエンドを Cloud Run に一括デプロイする。
# 各 Dockerfile は Cloud Build が自動ビルドする (gcloud run deploy --source)。
#
# 事前準備:
#   gcloud auth login
#   gcloud config set project <PROJECT_ID>
#   Cloud SQL (PostgreSQL) インスタンス作成 + スキーマ適用 (docs/DEPLOY.md 参照)
#
# 実行例:
#   DB_PASSWORD='********' REGION=asia-northeast1 SQL_INSTANCE=todo-pg ./deploy/deploy.sh
#
set -euo pipefail

# ===== 設定 (環境変数で上書き可) =====
PROJECT_ID="${PROJECT_ID:-$(gcloud config get-value project 2>/dev/null)}"
REGION="${REGION:-asia-northeast1}"
SQL_INSTANCE="${SQL_INSTANCE:-todo-pg}"
DB_NAME="${DB_NAME:-tododb}"
DB_USER="${DB_USER:-todo}"
DB_PASSWORD="${DB_PASSWORD:?DB_PASSWORD を環境変数で指定してください}"
# =====================================

CONN="${PROJECT_ID}:${REGION}:${SQL_INSTANCE}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

SERVICES=(
  node-vanilla node-express
  python-vanilla python-flask
  go-vanilla go-gin
  java-vanilla java-spring
  ruby-vanilla ruby-sinatra
)

echo "Project : ${PROJECT_ID}"
echo "Region  : ${REGION}"
echo "Cloud SQL: ${CONN}"
echo

for svc in "${SERVICES[@]}"; do
  echo "=================================================="
  echo " Deploying: ${svc}"
  echo "=================================================="

  # libpq 系 (Node/Python/Go/Ruby) は DB_HOST に unix socket を渡す。
  # Java は Cloud SQL Socket Factory 用に INSTANCE_CONNECTION_NAME を渡す。
  env_vars="DB_NAME=${DB_NAME},DB_USER=${DB_USER},DB_PASSWORD=${DB_PASSWORD},DB_HOST=/cloudsql/${CONN}"
  mem="512Mi"
  case "$svc" in
    java-spring) env_vars="${env_vars},INSTANCE_CONNECTION_NAME=${CONN}"; mem="1Gi" ;;
    java-*)      env_vars="${env_vars},INSTANCE_CONNECTION_NAME=${CONN}" ;;
  esac

  gcloud run deploy "$svc" \
    --source "${ROOT}/services/${svc}" \
    --project "${PROJECT_ID}" \
    --region "${REGION}" \
    --platform managed \
    --allow-unauthenticated \
    --add-cloudsql-instances "${CONN}" \
    --set-env-vars "${env_vars}" \
    --cpu 1 --memory "${mem}" --timeout 60 --max-instances 3
done

echo
echo "=== デプロイ済みサービス URL ==="
gcloud run services list --project "${PROJECT_ID}" --region "${REGION}" \
  --format="table(metadata.name, status.url)"
echo
echo "↑ これらの URL を frontend/main.html の SERVICES 配列に貼り付けてください。"
