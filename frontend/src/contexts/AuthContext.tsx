import { createContext, useContext, useState, useEffect, useCallback, type ReactNode } from 'react';
import { login as apiLogin, register as apiRegister, type UserInfo } from '../services/authApi';

// Storage keys
const ACCESS_TOKEN_KEY = 'wiki4ai_access_token';
const REFRESH_TOKEN_KEY = 'wiki4ai_refresh_token';
const USER_INFO_KEY = 'wiki4ai_user_info';
const TOKEN_EXPIRY_KEY = 'wiki4ai_token_expiry';

/** Permission names matching backend RBAC */
export type Permission = 'CREATE' | 'READ' | 'UPDATE' | 'DELETE' | 'MANAGE';

interface AuthContextType {
  user: UserInfo | null;
  accessToken: string | null;
  refreshToken: string | null;
  isAuthenticated: boolean;
  isLoading: boolean;
  login: (username: string, password: string) => Promise<void>;
  register: (username: string, email: string, password: string) => Promise<void>;
  logout: () => void;
  /** Explicitly refresh the access token (usually handled automatically by apiClient) */
  refreshTokenAction: () => Promise<void>;
}

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export function useAuth(): AuthContextType {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error('useAuth must be used within an AuthProvider');
  }
  return context;
}

interface AuthProviderProps {
  children: ReactNode;
}

/**
 * Decode JWT payload to check expiration without external libraries.
 * Returns the expiry timestamp in seconds or null if token is invalid.
 */
function getTokenExpiry(token: string): number | null {
  try {
    const parts = token.split('.');
    if (parts.length !== 3) return null;
    const payload = JSON.parse(atob(parts[1]));
    return payload.exp ?? null;
  } catch {
    return null;
  }
}

/**
 * Check if a JWT token is expired or about to expire (within 60 seconds).
 */
function isTokenExpired(token: string): boolean {
  const exp = getTokenExpiry(token);
  if (!exp) return true;
  // Consider expired if within 60 seconds of expiry
  return Date.now() / 1000 >= exp - 60;
}

export function AuthProvider({ children }: AuthProviderProps) {
  const [user, setUser] = useState<UserInfo | null>(null);
  const [accessToken, setAccessToken] = useState<string | null>(null);
  const [refreshToken, setRefreshToken] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  // Initialize auth state from localStorage on mount
  useEffect(() => {
    const storedToken = localStorage.getItem(ACCESS_TOKEN_KEY);
    const storedRefresh = localStorage.getItem(REFRESH_TOKEN_KEY);
    const storedUser = localStorage.getItem(USER_INFO_KEY);

    if (storedToken && storedUser) {
      try {
        const userInfo = JSON.parse(storedUser);
        setAccessToken(storedToken);
        setRefreshToken(storedRefresh);
        setUser(userInfo);

        // Check if token is expired
        if (isTokenExpired(storedToken)) {
          // Token expired, clear auth state
          clearAuthState();
        }
      } catch {
        clearAuthState();
      }
    }

    setIsLoading(false);
  }, []);

  const clearAuthState = useCallback(() => {
    localStorage.removeItem(ACCESS_TOKEN_KEY);
    localStorage.removeItem(REFRESH_TOKEN_KEY);
    localStorage.removeItem(USER_INFO_KEY);
    localStorage.removeItem(TOKEN_EXPIRY_KEY);
    setUser(null);
    setAccessToken(null);
    setRefreshToken(null);
  }, []);

  const saveAuthState = useCallback((token: string, refresh: string, userInfo: UserInfo) => {
    localStorage.setItem(ACCESS_TOKEN_KEY, token);
    localStorage.setItem(REFRESH_TOKEN_KEY, refresh);
    localStorage.setItem(USER_INFO_KEY, JSON.stringify(userInfo));
    setAccessToken(token);
    setRefreshToken(refresh);
    setUser(userInfo);
  }, []);

  const login = useCallback(async (username: string, password: string) => {
    try {
      const response = await apiLogin(username, password);
      saveAuthState(response.accessToken, response.refreshToken, response.user);
    } catch (error) {
      if (error instanceof Error) {
        throw error;
      }
      throw new Error('Login failed');
    }
  }, [saveAuthState]);

  const register = useCallback(async (username: string, email: string, password: string) => {
    try {
      await apiRegister(username, email, password);
    } catch (error) {
      if (error instanceof Error) {
        throw error;
      }
      throw new Error('Registration failed');
    }
  }, []);

  const logout = useCallback(() => {
    clearAuthState();
  }, [clearAuthState]);

  const refreshTokenAction = useCallback(async () => {
    const currentRefresh = localStorage.getItem(REFRESH_TOKEN_KEY);
    if (!currentRefresh) {
      clearAuthState();
      return;
    }

    try {
      const response = await fetch(`${import.meta.env.VITE_API_URL || '/api/v1'}/auth/refresh`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: currentRefresh }),
      });

      if (!response.ok) {
        clearAuthState();
        return;
      }

      const data = await response.json();
      saveAuthState(data.accessToken, data.refreshToken || currentRefresh, user!);
    } catch {
      clearAuthState();
    }
  }, [clearAuthState, saveAuthState, user]);

  const value: AuthContextType = {
    user,
    accessToken,
    refreshToken,
    isAuthenticated: !!user && !!accessToken,
    isLoading,
    login,
    register,
    logout,
    refreshTokenAction,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export default AuthContext;
