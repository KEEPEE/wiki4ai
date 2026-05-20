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
