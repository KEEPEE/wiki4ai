/**
 * WIKI4AI-161 / WIKI4AI-162: API service of the coordination layer.
 *
 * Every function here maps 1:1 onto one documented, live-verified endpoint:
 *
 *   listWorkItems()        -> GET /api/v1/work-items?projectId=&status=&label=&assignee=
 *   getWorkItem(id)        -> GET /api/v1/work-items/{id}
 *   listWorkItemEvents(id) -> GET /api/v1/work-items/{id}/events
 *   listWorkItemComments() -> GET /api/v1/work-items/{id}/comments?kind=&needs_human=
 *   listActors()           -> GET /api/v1/actors
 *   getWhoami()            -> GET /api/v1/actors/whoami
 *   listResources()        -> GET /api/v1/resources
 *   listReservations(key)  -> GET /api/v1/resources/{key}/reservations
 *
 * Filters of /work-items are ANDed by the backend, `label` is matched as a whole
 * token (not a substring) and an unknown `status` is a 400 — so the UI sends the
 * values it received from the API and never invents its own.
 *
 * ── SECURITY (WIKI4AI-177) ───────────────────────────────────────────────────
 * `WorkItemDTO.claimToken` is a 64-character lease secret and the backend still
 * returns it in GET responses (that is the open bug WIKI4AI-177). This service is
 * the single choke point that drops it: `toWorkItem()` copies an explicit allow-list
 * of fields, so the secret never enters the hook layer, never reaches a component
 * and cannot be spread into JSX, a `title` or a `data-*` attribute by accident.
 * The UI shows `leaseExpiresAt` — the lease is a deadline, and a deadline is not a
 * secret.
 */

import { apiGet, apiPost } from './apiClient';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

// ── Vocabulary of the coordination layer (mirrors the backend constants) ─────

/** `work_items.status` — the eight states of the state machine (WIKI4AI-140). */
export const WORK_ITEM_STATUSES = [
  'backlog',
  'ready',
  'claimed',
  'running',
  'review',
  'done',
  'blocked',
  'cancelled',
] as const;

export type WorkItemStatus = (typeof WORK_ITEM_STATUSES)[number];

/** `work_item_comments.kind` — the six kinds (WIKI4AI-148). */
export const COMMENT_KINDS = [
  'status',
  'blocker',
  'result',
  'handoff',
  'note',
  'decision',
] as const;

export type WorkItemCommentKind = (typeof COMMENT_KINDS)[number];

/** `actors.status` — what the presence dot is derived from (WIKI4AI-131). */
export const ACTOR_STATUSES = ['active', 'paused', 'archived'] as const;

export type ActorStatus = (typeof ACTOR_STATUSES)[number];

/** `work_item_events.event` — the timeline vocabulary (WIKI4AI-146). */
export const WORK_ITEM_EVENTS = [
  'created',
  'status_changed',
  'claimed',
  'claim_released',
  'lease_extended',
  'lease_expired',
  'stale_claim_cleared',
  'deleted',
] as const;

// ── DTOs ─────────────────────────────────────────────────────────────────────

/**
 * One work item, as the UI is allowed to see it.
 *
 * Deliberately narrower than the backend DTO: `claimToken` is not a field of this
 * type at all (see the SECURITY note above), so no component can read it even by
 * mistake. `leaseExpiresAt` is the field the board renders instead.
 */
export interface WorkItemDTO {
  id: number;
  projectId: number;
  title: string;
  body: string | null;
  status: string;
  priority: string | null;
  labels: string[];
  parentId: number | null;
  externalRef: string | null;
  assigneeActorId: number | null;
  /** Lease deadline of the current claim, or null when the item is not claimed. */
  leaseExpiresAt: string | null;
  createdByUserId: number | null;
  createdByActorId: number | null;
  updatedByActorId: number | null;
  createdAt: string | null;
  updatedAt: string | null;
  version: number | null;
}

/** One entry of the append-only timeline (GET /work-items/{id}/events). */
export interface WorkItemEventDTO {
  id: number;
  workItemId: number;
  actorId: number | null;
  event: string;
  fromStatus: string | null;
  toStatus: string | null;
  /** The event's own JSON detail, passed through as the string it is stored as. */
  payload: string | null;
  createdAt: string | null;
}

/** One immutable comment (GET /work-items/{id}/comments). */
export interface WorkItemCommentDTO {
  id: number;
  workItemId: number;
  authorActorId: number | null;
  kind: string;
  body: string;
  /** Structured details of the kind; null for a comment that carries no payload. */
  payload: Record<string, unknown> | null;
  createdAt: string | null;
}

/** One actor (GET /actors). Only the fields the board needs are declared. */
export interface ActorDTO {
  id: number;
  handle: string;
  displayName: string | null;
  kind: string | null;
  status: string;
  /** Presence from the last heartbeat: 'idle' | 'working' | 'busy' (WIKI4AI-133). */
  presenceStatus: string | null;
  presenceNote: string | null;
  lastSeenAt: string | null;
}

/** Identity of the caller (GET /actors/whoami). */
export interface ActorWhoamiDTO {
  userId: number | null;
  username: string | null;
  actorId: number | null;
  handle: string | null;
  kind: string | null;
  scopes: string[];
}

/** One resource and its live capacity (GET /resources, WIKI4AI-155). */
export interface ResourceDTO {
  id: number;
  key: string;
  name: string | null;
  kind: string | null;
  capacity: number | null;
  activeSlots: number | null;
  enabled: boolean | null;
  availableSlots: number | null;
  createdAt: string | null;
}

/** One capacity holding (GET /resources/{key}/reservations, WIKI4AI-155..157). */
export interface ReservationDTO {
  id: number;
  resourceId: number;
  resourceKey: string;
  actorId: number | null;
  workItemId: number | null;
  slots: number | null;
  reason: string | null;
  expiresAt: string | null;
  /** Null while the holding is live; omitted from the JSON when null. */
  releasedAt?: string | null;
  createdAt: string | null;
}

// ── Query shapes ─────────────────────────────────────────────────────────────

export interface WorkItemFilters {
  projectId?: number | null;
  status?: string | null;
  /** Whole-token match on the backend — a substring is not a label filter. */
  label?: string | null;
  assignee?: number | null;
}

export interface CommentQuery {
  kind?: string | null;
  /** `needs_human` is a payload filter of kind=blocker (WIKI4AI-151). */
  needsHuman?: boolean | null;
  page?: number;
  size?: number;
}

// ── Response normalization ───────────────────────────────────────────────────

/**
 * `GET /work-items/{id}/events` and `GET /work-items/{id}/comments` answer with a
 * Spring `Page` (`{ content: [...], totalElements: … }`), while `/work-items`,
 * `/actors` and `/resources` answer with a plain array. This helper accepts both
 * shapes and returns the rows; it never synthesizes a row that the API did not send.
 */
export function unwrapPage<T>(body: unknown): T[] {
  if (Array.isArray(body)) return body as T[];
  if (body && typeof body === 'object' && Array.isArray((body as { content?: unknown }).content)) {
    return (body as { content: T[] }).content;
  }
  return [];
}

/**
 * The allow-list copy that keeps the claim secret out of the app (WIKI4AI-177).
 * Fields are read one by one on purpose: a spread of the raw response would carry
 * `claimToken` into the UI, and a UI field is a UI field that can be rendered.
 */
export function toWorkItem(raw: unknown): WorkItemDTO {
  const r = (raw ?? {}) as Record<string, unknown>;
  return {
    id: numberOrNull(r.id) ?? 0,
    projectId: numberOrNull(r.projectId) ?? 0,
    title: typeof r.title === 'string' ? r.title : '',
    body: typeof r.body === 'string' ? r.body : null,
    status: typeof r.status === 'string' ? r.status : '',
    priority: typeof r.priority === 'string' ? r.priority : null,
    labels: Array.isArray(r.labels) ? r.labels.filter((l): l is string => typeof l === 'string') : [],
    parentId: numberOrNull(r.parentId),
    externalRef: typeof r.externalRef === 'string' ? r.externalRef : null,
    assigneeActorId: numberOrNull(r.assigneeActorId),
    leaseExpiresAt: typeof r.leaseExpiresAt === 'string' ? r.leaseExpiresAt : null,
    createdByUserId: numberOrNull(r.createdByUserId),
    createdByActorId: numberOrNull(r.createdByActorId),
    updatedByActorId: numberOrNull(r.updatedByActorId),
    createdAt: typeof r.createdAt === 'string' ? r.createdAt : null,
    updatedAt: typeof r.updatedAt === 'string' ? r.updatedAt : null,
    version: numberOrNull(r.version),
  };
}

function numberOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function qs(params: Record<string, string | number | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && `${value}` !== '') search.set(key, `${value}`);
  }
  const s = search.toString();
  return s ? `?${s}` : '';
}

// ── Endpoints ────────────────────────────────────────────────────────────────

/** GET /api/v1/work-items — filters are ANDed server-side. */
export async function listWorkItems(filters: WorkItemFilters = {}): Promise<WorkItemDTO[]> {
  const url =
    `${API_BASE_URL}/work-items` +
    qs({
      projectId: filters.projectId ?? undefined,
      status: filters.status ?? undefined,
      label: filters.label ?? undefined,
      assignee: filters.assignee ?? undefined,
    });
  const body = await apiGet<unknown>(url);
  return unwrapPage<unknown>(body).map(toWorkItem);
}

/** GET /api/v1/work-items/{id}. 404 for an item the caller cannot see. */
export async function getWorkItem(id: number): Promise<WorkItemDTO> {
  const body = await apiGet<unknown>(`${API_BASE_URL}/work-items/${id}`);
  return toWorkItem(body);
}

/** GET /api/v1/work-items/{id}/events — newest page first; the UI sorts chronologically. */
export async function listWorkItemEvents(
  id: number,
  options: { page?: number; size?: number } = {},
): Promise<WorkItemEventDTO[]> {
  const url =
    `${API_BASE_URL}/work-items/${id}/events` +
    qs({ page: options.page ?? 0, size: options.size ?? 100 });
  return unwrapPage<WorkItemEventDTO>(await apiGet<unknown>(url));
}

/**
 * GET /api/v1/work-items/{id}/comments
 * `kind` is a column filter; `needs_human` is decoded from the payload by the
 * backend (WIKI4AI-151) and is only meaningful together with kind=blocker.
 */
export async function listWorkItemComments(
  id: number,
  options: CommentQuery = {},
): Promise<WorkItemCommentDTO[]> {
  const url =
    `${API_BASE_URL}/work-items/${id}/comments` +
    qs({
      kind: options.kind ?? undefined,
      needs_human: options.needsHuman === undefined ? undefined : String(options.needsHuman),
      page: options.page ?? 0,
      size: options.size ?? 100,
    });
  return unwrapPage<WorkItemCommentDTO>(await apiGet<unknown>(url));
}

/** GET /api/v1/actors — optional status filter. */
export async function listActors(status?: string | null): Promise<ActorDTO[]> {
  const body = await apiGet<unknown>(`${API_BASE_URL}/actors` + qs({ status: status ?? undefined }));
  return unwrapPage<ActorDTO>(body);
}

/** GET /api/v1/actors/whoami — the caller's identity as the credential describes it. */
export async function getWhoami(): Promise<ActorWhoamiDTO> {
  return apiGet<ActorWhoamiDTO>(`${API_BASE_URL}/actors/whoami`);
}

/** GET /api/v1/resources — capacity and live slots of every resource. */
export async function listResources(): Promise<ResourceDTO[]> {
  const body = await apiGet<unknown>(`${API_BASE_URL}/resources`);
  return unwrapPage<ResourceDTO>(body);
}

/** GET /api/v1/resources/{key}/reservations — live and released holdings of one resource. */
export async function listReservations(key: string): Promise<ReservationDTO[]> {
  const body = await apiGet<unknown>(
    `${API_BASE_URL}/resources/${encodeURIComponent(key)}/reservations`,
  );
  return unwrapPage<ReservationDTO>(body);
}

/**
 * WIKI4AI-164: `POST /api/v1/reservations/{id}/release` — manual release, 204.
 *
 * Idempotent by contract: a second call is 204 again and decrements `active_slots`
 * exactly once, so a repeated click must look like a no-op, never like an error. The
 * page disables the button as soon as it has seen that 204. A 404 means the
 * reservation id does not exist, a 403 means another actor owns it — both are shown.
 */
export function releaseReservation(id: number): Promise<void> {
  return apiPost<void>(`${API_BASE_URL}/reservations/${id}/release`);
}
