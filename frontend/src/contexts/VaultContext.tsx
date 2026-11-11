import { createContext, useContext, useState, useCallback, type ReactNode } from 'react';
import { apiGet } from '../services/apiClient';
import { getMe } from '../services/authApi';
import { vaultApi } from '../services/vaultApi';
import {
  asV2Key,
  deriveV1DecryptKey,
  deriveV2Kek,
  fingerprintKey,
  generateDek,
  hashMasterPasswordLocally,
  parseV2KdfParams,
  unwrapDek,
  wrapDek,
  type V1DecryptKey,
  type V2Key,
} from '../services/vaultCrypto';
import { readV1FieldBytes } from '../services/vaultEntryCodec';
import { migrateVaultToV2, type MigrationReport } from '../services/vaultMigration';
import type { BackendVaultEntry, VaultV2Params } from '../types/vault';

/**
 * The keys an unlocked vault holds, and nothing else.
 *
 * `v2Key` is the only key a D2 client writes with. `v1Keys` can open old rows and is
 * a list because more than one v1 salt is a real situation (see `collectV1Salts`).
 * There is no `masterPassword` field any more: the previous context stored `h` there,
 * under a name that said password, and callers passed it to `deriveKey` as if it were
 * one.
 */
export interface VaultKeys {
  userId: number;
  keyId: string;
  v2Key: V2Key;
  v1Keys: readonly V1DecryptKey[];
}

const VAULT_SALT_KEY = 'wiki4ai_vault_salt';
const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

interface VaultContextType {
  isUnlocked: boolean;
  isLoading: boolean;
  keys: VaultKeys | null;
  hasMasterPasswordSet: boolean | null;
  /**
   * Kept for the setup-screen branch in VaultPage. D2 no longer needs it: the v2 salt
   * comes from the server, so a new browser cannot be missing a salt it has never
   * seen, which was the whole reason this flag existed.
   */
  needsReinit: boolean;
  error: string | null;
  /** What the first-unlock re-encryption did, for the banner in VaultPage. */
  migrationReport: MigrationReport | null;
  /** Takes the **raw** password. `h` is computed locally and never leaves the browser. */
  unlock: (rawPassword: string) => Promise<void>;
  setupVault: (rawPassword: string) => Promise<void>;
  lock: () => void;
  checkStatus: () => Promise<void>;
  dismissMigrationReport: () => void;
}

const VaultContext = createContext<VaultContextType | undefined>(undefined);

export function useVault(): VaultContextType {
  const context = useContext(VaultContext);
  if (context === undefined) {
    throw new Error('useVault must be used within a VaultProvider');
  }
  return context;
}

interface VaultProviderProps {
  children: ReactNode;
}

function getSaltFromStorage(): Uint8Array | null {
  const saltB64 = localStorage.getItem(VAULT_SALT_KEY);
  if (!saltB64) return null;
  try {
    return Uint8Array.from(atob(saltB64), (c) => c.charCodeAt(0));
  } catch {
    return null;
  }
}

/** apiClient prefixes rejected responses with the status, e.g. `409: <message>`. */
function isConflict(error: unknown): boolean {
  return error instanceof Error && /^409\b/.test(error.message);
}

function toError(error: unknown, fallback: string): Error {
  return error instanceof Error ? error : new Error(fallback);
}

/**
 * The v1 salts worth trying, in the order worth trying them.
 *
 * v1 kept its salt in two places — `localStorage` first, the server as a fallback —
 * and they are not guaranteed to agree: a browser whose local copy differed derived a
 * different key, and the rows it wrote are only readable with that one. The previous
 * code picked one and lived with the consequences. D2 tries both, because the
 * alternative is silently losing fields from rows that are perfectly readable.
 *
 * The server salt goes first: it is the one every other client (another browser, the
 * MCP server) uses, so it is the one most rows were written with.
 */
async function collectV1Salts(): Promise<Uint8Array[]> {
  const salts: Uint8Array[] = [];
  const push = (salt: Uint8Array | null) => {
    if (!salt || salt.length === 0) return;
    const key = salt.join(',');
    if (!salts.some((existing) => existing.join(',') === key)) salts.push(salt);
  };

  let serverSaltB64: string | null = null;
  try {
    const response = await apiGet<{ salt: string }>(`${API_BASE_URL}/vault/master-password/salt`);
    serverSaltB64 = response.salt;
  } catch {
    // No server-side salt (a vault that predates salt sync). The local copy, if any,
    // is all there is.
  }

  if (serverSaltB64) {
    try {
      push(Uint8Array.from(atob(serverSaltB64), (c) => c.charCodeAt(0)));
      localStorage.setItem(VAULT_SALT_KEY, serverSaltB64);
    } catch {
      // Malformed base64 from the server; nothing to do with it.
    }
  }

  push(getSaltFromStorage());
  return salts;
}

/**
 * Whether at least one v1 row opens with these keys.
 *
 * This is the password check before a v2 initialisation. The server's `/verify`
 * endpoint never guarded anything — `GET /vault/entries` needs only the JWT, so a
 * caller that got the password wrong could still read the vault — and D2 does not
 * call it at all. A row that opens with the key derived from this password says the
 * same thing, using evidence instead of an oracle.
 */
async function canOpenAnyV1Row(
  entries: readonly BackendVaultEntry[],
  v1Keys: readonly V1DecryptKey[]
): Promise<boolean> {
  if (v1Keys.length === 0) return false;
  for (const entry of entries) {
    if (entry.cryptoVersion === 2) continue;
    const opened = await readV1FieldBytes(entry.passwordEncrypted, v1Keys);
    if (opened) return true;
  }
  return false;
}

export function VaultProvider({ children }: VaultProviderProps) {
  const [isUnlocked, setIsUnlocked] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [keys, setKeys] = useState<VaultKeys | null>(null);
  const [hasMasterPasswordSet, setHasMasterPasswordSet] = useState<boolean | null>(null);
  const [needsReinit] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [migrationReport, setMigrationReport] = useState<MigrationReport | null>(null);

  const checkStatus = useCallback(async () => {
    setIsLoading(true);
    setError(null);

    try {
      const token = localStorage.getItem('wiki4ai_access_token');
      if (!token) {
        setHasMasterPasswordSet(false);
        setIsLoading(false);
        return;
      }

      const response = await fetch(`${API_BASE_URL}/vault/master-password/status`, {
        headers: { Authorization: `Bearer ${token}` },
      });

      if (!response.ok) {
        setHasMasterPasswordSet(false);
      } else {
        const isSet = await response.json();
        setHasMasterPasswordSet(isSet);
      }
    } catch {
      setError('Failed to check vault status');
      setHasMasterPasswordSet(null);
    } finally {
      setIsLoading(false);
    }
  }, []);

  /**
   * Turn a raw password into the keys an unlocked vault needs, initialise v2 if this
   * vault has no key yet, and re-encrypt whatever is still v1.
   *
   * Nothing below sends the password or `h` anywhere. The server is handed a sealed
   * key it cannot open and a fingerprint it cannot invert.
   */
  const establish = useCallback(
    async (rawPassword: string, options: { allowInit: boolean }): Promise<void> => {
      const token = localStorage.getItem('wiki4ai_access_token');
      if (!token) throw new Error('Not authenticated');

      const [params, profile, entries] = await Promise.all([
        vaultApi.getV2Params(),
        getMe(token),
        vaultApi.getAll(),
      ]);
      const userId = profile.id;

      const v1Rows = entries.filter((entry: BackendVaultEntry) => entry.cryptoVersion !== 2);

      // h is derived from the raw password and used only to open v1 rows. It is the
      // v1 key's input, not a secret of its own: the server saw it on every unlock and
      // every setup under v1, and storing it in the database is exactly what made v1
      // readable by the party that stores the vault.
      const h = await hashMasterPasswordLocally(rawPassword);

      let v1Keys: V1DecryptKey[] = [];
      if (v1Rows.length > 0) {
        const salts = await collectV1Salts();
        v1Keys = await Promise.all(salts.map((salt) => deriveV1DecryptKey(h, salt)));
      }

      let dek: Uint8Array;
      let wrapped: VaultV2Params = params;

      if (params.initialized && params.salt && params.wrappedKey) {
        // The vault already has a key: opening it *is* the password check. The GCM tag
        // on the sealed DEK either verifies or it does not, and there is no server left
        // to ask.
        const kek = await deriveV2Kek(rawPassword, fromBase64(params.salt), parseV2KdfParams(params.kdf).iterations);
        const opened = await unwrapDek(kek, params.wrappedKey);
        if (!opened) throw new Error('Incorrect master password');
        dek = opened;
      } else {
        if (!options.allowInit) throw new Error('Vault is not initialised');

        // Before writing a key, make sure this password is the one that opens the
        // existing rows. A vault with no rows has nothing to protect, so there is
        // nothing to check and initialising it is unconditional.
        if (v1Rows.length > 0 && !(await canOpenAnyV1Row(entries, v1Keys))) {
          throw new Error('Incorrect master password');
        }

        const saltParams = await vaultApi.initV2Salt();
        if (!saltParams.salt) throw new Error('The server returned no vault salt');
        const kek = await deriveV2Kek(
          rawPassword,
          fromBase64(saltParams.salt),
          parseV2KdfParams(saltParams.kdf).iterations
        );

        const freshDek = generateDek();
        const sealed = await wrapDek(kek, freshDek);
        const fingerprint = await fingerprintKey(freshDek);

        try {
          wrapped = await vaultApi.initV2Key(sealed, fingerprint);
          dek = freshDek;
        } catch (initError) {
          if (!isConflict(initError)) throw initError;
          // Another browser got there first. Its DEK is now the vault's, so ours is
          // useless and the only way to keep working is to open theirs.
          const current = await vaultApi.getV2Params();
          if (!current.wrappedKey) throw initError;
          const opened = await unwrapDek(kek, current.wrappedKey);
          if (!opened) throw new Error('Incorrect master password');
          wrapped = current;
          dek = opened;
        }
      }

      const keyId = await fingerprintKey(dek);
      if (wrapped.keyId && wrapped.keyId !== keyId) {
        // Should not happen: the fingerprint is of the key the server stored. If it
        // disagrees, the two sides hold different keys and writing with ours would
        // produce rows the vault cannot read back.
        throw new Error('The vault key does not match the key stored on the server');
      }

      const unlockedKeys: VaultKeys = { userId, keyId, v2Key: asV2Key(dek), v1Keys };

      if (v1Rows.length > 0) {
        const report = await migrateVaultToV2({
          entries,
          keys: unlockedKeys,
          migrate: vaultApi.migrateV2,
        });
        setMigrationReport(report);
      }

      setKeys(unlockedKeys);
      setIsUnlocked(true);
      setHasMasterPasswordSet(true);
    },
    []
  );

  const runEstablish = useCallback(
    async (rawPassword: string, options: { allowInit: boolean }) => {
      setIsLoading(true);
      setError(null);
      try {
        await establish(rawPassword, options);
      } catch (err) {
        setError(toError(err, 'Failed to unlock vault').message);
      } finally {
        setIsLoading(false);
      }
    },
    [establish]
  );

  const unlock = useCallback(
    (rawPassword: string) => runEstablish(rawPassword, { allowInit: true }),
    [runEstablish]
  );

  const setupVault = useCallback(
    (rawPassword: string) => runEstablish(rawPassword, { allowInit: true }),
    [runEstablish]
  );

  const lock = useCallback(() => {
    setIsUnlocked(false);
    setKeys(null);
    setError(null);
    setMigrationReport(null);
  }, []);

  const dismissMigrationReport = useCallback(() => setMigrationReport(null), []);

  return (
    <VaultContext.Provider
      value={{
        isUnlocked,
        isLoading,
        keys,
        hasMasterPasswordSet,
        needsReinit,
        error,
        migrationReport,
        unlock,
        setupVault,
        lock,
        checkStatus,
        dismissMigrationReport,
      }}
    >
      {children}
    </VaultContext.Provider>
  );
}

function fromBase64(value: string): Uint8Array {
  const binary = atob(value);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

export default VaultContext;
