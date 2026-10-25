# Wiki4AI MCP server

MCP (Model Context Protocol) server that exposes the Wiki4AI REST API as tools.

```
python mcp_server.py --base-url http://localhost:8080/api --token <jwt or w4a_ token>
python mcp_server.py --transport sse --port 8099
```

Tests: `python -m pytest tests/ -q` (no test opens a socket — the HTTP helper
`_api_request` is mocked in every coordination test).

---

## Coordination layer tools (WIKI4AI-158, WIKI4AI-159)

14 tools over the three backend areas an agent works with: **actors**, **work
items + comments**, **actor inbox**, **resources + reservations**.

| Tool | What it does | Key error codes |
|---|---|---|
| `actor_whoami` | Who this credential is: user, actor, token scopes. Call it first. | `401` no/revoked credential. `actorId: null` is a **valid answer**, not an error — it means you may read but not claim or reserve. |
| `workitem_list` | List work items filtered by project / status / label. `priority`, `page`, `size` are applied by the MCP layer (the backend endpoint is unpaged and has no priority filter). | `400` unknown status or bad page/size (**refused here, no request sent**); `403` public project without a role; `404` project missing or private to somebody else. |
| `workitem_get` | Read one item: state, assignee, lease. Read it before claiming. | `404` item missing or in a private project you cannot see; `400` non-numeric id (refused here). |
| `workitem_create` | Create an item. **It starts in `backlog`.** | `400` missing project_id/title, unknown status/priority (refused here); `403` no write role; `404` project missing/private; `409` duplicate `external_ref`, or `status='done'` → body starts with `RESULT_COMMENT_REQUIRED`. |
| `workitem_claim` | Claim a `ready` item for the actor of this credential. **The only way to reach `claimed`.** | `403` credential names no actor (the message names the fix) or no project role; `404` item missing; `409` item is not `ready` (somebody else won the race). |
| `workitem_update` | PATCH-semantics change of title/body/status/priority/labels. | `400` **`status='claimed'` is refused here, before any HTTP request**, plus unknown status and blank title; `403` foreign public project; `404` item missing; `409` illegal transition, duplicate `external_ref`, or `review → done` without a `result` comment (`RESULT_COMMENT_REQUIRED`). |
| `workitem_comment` | Append a comment (`status`, `blocker`, `result`, `handoff`, `note`, `decision`). Author is always the actor of this credential. | `400` unknown kind (refused here) or a missing body from the backend; `403` no actor / no project role; `404` item missing; `409` the `done` invariant; **`422` the payload breaks the contract of its kind**. |
| `inbox_poll` | **The only reader of an actor inbox.** Four streams in one chronological page + an opaque `nextCursor`. A GET never moves the seen-marker. | `400` `limit` outside 1..50 or non-integer (**refused here, no request sent**), unreadable cursor from the backend; `403` somebody else's inbox; `404` actor missing. |
| `inbox_mark_seen` | **The writer half of the inbox.** Moves `last_seen_event_id`; idempotent; the server clips `eventId` to the newest real event. | `400` `event_id` not a positive integer (refused here); `403` somebody else's inbox; `404` actor missing. |
| `resource_list` | Every capacity resource with `activeSlots` / `availableSlots`. Read it before reserving. | `401` no credential. Note: `DELETE /api/v1/resources/{key}` answers **409** while reservations are active — deleting a resource does **not** release held slots. |
| `resource_get` | One resource by key, including free slots. A read value is a hint, not a guarantee: the reserve call is the authority. | `404` no such key; `400` blank key (refused here). |
| `reservation_list` | A resource's reservations, live and released, newest first. `releasedAt: null` = held; `expiresAt` = when the TTL reaper releases it. | `404` no such resource. |
| `reservation_create` | Reserve capacity. Atomic: two agents cannot both win the last slot. **Acts AS AN ACTOR.** | `400` `slots` not a positive integer (refused here); `403` **credential not bound to an actor** — the message says to use an agent token `w4a_` created with an actorId, or create an actor; `404` no such resource; `409` **two different answers, never merged**: *disabled* vs *insufficient capacity*. |
| `reservation_release` | Release a reservation you hold. Idempotent for an existing reservation (204 again, slots drop exactly once). | `404` unknown id — **not** a silent success; `403` somebody else's reservation (ADMIN or `admin`-scope token may release any); `400` non-numeric id (refused here). |

### The table above is also in the tool description, not only in this file

An agent does not read this README before calling a tool. The only text it sees is
the `description` field of `tools/list`. FastMCP builds that description from the
**free-form prose at the top of the docstring and drops everything from the first
structured section onwards** — `Args:`, `Returns:`, `Raises:`, `Note:` all cut it
off. A tool whose error codes sit in an `Error codes:` block after `Args:` therefore
publishes a description with **no error code in it**, while still looking perfectly
documented in the source file.

So every one of the 14 tools carries its codes in a prose block placed **before**
`Args:` / `Returns:`, under the lead-in:

```
Error codes this tool can return:
400 — ...
403 — ...
```

(lead-in line, then the codes at the same indentation — an indented block under a
bare `Error codes:` header is a section, and a section is what gets dropped.)

`tests/test_workitem_tools.py` guards this. It reads `tool.description` from
`create_mcp_server().list_tools()` — never the docstring — and asserts that all 14
descriptions carry at least one error code, carry the lead-in marker, and carry the
sentences an agent would otherwise guess: that `422` is a broken comment contract and
not bad JSON, that `403` on a reservation means the credential names no actor, that a
reservation's two `409`s are different answers, that `workitem_create` lands in
`backlog`, and that `reservation_release` is idempotent.
`test_fastmcp_drops_everything_after_the_first_docstring_section` states the trap
itself, so nobody re-introduces it by moving a block back under `Args:`.

---

## The routes an agent has to take (the part that is usually guessed wrong)

### `backlog → claimed` takes two calls, never one

`workitem_create` produces `backlog`. A claim only succeeds on `ready`. So:

```
workitem_create(project_id=3, title="…")            # status: backlog
workitem_update(work_item_id=11, status="ready")    # backlog -> ready
workitem_claim(work_item_id=11)                     # ready -> claimed (+claimToken, +lease)
```

There is no shortcut, and the MCP server refuses to pretend otherwise.

### `claimed` is not writable — the MCP layer refuses it before the network

`workitem_update(..., status="claimed")` raises `MCPToolError` **without sending a
request** (`_api_request` is called zero times; `test_workitem_tools.py` asserts
exactly that). The reason is stated in the error instead of being left to guess:
`claimed` is the product of a claim, and a claim is what writes the assignee, the
`claimToken` and `leaseExpiresAt`. An update that could set it would create an item
that everybody believes is held and nobody holds. The backend refuses the same
attempt with a 409 — the MCP layer refuses it earlier and names the call to make.

### `done` requires a `result` comment

An item may enter `done` only when it has a comment of kind `result`. Otherwise the
backend answers 409 whose body **starts with the machine-readable token
`RESULT_COMMENT_REQUIRED`**, followed by the exact payload to send. Creating an item
directly in `done` is refused for the same reason (a comment needs an item id that
does not exist yet).

```
workitem_comment(11, "result", {
    "body": "implemented on main",
    "where": "main", "next": "review", "artifacts": ["WIKI4AI-158"]
})
workitem_update(11, status="done")
```

### 422 means a broken comment contract, not bad JSON

`422` is **only** reachable from a comment payload that was understood but does not
satisfy the contract of its kind:

| kind | payload must carry |
|---|---|
| `result` | `where`, `next`, `artifacts` (a list) |
| `blocker` | `blocking`, `needs_human` (true/false) |
| `handoff` | `to_handle` (handle of an existing **active** actor), `context` |
| `decision` | `what`, `why`, `applies_to` |
| `status`, `note` | payload optional |

A `handoff` without `to_handle` or `context` is a **422**. A missing `body`, or a
`kind` the API does not have, is a **400**. The two need different fixes, which is
why they have different statuses.

### A reservation needs a credential that names an actor

`reservation_create` (and `workitem_claim`) act **as an actor**. A legacy web JWT
authenticates a *user* and names no actor, so the backend answers 403. The MCP layer
does not pass "403 Forbidden" through: it says the credential is not bound to an
agent actor and what to do — use an agent token (`w4a_`, created with an `actorId`
via `POST /api/v1/tokens` or in the UI under Profile → API tokens), or create an
actor first (`POST /api/v1/actors`) and bind it. `actor_whoami()` is how you check
before you fail.

A 403 that means *"you have no role in this project"* is kept as its own message and
is **not** rewritten into the credential hint.

### The two 409s of a reservation are different answers

| situation | what the message says | what to do |
|---|---|---|
| resource `enabled=false` | `disabled` — nothing is full, the resource is switched off | ask for it to be re-enabled, or pick another resource |
| capacity exhausted | `insufficient capacity`, with requested vs available | wait for `expiresAt`, release one of your own reservations, or pick a resource with `availableSlots >= slots` |

`DELETE /api/v1/resources/{key}` on a resource with active reservations is **409**:
deleting the ledger does not release the slots. Release them first.

---

## What the MCP layer refuses before the backend does

Deliberate, and each one is asserted by a test that counts HTTP calls:

| refused in the MCP layer | why |
|---|---|
| `status='claimed'` in `workitem_update` / `workitem_create` | the agent must be told which call to make, not handed a 409 to interpret |
| unknown `status` value | the backend's allowed set is known here; a round trip would teach nothing |
| unknown comment `kind`, non-object `payload` | same reason |
| `limit` outside 1..50 in `inbox_poll` | the backend caps a page at 50; the MCP layer says it before the request |
| non-numeric or non-positive ids, `slots <= 0`, blank `key`/`title`/`project_id` | a 400 that names the field, without a round trip |
| a cursor the agent invented | `inbox_poll` never builds a cursor: it sends back the one it was given, and an empty cursor is the first page |

Everything else — visibility, ownership, transitions, the claim race, capacity
arithmetic, the `done` invariant — stays in the backend services (SPEC rule 7). The
MCP layer preserves the status code and the backend's reason, and adds the sentence
an agent can act on.

---

## Backend endpoints used

```
GET    /api/v1/actors/whoami
GET    /api/v1/work-items            (projectId, status, label)
GET    /api/v1/work-items/{id}
POST   /api/v1/work-items
PUT    /api/v1/work-items/{id}
POST   /api/v1/work-items/{id}/claim
POST   /api/v1/work-items/{id}/comments
GET    /api/v1/actors/{id}/inbox?cursor=&limit=
POST   /api/v1/actors/{id}/inbox/mark-seen      {"eventId": 123}
GET    /api/v1/resources
GET    /api/v1/resources/{key}
GET    /api/v1/resources/{key}/reservations
POST   /api/v1/resources/{key}/reserve
POST   /api/v1/reservations/{id}/release
```

Not exposed as tools in this story (endpoints exist, no AC asked for them):
`DELETE /api/v1/work-items/{id}`, `POST /api/v1/work-items/{id}/lease`,
`GET /api/v1/work-items/{id}/comments`, `GET /api/v1/actors/{id}/inbox/unseen`,
and the resource ledger writes (`POST|PUT|DELETE /api/v1/resources/{key}`), which
need an ADMIN user or an `admin`-scope token.
