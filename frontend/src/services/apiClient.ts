/**
 * Authenticated API client wrapper around fetch.
 *
 * Automatically attaches JWT Bearer tokens to every request and handles
 * 401 responses by attempting a transparent token refresh before retrying
 * the original request once. If refresh also fails, the user is logged out.
 */

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

// ── Storage keys (shared with AuthContext) ────────────────────────────────
const ACCESS_TOKEN_KEY = 'wiki4ai_access_token';
const REFRESH_TOKEN_KEY = 'wiki4ai_refresh_token';

// ── Helpers ───────────────────────────────────────────────────────────────

function getAccessToken(): string | null {
  return localStorage.getItem(ACCESS_TOKEN_KEY);
}

function getRefreshToken(): string | null {
  return localStorage.getItem(REFRESH_TOKEN_KEY);
}

function clearTokens(): void {
  localStorage.removeItem(ACCESS_TOKEN_KEY);
  localStorage.removeItem(REFRESH_TOKEN_KEY);
  // Also clear user info stored by AuthContext
  localStorage.removeItem('wiki4ai_user_info');
  localStorage.removeItem('wiki4ai_token_expiry');
}

/**
 * Attempt to refresh the access token using the stored refresh token.
 * Returns the new access token on success, or throws on failure.
 */
async function refreshToken(): Promise<string> {
  const currentRefresh = getRefreshToken();
  if (!currentRefresh) {
    throw new Error('No refresh token available');
  }

  const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ refreshToken: currentRefresh }),
  });

  if (!response.ok) {
    clearTokens();
    throw new Error('Token refresh failed');
  }

  const data = await response.json();
  // Store the new tokens
  localStorage.setItem(ACCESS_TOKEN_KEY, data.accessToken);
  if (data.refreshToken) {
    localStorage.setItem(REFRESH_TOKEN_KEY, data.refreshToken);
  }
  return data.accessToken;
}

// ── Request queue for serialising refresh attempts ────────────────────────
let isRefreshing = false;
let refreshPromise: Promise<string> | null = null;
const retryQueue: Array<{
  resolve: (token: string) => void;
  reject: (error: Error) => void;
}> = [];

function processRetryQueue(token: string): void {
  retryQueue.forEach(({ resolve }) => resolve(token));
  retryQueue.length = 0;
}

function processRetryQueueError(error: Error): void {
  retryQueue.forEach(({ reject }) => reject(error));
  retryQueue.length = 0;
}

/**
 * Get a fresh access token. If another refresh is already in progress,
 * queue this call and wait for the result.
 */
function waitForRefresh(): Promise<string> {
  return new Promise((resolve, reject) => {
    retryQueue.push({ resolve, reject });
  });
}

// ── Main API function ─────────────────────────────────────────────────────

export interface ApiOptions extends RequestInit {
  /** Skip authentication header (useful for login/register endpoints) */
  skipAuth?: boolean;
}

/**
 * Make an authenticated HTTP request.
 *
 * On 401 the client will automatically try to refresh the token and retry
 * the original request once. If refresh fails the user is redirected to
 * /login and all tokens are cleared.
 */
export async function apiRequest(
  url: string,
  options: ApiOptions = {},
): Promise<Response> {
  const { skipAuth = false, headers: extraHeaders = {}, ...restOptions } = options;

  // FormData bodies (file uploads) must NOT get an explicit Content-Type - the
  // browser sets `multipart/form-data; boundary=...` automatically when it
  // serializes the body, and a manually-set header here would override that
  // with no boundary, which the backend can't parse as multipart at all.
  const isFormData = typeof FormData !== 'undefined' && restOptions.body instanceof FormData;

  // Build headers
  const baseHeaders: Record<string, string> = {};
  if (!skipAuth && !isFormData && !(extraHeaders as Record<string, string>)['Content-Type']) {
    baseHeaders['Content-Type'] = 'application/json';
  }

  const headers = { ...baseHeaders, ...(extraHeaders as Record<string, string>) };

  // Attach auth token unless skipped
  if (!skipAuth) {
    const token = getAccessToken();
    if (token) {
      headers['Authorization'] = `Bearer ${token}`;
    }
  }

  let response = await fetch(url, { ...restOptions, headers });

  // ── Handle 401 with auto-refresh ──────────────────────────────────────
  if (response.status === 401 && !skipAuth) {
    if (isRefreshing) {
      // Another request is already refreshing – wait for it
      try {
        const newToken = await waitForRefresh();
        headers['Authorization'] = `Bearer ${newToken}`;
        response = await fetch(url, { ...restOptions, headers });
        return response;
      } catch {
        redirectToLogin();
        return response;
      }
    }

    isRefreshing = true;
    refreshPromise = refreshToken()
      .then((newToken) => {
        processRetryQueue(newToken);
        return newToken;
      })
      .catch((error) => {
        processRetryQueueError(error);
        redirectToLogin();
        throw error;
      })
      .finally(() => {
        isRefreshing = false;
        refreshPromise = null;
      });

    try {
      await refreshPromise;
      // Retry the original request with the new token
      headers['Authorization'] = `Bearer ${getAccessToken()!}`;
      response = await fetch(url, { ...restOptions, headers });
    } catch {
      // Redirect already happened in the .catch above
    }
  }

  return response;
}

/**
 * Redirect to login page, preserving the current path as a redirect parameter.
 */
function redirectToLogin(): void {
  const currentPath = window.location.pathname + window.location.search;
  window.location.href = `/login?redirect=${encodeURIComponent(currentPath)}`;
}

// ── Convenience methods ───────────────────────────────────────────────────

/**
 * Reject with a useful message for non-OK responses. Prefers the server's
 * "message" field (e.g. "Cannot create subproject: maximum hierarchy depth
 * of 5 levels would be exceeded") over the bare HTTP status text so UIs can
 * show the real reason to the user.
 */
async function rejectWithDetail(res: Response): Promise<never> {
  let detail = '';
  try {
    const data = await res.json();
    if (data && typeof data.message === 'string' && data.message.trim()) {
      detail = data.message;
    }
  } catch {
    // Non-JSON error body — fall back to the status line below.
  }
  throw new Error(detail ? `${res.status}: ${detail}` : `${res.status} ${res.statusText}`);
}

export function apiGet<T>(url: string): Promise<T> {
  return apiRequest(url, { method: 'GET' }).then((res) => {
    if (!res.ok) return rejectWithDetail(res);
    return res.json();
  });
}

export function apiPost<T>(url: string, body?: unknown): Promise<T> {
  return apiRequest(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  }).then((res) => {
    if (!res.ok) return rejectWithDetail(res);
    return res.json();
  });
}

export function apiPut<T>(url: string, body?: unknown): Promise<T> {
  return apiRequest(url, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: body !== undefined ? JSON.stringify(body) : undefined,
  }).then((res) => {
    if (!res.ok) return rejectWithDetail(res);
    return res.json();
  });
}

export function apiDelete<T>(url: string): Promise<T> {
  return apiRequest(url, { method: 'DELETE' }).then((res) => {
    if (!res.ok && res.status !== 204) return rejectWithDetail(res);
    return (res.headers.get('Content-Length') ? res.json() : {}) as T;
  });
}

export function apiPostFormData<T>(url: string, formData: FormData): Promise<T> {
  return apiRequest(url, {
    method: 'POST',
    body: formData,
  }).then((res) => {
    if (!res.ok) return rejectWithDetail(res);
    return res.json();
  });
}

export function apiGetBlob(url: string): Promise<Blob> {
  return apiRequest(url, { method: 'GET' }).then((res) => {
    if (!res.ok) return rejectWithDetail(res);
    return res.blob();
  });
}

/**
 * Parse the `redirect` query parameter from the current URL.
 * Used by login/register pages to redirect back after authentication.
 */
export function getRedirectFromUrl(): string | null {
  const params = new URLSearchParams(window.location.search);
  return params.get('redirect') || null;
}
