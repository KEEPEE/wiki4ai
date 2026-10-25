/**
 * WIKI4AI-168: browser-side storage for API tokens — metadata only, never a secret.
 *
 * The backend keeps a SHA-256 digest of every `w4a_…` token plus an 8-character
 * display prefix, and returns the plaintext exactly once, in the create/rotate
 * response. Any copy of that plaintext that outlives the component which received
 * it undoes the hashing: whoever reads browser storage holds the live credential.
 *
 * So localStorage may hold only the token *menu* — id, name, scopes, createdAt,
 * expiresAt, display prefix, `hashed` — and never the token itself.
 *
 * `readStoredTokenMenu()` also repairs storage written by older builds (they did
 * persist `accessToken`): the key is rewritten without the secret on the first
 * read, so an existing installation stops carrying the plaintext immediately.
 */

/** Menu of the user's named API tokens. Metadata only. */
export const STORED_NAMED_TOKENS_KEY = 'wiki4ai_stored_named_tokens';

/** Plaintext of the deprecated quick-generate JWT, persisted by older builds. */
export const LEGACY_GENERATED_TOKEN_KEY = 'wiki4ai_generated_api_token';

/**
 * Shape of a token plaintext: `w4a_` + 32 base62 characters (36 total).
 * Used only to *detect* a leaked secret, never to construct one.
 */
const TOKEN_PLAINTEXT_RE = /^w4a_[0-9A-Za-z]{32}$/;

/** True when a value is a full token plaintext rather than a display prefix. */
export function looksLikeTokenPlaintext(value: unknown): boolean {
  return typeof value === 'string' && TOKEN_PLAINTEXT_RE.test(value);
}

/** One entry of the stored token menu. There is no secret field by design. */
export interface StoredTokenMenuEntry {
  tokenId: number;
  name: string;
  expiresAt: string | null;
  createdAt: string;
  /** `true` = hashed token from /api/v1/tokens; `false`/absent = legacy JWT. */
  hashed: boolean;
  /** Display handle from the API; null for legacy tokens. */
  prefix: string | null;
  scopes: string[];
}

/**
 * Rebuild one stored entry from an explicit whitelist. Anything an older build
 * wrote that is not metadata (`accessToken`, `token`, `secret`, …) is dropped
 * because it is not copied over — so a secret cannot survive this function.
 * An entry that still carries something shaped like a live token is discarded
 * whole rather than kept in a partially-scrubbed form.
 */
function toMenuEntry(raw: unknown): StoredTokenMenuEntry | null {
  if (raw === null || typeof raw !== 'object') return null;
  const record = raw as Record<string, unknown>;

  const rawId = typeof record.tokenId === 'number' ? record.tokenId : Number(record.id);
  if (!Number.isFinite(rawId)) return null;

  const entry: StoredTokenMenuEntry = {
    tokenId: rawId,
    name: typeof record.name === 'string' ? record.name : '',
    expiresAt: typeof record.expiresAt === 'string' ? record.expiresAt : null,
    createdAt: typeof record.createdAt === 'string' ? record.createdAt : '',
    hashed: record.hashed === true,
    prefix: typeof record.prefix === 'string' ? record.prefix : null,
    scopes: Array.isArray(record.scopes)
      ? record.scopes.filter((scope): scope is string => typeof scope === 'string')
      : [],
  };

  for (const value of Object.values(entry)) {
    if (looksLikeTokenPlaintext(value)) return null;
  }
  return entry;
}

function toMenu(entries: unknown): StoredTokenMenuEntry[] {
  return (Array.isArray(entries) ? entries : [])
    .map(toMenuEntry)
    .filter((entry): entry is StoredTokenMenuEntry => entry !== null);
}

/**
 * Read the token menu, and — in the same pass — rewrite the key so it can no
 * longer contain a plaintext written by an earlier version of the UI.
 * A corrupted value is discarded instead of being rendered.
 */
export function readStoredTokenMenu(): StoredTokenMenuEntry[] {
  let raw: string | null = null;
  try {
    raw = localStorage.getItem(STORED_NAMED_TOKENS_KEY);
  } catch {
    return [];
  }
  if (!raw) return [];

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    try {
      localStorage.removeItem(STORED_NAMED_TOKENS_KEY);
    } catch {
      // Storage is unavailable; nothing more can be done here.
    }
    return [];
  }

  const menu = toMenu(parsed);
  try {
    localStorage.setItem(STORED_NAMED_TOKENS_KEY, JSON.stringify(menu));
  } catch {
    // Quota or privacy mode: the in-memory menu is still secret-free.
  }
  return menu;
}

/** Persist the token menu. The value is sanitized again on the way out. */
export function writeStoredTokenMenu(entries: StoredTokenMenuEntry[]): void {
  try {
    localStorage.setItem(STORED_NAMED_TOKENS_KEY, JSON.stringify(toMenu(entries)));
  } catch {
    // Storage failure must not break the page: the menu is a convenience cache.
  }
}

/**
 * Drop the plaintext the deprecated quick-generate flow used to persist under
 * `wiki4ai_generated_api_token`. Called on mount, so an installation that has
 * one stops carrying it without the user doing anything.
 */
export function clearLegacyGeneratedTokenPlaintext(): void {
  try {
    localStorage.removeItem(LEGACY_GENERATED_TOKEN_KEY);
  } catch {
    // Nothing to clean up if storage itself is unreachable.
  }
}

/**
 * Drop every token trace the browser is holding, menu included. Called on logout
 * and on every path that clears the session: a signed-out browser keeps no
 * token material at all.
 */
export function clearStoredTokenSecrets(): void {
  try {
    localStorage.removeItem(STORED_NAMED_TOKENS_KEY);
    localStorage.removeItem(LEGACY_GENERATED_TOKEN_KEY);
  } catch {
    // Nothing to clean up if storage itself is unreachable.
  }
}
