# Backend API Reference

Complete reference for the wiki4ai Spring Boot REST backend (all endpoints verified against the source code in `backend/src/main/java/com/wiki4ai/`).

- **Base URL:** `http://<host>:<backend-port>/api/v1` (container port 8080; the host side depends on your compose file)
- **Format:** JSON request/response bodies (except multipart upload endpoints and the ZIP export)
- **Auth header:** `Authorization: Bearer <accessToken>`

## Access model at a glance

The security filter chain (`SecurityConfig`) applies these rules:

| Path | Access |
|---|---|
| `/api/v1/auth/**` | Public (all methods) |
| `GET /api/v1/projects/**` (projects, documents, links, search under project paths) | **Public** — read endpoints do not require a JWT; unauthenticated callers are treated as `"anonymous"` and the permission check is skipped by design (wiki convention: reads are open, writes are protected) |
| `/api/health`, `/actuator/**`, `/h2-console/**` | Public |
| `OPTIONS *` (CORS preflight) | Public |
| Everything else — all mutating operations, `GET /api/v1/search/**`, `/api/v1/images/**`, `/api/v1/vault/**`, `/api/v1/admin/**`, the coordination endpoints (`/api/v1/actors/**`, `/api/v1/tokens/**`, `/api/v1/work-items/**`, `/api/v1/resources/**`, `/api/v1/reservations/**`, `/api/v1/metrics/**`), Swagger | **Valid credential required** — user JWT or hashed `w4a_` API token (401 otherwise) |

Role checks (USER vs ADMIN) and per-project permissions (READ/CREATE/UPDATE/DELETE/MANAGE) are enforced in the service layer, not by Spring Security matchers. The ADMIN role bypasses all project-level permission checks; any authenticated USER has implicit READ on every project; CREATE/UPDATE/DELETE require an explicit grant (or MANAGE).

## Authentication model

**JWT (HS256 via jjwt 0.12.x).** Both access and refresh tokens are JWTs signed with the shared secret (`jwt.secret`, env `JWT_SECRET`). Claims: `sub` = username, `type` = `ACCESS` or `REFRESH`. Default lifetimes (configurable in `application.yml`):

| Token | Property | Default |
|---|---|---|
| Access | `jwt.expiration` | 86400000 ms (24 h) |
| Refresh | `jwt.refreshExpiration` | 604800000 ms (7 d) |

**Token lifecycle.** There is **no dedicated `/refresh` endpoint**. Instead:

- `POST /api/v1/auth/login` and `POST /api/v1/auth/token` both return a fresh `{accessToken, refreshToken}` pair.
- The refresh token is persisted in the `refresh_tokens` table — **one per user** (unique `user_id`). Every login/token call deletes the previous row and inserts the new one (serialized by striped locks to avoid unique-constraint races). A refresh token with `expiresAt = null` never expires.
- Named API tokens (`POST /api/v1/auth/token/named`) are separate JWT access tokens stored in `api_tokens`, manageable per user, optionally infinite-lived. They are **deprecated** in favor of hashed `w4a_` tokens (`/api/v1/tokens`, see [API tokens](#api-tokens-apiv1tokens--hashed-w4a_-tokens)); legacy rows keep authenticating unchanged.

**Registration policy** (`auth.registration.open`, env `AUTH_REGISTRATION_OPEN`, tri-state):

| Value | Behavior of `POST /register` |
|---|---|
| unset (default) | Open **only while the users table is empty**; closes automatically (403) once the first account exists |
| `true` | Always open (explicit opt-in for public sign-up) |
| `false` | Never open — accounts via first-run setup or admin only |

**First-run initialization.** Two ways to create the very first ADMIN on an empty instance:

1. `POST /api/v1/auth/setup` — accepted **only while no account exists**, afterwards 403. Email optional; when omitted the backend derives `{username}@localhost`.
2. Env bootstrap `ADMIN_INITIAL_USERNAME` / `ADMIN_INITIAL_PASSWORD` — honored at startup only while the users table is empty (no defaults on purpose; a fresh instance otherwise starts *uninitialized* and the WebUI shows the setup form).

## Auth endpoints (`/api/v1/auth`)

| Method | Path | Access | Success | Errors |
|---|---|---|---|---|
| GET | `/status` | public | 200 `{"initialized":bool,"registrationOpen":bool}` | — |
| POST | `/setup` | public, empty instance only | 201 `UserDTO` (role ADMIN) | 403 already initialized; 400 validation |
| POST | `/register` | public, per registration policy | 201 `UserDTO` (role USER) | 403 closed; 409 username/email taken; 400 validation |
| POST | `/login` | public | 200 `AuthResponseDTO` | 401 invalid credentials (generic message — DB failures are deliberately masked as 401 too) |
| GET | `/me` | JWT | 200 `UserDTO` | 401 unauthenticated / user not in DB |
| PUT | `/me` | JWT | 200 `UserDTO` | 409 username/email taken; 400 wrong current password |
| POST | `/token` | JWT | 200 `AuthResponseDTO` (fresh pair) | 401 unauthenticated / user not in DB |
| POST | `/token/named` | JWT | 201 `TokenResponseDTO` | 400 missing name; 401 |
| GET | `/tokens` | JWT | 200 `List<TokenResponseDTO>` (token values **not** exposed — `accessToken` is null in listings) | 401 |
| DELETE | `/token/{tokenId}` | JWT, owner only | 204 | 401; 404 not found / not owner |
| GET | `/health` | public | 200 `{"status":"UP","service":"auth"}` | — |

**DTOs:** `UserDTO {id, username, email, role, createdAt}` · `AuthResponseDTO {accessToken, refreshToken, user: UserDTO}` · `TokenResponseDTO {accessToken, name, expiresAt, createdAt, tokenId}`.

### Examples

`POST /api/v1/auth/login`:

```json
// request
{"username": "admin", "password": "<your-password>"}
// response 200 (token values abbreviated)
{
  "accessToken": "eyJhbG…9...",
  "refreshToken": "eyJhbG…9...",
  "user": {"id": 1, "username": "admin", "email": "admin@example.com", "role": "ADMIN", "createdAt": "2026-09-14T08:42:35"}
}
```

`GET /api/v1/auth/status` (verified live on dev):

```json
{"initialized": true, "registrationOpen": false}
```

`PUT /api/v1/auth/me` — all fields optional; `currentPassword` is required only when changing the password (`newPassword` min 3 chars):

```json
{"email": "new@example.com", "currentPassword": "***", "newPassword": "***"}
```

## Projects API (`/api/v1/projects`)

| Method | Path | Access | Success | Errors |
|---|---|---|---|---|
| GET | `/` | public | 200 `List<ProjectDTO>` (newest first) | — |
| GET | `/{slug}` | public | 200 `ProjectDTO` | 404 unknown slug |
| GET | `/{slug}/tree` | public | 200 nested `ProjectTreeNodeDTO` tree (all levels) | 404 |
| POST | `/` | JWT | 201 `ProjectDTO` — creator auto-granted **MANAGE** | 409 duplicate name; 404 parent not found; 400 depth > 5; 400 validation |
| PUT | `/{slug}` | JWT, MANAGE | 200 `ProjectDTO` | 403; 404 project or new parent; 400 self-move / cycle / depth exceeded; 400 validation |
| DELETE | `/{slug}` | JWT, MANAGE | 204 | 403; 404 |
| GET | `/{slug}/export` | JWT, READ | 200 ZIP archive of all documents as `.md` files (`Content-Disposition: attachment`) | 403; 404 |
| GET | `/by-id/{id}` · PUT `/by-id/{id}` · DELETE `/by-id/{id}` | same as slug variants | — | — |

**`ProjectDTO`:** `{id, name, description, slug, parentSlug (null for root), depth (root = 1, max 5), documentCount, createdAt, updatedAt}`.

### Hierarchy rules (subprojects)

- A project has at most one parent (`parent_id` self-reference, `ON DELETE CASCADE` — deleting a project cascades to its subprojects and all documents).
- **Max depth 5** (`Project.MAX_HIERARCHY_DEPTH`); root projects are depth 1. Creating under a depth-4 project is rejected with 400.
- **Anti-cycle:** on move, the service walks up from the proposed new parent; if it reaches the moved project itself → 400 "would create a cycle". Self-move is also 400.
- Moving a project *deeper* additionally verifies that all of its descendants still fit within depth 5 (BFS over `parent_id`).
- **Move semantics via PUT** — the `parentId` key in the update body is tri-state:

| Payload | Effect |
|---|---|
| key absent | no move, keep current parent |
| `"parentId": null` | move back to root |
| `"parentId": 42` | move under project id 42 (validated as above) |

- **Renaming never changes the slug** — `setName()` intentionally does not regenerate it, so URLs stay stable across renames. Slugs are generated once at creation (`name.toLowerCase()`, non-alphanumerics stripped, spaces → `-`).
- Deleting a project requires MANAGE and cascades: documents, subprojects (DB-level `ON DELETE CASCADE`), permissions, and links touching the removed documents.

## Documents API (`/api/v1/projects/{projectSlug}/documents`)

| Method | Path | Access | Success | Errors |
|---|---|---|---|---|
| POST | `/` | JWT, CREATE | 201 `DocumentDTO` (new docs start at `version: 0`) | 400 validation; 403; 404 project |
| GET | `/` | public | 200 Spring `Page<DocumentSummaryDTO>` (`?page=0&size=20`, ordered by `updatedAt` desc; **no content field**) | 403; 404 |
| GET | `/{docSlug}` | public | 200 `DocumentDTO` (full content + version) | 404 project or document |
| PUT | `/{docSlug}` | JWT, UPDATE | 200 `DocumentDTO` (version incremented) | 400 nothing to update / `content`+`contentEdits` together / edit ambiguity; **409 version conflict**; 403; 404 |
| DELETE | `/{docSlug}` | JWT, DELETE | 204 | 403; 404 |
| GET | `/search?keyword=...` | public | 200 `List<DocumentDTO>` with `score` (hybrid search, see below) | 400 keyword < 2 chars; 404 project |
| POST | `/upload` (multipart `file`) | JWT, CREATE | 201 `DocumentDTO` — imports a `.md`/`.markdown` file | 400 wrong extension; 409 same title exists; 413 > 5 MB |
| GET | `/{docSlug}/links` | public | 200 `List<DocumentDTO>` (outgoing links) | 404 |
| POST | `/{docSlug}/links` | JWT, UPDATE | 201 link created — body `{"targetDocumentId": 42}`; same project only, no self-links, no duplicates | 400; 403; 404 |
| DELETE | `/{docSlug}/links/{targetDocId}` | JWT, UPDATE | 204 | 404 |
| GET | `/{docSlug}/backlinks` | public | 200 `List<DocumentDTO>` (documents linking here) | 404 |
| GET | `/{docSlug}/content` | public | 200 `DocumentContentDTO {id, title, htmlContent (rendered), wikiLinks (extracted [[WikiLink]] titles), linkedDocuments}` | 404 |
| POST | `/{docSlug}/move` | JWT, UPDATE+DELETE on source, READ on target | 200 `DocumentDTO` — body `{"targetProjectSlug": "other"}`; all links touching the document are cleared (it changes project) | 400; 403; 404 |
| POST | `/{docSlug}/copy` | JWT, UPDATE on source, CREATE on target | 201 `DocumentDTO` — body optional `{"targetProjectSlug": "..."}` (same project when omitted); title becomes `"X (copy)"` / `"X (copy N)"`; content is re-embedded | 403; 404 |

**`DocumentDTO`:** `{id, title, slug, content, projectId, linkedDocuments: [ids], createdAt, updatedAt, version, editsApplied (null except on contentEdits updates), score (only on search results)}`.

### Update semantics (PUT)

The body is a **partial update** — `title`, `content`, `contentEdits` are all optional but at least one must be present (400 otherwise). Omitted fields stay unchanged. Allowed combinations: title-only, content-only, contentEdits-only, title+content, title+contentEdits. `content` + `contentEdits` in the same request → 400.

- `content` = full replacement (backward compatible).
- `contentEdits` = incremental find/replace edits applied **sequentially** to the current content (each edit sees the result of the previous one):
  - `find` matches **exactly** (case-sensitive, including whitespace);
  - `replace: ""` deletes the matched text;
  - `replaceAll` defaults to false — when `find` occurs more than once without it → 400 with `editIndex` and `occurrences`; zero occurrences → same 400 shape.

### Optimistic locking (version / expectedVersion)

Every document carries a `version` (JPA `@Version`, column added by migration V11, existing rows backfilled to 0). Two layers protect concurrent writers:

1. **Explicit check:** if the update body contains `expectedVersion`, the service compares it with the current version *before any change*. Mismatch → **409 Conflict** with both versions in the body so the client can re-read and retry:

   ```json
   // PUT .../documents/some-doc  {"content": "...", "expectedVersion": 99}
   // response 409 (verified live on dev)
   {
     "currentVersion": 0,
     "expectedVersion": 99,
     "error": "Document version conflict",
     "message": "Document was modified since version 99 (current version: 0). Re-read the document and retry your change.",
     "status": 409,
     "timestamp": "2026-09-22T10:20:57.777586331"
   }
   ```

2. **JPA safety net:** even without `expectedVersion`, Hibernate appends `WHERE version = ?` to every UPDATE and increments it on success. A concurrent commit on stale state fails at flush with an optimistic-locking exception, also mapped to 409 (the handler re-reads the current version from the DB for the body).

**When/why:** clients (WebUI autosave, MCP agents) read a document, keep its `version`, and send it back as `expectedVersion` on save. If another writer committed in between, the stale write fails with 409 instead of silently overwriting — last-write-wins is eliminated for both humans and agents. The WebUI surfaces the conflict and asks for re-read/merge; a correct `expectedVersion` simply succeeds and bumps the version (verified: 0 → 1).

## Search API

Two endpoints share one hybrid implementation (text + vector + RRF — details in [[Search & Embeddings]]):

| Endpoint | Access | Params | Response |
|---|---|---|---|
| `GET /api/v1/projects/{slug}/documents/search` | public | `keyword` (required, min 2 chars) | `List<DocumentDTO>` — full content + `score`, all matches |
| `GET /api/v1/search/documents` | **JWT required** (login-only by design; anonymous → 401) | `keyword` (min 2 chars), `limit` (default 20, hard cap 50) | `List<GlobalSearchResultDTO>` — cross-project, **excerpt instead of full content** |

`GlobalSearchResultDTO`: `{id, title, slug, projectId, projectSlug, projectName, score, updatedAt, excerpt (~200 chars around the first keyword occurrence)}`.

Verified live (global search, `keyword=embedding&limit=3`, top hit):

```json
{
  "id": 14, "title": "Architecture Overview", "slug": "architecture-overview",
  "projectId": 9, "projectSlug": "wiki4ai", "projectName": "wiki4ai",
  "score": 0.032787, "updatedAt": "2026-09-22T08:51:43.681022",
  "excerpt": "…ctor** extension | Single source of truth: projects, documents (Markdown content + a `vector(1024)` embedding column)…}"
}
```

Scores are RRF values — small by design; a document ranked #1 in **both** the text and vector lists scores ≈ 0.0328 (= 2/61), #1 in one list only ≈ 0.0164 (= 1/61). Higher is better; absolute magnitude carries no other meaning.

## Images & uploads API (`/api/v1/images`)

Both endpoints **require a JWT** — the paths deliberately live outside the public `GET /api/v1/projects/**` matcher, so uploaded images are never anonymously accessible.

| Method | Path | Access | Success | Errors |
|---|---|---|---|---|
| POST | `/{projectSlug}` (multipart field `file`) | JWT | 201 `{url, markdown, filename, storedName, size, contentType}` | 400 empty file / unsupported type; 404 project; **413 > 10 MB** |
| GET | `/{projectSlug}/{filename}` | JWT | 200 image bytes (`Cache-Control: private, max-age=3600`, `X-Content-Type-Options: nosniff`; SVG additionally gets a restrictive CSP header) | 400 path-traversal-shaped filename; 404 project or image |

- Accepted types: **PNG, JPEG, WebP, GIF, SVG** — detected from magic bytes, not the extension.
- Files are stored on a host volume (`upload.dir`, env `UPLOAD_DIR`, container default `/uploads`) under `{projectSlug}/{uuid}.{ext}`; the returned `url` is the relative markdown path `/images/{projectSlug}/{uuid}.{ext}` and `markdown` is a ready-to-paste `![alt](url)` snippet (alt = original filename without extension).
- The WebUI intercepts `<img src>` values of that shape and fetches them with the JWT, converting the response to a blob URL (browsers can't send Bearer headers from `<img>`).

## Vault API (`/api/v1/vault`) — encrypted secret vault

Per-user entries (passwords, tokens) **encrypted client-side** before upload; the backend stores only ciphertext and never sees plaintext. Key derivation: PBKDF2WithHmacSHA256, 100 000 iterations, with a server-synced per-user salt so any of the user's clients (WebUI, MCP) can derive the same key from the master password.

| Method | Path | Purpose |
|---|---|---|
| POST | `/entries` | Create entry (encrypted fields: username, password, notes; plaintext metadata: title, url, groupPath) |
| GET | `/entries` | List own entries (metadata only — no decrypted values) |
| GET | `/entries/{id}` | Entry detail (ownership enforced) |
| PUT | `/entries/{id}` | Edit entry (only provided fields change) |
| DELETE | `/entries/{id}` | Delete entry |
| GET | `/search?query=...&groupPath=...` | Search by title/URL, optional group prefix filter |
| GET | `/master-password/status` | Whether the user has a vault master password set |
| POST | `/master-password/set` | Store PBKDF2 hash + salt (client computes the hash) |
| GET | `/master-password/salt` | Base64 salt (not secret — only slows precomputed-hash attacks) |
| POST | `/master-password/verify` | Verify a client-computed hash |
| POST | `/import/kdbx` | Parse a KeePass KDBX file, return entries as JSON for import |
| GET | `/export` | All own entries as encrypted blobs (frontend decrypts and formats CSV/JSON) |

## Project permissions API (`/api/v1/projects/{projectSlug}/permissions`)

Grants per-user permission sets on a project. Permission levels: `READ`, `CREATE`, `UPDATE`, `DELETE`, `MANAGE` (MANAGE implies all others plus the ability to grant). Managing grants requires MANAGE on the project (or ADMIN role).

| Method | Path | Access | Success |
|---|---|---|---|
| GET | `` | JWT, MANAGE | 200 list of `{username, permissions[]}` |
| POST | `` | JWT, MANAGE | grant — body `{"username": "bob", "permissions": ["READ","CREATE"]}` |
| PUT | `/{username}` | JWT, MANAGE | replace that user's permission set |
| DELETE | `/{username}` | JWT, MANAGE | revoke all permissions for the user |

## Calendar API (`/api/v1/calendar`)

Global (not project-scoped) event calendar. All endpoints **require a JWT**. Visibility: `public` events are visible to every authenticated user; `private` events only to their creator and ADMIN users — for anyone else, detail/update/delete return **404** (existence is deliberately not revealed) and list endpoints omit them. Update/delete are owner-or-ADMIN only (foreign public event → 403).

| Method | Path | Access | Success | Errors |
|---|---|---|---|---|
| GET | `/events?from=&to=&type=&mine=` | JWT | 200 `List<CalendarEventDTO>` | 400 invalid date or `from` > `to` |
| GET | `/events/{id}` | JWT, visibility-checked | 200 `CalendarEventDTO` | 404 not found / foreign private event |
| POST | `/events` | JWT | 201 `CalendarEventDTO` — creator = authenticated user | 400 missing title/date, unknown type (error lists available types), invalid visibility |
| PUT | `/events/{id}` | JWT, owner or ADMIN | 200 `CalendarEventDTO` — PATCH-like: omitted fields unchanged; `clearTime: true` reverts a timed event to all-day | 403 foreign public event; 404 foreign private event; 400 validation |
| DELETE | `/events/{id}` | JWT, owner or ADMIN | 204 | 403 foreign public event; 404 foreign private event |
| GET | `/event-types` | JWT | 200 `List<EventTypeDTO>` ordered by id (seed types first) | — |
| POST | `/event-types` | JWT | 201 `EventTypeDTO` | 400 missing name; **409 duplicate name** (case-insensitive) |
| DELETE | `/event-types/{id}` | JWT | 204 | 404 unknown type; **409 type still used by N events** |

**List semantics (`GET /events`).** `from`/`to` are inclusive `YYYY-MM-DD` dates; when omitted the **current month** is the default range. `type` filters by event-type name (case-insensitive exact match — unknown names yield an empty list). `mine=true` returns only the caller's own events in both visibilities. Without `mine`: an ADMIN sees everything, a regular user sees public events plus their own private ones. Results are ordered by date, then time — **all-day events first** on each day, timed events afterwards by start time.

**DTOs:**

- `CalendarEventDTO`: `{id, title, description, eventTypeId, eventType (name, denormalized), eventColor (hex, nullable), eventDate, startTime (null = all-day), endTime, visibility, createdBy (username), createdAt, updatedAt}`
- `EventTypeDTO`: `{id, name, color (nullable hex), createdAt}`

`POST /events` body: `title` and `eventDate` required; `eventType` accepts **either the numeric id or the type name** (case-insensitive); `visibility` defaults to `public`; `startTime`/`endTime` optional (`TIME`, `HH:mm:ss`). Two types are seeded by migration V13: `Agent task` (#4f8cff) and `Pripomienka` (#f59e0b). Full module guide (WebUI views, all-day vs timed, MCP tools): [[Calendar]].

## Agent coordination API

The coordination layer (module guide in [[Agent Coordination]]) adds six endpoint groups. All require a valid credential; endpoints that act *as an actor* (claim, comment, reserve) additionally require a credential that **names an actor** — typically a `w4a_` token bound to one — and answer **403** (with the fix in the message) for a legacy JWT that names none.

### Actors API (`/api/v1/actors`)

| Method | Path | Success | Errors |
|---|---|---|---|
| GET | `/` | 200 `List<ActorDTO>` | 401 |
| POST | `/` | 201 `ActorDTO` — `handle` normalized to lowercase, globally unique | 409 duplicate handle; 400 validation |
| GET | `/whoami` | 200 `ActorWhoamiDTO` — the actor named by the **verified credential** (null actor + empty scopes for a credential that names none) | 401 |
| GET | `/{id}` · PUT `/{id}` · DELETE `/{id}` | 200 / 200 / 204 | 404 unknown actor |
| POST | `/{id}/heartbeat` | 204 — updates `last_seen_at` and presence (`idle` \| `working` \| `busy` + optional note); writes are throttled server-side | 404 |
| DELETE | `/{id}/tokens` | 204 — **kill switch**: revokes every token bound to this actor at once | 404 |

Project-scoped roles live under `/api/v1/projects/{projectId}/actors`: `GET` (list assignments), `POST` (assign — 409 if already assigned), `PUT /{actorId}` (change role), `DELETE /{actorId}` (remove). Roles: `viewer` \| `worker` \| `planner` \| `admin` (admin manages the project's actors; enforced in the service layer).

### Actor inbox (`/api/v1/actors/{id}/inbox`)

| Method | Path | Success | Notes |
|---|---|---|---|
| GET | `?cursor=&limit=` | 200 `InboxResponseDTO` — four streams (assigned items; comments on items the actor created or holds; handoff mentions addressed to the actor's handle; items lost to `lease_expired` / `stale_claim_cleared`) | `cursor` is an **opaque per-stream string** the client stores and returns verbatim; `limit` outside 1..50 or non-numeric → 400 naming the parameter |
| GET | `/unseen` | 200 `UnseenEventsDTO` — unseen count; a null marker means "never read", distinct from any id | |
| POST | `/mark-seen` | 204 — advances the marker (body `{"eventId": 123}` optional; omitted = advance to the highest event) | |

### API tokens (`/api/v1/tokens`) — hashed `w4a_` tokens

| Method | Path | Success | Errors |
|---|---|---|---|
| POST | `/` | 201 `ApiTokenCreatedDTO` — plaintext (`w4a_` + 32 base62 chars) returned **once**; the DB keeps only the SHA-256 hash + 8-char display prefix. Optional `expiresAt` (null = infinite), `scopes` (default `read write`), `actorId` (the actor the token acts as) | 400 missing name / unknown scope / invalid actorId / expiry in the past / `admin` scope on an agent-bound token; 401 |
| GET | `/` | 200 `List<ApiTokenDTO>` — metadata + prefix only, never a secret | 401 |
| GET | `/{id}` | 200 `ApiTokenDTO` | 404 |
| POST | `/{id}/rotate` | 200 `ApiTokenCreatedDTO` — new plaintext, shown once | 404 |
| DELETE | `/{id}` | 204 — soft revoke (`revoked_at`); the row stays auditable, the token is dead → 401 on use | 404 |

A request carrying an unknown, expired, or revoked `w4a_` token gets one indistinguishable **401** (`"Invalid, expired or revoked API token"`). Each hashed token is rate-limited to **30 requests per 1000 ms window** (server-configurable); exceeding it returns **429** with a `Retry-After` header. Legacy JWT tokens (`/auth/token/named`) keep working but are deprecated.

### Work items API (`/api/v1/work-items`)

| Method | Path | Success | Errors |
|---|---|---|---|
| GET | `?projectId=&status=&label=&assignee=` | 200 `List<WorkItemDTO>` (unpaged; filters combine with AND; label matched as a whole token) | 400 unknown status; 403 public project without role; 404 unknown/foreign-private project |
| GET | `/{id}` | 200 `WorkItemDTO` | 404 (foreign private project → 404, existence not revealed) |
| POST | `/` | 201 `WorkItemDTO` — starts in `backlog` unless another legal start state is given | 400 validation or `status: "claimed"` (only the claim endpoint produces it); 403; 404; 409 duplicate `external_ref` (globally unique) or create straight into `done` (`RESULT_COMMENT_REQUIRED`) |
| PUT | `/{id}` | 200 `WorkItemDTO` — PATCH-like partial update; `claimed` is **not** a legal update target | **409 illegal transition** (message lists the legal targets) / `RESULT_COMMENT_REQUIRED` on `review → done` without a result comment; 403; 404 |
| DELETE | `/{id}` | 204 | 403; 404 |
| POST | `/{id}/claim` | 200 `WorkItemClaimDTO {claimToken, leaseExpiresAt, assigneeActorId}` — **atomic**: a single conditional update on `status = 'ready'`; first writer wins | **409** item no longer `ready`; 403 credential names no actor; 404 |
| POST | `/{id}/lease` | 200 `WorkItemClaimDTO` — extends the lease of the item you hold (optional body `{"claimToken": "…"}` must match when sent); TTL is a server setting (`wiki4ai.work-items.lease-minutes`, default 45) | 403 not the holder / token mismatch; 409 no lease to extend; 404 |

Comments and events hang off the item:

| Method | Path | Success | Errors |
|---|---|---|---|
| POST | `/{id}/comments` | 201 `WorkItemCommentDTO` — `kind` (`note` \| `status` \| `blocker` \| `result` \| `handoff` \| `decision`) + `body` + optional `payload`; author comes from the credential, never the body | 400 missing body/kind; **422** payload breaks the kind's contract (message names the expected keys); 403 no actor; 404 |
| GET | `/{id}/comments` | 200 `Page<WorkItemCommentDTO>` (oldest first) | 403; 404 |
| GET | `/{id}/events` | 200 `Page<WorkItemEventDTO>` — the append-only timeline (`created`, `status_changed`, `claimed`, `claim_released`, `lease_extended`, `lease_expired`, `stale_claim_cleared`, `deleted`) | 403; 404 |

Comments and events are **append-only**: the API exposes no update or delete, and a database trigger refuses `UPDATE` outright.

### Resources & reservations API

| Method | Path | Success | Errors |
|---|---|---|---|
| GET | `/api/v1/resources` | 200 `List<ResourceDTO>` (capacity + active slots) | 401 |
| POST | `/api/v1/resources` | 201 `ResourceDTO` | 409 duplicate key |
| GET · PUT · DELETE | `/api/v1/resources/{key}` | 200 / 200 / 204 | 404 unknown key |
| GET | `/api/v1/resources/{key}/reservations` | 200 `List<ReservationDTO>` (unreleased) | 404 |
| POST | `/api/v1/resources/{key}/reserve` | **201** `ReservationDTO` — body `{slots?, reason?, workItemId?}`; the capacity check is part of one conditional UPDATE, so concurrent claims of the last slot cannot both win | **409** resource disabled or out of capacity (message says which); 400 `slots` not a positive integer; 403 no actor; 404 unknown key |
| POST | `/api/v1/reservations/{id}/release` | 204 — **idempotent**: a second release is still 204 and lowers usage exactly once | 403 owned by another actor; 404 |

Expired reservations stop holding capacity on their own — a scheduled reaper releases them (same pattern as the work-item lease reaper).

### Actor metrics (`/api/v1/metrics/actors`)

`GET /api/v1/metrics/actors?from=&to=&scope=&projectId=` → 200 `ActorMetricsResponseDTO`: time-in-state per actor, cycle time (created → done), completed/blocked counts, and expired-lease counts — all derived **exclusively from `work_item_events`**. `from`/`to` accept `2026-10-01` or `2026-10-01T08:00:00` (a bare `to` means end of that day); `scope` is `all` \| `self` \| `project` (`project` requires `projectId`). Your own metrics are open to any authenticated caller; someone else's require ADMIN. Errors: 400 bad `from`/`to`/`scope`/`projectId` format (naming the parameter), 403 credential without actor and non-ADMIN, 404 unknown or foreign-private `projectId`.

## Admin API (`/api/v1/admin`) — ADMIN role required (403 otherwise)

| Method | Path | Success | Errors | Notes |
|---|---|---|---|---|
| GET | `/users?page=0&size=20&search=...` | 200 `Page<UserDTO>` (default size 20, sorted by id; case-insensitive username/email search) | 401/403 | |
| POST | `/users` | 201 `UserDTO` | 409 username or email exists; 400 validation | body `{username (2–50), email, password (≥8), role: "USER"\|"ADMIN"}` |
| PUT | `/users/{id}/role` | 200 `UserDTO` | 404 user not found | body `{"role": "USER" \| "ADMIN"}` |
| DELETE | `/users/{id}` | 204 | **400 cannot delete your own account**; 404 | |
| GET | `/integrity` | 200 row counts + canonical content checksums (slug-ordered — identical instances produce identical checksums) | 401/403 | used to verify data migrations source vs target |

### Embeddings backfill (`/api/v1/admin/embeddings`)

| Method | Path | Success | Errors |
|---|---|---|---|
| POST | `/backfill?force=false` | **202** `{"status":"started","force":false,"message":"Backfill running in the background; poll /api/v1/admin/embeddings/backfill/status"}` | 409 a backfill is already running (single-flight); 403 non-admin; 401 |
| GET | `/backfill/status` | 200 `{running, force, total, processed, embedded, skipped, failed, lastError, startedAt, finishedAt}` | 403; 401 |

Semantics: the backfill walks **all** documents in batches of 4; `skipped` = documents that already have an embedding (unless `force=true`, which re-embeds everything); `failed` counts documents whose batch errored (the run continues and records `lastError`); when finished, `processed == total`. Full details in [[Search & Embeddings]].

## Other endpoints

| Method | Path | Access | Notes |
|---|---|---|---|
| GET | `/api/health` | public | `{"status":"UP","application":"wiki4ai-backend","version":"0.0.1-SNAPSHOT"}` |
| GET | `/actuator/health`, `/actuator/info` | public | Spring Actuator (details always shown for health) |
| GET | `/api/v1/migration/export` | JWT | Full instance export as JSON: projects (with hierarchy), documents (title+body+slug), links, usernames — no secrets, no embeddings |
| POST | `/api/v1/migration/import` | **ADMIN** | Body = exact output of export. `mode=merge` (default) creates only what is missing; `mode=full` wipes all wiki content first and requires `confirmFullRestore=true`. Response includes ID mappings + integrity report |

## Error model (`GlobalExceptionHandler`)

All errors are JSON with at least `timestamp`, `status`, `error`. Mapping of exceptions to HTTP status:

| Status | Trigger | Body shape (beyond timestamp/status/error) |
|---|---|---|
| 400 | Bean validation failure on a `@Valid` body (`MethodArgumentNotValidException`) | `errors: {field: message}` per field |
| 400 | Service-layer `BadRequestException` (e.g. hierarchy depth/cycle, missing required update field) | `message` |
| 400 | `ContentEditException` (ambiguous or absent find/replace) | `message`, `editIndex`, `occurrences` |
| 400 | Malformed multipart request (`MultipartException`) | `message: "Invalid file upload request: ..."` |
| 401 | Missing/absent JWT on a protected endpoint (security entry point) | `{"message":"Authentication required"}` |
| 401 | Invalid or expired JWT (auth filter, not the advice) | `{"error":"Invalid or expired JWT token"}` |
| 401 | Unknown, expired, or **revoked** hashed `w4a_` API token — one indistinguishable answer for all three (auth filter) | `{"error":"Invalid, expired or revoked API token"}` |
| 403 | `AccessDeniedException` — missing project permission; also `RegistrationDisabledException` and `SetupAlreadyCompletedException` (generic messages by design — they must not leak instance state) | `message` |
| 404 | `EntityNotFoundException` (unknown project/document/user/token/image/actor/work item/resource) | `message` |
| 405 | Unsupported HTTP method on a known path | `allow: "GET, POST, ..."` + `Allow` response header |
| 409 | `DocumentVersionConflictException` (explicit `expectedVersion` mismatch) | `currentVersion`, `expectedVersion`, `message` |
| 409 | JPA optimistic-locking failures (`StaleObjectStateException`, `ObjectOptimisticLockingFailureException`, `OptimisticLockException`) — the safety-net path | `currentVersion` (re-read from DB), `message` |
| 409 | Coordination conflicts: work item no longer `ready` (lost the claim race), illegal state transition (legal targets listed), `RESULT_COMMENT_REQUIRED` on entering `done`, duplicate actor handle / `external_ref` / resource key, resource disabled or out of capacity | `message` (the `RESULT_COMMENT_REQUIRED` and capacity messages start with a machine-readable token) |
| 422 | `CommentContractException` — a work-item comment payload understood but violating its kind's contract | `message` naming the kind and the expected keys |
| 429 | Per-token rate limit exceeded (`RateLimitExceededException`) — the credential itself still works | `message`, `retryAfterSeconds` + **`Retry-After` response header** |
| 413 | Upload exceeds multipart limit (`MaxUploadSizeExceededException`) or image > 10 MB | `message` |
| 500 | Any unhandled exception (logged server-side) | `message` |

Controller-level conflicts are also expressed as 409 with a single `error` field: duplicate project name on create, duplicate username/email on register/profile update/admin user create.

## Swagger / OpenAPI

Served by SpringDoc from the same backend process:

- **UI:** `http://<host>:<backend-port>/swagger-ui/index.html` (also via the frontend nginx proxy at `/swagger-ui/`)
- **Spec:** `http://<host>:<backend-port>/v3/api-docs` (OpenAPI 3 JSON, ~60 KB; also proxied at `/v3/api-docs/`)

Access: **any authenticated user** — the paths are not in the public matcher list, so anonymous requests get 401 and a valid JWT of any role gets 200. The OpenAPI document is generated from the `@Tag`/`@Operation`/`@ApiResponse` annotations on the controllers.
