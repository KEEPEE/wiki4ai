/**
 * TypeScript type definitions for Vault entries.
 */

export interface EncryptedVaultEntry {
  ciphertext: Uint8Array;
  iv: Uint8Array;
}

export interface VaultEntryData {
  username?: string;
  password: string;
  notes?: string;
}

export interface VaultEntry {
  id: number;
  title: string;
  url?: string;
  groupPath?: string;
  data: VaultEntryData;
  createdAt: string;
  updatedAt: string;
}

export interface CreateVaultEntryDto {
  title: string;
  url?: string;
  groupPath?: string;
  usernameEncrypted: Uint8Array;
  passwordEncrypted: Uint8Array;
  notesEncrypted: Uint8Array | null;
  iv: Uint8Array;
}

export interface UpdateVaultEntryDto {
  title?: string;
  url?: string;
  groupPath?: string;
  usernameEncrypted?: Uint8Array;
  passwordEncrypted?: Uint8Array;
  notesEncrypted?: Uint8Array | null;
  iv?: Uint8Array;
}

export interface BackendVaultEntry {
  id: number;
  title: string;
  url?: string;
  groupPath?: string;
  usernameEncrypted: number[];
  passwordEncrypted: number[];
  notesEncrypted: number[] | null;
  iv: number[];
}
