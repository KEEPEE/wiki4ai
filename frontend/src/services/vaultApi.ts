/**
 * API service for Vault entry operations.
 * Uses authenticated apiClient for all requests (automatic JWT token + 401 retry).
 */

import type { BackendVaultEntry, CreateVaultEntryDto, UpdateVaultEntryDto } from '../types/vault';
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
};
