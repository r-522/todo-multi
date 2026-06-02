# Python (Flask) - Cloud Run 用イメージ
FROM python:3.12-slim

WORKDIR /app

COPY requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt

COPY app.py ./
COPY static ./static

ENV PORT=8080
EXPOSE 8080
# gunicorn で起動 (PORT は Cloud Run が注入)
CMD exec gunicorn --bind "0.0.0.0:${PORT}" --workers 2 --threads 4 app:app
