/**
 * WIKI4AI-186: client-side ordering helpers for pinned projects/documents.
 *
 * The backend already returns every list with pinned entries first — the
 * ordering is `pinned DESC` followed by the active sort key, which defaults to
 * `created_desc` for projects and `updated_desc` for documents (see
 * `backend/.../ListSort.java`, WIKI4AI-184). The client therefore must only
 * float pinned entries to the front and must **preserve the incoming order
 * inside each group**: re-sorting by `sortOrder` here would silently replace
 * the "newest first" list with the manual order the user never asked for.
 *
 * `sortOrder` is OPAQUE: the backend hands out negative values while pinning
 * and renumbers the whole list on a reorder. Never render it, never assume a
 * 0..n range. It is the ordering key of `?sort=manual` only — which is what
 * drag & drop (WIKI4AI-187) will fetch, and `comparePinned` is the comparator
 * for that case.
 */

/** Minimal shape shared by `Project` and `Document` for pin ordering. */
export interface Pinable {
  pinned?: boolean;
  sortOrder?: number;
}

/**
 * Return a new array with pinned entries first, the order of the input kept
 * inside both groups (a stable partition — nothing else is reordered).
 */
export function pinnedFirst<T extends Pinable>(items: T[]): T[] {
  const pinned: T[] = [];
  const rest: T[] = [];
  for (const item of items) {
    (item.pinned ? pinned : rest).push(item);
  }
  return [...pinned, ...rest];
}

/** Missing/opaque sortOrder is treated as the neutral 0 (legacy rows). */
const orderOf = (item: Pinable): number =>
  typeof item.sortOrder === 'number' ? item.sortOrder : 0;

/**
 * Comparator for a `sort=manual` list: pinned first, then by the opaque manual
 * order. Not used by the default lists (those are ordered by the server on
 * another key) — WIKI4AI-187 (drag & drop) is its consumer.
 */
export function comparePinned(a: Pinable, b: Pinable): number {
  if (!!a.pinned !== !!b.pinned) return a.pinned ? -1 : 1;
  return orderOf(a) - orderOf(b);
}
