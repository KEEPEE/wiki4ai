/**
 * WIKI4AI-163 / WIKI4AI-164 / WIKI4AI-165: API service of the ACTOR side of the
 * coordination layer (agents, their tokens, their inbox marker, their metrics).
 *
 * Why a second service file and not more functions inside `boardApi.ts`:
 * `boardApi.ts` is the read model of the board — work items, their timeline and
 * comments, actors, resources — and its defining feature is the `toWorkItem()`
 * allow-list that keeps the `claimToken` secret out of the app (WIKI4AI-177).
 * Everything this file adds is a MUTATION of an actor's credential or a read of an
 * actor-scoped counter. Those are different concerns and different failure modes
 * (403/404 ownership rules, one-time plaintext), so they live here. This is NOT a
 * second HTTP layer: every call goes through the same `apiClient` (JWT + 401 refresh)
 * and page-shape normalization is the SAME `unwrapPage()` that `boardApi.ts` exports.
 * Token listing/rotation/revocation is not re-implemented here either — it is reused
 * from `tokenApi.ts`, which already owns `/api/v1/tokens` (WIKI4AI-135..138).
 *
 * Endpoints (1:1, no invented paths):
 *   listActorTokens()        -> GET    /api/v1/tokens                 (via tokenApi)
 *   rotateActorToken(id)     -> POST   /api/v1/tokens/{id}/rotate     (via tokenApi)
 *   revokeActorToken(id)     -> DELETE /api/v1/tokens/{id}            (via tokenApi)
 *   revokeAllActorTokens(id) -> DELETE /api/v1/actors/{id}/tokens
 *   getUnseenEvents(id)      -> GET    /api/v1/actors/{id}/inbox/unseen
 *   markInboxSeen(id)        -> POST   /api/v1/actors/{id}/inbox/mark-seen  (no body)
 *   getActorMetrics(from,to) -> GET    /api/v1/metrics/actors?from=&to=
 *   listProjectActors(pid)   -> GET    /api/v1/projects/{pid}/actors  (project role)
 *
 * ── SECURITY (WIKI4AI-168 / WIKI4AI-177) ─────────────────────────────────────
 * `GET /api/v1/tokens` answers with `ApiTokenDTO`, which carries NEITHER the
 * plaintext nor the hash — only `prefix`, the first 8 characters of the random part.
 * The plaintext exists only in the body of `POST /tokens` and `POST /tokens/{id}/rotate`.
 * This service passes that value to the caller and nowhere else: it is not written to
 * localStorage, not logged, not cached by react-query (the rotate mutation's result is
 * read once by the page and dropped on unmount). Nothing here can turn a prefix back
 * into a token, and no function of this file returns a token for a listing.
 */

import { apiDelete, apiGet, apiPost } from './apiClient';
import { unwrapPage, type ActorDTO } from './boardApi';
import {
  deleteToken,
  listTokens,
  rotateToken,
  type TokenCreated,
  type TokenInfo,
} from './tokenApi';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

// Re-exported so a page can import every token type from one place; the functions
// themselves stay in `tokenApi.ts` (one owner per endpoint).
export type { TokenInfo, TokenCreated };
export { deleteToken as revokeActorToken, rotateToken as rotateActorToken };

// ── Vocabulary ───────────────────────────────────────────────────────────────

/** `project_actors.role` — the four roles the backend validates (WIKI4AI-132). */
export const PROJECT_ACTOR_ROLES = ['viewer', 'worker', 'planner', 'admin'] as const;
export type ProjectActorRole = (typeof PROJECT_ACTOR_ROLES)[number];

/** `actors.kind` — what kind of being an actor is (WIKI4AI-131). */
export const ACTOR_KINDS = ['agent', 'human', 'service'] as const;

// ── DTOs ─────────────────────────────────────────────────────────────────────

/** One row of `project_actors` (GET /api/v1/projects/{projectId}/actors). */
export interface ProjectActorDTO {
  id: number;
  projectId: number;
  /**
   * `project_actors.actor_id`. The column is NOT NULL, so a null here means the API
   * did not name an actor for this row — and "no actor named" is not actor 0. A null
   * row matches no agent on the page (see `ActorMetricsDTO.actorId` for the case where
   * null is a real, meaningful value).
   */
  actorId: number | null;
  /** 'viewer' | 'worker' | 'planner' | 'admin'. */
  role: string | null;
  createdAt: string | null;
}

/**
 * `GET /api/v1/actors/{id}/inbox/unseen` (WIKI4AI-153).
 *
 * The DTO is serialized with `@JsonInclude(NON_NULL)`, so `eventIds`,
 * `lastSeenEventId` and `newestEventId` can be ABSENT from the JSON. Absent is not
 * zero: `lastSeenEventId == null` means "this actor has never read its inbox", and a
 * UI that read it as 0 would silently change the meaning of the number.
 */
export interface UnseenEventsDTO {
  count: number;
  /** Capped list of unseen ids (oldest first). Absent when the server sent none. */
  eventIds: number[];
  /** Current `actors.last_seen_event_id`. Null/absent = never read. */
  lastSeenEventId: number | null;
  /** Top of this actor's event stream. Null/absent when the actor has no events. */
  newestEventId: number | null;
}

/** One actor's activity in the window — every number is the backend's, WIKI4AI-147. */
export interface ActorMetricsDTO {
  /**
   * `ActorMetricsDTO.actorId` is a Java `Long`, and `ActorMetricsService` groups events
   * with `byActor.computeIfAbsent(event.getActorId(), …)` — a map whose keys may be
   * null. So a row with `actorId === null` is a REAL row: the events in it carry no
   * attributed actor (a human acting through a legacy JWT, a reaper-written event).
   *
   * Null must stay null. Rendering it as `0` would claim an actor with id 0 exists,
   * which is a number the API never returned.
   */
  actorId: number | null;
  eventCount: number;
  createdCount: number;
  claimedCount: number;
  completedCount: number;
  blockedCount: number;
  /** Leases this actor was holding when the reaper took them back. */
  leaseExpiredCount: number;
  leaseExtendedCount: number;
  /** Seconds the actor's items spent in each state, keyed by state name. */
  statusSeconds: Record<string, number>;
  totalStatusSeconds: number;
  cycleCount: number;
  totalCycleSeconds: number;
  averageCycleSeconds: number;
}

/** The `summary` block of the same response — totals, not UI sums. */
export interface ActorMetricsSummaryDTO {
  eventCount: number;
  createdCount: number;
  claimedCount: number;
  completedCount: number;
  blockedCount: number;
  leaseExpiredCount: number;
  leaseExtendedCount: number;
  cycleCount: number;
  totalCycleSeconds: number;
  averageCycleSeconds: number;
  totalStatusSeconds: number;
  statusSeconds: Record<string, number>;
  /** Events written by the V21 backfill: reconstructed history, reported apart. */
  backfilledEventCount: number;
  actorCount: number;
}

/** `GET /api/v1/metrics/actors?from=&to=` (WIKI4AI-147). */
export interface ActorMetricsResponseDTO {
  from: string | null;
  to: string | null;
  /** 'all' (ADMIN) | 'self' (everybody else) — whose numbers these are. */
  scope: string | null;
  projectId: number | null;
  actors: ActorMetricsDTO[];
  summary: ActorMetricsSummaryDTO | null;
}

export interface MetricsWindow {
  /** 'YYYY-MM-DD' or 'YYYY-MM-DDTHH:mm:ss'; anything else is a 400 from the backend. */
  from?: string | null;
  to?: string | null;
}

// ── Response normalization ───────────────────────────────────────────────────

function num(value: unknown, fallback = 0): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function numOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function text(value: unknown): string | null {
  return typeof value === 'string' && value !== '' ? value : null;
}

function recordOfNumbers(value: unknown): Record<string, number> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {};
  const out: Record<string, number> = {};
  for (const [key, raw] of Object.entries(value as Record<string, unknown>)) {
    if (typeof raw === 'number' && Number.isFinite(raw)) out[key] = raw;
  }
  return out;
}

/**
 * Normalize one `UnseenEventsDTO`. Missing keys stay null / empty — the UI is then
 * required to render nothing for them, because the API did not say anything.
 */
export function toUnseenEvents(raw: unknown): UnseenEventsDTO {
  const r = (raw ?? {}) as Record<string, unknown>;
  return {
    count: num(r.count),
    eventIds: Array.isArray(r.eventIds)
      ? r.eventIds.filter((id): id is number => typeof id === 'number' && Number.isFinite(id))
      : [],
    lastSeenEventId: numOrNull(r.lastSeenEventId),
    newestEventId: numOrNull(r.newestEventId),
  };
}

/** Normalize one `ActorMetricsDTO`. A field the API omitted becomes 0 / {} — never a guess. */
export function toActorMetrics(raw: unknown): ActorMetricsDTO {
  const r = (raw ?? {}) as Record<string, unknown>;
  return {
    // `numOrNull`, not `num`: a null actorId means "no actor attributed to these
    // events" and must not become the id 0.
    actorId: numOrNull(r.actorId),
    eventCount: num(r.eventCount),
    createdCount: num(r.createdCount),
    claimedCount: num(r.claimedCount),
    completedCount: num(r.completedCount),
    blockedCount: num(r.blockedCount),
    leaseExpiredCount: num(r.leaseExpiredCount),
    leaseExtendedCount: num(r.leaseExtendedCount),
    statusSeconds: recordOfNumbers(r.statusSeconds),
    totalStatusSeconds: num(r.totalStatusSeconds),
    cycleCount: num(r.cycleCount),
    totalCycleSeconds: num(r.totalCycleSeconds),
    averageCycleSeconds: num(r.averageCycleSeconds),
  };
}

/** Normalize the whole metrics response, window included. */
export function toActorMetricsResponse(raw: unknown): ActorMetricsResponseDTO {
  const r = (raw ?? {}) as Record<string, unknown>;
  const summary = r.summary;
  return {
    from: text(r.from),
    to: text(r.to),
    scope: text(r.scope),
    projectId: numOrNull(r.projectId),
    actors: Array.isArray(r.actors) ? r.actors.map(toActorMetrics) : [],
    summary:
      summary && typeof summary === 'object'
        ? {
            eventCount: num((summary as Record<string, unknown>).eventCount),
            createdCount: num((summary as Record<string, unknown>).createdCount),
            claimedCount: num((summary as Record<string, unknown>).claimedCount),
            completedCount: num((summary as Record<string, unknown>).completedCount),
            blockedCount: num((summary as Record<string, unknown>).blockedCount),
            leaseExpiredCount: num((summary as Record<string, unknown>).leaseExpiredCount),
            leaseExtendedCount: num((summary as Record<string, unknown>).leaseExtendedCount),
            cycleCount: num((summary as Record<string, unknown>).cycleCount),
            totalCycleSeconds: num((summary as Record<string, unknown>).totalCycleSeconds),
            averageCycleSeconds: num((summary as Record<string, unknown>).averageCycleSeconds),
            totalStatusSeconds: num((summary as Record<string, unknown>).totalStatusSeconds),
            statusSeconds: recordOfNumbers((summary as Record<string, unknown>).statusSeconds),
            backfilledEventCount: num((summary as Record<string, unknown>).backfilledEventCount),
            actorCount: num((summary as Record<string, unknown>).actorCount),
          }
        : null,
  };
}

/** One `project_actors` row. `role` stays null when the API did not name one. */
export function toProjectActor(raw: unknown): ProjectActorDTO {
  const r = (raw ?? {}) as Record<string, unknown>;
  return {
    id: num(r.id),
    projectId: num(r.projectId),
    // An unnamed actor stays unnamed: `num()` would turn it into actor 0.
    actorId: numOrNull(r.actorId),
    role: text(r.role),
    createdAt: text(r.createdAt),
  };
}

// ── Tokens of an actor ───────────────────────────────────────────────────────

/**
 * Allow-list copy of one token row — the same technique `boardApi.toWorkItem()` uses
 * for `claimToken` (WIKI4AI-177). `ApiTokenDTO` carries no secret today, but a
 * listing is exactly where a future field would leak silently: copying the row field
 * by field means a field this function does not copy cannot reach a component, a
 * `title` or a `data-*` attribute. A plaintext or a hash is therefore never copied,
 * whatever the endpoint happens to send.
 */
export function toTokenInfo(raw: unknown): TokenInfo {
  const r = (raw ?? {}) as Record<string, unknown>;
  return {
    id: num(r.id),
    name: typeof r.name === 'string' ? r.name : '',
    /** Display prefix only — the first 8 characters of the random part. */
    prefix: typeof r.prefix === 'string' ? r.prefix : null,
    scopes: Array.isArray(r.scopes)
      ? r.scopes.filter((scope): scope is string => typeof scope === 'string')
      : [],
    actorId: numOrNull(r.actorId),
    expiresAt: text(r.expiresAt),
    revokedAt: text(r.revokedAt),
    lastUsedAt: text(r.lastUsedAt),
    createdAt: text(r.createdAt) ?? '',
    hashed: r.hashed === true,
  };
}

/**
 * Every API token of the caller, as the read endpoint returns it: `prefix` only.
 *
 * There is no `GET /api/v1/actors/{id}/tokens` on the backend — the listing is
 * per-credential (`GET /api/v1/tokens`) and each row carries its own `actorId`.
 * Grouping by that field is a filter on data the API sent, not an assumption about
 * which token "belongs" to which agent.
 */
export async function listActorTokens(): Promise<TokenInfo[]> {
  const rows = await listTokens();
  return Array.isArray(rows) ? rows.map(toTokenInfo) : [];
}

/**
 * Tokens that act as one actor. `actorId` is the backend's own binding (WIKI4AI-135);
 * a token with `actorId === null` is unbound and therefore belongs to no agent here.
 */
export function tokensOfActor(tokens: TokenInfo[], actorId: number): TokenInfo[] {
  return tokens.filter((token) => token.actorId === actorId);
}

/**
 * Kill switch: revoke every token of one actor at once (204, idempotent — a second
 * call is 204 again and changes zero rows). Owner or ADMIN, otherwise 404.
 */
export function revokeAllActorTokens(actorId: number): Promise<void> {
  // `apiDelete` is the client helper that treats 204 as success, which is exactly
  // what this endpoint answers.
  return apiDelete<void>(`${API_BASE_URL}/actors/${actorId}/tokens`);
}

// ── Inbox marker (WIKI4AI-165) ───────────────────────────────────────────────

/**
 * `GET /api/v1/actors/{id}/inbox/unseen`.
 *
 * Strictly actor-scoped: `requireInboxAccess` answers 403 for another actor's inbox,
 * and a caller with no actor at all (a legacy JWT) has no inbox to ask for. Callers
 * must check `whoami.actorId !== null` before calling this — see `useUnseenEvents`.
 */
export async function getUnseenEvents(actorId: number): Promise<UnseenEventsDTO> {
  const body = await apiGet<unknown>(`${API_BASE_URL}/actors/${actorId}/inbox/unseen`);
  return toUnseenEvents(body);
}

/**
 * `POST /api/v1/actors/{id}/inbox/mark-seen` with NO body = "everything I have been
 * shown is seen". The backend stamps `actors.last_seen_event_id` with the top of the
 * actor's stream; the body form (`{"eventId": 123}`) exists for partial reads and is
 * deliberately not used by the UI marker, because the marker shows a count, not a
 * position.
 */
export function markInboxSeen(actorId: number): Promise<void> {
  return apiPost<void>(`${API_BASE_URL}/actors/${actorId}/inbox/mark-seen`);
}

// ── Metrics (WIKI4AI-164) ────────────────────────────────────────────────────

/**
 * `GET /api/v1/metrics/actors?from=&to=`. Omitted boundaries mean the backend's own
 * default window; the response echoes `from`/`to`/`scope` and the page renders that
 * echo, so a number is never shown without the window it belongs to.
 */
export async function getActorMetrics(window: MetricsWindow = {}): Promise<ActorMetricsResponseDTO> {
  const search = new URLSearchParams();
  if (window.from) search.set('from', window.from);
  if (window.to) search.set('to', window.to);
  const query = search.toString();
  const body = await apiGet<unknown>(`${API_BASE_URL}/metrics/actors${query ? `?${query}` : ''}`);
  return toActorMetricsResponse(body);
}

// ── Project role (WIKI4AI-163) ───────────────────────────────────────────────

/**
 * `GET /api/v1/projects/{projectId}/actors` — the only place `project_actors.role`
 * is exposed. `GET /api/v1/actors` does not carry a role, so the agents page asks
 * for it per project and renders "unknown" when the answer is a 403/404 (a private
 * project is a 404 for a foreign caller). It never labels an agent with a role the
 * project did not grant.
 */
export async function listProjectActors(projectId: number): Promise<ProjectActorDTO[]> {
  const body = await apiGet<unknown>(`${API_BASE_URL}/projects/${projectId}/actors`);
  return unwrapPage<unknown>(body).map(toProjectActor);
}

/** Role of one actor inside one project, or null when the project did not grant one. */
export function roleOfActor(
  projectActors: ProjectActorDTO[],
  actorId: number,
): string | null {
  // A row whose actorId is null names no actor, so it can never answer for an id —
  // and an id that is not a number can never answer for a row.
  if (!Number.isFinite(actorId)) return null;
  const row = projectActors.find((pa) => pa.actorId === actorId);
  return row ? row.role : null;
}

// ── Convenience for the agents page ──────────────────────────────────────────

/** Actor + the rows the page needs next to it, all from the API. */
export interface AgentRow {
  actor: ActorDTO;
  tokens: TokenInfo[];
  role: string | null;
  /** From `ActorMetricsDTO.leaseExpiredCount` — the UI does not count leases. */
  leaseExpiredCount: number | null;
}
