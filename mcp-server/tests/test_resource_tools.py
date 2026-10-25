"""
Tests for the Wiki4AI MCP resource and reservation tools (WIKI4AI-159).

What these tests are for
------------------------
Three behaviours of this story are only useful to an agent if the MCP layer keeps
them distinguishable, and each one is asserted on the error TEXT, not just on the
fact that something failed:

1. reservation_create acts AS AN ACTOR. A legacy web JWT (no actorId) is answered
   403 by the backend. The MCP layer rewrites that into an instruction that names
   the fix — an agent token 'w4a_' created with an actorId, or creating an actor —
   because "403 Forbidden" tells an agent nothing it can act on.
2. There are TWO different 409s and they are never merged: a disabled resource
   (nothing is full, the resource is switched off) and insufficient capacity (the
   resource is on but has no room). Each message keeps its own word.
3. reservation_release is idempotent for a reservation that exists (204 again) and
   404 for an id that does not. A typo'd id is not a silent success.

slots outside the positive range is refused by the MCP layer before any request is
sent (_api_request called zero times on the mock).

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
    reservation_create,
    reservation_list,
    reservation_release,
    resource_get,
    resource_list,
    set_base_url,
    set_jwt_token,
)


NEW_TOOLS = [
    "resource_list",
    "resource_get",
    "reservation_list",
    "reservation_create",
    "reservation_release",
]


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


SAMPLE_RESOURCE = {
    "id": 2, "key": "runner-gpu", "name": "GPU runner", "kind": "machine",
    "capacity": 2, "activeSlots": 1, "enabled": True, "metadata": None,
    "availableSlots": 1, "createdAt": "2026-10-10T10:00:00",
}

SAMPLE_RESERVATION = {
    "id": 8, "resourceId": 2, "resourceKey": "runner-gpu", "actorId": 7,
    "workItemId": 11, "slots": 1, "reason": "build", "expiresAt": "2026-10-10T11:00:00",
    "releasedAt": None, "createdAt": "2026-10-10T10:30:00",
}

ACTOR_LESS_403 = _backend_error(
    403, "You can only reserve capacity on capacity as an actor; your credential does not name one"
)


# ─── resource_list / resource_get / reservation_list (the read side) ─────────

def test_resource_list_calls_resources_endpoint(api):
    api.return_value = [SAMPLE_RESOURCE]

    result = resource_list()

    api.assert_called_once_with("GET", "/v1/resources")
    assert result[0]["key"] == "runner-gpu"
    assert result[0]["availableSlots"] == 1


def test_resource_get_calls_key_endpoint(api):
    api.return_value = SAMPLE_RESOURCE

    result = resource_get("runner-gpu")

    api.assert_called_once_with("GET", "/v1/resources/runner-gpu")
    assert result["capacity"] == 2


def test_resource_get_encodes_the_key(api):
    """A key can never forge a path segment."""
    api.return_value = SAMPLE_RESOURCE

    resource_get("a/b")

    assert api.call_args.args[1] == "/v1/resources/a%2Fb"


def test_resource_get_rejects_blank_key_before_any_request(api):
    with pytest.raises(MCPToolError) as exc:
        resource_get("   ")

    assert "key" in exc.value.message
    assert api.call_count == 0


def test_resource_get_propagates_404(api):
    api.side_effect = _backend_error(404, "Resource not found with key: nope")

    with pytest.raises(MCPToolError) as exc:
        resource_get("nope")

    assert exc.value.status_code == 404
    assert "nope" in exc.value.message


def test_reservation_list_calls_reservations_endpoint(api):
    api.return_value = [SAMPLE_RESERVATION]

    result = reservation_list("runner-gpu")

    api.assert_called_once_with("GET", "/v1/resources/runner-gpu/reservations")
    # releasedAt null means the slot is held — the tool must not drop the field.
    assert result[0]["releasedAt"] is None
    assert result[0]["expiresAt"] == "2026-10-10T11:00:00"


def test_reservation_list_propagates_404_unknown_resource(api):
    api.side_effect = _backend_error(404, "Resource not found with key: nope")

    with pytest.raises(MCPToolError) as exc:
        reservation_list("nope")

    assert exc.value.status_code == 404


# ─── reservation_create ──────────────────────────────────────────────────────

def test_reservation_create_sends_slots_as_a_string(api):
    """The backend DTO carries slots as String on purpose; the tool must match it."""
    api.return_value = SAMPLE_RESERVATION

    result = reservation_create("runner-gpu", slots=1, reason="build", work_item_id=11)

    method, path, body = api.call_args.args
    assert method == "POST"
    assert path == "/v1/resources/runner-gpu/reserve"
    assert body["slots"] == "1"
    assert body["reason"] == "build"
    assert body["workItemId"] == 11
    assert result["id"] == 8


def test_reservation_create_defaults_to_one_slot(api):
    api.return_value = SAMPLE_RESERVATION

    reservation_create("runner-gpu")

    body = api.call_args.args[2]
    assert body["slots"] == "1"
    assert "reason" not in body
    assert "workItemId" not in body


@pytest.mark.parametrize("bad_slots", [0, -3, "many", None])
def test_reservation_create_rejects_bad_slots_with_zero_http_calls(api, bad_slots):
    with pytest.raises(MCPToolError) as exc:
        reservation_create("runner-gpu", slots=bad_slots)

    assert "slots" in exc.value.message
    assert api.call_count == 0


def test_reservation_create_rejects_blank_key_with_zero_http_calls(api):
    with pytest.raises(MCPToolError) as exc:
        reservation_create("", slots=1)

    assert "key" in exc.value.message
    assert api.call_count == 0


def test_reservation_create_without_actor_explains_the_actor_problem(api):
    """Not '403 Forbidden': the text names the credential problem and the fix."""
    api.side_effect = ACTOR_LESS_403

    with pytest.raises(MCPToolError) as exc:
        reservation_create("runner-gpu")

    message = exc.value.message
    assert exc.value.status_code == 403
    assert "actor" in message.lower()
    assert "w4a_" in message                      # the agent token the agent must use
    assert "create" in message.lower()            # ... or create an actor
    assert "Backend said:" in message             # the original reason is kept, not hidden


def test_reservation_create_archived_actor_also_gets_the_actor_hint(api):
    api.side_effect = _backend_error(
        403,
        "You can only reserve capacity on capacity as an actor; actor 'ci-runner' is archived",
    )

    with pytest.raises(MCPToolError) as exc:
        reservation_create("runner-gpu")

    assert "w4a_" in exc.value.message
    assert "archived" in exc.value.message


def test_reservation_create_keeps_a_work_item_role_403_as_a_different_answer(api):
    """A 403 about project roles must not be rewritten into the credential message."""
    api.side_effect = _backend_error(
        403,
        "You can only reserve capacity for a work item of a project you own, a project you "
        "administer, or a project where your actor has at least the 'worker' role",
    )

    with pytest.raises(MCPToolError) as exc:
        reservation_create("runner-gpu", work_item_id=11)

    assert exc.value.status_code == 403
    assert "worker" in exc.value.message
    assert "w4a_" not in exc.value.message


def test_reservation_create_disabled_resource_says_disabled(api):
    api.side_effect = _backend_error(409, "Resource 'runner-gpu' is disabled")

    with pytest.raises(MCPToolError) as exc:
        reservation_create("runner-gpu")

    message = exc.value.message
    assert exc.value.status_code == 409
    assert "disabled" in message
    # The two 409s are different answers: a disabled resource is not a full one.
    assert "insufficient capacity" not in message.lower()


def test_reservation_create_capacity_conflict_says_capacity(api):
    api.side_effect = _backend_error(
        409, "Insufficient capacity on resource 'runner-gpu': requested 2, available 1"
    )

    with pytest.raises(MCPToolError) as exc:
        reservation_create("runner-gpu", slots=2)

    message = exc.value.message
    assert exc.value.status_code == 409
    assert "capacity" in message.lower()
    assert "requested 2, available 1" in message
    # ... and it is not reported as a disabled resource.
    assert "is disabled" not in message


def test_reservation_create_two_409s_produce_two_different_messages(api):
    """Same status code, never the same sentence."""
    api.side_effect = _backend_error(409, "Resource 'runner-gpu' is disabled")
    with pytest.raises(MCPToolError) as disabled:
        reservation_create("runner-gpu")

    api.side_effect = _backend_error(
        409, "Insufficient capacity on resource 'runner-gpu': requested 1, available 0"
    )
    with pytest.raises(MCPToolError) as full:
        reservation_create("runner-gpu")

    assert "disabled" in disabled.value.message
    assert "capacity" in full.value.message.lower()
    assert disabled.value.message != full.value.message


def test_reservation_create_propagates_404_unknown_resource(api):
    api.side_effect = _backend_error(404, "Resource not found with key: nope")

    with pytest.raises(MCPToolError) as exc:
        reservation_create("nope")

    assert exc.value.status_code == 404
    assert "resource_list" in exc.value.message


# ─── reservation_release ─────────────────────────────────────────────────────

def test_reservation_release_returns_empty_body_on_204(api):
    api.return_value = {}

    result = reservation_release(8)

    api.assert_called_once_with("POST", "/v1/reservations/8/release")
    assert result == {}


def test_reservation_release_is_idempotent(api):
    """Already released: 204 again, no error, active_slots dropped exactly once (backend)."""
    api.return_value = {}

    assert reservation_release(8) == {}
    assert reservation_release(8) == {}
    assert api.call_count == 2


def test_reservation_release_unknown_id_is_a_404_path(api):
    api.side_effect = _backend_error(404, "Reservation not found with id: 999")

    with pytest.raises(MCPToolError) as exc:
        reservation_release(999)

    message = exc.value.message
    assert exc.value.status_code == 404
    assert "999" in message
    # The distinction an agent needs: idempotent != always successful.
    assert "idempotent" in message
    assert "reservation_list" in message


def test_reservation_release_403_foreign_reservation(api):
    api.side_effect = _backend_error(
        403, "You can only release your own reservations (an ADMIN user or an admin-scope "
            "token may release any)"
    )

    with pytest.raises(MCPToolError) as exc:
        reservation_release(8)

    assert exc.value.status_code == 403
    assert "own reservations" in exc.value.message


def test_reservation_release_rejects_non_numeric_id_with_zero_http_calls(api):
    with pytest.raises(MCPToolError) as exc:
        reservation_release("eight")

    assert "reservation_id" in exc.value.message
    assert api.call_count == 0


# ─── Registration and docstrings ─────────────────────────────────────────────

def test_resource_tools_are_registered_in_tools_list():
    server = create_mcp_server()
    names = {tool.name for tool in asyncio.run(server.list_tools())}

    for name in NEW_TOOLS:
        assert name in names, f"{name} is missing from the MCP tools list"


def test_resource_tools_have_docstrings():
    for name in NEW_TOOLS:
        docstring = inspect.getdoc(getattr(mcp_server, name))
        assert docstring and docstring.strip(), f"{name} has no docstring"


@pytest.mark.parametrize("tool_name", NEW_TOOLS)
def test_every_resource_tool_documents_its_error_codes(tool_name):
    docstring = inspect.getdoc(getattr(mcp_server, tool_name)) or ""
    documented = [code for code in ("400", "403", "404", "409") if code in docstring]
    assert documented, f"{tool_name} documents none of 400/403/404/409"


def test_reservation_create_docstring_separates_the_two_409s():
    docstring = inspect.getdoc(mcp_server.reservation_create) or ""
    assert "disabled" in docstring
    assert "capacity" in docstring


def test_reservation_release_docstring_states_idempotency_and_404():
    docstring = inspect.getdoc(mcp_server.reservation_release) or ""
    assert "Idempotent" in docstring
    assert "404" in docstring


def test_resource_list_docstring_warns_that_delete_does_not_release_slots():
    """An agent must not expect DELETE /resources/{key} to free a held slot."""
    docstring = inspect.getdoc(mcp_server.resource_list) or ""
    assert "409" in docstring
    assert "release" in docstring.lower()
