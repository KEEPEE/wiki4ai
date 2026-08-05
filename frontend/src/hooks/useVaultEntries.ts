/**
 * Custom React hook for managing vault entries using TanStack Query.
 * Handles encryption/decryption of sensitive fields client-side before API calls.
 */

import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query';
import type { VaultEntry, CreateVaultEntryDto, UpdateVaultEntryDto, BackendVaultEntry, VaultEntryData } from '../types/vault';
import { vaultApi } from '../services/vaultApi';
import { deriveKey, encrypt, decrypt } from '../services/encryptionService';

const VAULT_ENTRIES_QUERY_KEY = ['vault', 'entries'] as const;
const SEARCH_VAULT_ENTRIES_QUERY_KEY = (query: string, groupPath?: string) => ['vault', 'search', query, groupPath] as const;

export interface VaultEncryptionConfig {
  masterPassword: string;
  salt: Uint8Array;
}

interface EncryptedFields {
  usernameEncrypted: Uint8Array;
  passwordEncrypted: Uint8Array;
  notesEncrypted: Uint8Array | null;
  iv: Uint8Array;
}

async function encryptEntryData(data: VaultEntryData, keyBytes: Uint8Array): Promise<EncryptedFields> {
  const usernameJson = data.username ? JSON.stringify(data.username) : '';
  const passwordJson = JSON.stringify(data.password);
  const notesJson = data.notes ? JSON.stringify(data.notes) : null;

  const iv = crypto.getRandomValues(new Uint8Array(12));

  const usernameEncrypted = await encrypt(usernameJson, keyBytes);
  const passwordEncrypted = await encrypt(passwordJson, keyBytes);
  const notesEncrypted = notesJson ? await encrypt(notesJson, keyBytes) : null;

  return {
    usernameEncrypted: usernameEncrypted.ciphertext,
    passwordEncrypted: passwordEncrypted.ciphertext,
    notesEncrypted: notesEncrypted?.ciphertext ?? null,
    iv,
  };
}

async function decryptEntryData(backendEntry: BackendVaultEntry, keyBytes: Uint8Array): Promise<VaultEntryData> {
  const usernameDecrypted = await decrypt(
    new Uint8Array(backendEntry.usernameEncrypted),
    new Uint8Array(backendEntry.iv),
    keyBytes,
  );

  const passwordDecrypted = await decrypt(
    new Uint8Array(backendEntry.passwordEncrypted),
    new Uint8Array(backendEntry.iv),
    keyBytes,
  );

  let notesDecrypted: string | null;
  if (backendEntry.notesEncrypted) {
    try {
      notesDecrypted = await decrypt(
        new Uint8Array(backendEntry.notesEncrypted),
        new Uint8Array(backendEntry.iv),
        keyBytes,
      );
    } catch {
      notesDecrypted = null;
    }
  } else {
    notesDecrypted = null;
  }

  return {
    username: usernameDecrypted ? JSON.parse(usernameDecrypted) : undefined,
    password: passwordDecrypted ? JSON.parse(passwordDecrypted) : '[decryption failed]',
    notes: notesDecrypted ? JSON.parse(notesDecrypted) : undefined,
  };
}

function backendToVaultEntry(backendEntry: BackendVaultEntry, data: VaultEntryData): VaultEntry {
  return {
    id: backendEntry.id,
    title: backendEntry.title,
    url: backendEntry.url,
    groupPath: backendEntry.groupPath,
    data,
    createdAt: new Date().toISOString(),
    updatedAt: new Date().toISOString(),
  };
}

export function useVaultEntries(config: VaultEncryptionConfig | null) {
  const queryClient = useQueryClient();

  // Disable all queries/mutations when config is not available (vault locked/not set up)
  const isEnabled = !!config;

  const deriveKeyFn = async () => {
    if (!config) throw new Error('Vault encryption config not available');
    return deriveKey(config.masterPassword, config.salt);
  };

  // Fetch all vault entries and decrypt them (disabled when no config)
  const { data, isLoading, error, refetch } = useQuery({
    queryKey: VAULT_ENTRIES_QUERY_KEY,
    enabled: isEnabled,
    queryFn: async () => {
      const backendEntries = await vaultApi.getAll();
      const key = await deriveKeyFn();

      return Promise.all(
        backendEntries.map(async (entry) => {
          try {
            const decryptedData = await decryptEntryData(entry, key);
            return backendToVaultEntry(entry, decryptedData);
          } catch {
            return {
              id: entry.id,
              title: entry.title,
              url: entry.url,
              groupPath: entry.groupPath,
              data: { password: '[decryption failed]' },
              createdAt: new Date().toISOString(),
              updatedAt: new Date().toISOString(),
            };
          }
        }),
      );
    },
  });

  // Create vault entry mutation with optimistic update (disabled when no config)
  const createMutation = useMutation({
    mutationFn: async (entry: { title: string; url?: string; groupPath?: string; data: VaultEntryData }) => {
      if (!config) throw new Error('Vault encryption config not available');
      const key = await deriveKeyFn();
      const encryptedFields = await encryptEntryData(entry.data, key);

      const dto: CreateVaultEntryDto = {
        title: entry.title,
        url: entry.url,
        groupPath: entry.groupPath,
        ...encryptedFields,
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

  // Update vault entry mutation with optimistic update (disabled when no config)
  const updateMutation = useMutation({
    mutationFn: async ({ id, updates }: { id: number; updates: Partial<{ title: string; url: string; groupPath: string }> & { data?: VaultEntryData } }) => {
      if (!config) throw new Error('Vault encryption config not available');
      const key = await deriveKeyFn();

      if (updates.data) {
        const encryptedFields = await encryptEntryData(updates.data, key);
        const dto: UpdateVaultEntryDto = {
          title: updates.title,
          url: updates.url,
          groupPath: updates.groupPath,
          ...encryptedFields,
        };
        return vaultApi.update(id, dto);
      }

      const dto: UpdateVaultEntryDto = {
        title: updates.title,
        url: updates.url,
        groupPath: updates.groupPath,
      };
      return vaultApi.update(id, dto);
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

  // Delete vault entry mutation with optimistic update (disabled when no config)
  const deleteMutation = useMutation({
    mutationFn: async (id: number) => {
      if (!config) throw new Error('Vault encryption config not available');
      return vaultApi.delete(id);
    },
    onMutate: async (id) => {
      await queryClient.cancelQueries({ queryKey: VAULT_ENTRIES_QUERY_KEY });
      const previousEntries = queryClient.getQueryData<VaultEntry[]>(VAULT_ENTRIES_QUERY_KEY) ?? [];

      queryClient.setQueryData(VAULT_ENTRIES_QUERY_KEY, (old: VaultEntry[] | undefined) => {
        if (!old) return old;
        return old.filter((entry) => entry.id !== id);
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

  return {
    entries: data ?? [],
    isLoading,
    error,
    refetch,
    createEntry: createMutation.mutateAsync,
    updateEntry: updateMutation.mutateAsync,
    deleteEntry: deleteMutation.mutateAsync,
    isCreating: createMutation.isPending,
    isUpdating: updateMutation.isPending,
    isDeleting: deleteMutation.isPending,
  };
}

/**
 * Custom React hook for searching vault entries.
 */
export function useSearchVaultEntries(config: VaultEncryptionConfig | null, query: string, groupPath?: string) {
  const trimmedQuery = query.trim();

  // Disable search when no config or no query
  const isEnabled = !!config && !!trimmedQuery;

  const deriveKeyFn = async () => {
    if (!config) throw new Error('Vault encryption config not available');
    return deriveKey(config.masterPassword, config.salt);
  };

  const { data, isLoading, isFetching } = useQuery({
    queryKey: SEARCH_VAULT_ENTRIES_QUERY_KEY(trimmedQuery, groupPath),
    enabled: isEnabled,
    queryFn: async () => {
      const backendEntries = await vaultApi.search(trimmedQuery, groupPath);
      const key = await deriveKeyFn();

      return Promise.all(
        backendEntries.map(async (entry) => {
          try {
            const decryptedData = await decryptEntryData(entry, key);
            return backendToVaultEntry(entry, decryptedData);
          } catch {
            return {
              id: entry.id,
              title: entry.title,
              url: entry.url,
              groupPath: entry.groupPath,
              data: { password: '[decryption failed]' },
              createdAt: new Date().toISOString(),
              updatedAt: new Date().toISOString(),
            };
          }
        }),
      );
    },
  });

  return {
    searchResults: data ?? [],
    isLoading: isLoading || isFetching,
    hasSearched: trimmedQuery.length > 0,
  };
}
