/**
 * The v1 → v2 re-encryption of a whole vault, run once at the first v2 unlock
 * (WIKI4AI-197 D2).
 *
 * ## Why the client does it
 *
 * The server holds no key that can open a v1 row — v1's key is derived from
 * `SHA-256(password)` and the client is the only party that still has the password in
 * a form from which `h` can be recomputed. So the server cannot migrate the vault; it
 * can only accept or refuse what the client offers, atomically, per batch.
 *
 * ## Why the whole vault at once
 *
 * Lazy per-entry migration would mean a vault that is half v1 and half v2 indefinitely
 * — every read has to handle both, and every write of a v1 row has to upgrade it. One
 * pass at unlock costs one round trip per 500 rows (the server's batch cap) and leaves
 * one format behind. For a vault of the size this one is, that is the cheaper place to
 * spend the complexity.
 *
 * ## The rule that decides whether a row moves
 *
 * A row moves only if the client can prove, before sending anything, that the v2 bytes
 * it produced say exactly what the v1 bytes said:
 *
 *   1. decrypt **every** non-empty v1 field, as bytes;
 *   2. if any of them fails, the row does not go in the batch — this is invariant I3,
 *      and it is the whole reason a migration can be trusted;
 *   3. encrypt those same bytes under v2;
 *   4. decrypt the v2 blob straight back and compare it byte for byte with the v1
 *      plaintext;
 *   5. only then add the row.
 *
 * A row that fails any step stays v1. It is still readable with the v1 key, and the
 * user is told it was skipped. The failure mode this replaces is the silent one: a row
 * whose notes could not be decrypted, migrated anyway, and coming back with the notes
 * gone.
 */

import type {
  BackendVaultEntry,
  VaultV2MigrateEntry,
  VaultV2MigrateResult,
} from '../types/vault';
import {
  bytesEqual,
  computeV1Digest,
  decryptV2Field,
  encryptV2Field,
  type V1DecryptKey,
  type VaultField,
  type V2Key,
} from './vaultCrypto';
import { base64ToBytes } from './encryptionService';
import { readV1FieldBytes } from './vaultEntryCodec';

/** The server refuses a batch larger than this (`@Size(max = 500)`). */
export const MIGRATE_BATCH_LIMIT = 500;

export type MigrationSkipReason =
  /** A v1 field would not open, so its value is unknown and cannot be carried over. */
  | 'undecryptable'
  /** The v2 round trip did not return the same bytes that went in. */
  | 'round-trip-mismatch'
  /** No row identity to build the AAD from. */
  | 'no-entry-uuid'
  /** Our digest of the v1 bytes disagrees with the server's — the two implementations drifted. */
  | 'digest-mismatch'
  /** The server refused the batch (the row moved underneath us). */
  | 'server-refused';

export interface MigrationSkip {
  id: number;
  reason: MigrationSkipReason;
}

export interface MigrationReport {
  /** Rows this client found in v1. */
  v1Rows: number;
  /** Rows the server accepted and moved. */
  migrated: number;
  skipped: MigrationSkip[];
}

export interface VaultKeysForMigration {
  userId: number;
  keyId: string;
  v2Key: V2Key;
  v1Keys: readonly V1DecryptKey[];
}

/** Rows the migration has work to do on. A v2 row is already where it belongs. */
export function v1RowsOf(entries: readonly BackendVaultEntry[]): BackendVaultEntry[] {
  return entries.filter((entry) => entry.cryptoVersion !== 2);
}

interface PreparedRow {
  entry: BackendVaultEntry;
  payload: VaultV2MigrateEntry;
}

/**
 * Do the cryptography for one row, or say why it cannot be done.
 *
 * Nothing here touches the network, which is what makes the two failure tests
 * (a row that will not decrypt, a round trip that does not match) testable without a
 * server: the decision is made before a request exists.
 */
async function prepareRow(
  entry: BackendVaultEntry,
  keys: VaultKeysForMigration
): Promise<PreparedRow | MigrationSkip> {
  if (!entry.entryUuid) {
    return { id: entry.id, reason: 'no-entry-uuid' };
  }

  const usernameBytes = await readV1FieldBytes(entry.usernameEncrypted, keys.v1Keys);
  const passwordBytes = await readV1FieldBytes(entry.passwordEncrypted, keys.v1Keys);
  const notesBytes = await readV1FieldBytes(entry.notesEncrypted, keys.v1Keys);

  // Step 2 (I3). A row is migratable only if every field it holds opened. `notes`
  // being absent is fine — that is a field the user never filled. `notes` being
  // present and unreadable is not: encrypting nothing in its place would delete it.
  const passwordMissing = !entry.passwordEncrypted?.ciphertext;
  const usernameWanted = !!entry.usernameEncrypted?.ciphertext;
  const notesWanted = !!entry.notesEncrypted?.ciphertext;

  if (passwordMissing || !passwordBytes) return { id: entry.id, reason: 'undecryptable' };
  if (usernameWanted && !usernameBytes) return { id: entry.id, reason: 'undecryptable' };
  if (notesWanted && !notesBytes) return { id: entry.id, reason: 'undecryptable' };

  const aadBase = {
    userId: keys.userId,
    entryUuid: entry.entryUuid,
    keyId: keys.keyId,
  };

  // The compare-and-set token. Computed from the bytes in hand, not from the one the
  // server offered: the point is to state what *we* read. When the server's copy of
  // that statement disagrees, the two implementations have drifted, and sending ours
  // would fail the whole batch with a message that blames the row.
  const digest = await computeV1Digest({
    username: entry.usernameEncrypted?.ciphertext
      ? base64ToBytes(entry.usernameEncrypted.ciphertext)
      : null,
    password: base64ToBytes(entry.passwordEncrypted!.ciphertext),
    notes: entry.notesEncrypted?.ciphertext ? base64ToBytes(entry.notesEncrypted.ciphertext) : null,
    iv: entry.passwordEncrypted?.iv ? base64ToBytes(entry.passwordEncrypted.iv) : null,
  });

  if (entry.v1Digest && entry.v1Digest !== digest) {
    return { id: entry.id, reason: 'digest-mismatch' };
  }

  // Steps 3 and 4: encrypt, then open again and compare bytes. A mismatch here is not
  // a plausible outcome of correct code — which is exactly why it is checked rather
  // than assumed. Anything that breaks the round trip (an AAD built from the wrong
  // row, a blob packed wrong, a key swapped) fails here instead of in the user's view
  // of their password a week later.
  const fields: VaultField[] = ['password'];
  if (usernameWanted) fields.push('username');
  if (notesWanted) fields.push('notes');

  const sealed: Partial<Record<VaultField, string>> = {};

  for (const field of fields) {
    const plaintext = field === 'password' ? passwordBytes : field === 'username' ? usernameBytes : notesBytes;
    const packed = await encryptV2Field(plaintext!, keys.v2Key, { ...aadBase, field });

    const opened = await decryptV2Field(packed, keys.v2Key, { ...aadBase, field });
    if (!opened || !bytesEqual(opened, plaintext!)) {
      return { id: entry.id, reason: 'round-trip-mismatch' };
    }

    sealed[field] = packed;
  }

  const payload: VaultV2MigrateEntry = {
    id: entry.id,
    expectedV1Digest: digest,
    passwordV2: sealed.password!,
    ...(sealed.username ? { usernameV2: sealed.username } : {}),
    ...(sealed.notes ? { notesV2: sealed.notes } : {}),
  };

  return { entry, payload };
}

/**
 * Re-encrypt every v1 row this client can, in batches the server will accept.
 *
 * @param migrate injected rather than imported, so the tests can hand back a real
 *   409 without a server, and so the caller (VaultContext) stays the only place that
 *   knows about tokens and endpoints.
 */
export async function migrateVaultToV2(input: {
  entries: readonly BackendVaultEntry[];
  keys: VaultKeysForMigration;
  migrate: (keyId: string, batch: VaultV2MigrateEntry[]) => Promise<VaultV2MigrateResult>;
  batchSize?: number;
}): Promise<MigrationReport> {
  const { entries, keys, migrate } = input;
  const batchSize = Math.min(input.batchSize ?? MIGRATE_BATCH_LIMIT, MIGRATE_BATCH_LIMIT);

  const v1Rows = v1RowsOf(entries);
  const report: MigrationReport = { v1Rows: v1Rows.length, migrated: 0, skipped: [] };

  const batch: VaultV2MigrateEntry[] = [];

  for (const entry of v1Rows) {
    const prepared = await prepareRow(entry, keys);
    if ('payload' in prepared) batch.push(prepared.payload);
    else report.skipped.push(prepared);
  }

  // An empty batch is a 400 on the server (`@NotEmpty`), so it is never sent. A vault
  // that is already v2, or one where every row was skipped, costs no request at all.
  for (let offset = 0; offset < batch.length; offset += batchSize) {
    const chunk = batch.slice(offset, offset + batchSize);
    try {
      const result = await migrate(keys.keyId, chunk);
      report.migrated += result.migrated;
    } catch {
      // The server refused the whole chunk (one row moved, so all of them roll back).
      // Those rows stay v1 and stay readable; the next unlock tries again. Retrying
      // here would re-read the vault, which is the caller's job, not this one's.
      for (const row of chunk) report.skipped.push({ id: row.id, reason: 'server-refused' });
    }
  }

  return report;
}

/** A one-line summary for the UI, in the user's terms rather than ours. */
export function describeMigration(report: MigrationReport): string | null {
  if (report.v1Rows === 0) return null;
  if (report.skipped.length === 0) {
    return `Vault re-encrypted: ${report.migrated} of ${report.v1Rows} entries moved to crypto v2.`;
  }
  const undecryptable = report.skipped.filter((s) => s.reason === 'undecryptable').length;
  const other = report.skipped.length - undecryptable;
  const parts = [
    `Vault re-encrypted: ${report.migrated} of ${report.v1Rows} entries moved to crypto v2.`,
    `${report.skipped.length} left as they were`,
  ];
  if (undecryptable > 0) parts.push(`${undecryptable} could not be decrypted with this master password`);
  if (other > 0) parts.push(`${other} refused by the server`);
  return parts.join(' — ') + '.';
}
