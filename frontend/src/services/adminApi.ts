/**
 * API service for Admin operations.
 * All endpoints require ADMIN role. Returns 403 if the current user is not an admin.
 */

import { apiGet, apiPost } from './apiClient';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

export interface AdminUserDTO {
  id: number;
  username: string;
  email: string;
  role: 'ADMIN' | 'USER';
  createdAt: string;
}

export interface AdminUsersPageResponse {
  content: AdminUserDTO[];
  totalElements: number;
  totalPages: number;
  number: number; // current page index (0-based)
  size: number;
}

export interface CreateUserRequest {
  username: string;
  email: string;
  password: string;
  role: 'ADMIN' | 'USER';
}

/**
 * Get paginated list of all users. Admin only.
 */
export async function listUsers(page = 0, size = 20, search?: string): Promise<AdminUsersPageResponse> {
  const params = new URLSearchParams({ page: String(page), size: String(size) });
  if (search) params.set('search', search);
  return apiGet(`${API_BASE_URL}/admin/users?${params.toString()}`);
}

/**
 * Create a new user. Admin only.
 */
export async function createUser(request: CreateUserRequest): Promise<AdminUserDTO> {
  return apiPost(`${API_BASE_URL}/admin/users`, request);
}
