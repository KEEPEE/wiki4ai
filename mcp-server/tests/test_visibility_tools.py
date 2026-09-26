"""
Tests for Wiki4AI MCP Server visibility parameters (WIKI4AI-101).

Tests verify:
- create_project / create_document / import_document send 'visibility' in the POST body
  with the backward-compatible default 'public'.
- batch_create_documents applies the `visibility` parameter to documents without their
  own 'visibility' key, and per-document keys override it.
- update_project / update_document omit the 'visibility' field when the parameter is
  None (no change — backward compatible) and send it when provided.
- update_document allows a visibility-only update (no title/content/edits needed).
- Invalid visibility values raise MCPToolError before any HTTP request.

Visibility rules (private visible only to owner+ADMIN, absent/404 for other users)
are enforced by the backend API — these tests mock the HTTP layer and verify that
the tools forward requests unchanged (identity JWT via _api_request).
"""

import json
import sys
import os
from unittest.mock import patch, MagicMock

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from mcp_server import (
    create_project,
    update_project,
    create_document,
    batch_create_documents,
    import_document,
    update_document,
    create_mcp_server,
    MCPToolError,
    set_base_url,
)


# ─── Fixtures ──────────────────────────────────────────────────────────────

@pytest.fixture(autouse=True)
def reset_base_url():
    """Reset BASE_URL to default before each test."""
    set_base_url("http://localhost:8080/api")
    yield


@pytest.fixture(autouse=True)
def reset_jwt_token_fixture():
    """Reset JWT_TOKEN to None before each test."""
    from mcp_server import set_jwt_token as _set
    _set(None)
    yield


# ─── Helpers ───────────────────────────────────────────────────────────────

def _mock_response(body):
    """Build a mock urlopen context-manager response returning JSON body."""
    resp = MagicMock()
    resp.read.return_value = json.dumps(body).encode("utf-8")
    return resp


def _sent_request(mock_urlopen, call_index=-1):
    """Return (Request object, parsed JSON body or None) of the given call (default: last)."""
    request = mock_urlopen.call_args_list[call_index][0][0]
    return request, (json.loads(request.data.decode("utf-8")) if request.data else None)


# ─── Tests: create_project visibility ──────────────────────────────────────

class TestCreateProjectVisibility:
    """create_project sends 'visibility' in the POST body (default 'public')."""

    @patch("mcp_server.urlopen")
    def test_default_visibility_is_public(self, mock_urlopen):
        """create_project(name) → body contains visibility 'public' (backward compatible)."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(
            {"id": 1, "name": "P", "slug": "p", "visibility": "public"}
        )

        create_project("P")

        request, body = _sent_request(mock_urlopen)
        assert str(request.full_url) == "http://localhost:8080/api/v1/projects"
        assert request.get_method() == "POST"
        assert body["visibility"] == "public"

    @patch("mcp_server.urlopen")
    def test_private_visibility_forwarded(self, mock_urlopen):
        """create_project(name, visibility='private') → body contains visibility 'private'."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(
            {"id": 1, "name": "P", "slug": "p", "visibility": "private"}
        )

        create_project("P", description="d", visibility="private")

        _, body = _sent_request(mock_urlopen)
        assert body["visibility"] == "private"
        assert body["description"] == "d"

    @patch("mcp_server.urlopen")
    def test_visibility_normalized_to_lowercase(self, mock_urlopen):
        """visibility='PRIVATE' is normalized to 'private' in the body."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(
            {"id": 1, "name": "P", "slug": "p", "visibility": "private"}
        )

        create_project("P", visibility="PRIVATE")

        _, body = _sent_request(mock_urlopen)
        assert body["visibility"] == "private"

    @patch("mcp_server.urlopen")
    def test_invalid_visibility_rejected(self, mock_urlopen):
        """visibility='internal' raises MCPToolError BEFORE any HTTP request."""
        with pytest.raises(MCPToolError) as exc_info:
            create_project("P", visibility="internal")

        assert "must be 'public' or 'private'" in str(exc_info.value)
        mock_urlopen.assert_not_called()


# ─── Tests: update_project visibility ──────────────────────────────────────

class TestUpdateProjectVisibility:
    """update_project sends 'visibility' only when the parameter is not None."""

    CURRENT_PROJECT = {"id": 1, "name": "My Proj", "slug": "my-proj", "depth": 1}

    @classmethod
    def _mock_get_then_put(cls, mock_urlopen):
        """Mock a GET (current project, for the name echo) followed by a PUT."""
        def _cm(payload):
            cm = MagicMock()
            inner = MagicMock()
            inner.read.return_value = json.dumps(payload).encode("utf-8")
            cm.__enter__.return_value = inner
            return cm

        mock_urlopen.side_effect = [
            _cm(cls.CURRENT_PROJECT),
            _cm({**cls.CURRENT_PROJECT, "visibility": "private"}),
        ]

    @patch("mcp_server.urlopen")
    def test_visibility_none_omits_field(self, mock_urlopen):
        """update_project(slug) without visibility → PUT body has NO visibility key."""
        self._mock_get_then_put(mock_urlopen)

        update_project("my-proj", description="only desc")

        # First call: GET (name echo). Second call: PUT.
        request, body = _sent_request(mock_urlopen, call_index=1)
        assert request.get_method() == "PUT"
        assert str(request.full_url) == "http://localhost:8080/api/v1/projects/my-proj"
        assert "visibility" not in body
        assert body["description"] == "only desc"

    @patch("mcp_server.urlopen")
    def test_visibility_forwarded_when_provided(self, mock_urlopen):
        """update_project(slug, visibility='private') → PUT body contains it."""
        self._mock_get_then_put(mock_urlopen)

        update_project("my-proj", visibility="private")

        _, body = _sent_request(mock_urlopen, call_index=1)
        assert body["visibility"] == "private"
        # name is echoed (backend requires non-blank name on every PUT).
        assert body["name"] == "My Proj"

    @patch("mcp_server.urlopen")
    def test_invalid_visibility_rejected(self, mock_urlopen):
        """visibility='secret' raises MCPToolError BEFORE any HTTP request."""
        with pytest.raises(MCPToolError) as exc_info:
            update_project("my-proj", visibility="secret")

        assert "must be 'public' or 'private'" in str(exc_info.value)
        mock_urlopen.assert_not_called()


# ─── Tests: create_document / import_document visibility ───────────────────

class TestCreateDocumentVisibility:
    """create_document sends 'visibility' in the POST body (default 'public')."""

    @patch("mcp_server.urlopen")
    def test_default_visibility_is_public(self, mock_urlopen):
        """create_document(project, title) → body contains visibility 'public'."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(
            {"id": 10, "title": "D", "slug": "d", "visibility": "public"}
        )

        create_document("my-project", "D")

        request, body = _sent_request(mock_urlopen)
        assert str(request.full_url) == "http://localhost:8080/api/v1/projects/my-project/documents"
        assert request.get_method() == "POST"
        assert body["visibility"] == "public"

    @patch("mcp_server.urlopen")
    def test_private_visibility_forwarded(self, mock_urlopen):
        """create_document(..., visibility='private') → body contains 'private'."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(
            {"id": 10, "title": "D", "slug": "d", "visibility": "private"}
        )

        create_document("my-project", "D", content="c", visibility="private")

        _, body = _sent_request(mock_urlopen)
        assert body["visibility"] == "private"
        assert body["content"] == "c"

    @patch("mcp_server.urlopen")
    def test_invalid_visibility_rejected(self, mock_urlopen):
        """visibility='hidden' raises MCPToolError BEFORE any HTTP request."""
        with pytest.raises(MCPToolError) as exc_info:
            create_document("my-project", "D", visibility="hidden")

        assert "must be 'public' or 'private'" in str(exc_info.value)
        mock_urlopen.assert_not_called()


class TestImportDocumentVisibility:
    """import_document sends 'visibility' in the POST body (default 'public')."""

    @patch("mcp_server.urlopen")
    def test_default_visibility_is_public(self, mock_urlopen):
        """import_document(project, title, content) → body contains visibility 'public'."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(
            {"id": 11, "title": "I", "slug": "i", "visibility": "public"}
        )

        import_document("my-project", "I", "content here")

        _, body = _sent_request(mock_urlopen)
        assert body["visibility"] == "public"
        assert body["content"] == "content here"

    @patch("mcp_server.urlopen")
    def test_private_visibility_forwarded(self, mock_urlopen):
        """import_document(..., visibility='private') → body contains 'private'."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(
            {"id": 11, "title": "I", "slug": "i", "visibility": "private"}
        )

        import_document("my-project", "I", "content here", visibility="private")

        _, body = _sent_request(mock_urlopen)
        assert body["visibility"] == "private"


# ─── Tests: batch_create_documents visibility ──────────────────────────────

class TestBatchCreateDocumentsVisibility:
    """batch_create_documents applies the `visibility` parameter as default;
    a per-document 'visibility' key overrides it."""

    @patch("mcp_server.urlopen")
    def test_default_visibility_applied_to_all(self, mock_urlopen):
        """No visibility anywhere → every POST body carries 'public'."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(
            {"id": 12, "title": "X", "slug": "x", "visibility": "public"}
        )

        batch_create_documents("my-project", [
            {"title": "Chapter 1", "content": "c1"},
            {"title": "Chapter 2"},
        ])

        assert mock_urlopen.call_count == 2
        for i in range(2):
            _, body = _sent_request(mock_urlopen, call_index=i)
            assert body["visibility"] == "public"

    @patch("mcp_server.urlopen")
    def test_global_private_parameter(self, mock_urlopen):
        """batch(..., visibility='private') → every POST body carries 'private'."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(
            {"id": 12, "title": "X", "slug": "x", "visibility": "private"}
        )

        batch_create_documents("my-project", [{"title": "A"}, {"title": "B"}], visibility="private")

        assert mock_urlopen.call_count == 2
        for i in range(2):
            _, body = _sent_request(mock_urlopen, call_index=i)
            assert body["visibility"] == "private"

    @patch("mcp_server.urlopen")
    def test_per_document_override_wins(self, mock_urlopen):
        """A document's own 'visibility' key overrides the global parameter."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(
            {"id": 12, "title": "X", "slug": "x"}
        )

        batch_create_documents("my-project", [
            {"title": "Secret Plan"},
            {"title": "Public Summary", "visibility": "public"},
        ], visibility="private")

        _, body_first = _sent_request(mock_urlopen, call_index=0)
        _, body_second = _sent_request(mock_urlopen, call_index=1)
        assert body_first["visibility"] == "private"   # inherited from the parameter
        assert body_second["visibility"] == "public"   # per-document override

    @patch("mcp_server.urlopen")
    def test_invalid_per_document_visibility_rejected(self, mock_urlopen):
        """A document dict with visibility='hidden' raises MCPToolError (no HTTP)."""
        with pytest.raises(MCPToolError) as exc_info:
            batch_create_documents("my-project", [{"title": "A", "visibility": "hidden"}])

        assert "must be 'public' or 'private'" in str(exc_info.value)
        mock_urlopen.assert_not_called()


# ─── Tests: update_document visibility ─────────────────────────────────────

class TestUpdateDocumentVisibility:
    """update_document sends 'visibility' only when the parameter is not None;
    a visibility-only update is a valid call on its own."""

    UPDATED = {
        "id": 1, "title": "My Document", "slug": "my-doc", "projectId": 10,
        "visibility": "private", "version": 2,
        "createdAt": "2026-01-01T00:00:00", "updatedAt": "2026-01-02T00:00:00",
    }

    @patch("mcp_server._api_request")
    def test_visibility_none_omits_field(self, mock_api_request):
        """update_document(proj, doc, content=...) → body has NO visibility key."""
        mock_api_request.return_value = self.UPDATED

        update_document("my-project", "my-doc", content="New content")

        mock_api_request.assert_called_once_with(
            "PUT", "/v1/projects/my-project/documents/my-doc", {"content": "New content"}
        )

    @patch("mcp_server._api_request")
    def test_visibility_forwarded_when_provided(self, mock_api_request):
        """update_document(..., visibility='private') → body contains it alongside content."""
        mock_api_request.return_value = self.UPDATED

        update_document("my-project", "my-doc", content="New content", visibility="private")

        mock_api_request.assert_called_once_with(
            "PUT", "/v1/projects/my-project/documents/my-doc",
            {"content": "New content", "visibility": "private"},
        )

    @patch("mcp_server._api_request")
    def test_visibility_only_update_allowed(self, mock_api_request):
        """update_document(proj, doc, visibility=...) alone is a valid call (no 400)."""
        mock_api_request.return_value = self.UPDATED

        result = update_document("my-project", "my-doc", visibility="private")

        mock_api_request.assert_called_once_with(
            "PUT", "/v1/projects/my-project/documents/my-doc", {"visibility": "private"}
        )
        assert result["visibility"] == "private"

    @patch("mcp_server._api_request")
    def test_invalid_visibility_rejected(self, mock_api_request):
        """visibility='secret' raises MCPToolError BEFORE any HTTP request."""
        with pytest.raises(MCPToolError) as exc_info:
            update_document("my-project", "my-doc", content="c", visibility="secret")

        assert "must be 'public' or 'private'" in str(exc_info.value)
        mock_api_request.assert_not_called()


# ─── Tests: tool registration unchanged ────────────────────────────────────

class TestVisibilityToolRegistration:
    """All tools touched by WIKI4AI-101 remain registered in create_mcp_server()."""

    def test_all_visibility_tools_registered(self):
        import asyncio

        mcp = create_mcp_server()
        names = {t.name for t in asyncio.run(mcp.list_tools())}
        for name in (
            "create_project", "update_project",
            "create_document", "batch_create_documents", "import_document", "update_document",
            "list_projects", "list_documents", "get_project", "get_document",
            "search_documents", "search_documents_global",
        ):
            assert name in names
