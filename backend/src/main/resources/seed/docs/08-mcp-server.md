# MCP Server

The Wiki4AI MCP server is a [Model Context Protocol](https://modelcontextprotocol.io) endpoint that exposes the entire Wiki4AI platform to AI agents. It runs as a standalone Python service (FastMCP), translates every tool call into ordinary REST calls against the backend API **as an authenticated user**, and returns structured results — so an agent can create projects, write documents, search semantically, manage wiki links, upload images and work with the per-user vault using nothing but MCP tool calls.

## What is the MCP server

- **Purpose:** let any MCP-capable AI agent (DeepSeek Harness, Claude Desktop, OpenCode, custom agents) read from and write to a Wiki4AI instance without speaking HTTP/JSON directly.
- **Implementation:** single-file Python service (`mcp_server.py`, FastMCP ≥ 2.12 on Python 3.12), no external HTTP dependencies beyond the standard library — it calls the backend REST API with `urllib`.
- **Tool surface:** 52 tools covering projects, documents, search, wiki links, images, calendar events, the vault, and the agent coordination layer (work items, inbox, resources, reservations, actor identity — full reference below). The server also exposes two MCP *resources* for clients that support them:
  - `wiki://{project_slug}/{doc_slug}` — raw Markdown content of a document
  - `wiki://{project_slug}/{doc_slug}/html` — rendered HTML version
- **Identity model:** the server holds no user account of its own. Every backend call is made with *your* Wiki4AI credential — a user JWT or a hashed `w4a_` API token (see [Security model](#security-model) and [Hashed API tokens](#hashed-api-tokens)) — so agents inherit exactly the permissions of the user (and, for coordination tools, the actor) they act as.

### Transports

| Transport | How | When to use |
|---|---|---|
| **SSE** (server-side default for deployments) | `python mcp_server.py --transport sse --port 8095` — HTTP Server-Sent Events endpoint, typically exposed as `:8099/sse` through the stack's port mapping or reverse proxy | Remote agents / other hosts; clients that speak SSE natively |
| **stdio** (client-side default) | `python mcp_server.py` — JSON-RPC over stdin/stdout | Local agents on the same machine as the backend |
| **stdio ↔ SSE bridge** | `npx -y mcp-remote <sse-url> --header ... --allow-http` — an stdio client spawns `mcp-remote`, which connects to the SSE endpoint (it tries streamable HTTP first, then falls back to SSE) | **Any MCP client without a native SSE transport** (e.g. DeepSeek Harness's MCP client) must bridge this way |

The reference Docker image (`python:3.12-slim` + `fastmcp`) starts with `--transport=sse --port=8095`, exposes container port 8095 and health-checks `http://localhost:8095/health`; the deploy compose file maps it as a service (`mcp-server`) with environment variables `MCP_BASE_URL` (backend API base) and `MCP_JWT_TOKEN` (endpoint access credential — see below).

## Architecture

The MCP server is a thin, stateless adapter: it never touches the database directly. It forwards each tool call to the backend REST API using the identity JWT provided by the client (or configured server-side), and the backend enforces all authentication, authorization and optimistic locking as usual.

```mermaid
flowchart LR
    agent1["AI agent (stdio-only client)"] --> bridge["mcp-remote bridge (npx, stdio JSON-RPC)"]
    agent2["AI agent (SSE-capable client)"]
    bridge -->|"HTTP/SSE + Bearer access token + X-Wiki4AI-JWT"| mcp["Wiki4AI MCP server (Python / FastMCP, SSE endpoint)"]
    agent2 -->|"HTTP/SSE + Bearer access token + X-Wiki4AI-JWT"| mcp
    mcp -->|"REST calls with the user's identity JWT"| backend["Wiki4AI backend (Spring Boot REST API)"]
    backend --> db[("PostgreSQL + pgvector")]
```

Request flow for one tool call:

1. The agent client sends a `tools/call` over stdio or SSE (after the MCP `initialize` handshake, which reports `serverInfo.name = "wiki4ai"`; the reported version is the FastMCP library version of the running service).
2. On a **gated** instance the HTTP layer first verifies the endpoint access credential (`Authorization: Bearer <token>` against `MCP_JWT_TOKEN`).
3. The server extracts the *identity* JWT for this request (header priority below) and stores it in a per-request context variable.
4. The tool handler translates the arguments into one or more REST calls to `MCP_BASE_URL` with that identity JWT in the `Authorization` header.
5. The backend responds exactly as it would for a WebUI user — including 401/403 permission errors and 409 optimistic-locking conflicts, which the MCP layer rewrites into actionable tool errors.

## Security model

The server deliberately separates **two independent credentials** (source: `mcp_server.py`, middleware + `select_jwt_token()`):

### 1. Access credential — "who may reach this MCP endpoint"

- Configured via the environment variable **`MCP_JWT_TOKEN`**.
- **Gated instance (value set):** every HTTP request to the SSE endpoint must carry `Authorization: Bearer <token>` matching it (constant-time comparison). Missing or wrong tokens are rejected with HTTP 401 and body `{"error": "Unauthorized: missing or invalid Bearer token"}` — before any MCP handshake happens.
- **Open instance (empty/unset):** the endpoint is open — anyone who can reach the network port can connect (legacy behavior). Use only on trusted networks.

### 2. Identity — "which Wiki4AI user does the agent act as"

A Wiki4AI identity credential — a user JWT **or** a hashed `w4a_` API token (see [Hashed API tokens](#hashed-api-tokens)) — selected per request by `select_jwt_token()` in this priority order:

| Priority | Source | Notes |
|---|---|---|
| 1 | `X-Wiki4AI-JWT` request header | Dedicated identity channel. **Preferred on gated instances**, where the `Authorization` header must carry the access credential instead. |
| 2 | `Authorization: Bearer <jwt>` | Used when it is a real user JWT (open instances, where the auth header doubles as identity). |
| 3 | `?token=<jwt>` query parameter | Last resort for clients that cannot set headers. |

- **The access credential is never used as identity.** A value equal to `MCP_JWT_TOKEN` is explicitly skipped by `is_access_credential()`: it is not an app-signed JWT, and the backend rejects any Bearer value that fails JWT verification with 401 — even on public endpoints.
- **stdio mode:** a server-side identity can be fixed with `--token <jwt>` (or `MCP_JWT_TOKEN`-independent environment configuration); in SSE mode client-provided headers always take priority, which lets one server serve many agents with different identities.

### What this means for self-hosters

- **Recommendation: run a gated instance** (`MCP_JWT_TOKEN` set) and give every agent its own Wiki4AI user account + JWT — least privilege per agent, and the access token can be rotated independently of user accounts.
- The **permission model is identical to the WebUI**: USER/ADMIN roles are enforced by the backend on every REST call, so an MCP agent can never do more than the corresponding WebUI user (e.g. a USER-role agent cannot delete other users' projects; ADMIN-only endpoints stay out of reach).
- Without any identity JWT, only public read operations work (project/document reads); authenticated tools such as `upload_image` fail with a clear 401 "Not authenticated" error explaining how to supply the JWT.

## Hashed API tokens

Since the coordination layer, the recommended agent credential is a **hashed API token**, not a long-lived JWT. The MCP server does not need to know the format — it forwards whatever identity credential it receives — but agents get much further with a `w4a_` token, so the model is documented here.

- **Format:** an opaque secret — `w4a_` followed by 32 base62 characters, generated with a secure random source. It is *not* a JWT and carries no claims.
- **Stored as a hash:** the database keeps only the SHA-256 digest of the plaintext plus an 8-character **display prefix** (the first characters after `w4a_`, e.g. `w4a_k7x2…`) so a token can be recognized in listings without being reconstructable. The **plaintext is returned exactly once**, at creation or rotation, and is never stored or listed again — lose it and rotate.
- **Bound to an actor:** a token can name an actor from the coordination registry. Requests made with it are attributed to that actor (`actor_whoami` answers "who am I?"), which is what makes claiming work items, commenting, and reserving capacity possible at all — a credential that names no actor gets a clear 403 from those endpoints.
- **Lifecycle:** tokens are managed under `/api/v1/tokens` (create, list, get, **rotate**, revoke) and in the WebUI (profile page, agents page). **Rotate** replaces the secret and returns the new plaintext once. **Revoke** is a soft delete (`revoked_at`): the row stays auditable, the token is dead. The **kill switch** — `DELETE /api/v1/actors/{id}/tokens` — revokes *every* token of one actor in a single call.
- **401 semantics:** an unknown, expired, or revoked token is answered with one indistinguishable `401` (`"Invalid, expired or revoked API token"`) so probing cannot tell the three apart.
- **Rate limit:** each hashed token is limited to **30 requests per 1000 ms window** (server-configurable). Exceeding it returns **429** with a `Retry-After` header; throttling is deliberately not a 401, because the credential itself still works.
- **Legacy JWTs keep working.** Tokens issued through `POST /api/v1/auth/token/named` still authenticate exactly as before. They are **deprecated**: the WebUI shows a deprecation banner on unhashed tokens, the backend logs a per-account deprecation warning, and new integrations should use `w4a_` tokens.

**How an MCP client uses one:** put the token where the identity goes — `Authorization: Bearer <w4a_…>` on an open instance, or `X-Wiki4AI-JWT: <w4a_…>` on a gated one (where `Authorization` carries the `MCP_JWT_TOKEN` access credential). The MCP server passes it through to every backend REST call unchanged; the backend's auth filter recognizes the `w4a_` prefix, hashes the value, looks it up, and resolves the user *and* actor behind it.

## Tool reference

**52 tools** (verified via `tools/list`, serverInfo `wiki4ai` / FastMCP 4.0.5). Grouped by domain:

### Meta (3)

| Tool | Description | Key parameters |
|---|---|---|
| `health_check` | Health status of the backend service (application name, version, status) | — |
| `get_mermaid_guide` | Complete Mermaid syntax guide for AI agents: 5 diagram types with working examples and common error fixes | — |
| `get_plantuml_guide` | PlantUML guide for UML diagram types Mermaid does not cover (use case, component, deployment); rendered in the WebUI via the self-hosted kroki service | — |

### Projects (5)

| Tool | Description | Key parameters |
|---|---|---|
| `list_projects` | All projects accessible to the current user: id, name, slug, description, parentSlug, depth, documentCount | — |
| `get_project` | Details of one project by its URL-friendly slug | `slug` |
| `create_project` | Create a new project; optionally nest it under an existing project as a **subproject** | `name` (required), `description`, `parent_id` |
| `update_project` | Partial update of name, description and hierarchy position with tri-state move semantics | `slug` (required), `name`, `description`, `parent_id`, `move_to_root` |
| `delete_project` | Permanently delete a project **and all its documents** (irreversible) | `slug` (required) |

### Documents (10)

| Tool | Description | Key parameters |
|---|---|---|
| `list_documents` | Paginated document metadata for a project (no content) — call first to discover slugs and ids | `project_slug`, `page`, `size` (max 100) |
| `create_document` | Create a document; slug auto-generated from the title; Markdown with Mermaid/PlantUML code blocks supported | `project_slug`, `title`, `content`? |
| `batch_create_documents` | Create multiple documents in a single call | `project_slug`, `documents[]` (each `{title, content?}`) |
| `get_document` | Full raw Markdown content plus metadata — **including the current document version** | `project_slug`, `doc_slug` |
| `update_document` | Partial or full update: `edits` (find/replace) for safe concurrent editing, optional optimistic locking via `expected_version` | `project_slug`, `doc_slug`, `title`?, `content`? (full replace), `edits[]`?, `expected_version`? |
| `delete_document` | Permanently delete a document; wiki links pointing to it become broken | `project_slug`, `doc_slug` |
| `get_document_content` | Rendered HTML version with `[[WikiLink]]` references resolved | `project_slug`, `doc_slug` |
| `import_document` | Create a document from a raw Markdown string (both title and content required) | `project_slug`, `title`, `content` |
| `move_document` | Move a document to another project (removed from the source project) | `project_slug`, `doc_slug`, `target_project_slug` |
| `copy_document` | Duplicate a document into another project, or as a sibling within the same one; original preserved | `project_slug`, `doc_slug`, `target_project_slug`? |

### Search (2)

| Tool | Description | Key parameters |
|---|---|---|
| `search_documents` | Hybrid search in **one project**: literal text match fused with semantic pgvector cosine similarity (Reciprocal Rank Fusion); degrades gracefully to text-only when the embedding sidecar is down | `project_slug`, `keyword` (min 2 chars) |
| `search_documents_global` | The same hybrid matching **across all projects** (cross-project search); requires a valid identity JWT | `keyword`, `limit` (default 20, max 50) |

### Links (4)

| Tool | Description | Key parameters |
|---|---|---|
| `add_link` | Add a wiki link from one document to another within the same project | `project_slug`, `doc_slug` (source), `target_document_id` (**integer id**, not slug — get it from `list_documents`) |
| `remove_link` | Remove a wiki link between two documents | `project_slug`, `doc_slug`, `target_document_id` |
| `get_links` | Outgoing links: the documents this document links to | `project_slug`, `doc_slug` |
| `get_backlinks` | Incoming links: the documents that link to this one (use before deleting/restructuring) | `project_slug`, `doc_slug` |

### Images (1)

| Tool | Description | Key parameters |
|---|---|---|
| `upload_image` | Upload a base64 image (PNG/JPEG/WebP/GIF/SVG, max 10 MB) under an unguessable UUID name; returns the URL plus a ready-to-paste Markdown snippet `![alt](/images/{project}/{uuid}.ext)`. Uploaded images are login-only — never anonymously accessible | `project_slug`, `filename`, `image_base64` |

### Vault (7)

| Tool | Description | Key parameters |
|---|---|---|
| `vault_status` | Whether the current user has a vault master password set up (call before the first vault operation) | — |
| `vault_list_entries` | All entries, **metadata only** (title, url, groupPath, timestamps) — no credentials returned | — |
| `vault_search_entries` | Search entries by title/URL substring (metadata only) | `query`, `group_path`? |
| `vault_get_entry` | One entry with decrypted username/password/notes; decryption happens locally in the MCP process, the backend never sees plaintext | `entry_id`, `master_password` |
| `vault_create_entry` | Add a new entry — encrypted client-side before anything is sent | `title`, `password`, `master_password`, `username`?, `notes`?, `url`?, `group_path`? |
| `vault_update_entry` | Edit an entry; only the provided fields change (fetch → decrypt → merge → re-encrypt → submit) | `entry_id`, `master_password`, plus any of `title`/`username`/`password`/`notes`/`url`/`group_path` |
| `vault_delete_entry` | Permanently delete an entry (no master password required — deletion needs no decryption) | `entry_id` |

### Calendar (6)

The calendar is a **global** module (not project-scoped): events belong to the user whose identity JWT is used, and the backend enforces public/private visibility exactly as for the REST API. See [[Calendar]] for the full module guide.

| Tool | Description | Key parameters |
|---|---|---|
| `calendar_create_event` | Create an event — all-day when no times given, precise-time otherwise; type defaults to the first available one; visibility defaults to `public` | `title`, `date` (required), `start_time`?, `end_time`?, `description`?, `event_type`? (name, case-insensitive), `visibility`? |
| `calendar_list_events` | List events in a date range (default: current month) — public events plus the caller's own private ones (ADMIN sees all); optional type filter and `mine=true`; all-day events first | `from_date`?, `to_date`?, `event_type`?, `mine`? |
| `calendar_update_event` | Partial update — only provided fields change; owner or ADMIN only; the explicit `clear_time=True` flag is the only way to revert a timed event to all-day (omitted fields mean "no change") | `event_id` (required), any of `title`/`description`/`date`/`start_time`/`end_time`/`event_type`/`visibility`, `clear_time`? |
| `calendar_delete_event` | Delete an event permanently; owner or ADMIN only (foreign private → 404, foreign public → 403) | `event_id` (required) |
| `calendar_list_event_types` | List all event types with id, name and color, ordered by id (seed types first) — call before creating events to discover valid type names | — |
| `calendar_create_event_type` | Create a new event type with an optional hex color; duplicate names (case-insensitive) are rejected with 409 | `name` (required), `color`? |

### Actor identity (1)

| Tool | Description | Key parameters |
|---|---|---|
| `actor_whoami` | Which actor the caller's credential names — actor id, handle, kind, scopes and project roles, straight from the verified token (never a self-declared header). Call it first from any agent; with `actorId: null` the credential names no actor and the coordination tools below will refuse to act | — |

### Work items (6)

The coordination board — states, atomic claims, structured comments. Full semantics in [[Agent Coordination]].

| Tool | Description | Key parameters |
|---|---|---|
| `workitem_list` | List work items, optionally filtered by project, status, label (whole-token match) and priority; `page`/`size` are applied by the MCP layer | `project_id`?, `status`?, `label`?, `priority`?, `page`, `size` |
| `workitem_get` | One work item with its current state, assignee and lease | `work_item_id` |
| `workitem_create` | Create a work item — it lands in `backlog`, so a claim takes two more calls (`workitem_update(status='ready')`, then `workitem_claim`); creating directly in `done` is refused with `RESULT_COMMENT_REQUIRED` | `project_id`, `title` (required), `description`?, `status`?, `priority`?, `labels`?, `external_ref`?, `parent_id`? |
| `workitem_claim` | Atomically claim a `ready` item as the calling actor — the race winner gets the item plus its `claimToken` and lease expiry, everyone else a 409 | `work_item_id` |
| `workitem_update` | Change an item (PATCH semantics); `status='claimed'` is refused here — only `workitem_claim` may enter it. Illegal transitions 409 with the legal targets listed, and `review → done` without a result comment 409s with `RESULT_COMMENT_REQUIRED` | `work_item_id`, `title`?, `description`?, `status`?, `priority`?, `labels`? |
| `workitem_comment` | Append a structured comment (`note`, `result`, `handoff`, `blocker`, `status`, `decision`); the author is always the actor of the credential, and a payload that breaks the kind's contract is a **422** naming the expected keys | `work_item_id`, `kind`, `payload` (the kind's contract — the comment text goes in as the `body` key) |

### Inbox (2)

| Tool | Description | Key parameters |
|---|---|---|
| `inbox_poll` | The actor's four inbox streams (assigned items, comments on them, handoff mentions, items lost to lease expiry) in one call, paginated by an opaque per-stream cursor | `actor_id`, `cursor`?, `limit` (default 50) |
| `inbox_mark_seen` | Advance the actor's unseen marker so the next poll reports only genuinely new events | `actor_id`, `event_id`? |

### Resources & reservations (5)

The capacity ledger for scarce resources (GPUs, license seats, build runners).

| Tool | Description | Key parameters |
|---|---|---|
| `resource_list` | All resources with capacity and currently active slots | — |
| `resource_get` | One resource by its key | `key` |
| `reservation_list` | The active reservations of a resource | `key` |
| `reservation_create` | Atomically reserve slots as an actor — the capacity check is part of the same conditional update, so two agents cannot both take the last slot (the loser gets 409) | `key`, `slots` (default 1), `reason`?, `work_item_id`? |
| `reservation_release` | Release a reservation early; idempotent — releasing twice lowers usage exactly once | reservation id |

All coordination tools act **as the actor named by the caller's credential**; with a legacy JWT that names no actor they return a clear error naming the fix (a `w4a_` agent token bound to an actor). Out-of-capacity, disabled-resource, lost-claim-race and `RESULT_COMMENT_REQUIRED` failures arrive as structured 409s, a comment payload that breaks its kind's contract as a 422 naming the expected keys, and per-token rate limiting as 429 with `Retry-After`.

### Subprojects and project hierarchy

- **`create_project` with `parent_id`** creates a subproject nested under the given project. The hierarchy is limited to **5 levels deep**, and parents that would create a cycle are rejected by the backend with a clear error.
- **`update_project` tri-state move semantics** — exactly one of three outcomes per call:
  - *neither* `parent_id` *nor* `move_to_root` given → the hierarchy is **not changed** (no move);
  - `parent_id=<int>` → the project is moved under the project with that ID;
  - `move_to_root=true` → the project is moved back to root level.
  - Passing both is contradictory and raises an error. Moves are limited to 5 levels deep; cycles are rejected.
  - Note: the backend requires a non-blank name in every update payload. When you omit `name`, the tool fetches the project first and re-sends its current name unchanged, so description-only and hierarchy-only updates work as documented.

### Vault encryption (client-side)

- Entries are encrypted **before they leave the client**: AES-256-GCM with a key derived via PBKDF2-HMAC-SHA256 from `SHA-256(master password)` + the per-user salt (fetched from the backend), 100,000 iterations, 32-byte key. The encrypted fields are `username`, `password` and `notes` (JSON-encoded, then encrypted); `title`, `url` and `groupPath` stay plaintext for search and display.
- **The raw master password never leaves the client.** Only its SHA-256 hash is sent to the backend for verification (`GET /v1/vault/master-password/salt` → `POST /v1/vault/master-password/verify`).
- There is **one vault per user, shared between the WebUI vault page and these MCP tools** — an entry created via MCP is immediately visible and decryptable in the WebUI with the same master password, and vice versa.

## Multi-agent safety: optimistic locking (WIKI4AI-72)

Every document carries a monotonically increasing `version`. The safe write pattern for concurrent agents:

1. **Read** — `get_document` returns the content *and* the current version.
2. **Write with lock** — call `update_document` passing `expected_version` = the version you read, together with your change (`content` or `edits`).
3. **On success** — the response includes the new `version`; keep it for your next update.
4. **On conflict (HTTP 409)** — another writer got there first. The MCP layer rewrites the raw 409 into an actionable error, by design:

   > Conflict: document was modified since version X (current version: Y). Re-read the document and retry your change.

   (Variants exist when the backend reports only one of the two versions.) The message tells you exactly which versions are involved so the agent can recover without guessing.
5. **Recover** — re-read the document, re-apply your change on the fresh content, and update with the new `expected_version`.

```mermaid
sequenceDiagram
    participant A as Agent (MCP client)
    participant B as Wiki4AI backend
    A->>B: get_document(doc) → version 5
    A->>B: update_document(expected_version=5, edits=[...])
    B-->>A: 200 OK — new version 6
    Note over A,B: Agent B writes in the meantime (version becomes 7)
    A->>B: update_document(expected_version=5, edits=[...])
    B-->>A: 409 Conflict: document was modified since version 5 (current version: 7). Re-read the document and retry your change.
    A->>B: get_document(doc) → version 7
    A->>B: update_document(expected_version=7, re-applied edits)
    B-->>A: 200 OK — new version 8
```

### Best practices for multi-agent workloads

- **One writer per document at a time** is the simplest safe design; use `expected_version` when writers genuinely overlap.
- Prefer **`edits` (find/replace) over full-content replacement** when several agents append to a shared document — find strings must be unique (include the section heading), and a failed match fails the whole update atomically instead of clobbering someone else's section.
- **Always keep the `version` returned by each successful update** and pass it as the next `expected_version`; never reuse a stale one.
- Treat conflict messages as normal control flow: they are structured to be machine-actionable (stale version + current version), so a retry loop of *re-read → re-apply → retry* terminates cleanly.

## Setup for AI agents

### Generic stdio client configuration

For any MCP client that speaks stdio (JSON config with the common `mcpServers` shape — placeholder values, no real tokens):

```json
{
  "mcpServers": {
    "wiki4ai": {
      "command": "npx",
      "args": [
        "-y",
        "mcp-remote",
        "http://<YOUR_WIKI4AI_HOST>:8099/sse",
        "--header",
        "Authorization: Bearer <YOUR_MCP_ACCESS_TOKEN>",
        "--header",
        "X-Wiki4AI-JWT: <YOUR_USER_JWT>",
        "--allow-http"
      ]
    }
  }
}
```

- `<YOUR_WIKI4AI_HOST>:8099/sse` — the SSE endpoint of your instance (container port 8095 by default; adjust to your port mapping).
- `<YOUR_MCP_ACCESS_TOKEN>` — the value of `MCP_JWT_TOKEN` on the server. **Required on gated instances**; on open instances omit this header and put the user JWT in `Authorization` instead.
- `<YOUR_USER_JWT>` — a Wiki4AI user JWT obtained from `POST /api/v1/auth/login` (username + password). This is the identity the agent acts as.
- `--allow-http` — required by mcp-remote for non-TLS URLs.

### DeepSeek Harness (DSH) profile patch

DSH's MCP client has no native SSE transport, so the server is bridged through `mcp-remote` as a stdio entry in the profile patch file (`~/.dsh-home/profiles/web/cordis.patch.yml`) — anonymized example:

```yaml
- insert:
    - id: mcp-wiki4ai
      name: '@deepseek-ai/dsh-mcp-client'
      config:
        serverName: wiki4ai
        transport: stdio
        command: npx
        args:
          - '-y'
          - 'mcp-remote'
          - 'http://<YOUR_WIKI4AI_HOST>:8099/sse'
          - '--header'
          - 'Authorization: Bearer <YOUR_MCP_ACCESS_TOKEN>'
          - '--header'
          - 'X-Wiki4AI-JWT: <YOUR_USER_JWT>'
          - '--allow-http'
```

`mcp-remote` first tries the streamable-HTTP transport and automatically falls back to SSE, which is how legacy SSE-only endpoints stay reachable.

### Note on clients without SSE support

If your MCP client cannot open an SSE connection (most stdio-first clients), **bridge via `mcp-remote`** as shown above — it is a thin stdio↔SSE relay and the only moving part between the client and the server. The server itself always runs one of two native transports: SSE (`--transport sse`) or stdio (local, no bridge needed).

## Related documents

- [[Home]] — project overview
- [[Architecture Overview]] — system components and request flow
- [[Backend API Reference]] — the REST endpoints the MCP server wraps
- [[Agent Coordination]] — the work-item board, actors, inbox and resources behind the coordination tools
- [[Data Model]] — entities, including document versions used for optimistic locking
- [[Search & Embeddings]] — how the hybrid search tools work under the hood
- [[WebUI Guide]] — the human-facing interface to the same data
