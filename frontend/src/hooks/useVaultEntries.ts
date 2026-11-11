/**
 * Custom React hook for managing vault entries using TanStack Query.
 * Encrypts and decrypts client-side before and after the API calls.
 *
 * WIKI4AI-197 (D2): reads whichever format a row is in and writes only v2. The v1
 * encryption path is gone from this file — not "unused for now", gone — so there is
 * no code path left that can put a v1 row into a vault whose owner reads v2.
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type {
  VaultEntry,
  CreateVaultEntryDto,
  UpdateVaultEntryDto,
  BackendVaultEntry,
  VaultEntryData,
} from '../types/vault';
import { vaultApi } from '../services/vaultApi';
import { decodeEntry, encodeEntryV2, generateEntryUuid } from '../services/vaultEntryCodec';
import type { VaultKeys } from '../contexts/VaultContext';

const VAULT_ENTRIES_QUERY_KEY = ['vault', 'entries'] as const;
const SEARCH_VAULT_ENTRIES_QUERY_KEY = (query: string, groupPath?: string) => ['vault', 'search', query, groupPath] as const;

function backendToVaultEntry(backendEntry: BackendVaultEntry, data: VaultEntryData, isReadable: boolean): VaultEntry {
  return {
    id: backendEntry.id,
    title: backendEntry.title,
    url: backendEntry.url,
    groupPath: backendEntry.groupPath,
    entryUuid: backendEntry.entryUuid ?? undefined,
    data,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
    isReadable,
  };
}

/**
 * A row whose secrets would not open. `isReadable: false` rather than a sentinel in
 * the password field, because the form prefilled the sentinel and saving wrote it out
 * as the user's new password.
 */
function unreadableEntry(backendEntry: BackendVaultEntry): VaultEntry {
  return backendToVaultEntry(backendEntry, { password: '' }, false);
}

export function useVaultEntries(keys: VaultKeys | null) {
  const queryClient = useQueryClient();

  // Disable all queries/mutations when keys is not available (vault locked/not set up)
  const isEnabled = !!keys;

  const requireKeys = (): VaultKeys => {
    if (!keys) throw new Error('Vault is locked');
    return keys;
  };

  // Fetch all vault entries and decrypt them (disabled when locked)
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: VAULT_ENTRIES_QUERY_KEY,
    enabled: isEnabled,
    queryFn: async () => {
      const unlocked = requireKeys();
      const backendEntries = await vaultApi.getAll();

      return Promise.all(
        backendEntries.map(async (entry) => {
          try {
            const decoded = await decodeEntry(entry, unlocked);
            return backendToVaultEntry(entry, decoded.data, decoded.isReadable);
          } catch {
            return unreadableEntry(entry);
          }
        }),
      );
    },
  });

  /**
   * The row identity a v2 write has to seal against.
   *
   * `entry_uuid` is not updatable: the server keeps the one the row was created with,
   * and the AAD is checked against that value on every read. Encrypting with a fresh
   * uuid would therefore store a blob the row cannot open — the entry would come back
   * unreadable, and the old value would already be gone. So an unknown identity stops
   * the write instead of guessing.
   */
  const entryUuidFor = (id: number): string => {
    const cached = queryClient
      .getQueryData<VaultEntry[]>(VAULT_ENTRIES_QUERY_KEY)
      ?.find((entry) => entry.id === id);
    if (cached?.entryUuid) return cached.entryUuid;
    throw new Error('Vault entry identity is unknown; reload the vault and try again.');
  };

  // Create vault entry mutation with optimistic update (disabled when locked)
  const createMutation = useMutation({
    mutationFn: async (entry: { title: string; url?: string; groupPath?: string; data: VaultEntryData }) => {
      const unlocked = requireKeys();
      // The row has no id before the INSERT, and the AAD is built from the row's
      // identity, so the client generates the identity and sends it along.
      const entryUuid = generateEntryUuid();
      const v2Fields = await encodeEntryV2(entry.data, unlocked, entryUuid);

      const dto: CreateVaultEntryDto = {
        title: entry.title,
        url: entry.url,
        groupPath: entry.groupPath,
        ...v2Fields,
      };

      return vaultApi.create(dto);
    },
    onMutate: async (entry) => {
      await queryClient.cancelQueries({ queryKey: VAULT_ENTRIES_QUERY_KEY });
      const previousEntries = queryClient.getQueryData<VaultEntry[]>(VAULT_ENTRIES_QUERY_KEY) ?? [];

      const optimisticEntry: VaultEntry = {
        id: Date.now(),
        title: entry.title,
        url: entry.url,
        groupPath: entry.groupPath,
        data: entry.data,
        createdAt: new Date().toISOString(),
        updatedAt: new Date().toISOString(),
        isReadable: true,
      };

      queryClient.setQueryData(VAULT_ENTRIES_QUERY_KEY, [...previousEntries, optimisticEntry]);

      return { previousEntries };
    },
    onError: (_err, _vars, context) => {
      if (context?.previousEntries) {
        queryClient.setQueryData(VAULT_ENTRIES_QUERY_KEY, context.previousEntries);
      }
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: VAULT_ENTRIES_QUERY_KEY });
    },
  });

  // Update vault entry mutation with optimistic update (disabled when locked)
  const updateMutation = useMutation({
    mutationFn: async ({
      id,
      updates,
    }: {
      id: number;
      updates: Partial<{ title: string; url: string; groupPath: string }> & { data?: VaultEntryData };
    }) => {
      const unlocked = requireKeys();

      if (updates.data) {
        const v2Fields = await encodeEntryV2(updates.data, unlocked, entryUuidFor(id));
        const dto: UpdateVaultEntryDto = {
          title: updates.title,
          url: updates.url,
          groupPath: updates.groupPath,
          ...v2Fields,
        };
        return vaultApi.update(id, dto);
      }

      // A metadata-only update is refused rather than sent. The v1 semantics were
      // "an omitted field is cleared", so a title-only PUT wrote NULL over the
      // password ciphertext; the backend now rejects that, and a v2 client has the
      // decrypted value in hand anyway, so there is nothing to gain from a partial
      // write that would have to re-encrypt the whole row regardless.
      throw new Error('Vault updates must carry the entry data; reload the vault and try again.');
    },
    onMutate: async ({ id, updates }) => {
      await queryClient.cancelQueries({ queryKey: VAULT_ENTRIES_QUERY_KEY });
      const previousEntries = queryClient.getQueryData<VaultEntry[]>(VAULT_ENTRIES_QUERY_KEY) ?? [];

      queryClient.setQueryData(VAULT_ENTRIES_QUERY_KEY, (old: VaultEntry[] | undefined) => {
        if (!old) return old;
        return old.map((entry) => {
          if (entry.id === id) {
            return {
              ...entry,
              ...(updates.title !== undefined && { title: updates.title }),
              ...(updates.url !== undefined && { url: updates.url }),
              ...(updates.groupPath !== undefined && { groupPath: updates.groupPath }),
              ...(updates.data !== undefined && { data: updates.data }),
              updatedAt: new Date().toISOString(),
            };
          }
          return entry;
        });
      });

      return { previousEntries };
    },
    onError: (_err, _vars, context) => {
      if (context?.previousEntries) {
        queryClient.setQueryData(VAULT_ENTRIES_QUERY_KEY, context.previousEntries);
      }
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: VAULT_ENTRIES_QUERY_KEY });
    },
  });

  // Delete vault entry mutation with optimistic update (disabled when locked)
  const deleteMutation = useMutation({
    mutationFn: async (id: number) => {
      requireKeys();
      return vaultApi.delete(id);
    },
    onMutate: async (id) => {
      await queryClient.cancelQueries({ queryKey: VAULT_ENTRIES_QUERY_KEY });
      const previousEntries = queryClient.getQueryData<VaultEntry[]>(VAULT_ENTRIES_QUERY_KEY) ?? [];

      queryClient.setQueryData(
        VAULT_ENTRIES_QUERY_KEY,
        (old: VaultEntry[] | undefined) => old?.filter((entry) => entry.id !== id) ?? []
      );

      return { previousEntries };
    },
    onError: (_err, _vars, context) => {
      if (context?.previousEntries) {
        queryClient.setQueryData(VAULT_ENTRIES_QUERY_KEY, context.previousEntries);
      }
    },
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: VAULT_ENTRIES_QUERY_KEY });
    },
  });

  const createEntry = async (entry: { title: string; url?: string; groupPath?: string; data: VaultEntryData }) =>
    createMutation.mutateAsync(entry);

  const updateEntry = async (args: {
    id: number;
    updates: Partial<{ title: string; url: string; groupPath: string }> & { data?: VaultEntryData };
  }) => updateMutation.mutateAsync(args);

  const deleteEntry = async (id: number) => deleteMutation.mutateAsync(id);

  return {
    entries: data ?? [],
    isLoading,
    error,
    refetch,
    createEntry,
    updateEntry,
    deleteEntry,
    isCreating: createMutation.isPending,
    isUpdating: updateMutation.isPending,
    isDeleting: deleteMutation.isPending,
  };
}

export function useSearchVaultEntries(
  query: string,
  groupPath?: string,
  keys: VaultKeys | null = null
) {
  const trimmedQuery = query.trim();

  const { data, isLoading, isFetching } = useQuery({
    queryKey: SEARCH_VAULT_ENTRIES_QUERY_KEY(trimmedQuery, groupPath),
    enabled: !!keys && trimmedQuery.length > 0,
    queryFn: async () => {
      const unlocked = requireKeys();
      const backendEntries = await vaultApi.search(trimmedQuery, groupPath);

      return Promise.all(
        backendEntries.map(async (entry) => {
          try {
            const decoded = await decodeEntry(entry, unlocked);
            return backendToVaultEntry(entry, decoded.data, decoded.isReadable);
          } catch {
            return unreadableEntry(entry);
          }
        }),
      );
    },
  });

  function requireKeys(): VaultKeys {
    if (!keys) throw new Error('Vault is locked');
    return keys;
  }

  return {
    searchResults: data ?? [],
    isLoading: isLoading || isFetching,
    hasSearched: trimmedQuery.length > 0,
  };
}
