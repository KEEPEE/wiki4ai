import { useState, useCallback, useEffect } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { listUsers, createUser, type AdminUserDTO, type CreateUserRequest } from '../services/adminApi';
import './AdminUsers.css';

export default function AdminUsersPage() {
  const { user, isLoading: isAuthLoading } = useAuth();
  // Cast to access role property stored in localStorage but not typed in UserInfo
  const isAdmin = (user as any)?.role === 'ADMIN';

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
    if (isAdmin) {
      loadUsers();
    }
  }, [isAdmin, loadUsers]);

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
      } finally {
        setIsCreating(false);
      }
    },
    [createUsername, createEmail, createPassword, createRole, loadUsers],
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
                </tr>
              </thead>
              <tbody>
                {users.map((u) => (
                  <tr key={u.id} data-testid={`user-row-${u.id}`}>
                    <td className="cell-id">{u.id}</td>
                    <td className="cell-username" data-testid={`user-username-${u.username}`}>{u.username}</td>
                    <td className="cell-email">{u.email}</td>
                    <td>
                      <span
                        className={`role-badge role-${u.role.toLowerCase()}`}
                        data-testid={`user-role-${u.id}`}
                      >
                        {u.role}
                      </span>
                    </td>
                    <td className="cell-date">
                      {new Date(u.createdAt).toLocaleDateString()}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
