import { createContext, useContext, useState, useCallback, type ReactNode } from 'react';
import { deriveKey } from '../services/encryptionService';
import type { VaultEncryptionConfig } from '../hooks/useVaultEntries';

const VAULT_SALT_KEY = 'wiki4ai_vault_salt';

interface VaultContextType {
  isUnlocked: boolean;
  isLoading: boolean;
  config: VaultEncryptionConfig | null;
  keyBytes: Uint8Array | null; // Raw encryption key bytes (works in both secure and insecure contexts)
  hasMasterPasswordSet: boolean | null;
  needsReinit: boolean; // True when backend has master password but browser lacks salt (new device/browser)
  error: string | null;
  unlock: (masterPasswordHash: string) => Promise<void>;
  setupVault: (masterPasswordHash: string) => Promise<void>;
  lock: () => void;
  checkStatus: () => Promise<void>;
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

export function VaultProvider({ children }: VaultProviderProps) {
  const [isUnlocked, setIsUnlocked] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [config, setConfig] = useState<VaultEncryptionConfig | null>(null);
  const [keyBytes, setKeyBytes] = useState<Uint8Array | null>(null); // Raw key bytes instead of CryptoKey
  const [hasMasterPasswordSet, setHasMasterPasswordSet] = useState<boolean | null>(null);
  const [needsReinit, setNeedsReinit] = useState(false); // True when salt is missing but master password exists on backend
  const [error, setError] = useState<string | null>(null);

  const getSaltFromStorage = useCallback((): Uint8Array | null => {
    const saltB64 = localStorage.getItem(VAULT_SALT_KEY);
    if (!saltB64) return null;
    try {
      return Uint8Array.from(atob(saltB64), (c) => c.charCodeAt(0));
    } catch {
      return null;
    }
  }, []);

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

      const apiUrl = import.meta.env.VITE_API_URL || '/api/v1';
      const response = await fetch(`${apiUrl}/vault/master-password/status`, {
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

  const unlock = useCallback(async (masterPasswordHash: string) => {
    setIsLoading(true);
    setError(null);

    try {
      const token = localStorage.getItem('wiki4ai_access_token');
      if (!token) {
        throw new Error('Not authenticated');
      }

      const apiUrl = import.meta.env.VITE_API_URL || '/api/v1';
      const response = await fetch(`${apiUrl}/vault/master-password/verify`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ masterPasswordHash }),
      });

      if (!response.ok) {
        throw new Error('Incorrect master password');
      }

      const salt = getSaltFromStorage();
      if (!salt) {
        // Salt missing but master password hash exists on backend → new browser/device
        // Signal parent to show setup screen instead of error
        setNeedsReinit(true);
        setError('Vault needs re-initialization for this browser. Please set up your vault again.');
        setIsLoading(false);
        return;
      }

      const derivedKey = await deriveKey(masterPasswordHash, salt);

      setConfig({ masterPassword: masterPasswordHash, salt });
      setKeyBytes(derivedKey);
      setIsUnlocked(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to unlock vault');
    } finally {
      setIsLoading(false);
    }
  }, [getSaltFromStorage]);

  const setupVault = useCallback(async (masterPasswordHash: string) => {
    setIsLoading(true);
    setError(null);

    try {
      const token = localStorage.getItem('wiki4ai_access_token');
      if (!token) {
        throw new Error('Not authenticated');
      }

      const salt = crypto.getRandomValues(new Uint8Array(16));
      localStorage.setItem(VAULT_SALT_KEY, btoa(String.fromCharCode(...salt)));

      const apiUrl = import.meta.env.VITE_API_URL || '/api/v1';
      const response = await fetch(`${apiUrl}/vault/master-password/set`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          Authorization: `Bearer ${token}`,
        },
        body: JSON.stringify({ masterPasswordHash }),
      });

      if (!response.ok) {
        throw new Error('Failed to set master password');
      }

      const derivedKey = await deriveKey(masterPasswordHash, salt);

      setConfig({ masterPassword: masterPasswordHash, salt });
      setKeyBytes(derivedKey);
      setIsUnlocked(true);
      setHasMasterPasswordSet(true);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to setup vault');
    } finally {
      setIsLoading(false);
    }
  }, []);

  const lock = useCallback(() => {
    setIsUnlocked(false);
    setConfig(null);
    setKeyBytes(null);
    setError(null);
  }, []);

  return (
    <VaultContext.Provider value={{ isUnlocked, isLoading, config, keyBytes, hasMasterPasswordSet, needsReinit, error, unlock, setupVault, lock, checkStatus }}>
      {children}
    </VaultContext.Provider>
  );
}

export default VaultContext;
