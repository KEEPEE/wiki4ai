"""
Tests for the Wiki4AI MCP work item tools (WIKI4AI-158).

What these tests are for
------------------------
The coordination rules of this story are enforced in TWO places, and the tests
below are the proof of both:

1. In the MCP layer, BEFORE any HTTP request is sent:
   - workitem_update / workitem_create refuse status='claimed' (MCPToolError, and
     _api_request is called ZERO times — asserted with a mock, never real HTTP);
   - an unknown status or kind is refused the same way.
   The point is not to duplicate the backend: it is that an agent gets an error
   that names the call to make next, instead of a 409 whose reason it must guess.

2. In the backend, reached through the MCP layer: the claim race (409), the
   credential-without-actor refusal (403), the 'done' invariant (409 with the
   token RESULT_COMMENT_REQUIRED) and the comment payload contract (422). Here the
   tests assert that the MCP tool PRESERVES the status code and the reason, and
   adds the instruction an agent needs.

Every test mocks mcp_server._api_request. No test opens a socket, and none of
them can reach a real server.
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
    actor_whoami,
    create_mcp_server,
    set_base_url,
    set_jwt_token,
    workitem_claim,
    workitem_comment,
    workitem_create,
    workitem_get,
    workitem_list,
    workitem_update,
)


NEW_TOOLS = [
    "actor_whoami",
    "workitem_list",
    "workitem_get",
    "workitem_create",
    "workitem_claim",
    "workitem_update",
    "workitem_comment",
]


# ─── Fixtures ────────────────────────────────────────────────────────────────

@pytest.fixture(autouse=True)
def reset_connection_state():
    """Reset BASE_URL / JWT_TOKEN so no test can inherit a live-looking config."""
    set_base_url("http://localhost:8080/api")
    set_jwt_token(None)
    yield


@pytest.fixture
def api():
    """Patch _api_request. Patching the helper (not urlopen) guarantees no HTTP."""
    with patch("mcp_server._api_request") as mock:
        yield mock


def _backend_error(code: int, message: str) -> MCPToolError:
    """The MCPToolError _api_request raises when the backend answers non-2xx."""
    return MCPToolError(f"API Error {code}: {message}", status_code=code,
                        details={"status": code, "message": message})


SAMPLE_ITEM = {
    "id": 11,
    "projectId": 3,
    "title": "MCP work item tools",
    "body": "body text",
    "status": "ready",
    "priority": "high",
    "labels": ["mcp"],
    "parentId": None,
    "externalRef": "WIKI4AI-158",
    "assigneeActorId": None,
    "createdByUserId": 1,
    "createdAt": "2026-10-10T12:00:00",
    "updatedAt": "2026-10-10T12:00:00",
    "version": 2,
}


# ─── actor_whoami ────────────────────────────────────────────────────────────

def test_actor_whoami_calls_whoami_endpoint(api):
    api.return_value = {"userId": 1, "username": "keepee", "actorId": 7,
                        "handle": "ci-runner", "kind": "agent", "scopes": ["resources"]}

    result = actor_whoami()

    api.assert_called_once_with("GET", "/v1/actors/whoami")
    assert result["actorId"] == 7
    assert result["handle"] == "ci-runner"


def test_actor_whoami_accepts_credential_without_actor(api):
    """actorId null is a valid answer, not an error — the tool must not invent one."""
    api.return_value = {"userId": 1, "username": "keepee", "actorId": None,
                        "handle": None, "kind": None, "scopes": []}

    result = actor_whoami()

    assert result["actorId"] is None
    assert result["scopes"] == []


def test_actor_whoami_propagates_401(api):
    api.side_effect = _backend_error(401, "Full authentication is required")

    with pytest.raises(MCPToolError) as exc:
        actor_whoami()

    assert exc.value.status_code == 401


# ─── workitem_list ───────────────────────────────────────────────────────────

def test_workitem_list_without_filters(api):
    api.return_value = [SAMPLE_ITEM]

    result = workitem_list()

    api.assert_called_once_with("GET", "/v1/work-items")
    assert result["total"] == 1
    assert result["items"][0]["id"] == 11


def test_workitem_list_sends_backend_query_params(api):
    api.return_value = []

    workitem_list(project_id=3, status="READY", label="mcp")

    method, path = api.call_args.args
    assert method == "GET"
    assert path.startswith("/v1/work-items?")
    assert "projectId=3" in path
    assert "status=ready" in path
    assert "label=mcp" in path


def test_workitem_list_filters_priority_and_pages_in_mcp_layer(api):
    """The backend endpoint is unpaged and has no priority filter; the MCP layer does both."""
    api.return_value = [
        {"id": 1, "priority": "high"},
        {"id": 2, "priority": "low"},
        {"id": 3, "priority": "high"},
        {"id": 4, "priority": "high"},
    ]

    result = workitem_list(priority="high", page=1, size=2)

    assert result["total"] == 3
    assert result["returned"] == 1
    assert [item["id"] for item in result["items"]] == [4]
    assert result["page"] == 1 and result["size"] == 2
    # priority/page/size are MCP-side: they are not sent to the backend.
    assert "priority" not in api.call_args.args[1]
    assert "size" not in api.call_args.args[1]


def test_workitem_list_rejects_unknown_status_before_any_request(api):
    with pytest.raises(MCPToolError) as exc:
        workitem_list(status="in-progress")

    assert "status" in exc.value.message
    assert "backlog" in exc.value.message
    assert api.call_count == 0


def test_workitem_list_rejects_bad_page_size(api):
    with pytest.raises(MCPToolError) as exc:
        workitem_list(size=0)
    assert "size" in exc.value.message
    assert api.call_count == 0

    with pytest.raises(MCPToolError) as exc:
        workitem_list(page=-1)
    assert "page" in exc.value.message
    assert api.call_count == 0


def test_workitem_list_propagates_404_for_private_project(api):
    api.side_effect = _backend_error(404, "Project not found with id: 99")

    with pytest.raises(MCPToolError) as exc:
        workitem_list(project_id=99)

    assert exc.value.status_code == 404


# ─── workitem_get ────────────────────────────────────────────────────────────

def test_workitem_get_calls_item_endpoint(api):
    api.return_value = SAMPLE_ITEM

    result = workitem_get(11)

    api.assert_called_once_with("GET", "/v1/work-items/11")
    assert result["status"] == "ready"


def test_workitem_get_rejects_non_numeric_id_before_any_request(api):
    with pytest.raises(MCPToolError) as exc:
        workitem_get("not-a-number")

    assert "work_item_id" in exc.value.message
    assert api.call_count == 0


def test_workitem_get_propagates_404(api):
    api.side_effect = _backend_error(404, "Work item not found with id: 12345")

    with pytest.raises(MCPToolError) as exc:
        workitem_get(12345)

    assert exc.value.status_code == 404
    assert "12345" in exc.value.message


# ─── workitem_create ─────────────────────────────────────────────────────────

def test_workitem_create_defaults_to_backlog_and_sends_body_field(api):
    api.return_value = SAMPLE_ITEM

    workitem_create(project_id=3, title="MCP work item tools", description="body text")

    method, path, body = api.call_args.args
    assert method == "POST"
    assert path == "/v1/work-items"
    assert body["projectId"] == 3
    assert body["title"] == "MCP work item tools"
    # The API field is called `body`; the tool parameter is `description`.
    assert body["body"] == "body text"
    # No status was requested: the backend default is 'backlog'.
    assert "status" not in body


def test_workitem_create_sends_status_when_requested(api):
    api.return_value = SAMPLE_ITEM

    workitem_create(project_id=3, title="t", status="REVIEW", priority="HIGH",
                    labels=["a", "b"], external_ref="JIRA-1", parent_id=7)

    body = api.call_args.args[2]
    assert body["status"] == "review"
    assert body["priority"] == "high"
    assert body["labels"] == ["a", "b"]
    assert body["externalRef"] == "JIRA-1"
    assert body["parentId"] == 7


def test_workitem_create_rejects_missing_project_id_before_any_request(api):
    with pytest.raises(MCPToolError) as exc:
        workitem_create(project_id=None, title="t")

    assert "project_id" in exc.value.message
    assert api.call_count == 0


def test_workitem_create_rejects_blank_title_before_any_request(api):
    with pytest.raises(MCPToolError) as exc:
        workitem_create(project_id=3, title="   ")

    assert "title" in exc.value.message
    assert api.call_count == 0


def test_workitem_create_rejects_claimed_status_with_zero_http_calls(api):
    """'claimed' is not a state a create can produce — refused here, nothing sent."""
    with pytest.raises(MCPToolError) as exc:
        workitem_create(project_id=3, title="t", status="claimed")

    assert "claimed" in exc.value.message
    assert "workitem_claim" in exc.value.message
    assert api.call_count == 0


def test_workitem_create_propagates_409_duplicate_external_ref(api):
    api.side_effect = _backend_error(409, "Work item with externalRef 'WIKI4AI-158' already exists")

    with pytest.raises(MCPToolError) as exc:
        workitem_create(project_id=3, title="t", external_ref="WIKI4AI-158")

    assert exc.value.status_code == 409


def test_workitem_create_propagates_done_invariant_409_token(api):
    """'done' needs a 'result' comment; the machine-readable token must survive."""
    api.side_effect = _backend_error(
        409,
        "RESULT_COMMENT_REQUIRED: a work item cannot be created in status 'done' ...",
    )

    with pytest.raises(MCPToolError) as exc:
        workitem_create(project_id=3, title="t", status="done")

    assert exc.value.status_code == 409
    assert "RESULT_COMMENT_REQUIRED" in exc.value.message


# ─── workitem_claim ──────────────────────────────────────────────────────────

def test_workitem_claim_calls_claim_endpoint_and_returns_claim_triple(api):
    api.return_value = {"id": 11, "status": "claimed", "assigneeActorId": 7,
                        "claimToken": "a" * 64, "leaseExpiresAt": "2026-10-10T13:00:00",
                        "version": 3, "updatedAt": "2026-10-10T12:00:00"}

    result = workitem_claim(11)

    api.assert_called_once_with("POST", "/v1/work-items/11/claim")
    assert result["status"] == "claimed"
    assert result["claimToken"]


def test_workitem_claim_409_says_the_item_is_not_ready(api):
    api.side_effect = _backend_error(
        409,
        "Work item 11 could not be claimed: it is not in status 'ready' "
        "(it was already claimed, is being worked on, or is finished/cancelled).",
    )

    with pytest.raises(MCPToolError) as exc:
        workitem_claim(11)

    message = exc.value.message
    assert exc.value.status_code == 409
    assert "ready" in message
    # The agent is told what to do instead of being left with a bare 409.
    assert "workitem_get" in message
    assert "workitem_update" in message


def test_workitem_claim_403_without_actor_tells_the_agent_what_to_do(api):
    api.side_effect = _backend_error(
        403,
        "Claiming a work item requires an actor identity; this credential names no actor",
    )

    with pytest.raises(MCPToolError) as exc:
        workitem_claim(11)

    message = exc.value.message
    assert exc.value.status_code == 403
    assert "actor" in message.lower()
    assert "w4a_" in message
    assert "Backend said:" in message


def test_workitem_claim_keeps_project_write_403_as_a_different_answer(api):
    """A 403 about roles must NOT be rewritten into the actor-credential message."""
    api.side_effect = _backend_error(
        403,
        "You can only claim work items of projects you own, projects you administer, "
        "or projects where your actor has at least the 'worker' role",
    )

    with pytest.raises(MCPToolError) as exc:
        workitem_claim(11)

    assert exc.value.status_code == 403
    assert "worker" in exc.value.message
    assert "w4a_" not in exc.value.message


def test_workitem_claim_propagates_404(api):
    api.side_effect = _backend_error(404, "Work item not found with id: 999")

    with pytest.raises(MCPToolError) as exc:
        workitem_claim(999)

    assert exc.value.status_code == 404


# ─── workitem_update ─────────────────────────────────────────────────────────

def test_workitem_update_sends_only_the_fields_given(api):
    api.return_value = SAMPLE_ITEM

    workitem_update(11, status="review")

    method, path, body = api.call_args.args
    assert method == "PUT"
    assert path == "/v1/work-items/11"
    assert body == {"status": "review"}


def test_workitem_update_maps_description_to_body(api):
    api.return_value = SAMPLE_ITEM

    workitem_update(11, description="new body", labels=[])

    body = api.call_args.args[2]
    assert body["body"] == "new body"
    # [] means "remove all labels" and must survive as [], not be dropped.
    assert body["labels"] == []


def test_workitem_update_rejects_claimed_status_with_zero_http_calls(api):
    """THE rule of WIKI4AI-158, measured: no request leaves the MCP server."""
    with pytest.raises(MCPToolError) as exc:
        workitem_update(11, status="claimed")

    message = exc.value.message
    assert "claimed" in message
    assert "workitem_claim" in message
    # Proof that the refusal happened before the HTTP layer: the helper was never called.
    assert api.call_count == 0


def test_workitem_update_rejects_claimed_in_every_spelling(api):
    for value in ("CLAIMED", " claimed ", "Claimed"):
        with pytest.raises(MCPToolError):
            workitem_update(11, status=value)

    assert api.call_count == 0


def test_workitem_update_rejects_unknown_status_with_zero_http_calls(api):
    with pytest.raises(MCPToolError) as exc:
        workitem_update(11, status="done-ish")

    assert "status" in exc.value.message
    assert api.call_count == 0


def test_workitem_update_allows_the_other_statuses(api):
    api.return_value = SAMPLE_ITEM

    for value in ("backlog", "ready", "running", "review", "done", "blocked", "cancelled"):
        workitem_update(11, status=value)

    assert api.call_count == 7


def test_workitem_update_propagates_illegal_transition_409(api):
    api.side_effect = _backend_error(
        409,
        "Invalid status transition 'backlog' -> 'review'. Allowed from 'backlog': ready",
    )

    with pytest.raises(MCPToolError) as exc:
        workitem_update(11, status="review")

    assert exc.value.status_code == 409
    assert "backlog" in exc.value.message


def test_workitem_update_propagates_done_invariant_with_token(api):
    api.side_effect = _backend_error(
        409,
        "RESULT_COMMENT_REQUIRED: work item 11 cannot enter status 'done' because it has "
        "no comment of kind 'result'.",
    )

    with pytest.raises(MCPToolError) as exc:
        workitem_update(11, status="done")

    assert exc.value.status_code == 409
    assert "RESULT_COMMENT_REQUIRED" in exc.value.message


def test_workitem_update_propagates_403_foreign_public_project(api):
    api.side_effect = _backend_error(403, "You can only update work items of projects you own")

    with pytest.raises(MCPToolError) as exc:
        workitem_update(11, title="x")

    assert exc.value.status_code == 403


# ─── workitem_comment ────────────────────────────────────────────────────────

def test_workitem_comment_sends_kind_payload_and_a_body(api):
    api.return_value = {"id": 5, "workItemId": 11, "authorActorId": 7, "kind": "result",
                        "body": "done on main", "payload": {"body": "done on main",
                                                            "where": "main",
                                                            "next": "review",
                                                            "artifacts": ["WIKI4AI-158"]},
                        "createdAt": "2026-10-10T12:00:00"}

    workitem_comment(11, "result", {"body": "done on main", "where": "main",
                                    "next": "review", "artifacts": ["WIKI4AI-158"]})

    method, path, body = api.call_args.args
    assert method == "POST"
    assert path == "/v1/work-items/11/comments"
    assert body["kind"] == "result"
    # The backend requires a non-empty body (400 without it); payload['body'] is used.
    assert body["body"] == "done on main"
    assert body["payload"]["where"] == "main"


def test_workitem_comment_writes_a_placeholder_body_when_none_is_given(api):
    api.return_value = {"id": 5, "kind": "note"}

    workitem_comment(11, "note", {"context": "heads up"})

    body = api.call_args.args[2]
    assert body["body"].strip() != ""
    assert "note" in body["body"]


def test_workitem_comment_rejects_unknown_kind_with_zero_http_calls(api):
    with pytest.raises(MCPToolError) as exc:
        workitem_comment(11, "progress")

    assert "kind" in exc.value.message
    assert "handoff" in exc.value.message
    assert api.call_count == 0


def test_workitem_comment_rejects_non_object_payload(api):
    with pytest.raises(MCPToolError) as exc:
        workitem_comment(11, "note", ["a", "b"])

    assert "payload" in exc.value.message
    assert api.call_count == 0


def test_workitem_comment_422_names_the_broken_contract(api):
    """422 here means a payload contract, never a JSON syntax problem."""
    api.side_effect = _backend_error(
        422,
        "Comment kind 'handoff' requires payload keys: to_handle, context",
    )

    with pytest.raises(MCPToolError) as exc:
        workitem_comment(11, "handoff", {"context": "please continue"})

    message = exc.value.message
    assert exc.value.status_code == 422
    assert "to_handle" in message
    assert "not a JSON syntax error" in message


def test_workitem_comment_403_without_actor_tells_the_agent_what_to_do(api):
    """The real backend text for this case, kept and wrapped with the credential hint."""
    api.side_effect = _backend_error(
        403,
        "A comment must be attributed to an actor. Authenticate with a w4a_ API token bound "
        "to an actor, or register a 'human' actor for this account under /api/v1/actors.",
    )

    with pytest.raises(MCPToolError) as exc:
        workitem_comment(11, "note")

    message = exc.value.message
    assert exc.value.status_code == 403
    assert "actor" in message.lower()
    assert "w4a_" in message
    assert "Backend said:" in message


def test_workitem_comment_propagates_400_missing_body(api):
    api.side_effect = _backend_error(400, "Comment body is required")

    with pytest.raises(MCPToolError) as exc:
        workitem_comment(11, "note")

    assert exc.value.status_code == 400


# ─── Registration and docstrings (AC: tools are visible in the tools list) ───

def test_work_item_tools_are_registered_in_tools_list():
    server = create_mcp_server()
    names = {tool.name for tool in asyncio.run(server.list_tools())}

    for name in NEW_TOOLS:
        assert name in names, f"{name} is missing from the MCP tools list"


def test_work_item_tools_have_docstrings():
    """FastMCP uses the docstring as the tool description an agent reads."""
    for name in NEW_TOOLS:
        function = getattr(mcp_server, name)
        docstring = inspect.getdoc(function)
        assert docstring and docstring.strip(), f"{name} has no docstring"


@pytest.mark.parametrize("tool_name", NEW_TOOLS)
def test_every_work_item_tool_documents_its_error_codes(tool_name):
    """An agent must be able to read the meaning of 400/403/404/409 out of the tool itself."""
    docstring = inspect.getdoc(getattr(mcp_server, tool_name)) or ""
    documented = [code for code in ("400", "403", "404", "409") if code in docstring]
    assert documented, f"{tool_name} documents none of 400/403/404/409"


# ─── Error codes in the description an agent ACTUALLY receives (AC of 160) ───
#
# Why this test exists and why it does not read the docstring.
#
# FastMCP does not publish the docstring. It publishes the free-form text that
# comes BEFORE the first structured section (Args:, Returns:, Raises:, Note:, and
# so on) — everything after that section is parsed away and never reaches
# tools/list. A tool could therefore have a perfect "Error codes:" block in its
# docstring, pass a test that greps the docstring, and still show an agent a
# description with no error code in it at all. That is exactly what happened to
# these tools once, and it is the difference between documentation a human reads
# in a source file and documentation an agent reads before deciding what to call.
#
# So every assertion below reads `tool.description` from list_tools(). The marker
# "Error codes this tool can return" is required too: it is the prose lead-in that
# FastMCP preserves, so demanding it is what stops a later edit from moving the
# codes back into a section that gets dropped.

COORDINATION_TOOLS = [
    "actor_whoami",
    "workitem_list",
    "workitem_get",
    "workitem_create",
    "workitem_claim",
    "workitem_update",
    "workitem_comment",
    "inbox_poll",
    "inbox_mark_seen",
    "resource_list",
    "resource_get",
    "reservation_list",
    "reservation_create",
    "reservation_release",
]

ERROR_CODE_TOKENS = ("400", "401", "403", "404", "409", "422")

ERROR_CODES_MARKER = "Error codes this tool can return"


def _published_descriptions() -> dict:
    """name -> the description FastMCP publishes in tools/list."""
    server = create_mcp_server()
    return {tool.name: (tool.description or "") for tool in asyncio.run(server.list_tools())}


def test_fastmcp_drops_everything_after_the_first_docstring_section():
    """The trap this file must not fall into again, written down as an assertion.

    workitem_get has a Returns: section and an Error codes block after its Args:.
    The published description contains neither — it stops at Args:. Anyone who
    wants an agent to read something must put it in the prose before the first
    section, which is what the tests below check for all fourteen tools.
    """
    description = _published_descriptions()["workitem_get"]

    assert "Read one work item" in description
    assert "Args:" not in description
    assert "Returns:" not in description
    assert "WorkItemDTO" not in description


@pytest.mark.parametrize("tool_name", COORDINATION_TOOLS)
def test_every_coordination_tool_publishes_error_codes_in_its_description(tool_name):
    """AC 160: the description in tools/list names the error codes of the tool."""
    description = _published_descriptions()[tool_name]

    assert description.strip(), f"{tool_name} publishes an empty description"
    codes = [code for code in ERROR_CODE_TOKENS if code in description]
    assert codes, (
        f"{tool_name} publishes a description with no error code in it. The agent sees only "
        f"this text before choosing what to call, so codes written after Args:/Returns: are "
        f"not documentation an agent can use."
    )
    assert ERROR_CODES_MARKER in description, (
        f"{tool_name}: the error codes are not in the prose block FastMCP preserves. Keep "
        f"them under '{ERROR_CODES_MARKER}:' before Args:/Returns:."
    )


def test_all_fourteen_coordination_tools_have_codes_in_their_descriptions():
    """Counted, because 'most of them' is not the AC."""
    descriptions = _published_descriptions()
    missing = [
        name for name in COORDINATION_TOOLS
        if not any(code in descriptions.get(name, "") for code in ERROR_CODE_TOKENS)
    ]

    assert not missing, f"{len(COORDINATION_TOOLS) - len(missing)}/{len(COORDINATION_TOOLS)} " \
                        f"descriptions carry a code; missing: {missing}"


def test_workitem_comment_description_says_422_is_a_broken_contract_not_bad_json():
    """The single most important sentence in this story: without it the agent guesses."""
    description = _published_descriptions()["workitem_comment"]
    lowered = description.lower()

    assert "422" in description
    assert "not a json syntax error" in lowered, "422 must be explained, not just listed"
    assert "understood" in lowered, "the description must say the request WAS understood"
    assert "to_handle" in description, "the handoff contract must be named in the description"


def test_reservation_create_description_names_403_and_the_actor_fix():
    description = _published_descriptions()["reservation_create"]

    assert "403" in description
    assert "actor" in description.lower()
    assert "w4a_" in description, "the description must name the credential that works"


def test_reservation_create_description_keeps_the_two_409s_apart():
    """'disabled' and 'insufficient capacity' are different problems; the agent must see both."""
    description = _published_descriptions()["reservation_create"].lower()

    assert "409" in description
    assert "disabled" in description
    assert "insufficient capacity" in description


def test_reservation_release_description_says_404_and_idempotent():
    description = _published_descriptions()["reservation_release"]
    lowered = description.lower()

    assert "404" in description
    assert "idempotent" in lowered
    assert "active_slots" in description, "the description must say the counter drops once"


def test_workitem_create_description_says_the_item_starts_in_backlog():
    """Without this an agent tries to claim a freshly created item and reads 409 as a bug."""
    description = _published_descriptions()["workitem_create"].lower()

    assert "backlog" in description
    assert "two calls" in description or "workitem_claim" in description


def test_workitem_update_description_refuses_claimed_in_the_mcp_layer():
    description = _published_descriptions()["workitem_update"]

    assert "claimed" in description
    assert "MCPToolError" in description
    assert "workitem_claim" in description, "it must name the call that is allowed to claim"


def test_workitem_claim_description_is_the_only_route_to_claimed():
    description = _published_descriptions()["workitem_claim"].lower()

    assert "409" in description
    assert "ready" in description
    assert "claim" in description


def test_inbox_poll_description_states_the_limit_range_and_400():
    description = _published_descriptions()["inbox_poll"]

    assert "400" in description
    assert "1..50" in description
    assert "first page" in description.lower(), "an empty cursor must not look like an error"


def test_inbox_mark_seen_description_says_the_marker_is_clamped():
    """A client that thinks it controls the marker will wait for events that never come."""
    description = _published_descriptions()["inbox_mark_seen"].lower()

    assert "clamped" in description
    assert "404" in description


def test_actor_whoami_description_says_a_legacy_jwt_is_not_an_error():
    """Otherwise an agent with a web JWT concludes the tool is broken."""
    description = _published_descriptions()["actor_whoami"]
    lowered = description.lower()

    assert "401" in description
    assert "legacy" in lowered
    assert "actorid null" in lowered


@pytest.mark.parametrize("tool_name", COORDINATION_TOOLS)
def test_no_published_description_leaks_a_secret_or_a_live_host(tool_name):
    """Descriptions are shipped to every client: no token, no credential, no private IP."""
    description = _published_descriptions()[tool_name]

    assert "Bearer " not in description
    assert "eyJ" not in description, "a JWT fragment must never appear in a description"
    assert "192.168." not in description
    assert "http://127.0.0.1" not in description
