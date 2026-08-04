/**
 * TypeScript type definitions for Vault encryption.
 */

export interface EncryptedVaultEntry {
  ciphertext: Uint8Array;
  iv: Uint8Array;
}
