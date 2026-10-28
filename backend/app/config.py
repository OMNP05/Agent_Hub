"""Settings, read from the environment only. Nothing here has a hard-coded secret."""

from functools import lru_cache
from typing import Literal

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    environment: Literal["local", "test", "production"] = "local"
    log_level: str = "INFO"
    public_base_url: str = "http://localhost:8000"

    supabase_url: str = ""
    supabase_publishable_key: str = ""
    supabase_secret_key: SecretStr = SecretStr("")
    database_url: SecretStr = SecretStr("")

    master_encryption_key: SecretStr = SecretStr("")
    key_version: int = Field(default=1, ge=1)

    @field_validator("log_level")
    @classmethod
    def _level(cls, value: str) -> str:
        level = value.upper()
        if level not in {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}:
            raise ValueError("log_level must be a standard logging level")
        return level

    @model_validator(mode="after")
    def _production_requirements(self) -> "Settings":
        """Production refuses to start without what it needs; local and test stay easy to run."""
        if self.environment != "production":
            return self
        missing = [
            name
            for name, value in {
                "SUPABASE_URL": self.supabase_url,
                "SUPABASE_SECRET_KEY": self.supabase_secret_key.get_secret_value(),
                "DATABASE_URL": self.database_url.get_secret_value(),
                "MASTER_ENCRYPTION_KEY": self.master_encryption_key.get_secret_value(),
            }.items()
            if not value
        ]
        if missing:
            raise ValueError("missing required settings: " + ", ".join(missing))
        if not self.public_base_url.startswith("https://"):
            raise ValueError("PUBLIC_BASE_URL must be https in production")
        return self


@lru_cache
def get_settings() -> Settings:
    return Settings()
