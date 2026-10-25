/**
 * API service for API-token operations (WIKI4AI-138).
 * Uses authenticated apiClient for all requests (automatic JWT token + 401 retry).
 *
 * These are the NEW hashed-token endpoints under `/api/v1/tokens` (WIKI4AI-135..137),
 * not the legacy `/api/v1/auth/token*` endpoints:
 *
 * - `GET    /api/v1/tokens`            — the only listing that exposes `hashed`,
 *   which is what lets the UI tell a legacy clear-text JWT apart from a revocable
 *   hashed token. The legacy listing has no such field.
 * - `DELETE /api/v1/tokens/{id}`       — soft revoke: stamps `revokedAt` AND evicts
 *   the validation cache, so the token stops working immediately. The legacy
 *   `DELETE /auth/token/{id}` hard-deletes the row and leaves the cache untouched.
 * - `POST   /api/v1/tokens`            — creates a `w4a_…` hashed token; the plaintext
 *   is returned exactly once, in this response and never again.
 * - `POST   /api/v1/tokens/{id}/rotate` — revokes the old token and issues its
 *   replacement in one transaction; plaintext of the new token is returned once.
 *   Legacy rows are rejected with 400, already-revoked ones with 409.
 */

import { apiGet, apiPost, apiDelete } from './apiClient';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

/**
 * One API token as returned by every read endpoint. Carries neither the plaintext
 * nor the hash — `prefix` is the display handle instead.
 */
export interface TokenInfo {
  /** Token row id. The UI keeps its own `tokenId` naming for this value. */
  id: number;
  /** Name given by the owner. */
  name: string;
  /** First characters of the token body. Null for legacy tokens. */
  prefix: string | null;
  /** Granted scopes. Empty for legacy tokens, which have no scope concept. */
  scopes: string[];
  /** Actor this token acts as. Null for legacy and unbound tokens. */
  actorId: number | null;
  /** Expiry, or null for a token that never expires. */
  expiresAt: string | null;
  /** When the token was revoked. Null while it is still usable. */
  revokedAt: string | null;
  /** Last time this token authenticated a request (throttled, so it can lag). */
  lastUsedAt: string | null;
  /** Creation time. */
  createdAt: string;
  /**
   * `true` = hashed (stateful, revocable, rotatable).
   * `false` = legacy clear-text JWT: infinite lifetime, cannot be revoked, cannot
   * be rotated. This single flag drives the deprecation banner in the UI.
   */
  hashed: boolean;
}

/** Response of POST /tokens and POST /tokens/{id}/rotate. */
export interface TokenCreated {
  /** Plaintext secret. Shown once — the backend stores only its SHA-256 digest. */
  token: string;
  /** Everything about the token except its secret. */
  metadata: TokenInfo;
}

/** Request body of POST /tokens. `scopes` MUST be a JSON array — a string is a 500. */
export interface TokenCreateRequest {
  name: string;
  scopes: string[];
  expiresAt?: string | null;
  actorId?: number | null;
}

/**
 * List every API token of the current user, newest first — legacy rows included,
 * flagged with `hashed: false`.
 */
export function listTokens(): Promise<TokenInfo[]> {
  return apiGet<TokenInfo[]>(`${API_BASE_URL}/tokens`);
}

/**
 * Create a new hashed API token. `scopes` is always sent as an array so the
 * backend never receives a string (that would fail JSON parsing with 500).
 */
export function createToken(
  name: string,
  scopes: string[],
  expiresAt?: string | null,
): Promise<TokenCreated> {
  const body: TokenCreateRequest = { name, scopes };
  if (expiresAt) body.expiresAt = expiresAt;
  return apiPost<TokenCreated>(`${API_BASE_URL}/tokens`, body);
}

/**
 * Rotate a hashed token: the old one is revoked and a replacement is created in
 * the same transaction. 400 for a legacy token, 409 when already revoked,
 * 404 when the token is not the caller's.
 */
export function rotateToken(id: number): Promise<TokenCreated> {
  return apiPost<TokenCreated>(`${API_BASE_URL}/tokens/${id}/rotate`);
}

/**
 * Soft-revoke a token (204). Also evicts the backend validation cache, which the
 * legacy delete endpoint does not do.
 */
export function deleteToken(id: number): Promise<void> {
  return apiDelete<void>(`${API_BASE_URL}/tokens/${id}`);
}
