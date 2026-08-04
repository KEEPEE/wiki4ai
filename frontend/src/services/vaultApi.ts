/**
 * API service for Vault entry operations.
 * Uses authenticated apiClient for all requests (automatic JWT token + 401 retry).
 */

import type { BackendVaultEntry, CreateVaultEntryDto, UpdateVaultEntryDto } from '../types/vault';
import { apiGet, apiPost, apiPut, apiDelete, apiPostFormData } from './apiClient';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

function uint8ArrayToNumbers(arr: Uint8Array): number[] {
  return Array.from(arr);
}

export const vaultApi = {
  /**
   * Get all vault entries for the current user.
   */
  getAll: (): Promise<BackendVaultEntry[]> =>
    apiGet(`${API_BASE_URL}/vault/entries`),

  /**
   * Create a new vault entry with encrypted data.
   */
  create: (data: CreateVaultEntryDto): Promise<BackendVaultEntry> => {
    const body = {
      title: data.title,
      url: data.url,
      groupPath: data.groupPath,
      usernameEncrypted: uint8ArrayToNumbers(data.usernameEncrypted),
      passwordEncrypted: uint8ArrayToNumbers(data.passwordEncrypted),
      notesEncrypted: data.notesEncrypted ? uint8ArrayToNumbers(data.notesEncrypted) : null,
      iv: uint8ArrayToNumbers(data.iv),
    };
    return apiPost(`${API_BASE_URL}/vault/entries`, body);
  },

  /**
   * Update an existing vault entry.
   */
  update: (id: number, data: UpdateVaultEntryDto): Promise<BackendVaultEntry> => {
    const body: Record<string, unknown> = {};
    if (data.title !== undefined) body.title = data.title;
    if (data.url !== undefined) body.url = data.url;
    if (data.groupPath !== undefined) body.groupPath = data.groupPath;
    if (data.usernameEncrypted !== undefined) body.usernameEncrypted = uint8ArrayToNumbers(data.usernameEncrypted);
    if (data.passwordEncrypted !== undefined) body.passwordEncrypted = uint8ArrayToNumbers(data.passwordEncrypted);
    if (data.notesEncrypted !== undefined) body.notesEncrypted = data.notesEncrypted ? uint8ArrayToNumbers(data.notesEncrypted) : null;
    if (data.iv !== undefined) body.iv = uint8ArrayToNumbers(data.iv);
    return apiPut(`${API_BASE_URL}/vault/entries/${id}`, body);
  },

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
};
