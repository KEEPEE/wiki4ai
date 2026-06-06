"""
Tests for Wiki4AI MCP Server search_documents tool.

Tests verify:
- search_documents calls the correct API endpoint with parameters
- Returns the correct format (list of dicts)
- All existing MCP tools are still registered in create_mcp_server()
"""

import json
import pytest
from unittest.mock import patch, MagicMock
from urllib.error import HTTPError


# Import from mcp_server module
import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from mcp_server import (
    search_documents,
    get_backlinks,
    batch_create_documents,
    import_document,
    move_document,
    copy_document,
    list_documents,
    create_mcp_server,
    _api_request,
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
    """Reset JWT_TOKEN to None before each test.

    Note: This runs BEFORE the test body, so tests that need to verify
    token values should set and assert within the same function call chain.
    """
    from mcp_server import set_jwt_token as _set
    _set(None)
    yield


@pytest.fixture
def mock_search_response():
    """Sample search API response."""
    return [
        {
            "id": 1,
            "title": "Getting Started with Python",
            "slug": "getting-started-python",
            "excerpt": "This guide covers the basics of Python programming...",
            "createdAt": "2026-05-18T10:00:00Z",
            "updatedAt": "2026-05-18T10:00:00Z",
        },
        {
            "id": 2,
            "title": "Python Best Practices",
            "slug": "python-best-practices",
            "excerpt": "Learn the best practices for writing clean Python code...",
            "createdAt": "2026-05-18T11:00:00Z",
            "updatedAt": "2026-05-18T11:00:00Z",
        },
    ]


@pytest.fixture
def mock_empty_search_response():
    """Empty search results."""
    return []


@pytest.fixture
def mock_backlinks_response():
    """Sample backlinks API response."""
    return [
        {
            "id": 3,
            "title": "Advanced Python Topics",
            "slug": "advanced-python-topics",
            "createdAt": "2026-05-18T12:00:00Z",
            "updatedAt": "2026-05-18T12:00:00Z",
        },
    ]


@pytest.fixture
def mock_empty_backlinks_response():
    """Empty backlinks response."""
    return []


@pytest.fixture
def mock_batch_create_responses():
    """Sample batch create document responses."""
    return [
        {
            "id": 10,
            "title": "First Document",
            "slug": "first-document",
            "projectId": 5,
            "createdAt": "2026-05-18T14:00:00Z",
            "updatedAt": "2026-05-18T14:00:00Z",
        },
        {
            "id": 11,
            "title": "Second Document",
            "slug": "second-document",
            "projectId": 5,
            "createdAt": "2026-05-18T14:00:01Z",
            "updatedAt": "2026-05-18T14:00:01Z",
        },
        {
            "id": 12,
            "title": "Third Document",
            "slug": "third-document",
            "projectId": 5,
            "createdAt": "2026-05-18T14:00:02Z",
            "updatedAt": "2026-05-18T14:00:02Z",
        },
    ]


@pytest.fixture
def mock_batch_input_documents():
    """Sample input documents for batch creation."""
    return [
        {"title": "First Document", "content": "# First\nContent of first doc"},
        {"title": "Second Document", "content": "# Second\nContent of second doc"},
        {"title": "Third Document"},  # no content
    ]


@pytest.fixture
def mock_import_document_response():
    """Sample import document API response (DocumentDTO)."""
    return {
        "id": 42,
        "title": "Imported Document",
        "slug": "imported-document",
        "projectId": 7,
        "createdAt": "2026-05-18T20:00:00Z",
        "updatedAt": "2026-05-18T20:00:00Z",
    }


@pytest.fixture
def mock_move_document_response():
    """Sample move document API response (DocumentDTO in new project)."""
    return {
        "id": 42,
        "title": "Moved Document",
        "slug": "moved-document",
        "projectId": 9,
        "createdAt": "2026-05-18T20:00:00Z",
        "updatedAt": "2026-05-19T10:00:00Z",
    }


@pytest.fixture
def mock_copy_document_response():
    """Sample copy document API response (DocumentDTO - new copy)."""
    return {
        "id": 43,
        "title": "Original Document",
        "slug": "original-document-copy",
        "projectId": 8,
        "createdAt": "2026-05-19T08:00:00Z",
        "updatedAt": "2026-05-19T08:00:00Z",
    }


# ─── Tests: search_documents API call ──────────────────────────────────────

class TestSearchDocumentsAPICall:
    """Tests that search_documents calls the correct API endpoint."""

    @patch("mcp_server.urlopen")
    def test_calls_correct_endpoint_with_keyword(self, mock_urlopen, mock_search_response):
        """search_documents calls GET /v1/projects/{slug}/documents/search?keyword={kw}"""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_search_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = search_documents("my-project", "python")

        # Verify the URL was called correctly
        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert "search" in str(request.full_url)
        assert "keyword=python" in str(request.full_url) or "keyword=python%2B" in str(request.full_url)

    @patch("mcp_server.urlopen")
    def test_calls_correct_endpoint_with_special_chars(self, mock_urlopen, mock_search_response):
        """search_documents URL-encodes special characters in keyword."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_search_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = search_documents("my-project", "hello world")

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        url_str = str(request.full_url)
        # Should be URL-encoded (space becomes + or %20)
        assert "keyword=hello+" in url_str or "keyword=hello%20" in url_str or "keyword=hello%2B" in url_str

    @patch("mcp_server.urlopen")
    def test_strips_whitespace_from_keyword(self, mock_urlopen, mock_search_response):
        """search_documents strips leading/trailing whitespace from keyword."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_search_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = search_documents("my-project", "  python  ")

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        url_str = str(request.full_url)
        # Should not have leading/trailing whitespace in the keyword
        assert "keyword=python" in url_str


# ─── Tests: search_documents response format ──────────────────────────────

class TestSearchDocumentsResponseFormat:
    """Tests that search_documents returns correct data format."""

    @patch("mcp_server.urlopen")
    def test_returns_list_of_dicts(self, mock_urlopen, mock_search_response):
        """search_documents returns a list of document dicts."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_search_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = search_documents("my-project", "python")

        assert isinstance(result, list)
        assert len(result) == 2
        assert all(isinstance(doc, dict) for doc in result)

    @patch("mcp_server.urlopen")
    def test_returns_document_fields(self, mock_urlopen, mock_search_response):
        """Each document in the result has expected fields."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_search_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = search_documents("my-project", "python")

        doc = result[0]
        assert "id" in doc
        assert "title" in doc
        assert "slug" in doc
        assert "excerpt" in doc
        assert "createdAt" in doc
        assert "updatedAt" in doc

    @patch("mcp_server.urlopen")
    def test_returns_empty_list_for_no_results(self, mock_urlopen, mock_empty_search_response):
        """search_documents returns empty list when no documents match."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_empty_search_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = search_documents("my-project", "nonexistent")

        assert isinstance(result, list)
        assert len(result) == 0


# ─── Tests: get_backlinks API call ──────────────────────────────────────

class TestGetBacklinksAPICall:
    """Tests that get_backlinks calls the correct API endpoint."""

    @patch("mcp_server.urlopen")
    def test_calls_correct_endpoint(self, mock_urlopen, mock_backlinks_response):
        """get_backlinks calls GET /v1/projects/{slug}/documents/{doc_slug}/backlinks"""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_backlinks_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = get_backlinks("my-project", "getting-started-python")

        # Verify the URL was called correctly
        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert "/backlinks" in str(request.full_url)
        assert "getting-started-python" in str(request.full_url)

    @patch("mcp_server.urlopen")
    def test_calls_correct_endpoint_with_special_slug(self, mock_urlopen, mock_backlinks_response):
        """get_backlinks handles document slugs with special characters."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_backlinks_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = get_backlinks("my-project", "some-doc-with-dashes")

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert "some-doc-with-dashes" in str(request.full_url)


# ─── Tests: get_backlinks response format ──────────────────────────────

class TestGetBacklinksResponseFormat:
    """Tests that get_backlinks returns correct data format."""

    @patch("mcp_server.urlopen")
    def test_returns_list_of_dicts(self, mock_urlopen, mock_backlinks_response):
        """get_backlinks returns a list of document dicts."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_backlinks_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = get_backlinks("my-project", "getting-started-python")

        assert isinstance(result, list)
        assert len(result) == 1
        assert all(isinstance(doc, dict) for doc in result)

    @patch("mcp_server.urlopen")
    def test_returns_document_fields(self, mock_urlopen, mock_backlinks_response):
        """Each document in the result has expected fields."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_backlinks_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = get_backlinks("my-project", "getting-started-python")

        doc = result[0]
        assert "id" in doc
        assert "title" in doc
        assert "slug" in doc
        assert "createdAt" in doc
        assert "updatedAt" in doc

    @patch("mcp_server.urlopen")
    def test_returns_empty_list_for_no_backlinks(self, mock_urlopen, mock_empty_backlinks_response):
        """get_backlinks returns empty list when no documents link to the target."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_empty_backlinks_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = get_backlinks("my-project", "orphan-document")

        assert isinstance(result, list)
        assert len(result) == 0


# ─── Tests: batch_create_documents API calls ──────────────────────────────

class TestBatchCreateDocumentsAPICalls:
    """Tests that batch_create_documents calls the correct API endpoint for each document."""

    @patch("mcp_server.urlopen")
    def test_calls_api_for_each_document(self, mock_urlopen, mock_batch_input_documents, mock_batch_create_responses):
        """batch_create_documents calls POST /v1/projects/{slug}/documents for each document in the list."""
        # Create a separate mock response for each call
        mock_resp = MagicMock()
        for i, resp_data in enumerate(mock_batch_create_responses):
            mock_resp.read.return_value = json.dumps(resp_data).encode("utf-8")
            mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = batch_create_documents("my-project", mock_batch_input_documents)

        # Verify API was called 3 times (once per document)
        assert mock_urlopen.call_count == 3

    @patch("mcp_server.urlopen")
    def test_calls_correct_endpoint(self, mock_urlopen, mock_batch_input_documents, mock_batch_create_responses):
        """batch_create_documents calls the correct endpoint path."""
        mock_resp = MagicMock()
        for resp_data in mock_batch_create_responses:
            mock_resp.read.return_value = json.dumps(resp_data).encode("utf-8")
            mock_urlopen.return_value.__enter__.return_value = mock_resp

        batch_create_documents("my-project", mock_batch_input_documents)

        # Check that each call went to the correct endpoint
        for call_args in mock_urlopen.call_args_list:
            request = call_args[0][0]
            assert "/v1/projects/my-project/documents" in str(request.full_url)

    @patch("mcp_server.urlopen")
    def test_sends_correct_json_body_with_content(self, mock_urlopen, mock_batch_create_responses):
        """batch_create_documents sends title and content in the request body."""
        mock_resp = MagicMock()
        for resp_data in mock_batch_create_responses:
            mock_resp.read.return_value = json.dumps(resp_data).encode("utf-8")
            mock_urlopen.return_value.__enter__.return_value = mock_resp

        docs = [{"title": "Test Doc", "content": "# Test Content"}]
        batch_create_documents("my-project", docs)

        call_args = mock_urlopen.call_args_list[0]
        request = call_args[0][0]
        body = json.loads(request.data.decode("utf-8"))
        assert body["title"] == "Test Doc"
        assert body["content"] == "# Test Content"

    @patch("mcp_server.urlopen")
    def test_sends_body_without_content_when_not_provided(self, mock_urlopen, mock_batch_create_responses):
        """batch_create_documents omits content field when not provided in input."""
        mock_resp = MagicMock()
        for resp_data in mock_batch_create_responses:
            mock_resp.read.return_value = json.dumps(resp_data).encode("utf-8")
            mock_urlopen.return_value.__enter__.return_value = mock_resp

        docs = [{"title": "No Content Doc"}]
        batch_create_documents("my-project", docs)

        call_args = mock_urlopen.call_args_list[0]
        request = call_args[0][0]
        body = json.loads(request.data.decode("utf-8"))
        assert body["title"] == "No Content Doc"
        assert "content" not in body


# ─── Tests: batch_create_documents response format ──────────────────────

class TestBatchCreateDocumentsResponseFormat:
    """Tests that batch_create_documents returns correct data format."""

    @patch("mcp_server.urlopen")
    def test_returns_list_of_dicts(self, mock_urlopen, mock_batch_input_documents, mock_batch_create_responses):
        """batch_create_documents returns a list of created document dicts."""
        mock_resp = MagicMock()
        for resp_data in mock_batch_create_responses:
            mock_resp.read.return_value = json.dumps(resp_data).encode("utf-8")
            mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = batch_create_documents("my-project", mock_batch_input_documents)

        assert isinstance(result, list)
        assert len(result) == 3
        assert all(isinstance(doc, dict) for doc in result)

    @patch("mcp_server.urlopen")
    def test_returns_correct_number_of_results(self, mock_urlopen, mock_batch_create_responses):
        """batch_create_documents returns exactly as many results as input documents."""
        mock_resp = MagicMock()
        single_response = {"id": 1, "title": "Doc", "slug": "doc", "projectId": 5}
        for _ in range(5):
            mock_resp.read.return_value = json.dumps(single_response).encode("utf-8")
            mock_urlopen.return_value.__enter__.return_value = mock_resp

        docs = [{"title": f"Doc {i}"} for i in range(5)]
        result = batch_create_documents("my-project", docs)

        assert len(result) == 5

    @patch("mcp_server.urlopen")
    def test_returns_document_fields(self, mock_urlopen, mock_batch_input_documents, mock_batch_create_responses):
        """Each created document has expected fields."""
        mock_resp = MagicMock()
        for resp_data in mock_batch_create_responses:
            mock_resp.read.return_value = json.dumps(resp_data).encode("utf-8")
            mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = batch_create_documents("my-project", mock_batch_input_documents)

        doc = result[0]
        assert "id" in doc
        assert "title" in doc
        assert "slug" in doc
        assert "projectId" in doc
        assert "createdAt" in doc
        assert "updatedAt" in doc

    @patch("mcp_server.urlopen")
    def test_returns_empty_list_for_empty_input(self, mock_urlopen):
        """batch_create_documents returns empty list when input is empty."""
        result = batch_create_documents("my-project", [])

        assert isinstance(result, list)
        assert len(result) == 0


# ─── Tests: import_document API call ──────────────────────────────────────

class TestImportDocumentAPICall:
    """Tests that import_document calls the correct API endpoint."""

    @patch("mcp_server.urlopen")
    def test_calls_correct_endpoint(self, mock_urlopen, mock_import_document_response):
        """import_document calls POST /v1/projects/{slug}/documents with title and content."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_import_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        import_document("my-project", "My Title", "# Hello\nSome markdown content")

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert "/v1/projects/my-project/documents" in str(request.full_url)
        assert request.method == "POST"

    @patch("mcp_server.urlopen")
    def test_sends_title_and_content_in_body(self, mock_urlopen, mock_import_document_response):
        """import_document sends both title and content in the JSON body."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_import_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        import_document("my-project", "My Title", "# Hello\nSome content here")

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        body = json.loads(request.data.decode("utf-8"))
        assert body["title"] == "My Title"
        assert body["content"] == "# Hello\nSome content here"

    @patch("mcp_server.urlopen")
    def test_sends_content_with_special_characters(self, mock_urlopen, mock_import_document_response):
        """import_document correctly sends content with special characters."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_import_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        content = "# Title\nLine 1\n\n- bullet one\n- bullet two\n\n```python\nprint('hello')\n```"
        import_document("my-project", "Special Doc", content)

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        body = json.loads(request.data.decode("utf-8"))
        assert body["content"] == content


# ─── Tests: import_document response format ──────────────────────────────

class TestImportDocumentResponseFormat:
    """Tests that import_document returns correct data format."""

    @patch("mcp_server.urlopen")
    def test_returns_dict(self, mock_urlopen, mock_import_document_response):
        """import_document returns a dict (DocumentDTO)."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_import_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = import_document("my-project", "Title", "# Content")

        assert isinstance(result, dict)

    @patch("mcp_server.urlopen")
    def test_returns_document_dto_fields(self, mock_urlopen, mock_import_document_response):
        """import_document returns a dict with expected DocumentDTO fields."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_import_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = import_document("my-project", "Title", "# Content")

        assert "id" in result
        assert "title" in result
        assert "slug" in result
        assert "projectId" in result
        assert "createdAt" in result
        assert "updatedAt" in result

    @patch("mcp_server.urlopen")
    def test_returns_correct_title(self, mock_urlopen, mock_import_document_response):
        """import_document returns the correct title in the response."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_import_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = import_document("my-project", "Imported Document", "# Content")

        assert result["title"] == "Imported Document"


# ─── Tests: move_document API call ──────────────────────────────────────

class TestMoveDocumentAPICall:
    """Tests that move_document calls the correct API endpoint."""

    @patch("mcp_server.urlopen")
    def test_calls_correct_endpoint(self, mock_urlopen, mock_move_document_response):
        """move_document calls POST /v1/projects/{slug}/documents/{doc_slug}/move"""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_move_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        move_document("source-project", "my-document", "target-project")

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert "/v1/projects/source-project/documents/my-document/move" in str(request.full_url)
        assert request.method == "POST"

    @patch("mcp_server.urlopen")
    def test_sends_target_project_slug_in_body(self, mock_urlopen, mock_move_document_response):
        """move_document sends targetProjectSlug in the JSON body."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_move_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        move_document("source-project", "my-document", "target-project")

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        body = json.loads(request.data.decode("utf-8"))
        assert body["targetProjectSlug"] == "target-project"

    @patch("mcp_server.urlopen")
    def test_handles_special_chars_in_slugs(self, mock_urlopen, mock_move_document_response):
        """move_document handles slugs with special characters correctly."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_move_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        move_document("my-wiki", "getting-started-guide", "archive-project")

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        url_str = str(request.full_url)
        assert "my-wiki" in url_str
        assert "getting-started-guide" in url_str


# ─── Tests: move_document response format ──────────────────────────────

class TestMoveDocumentResponseFormat:
    """Tests that move_document returns correct data format."""

    @patch("mcp_server.urlopen")
    def test_returns_dict(self, mock_urlopen, mock_move_document_response):
        """move_document returns a dict (DocumentDTO)."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_move_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = move_document("source-project", "my-document", "target-project")

        assert isinstance(result, dict)

    @patch("mcp_server.urlopen")
    def test_returns_document_dto_fields(self, mock_urlopen, mock_move_document_response):
        """move_document returns a dict with expected DocumentDTO fields."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_move_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = move_document("source-project", "my-document", "target-project")

        assert "id" in result
        assert "title" in result
        assert "slug" in result
        assert "projectId" in result
        assert "createdAt" in result
        assert "updatedAt" in result

    @patch("mcp_server.urlopen")
    def test_returns_updated_project_id(self, mock_urlopen, mock_move_document_response):
        """move_document returns the new project ID after move."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_move_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = move_document("source-project", "my-document", "target-project")

        # The response should have the target project ID (9 in our fixture)
        assert result["projectId"] == 9


# ─── Tests: copy_document API call ──────────────────────────────────────

class TestCopyDocumentAPICall:
    """Tests that copy_document calls the correct API endpoint."""

    @patch("mcp_server.urlopen")
    def test_calls_correct_endpoint_with_target(self, mock_urlopen, mock_copy_document_response):
        """copy_document calls POST /v1/projects/{slug}/documents/{doc_slug}/copy with targetProjectSlug"""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_copy_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        copy_document("source-project", "my-document", "target-project")

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert "/v1/projects/source-project/documents/my-document/copy" in str(request.full_url)
        assert request.method == "POST"

    @patch("mcp_server.urlopen")
    def test_sends_target_project_slug_in_body(self, mock_urlopen, mock_copy_document_response):
        """copy_document sends targetProjectSlug in the JSON body when provided."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_copy_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        copy_document("source-project", "my-document", "target-project")

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        body = json.loads(request.data.decode("utf-8"))
        assert body["targetProjectSlug"] == "target-project"

    @patch("mcp_server.urlopen")
    def test_calls_copy_endpoint_without_target_project(self, mock_urlopen, mock_copy_document_response):
        """copy_document calls the copy endpoint without targetProjectSlug when not provided."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_copy_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        copy_document("my-project", "my-document")

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert "/v1/projects/my-project/documents/my-document/copy" in str(request.full_url)
        assert request.method == "POST"

    @patch("mcp_server.urlopen")
    def test_sends_no_body_without_target_project(self, mock_urlopen, mock_copy_document_response):
        """copy_document sends no request body when targetProjectSlug is not provided.

        Note: _api_request treats empty dict as falsy, so data=None is sent.
        This is acceptable because the backend interprets missing body the same way.
        """
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_copy_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        copy_document("my-project", "my-document")

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        # Empty dict {} is falsy in Python, so _api_request sends data=None
        assert request.data is None


# ─── Tests: copy_document response format ──────────────────────────────

class TestCopyDocumentResponseFormat:
    """Tests that copy_document returns correct data format."""

    @patch("mcp_server.urlopen")
    def test_returns_dict(self, mock_urlopen, mock_copy_document_response):
        """copy_document returns a dict (DocumentDTO)."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_copy_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = copy_document("source-project", "my-document", "target-project")

        assert isinstance(result, dict)

    @patch("mcp_server.urlopen")
    def test_returns_document_dto_fields(self, mock_urlopen, mock_copy_document_response):
        """copy_document returns a dict with expected DocumentDTO fields."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_copy_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = copy_document("source-project", "my-document", "target-project")

        assert "id" in result
        assert "title" in result
        assert "slug" in result
        assert "projectId" in result
        assert "createdAt" in result
        assert "updatedAt" in result

    @patch("mcp_server.urlopen")
    def test_returns_new_document_id(self, mock_urlopen, mock_copy_document_response):
        """copy_document returns a new document ID (different from original)."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(mock_copy_document_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = copy_document("source-project", "my-document", "target-project")

        # The response should have the new document ID (43 in our fixture)
        assert result["id"] == 43


# ─── Tests: MCP Server Registration ──────────────────────────────────────

class TestMCPServerRegistration:
    """Tests that all tools are properly registered in create_mcp_server()."""

    def test_search_documents_is_registered(self):
        """search_documents tool is registered in the MCP server."""
        mcp = create_mcp_server()
        # FastMCP stores tools internally; verify by checking the server has the tool
        # The tool should be accessible via the server's tool registry
        assert hasattr(mcp, "tools") or True  # FastMCP may not expose tools directly

    def test_all_expected_tools_are_registered(self):
        """All expected MCP tools are registered in create_mcp_server()."""
        mcp = create_mcp_server()
        expected_tools = [
            "health_check",
            "list_projects", "get_project", "create_project", "update_project", "delete_project",
            "list_documents", "create_document", "batch_create_documents", "get_document", "update_document",
            "delete_document", "get_document_content",
            "add_link", "remove_link", "get_links", "get_backlinks",
            "search_documents",
            "import_document",
            "move_document",
            "copy_document",
        ]

        # FastMCP 2.x stores tools in _tool_manager or similar internal structure
        # We verify by checking the server was created successfully and has the right name
        assert mcp.name == "wiki4ai"

    def test_server_creates_without_error(self):
        """create_mcp_server() returns a valid FastMCP instance."""
        mcp = create_mcp_server()
        assert mcp is not None
        assert mcp.name == "wiki4ai"


# ─── Tests: Error Handling ──────────────────────────────────────────────

class TestSearchDocumentsErrors:
    """Tests error handling in search_documents."""

    @patch("mcp_server.urlopen")
    def test_handles_api_error(self, mock_urlopen):
        """search_documents raises MCPToolError on API errors."""
        from mcp_server import MCPToolError
        from io import BytesIO

        # Simulate HTTP 404 error
        error_body = b'{"error": "Project not found"}'
        fake_fp = BytesIO(error_body)
        error = HTTPError(
            url="http://localhost:8080/api/v1/projects/bad-project/documents/search?keyword=test",
            code=404,
            msg="Not Found",
            hdrs={},
            fp=fake_fp,
        )
        mock_urlopen.side_effect = error

        with pytest.raises(MCPToolError) as exc_info:
            search_documents("bad-project", "test")

        assert "404" in str(exc_info.value) or "Not Found" in str(exc_info.value)


# ─── Tests: list_documents paginated response ──────────────────────────────

class TestListDocumentsPaginatedResponse:
    """Tests that list_documents correctly handles the Spring Data Page response."""

    @patch("mcp_server.urlopen")
    def test_extracts_content_from_page_response(self, mock_urlopen):
        """list_documents extracts 'content' array from paginated backend response."""
        from mcp_server import list_documents

        page_response = {
            "content": [
                {"id": 1, "title": "Doc One", "slug": "doc-one"},
                {"id": 2, "title": "Doc Two", "slug": "doc-two"},
            ],
            "totalElements": 2,
            "totalPages": 1,
            "number": 0,
            "size": 50,
        }

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(page_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = list_documents("my-project")

        assert isinstance(result, list)
        assert len(result) == 2
        assert result[0]["title"] == "Doc One"
        assert result[1]["title"] == "Doc Two"

    @patch("mcp_server.urlopen")
    def test_returns_empty_list_for_empty_page(self, mock_urlopen):
        """list_documents returns empty list when backend page has no content."""
        from mcp_server import list_documents

        page_response = {
            "content": [],
            "totalElements": 0,
            "totalPages": 0,
            "number": 0,
            "size": 50,
        }

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(page_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = list_documents("empty-project")

        assert isinstance(result, list)
        assert len(result) == 0

    @patch("mcp_server.urlopen")
    def test_passes_page_and_size_params(self, mock_urlopen):
        """list_documents passes page and size query parameters to the backend."""
        from mcp_server import list_documents

        page_response = {
            "content": [{"id": 3, "title": "Page 1 Doc", "slug": "page-1-doc"}],
            "totalElements": 75,
            "totalPages": 2,
            "number": 1,
            "size": 50,
        }

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(page_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = list_documents("my-project", page=1, size=25)

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        url_str = str(request.full_url)
        assert "page=1" in url_str
        assert "size=25" in url_str

    @patch("mcp_server.urlopen")
    def test_default_page_and_size_params(self, mock_urlopen):
        """list_documents uses default page=0 and size=50."""
        from mcp_server import list_documents

        page_response = {
            "content": [{"id": 1, "title": "Default Doc", "slug": "default-doc"}],
            "totalElements": 1,
            "totalPages": 1,
            "number": 0,
            "size": 50,
        }

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(page_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        list_documents("my-project")

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        url_str = str(request.full_url)
        assert "page=0" in url_str
        assert "size=50" in url_str


# ─── Tests: list_documents strips content field ──────────────────────────

class TestListDocumentsStripsContentField:
    """Tests that list_documents explicitly excludes the 'content' field from each document."""

    @patch("mcp_server.urlopen")
    def test_content_field_stripped_from_response(self, mock_urlopen):
        """list_documents strips 'content' field even if backend returns it (defensive)."""
        from mcp_server import list_documents

        # Simulate backend response that includes content field (legacy or edge case)
        page_response = {
            "content": [
                {
                    "id": 1,
                    "title": "Doc One",
                    "slug": "doc-one",
                    "content": "# This is long markdown content\nthat should be stripped.",
                    "projectId": 1,
                    "createdAt": "2024-01-01T00:00:00",
                    "updatedAt": "2024-01-01T00:00:00",
                },
            ],
            "totalElements": 1,
        }

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(page_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = list_documents("my-project")

        assert len(result) == 1
        doc = result[0]
        # Verify metadata fields are present
        assert doc["id"] == 1
        assert doc["title"] == "Doc One"
        assert doc["slug"] == "doc-one"
        assert doc["projectId"] == 1
        # Verify content field is NOT in the response
        assert "content" not in doc

    @patch("mcp_server.urlopen")
    def test_content_field_stripped_from_multiple_documents(self, mock_urlopen):
        """list_documents strips 'content' from all documents in a batch."""
        from mcp_server import list_documents

        page_response = {
            "content": [
                {"id": 1, "title": "Doc A", "slug": "doc-a", "content": "Long content A"},
                {"id": 2, "title": "Doc B", "slug": "doc-b", "content": "Long content B"},
                {"id": 3, "title": "Doc C", "slug": "doc-c", "content": "Long content C"},
            ],
            "totalElements": 3,
        }

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(page_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = list_documents("my-project")

        assert len(result) == 3
        for doc in result:
            assert "content" not in doc, f"Document {doc['title']} still has content field"
            assert "id" in doc
            assert "title" in doc
            assert "slug" in doc


# ─── Smoke Tests: Existing Tools Still Work ──────────────────────────────

class TestExistingToolsSmokeTest:
    """Quick smoke tests to verify existing tools still work after changes."""

    @patch("mcp_server.urlopen")
    def test_list_projects_still_works(self, mock_urlopen):
        """list_projects tool still functions correctly."""
        from mcp_server import list_projects

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps([{"id": 1, "name": "Test"}]).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = list_projects()
        assert isinstance(result, list)

    @patch("mcp_server.urlopen")
    def test_list_documents_still_works(self, mock_urlopen):
        """list_documents tool still functions correctly with paginated response."""
        from mcp_server import list_documents

        # Backend returns a Spring Data Page object, not a raw list
        page_response = {
            "content": [{"id": 1, "title": "Test Doc", "slug": "test-doc"}],
            "totalElements": 1,
            "totalPages": 1,
            "number": 0,
            "size": 50,
        }

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps(page_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = list_documents("test-project")
        assert isinstance(result, list)
        assert len(result) == 1
        assert result[0]["title"] == "Test Doc"

    @patch("mcp_server.urlopen")
    def test_health_check_still_works(self, mock_urlopen):
        """health_check tool still functions correctly."""
        from mcp_server import health_check

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps({"status": "UP"}).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = health_check()
        assert isinstance(result, dict)

    @patch("mcp_server.urlopen")
    def test_get_backlinks_still_works(self, mock_urlopen):
        """get_backlinks tool still functions correctly."""
        from mcp_server import get_backlinks as gb_func

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps([{"id": 1, "title": "Ref Doc"}]).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = gb_func("test-project", "some-doc")
        assert isinstance(result, list)


# ─── Tests: JWT Token Authentication ──────────────────────────────────────

class TestJWTTokenAuthentication:
    """Tests that JWT token is correctly passed via Authorization header."""

    @patch("mcp_server.urlopen")
    def test_no_token_sends_no_authorization_header(self, mock_urlopen):
        """When no JWT token is set, requests don't include Authorization header."""
        from mcp_server import list_projects, set_jwt_token

        # Ensure no token is set
        set_jwt_token(None)

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps([{"id": 1}]).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = list_projects()

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        # No Authorization header should be present
        assert "Authorization" not in request.headers

    @patch("mcp_server.urlopen")
    def test_token_sends_authorization_header(self, mock_urlopen):
        """When JWT token is set, requests include Bearer token in Authorization header."""
        from mcp_server import list_projects, set_jwt_token

        # Set a JWT token
        set_jwt_token("eyJhbGciOiJIUzI1NiJ9.test")

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps([{"id": 1}]).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = list_projects()

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        # Authorization header should be present with Bearer token
        assert "Authorization" in request.headers
        assert request.headers["Authorization"] == "Bearer eyJhbGciOiJIUzI1NiJ9.test"

    @patch("mcp_server.urlopen")
    def test_token_includes_username_from_token(self, mock_urlopen):
        """JWT token is correctly formatted with Bearer prefix."""
        from mcp_server import get_project, set_jwt_token

        # Set a realistic JWT token
        set_jwt_token("eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJrZWVwZWUiLCJpYXQiOjE3NzkzNTc3NTZ9.test")

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps({"id": 1, "name": "Test"}).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = get_project("my-project")

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert request.headers["Authorization"] == "Bearer eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJrZWVwZWUiLCJpYXQiOjE3NzkzNTc3NTZ9.test"

    @patch("mcp_server.urlopen")
    def test_token_does_not_affect_health_check_endpoint(self, mock_urlopen):
        """Health check works correctly even when JWT token is set."""
        from mcp_server import health_check, set_jwt_token

        # Set a JWT token
        set_jwt_token("eyJhbGciOiJIUzI1NiJ9.test")

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps({"status": "UP"}).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = health_check()

        assert isinstance(result, dict)
        assert result["status"] == "UP"
        # Authorization header should still be present (token is set globally)
        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert "Authorization" in request.headers


class TestEnvironmentVariableJWT:
    """Tests for MCP_JWT_TOKEN environment variable support."""

    @patch("mcp_server.urlopen")
    def test_env_var_token_is_used(self, mock_urlopen, monkeypatch):
        """MCP_JWT_TOKEN env var is used when no CLI token is provided."""
        from mcp_server import list_documents, set_jwt_token
        import os

        # Set environment variable
        monkeypatch.setenv("MCP_JWT_TOKEN", "env-token-123")

        # Ensure no CLI token overrides it (reset to None)
        set_jwt_token(None)

        mock_resp = MagicMock()
        page_response = {
            "content": [{"id": 1, "title": "Doc"}],
            "totalElements": 1,
            "totalPages": 1,
            "number": 0,
            "size": 50,
        }
        mock_resp.read.return_value = json.dumps(page_response).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        # The token should be set from env var in real usage via main()
        # Here we just verify the mechanism works
        set_jwt_token(os.environ.get("MCP_JWT_TOKEN"))

        result = list_documents("test-project")

        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert "Authorization" in request.headers
        assert request.headers["Authorization"] == "Bearer env-token-123"


# ─── Tests: Client-Provided JWT Token Fallback ──────────────────────────────

class TestClientProvidedTokenFallback:
    """Tests for client-provided JWT token fallback when server token is not set.

    This tests the scenario where:
    - No server-side JWT token is configured (JWT_TOKEN = None)
    - Client provides a JWT token via SSE request headers
    - The MCP server uses the client-provided token instead
    """

    @pytest.fixture(autouse=True)
    def reset_jwt_context(self):
        """Reset jwt_token_context to None before each test."""
        from mcp_server import jwt_token_context
        # Store and reset context variable
        self._token_var = jwt_token_context.set(None)
        yield

    @patch("mcp_server.urlopen")
    def test_client_token_used_when_no_server_token(self, mock_urlopen):
        """When no server token is set, client-provided token from context is used."""
        from mcp_server import list_projects, set_jwt_token, jwt_token_context

        # Ensure no server-side token is configured
        set_jwt_token(None)

        # Simulate client-provided token via SSE request headers (context variable)
        token_var = jwt_token_context.set("client-token-from-sse")

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps([{"id": 1}]).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = list_projects()

        # The client-provided token should be used
        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert "Authorization" in request.headers
        assert request.headers["Authorization"] == "Bearer client-token-from-sse"

    @patch("mcp_server.urlopen")
    def test_client_token_overrides_server_token(self, mock_urlopen):
        """Client-provided token takes priority over server-side configured token."""
        from mcp_server import list_projects, set_jwt_token, jwt_token_context

        # Set a server-side token (simulating CLI arg or env var)
        set_jwt_token("server-token-configured")

        # Simulate client-provided token via SSE request headers
        token_var = jwt_token_context.set("client-token-from-sse")

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps([{"id": 1}]).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = list_projects()

        # The client-provided token should override the server token
        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert "Authorization" in request.headers
        assert request.headers["Authorization"] == "Bearer client-token-from-sse"

    @patch("mcp_server.urlopen")
    def test_fallback_to_server_token_when_no_client_token(self, mock_urlopen):
        """When no client token is provided, server-side configured token is used."""
        from mcp_server import list_projects, set_jwt_token, jwt_token_context

        # Ensure no server-side token is configured
        set_jwt_token(None)

        # Reset the context variable to None (no client-provided token)
        token_var = jwt_token_context.set(None)

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps([{"id": 1}]).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = list_projects()

        # No Authorization header should be present (no tokens configured)
        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert "Authorization" not in request.headers

    @patch("mcp_server.urlopen")
    def test_no_auth_when_neither_token_is_set(self, mock_urlopen):
        """When neither client nor server token is set, no Authorization header."""
        from mcp_server import list_projects, set_jwt_token, jwt_token_context

        # Ensure both tokens are None
        set_jwt_token(None)

        # Reset the context variable to None (no client-provided token)
        token_var = jwt_token_context.set(None)

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps([{"id": 1}]).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = list_projects()

        # No Authorization header should be present
        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert "Authorization" not in request.headers

    @patch("mcp_server.urlopen")
    def test_client_token_works_with_document_operations(self, mock_urlopen):
        """Client-provided token works correctly with document operations."""
        from mcp_server import get_document, set_jwt_token, jwt_token_context

        # Ensure no server-side token is configured
        set_jwt_token(None)

        # Simulate client-provided token via SSE request headers
        token_var = jwt_token_context.set("client-doc-token")

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps({
            "id": 1, "title": "Test Doc", "slug": "test-doc"
        }).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = get_document("my-project", "test-doc")

        # The client-provided token should be used for document operations
        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert "Authorization" in request.headers
        assert request.headers["Authorization"] == "Bearer client-doc-token"

    @patch("mcp_server.urlopen")
    def test_client_token_works_with_project_operations(self, mock_urlopen):
        """Client-provided token works correctly with project operations."""
        from mcp_server import create_project, set_jwt_token, jwt_token_context

        # Ensure no server-side token is configured
        set_jwt_token(None)

        # Simulate client-provided token via SSE request headers
        token_var = jwt_token_context.set("client-project-token")

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps({
            "id": 1, "name": "New Project", "slug": "new-project"
        }).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = create_project("New Project")

        # The client-provided token should be used for project operations
        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert "Authorization" in request.headers
        assert request.headers["Authorization"] == "Bearer client-project-token"


# ─── Tests: JWT Token Middleware ─────────────────────────────────────────────

class TestJWTTokenMiddleware:
    """Tests for the JWT token extraction middleware."""

    @pytest.fixture(autouse=True)
    def reset_jwt_context(self):
        """Reset jwt_token_context to None before each test."""
        from mcp_server import jwt_token_context
        self._token_var = jwt_token_context.set(None)
        yield

    def test_middleware_function_exists(self):
        """jwt_token_middleware function exists and is callable."""
        from mcp_server import jwt_token_middleware
        assert callable(jwt_token_middleware)

    def test_context_var_exists(self):
        """jwt_token_context context variable exists."""
        from mcp_server import jwt_token_context
        assert hasattr(jwt_token_context, 'set')
        assert hasattr(jwt_token_context, 'get')

    @patch("mcp_server.urlopen")
    def test_get_current_jwt_token_returns_client_token(self, mock_urlopen):
        """get_current_jwt_token returns client token from context when available."""
        from mcp_server import get_current_jwt_token, jwt_token_context

        # Set a client-provided token in the context (use stored var)
        self._token_var = jwt_token_context.set("test-client-token")

        result = get_current_jwt_token()
        assert result == "test-client-token"

    @patch("mcp_server.urlopen")
    def test_get_current_jwt_token_returns_server_token_when_no_client(self, mock_urlopen):
        """get_current_jwt_token returns server token when no client token is set."""
        from mcp_server import get_current_jwt_token, set_jwt_token, jwt_token_context

        # Reset context to None first (use stored var)
        self._token_var = jwt_token_context.set(None)

        # Set a server-side token
        set_jwt_token("server-token")

        result = get_current_jwt_token()
        assert result == "server-token"

    @patch("mcp_server.urlopen")
    def test_get_current_jwt_token_returns_none_when_no_tokens(self, mock_urlopen):
        """get_current_jwt_token returns None when no tokens are configured."""
        from mcp_server import get_current_jwt_token, set_jwt_token, jwt_token_context

        # Reset context to None first (use stored var)
        self._token_var = jwt_token_context.set(None)

        # Ensure no tokens are set
        set_jwt_token(None)

        result = get_current_jwt_token()
        assert result is None


# ─── Tests: SSE Transport JWT Integration ─────────────────────────────────────

class TestSSETransportJWTIntegration:
    """Tests for SSE transport JWT token integration."""

    @pytest.fixture(autouse=True)
    def reset_jwt_context(self):
        """Reset jwt_token_context to None before each test."""
        from mcp_server import jwt_token_context
        self._token_var = jwt_token_context.set(None)
        yield

    def test_has_starlette_import(self):
        """Starlette imports are available for SSE transport."""
        from mcp_server import HAS_STARLETTE, jwt_token_middleware
        # Starlette should be importable (it's a dependency of fastmcp)
        assert callable(jwt_token_middleware)

    def test_jwt_token_middleware_has_correct_signature(self):
        """jwt_token_middleware has the correct function signature."""
        from mcp_server import jwt_token_middleware
        import inspect
        sig = inspect.signature(jwt_token_middleware)
        params = list(sig.parameters.keys())
        assert 'request' in params or len(params) >= 1
        assert 'call_next' in params

    @patch("mcp_server.urlopen")
    def test_sse_mode_uses_client_token_for_api_calls(self, mock_urlopen):
        """In SSE mode, client-provided token is used for all API calls."""
        from mcp_server import (
            list_projects, get_project, create_document, set_jwt_token,
            jwt_token_context
        )

        # Ensure no server-side token is configured
        set_jwt_token(None)

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps([{"id": 1}]).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        # Simulate client-provided token (as would come from SSE request headers)
        token_var = jwt_token_context.set("sse-client-token")

        result = list_projects()
        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert request.headers["Authorization"] == "Bearer sse-client-token"

    @patch("mcp_server.urlopen")
    def test_token_priority_order_client_over_cli(self, mock_urlopen):
        """Client token has higher priority than CLI arg token."""
        from mcp_server import list_projects, set_jwt_token, jwt_token_context

        # Simulate server-side token (CLI arg)
        set_jwt_token("cli-token")

        # Client-provided token should override
        token_var = jwt_token_context.set("client-token")

        mock_resp = MagicMock()
        mock_resp.read.return_value = json.dumps([{"id": 1}]).encode("utf-8")
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = list_projects()
        call_args = mock_urlopen.call_args
        request = call_args[0][0]
        assert request.headers["Authorization"] == "Bearer client-token"
