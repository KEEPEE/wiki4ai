"""Tests for BearerAuthMiddleware — Bearer-token access control for the MCP SSE endpoint.

Behavior gated on the MCP_JWT_TOKEN environment variable:
- token not set / empty  -> endpoint open (legacy behavior, nothing breaks)
- token set, no header   -> 401 with JSON error
- token set, wrong token -> 401 with JSON error
- token set, correct     -> request passes through to the app
"""

import json

import pytest
from starlette.applications import Starlette
from starlette.responses import PlainTextResponse
from starlette.routing import Route
from starlette.testclient import TestClient

from mcp_server import BearerAuthMiddleware

VALID_TOKEN = "test-access-token-0123456789"


def _make_dummy_app():
    """Minimal ASGI app mimicking the MCP SSE endpoints (no FastMCP needed)."""
    async def sse_endpoint(request):
        return PlainTextResponse("sse-ok")

    async def messages_endpoint(request):
        return PlainTextResponse("messages-ok")

    app = Starlette(
        routes=[
            Route("/sse", sse_endpoint, methods=["GET"]),
            Route("/messages/", messages_endpoint, methods=["POST"]),
        ]
    )
    return BearerAuthMiddleware(app)


@pytest.fixture
def client():
    return TestClient(_make_dummy_app())


class TestBearerAuthDisabled:
    """MCP_JWT_TOKEN empty/unset -> legacy open behavior."""

    def test_open_when_token_not_set(self, client, monkeypatch):
        monkeypatch.delenv("MCP_JWT_TOKEN", raising=False)
        resp = client.get("/sse")
        assert resp.status_code == 200
        assert resp.text == "sse-ok"

    def test_open_when_token_empty(self, client, monkeypatch):
        monkeypatch.setenv("MCP_JWT_TOKEN", "")
        resp = client.get("/sse")
        assert resp.status_code == 200

    def test_open_when_token_whitespace_only(self, client, monkeypatch):
        monkeypatch.setenv("MCP_JWT_TOKEN", "   ")
        resp = client.post("/messages/")
        assert resp.status_code == 200


class TestBearerAuthEnabled:
    """MCP_JWT_TOKEN set -> Bearer token required on every request."""

    def test_missing_header_rejected(self, client, monkeypatch):
        monkeypatch.setenv("MCP_JWT_TOKEN", VALID_TOKEN)
        resp = client.get("/sse")
        assert resp.status_code == 401
        body = resp.json()
        assert "error" in body

    def test_wrong_token_rejected(self, client, monkeypatch):
        monkeypatch.setenv("MCP_JWT_TOKEN", VALID_TOKEN)
        resp = client.get("/sse", headers={"Authorization": "Bearer wrong-token"})
        assert resp.status_code == 401

    def test_non_bearer_scheme_rejected(self, client, monkeypatch):
        monkeypatch.setenv("MCP_JWT_TOKEN", VALID_TOKEN)
        resp = client.get("/sse", headers={"Authorization": f"Basic {VALID_TOKEN}"})
        assert resp.status_code == 401

    def test_correct_token_passes_sse(self, client, monkeypatch):
        monkeypatch.setenv("MCP_JWT_TOKEN", VALID_TOKEN)
        resp = client.get("/sse", headers={"Authorization": f"Bearer {VALID_TOKEN}"})
        assert resp.status_code == 200
        assert resp.text == "sse-ok"

    def test_correct_token_passes_messages(self, client, monkeypatch):
        monkeypatch.setenv("MCP_JWT_TOKEN", VALID_TOKEN)
        resp = client.post("/messages/", headers={"Authorization": f"Bearer {VALID_TOKEN}"})
        assert resp.status_code == 200
        assert resp.text == "messages-ok"

    def test_messages_path_also_protected(self, client, monkeypatch):
        """The /messages/ session path is protected too, not just the SSE handshake."""
        monkeypatch.setenv("MCP_JWT_TOKEN", VALID_TOKEN)
        resp = client.post("/messages/")
        assert resp.status_code == 401

    def test_401_body_is_json(self, client, monkeypatch):
        monkeypatch.setenv("MCP_JWT_TOKEN", VALID_TOKEN)
        resp = client.get("/sse")
        assert resp.headers["content-type"].startswith("application/json")
        parsed = json.loads(resp.text)
        assert isinstance(parsed.get("error"), str)


class TestIsAccessCredential:
    """The shared access credential is never forwarded to the backend as identity."""

    def test_false_when_env_not_set(self, monkeypatch):
        from mcp_server import is_access_credential
        monkeypatch.delenv("MCP_JWT_TOKEN", raising=False)
        assert is_access_credential(VALID_TOKEN) is False

    def test_false_for_empty_or_none_token(self, monkeypatch):
        from mcp_server import is_access_credential
        monkeypatch.setenv("MCP_JWT_TOKEN", VALID_TOKEN)
        assert is_access_credential("") is False
        assert is_access_credential(None) is False

    def test_true_when_token_matches_env(self, monkeypatch):
        from mcp_server import is_access_credential
        monkeypatch.setenv("MCP_JWT_TOKEN", VALID_TOKEN)
        assert is_access_credential(VALID_TOKEN) is True

    def test_false_when_token_differs(self, monkeypatch):
        """A real client JWT (different from the access credential) is forwarded."""
        from mcp_server import is_access_credential
        monkeypatch.setenv("MCP_JWT_TOKEN", VALID_TOKEN)
        assert is_access_credential("some-other-jwt") is False

    def test_false_when_env_whitespace_only(self, monkeypatch):
        from mcp_server import is_access_credential
        monkeypatch.setenv("MCP_JWT_TOKEN", "   ")
        assert is_access_credential(VALID_TOKEN) is False


# ─── Integration tests against the real FastMCP SSE app (http_app) ──────────
#
# Note: the streaming GET /sse handshake is not exercised here because
# starlette's TestClient cannot reliably drive the FastMCP SSE stream in a
# unit-test environment; it is covered by the live E2E verification on the
# deployed instance. These tests assert middleware behavior on the real app
# via the non-streaming /messages/ path and the /sse 401 short-circuit.


@pytest.fixture(scope="module")
def sse_client():
    from mcp_server import create_mcp_server

    app = BearerAuthMiddleware(create_mcp_server().http_app(transport="sse"))
    with TestClient(app) as client:  # context manager runs the ASGI lifespan
        yield client


def _initialize_body():
    return {
        "jsonrpc": "2.0",
        "id": 1,
        "method": "initialize",
        "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                   "clientInfo": {"name": "pytest", "version": "0"}},
    }


def test_real_app_open_when_token_not_set(sse_client, monkeypatch):
    """Without MCP_JWT_TOKEN the real app is reachable (legacy open behavior)."""
    monkeypatch.delenv("MCP_JWT_TOKEN", raising=False)
    resp = sse_client.post("/messages/", json=_initialize_body())
    # Reaches the FastMCP handler (400: no session yet) — crucially NOT 401.
    assert resp.status_code != 401


def test_real_sse_rejects_missing_token(sse_client, monkeypatch):
    monkeypatch.setenv("MCP_JWT_TOKEN", VALID_TOKEN)
    resp = sse_client.get("/sse")
    assert resp.status_code == 401
    assert "error" in resp.json()


def test_real_sse_rejects_wrong_token(sse_client, monkeypatch):
    monkeypatch.setenv("MCP_JWT_TOKEN", VALID_TOKEN)
    resp = sse_client.get("/sse", headers={"Authorization": "Bearer nope"})
    assert resp.status_code == 401


def test_real_messages_rejected_without_token(sse_client, monkeypatch):
    """The /messages/ session path is protected too (not just the SSE handshake)."""
    monkeypatch.setenv("MCP_JWT_TOKEN", VALID_TOKEN)
    resp = sse_client.post("/messages/", json=_initialize_body())
    assert resp.status_code == 401


def test_real_app_passes_through_with_correct_token(sse_client, monkeypatch):
    """With the correct Bearer token the request reaches the FastMCP handler."""
    monkeypatch.setenv("MCP_JWT_TOKEN", VALID_TOKEN)
    resp = sse_client.post(
        "/messages/",
        json=_initialize_body(),
        headers={"Authorization": f"Bearer {VALID_TOKEN}"},
    )
    # Reaches the FastMCP handler (400: no session yet) — crucially NOT 401.
    assert resp.status_code != 401
