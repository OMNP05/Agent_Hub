import pytest
from pydantic import ValidationError

from app.config import Settings

VALID_KEY = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU="  # 32 bytes, a test value only


def make(**overrides):
    return Settings(_env_file=None, **overrides)


def test_local_and_test_start_with_no_secrets():
    assert make(environment="local").environment == "local"
    assert make(environment="test").environment == "test"


def test_production_refuses_to_start_without_required_settings():
    with pytest.raises(ValidationError) as caught:
        make(environment="production")
    message = str(caught.value)
    for name in ("SUPABASE_URL", "SUPABASE_SECRET_KEY", "DATABASE_URL", "MASTER_ENCRYPTION_KEY"):
        assert name in message


def test_production_requires_an_https_public_url():
    with pytest.raises(ValidationError):
        make(
            environment="production",
            supabase_url="https://x.supabase.co",
            supabase_secret_key="sb_secret_aaaaaaaaaaaa",
            database_url="postgresql://u:p@h/db",
            master_encryption_key=VALID_KEY,
            public_base_url="http://insecure.example.com",
        )


def test_production_starts_with_everything_set():
    s = make(
        environment="production",
        supabase_url="https://x.supabase.co",
        supabase_secret_key="sb_secret_aaaaaaaaaaaa",
        database_url="postgresql://u:p@h/db",
        master_encryption_key=VALID_KEY,
        public_base_url="https://api.example.com",
    )
    assert s.environment == "production"


def test_secrets_are_hidden_in_repr_and_str():
    s = make(
        supabase_secret_key="sb_secret_topsecretvalue",
        database_url="postgresql://u:pw123@h/db",
        master_encryption_key="masterkeyvalue",
    )
    shown = repr(s) + str(s) + str(s.model_dump())
    for secret in ("topsecretvalue", "pw123", "masterkeyvalue"):
        assert secret not in shown


def test_invalid_environment_and_log_level_are_rejected():
    with pytest.raises(ValidationError):
        make(environment="staging")
    with pytest.raises(ValidationError):
        make(log_level="chatty")


def test_log_level_is_normalised():
    assert make(log_level="debug").log_level == "DEBUG"


def test_environment_variables_are_read(monkeypatch):
    monkeypatch.setenv("LOG_LEVEL", "warning")
    assert Settings(_env_file=None).log_level == "WARNING"
