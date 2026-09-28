# Calendar

The calendar is a **global** module of wiki4ai — it is not attached to any project. Events belong to the **user who created them** (`created_by`), and every authenticated user can create events, see public events of everyone, and manage their own. The WebUI exposes it at `/calendar`; the REST API lives under `/api/v1/calendar`; AI agents get six dedicated MCP tools (see [MCP tools](#mcp-tools)).

## Overview & visibility

| Aspect | Behavior |
|---|---|
| Scope | Global — events are **not** project-scoped; there is no per-project calendar |
| Ownership | Every event records its creator (`created_by` → `users.id`); the creator can always update/delete it |
| Visibility | Each event is **`public`** (default) or **`private`** |
| `public` events | Visible to **every authenticated user**; only the owner or an ADMIN may modify them |
| `private` events | Visible **only to their creator and ADMIN users**. For anyone else, detail/update/delete return **404** (existence is deliberately not revealed) and list endpoints simply omit them |
| ADMIN role | Sees all events regardless of visibility; can update/delete any event |

This mirrors the public/private visibility model introduced for projects and documents in migration V14 — but calendar events carry their own `visibility` column, independent of project permissions. See [[Data Model]] for the tables.

## WebUI views (`/calendar`)

The calendar page has two views, switched with the **Mesiac / Week** toggle (the choice is reflected in the URL as `?view=week`). Navigation arrows step by one month (month view) or one week (week view).

### Month view (default)

A classic monthly grid: weekday header row, six rows of day cells. Each event renders as a **pill colored by its event type**; long titles are truncated with an ellipsis and the full title is available as a tooltip on hover. Clicking a day cell opens the create form prefilled with that date.

![Calendar month view with color-coded event pills](/images/wiki4ai/9675ade5-5d4f-45e9-81e2-f886b842bba0.png)

On narrow (mobile) widths the grid collapses into a compact single-column layout where each day shows its number and its events as stacked pills:

![Calendar month view on a mobile-width viewport](/images/wiki4ai/556dbfad-e4d7-424b-b24e-fbc881dd637d.png)

### Week view

The **Week** toggle switches to a time-grid: **7 day columns × 24 hourly rows** (00–23, one row per hour). The layout rules:

- **All-day events** live in a dedicated strip above the time grid — they never occupy an hour slot.
- **Timed events** are absolutely positioned blocks at their exact start/end position within the day column; overlapping events are laid out **side by side** in the same column (widths shrink to fit).
- The grid opens scrolled so that the **≈08:00** hour line sits near the top of the viewport — working hours are visible immediately.

Clicking a day header or an empty time slot opens the create form prefilled with that date (and, for slot clicks, the clicked hour as start time).

![Calendar week view: 7×24 hourly grid with an all-day strip and positioned timed blocks](/images/wiki4ai/11c39336-3a5d-4cf8-b803-00799cb11617.png)

### Create / edit form

The event form is a modal with **Title** (required), **Date** (required), **Start time** / **End time** (optional — leave both empty for an all-day event), **Event type** (dropdown of all types), **Visibility** (public/private toggle) and **Description**. The same form handles editing; deleting is a separate confirm action available on the event.

![Create-event form with date, times, type and visibility fields](/images/wiki4ai/87388872-7ab0-4ed1-a36b-58c9f74b3f61.png)

## Event types

Event types are **extensible at runtime** — new types can be added from the WebUI, via the REST API, or through MCP; there is no fixed set. Two types are seeded by migration V13:

| Type | Color | Purpose |
|---|---|---|
| `Agent task` | `#4f8cff` (blue) | Tasks an AI agent works on |
| `Pripomienka` | `#f59e0b` (amber) | Notes / reminders |

The **type's color is the pill/block color** in both views — that is the only place the color is used. Type names are unique case-insensitively; a type that still has events assigned to it cannot be deleted (the API returns 409 with the event count).

## All-day vs timed events

The distinction is made by `start_time`:

| Kind | `start_time` | Meaning | Rendering |
|---|---|---|---|
| **All-day** | `NULL` | The event occupies the whole calendar day (`event_date` only) | Month: pill in the day cell. Week: all-day strip above the grid |
| **Timed** | non-`NULL` | Precise time on that day; `end_time` is optional and may be omitted (the block then has no fixed height) | Month: pill with a time prefix. Week: positioned block at its exact hour position |

Times are `TIME` values (`HH:mm:ss`). To turn a timed event back into an all-day one, the update must send the explicit `clearTime: true` flag — in PATCH-like semantics, a plain `null` means "no change", so omitting the times would keep them.

## REST API (`/api/v1/calendar`)

All endpoints require a **JWT** (they are not under the public read matcher). Verified against `CalendarEventController` / `CalendarEventService`:

| Method | Path | Access | Success | Errors |
|---|---|---|---|---|
| GET | `/events?from=&to=&type=&mine=` | JWT | 200 `List<CalendarEventDTO>` | 400 invalid date or `from` > `to` |
| GET | `/events/{id}` | JWT, visibility-checked | 200 `CalendarEventDTO` | 404 not found **or someone else's private event** (existence hidden) |
| POST | `/events` | JWT | 201 `CalendarEventDTO` — creator = the authenticated user | 400 missing title/date, unknown type (error lists available types), invalid visibility |
| PUT | `/events/{id}` | JWT, **owner or ADMIN** | 200 `CalendarEventDTO` — PATCH-like: omitted fields unchanged | 403 foreign public event; 404 foreign private event; 400 validation |
| DELETE | `/events/{id}` | JWT, **owner or ADMIN** | 204 | 403 foreign public event; 404 foreign private event |
| GET | `/event-types` | JWT | 200 `List<EventTypeDTO>` ordered by id (seed types first) | — |
| POST | `/event-types` | JWT | 201 `EventTypeDTO` | 400 missing name; **409 duplicate name** (case-insensitive) |
| DELETE | `/event-types/{id}` | JWT | 204 | 404 unknown type; **409 type still used by N events** |

**List semantics (`GET /events`).** `from`/`to` are inclusive `YYYY-MM-DD` dates; when omitted the **current month** is the default range. `type` filters by event-type name (case-insensitive exact match — unknown names yield an empty list). `mine=true` returns only the caller's own events in both visibilities. Visibility: without `mine`, an ADMIN sees everything, a regular user sees public events plus their own private ones. Results are ordered by date, then time — **all-day events first** on each day, timed events afterwards by start time.

**DTOs:**

- `CalendarEventDTO`: `{id, title, description, eventTypeId, eventType (name, denormalized), eventColor (hex, nullable), eventDate, startTime (null = all-day), endTime, visibility, createdBy (username), createdAt, updatedAt}`
- `EventTypeDTO`: `{id, name, color (nullable hex), createdAt}`

`POST /events` body: `title` and `eventDate` required; `eventType` accepts **either the numeric id or the type name** (case-insensitive); `visibility` defaults to `public`; `startTime`/`endTime` optional.

## MCP tools

Six of the MCP server's 38 tools cover the calendar (see [[MCP Server]] for transports and identity model):

| Tool | What it does |
|---|---|
| `calendar_create_event` | Create an event: title + date required; optional start/end time, description, type name (defaults to the first available type), visibility (`public` default) |
| `calendar_list_events` | List events in a date range (default: current month) with optional type filter and `mine=true`; returns public events plus the caller's own private ones (ADMIN sees all), all-day first |
| `calendar_update_event` | Partial update — only provided fields change; includes the explicit `clear_time=True` flag to revert a timed event to all-day. Owner or ADMIN only |
| `calendar_delete_event` | Delete an event permanently. Owner or ADMIN only (foreign private → 404, foreign public → 403) |
| `calendar_list_event_types` | List all event types with id, name and color (seed types first) — call before creating events to discover valid type names |
| `calendar_create_event_type` | Create a new event type with an optional hex color; duplicate names are rejected with 409 |

Because the MCP server acts as your identity JWT, an agent's calendar tools obey exactly the same visibility and ownership rules as the REST API.

## Database

Two tables (migration **V13** `create_calendar`):

- **`calendar_events`** — id, title, description, event_type_id (FK), event_date, start_time / end_time (nullable `TIME`), visibility (`public`/`private`, default `public`), created_by (FK → users), audit columns. Indexed on `event_date`, `event_type_id` and `created_by`.
- **`event_entity_types`** — id, name (unique), color (nullable hex), created_at; seeded with `Agent task` (#4f8cff) and `Pripomienka` (#f59e0b).

Full column reference in [[Data Model]]; the REST/MCP surface is summarized above and in [[Backend API Reference]].

## Related documents

- [[Home]] — project overview
- [[WebUI Guide]] — the rest of the web interface, screen by screen
- [[Backend API Reference]] — complete REST endpoint reference
- [[MCP Server]] — all 38 MCP tools, transports and security model
- [[Data Model]] — tables, columns and migrations
