"""
Tests for the Wiki4AI MCP actor inbox tools (WIKI4AI-158).

What these tests are for
------------------------
The inbox has exactly one reader (inbox_poll) and one writer (inbox_mark_seen),
and the split is the point of the story:

- a GET never moves the seen-marker. That is a backend property, and the MCP
  layer must not work around it — so inbox_poll sends no body and no marker call,
  and the tests assert the method and the path.
- limit outside 1..50 is refused by the MCP layer BEFORE any request is sent
  (_api_request called zero times, asserted on the mock, never on real HTTP).
- an empty cursor is the first page, not an error.

Every test mocks mcp_server._api_request. No test opens a socket.
"""

import asyncio
import inspect
import os
import sys
from unittest.mock import patch

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..'))

import mcp_server
from mcp_server import (
    MCPToolError,
    create_mcp_server,
    inbox_mark_seen,
    inbox_poll,
    set_base_url,
    set_jwt_token,
)


NEW_TOOLS = ["inbox_poll", "inbox_mark_seen"]


# ─── Fixtures ────────────────────────────────────────────────────────────────

@pytest.fixture(autouse=True)
def reset_connection_state():
    set_base_url("http://localhost:8080/api")
    set_jwt_token(None)
    yield


@pytest.fixture
def api():
    """Patch _api_request so no test can reach a real server."""
    with patch("mcp_server._api_request") as mock:
        yield mock


def _backend_error(code: int, message: str) -> MCPToolError:
    return MCPToolError(f"API Error {code}: {message}", status_code=code,
                        details={"status": code, "message": message})


SAMPLE_INBOX = {
    "items": [
        {"stream": "assigned", "id": 4, "workItemId": 11, "kind": None,
         "title": "MCP work item tools", "body": None, "payload": None,
         "createdAt": "2026-10-10T12:00:00"},
        {"stream": "mentions", "id": 9, "workItemId": 12, "kind": "handoff",
         "title": None, "body": "please continue", "payload": {"to_handle": "ci-runner"},
         "createdAt": "2026-10-10T12:05:00"},
    ],
    "nextCursor": "eyJhc3NpZ25lZCI6NCwibWVudGlvbnMiOjl9",
    "limit": 50,
    "totalElements": 2,
}


# ─── inbox_poll: the read path ───────────────────────────────────────────────

def test_inbox_poll_first_page_without_cursor(api):
    """An empty cursor is the first page and must not be an error."""
    api.return_value = SAMPLE_INBOX

    result = inbox_poll(7)

    method, path = api.call_args.args
    assert method == "GET"
    assert path == "/v1/actors/7/inbox?limit=50"
    assert "cursor" not in path
    assert result["totalElements"] == 2


def test_inbox_poll_empty_string_cursor_is_first_page(api):
    api.return_value = SAMPLE_INBOX

    inbox_poll(7, cursor="")

    path = api.call_args.args[1]
    assert "cursor" not in path


def test_inbox_poll_returns_next_cursor_unchanged(api):
    api.return_value = SAMPLE_INBOX

    result = inbox_poll(7)

    assert result["nextCursor"] == SAMPLE_INBOX["nextCursor"]


def test_inbox_poll_sends_the_cursor_it_was_given(api):
    """The cursor is opaque: the tool stores and returns it, it never builds one."""
    api.return_value = SAMPLE_INBOX
    cursor = SAMPLE_INBOX["nextCursor"]

    inbox_poll(7, cursor=cursor, limit=10)

    path = api.call_args.args[1]
    assert "limit=10" in path
    # The cursor is URL-encoded on the way out and must decode back to the same string.
    from urllib.parse import unquote
    assert unquote(path.split("cursor=")[1].split("&")[0]) == cursor


def test_inbox_poll_is_a_get_with_no_body(api):
    """A GET never advances the marker: no body, no POST, no mark-seen call."""
    api.return_value = SAMPLE_INBOX

    inbox_poll(7)

    assert api.call_args.args[0] == "GET"
    assert len(api.call_args.args) == 2  # method + path, no body
    assert api.call_args.kwargs == {}


@pytest.mark.parametrize("bad_limit", [0, -1, 51, 1000])
def test_inbox_poll_rejects_limit_outside_range_with_zero_http_calls(api, bad_limit):
    with pytest.raises(MCPToolError) as exc:
        inbox_poll(7, limit=bad_limit)

    assert "limit" in exc.value.message
    assert "1" in exc.value.message and "50" in exc.value.message
    assert api.call_count == 0


def test_inbox_poll_rejects_non_integer_limit_with_zero_http_calls(api):
    with pytest.raises(MCPToolError) as exc:
        inbox_poll(7, limit="many")

    assert "limit" in exc.value.message
    assert api.call_count == 0


def test_inbox_poll_accepts_the_whole_valid_range(api):
    api.return_value = SAMPLE_INBOX

    for limit in (1, 25, 50):
        inbox_poll(7, limit=limit)

    assert api.call_count == 3


def test_inbox_poll_rejects_bad_actor_id_before_any_request(api):
    with pytest.raises(MCPToolError) as exc:
        inbox_poll(0)

    assert "actor_id" in exc.value.message
    assert api.call_count == 0


def test_inbox_poll_propagates_400_from_backend(api):
    api.side_effect = _backend_error(400, "Invalid value for parameter 'limit'")

    with pytest.raises(MCPToolError) as exc:
        inbox_poll(7, limit="42")  # numeric string passes the MCP check, backend rejects it

    # '42' is a valid integer for the MCP layer; the backend's 400 is preserved.
    assert exc.value.status_code == 400


def test_inbox_poll_propagates_403_foreign_inbox(api):
    api.side_effect = _backend_error(403, "You can only read the inbox of your own actor")

    with pytest.raises(MCPToolError) as exc:
        inbox_poll(7)

    assert exc.value.status_code == 403
    # A role problem is not a credential problem: no agent-token lecture.
    assert "w4a_" not in exc.value.message


def test_inbox_poll_403_without_actor_names_the_fix(api):
    """The real backend text for a legacy credential, wrapped with the credential hint."""
    api.side_effect = _backend_error(
        403,
        "An inbox belongs to an actor, and this credential names no actor; read it with a "
        "token bound to the actor",
    )

    with pytest.raises(MCPToolError) as exc:
        inbox_poll(7)

    message = exc.value.message
    assert exc.value.status_code == 403
    assert "actor" in message.lower()
    assert "w4a_" in message
    assert "Backend said:" in message


def test_inbox_poll_propagates_404_unknown_actor(api):
    api.side_effect = _backend_error(404, "Actor not found with id: 999")

    with pytest.raises(MCPToolError) as exc:
        inbox_poll(999)

    assert exc.value.status_code == 404
    assert "999" in exc.value.message


# ─── inbox_mark_seen: the write path ─────────────────────────────────────────

def test_inbox_mark_seen_without_event_id_marks_everything(api):
    api.return_value = {}

    result = inbox_mark_seen(7)

    method, path, *rest = api.call_args.args
    assert method == "POST"
    assert path == "/v1/actors/7/inbox/mark-seen"
    # No eventId requested: the body is omitted, which the backend reads as "all of it".
    assert not rest or rest[0] is None
    assert result == {}


def test_inbox_mark_seen_sends_event_id(api):
    api.return_value = {}

    inbox_mark_seen(7, event_id=123)

    assert api.call_args.args[2] == {"eventId": 123}


def test_inbox_mark_seen_is_idempotent_call(api):
    """The backend answers 204 twice; the tool returns {} twice and does not invent an error."""
    api.return_value = {}

    assert inbox_mark_seen(7) == {}
    assert inbox_mark_seen(7) == {}
    assert api.call_count == 2


def test_inbox_mark_seen_rejects_non_numeric_event_id_with_zero_http_calls(api):
    with pytest.raises(MCPToolError) as exc:
        inbox_mark_seen(7, event_id="latest")

    assert "event_id" in exc.value.message
    assert api.call_count == 0


def test_inbox_mark_seen_rejects_negative_event_id_with_zero_http_calls(api):
    with pytest.raises(MCPToolError) as exc:
        inbox_mark_seen(7, event_id=-5)

    assert "event_id" in exc.value.message
    assert api.call_count == 0


def test_inbox_mark_seen_propagates_404_unknown_actor(api):
    api.side_effect = _backend_error(404, "Actor not found with id: 999")

    with pytest.raises(MCPToolError) as exc:
        inbox_mark_seen(999)

    assert exc.value.status_code == 404


def test_inbox_mark_seen_propagates_403_foreign_inbox(api):
    api.side_effect = _backend_error(403, "You can only mark the inbox of the actor you are acting as")

    with pytest.raises(MCPToolError) as exc:
        inbox_mark_seen(7)

    assert exc.value.status_code == 403
    assert "w4a_" not in exc.value.message


def test_inbox_mark_seen_403_without_actor_names_the_fix(api):
    api.side_effect = _backend_error(
        403, "An inbox belongs to an actor, and this credential names no actor")

    with pytest.raises(MCPToolError) as exc:
        inbox_mark_seen(7)

    message = exc.value.message
    assert exc.value.status_code == 403
    assert "actor" in message.lower()
    assert "w4a_" in message


def test_inbox_mark_seen_never_reads_the_inbox(api):
    """The write half must not smuggle a read in: only the mark-seen path is called."""
    api.return_value = {}

    inbox_mark_seen(7, event_id=3)

    assert api.call_count == 1
    assert api.call_args.args[1].endswith("/inbox/mark-seen")
    assert "/inbox?" not in api.call_args.args[1]


# ─── Registration and docstrings ─────────────────────────────────────────────

def test_inbox_tools_are_registered_in_tools_list():
    server = create_mcp_server()
    names = {tool.name for tool in asyncio.run(server.list_tools())}

    for name in NEW_TOOLS:
        assert name in names, f"{name} is missing from the MCP tools list"


def test_inbox_tools_have_docstrings():
    for name in NEW_TOOLS:
        docstring = inspect.getdoc(getattr(mcp_server, name))
        assert docstring and docstring.strip(), f"{name} has no docstring"


@pytest.mark.parametrize("tool_name", NEW_TOOLS)
def test_inbox_tools_document_their_error_codes(tool_name):
    docstring = inspect.getdoc(getattr(mcp_server, tool_name)) or ""
    assert any(code in docstring for code in ("400", "403", "404")), (
        f"{tool_name} documents none of 400/403/404"
    )


def test_inbox_poll_docstring_says_a_get_does_not_move_the_marker():
    """The property an agent must not have to discover by experiment."""
    docstring = inspect.getdoc(mcp_server.inbox_poll) or ""
    assert "marker" in docstring
    assert "inbox_mark_seen" in docstring
