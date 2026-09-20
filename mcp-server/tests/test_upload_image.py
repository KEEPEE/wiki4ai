"""
Tests for the upload_image MCP tool (WIKI4AI-64).

Covers:
- success path returns the backend {url, markdown, ...} payload
- multipart body is well-formed (boundary, field name "file", filename, raw bytes)
- identity JWT (X-Wiki4AI-JWT context / server token) is forwarded as Bearer
- 401 from the backend raises a clear "Not authenticated" message
- invalid base64 / empty / oversized payloads fail BEFORE any HTTP call
"""

import base64
import io
import json
import os
import sys
from unittest.mock import MagicMock, patch

import pytest
from urllib.error import HTTPError

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from mcp_server import (  # noqa: E402
    MCPToolError,
    jwt_token_context,
    set_base_url,
    set_jwt_token,
    upload_image,
)

# Minimal PNG header + padding — content is irrelevant to the MCP layer.
PNG_BYTES = bytes([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]) + b"\x00" * 32


@pytest.fixture(autouse=True)
def reset_base_url():
    set_base_url("http://localhost:8080/api")
    yield


@pytest.fixture(autouse=True)
def reset_auth():
    set_jwt_token(None)
    token_var = jwt_token_context.set(None)
    yield


def _header(request, name):
    """Case-insensitive header lookup (urllib Request capitalizes header names)."""
    for key, value in request.headers.items():
        if key.lower() == name.lower():
            return value
    return None


def _mock_success_response(mock_urlopen):
    payload = {
        "url": "/images/my-proj/9f1c2d3e-aaaa-bbbb-cccc-000011112222.png",
        "markdown": "!shot(/images/my-proj/9f1c2d3e-aaaa-bbbb-cccc-000011112222.png)",
        "filename": "shot.png",
        "storedName": "9f1c2d3e-aaaa-bbbb-cccc-000011112222.png",
        "size": len(PNG_BYTES),
        "contentType": "image/png",
    }
    mock_resp = MagicMock()
    mock_resp.read.return_value = json.dumps(payload).encode("utf-8")
    mock_urlopen.return_value.__enter__.return_value = mock_resp
    return payload


class TestUploadImageSuccess:
    @patch("mcp_server.urlopen")
    def test_success_returns_url_and_markdown(self, mock_urlopen):
        payload = _mock_success_response(mock_urlopen)

        result = upload_image("my-proj", "shot.png", base64.b64encode(PNG_BYTES).decode())

        assert result == payload
        request = mock_urlopen.call_args[0][0]
        assert request.full_url == "http://localhost:8080/api/v1/images/my-proj"
        assert request.get_method() == "POST"

    @patch("mcp_server.urlopen")
    def test_multipart_body_is_well_formed(self, mock_urlopen):
        _mock_success_response(mock_urlopen)

        upload_image("my-proj", "shot.png", base64.b64encode(PNG_BYTES).decode())

        request = mock_urlopen.call_args[0][0]
        content_type = _header(request, "Content-Type")
        assert content_type is not None
        assert content_type.startswith("multipart/form-data; boundary=")
        boundary = content_type.split("boundary=", 1)[1]

        body = request.data.decode("latin-1")
        assert body.startswith(f"--{boundary}\r\n")
        assert f'name="file"; filename="shot.png"' in body
        assert body.endswith(f"\r\n--{boundary}--\r\n")
        # The raw image bytes are embedded between the headers and the closing CRLF.
        header_end = body.index("\r\n\r\n") + 4
        trailer_start = body.rindex("\r\n--")
        assert PNG_BYTES.decode("latin-1") == body[header_end:trailer_start]

    @patch("mcp_server.urlopen")
    def test_base64_with_whitespace_is_accepted(self, mock_urlopen):
        _mock_success_response(mock_urlopen)
        b64 = base64.b64encode(PNG_BYTES).decode()
        padded = "\n".join(b64[i : i + 16] for i in range(0, len(b64), 16))

        result = upload_image("my-proj", "shot.png", padded)
        assert result["url"].startswith("/images/my-proj/")


class TestUploadImageAuth:
    @patch("mcp_server.urlopen")
    def test_identity_token_from_context_forwarded_as_bearer(self, mock_urlopen):
        _mock_success_response(mock_urlopen)
        jwt_token_context.set("client-identity-jwt")

        upload_image("my-proj", "shot.png", base64.b64encode(PNG_BYTES).decode())

        request = mock_urlopen.call_args[0][0]
        assert request.headers.get("Authorization") == "Bearer client-identity-jwt"

    @patch("mcp_server.urlopen")
    def test_server_token_used_when_no_client_token(self, mock_urlopen):
        _mock_success_response(mock_urlopen)
        set_jwt_token("server-side-token")

        upload_image("my-proj", "shot.png", base64.b64encode(PNG_BYTES).decode())

        request = mock_urlopen.call_args[0][0]
        assert request.headers.get("Authorization") == "Bearer server-side-token"

    @patch("mcp_server.urlopen")
    def test_no_auth_header_when_no_token_anywhere(self, mock_urlopen):
        _mock_success_response(mock_urlopen)

        upload_image("my-proj", "shot.png", base64.b64encode(PNG_BYTES).decode())

        request = mock_urlopen.call_args[0][0]
        assert "Authorization" not in request.headers

    @patch("mcp_server._api_request_multipart")
    def test_401_raises_clear_not_authenticated_message(self, mock_multipart):
        """401 from the backend must surface as a clear JWT guidance message."""
        mock_multipart.side_effect = MCPToolError(
            "Authentication required", status_code=401, details={"message": "Authentication required"}
        )

        with pytest.raises(MCPToolError) as exc_info:
            upload_image("my-proj", "shot.png", base64.b64encode(PNG_BYTES).decode())

        assert exc_info.value.status_code == 401
        message = str(exc_info.value)
        assert "Not authenticated" in message
        assert "JWT" in message
        assert "X-Wiki4AI-JWT" in message

    @patch("mcp_server._api_request_multipart")
    def test_non_401_errors_are_reraised_unchanged(self, mock_multipart):
        """Non-401 errors (e.g. 400 bad format, 404 unknown project) pass through."""
        mock_multipart.side_effect = MCPToolError(
            "API Error 404: {\"message\": \"Project not found with slug: nope\"}",
            status_code=404,
        )

        with pytest.raises(MCPToolError) as exc_info:
            upload_image("nope", "shot.png", base64.b64encode(PNG_BYTES).decode())

        assert exc_info.value.status_code == 404
        assert "Project not found" in str(exc_info.value)


class TestUploadImageValidation:
    def test_invalid_base64_raises_before_http(self):
        with pytest.raises(MCPToolError) as exc_info:
            upload_image("my-proj", "shot.png", "!!!not-base64!!!")
        assert "base64" in str(exc_info.value).lower()

    def test_empty_image_raises(self):
        with pytest.raises(MCPToolError) as exc_info:
            upload_image("my-proj", "shot.png", "")
        assert "required" in str(exc_info.value) or "zero bytes" in str(exc_info.value)

    def test_oversized_image_raises_before_http(self):
        big = b"\x89PNG" + b"\x00" * (10 * 1024 * 1024 + 1)
        with pytest.raises(MCPToolError) as exc_info:
            upload_image("my-proj", "big.png", base64.b64encode(big).decode())
        assert "too large" in str(exc_info.value)

    def test_oversized_never_calls_backend(self):
        big = b"\x89PNG" + b"\x00" * (10 * 1024 * 1024 + 1)
        with patch("mcp_server.urlopen") as mock_urlopen:
            with pytest.raises(MCPToolError):
                upload_image("my-proj", "big.png", base64.b64encode(big).decode())
            mock_urlopen.assert_not_called()

    def test_blank_project_slug_raises(self):
        with pytest.raises(MCPToolError) as exc_info:
            upload_image("   ", "shot.png", base64.b64encode(PNG_BYTES).decode())
        assert "project_slug" in str(exc_info.value)


class TestUploadImageRegistration:
    def test_tool_is_registered(self):
        """upload_image must be exposed by create_mcp_server (WIKI4AI-64: 32 tools)."""
        import asyncio

        from mcp_server import create_mcp_server

        server = create_mcp_server()
        tool_names = {t.name for t in asyncio.run(server.list_tools())}
        assert "upload_image" in tool_names
        # Sanity: the previous 31 tools are still there (spot-check a few).
        for expected in ("list_projects", "get_document", "update_document", "search_documents_global"):
            assert expected in tool_names
