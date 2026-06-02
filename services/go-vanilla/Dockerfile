# Go (Vanilla) - マルチステージビルド -> distroless
FROM golang:1.23-bookworm AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
# public/index.html と main.go は go:embed でバイナリに同梱される
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -ldflags="-s -w" -o /app/server .

FROM gcr.io/distroless/static-debian12
COPY --from=build /app/server /server
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["/server"]
