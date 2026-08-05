/**
 * TypeScript type definitions for Vault entries.
 */

export interface EncryptedVaultEntry {
  ciphertext: Uint8Array;
  iv: Uint8Array;
}

/**
 * Wire format for an encrypted field, matching the backend's EncryptedField DTO:
 * ciphertext/iv are Base64-encoded strings, not raw byte arrays.
 */
export interface EncryptedField {
  ciphertext: string;
  iv: string;
  salt?: string;
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
  usernameEncrypted?: EncryptedField;
  passwordEncrypted: EncryptedField;
  notesEncrypted?: EncryptedField;
}

export interface UpdateVaultEntryDto {
  title?: string;
  url?: string;
  groupPath?: string;
  usernameEncrypted?: EncryptedField;
  passwordEncrypted?: EncryptedField;
  notesEncrypted?: EncryptedField;
}

export interface BackendVaultEntry {
  id: number;
  title: string;
  url?: string;
  groupPath?: string;
  usernameEncrypted: EncryptedField | null;
  passwordEncrypted: EncryptedField;
  notesEncrypted: EncryptedField | null;
  createdAt: string;
  updatedAt: string;
}
