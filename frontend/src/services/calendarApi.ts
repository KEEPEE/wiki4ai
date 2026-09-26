/**
 * WIKI4AI-97: Calendar API service.
 *
 * Thin typed wrapper over the calendar REST endpoints (WIKI4AI-96) using the
 * shared authenticated apiClient (JWT + 401 refresh). Endpoints:
 *   GET    /api/v1/calendar/events?from=&to=        list (public + own private)
 *   GET    /api/v1/calendar/events/{id}             detail
 *   POST   /api/v1/calendar/events                  create
 *   PUT    /api/v1/calendar/events/{id}             update (PATCH-like; null = no change)
 *   DELETE /api/v1/calendar/events/{id}             delete (owner or ADMIN)
 *   GET/POST/DELETE /api/v1/calendar/event-types    type CRUD
 */

import { apiGet, apiPost, apiPut, apiDelete } from './apiClient';

const BASE = '/api/v1/calendar';

// ── Types (mirror backend DTOs) ──────────────────────────────────────────────

export interface CalendarEventType {
  id: number;
  name: string;
  /** Optional hex color used by the UI (e.g. "#4f8cff"). */
  color: string | null;
  createdAt?: string;
}

export interface CalendarEvent {
  id: number;
  title: string;
  description: string | null;
  eventTypeId: number;
  /** Denormalized type name. */
  eventType: string | null;
  /** Denormalized type color (hex) for pill rendering. */
  eventColor: string | null;
  /** YYYY-MM-DD */
  eventDate: string;
  /** HH:mm, or null = all-day event. */
  startTime: string | null;
  /** HH:mm, optional. */
  endTime: string | null;
  visibility: 'public' | 'private';
  createdBy: string;
  createdAt: string;
  updatedAt: string;
}

export interface CalendarEventCreateInput {
  title: string;
  description?: string;
  /** Type id (preferred) or type name — backend resolves both. */
  eventType: number | string;
  /** YYYY-MM-DD */
  eventDate: string;
  /** HH:mm; omit/null = all-day event. */
  startTime?: string | null;
  endTime?: string | null;
  visibility: 'public' | 'private';
}

export interface CalendarEventUpdateInput {
  title?: string;
  description?: string;
  eventType?: number | string;
  eventDate?: string;
  startTime?: string | null;
  endTime?: string | null;
  visibility?: 'public' | 'private';
  /**
   * WIKI4AI-97: explicit signal to clear the precise time (revert a timed
   * event to all-day). Needed because PUT is PATCH-like — null times mean
   * "no change", so they cannot express "clear".
   */
  clearTime?: boolean;
}

// ── Events ───────────────────────────────────────────────────────────────────

/** List events in [from, to] (inclusive): public events + the caller's private ones. */
export function fetchEvents(from: string, to: string): Promise<CalendarEvent[]> {
  const params = new URLSearchParams({ from, to });
  return apiGet<CalendarEvent[]>(`${BASE}/events?${params.toString()}`);
}

/** Event detail (foreign private events → 404). */
export function fetchEvent(id: number): Promise<CalendarEvent> {
  return apiGet<CalendarEvent>(`${BASE}/events/${id}`);
}

export function createEvent(input: CalendarEventCreateInput): Promise<CalendarEvent> {
  return apiPost<CalendarEvent>(`${BASE}/events`, input);
}

export function updateEvent(id: number, input: CalendarEventUpdateInput): Promise<CalendarEvent> {
  return apiPut<CalendarEvent>(`${BASE}/events/${id}`, input);
}

export function deleteEvent(id: number): Promise<void> {
  return apiDelete<void>(`${BASE}/events/${id}`);
}

// ── Event types ──────────────────────────────────────────────────────────────

export function fetchEventTypes(): Promise<CalendarEventType[]> {
  return apiGet<CalendarEventType[]>(`${BASE}/event-types`);
}

export function createEventType(name: string, color?: string): Promise<CalendarEventType> {
  return apiPost<CalendarEventType>(`${BASE}/event-types`, { name, color });
}
