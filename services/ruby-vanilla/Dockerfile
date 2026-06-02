# Ruby (Vanilla / WEBrick) - Cloud Run 用イメージ
FROM ruby:3.3-slim

WORKDIR /app

# pg gem のネイティブ拡張ビルドに必要
RUN apt-get update \
 && apt-get install -y --no-install-recommends build-essential libpq-dev \
 && rm -rf /var/lib/apt/lists/*

COPY Gemfile ./
RUN bundle install

COPY app.rb ./
COPY public ./public

ENV PORT=8080
EXPOSE 8080
CMD ["ruby", "app.rb"]
