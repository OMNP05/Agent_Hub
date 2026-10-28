import re

from fastapi import FastAPI
from fastapi.testclient import TestClient
from pydantic import BaseModel

from app.config import Settings
from app.errors import ApiError
from app.main import create_app


def test_healthz_is_ok_and_needs_no_database(client):
    response = client.get("/healthz")
    assert response.status_code == 200
    assert response.json() == {"status": "ok"}


def test_unknown_route_uses_the_error_format(client):
    response = client.get("/nope")
    assert response.status_code == 404
    assert response.json() == {"error": {"code": "not_found"}}


def test_wrong_method_uses_the_error_format(client):
    response = client.post("/healthz")
    assert response.status_code == 405
    assert response.json() == {"error": {"code": "method_not_allowed"}}


def _app_with_routes() -> FastAPI:
    app = create_app(Settings(_env_file=None, environment="test"))

    class Body(BaseModel):
        email: str
        age: int

    @app.post("/echo")
    async def echo(body: Body):
        return body.model_dump()

    @app.get("/boom")
    async def boom():
        raise RuntimeError("password=hunter2 sk-abcdefghijklmnopqrstuvwxyz0123456789")

    @app.get("/busy")
    async def busy():
        raise ApiError("agent_busy", 409)

    return app


def test_validation_errors_never_echo_the_rejected_input():
    client = TestClient(_app_with_routes(), raise_server_exceptions=False)
    response = client.post("/echo", json={"email": "secret-value-123", "age": "not a number"})
    assert response.status_code == 422
    assert response.json() == {"error": {"code": "invalid_request"}}
    assert "secret-value-123" not in response.text


def test_unhandled_exceptions_return_a_generic_error_without_the_message():
    client = TestClient(_app_with_routes(), raise_server_exceptions=False)
    response = client.get("/boom")
    assert response.status_code == 500
    assert response.json() == {"error": {"code": "internal_error"}}
    assert "hunter2" not in response.text and "sk-" not in response.text
    assert "Traceback" not in response.text


def test_api_errors_carry_their_code_and_status():
    client = TestClient(_app_with_routes(), raise_server_exceptions=False)
    response = client.get("/busy")
    assert response.status_code == 409
    assert response.json() == {"error": {"code": "agent_busy"}}


def test_every_response_has_a_request_id(client):
    response = client.get("/healthz")
    assert re.fullmatch(r"[0-9a-f]{32}", response.headers["x-request-id"])


def test_a_safe_caller_request_id_is_kept(client):
    response = client.get("/healthz", headers={"x-request-id": "client-req-12345"})
    assert response.headers["x-request-id"] == "client-req-12345"


def test_an_unsafe_caller_request_id_is_replaced(client):
    response = client.get("/healthz", headers={"x-request-id": "bad id\nwith newline"})
    assert re.fullmatch(r"[0-9a-f]{32}", response.headers["x-request-id"])
    response = client.get("/healthz", headers={"x-request-id": "x" * 500})
    assert re.fullmatch(r"[0-9a-f]{32}", response.headers["x-request-id"])


def test_docs_are_hidden_in_production():
    production = Settings(
        _env_file=None,
        environment="production",
        supabase_url="https://x.supabase.co",
        supabase_secret_key="sb_secret_aaaaaaaaaaaa",
        database_url="postgresql://u:p@h/db",
        master_encryption_key="QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU=",
        public_base_url="https://api.example.com",
    )
    client = TestClient(create_app(production))
    assert client.get("/docs").status_code == 404
    assert client.get("/openapi.json").status_code == 404
