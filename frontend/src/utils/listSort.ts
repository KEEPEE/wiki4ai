/**
 * WIKI4AI-187: the sort/filter vocabulary shared by the project list (Dashboard)
 * and the document list (ProjectDetail).
 *
 * The backend (WIKI4AI-184) owns the ordering: `GET /projects` and
 * `GET /projects/{slug}/documents` both take `sort`, `q` and (projects only)
 * `visibility`. The UI therefore never re-sorts a list client-side — it sends
 * the user's choice as a query parameter and renders what comes back. The only
 * client-side ordering left is the pinned-first float of `utils/pinSort`, which
 * mirrors what the server already did.
 *
 * Keys are exactly the backend vocabulary (`backend/.../ListSort.java`); an
 * unknown value is a 400 there, so the UI must not invent its own.
 */

/** One of the `sort` keys accepted by both list endpoints. */
export type ListSortKey =
  | 'manual'
  | 'name_asc'
  | 'name_desc'
  | 'created_asc'
  | 'created_desc'
  | 'updated_desc';

/** `visibility` filter of `GET /projects` — empty string means "no filter". */
export type VisibilityFilter = '' | 'public' | 'private';

/** Defaults keep the pre-WIKI4AI-187 behaviour of each list. */
export const DEFAULT_PROJECT_SORT: ListSortKey = 'created_desc';
export const DEFAULT_DOCUMENT_SORT: ListSortKey = 'updated_desc';

/** localStorage keys of the per-user preferences (requirement: persist choice). */
export const PROJECT_SORT_STORAGE_KEY = 'wiki4ai-projects-sort';
export const DOCS_SORT_STORAGE_KEY = 'wiki4ai-docs-sort';

/**
 * The five entries of the sort menu. `created_asc` exists on the backend but is
 * not offered — the story asks for exactly these five and the menu stays
 * readable. `updated_desc` is offered for projects too (the backend supports
 * it) so the same menu works on both pages.
 */
export const SORT_OPTIONS: ListSortKey[] = [
  'manual',
  'name_asc',
  'name_desc',
  'created_desc',
  'updated_desc',
];

/** i18n key of a sort option (label shown in the menu). */
export const SORT_LABEL_KEY: Record<ListSortKey, string> = {
  manual: 'listSort.manual',
  name_asc: 'listSort.nameAsc',
  name_desc: 'listSort.nameDesc',
  created_asc: 'listSort.createdAsc',
  created_desc: 'listSort.createdDesc',
  updated_desc: 'listSort.updatedDesc',
};

const SORT_KEYS = new Set<string>(SORT_OPTIONS.concat('created_asc'));

/** Guard against a stale/garbage value in localStorage. */
export function isListSortKey(value: unknown): value is ListSortKey {
  return typeof value === 'string' && SORT_KEYS.has(value);
}

/**
 * Read the persisted sort choice. A missing key, a disabled/blocked localStorage
 * (Safari private mode throws on access) or an unknown value all fall back to
 * the endpoint's own default, so the list is never requested with a 400 sort.
 */
export function readStoredSort(storageKey: string, fallback: ListSortKey): ListSortKey {
  try {
    const raw = window.localStorage.getItem(storageKey);
    return raw !== null && isListSortKey(raw) ? raw : fallback;
  } catch {
    return fallback;
  }
}

/** Persist the sort choice; storage errors are non-fatal (session-only choice). */
export function writeStoredSort(storageKey: string, value: ListSortKey): void {
  try {
    window.localStorage.setItem(storageKey, value);
  } catch {
    /* quota / private mode — the preference simply does not survive a reload */
  }
}

/**
 * Build the query string of a list request. Only non-empty values are sent: an
 * empty `q` would ask the backend for "name contains nothing", and an omitted
 * `sort` is the endpoint default, which is what a first visit should get.
 */
export function buildListQuery(params: {
  sort?: string;
  q?: string;
  visibility?: string;
  page?: number;
  size?: number;
}): string {
  const parts: string[] = [];
  // Pagination first: `?page=0&size=50` stays byte-identical to the URL the
  // document list requested before this story, so nothing else has to change.
  if (params.page !== undefined) parts.push(`page=${params.page}`);
  if (params.size !== undefined) parts.push(`size=${params.size}`);
  if (params.sort) parts.push(`sort=${encodeURIComponent(params.sort)}`);
  const q = (params.q ?? '').trim();
  if (q) parts.push(`q=${encodeURIComponent(q)}`);
  if (params.visibility) parts.push(`visibility=${encodeURIComponent(params.visibility)}`);
  return parts.length ? `?${parts.join('&')}` : '';
}

/**
 * True when a filter is active. Drag & drop is only meaningful on the unfiltered
 * manual list (the reorder endpoint writes the manual order of the WHOLE list,
 * while a filtered view shows a subset), so both pages disable DnD while this
 * holds and say why.
 */
export function hasActiveFilter(q: string, visibility?: string): boolean {
  return q.trim().length > 0 || !!visibility;
}
