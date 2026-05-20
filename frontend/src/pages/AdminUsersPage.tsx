import { useState, useCallback, useEffect, useRef } from 'react';
import { useAuth } from '../contexts/AuthContext';
import {
  listUsers,
  createUser,
  updateUserRole,
  deleteUser,
  type AdminUserDTO,
  type CreateUserRequest,
  type ChangeRoleRequest,
} from '../services/adminApi';
import './AdminUsers.css';

export default function AdminUsersPage() {
  const { user, isLoading: isAuthLoading } = useAuth();
  // Cast to access role property stored in localStorage but not typed in UserInfo
  const isAdmin = (user as any)?.role === 'ADMIN';
  const currentUserId = (user as any)?.id ?? null;

  // Users list state
  const [users, setUsers] = useState<AdminUserDTO[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  // Create user form state
  const [createUsername, setCreateUsername] = useState('');
  const [createEmail, setCreateEmail] = useState('');
  const [createPassword, setCreatePassword] = useState('');
  const [createRole, setCreateRole] = useState<'ADMIN' | 'USER'>('USER');
  const [createMessage, setCreateMessage] = useState<string | null>(null);
  const [createError, setCreateError] = useState<string | null>(null);
  const [isCreating, setIsCreating] = useState(false);

  // Toast notification state
  const [toast, setToast] = useState<{ message: string; type: 'success' | 'error' } | null>(null);
  const toastTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Delete confirmation dialog state
  const [deleteConfirmUser, setDeleteConfirmUser] = useState<AdminUserDTO | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);

  // Show toast notification for 3 seconds
  const showToast = useCallback((message: string, type: 'success' | 'error') => {
    if (toastTimerRef.current) {
      clearTimeout(toastTimerRef.current);
    }
    setToast({ message, type });
    toastTimerRef.current = setTimeout(() => {
      setToast(null);
      toastTimerRef.current = null;
    }, 3000);
  }, []);

  // Load users on mount (only if admin)
  const loadUsers = useCallback(async () => {
    try {
      const response = await listUsers(0, 100);
      setUsers(response.content || []);
    } catch (err) {
      const msg = err instanceof Error ? err.message : 'Failed to load users';
      if (msg.includes('403') || msg.includes('Forbidden')) {
        setLoadError('Access denied. Admin role required.');
      } else {
        setLoadError(msg);
      }
    } finally {
      setIsLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!isAuthLoading && isAdmin) {
      loadUsers();
    } else if (!isAuthLoading && !isAdmin) {
      // Non-admin: no need to load users, stop loading immediately
      setIsLoading(false);
    }
  }, [isAuthLoading, isAdmin, loadUsers]);

  const handleCreateUser = useCallback(
    async (e: React.FormEvent) => {
      e.preventDefault();
      setCreateMessage(null);
      setCreateError(null);
      setIsCreating(true);

      const request: CreateUserRequest = {
        username: createUsername.trim(),
        email: createEmail.trim(),
        password: createPassword,
        role: createRole,
      };

      try {
        await createUser(request);
        setCreateMessage('User created successfully');
        showToast('User created successfully', 'success');
        // Reset form
        setCreateUsername('');
        setCreateEmail('');
        setCreatePassword('');
        setCreateRole('USER');
        // Reload users list
        await loadUsers();
      } catch (err) {
        const msg = err instanceof Error ? err.message : 'Failed to create user';
        setCreateError(msg);
        showToast(msg, 'error');
      } finally {
        setIsCreating(false);
      }
    },
    [createUsername, createEmail, createPassword, createRole, loadUsers, showToast],
  );

  // Handle role change via dropdown
  const handleRoleChange = useCallback(
    async (userId: number, newRole: 'ADMIN' | 'USER') => {
      try {
        const request: ChangeRoleRequest = { role: newRole };
        await updateUserRole(userId, request);

        // Optimistic update
        setUsers((prev) =>
          prev.map((u) => (u.id === userId ? { ...u, role: newRole } : u)),
        );
        showToast(`Role updated to ${newRole}`, 'success');
      } catch (err) {
        const msg = err instanceof Error ? err.message : 'Failed to update role';
        showToast(msg, 'error');
        // Reload to restore correct state on error
        await loadUsers();
      }
    },
    [loadUsers, showToast],
  );

  // Handle delete confirmation
  const handleDeleteConfirm = useCallback(
    async (userId: number) => {
      setIsDeleting(true);
      try {
        await deleteUser(userId);
        setDeleteConfirmUser(null);
        // Remove from local state
        setUsers((prev) => prev.filter((u) => u.id !== userId));
        showToast('User deleted successfully', 'success');
      } catch (err) {
        const msg = err instanceof Error ? err.message : 'Failed to delete user';
        showToast(msg, 'error');
      } finally {
        setIsDeleting(false);
      }
    },
    [showToast],
  );

  // Wait for auth context to load before checking admin status
  if (isAuthLoading) {
    return (
      <div className="admin-users-page">
        <div className="loading-state">
          <div className="spinner" />
          <p>Loading...</p>
        </div>
      </div>
    );
  }

  // Non-admin access denied view
  if (!isAdmin) {
    return (
      <div className="admin-users-page">
        <div className="access-denied" data-testid="access-denied">
          <h1>403 - Access Denied</h1>
          <p>You need ADMIN role to access this page.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="admin-users-page">
      <h1 className="page-title">User Management</h1>

      {/* Toast Notification */}
      {toast && (
        <div
          className={`toast-notification toast-${toast.type}`}
          role="alert"
          data-testid="toast-notification"
        >
          {toast.message}
        </div>
      )}

      {/* Delete Confirmation Dialog */}
      {deleteConfirmUser && (
        <div className="modal-overlay" data-testid="delete-confirm-overlay" onClick={() => setDeleteConfirmUser(null)}>
          <div className="modal-content" onClick={(e) => e.stopPropagation()}>
            <h2 className="modal-title">Confirm Delete</h2>
            <p className="modal-text">
              Are you sure you want to delete user <strong>{deleteConfirmUser.username}</strong>?
              This action cannot be undone.
            </p>
            <div className="modal-actions">
              <button
                className="btn btn-secondary"
                onClick={() => setDeleteConfirmUser(null)}
                data-testid="delete-cancel-btn"
                disabled={isDeleting}
              >
                Cancel
              </button>
              <button
                className="btn btn-danger"
                onClick={() => handleDeleteConfirm(deleteConfirmUser.id)}
                data-testid="delete-confirm-btn"
                disabled={isDeleting}
              >
                {isDeleting ? 'Deleting...' : 'Delete'}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Create User Form */}
      <section className="admin-section" data-testid="create-user-section">
        <h2 className="section-title">Create New User</h2>

        {createMessage && (
          <div className="success-message" role="alert" data-testid="create-success">
            {createMessage}
          </div>
        )}
        {createError && (
          <div className="error-message" role="alert" data-testid="create-error">
            {createError}
          </div>
        )}

        <form onSubmit={handleCreateUser} className="admin-form" data-testid="create-user-form">
          <div className="form-row">
            <div className="form-group">
              <label htmlFor="new-username" className="form-label">Username</label>
              <input
                id="new-username"
                type="text"
                value={createUsername}
                onChange={(e) => setCreateUsername(e.target.value)}
                className="form-input"
                placeholder="Username (2-50 chars)"
                required
                minLength={2}
                maxLength={50}
                data-testid="create-username"
              />
            </div>

            <div className="form-group">
              <label htmlFor="new-email" className="form-label">Email</label>
              <input
                id="new-email"
                type="email"
                value={createEmail}
                onChange={(e) => setCreateEmail(e.target.value)}
                className="form-input"
                placeholder="user@example.com"
                required
                data-testid="create-email"
              />
            </div>
          </div>

          <div className="form-row">
            <div className="form-group">
              <label htmlFor="new-password" className="form-label">Password</label>
              <input
                id="new-password"
                type="password"
                value={createPassword}
                onChange={(e) => setCreatePassword(e.target.value)}
                className="form-input"
                placeholder="Min 8 characters"
                required
                minLength={8}
                data-testid="create-password"
              />
            </div>

            <div className="form-group">
              <label htmlFor="new-role" className="form-label">Role</label>
              <select
                id="new-role"
                value={createRole}
                onChange={(e) => setCreateRole(e.target.value as 'ADMIN' | 'USER')}
                className="form-input form-select"
                data-testid="create-role"
              >
                <option value="USER">USER</option>
                <option value="ADMIN">ADMIN</option>
              </select>
            </div>
          </div>

          <button
            type="submit"
            className="btn btn-primary"
            disabled={isCreating}
            data-testid="create-user-btn"
          >
            {isCreating ? 'Creating...' : 'Create User'}
          </button>
        </form>
      </section>

      {/* Users Table */}
      <section className="admin-section" data-testid="users-list-section">
        <h2 className="section-title">All Users ({users.length})</h2>

        {loadError && (
          <div className="error-message" role="alert" data-testid="load-error">
            {loadError}
          </div>
        )}

        {isLoading ? (
          <div className="loading-state">
            <div className="spinner" />
            <p>Loading users...</p>
          </div>
        ) : (
          <div className="table-wrapper">
            <table className="admin-table" data-testid="users-table">
              <thead>
                <tr>
                  <th>ID</th>
                  <th>Username</th>
                  <th>Email</th>
                  <th>Role</th>
                  <th>Created</th>
                  <th>Actions</th>
                </tr>
              </thead>
              <tbody>
                {users.map((u) => {
                  const isSelf = u.id === currentUserId;
                  return (
                    <tr key={u.id} data-testid={`user-row-${u.id}`}>
                      <td className="cell-id">{u.id}</td>
                      <td className="cell-username" data-testid={`user-username-${u.username}`}>{u.username}</td>
                      <td className="cell-email">{u.email}</td>
                      <td>
                        <select
                          className="role-select form-select"
                          value={u.role}
                          onChange={(e) => handleRoleChange(u.id, e.target.value as 'ADMIN' | 'USER')}
                          data-testid={`user-role-select-${u.id}`}
                        >
                          <option value="USER">USER</option>
                          <option value="ADMIN">ADMIN</option>
                        </select>
                      </td>
                      <td className="cell-date">
                        {new Date(u.createdAt).toLocaleDateString()}
                      </td>
                      <td className="cell-actions">
                        <button
                          className="btn btn-sm btn-danger-outline"
                          onClick={() => setDeleteConfirmUser(u)}
                          disabled={isSelf}
                          title={isSelf ? 'Cannot delete your own account' : `Delete ${u.username}`}
                          data-testid={`delete-user-btn-${u.id}`}
                        >
                          {isSelf ? '🔒' : '🗑️'}
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
