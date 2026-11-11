/**
 * API service for Vault entry operations.
 * Uses authenticated apiClient for all requests (automatic JWT token + 401 retry).
 */

import type {
  BackendVaultEntry,
  CreateVaultEntryDto,
  UpdateVaultEntryDto,
  VaultV2Params,
  VaultV2MigrateEntry,
  VaultV2MigrateResult,
} from '../types/vault';
import { apiGet, apiPost, apiPut, apiDelete, apiPostFormData } from './apiClient';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

export const vaultApi = {
  /**
   * Get all vault entries for the current user.
   */
  getAll: (): Promise<BackendVaultEntry[]> =>
    apiGet(`${API_BASE_URL}/vault/entries`),

  /**
   * Create a new vault entry with encrypted data.
   */
  create: (data: CreateVaultEntryDto): Promise<BackendVaultEntry> =>
    apiPost(`${API_BASE_URL}/vault/entries`, data),

  /**
   * Update an existing vault entry.
   */
  update: (id: number, data: UpdateVaultEntryDto): Promise<BackendVaultEntry> =>
    apiPut(`${API_BASE_URL}/vault/entries/${id}`, data),

  /**
   * Delete a vault entry.
   */
  delete: (id: number): Promise<void> =>
    apiDelete(`${API_BASE_URL}/vault/entries/${id}`),

  /**
   * Search vault entries by query string and optional group path prefix.
   */
  search: (query: string, groupPath?: string): Promise<BackendVaultEntry[]> => {
    const params = new URLSearchParams({ q: query });
    if (groupPath) params.append('groupPath', groupPath);
    return apiGet(`${API_BASE_URL}/vault/search?${params.toString()}`);
  },

  /**
   * Import entries from a KDBX file. Returns plaintext entries for frontend to encrypt and save.
   */
  importFromKdbx: (file: File, password: string): Promise<Array<{ title: string; username?: string; password: string; url?: string; notes?: string; groupPath?: string }>> => {
    const formData = new FormData();
    formData.append('file', file);
    formData.append('password', password);
    return apiPostFormData(`${API_BASE_URL}/vault/import/kdbx`, formData);
  },

  /**
   * Get all encrypted vault entries for export. Backend returns encrypted blobs, frontend decrypts and formats.
   */
  getExportEntries: (): Promise<BackendVaultEntry[]> =>
    apiGet(`${API_BASE_URL}/vault/export`),

  // ==================== WIKI4AI-197 (D2): the v2 key hierarchy ====================
  //
  // None of these takes a password or a password hash. That is the point: v1 handed
  // the server SHA-256(password) and the salt, so the server could derive the key and
  // read the vault. v2 hands it a sealed key it cannot open.

  /**
   * The caller's v2 parameters. Answers `initialized: false` before anything exists,
   * which is how the client knows whether to unwrap a key or initialise one.
   */
  getV2Params: (): Promise<VaultV2Params> =>
    apiGet(`${API_BASE_URL}/vault/v2/params`),

  /**
   * Ask for the v2 salt. Write-once on the server: two browsers opening an
   * uninitialised vault are both given the same salt, so both derive the same KEK.
   */
  initV2Salt: (): Promise<VaultV2Params> =>
    apiPost(`${API_BASE_URL}/vault/v2/init-salt`),

  /**
   * Store the sealed DEK. Write-once; a second attempt is a 409 and the client then
   * reads back the key that won and unwraps that one instead.
   */
  initV2Key: (wrappedKey: string, keyId: string): Promise<VaultV2Params> =>
    apiPost(`${API_BASE_URL}/vault/v2/init-key`, { wrappedKey, keyId }),

  /**
   * Re-encrypt a batch of rows from v1 to v2. One transaction on the server: either
   * every row in the batch moves, or none does.
   *
   * @param expectedV1Digest per row is the compare-and-set — it states which v1 bytes
   *   the v2 ones were built from, so a row that moved in between refuses the batch
   *   instead of being overwritten by a snapshot nobody has any more.
   */
  migrateV2: (keyId: string, entries: VaultV2MigrateEntry[]): Promise<VaultV2MigrateResult> =>
    apiPost(`${API_BASE_URL}/vault/v2/migrate`, { keyId, entries }),
};
