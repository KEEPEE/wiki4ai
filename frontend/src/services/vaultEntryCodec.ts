/**
 * Reading and writing one vault entry, in whichever crypto version that row is in
 * (WIKI4AI-197 D2).
 *
 * ## Why a row decides, not a user
 *
 * `crypto_version` is per row. A vault is legitimately a mix of v1 and v2 for as long
 * as the migration is running — the user unlocks at one moment, the migration writes
 * in batches, and a row whose v1 bytes could not be opened stays v1 on purpose. So
 * every read branches on the row, and every write is v2.
 *
 * ## Why the plaintext is JSON bytes and not a bare string
 *
 * v1 stored `JSON.stringify(value)` — a quoted JSON string — and the migration has to
 * prove it moved the bytes unchanged. Re-serialising would give the same characters
 * for most values but not for all of them (escapes, and the MCP server's
 * `ensure_ascii=True` wrote `é` as `\u00e9`, which is different bytes with the same
 * meaning). Carrying v1's bytes through verbatim is the only way "unchanged" means
 * what it says, so v2 keeps the JSON wrapper and the codec stays uniform.
 */

import type {
  BackendVaultEntry,
  EncryptedField,
  VaultEntryData,
  VaultV2WriteFields,
} from '../types/vault';
import { decryptV1Bytes } from './encryptionService';
import {
  decryptV2Field,
  encryptV2Field,
  type V1DecryptKey,
  type V2FieldContext,
  type V2Key,
  type VaultField,
} from './vaultCrypto';

const decoder = new TextDecoder();
const encoder = new TextEncoder();

/** The JSON text v1/v2 store, turned back into a string. */
export function decodeFieldText(bytes: Uint8Array): string {
  const text = decoder.decode(bytes);
  try {
    const parsed = JSON.parse(text);
    return typeof parsed === 'string' ? parsed : text;
  } catch {
    // Not JSON — bytes written by something that did not wrap them. Better to show
    // them than to lose them.
    return text;
  }
}

/** The exact bytes v1/v2 store for a string value. */
export function encodeFieldText(value: string): Uint8Array {
  return encoder.encode(JSON.stringify(value));
}

/**
 * Open a v1 field with the first key that works.
 *
 * More than one candidate is not hypothetical: the v1 salt came from `localStorage`
 * first and the server second, so a browser whose local copy disagreed with the
 * server derived a different key, and rows written from that browser are only
 * readable with it. Guessing one salt and discarding the other silently loses fields.
 */
export async function readV1FieldBytes(
  field: EncryptedField | null | undefined,
  keys: readonly V1DecryptKey[]
): Promise<Uint8Array | null> {
  if (!field?.ciphertext || !field.iv) return null;
  for (const key of keys) {
    const bytes = await decryptV1Bytes(field, key);
    if (bytes) return bytes;
  }
  return null;
}

/** Open a v2 field. */
export async function readV2FieldBytes(
  packedBase64: string | null | undefined,
  key: V2Key,
  context: Omit<V2FieldContext, 'field'>,
  field: VaultField
): Promise<Uint8Array | null> {
  return decryptV2Field(packedBase64, key, { ...context, field });
}

/**
 * The row's secrets, plus whether they could be read at all.
 *
 * `isReadable: false` is a state, not a string. The previous code put
 * `'[decryption failed]'` in the password field, the edit form prefilled it, and
 * saving wrote it out as the user's new password — a failed read became a write.
 */
export interface DecodedEntry {
  data: VaultEntryData;
  isReadable: boolean;
}

export function v2ContextFor(
  entry: Pick<BackendVaultEntry, 'entryUuid'>,
  keys: { userId: number; keyId: string }
): Omit<V2FieldContext, 'field'> | null {
  if (!entry.entryUuid) return null;
  return { userId: keys.userId, entryUuid: entry.entryUuid, keyId: keys.keyId };
}

export async function decodeEntry(
  entry: BackendVaultEntry,
  keys: { userId: number; keyId: string; v2Key: V2Key; v1Keys: readonly V1DecryptKey[] }
): Promise<DecodedEntry> {
  const isV2 = entry.cryptoVersion === 2;

  if (isV2) {
    const base = v2ContextFor(entry, keys);
    if (!base) return { data: { password: '' }, isReadable: false };

    const [passwordBytes, usernameBytes, notesBytes] = await Promise.all([
      readV2FieldBytes(entry.passwordV2, keys.v2Key, base, 'password'),
      readV2FieldBytes(entry.usernameV2, keys.v2Key, base, 'username'),
      readV2FieldBytes(entry.notesV2, keys.v2Key, base, 'notes'),
    ]);

    // The password is the field that decides readability: username and notes are
    // optional, so their absence is normal, but a v2 row without an openable password
    // is a row this client cannot read.
    if (!passwordBytes) return { data: { password: '' }, isReadable: false };

    return {
      data: {
        password: decodeFieldText(passwordBytes),
        ...(usernameBytes ? { username: decodeFieldText(usernameBytes) } : {}),
        ...(notesBytes ? { notes: decodeFieldText(notesBytes) } : {}),
      },
      isReadable: true,
    };
  }

  const [passwordBytes, usernameBytes, notesBytes] = await Promise.all([
    readV1FieldBytes(entry.passwordEncrypted, keys.v1Keys),
    readV1FieldBytes(entry.usernameEncrypted, keys.v1Keys),
    readV1FieldBytes(entry.notesEncrypted, keys.v1Keys),
  ]);

  if (!passwordBytes) return { data: { password: '' }, isReadable: false };

  return {
    data: {
      password: decodeFieldText(passwordBytes),
      ...(usernameBytes ? { username: decodeFieldText(usernameBytes) } : {}),
      ...(notesBytes ? { notes: decodeFieldText(notesBytes) } : {}),
    },
    isReadable: true,
  };
}

/**
 * Seal an entry's secrets under the v2 key: one IV per field, and each field's AAD
 * naming the row, the user and the field itself.
 *
 * A field the user left empty is omitted rather than encrypted as an empty string, so
 * clearing a value really clears the column instead of storing `""`.
 */
export async function encodeEntryV2(
  data: VaultEntryData,
  keys: { userId: number; keyId: string; v2Key: V2Key },
  entryUuid: string
): Promise<VaultV2WriteFields> {
  const base = { userId: keys.userId, entryUuid, keyId: keys.keyId };

  const passwordV2 = await encryptV2Field(encodeFieldText(data.password), keys.v2Key, {
    ...base,
    field: 'password',
  });

  const fields: VaultV2WriteFields = {
    cryptoVersion: 2,
    keyId: keys.keyId,
    entryUuid,
    passwordV2,
  };

  if (data.username) {
    fields.usernameV2 = await encryptV2Field(encodeFieldText(data.username), keys.v2Key, {
      ...base,
      field: 'username',
    });
  }

  if (data.notes) {
    fields.notesV2 = await encryptV2Field(encodeFieldText(data.notes), keys.v2Key, {
      ...base,
      field: 'notes',
    });
  }

  return fields;
}

/** A uuid for a row that does not have one yet (a create has no id before the INSERT). */
export function generateEntryUuid(): string {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  // RFC 4122 version 4: set the version nibble and clear the variant bits.
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return [
    hex.slice(0, 8),
    hex.slice(8, 12),
    hex.slice(12, 16),
    hex.slice(16, 20),
    hex.slice(20, 32),
  ].join('-');
}
