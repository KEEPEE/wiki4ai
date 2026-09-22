/**
 * API service for Authentication operations.
 * Handles login, register, and token refresh requests to the backend.
 */

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

export interface LoginRequest {
  username: string;
  password: string;
}

export interface RegisterRequest {
  username: string;
  email: string;
  password: string;
}

export interface UserInfo {
  id: number;
  username: string;
  email: string;
  role?: 'ADMIN' | 'USER';
  createdAt: string;
}

export interface AuthResponse {
  accessToken: string;
  refreshToken: string;
  user: UserInfo;
}

/**
 * WIKI4AI-69/70: public instance auth status (GET /api/v1/auth/status).
 * Booleans only — the backend deliberately leaks no user details.
 */
export interface AuthStatus {
  /** true once at least one account exists (instance initialized) */
  initialized: boolean;
  /** whether POST /api/v1/auth/register is currently accepted */
  registrationOpen: boolean;
}

/** Request body for the first-run setup endpoint. */
export interface SetupRequest {
  username: string;
  password: string;
  /** Optional — when omitted the backend derives {username}@localhost */
  email?: string;
}

/**
 * Login with username and password.
 */
export async function login(username: string, password: string): Promise<AuthResponse> {
  const response = await fetch(`${API_BASE_URL}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });

  if (!response.ok) {
    const errorBody = await response.json().catch(() => ({}));
    throw new Error(errorBody.error || `Login failed: ${response.statusText}`);
  }

  return response.json();
}

/**
 * Register a new user.
 */
export async function register(username: string, email: string, password: string): Promise<UserInfo> {
  const response = await fetch(`${API_BASE_URL}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, email, password }),
  });

  if (!response.ok) {
    const errorBody = await response.json().catch(() => ({}));
    throw new Error(errorBody.error || `Registration failed: ${response.statusText}`);
  }

  return response.json();
}

/**
 * WIKI4AI-69/70: fetch the public instance auth status.
 * Used to decide between the first-run setup form and the regular login page,
 * and whether the register form/link is shown.
 */
export async function getAuthStatus(signal?: AbortSignal): Promise<AuthStatus> {
  const response = await fetch(`${API_BASE_URL}/auth/status`, signal ? { signal } : undefined);

  if (!response.ok) {
    throw new Error(`Failed to load auth status: ${response.statusText}`);
  }

  return response.json();
}

/**
 * WIKI4AI-69: first-run setup — creates the very first account (ADMIN role).
 * Only accepted while no account exists; afterwards the backend returns 403.
 */
export async function setup(username: string, password: string, email?: string): Promise<UserInfo> {
  const body: SetupRequest = { username, password };
  if (email) {
    body.email = email;
  }

  const response = await fetch(`${API_BASE_URL}/auth/setup`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    const errorBody = await response.json().catch(() => ({}));
    throw new Error(errorBody.message || errorBody.error || `Setup failed: ${response.statusText}`);
  }

  return response.json();
}
