FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /srv

# Dependencies first so this layer is cached until pyproject.toml changes.
COPY pyproject.toml ./
RUN pip install --upgrade pip && pip install .

COPY app ./app

# Runs as a normal user, never root.
RUN useradd --system --uid 10001 appuser
USER appuser

# Cloud Run supplies PORT; 8000 is the local default.
ENV PORT=8000
EXPOSE 8000
CMD ["sh", "-c", "exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT} --proxy-headers --forwarded-allow-ips='*'"]
