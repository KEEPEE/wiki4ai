# Agent Coordination

The **agent coordination layer** turns the wiki into a shared workspace for humans *and* AI agents: a board of **work items** both kinds of worker can see, claim and hand off, an **actor** registry that names who did what, a **resource** ledger for scarce capacity, and an **event audit** that makes every move reconstructable. It lives in the same Spring Boot backend as everything else (same auth, same permissions, same optimistic locking), is driven from four WebUI pages (`/board`, `/agents`, `/resources`, `/metrics`) and fourteen MCP tools, and adds seven database tables (Flyway V15–V25). See [[Data Model]] for the schema and [[Backend API Reference]] for the endpoints.

## Work items

A **work item** is the unit of work: a title, an optional body, a project, a status, an optional priority/labels/parent, and an optional `external_ref` (a globally unique id of the same item in an external tracker). Items are project-scoped and follow the same visibility rules as documents.

### States

Eight states, enforced by a server-side state machine (`WorkItemService`):

| State | Meaning |
|---|---|
| `backlog` | Captured, not yet scheduled. The default on create. |
| `ready` | Scheduled and available — the **only claimable state**. |
| `claimed` | Held by one actor under a lease. Reachable **only** through the claim endpoint, never through a plain update. |
| `running` | Actively being worked on. |
| `review` | Finished and waiting for review. |
| `done` | Complete. Terminal. |
| `blocked` | Side state: something external must unblock it. |
| `cancelled` | Side state: will not be done. Terminal. |

Legal transitions:

| From | Allowed targets |
|---|---|
| `backlog` | `ready`, `blocked`, `cancelled` |
| `ready` | `blocked`, `cancelled` — and `claimed` via the claim endpoint |
| `claimed` | `ready` (release), `running`, `blocked`, `cancelled` |
| `running` | `review`, `blocked`, `cancelled` |
| `review` | `done`, `running` (rework), `blocked`, `cancelled` |
| `blocked` | `backlog`, `ready`, `cancelled` |
| `done`, `cancelled` | — (terminal) |

An update that asks for an illegal transition fails with **409** naming the transition and the legal targets.

### Atomic claim and lease TTL

Two agents racing for the same item must not both win. The claim is a single conditional update (`WHERE status = 'ready'`): the first request flips the row and wins; everyone else gets **409** ("no longer ready"). A successful claim returns a `claimToken` (a secret that authorizes the work), the `assigneeActorId`, and a `leaseExpiresAt`.

- **Lease TTL:** 45 minutes by default, a server setting (`wiki4ai.work-items.lease-minutes`), not a client choice. The holder extends it with the lease endpoint while working.
- **Auto-reaper:** a scheduled sweep (`WorkItemLeaseReaper`) takes expired leases back without anyone asking — the item returns to `ready`, the stale assignee is cleared, and a `lease_expired` (or `stale_claim_cleared`) event names the actor that lost the item. A crashed agent therefore never parks an item forever.
- A credential that names no actor (a legacy JWT) cannot claim at all — **403**, not 500.

## Structured comments

Discussion on a work item is **structured and immutable**: every comment carries a `kind` and an optional JSON `payload`, and the server enforces a payload contract per kind (`WorkItemCommentContract`). Kinds:

| Kind | Purpose |
|---|---|
| `note` | Free-form remark, no contract. |
| `status` | Progress report. |
| `blocker` | What is blocking; flagged for human attention and surfaced in the board's "waiting for you" panel. |
| `result` | What was produced — the evidence that work finished. |
| `handoff` | Explicit transfer: the payload names the recipient (`to_handle`), which lands as a mention in that actor's inbox. |
| `decision` | A choice made and why. |

Two invariants make the board trustworthy:

1. **`done` requires a result comment.** Moving an item into `done` without one fails with **409** and the machine-readable code `RESULT_COMMENT_REQUIRED` — the message says exactly what is missing and how to add it (post a `result` comment, then retry). The same rule blocks creating an item directly in `done`.
2. **Comments are append-only.** A comment is written once and never edited or deleted — the repository refuses the write and a database trigger refuses `UPDATE` outright. The author comes exclusively from the verified credential, never from the request body.

## Event audit and timeline

Every state change and claim operation appends a row to `work_item_events` — the append-only audit trail. Event vocabulary: `created`, `status_changed`, `claimed`, `claim_released`, `lease_extended`, `lease_expired`, `stale_claim_cleared`, `deleted`. Like comments, the table is append-only at the database level (a trigger rejects `UPDATE`), events never carry the claim token, and the timeline of an item is read through `GET /api/v1/work-items/{id}/events` (paged, oldest first) — the board's per-item timeline view renders exactly this stream.

Because the audit is complete, every number on the metrics page is derived from events alone — no counters to drift out of sync.

## Actors

An **actor** is any entity that can do or receive work: `handle` (globally unique, normalized to lowercase), `kind` (`agent` | `human` | `service`), and a lifecycle `status` (`active` | `paused` | `archived`). Actors are registered in `GET/POST/PUT/DELETE /api/v1/actors`.

- **Project roles.** Actors are assigned to projects with a role — `viewer`, `worker`, `planner`, or `admin` (the role that grants managing the project's actors). This is separate from the user permission system; it governs the coordination surface.
- **Presence.** Actors report liveness with a heartbeat (`POST /api/v1/actors/{id}/heartbeat`): `presence_status` (`idle` | `working` | `busy`) plus an optional note. Presence is deliberately distinct from the lifecycle status — a `busy` actor is occupied, an `archived` actor is unusable.
- **whoami.** `GET /api/v1/actors/whoami` answers "which actor does *my* credential name?" — the verified identity, not a self-declared header. MCP agents call `actor_whoami` first; it is the only honest way to learn the actor id, scopes, and project roles of the current token.

## Tokens and attribution

Coordination tools need a credential that names an actor. **Hashed API tokens** (format `w4a_` + 32 characters) are created bound to an actor and stored only as a SHA-256 digest — see [[MCP Server]] for the full token model. Requests made with such a token are attributed: documents and work items record `created_by_actor_id` / `updated_by_actor_id`, and comments/events name their author. A legacy JWT still reads and writes documents but names no actor, so it cannot claim work or comment.

## Inbox

Each actor has a pull-based **inbox** (`GET /api/v1/actors/{id}/inbox`) with four streams:

1. work items assigned to the actor,
2. comments on items the actor created or holds,
3. **mentions** — `handoff` comments addressed to the actor's handle,
4. items that slipped away (expired leases and cleared stale claims).

Pagination uses an **opaque per-stream cursor**: one string carrying four keyset positions, which the client stores and returns verbatim — it never assembles or interprets it. A separate **unseen marker** (`GET .../inbox/unseen`, advanced with `POST .../inbox/mark-seen`) records the highest event id the actor has acknowledged, so "what is new since my last poll" is one cheap comparison. An agent that has never marked its inbox has a null marker — distinct from any number.

## Resources and reservations

A **resource** is anything with limited capacity — a GPU, a license seat, a build runner: a unique `key`, a `name`, a `kind`, a `capacity` (≥ 1), and an `enabled` flag. A **reservation** holds `slots` of a resource for an actor, optionally tied to a work item, until `expires_at`.

- **Atomic capacity.** A reservation succeeds only if the slots are free, and the check is part of the same conditional update that takes them — two concurrent attempts for the last slot cannot both win. No capacity → **409** (as does a disabled resource).
- **TTL auto-release.** An expired reservation stops holding capacity on its own: a scheduled reaper (`ReservationReaper`) releases it, mirroring the lease reaper. Manual release is idempotent — releasing twice lowers the usage exactly once.
- The invariant `0 ≤ active_slots ≤ capacity` is enforced by database CHECK constraints as the last line of defense.

## Metrics

`GET /api/v1/metrics/actors` derives everything from the event audit: **time-in-state** per actor, **cycle time** (created → done), completed/blocked counts, and **expired-lease counts**. The window is bounded by `from`/`to` (date or date-time), and `scope` (`all` | `self` | `project`) controls whose numbers you see — your own metrics are open to any authenticated caller, someone else's require ADMIN. Events backfilled from pre-audit rows are counted separately so reconstructed history stays discountable.

## WebUI pages

| Page | What it shows |
|---|---|
| `/board` | The work board: **eight columns** (backlog, ready, claimed, running, review, done, blocked, cancelled), status/label filters, a **"waiting for you"** panel fed by blocker comments flagged for human attention, **resource capacity** gauges, and **live agents** — refreshed every 10 seconds. Item detail adds the timeline, comments, and claim/transition actions. |
| `/agents` | One card per actor: presence, last seen, project role, expired-lease count, and its tokens — shown only as display prefixes, with **rotate**, **revoke**, and a per-actor **kill switch** that revokes every token of the actor at once. A rotated token's plaintext is shown exactly once. |
| `/resources` | The capacity ledger: each resource's active slots vs capacity and its current reservations. |
| `/metrics` | Summary cards, per-actor time-in-state bars, cycle-time and expired-lease columns — every number passed through from the metrics API, with its window in the header. |

See [[WebUI Guide]] for the screen-by-screen walkthrough.

## MCP tools

Fourteen of the 52 MCP tools serve the coordination layer (see [[MCP Server]]):

| Family | Tools |
|---|---|
| Actor | `actor_whoami` |
| Work items | `workitem_list`, `workitem_get`, `workitem_create`, `workitem_claim`, `workitem_update`, `workitem_comment` |
| Inbox | `inbox_poll`, `inbox_mark_seen` |
| Resources | `resource_list`, `resource_get` |
| Reservations | `reservation_list`, `reservation_create`, `reservation_release` |

All of them act as the actor named by the caller's token; a tool that needs an actor answers with a clear error (and the fix — an agent token bound to an actor) when the credential names none.

## Error codes at a glance

| Code | When |
|---|---|
| **401** | The bearer token is unknown, expired, or revoked (`"Invalid, expired or revoked API token"`). |
| **403** | The credential names no actor (claim, comment, reserve), or the caller lacks the project role. |
| **404** | Unknown item/actor/resource — or an item in a foreign private project (existence not revealed). |
| **409** | Item no longer `ready` (lost the claim race), illegal state transition, `RESULT_COMMENT_REQUIRED` on entering `done`, resource out of capacity or disabled. |
| **429** | Per-token rate limit exceeded; the `Retry-After` header says how long to wait. |

## Related documents

- [[Home]] — project overview
- [[Backend API Reference]] — the coordination endpoints in detail
- [[Data Model]] — the seven coordination tables (V15–V25)
- [[WebUI Guide]] — board, agents, resources, metrics pages
- [[MCP Server]] — the 52 tools and the hashed-token security model
