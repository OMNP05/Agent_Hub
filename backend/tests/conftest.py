import os

import pytest
from fastapi.testclient import TestClient

# Tests must never read the developer's real backend/.env or touch real services.
os.environ["ENVIRONMENT"] = "test"
for name in ("DATABASE_URL", "SUPABASE_SECRET_KEY", "MASTER_ENCRYPTION_KEY"):
    os.environ.pop(name, None)

from app.config import Settings  # noqa: E402
from app.main import create_app  # noqa: E402


@pytest.fixture
def settings() -> Settings:
    return Settings(_env_file=None, environment="test")


@pytest.fixture
def client(settings: Settings) -> TestClient:
    return TestClient(create_app(settings), raise_server_exceptions=False)
