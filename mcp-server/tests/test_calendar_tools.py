"""
Tests for Wiki4AI MCP Server calendar tools (WIKI4AI-98).

Tests verify:
- Each tool calls the correct backend endpoint with the correct payload shape.
- Input validation (date/time/visibility formats, no-fields update) raises clear MCPToolErrors.
- Error paths (unknown event type → 400 with available types, duplicate type name → 409,
  someone else's private event → 404) propagate as MCPToolError from the backend response.
- All six calendar tools are registered in create_mcp_server().

Visibility rules (private visible only to owner+ADMIN, update/delete owner/ADMIN only)
are enforced by the backend API — these tests mock the HTTP layer and verify that the
tools forward requests unchanged (identity JWT via _api_request).
"""

import json
import sys
import os
from io import BytesIO
from unittest.mock import patch, MagicMock
from urllib.error import HTTPError

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

from mcp_server import (
    calendar_create_event,
    calendar_list_events,
    calendar_update_event,
    calendar_delete_event,
    calendar_list_event_types,
    calendar_create_event_type,
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


def _mock_response(body):
    """Build a mock urlopen context-manager response returning JSON body."""
    resp = MagicMock()
    resp.read.return_value = json.dumps(body).encode("utf-8")
    return resp


def _http_error(code, body):
    """Build an HTTPError with a JSON body (as raised by _api_request on non-2xx)."""
    return HTTPError(
        url="http://localhost:8080/api/v1/calendar/events",
        code=code,
        msg=str(code),
        hdrs={},
        fp=BytesIO(json.dumps(body).encode("utf-8")),
    )


@pytest.fixture
def mock_event_types_response():
    """Sample GET /v1/calendar/event-types response (seed types first)."""
    return [
        {"id": 1, "name": "Agent task", "color": "#4f8cff", "createdAt": "2026-09-26T10:00:00"},
        {"id": 2, "name": "Pripomienka", "color": None, "createdAt": "2026-09-26T10:00:00"},
    ]


@pytest.fixture
def mock_created_event_response():
    """Sample POST /v1/calendar/events response (CalendarEventDTO)."""
    return {
        "id": 42,
        "title": "Deploy to staging",
        "description": None,
        "eventTypeId": 1,
        "eventType": "Agent task",
        "eventColor": "#4f8cff",
        "eventDate": "2026-09-30",
        "startTime": None,
        "endTime": None,
        "visibility": "public",
        "createdBy": "keepee",
        "createdAt": "2026-09-26T12:00:00",
        "updatedAt": "2026-09-26T12:00:00",
    }


@pytest.fixture
def mock_listed_events_response():
    """Sample GET /v1/calendar/events response (list of CalendarEventDTO)."""
    return [
        {
            "id": 42,
            "title": "All-day event",
            "description": None,
            "eventTypeId": 1,
            "eventType": "Agent task",
            "eventColor": "#4f8cff",
            "eventDate": "2026-09-30",
            "startTime": None,
            "endTime": None,
            "visibility": "public",
            "createdBy": "keepee",
            "createdAt": "2026-09-26T12:00:00",
            "updatedAt": "2026-09-26T12:00:00",
        },
        {
            "id": 43,
            "title": "Timed private event",
            "description": "sync",
            "eventTypeId": 2,
            "eventType": "Pripomienka",
            "eventColor": None,
            "eventDate": "2026-09-28",
            "startTime": "10:00:00",
            "endTime": "11:00:00",
            "visibility": "private",
            "createdBy": "keepee",
            "createdAt": "2026-09-26T12:05:00",
            "updatedAt": "2026-09-26T12:05:00",
        },
    ]


@pytest.fixture
def mock_created_type_response():
    """Sample POST /v1/calendar/event-types response (EventTypeDTO)."""
    return {"id": 3, "name": "Deployment", "color": "#ff6b35", "createdAt": "2026-09-26T12:10:00"}


# ─── Tests: calendar_create_event API call ────────────────────────────────

class TestCalendarCreateEventAPICall:
    """Tests that calendar_create_event calls the correct endpoint with the right body."""

    @patch("mcp_server.urlopen")
    def test_calls_correct_endpoint(self, mock_urlopen, mock_created_event_response):
        """calendar_create_event calls POST /v1/calendar/events."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_created_event_response)

        calendar_create_event("Deploy to staging", "2026-09-30", event_type="Agent task")

        request = mock_urlopen.call_args[0][0]
        assert str(request.full_url) == "http://localhost:8080/api/v1/calendar/events"
        assert request.get_method() == "POST"

    @patch("mcp_server.urlopen")
    def test_sends_required_fields_in_body(self, mock_urlopen, mock_created_event_response):
        """title, eventDate, eventType and visibility are always sent."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_created_event_response)

        calendar_create_event("Deploy to staging", "2026-09-30", event_type="Agent task")

        request = mock_urlopen.call_args[0][0]
        body = json.loads(request.data.decode("utf-8"))
        assert body["title"] == "Deploy to staging"
        assert body["eventDate"] == "2026-09-30"
        assert body["eventType"] == "Agent task"
        assert body["visibility"] == "public"

    @patch("mcp_server.urlopen")
    def test_sends_optional_fields_when_provided(self, mock_urlopen, mock_created_event_response):
        """description, start_time and end_time are sent when provided."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_created_event_response)

        calendar_create_event(
            "Team sync", "2026-09-28",
            start_time="10:00", end_time="11:30",
            description="weekly sync", event_type="Pripomienka", visibility="private",
        )

        request = mock_urlopen.call_args[0][0]
        body = json.loads(request.data.decode("utf-8"))
        assert body["description"] == "weekly sync"
        assert body["startTime"] == "10:00"
        assert body["endTime"] == "11:30"
        assert body["visibility"] == "private"

    @patch("mcp_server.urlopen")
    def test_omits_optional_fields_when_not_provided(self, mock_urlopen, mock_created_event_response):
        """All-day event without description sends no time/description keys."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_created_event_response)

        calendar_create_event("Deploy to staging", "2026-09-30", event_type="Agent task")

        request = mock_urlopen.call_args[0][0]
        body = json.loads(request.data.decode("utf-8"))
        assert "startTime" not in body
        assert "endTime" not in body
        assert "description" not in body

    @patch("mcp_server.urlopen")
    def test_defaults_event_type_to_first_available(self, mock_urlopen, mock_event_types_response, mock_created_event_response):
        """event_type=None → GET /v1/calendar/event-types first, then POST with the first type name."""
        types_resp = _mock_response(mock_event_types_response)
        create_resp = _mock_response(mock_created_event_response)
        first_call = MagicMock()
        first_call.__enter__.return_value = types_resp
        second_call = MagicMock()
        second_call.__enter__.return_value = create_resp
        mock_urlopen.side_effect = [first_call, second_call]

        calendar_create_event("Deploy to staging", "2026-09-30")

        assert mock_urlopen.call_count == 2
        first_request = mock_urlopen.call_args_list[0][0][0]
        assert str(first_request.full_url) == "http://localhost:8080/api/v1/calendar/event-types"
        second_request = mock_urlopen.call_args_list[1][0][0]
        body = json.loads(second_request.data.decode("utf-8"))
        assert body["eventType"] == "Agent task"  # first available type name

    @patch("mcp_server.urlopen")
    def test_unknown_event_type_raises_error_with_available_types(self, mock_urlopen):
        """Backend 400 for an unknown event type propagates as MCPToolError with the type list."""
        error_body = {
            "timestamp": "2026-09-26T12:00:00",
            "status": 400,
            "error": "Bad Request",
            "message": "Unknown event type 'Bogus'. Available types: Agent task, Pripomienka",
        }
        mock_urlopen.side_effect = _http_error(400, error_body)

        with pytest.raises(MCPToolError) as exc_info:
            calendar_create_event("Event", "2026-09-30", event_type="Bogus")

        message = str(exc_info.value)
        assert "400" in message
        assert "Unknown event type 'Bogus'" in message
        assert "Available types: Agent task, Pripomienka" in message


# ─── Tests: calendar_create_event validation ──────────────────────────────

class TestCalendarCreateEventValidation:
    """Input validation raises clear MCPToolErrors before any HTTP call."""

    @patch("mcp_server.urlopen")
    def test_invalid_date_rejected(self, mock_urlopen):
        with pytest.raises(MCPToolError) as exc_info:
            calendar_create_event("Event", "30/09/2026", event_type="Agent task")
        assert "YYYY-MM-DD" in str(exc_info.value)
        mock_urlopen.assert_not_called()

    @patch("mcp_server.urlopen")
    def test_invalid_start_time_rejected(self, mock_urlopen):
        with pytest.raises(MCPToolError) as exc_info:
            calendar_create_event("Event", "2026-09-30", start_time="25:99", event_type="Agent task")
        assert "HH:MM" in str(exc_info.value)
        mock_urlopen.assert_not_called()

    @patch("mcp_server.urlopen")
    def test_invalid_end_time_rejected(self, mock_urlopen):
        with pytest.raises(MCPToolError) as exc_info:
            calendar_create_event("Event", "2026-09-30", end_time="10am", event_type="Agent task")
        assert "HH:MM" in str(exc_info.value)
        mock_urlopen.assert_not_called()

    @patch("mcp_server.urlopen")
    def test_invalid_visibility_rejected(self, mock_urlopen):
        with pytest.raises(MCPToolError) as exc_info:
            calendar_create_event("Event", "2026-09-30", event_type="Agent task", visibility="internal")
        assert "'public' or 'private'" in str(exc_info.value)
        mock_urlopen.assert_not_called()

    @patch("mcp_server.urlopen")
    def test_visibility_normalized_to_lowercase(self, mock_urlopen, mock_created_event_response):
        """Visibility matching is case-insensitive; the backend gets lowercase."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_created_event_response)

        calendar_create_event("Event", "2026-09-30", event_type="Agent task", visibility="PRIVATE")

        request = mock_urlopen.call_args[0][0]
        body = json.loads(request.data.decode("utf-8"))
        assert body["visibility"] == "private"


# ─── Tests: calendar_create_event response format ─────────────────────────

class TestCalendarCreateEventResponseFormat:
    """calendar_create_event returns the created CalendarEventDTO."""

    @patch("mcp_server.urlopen")
    def test_returns_dict_with_event_fields(self, mock_urlopen, mock_created_event_response):
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_created_event_response)

        result = calendar_create_event("Deploy to staging", "2026-09-30", event_type="Agent task")

        assert isinstance(result, dict)
        assert result["id"] == 42
        assert result["title"] == "Deploy to staging"
        assert result["eventDate"] == "2026-09-30"
        assert result["eventType"] == "Agent task"
        assert result["visibility"] == "public"


# ─── Tests: calendar_list_events API call ─────────────────────────────────

class TestCalendarListEventsAPICall:
    """Tests that calendar_list_events calls the correct endpoint with query params."""

    @patch("mcp_server.urlopen")
    def test_default_call_has_no_query_params(self, mock_urlopen, mock_listed_events_response):
        """Omitted from/to → backend defaults to the current month (no query string)."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_listed_events_response)

        calendar_list_events()

        request = mock_urlopen.call_args[0][0]
        assert str(request.full_url) == "http://localhost:8080/api/v1/calendar/events"
        assert request.get_method() == "GET"

    @patch("mcp_server.urlopen")
    def test_sends_all_query_params(self, mock_urlopen, mock_listed_events_response):
        """from/to/type/mine are sent as query parameters (type = backend param name)."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_listed_events_response)

        calendar_list_events(from_date="2026-09-28", to_date="2026-10-04", event_type="Agent task", mine=True)

        url_str = str(mock_urlopen.call_args[0][0].full_url)
        assert "from=2026-09-28" in url_str
        assert "to=2026-10-04" in url_str
        # 'Agent task' must be URL-encoded (space → + or %20)
        assert "type=Agent+task" in url_str or "type=Agent%20task" in url_str
        assert "mine=true" in url_str

    @patch("mcp_server.urlopen")
    def test_mine_false_not_sent(self, mock_urlopen, mock_listed_events_response):
        """mine=False (default) is omitted from the query string."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_listed_events_response)

        calendar_list_events(from_date="2026-09-28")

        url_str = str(mock_urlopen.call_args[0][0].full_url)
        assert "mine" not in url_str

    @patch("mcp_server.urlopen")
    def test_invalid_from_date_rejected(self, mock_urlopen):
        with pytest.raises(MCPToolError) as exc_info:
            calendar_list_events(from_date="28-09-2026")
        assert "YYYY-MM-DD" in str(exc_info.value)
        mock_urlopen.assert_not_called()


# ─── Tests: calendar_list_events response format ──────────────────────────

class TestCalendarListEventsResponseFormat:
    """calendar_list_events returns a list of event dicts."""

    @patch("mcp_server.urlopen")
    def test_returns_list_of_dicts(self, mock_urlopen, mock_listed_events_response):
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_listed_events_response)

        result = calendar_list_events()

        assert isinstance(result, list)
        assert len(result) == 2
        assert result[0]["id"] == 42
        assert result[1]["startTime"] == "10:00:00"
        assert result[1]["visibility"] == "private"

    @patch("mcp_server.urlopen")
    def test_returns_empty_list(self, mock_urlopen):
        mock_urlopen.return_value.__enter__.return_value = _mock_response([])

        result = calendar_list_events()

        assert result == []


# ─── Tests: calendar_update_event API call ────────────────────────────────

class TestCalendarUpdateEventAPICall:
    """Tests that calendar_update_event is PATCH-like (only provided fields sent)."""

    @patch("mcp_server.urlopen")
    def test_calls_correct_endpoint(self, mock_urlopen, mock_created_event_response):
        """calendar_update_event calls PUT /v1/calendar/events/{id}."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_created_event_response)

        calendar_update_event(42, title="Renamed")

        request = mock_urlopen.call_args[0][0]
        assert str(request.full_url) == "http://localhost:8080/api/v1/calendar/events/42"
        assert request.get_method() == "PUT"

    @patch("mcp_server.urlopen")
    def test_sends_only_provided_fields(self, mock_urlopen, mock_created_event_response):
        """Omitted fields are absent from the body (PATCH-like semantics)."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_created_event_response)

        calendar_update_event(42, title="Renamed", visibility="private")

        body = json.loads(mock_urlopen.call_args[0][0].data.decode("utf-8"))
        assert body == {"title": "Renamed", "visibility": "private"}

    @patch("mcp_server.urlopen")
    def test_sends_all_fields_when_provided(self, mock_urlopen, mock_created_event_response):
        """All optional fields map to the backend DTO names."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_created_event_response)

        calendar_update_event(
            42, title="T", description="D", date="2026-10-01",
            start_time="09:00", end_time="10:00", event_type="Agent task", visibility="public",
        )

        body = json.loads(mock_urlopen.call_args[0][0].data.decode("utf-8"))
        assert body == {
            "title": "T",
            "description": "D",
            "eventDate": "2026-10-01",
            "startTime": "09:00",
            "endTime": "10:00",
            "eventType": "Agent task",
            "visibility": "public",
        }

    @patch("mcp_server.urlopen")
    def test_clear_time_flag_sent_when_true(self, mock_urlopen, mock_created_event_response):
        """clear_time=True sends clearTime=true (reverts timed event to all-day)."""
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_created_event_response)

        calendar_update_event(42, clear_time=True)

        body = json.loads(mock_urlopen.call_args[0][0].data.decode("utf-8"))
        assert body == {"clearTime": True}

    @patch("mcp_server.urlopen")
    def test_no_fields_raises_error(self, mock_urlopen):
        """Updating with no fields at all raises a clear MCPToolError before any HTTP call."""
        with pytest.raises(MCPToolError) as exc_info:
            calendar_update_event(42)
        assert "No fields to update" in str(exc_info.value)
        mock_urlopen.assert_not_called()

    @patch("mcp_server.urlopen")
    def test_unknown_event_type_raises_error_with_available_types(self, mock_urlopen):
        """Backend 400 for an unknown event type propagates as MCPToolError."""
        error_body = {
            "status": 400,
            "message": "Unknown event type 'Bogus'. Available types: Agent task, Pripomienka",
        }
        mock_urlopen.side_effect = _http_error(400, error_body)

        with pytest.raises(MCPToolError) as exc_info:
            calendar_update_event(42, event_type="Bogus")

        assert "Unknown event type 'Bogus'" in str(exc_info.value)

    @patch("mcp_server.urlopen")
    def test_someone_elses_private_event_returns_404(self, mock_urlopen):
        """Backend 404 (someone else's private event) propagates as MCPToolError."""
        error_body = {"status": 404, "message": "Calendar event not found"}
        mock_urlopen.side_effect = _http_error(404, error_body)

        with pytest.raises(MCPToolError) as exc_info:
            calendar_update_event(99, title="hack")

        assert "404" in str(exc_info.value)


# ─── Tests: calendar_delete_event API call ────────────────────────────────

class TestCalendarDeleteEventAPICall:
    """Tests that calendar_delete_event calls DELETE /v1/calendar/events/{id}."""

    @patch("mcp_server.urlopen")
    def test_calls_correct_endpoint(self, mock_urlopen):
        """DELETE returns 204 (empty body) → tool returns a confirmation message."""
        mock_resp = MagicMock()
        mock_resp.read.return_value = b""
        mock_urlopen.return_value.__enter__.return_value = mock_resp

        result = calendar_delete_event(42)

        request = mock_urlopen.call_args[0][0]
        assert str(request.full_url) == "http://localhost:8080/api/v1/calendar/events/42"
        assert request.get_method() == "DELETE"
        assert result["message"] == "Calendar event 42 deleted successfully"

    @patch("mcp_server.urlopen")
    def test_someone_elses_public_event_returns_403(self, mock_urlopen):
        """Backend 403 (someone else's public event) propagates as MCPToolError."""
        error_body = {"status": 403, "message": "Access denied: only the owner or an admin may delete this event"}
        mock_urlopen.side_effect = _http_error(403, error_body)

        with pytest.raises(MCPToolError) as exc_info:
            calendar_delete_event(99)

        assert "403" in str(exc_info.value)


# ─── Tests: calendar_list_event_types ─────────────────────────────────────

class TestCalendarListEventTypesAPICall:
    """Tests that calendar_list_event_types calls GET /v1/calendar/event-types."""

    @patch("mcp_server.urlopen")
    def test_calls_correct_endpoint(self, mock_urlopen, mock_event_types_response):
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_event_types_response)

        calendar_list_event_types()

        request = mock_urlopen.call_args[0][0]
        assert str(request.full_url) == "http://localhost:8080/api/v1/calendar/event-types"
        assert request.get_method() == "GET"

    @patch("mcp_server.urlopen")
    def test_returns_list_of_type_dicts(self, mock_urlopen, mock_event_types_response):
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_event_types_response)

        result = calendar_list_event_types()

        assert isinstance(result, list)
        assert len(result) == 2
        assert result[0] == {"id": 1, "name": "Agent task", "color": "#4f8cff", "createdAt": "2026-09-26T10:00:00"}


# ─── Tests: calendar_create_event_type ────────────────────────────────────

class TestCalendarCreateEventTypeAPICall:
    """Tests that calendar_create_event_type calls POST /v1/calendar/event-types."""

    @patch("mcp_server.urlopen")
    def test_calls_correct_endpoint_with_name(self, mock_urlopen, mock_created_type_response):
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_created_type_response)

        calendar_create_event_type("Deployment")

        request = mock_urlopen.call_args[0][0]
        assert str(request.full_url) == "http://localhost:8080/api/v1/calendar/event-types"
        assert request.get_method() == "POST"
        body = json.loads(request.data.decode("utf-8"))
        assert body == {"name": "Deployment"}

    @patch("mcp_server.urlopen")
    def test_sends_color_when_provided(self, mock_urlopen, mock_created_type_response):
        mock_urlopen.return_value.__enter__.return_value = _mock_response(mock_created_type_response)

        calendar_create_event_type("Deployment", color="#ff6b35")

        body = json.loads(mock_urlopen.call_args[0][0].data.decode("utf-8"))
        assert body == {"name": "Deployment", "color": "#ff6b35"}

    @patch("mcp_server.urlopen")
    def test_duplicate_name_returns_409(self, mock_urlopen):
        """Backend 409 for a duplicate type name propagates as MCPToolError."""
        error_body = {"status": 409, "message": "An event type with this name already exists"}
        mock_urlopen.side_effect = _http_error(409, error_body)

        with pytest.raises(MCPToolError) as exc_info:
            calendar_create_event_type("Agent task")

        message = str(exc_info.value)
        assert "409" in message
        assert "already exists" in message


# ─── Tests: registration ──────────────────────────────────────────────────

class TestCalendarToolsRegistration:
    """All six calendar tools must be registered on the MCP server (WIKI4AI-98)."""

    def test_all_calendar_tools_are_registered(self):
        import asyncio

        mcp = create_mcp_server()
        tools = {t.name for t in asyncio.run(mcp.list_tools())}
        assert {
            "calendar_create_event",
            "calendar_list_events",
            "calendar_update_event",
            "calendar_delete_event",
            "calendar_list_event_types",
            "calendar_create_event_type",
        } <= tools

    def test_existing_tools_still_registered(self):
        """Adding the calendar tools must not break existing registrations."""
        import asyncio

        mcp = create_mcp_server()
        tools = {t.name for t in asyncio.run(mcp.list_tools())}
        assert "list_projects" in tools
        assert "create_document" in tools
        assert "vault_status" in tools

    def test_docstrings_document_params_and_defaults(self):
        """AC: docstringy s parametrami a defaultmi."""
        create_doc = calendar_create_event.__doc__ or ""
        assert "YYYY-MM-DD" in create_doc
        assert "'public' (default) or 'private'" in create_doc
        assert "start_time" in create_doc and "end_time" in create_doc

        list_doc = calendar_list_events.__doc__ or ""
        assert "Default: 1st day of the current month" in list_doc
        assert "mine" in list_doc

        update_doc = calendar_update_event.__doc__ or ""
        assert "clear_time" in update_doc
        assert "Default False" in update_doc
