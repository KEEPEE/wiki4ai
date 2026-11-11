/**
 * Tests for the whole-vault v1 → v2 migration (WIKI4AI-197 D2).
 *
 * The two properties this suite exists to protect:
 *
 *   1. a row whose v1 bytes could not be opened never enters the batch — migrating it
 *      anyway is how a vault comes back with fields missing;
 *   2. a row whose v2 bytes do not decrypt back to exactly the v1 bytes never enters
 *      the batch either — the client proves the round trip before it offers the row.
 *
 * Both are checked here without a server, because the decision is made client-side
 * before any request exists.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../services/vaultCrypto', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../services/vaultCrypto')>();
  return {
    ...actual,
    // Spied so one test can hand back a blob the real key cannot open. The default
    // implementation is the real one, captured before the replacement.
    encryptV2Field: vi.fn(async (...args: Parameters<typeof actual.encryptV2Field>) =>
      actual.encryptV2Field(...args)
    ),
  };
});

import * as vaultCrypto from '../services/vaultCrypto';
import { cryptoApi } from '../services/cryptoApi';
import { bytesToBase64 } from '../services/encryptionService';
import {
  MIGRATE_BATCH_LIMIT,
  describeMigration,
  migrateVaultToV2,
  v1RowsOf,
  type VaultKeysForMigration,
} from '../services/vaultMigration';
import { buildV2Aad, concat, type V1DecryptKey, type V2Key } from '../services/vaultCrypto';
import type { BackendVaultEntry, EncryptedField, VaultV2MigrateEntry } from '../types/vault';

const V1_KEY = new Uint8Array(32).fill(21) as V1DecryptKey;
const V2_KEY = new Uint8Array(32).fill(33) as V2Key;
const KEY_ID = 'k'.repeat(64);
const USER_ID = 7;

const KEYS: VaultKeysForMigration = { userId: USER_ID, keyId: KEY_ID, v2Key: V2_KEY, v1Keys: [V1_KEY] };

/** A v1-shaped field: AES-GCM over the bytes, random IV, no AAD. */
async function v1Field(text: string, key: Uint8Array = V1_KEY): Promise<EncryptedField> {
  const { ciphertextWithTag, iv } = await cryptoApi.encryptBytes(new TextEncoder().encode(text), key);
  return { ciphertext: bytesToBase64(ciphertextWithTag), iv: bytesToBase64(iv) };
}

/** A row as the backend returns it before any migration. */
async function v1Row(
  id: number,
  fields: { password?: string; username?: string; notes?: string; passwordField?: EncryptedField | null } = {}
): Promise<BackendVaultEntry> {
  const passwordField = fields.passwordField !== undefined
    ? fields.passwordField
    : await v1Field(JSON.stringify(fields.password ?? 'pw'));

  return {
    id,
    title: `entry ${id}`,
    url: undefined,
    groupPath: undefined,
    usernameEncrypted: fields.username ? await v1Field(JSON.stringify(fields.username)) : null,
    passwordEncrypted: passwordField,
    notesEncrypted: fields.notes ? await v1Field(JSON.stringify(fields.notes)) : null,
    cryptoVersion: 1,
    entryUuid: `00000000-0000-4000-8000-${String(id).padStart(12, '0')}`,
    keyId: null,
    usernameV2: null,
    passwordV2: null,
    notesV2: null,
    v1Digest: null,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
}

/** Ciphertext that no key will open: right shape, wrong bytes. */
function junkField(): EncryptedField {
  return {
    ciphertext: bytesToBase64(new Uint8Array([9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9])),
    iv: bytesToBase64(new Uint8Array(12).fill(5)),
  };
}

function okMigrate() {
  return vi.fn(async (_keyId: string, batch: VaultV2MigrateEntry[]) => ({
    migrated: batch.length,
    ids: batch.map((row) => row.id),
  }));
}

beforeEach(() => {
  vi.mocked(vaultCrypto.encryptV2Field).mockClear();
});

describe('migrateVaultToV2', () => {
  it('migrates a healthy v1 row and reports it', async () => {
    const entries = [await v1Row(1, { password: 'p@ss', username: 'user', notes: 'néšt' })];
    const migrate = okMigrate();

    const report = await migrateVaultToV2({ entries, keys: KEYS, migrate });

    expect(report.v1Rows).toBe(1);
    expect(report.migrated).toBe(1);
    expect(report.skipped).toEqual([]);
    expect(migrate).toHaveBeenCalledTimes(1);

    const batch = migrate.mock.calls[0][1];
    expect(batch).toHaveLength(1);
    expect(batch[0].id).toBe(1);
    expect(batch[0].passwordV2).toBeTruthy();
    expect(batch[0].usernameV2).toBeTruthy();
    expect(batch[0].notesV2).toBeTruthy();
  });

  it('carries the v1 bytes through verbatim, not a re-serialisation', async () => {
    // The MCP server wrote `é` as \u00e9 inside the JSON text. Re-serialising would
    // produce different bytes with the same meaning, and "migrated unchanged" would be
    // a claim about characters rather than about the row.
    const raw = '"príklad \\u00e9 🚀"';
    const entries = [await v1Row(2, {})];
    const row = { ...entries[0], passwordEncrypted: await v1Field(raw) };
    const migrate = okMigrate();

    await migrateVaultToV2({ entries: [row], keys: KEYS, migrate });

    const payload = migrate.mock.calls[0][1][0];
    const opened = await vaultCrypto.decryptV2Field(payload.passwordV2, V2_KEY, {
      userId: USER_ID,
      entryUuid: row.entryUuid!,
      field: 'password',
      keyId: KEY_ID,
    });

    expect(Array.from(opened ?? [])).toEqual(Array.from(new TextEncoder().encode(raw)));
  });

  it('sends a compare-and-set digest per row', async () => {
    const entries = [await v1Row(3, { password: 'pw' })];
    const migrate = okMigrate();

    await migrateVaultToV2({ entries, keys: KEYS, migrate });

    expect(migrate.mock.calls[0][1][0].expectedV1Digest).toMatch(/^[0-9a-f]{64}$/);
    expect(migrate.mock.calls[0][0]).toBe(KEY_ID);
  });

  it('accepts the digest the server computed for the same bytes', async () => {
    const { base64ToBytes } = await import('../services/encryptionService');
    const entry = await v1Row(4, { password: 'pw', notes: 'poz' });
    entry.v1Digest = await vaultCrypto.computeV1Digest({
      username: null,
      password: base64ToBytes(entry.passwordEncrypted!.ciphertext),
      notes: base64ToBytes(entry.notesEncrypted!.ciphertext),
      iv: base64ToBytes(entry.passwordEncrypted!.iv),
    });

    const migrate = okMigrate();
    const report = await migrateVaultToV2({ entries: [entry], keys: KEYS, migrate });

    expect(report.migrated).toBe(1);
    expect(report.skipped).toEqual([]);
  });

  it('skips a row whose digest disagrees with the server, rather than failing the batch', async () => {
    const entry = await v1Row(5, { password: 'pw' });
    entry.v1Digest = 'f'.repeat(64);

    const migrate = okMigrate();
    const report = await migrateVaultToV2({ entries: [entry], keys: KEYS, migrate });

    expect(report.migrated).toBe(0);
    expect(report.skipped).toEqual([{ id: 5, reason: 'digest-mismatch' }]);
    expect(migrate).not.toHaveBeenCalled();
  });

  it('skips a row whose password will not decrypt, and sends nothing', async () => {
    const entries = [await v1Row(6, { passwordField: junkField() })];
    const migrate = okMigrate();

    const report = await migrateVaultToV2({ entries, keys: KEYS, migrate });

    expect(report.migrated).toBe(0);
    expect(report.skipped).toEqual([{ id: 6, reason: 'undecryptable' }]);
    expect(migrate).not.toHaveBeenCalled();
  });

  it('skips a row whose notes are present but unreadable (invariant I3)', async () => {
    // The dangerous case: the password opens, so the row looks migratable, and the
    // naive implementation would write the password and quietly drop the notes.
    const entry = await v1Row(7, { password: 'pw' });
    entry.notesEncrypted = junkField();

    const migrate = okMigrate();
    const report = await migrateVaultToV2({ entries: [entry], keys: KEYS, migrate });

    expect(report.migrated).toBe(0);
    expect(report.skipped).toEqual([{ id: 7, reason: 'undecryptable' }]);
    expect(migrate).not.toHaveBeenCalled();
  });

  it('skips a row whose username is present but unreadable', async () => {
    const entry = await v1Row(8, { password: 'pw' });
    entry.usernameEncrypted = junkField();

    const report = await migrateVaultToV2({ entries: [entry], keys: KEYS, migrate: okMigrate() });

    expect(report.skipped).toEqual([{ id: 8, reason: 'undecryptable' }]);
  });

  it('skips a row whose v2 round trip does not return the same bytes', async () => {
    // The proof the client owes itself before offering a row: encrypt, open again,
    // compare. Forcing the two sides to disagree is how this test reaches a state
    // correct code should never produce on its own — here by sealing the field under a
    // different key while the reader still uses the vault's.
    const WRONG_KEY = new Uint8Array(32).fill(99);
    const entries = [await v1Row(9, { password: 'pw' })];
    vi.mocked(vaultCrypto.encryptV2Field).mockImplementationOnce(async (plaintext, _key, context) => {
      const sealed = await cryptoApi.encryptBytes(plaintext, WRONG_KEY, { aad: buildV2Aad(context) });
      return bytesToBase64(concat(sealed.iv, sealed.ciphertextWithTag));
    });

    const migrate = okMigrate();
    const report = await migrateVaultToV2({ entries, keys: KEYS, migrate });

    expect(report.migrated).toBe(0);
    expect(report.skipped).toEqual([{ id: 9, reason: 'round-trip-mismatch' }]);
    expect(migrate).not.toHaveBeenCalled();
  });

  it('leaves rows that are already v2 alone', async () => {
    const entry = await v1Row(10, { password: 'pw' });
    const migratedRow: BackendVaultEntry = {
      ...entry,
      id: 11,
      cryptoVersion: 2,
      passwordEncrypted: null,
      passwordV2: 'AQIDBAUGBwgJ',
      keyId: KEY_ID,
    };

    const migrate = okMigrate();
    const report = await migrateVaultToV2({ entries: [entry, migratedRow], keys: KEYS, migrate });

    expect(report.v1Rows).toBe(1);
    expect(migrate.mock.calls[0][1].map((row) => row.id)).toEqual([10]);
    expect(v1RowsOf([entry, migratedRow]).map((row) => row.id)).toEqual([10]);
  });

  it('sends at most the server batch cap per request', async () => {
    expect(MIGRATE_BATCH_LIMIT).toBe(500);

    const entries: BackendVaultEntry[] = [];
    for (let id = 1; id <= 5; id++) entries.push(await v1Row(id, { password: `pw${id}` }));

    const migrate = okMigrate();
    const report = await migrateVaultToV2({ entries, keys: KEYS, migrate, batchSize: 2 });

    expect(report.migrated).toBe(5);
    expect(migrate).toHaveBeenCalledTimes(3);
    expect(migrate.mock.calls.map((call) => call[1].length)).toEqual([2, 2, 1]);
  });

  it('a batchSize above the cap is clamped to the cap', async () => {
    const entries = [await v1Row(12, { password: 'pw' })];
    const migrate = okMigrate();

    await migrateVaultToV2({ entries, keys: KEYS, migrate, batchSize: 5000 });

    expect(migrate).toHaveBeenCalledTimes(1);
  });

  it('reports rows the server refused, and still reports the rest', async () => {
    const entries: BackendVaultEntry[] = [];
    for (let id = 1; id <= 4; id++) entries.push(await v1Row(id, { password: `pw${id}` }));

    const migrate = vi
      .fn<(keyId: string, batch: VaultV2MigrateEntry[]) => Promise<{ migrated: number; ids: number[] }>>()
      .mockImplementationOnce(async (_keyId, batch) => ({ migrated: batch.length, ids: batch.map((r) => r.id) }))
      .mockImplementationOnce(async () => {
        throw new Error('409: vault entries changed since these digests were computed');
      });

    const report = await migrateVaultToV2({ entries, keys: KEYS, migrate, batchSize: 2 });

    expect(report.migrated).toBe(2);
    expect(report.skipped.map((skip) => skip.id).sort()).toEqual([3, 4]);
    expect(report.skipped.every((skip) => skip.reason === 'server-refused')).toBe(true);
  });

  it('never calls the server when there is nothing to send', async () => {
    const entries = [await v1Row(13, { passwordField: junkField() })];
    const migrate = okMigrate();

    const report = await migrateVaultToV2({ entries, keys: KEYS, migrate });

    expect(migrate).not.toHaveBeenCalled();
    expect(report.v1Rows).toBe(1);
    expect(report.migrated).toBe(0);
  });

  it('a vault that is already v2 costs no request', async () => {
    const migrate = okMigrate();
    const report = await migrateVaultToV2({ entries: [], keys: KEYS, migrate });

    expect(report).toEqual({ v1Rows: 0, migrated: 0, skipped: [] });
    expect(migrate).not.toHaveBeenCalled();
  });
});

describe('describeMigration', () => {
  it('says nothing when there was no v1 to migrate', () => {
    expect(describeMigration({ v1Rows: 0, migrated: 0, skipped: [] })).toBeNull();
  });

  it('reports a clean migration by count', () => {
    const text = describeMigration({ v1Rows: 3, migrated: 3, skipped: [] });
    expect(text).toContain('3 of 3');
    expect(text).not.toContain('left as they were');
  });

  it('names the rows that were left behind and why', () => {
    const text = describeMigration({
      v1Rows: 4,
      migrated: 2,
      skipped: [
        { id: 3, reason: 'undecryptable' },
        { id: 4, reason: 'server-refused' },
      ],
    });

    expect(text).toContain('2 of 4');
    expect(text).toContain('2 left as they were');
    expect(text).toContain('could not be decrypted');
    expect(text).toContain('refused by the server');
  });
});
