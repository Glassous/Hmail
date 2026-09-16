FROM python:3.12-slim
WORKDIR /app
ARG PIP_INDEX_URL=https://pypi.tuna.tsinghua.edu.cn/simple
COPY requirements.txt .
RUN --mount=type=cache,target=/root/.cache/pip pip install --index-url ${PIP_INDEX_URL} --timeout 60 --retries 3 -r requirements.txt
COPY . .
RUN useradd --uid 10001 --create-home app && mkdir -p /tmp/hmail-uploads && chown app:app /tmp/hmail-uploads
USER app
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000", "--limit-concurrency", "128", "--no-access-log"]
