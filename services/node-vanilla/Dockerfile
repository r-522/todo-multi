# Node.js (Vanilla) - Cloud Run 用イメージ
FROM node:20-slim

WORKDIR /app

# 依存だけ先に入れてレイヤキャッシュを効かせる
COPY package.json ./
RUN npm install --omit=dev

# アプリ本体 (server.js は /source でそのまま配信される)
COPY server.js ./
COPY public ./public

ENV PORT=8080
EXPOSE 8080
CMD ["node", "server.js"]
