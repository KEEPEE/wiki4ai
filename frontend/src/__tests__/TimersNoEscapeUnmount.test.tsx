/**
 * WIKI4AI-179 — a timer must never outlive the component that started it.
 *
 * CI pipeline 2100 / job 6052 (`test-frontend`) failed on a commit whose diff
 * contained no file from `frontend/`:
 *
 *     ReferenceError: window is not defined
 *     ❯ dispatchSetState react-dom-client.development.js:9126:14
 *     ❯ Timeout._onTimeout src/pages/AdminUsersPage.tsx:73:7
 *     Test Files  63 passed (63) / Tests  804 passed (804) / Errors  1 error
 *
 * Every test passed and the job still failed, because the gate is the
 * *unhandled error* count: the toast timer fired after the jsdom teardown and
 * called `setToast(null)` on a component that was already gone.
 *
 * These tests unmount the component **while its timer is armed** and assert
 * that the scheduled callback never runs — the callback is the only thing that
 * calls `setState` in those paths, so "the setState never happened" is measured,
 * not inferred from the absence of a symptom. `armTimerProbe()` wraps every
 * callback the component hands to `setTimeout` and records whether it executed.
 *
 * Delete the unmount cleanup in `AdminUsersPage.tsx` / `ProjectDetail.tsx` /
 * `Toast.tsx` / `VaultEntryForm.tsx` and these tests fail.
 *
 * Note on fake timers: RTL's `waitFor` deadlocks under `vi.useFakeTimers()`
 * (its polling `setInterval` is fake and nothing advances it), so this file
 * drives time explicitly with `vi.advanceTimersByTime` inside `act`.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import type { Mock } from 'vitest';
import { render, screen, act, fireEvent, cleanup } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import AdminUsersPage from '../pages/AdminUsersPage';
import ProjectDetail from '../pages/ProjectDetail';
import ToastContainer from '../components/Toast';
import VaultEntryForm from '../components/VaultEntryForm';
import { AuthProvider, useAuth } from '../contexts/AuthContext';
import type { ToastItem } from '../components/Toast';

vi.mock('../services/adminApi', () => ({
  listUsers: vi.fn(),
  createUser: vi.fn(),
  updateUserRole: vi.fn(),
  deleteUser: vi.fn(),
}));

vi.mock('../hooks/useProjects', () => ({ useProjects: vi.fn() }));
vi.mock('../hooks/useDocuments', () => ({
  useDocuments: vi.fn(),
  useSearchDocuments: vi.fn(),
}));
vi.mock('../services/projectApi', () => ({
  projectApi: { exportProject: vi.fn() },
}));
vi.mock('../hooks/useEmbeddingStatus', () => ({
  useEmbeddingStatus: vi.fn(() => ({ data: undefined })),
}));

const mockAdminApi = await import('../services/adminApi');
const { useProjects } = await import('../hooks/useProjects');
const { useDocuments, useSearchDocuments } = await import('../hooks/useDocuments');

/* ── timer probe ─────────────────────────────────────────────────────────── */

interface ScheduledCall {
  delay: number;
  /** true once the callback actually executed — i.e. once its setState ran. */
  ran: boolean;
}

let spy: Mock | null = null;
let scheduled: ScheduledCall[] = [];

/**
 * Wrap every callback the component schedules with `setTimeout` and record
 * whether it executed. The wrapper delegates to the fake clock, so
 * `vi.advanceTimersByTime` still drives it.
 */
function armTimerProbe(): ScheduledCall[] {
  const fakeSetTimeout = window.setTimeout.bind(window);
  scheduled = [];
  spy = vi.spyOn(window, 'setTimeout').mockImplementation(((fn: unknown, delay?: number) => {
    const entry: ScheduledCall = { delay: delay ?? 0, ran: false };
    scheduled.push(entry);
    return fakeSetTimeout(() => {
      entry.ran = true;
      (fn as () => void)();
    }, delay) as unknown as ReturnType<typeof setTimeout>;
  }) as typeof window.setTimeout);
  return scheduled;
}

/** Callbacks scheduled with exactly `delay` ms. */
const withDelay = (delay: number) => scheduled.filter((c) => c.delay === delay);
/** Callbacks with exactly `delay` ms that actually ran (their setState ran). */
const ranWithDelay = (delay: number) => withDelay(delay).filter((c) => c.ran);

/** Drain pending promise callbacks (mocked API resolutions) without running a timer. */
async function settle() {
  await act(async () => {
    for (let i = 0; i < 12; i++) await Promise.resolve();
  });
}

async function advance(ms: number) {
  await act(async () => {
    vi.advanceTimersByTime(ms);
  });
}

/* ── AdminUsersPage fixtures (same shape as AdminUsersPage.test.tsx) ─────── */

function createFakeJwt(): string {
  const header = btoa(JSON.stringify({ alg: 'HS256', typ: 'JWT' }));
  const futureExp = Math.floor(Date.now() / 1000) + 3600;
  const payload = btoa(JSON.stringify({ sub: '1', exp: futureExp, iat: Date.now() / 1000 }));
  return `${header}.${payload}.${btoa('fake-signature')}`;
}

function WaitForAuth({ children }: { children: React.ReactNode }) {
  const { isLoading } = useAuth();
  if (isLoading) return <div data-testid="auth-loading">Loading auth...</div>;
  return <>{children}</>;
}

function renderAdminPage() {
  localStorage.setItem('wiki4ai_access_token', createFakeJwt());
  localStorage.setItem('wiki4ai_refresh_token', 'fake-refresh-token');
  localStorage.setItem(
    'wiki4ai_user_info',
    JSON.stringify({
      id: 1,
      username: 'test_admin',
      email: 'admin@test.com',
      role: 'ADMIN',
      createdAt: '',
    }),
  );

  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={['/admin/users']}>
        <Routes>
          <Route
            path="/admin/users"
            element={<WaitForAuth><AdminUsersPage /></WaitForAuth>}
          />
          <Route path="/login" element={<div data-testid="login-page">Login Page</div>} />
        </Routes>
      </MemoryRouter>
    </AuthProvider>,
  );
}

const EMPTY_PAGE = { content: [], totalElements: 0, totalPages: 0, number: 0, size: 100 };

/* ── ProjectDetail fixtures ──────────────────────────────────────────────── */

function renderProjectDetail() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: 0 } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/projects/test-project']}>
        <Routes>
          <Route path="/projects/:slug" element={<ProjectDetail />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/** A `uploadDocument` that stays pending until the test resolves it. */
function pendingUpload() {
  let resolve!: (value: unknown) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { uploadDocument: vi.fn(() => promise), resolve, reject };
}

function mockProjectDetailHooks(docs: Record<string, unknown> = {}) {
  vi.mocked(useProjects).mockReturnValue({
    projects: [
      { id: 1, name: 'Test Project', slug: 'test-project', description: null, documentCount: 0, createdAt: '', updatedAt: '' },
    ],
    isLoading: false,
    error: null,
    refetch: vi.fn(),
    createProject: vi.fn(),
    updateProject: vi.fn(),
    deleteProject: vi.fn(),
    isCreating: false,
    isUpdating: false,
    isDeleting: false,
  } as never);
  vi.mocked(useDocuments).mockReturnValue({
    documents: [],
    isLoading: false,
    error: null,
    refetch: vi.fn(),
    createDocument: vi.fn(),
    updateDocument: vi.fn(),
    deleteDocument: vi.fn(),
    uploadDocument: vi.fn().mockResolvedValue({}),
    isCreating: false,
    isUpdating: false,
    isDeleting: false,
    isUploading: false,
    ...docs,
  } as never);
  vi.mocked(useSearchDocuments).mockReturnValue({
    searchResults: [],
    isLoading: false,
    hasSearched: false,
  } as never);
}

function selectMarkdownFile() {
  const fileInput = screen.getByTestId('import-file-input') as HTMLInputElement;
  const file = new File(['# Uploaded\n'], 'uploaded.md', { type: 'text/markdown' });
  Object.defineProperty(fileInput, 'files', { value: [file], writable: true, configurable: true });
  fileInput.dispatchEvent(new Event('change', { bubbles: true }));
  return file;
}

/* ── lifecycle ───────────────────────────────────────────────────────────── */

beforeEach(() => {
  vi.useFakeTimers();
  localStorage.clear();
  vi.clearAllMocks();
});

afterEach(() => {
  cleanup();
  spy?.mockRestore();
  spy = null;
  scheduled = [];
  vi.useRealTimers();
});

/* ── tests ───────────────────────────────────────────────────────────────── */

describe('WIKI4AI-179: timers do not survive unmount', () => {
  it('AdminUsersPage: the toast is dismissed by its timer while the page is mounted', async () => {
    // Precondition for the regression test below: the 3 s dismissal timer is
    // real and it does call `setToast(null)` when the page is alive.
    armTimerProbe();
    vi.mocked(mockAdminApi.listUsers).mockResolvedValue(EMPTY_PAGE as never);
    vi.mocked(mockAdminApi.createUser).mockResolvedValue({ id: 2 } as never);

    renderAdminPage();
    await settle();

    fireEvent.submit(screen.getByTestId('create-user-form'));
    await settle();

    expect(screen.getByTestId('toast-notification')).toBeInTheDocument();
    expect(withDelay(3000)).toHaveLength(1);
    expect(ranWithDelay(3000)).toHaveLength(0);

    await advance(3000);

    expect(ranWithDelay(3000)).toHaveLength(1);
    expect(screen.queryByTestId('toast-notification')).not.toBeInTheDocument();
  });

  it('AdminUsersPage: unmounting with the toast timer armed never calls setToast', async () => {
    armTimerProbe();
    vi.mocked(mockAdminApi.listUsers).mockResolvedValue(EMPTY_PAGE as never);
    vi.mocked(mockAdminApi.createUser).mockResolvedValue({ id: 2 } as never);

    const { unmount } = renderAdminPage();
    await settle();

    fireEvent.submit(screen.getByTestId('create-user-form'));
    await settle();

    // The toast is on screen and its dismissal timer is armed but not fired.
    expect(screen.getByTestId('toast-notification')).toBeInTheDocument();
    const armed = withDelay(3000);
    expect(armed).toHaveLength(1);
    expect(armed[0].ran).toBe(false);

    unmount();
    // Far past the 3 s the timer was scheduled for.
    await advance(30_000);

    // The callback that calls `setToast(null)` never executed. This is the
    // exact call that produced `dispatchSetState` → `window is not defined`.
    expect(ranWithDelay(3000)).toHaveLength(0);
    expect(armed[0].ran).toBe(false);
    // Nothing re-armed it either.
    expect(withDelay(3000)).toHaveLength(1);
  });

  it('ProjectDetail: the upload progress timer runs while the page is mounted', async () => {
    // Precondition: the chained setTimeout ramp really advances the bar.
    armTimerProbe();
    const upload = pendingUpload();
    mockProjectDetailHooks({ uploadDocument: upload.uploadDocument });

    renderProjectDetail();
    await settle();
    selectMarkdownFile();

    expect(withDelay(200)).toHaveLength(1);
    expect(ranWithDelay(200)).toHaveLength(0);

    await advance(600);

    // 200 ms / 400 ms / 600 ms ticks ran → 30 % and the chain is still armed.
    expect(ranWithDelay(200)).toHaveLength(3);
    expect(withDelay(200)).toHaveLength(4);
    const bar = document.querySelector('.upload-progress-bar') as HTMLElement | null;
    expect(bar).not.toBeNull();
    expect(bar!.style.width).toBe('30%');

    upload.resolve({});
    await settle();
  });

  it('ProjectDetail: unmounting mid-upload never calls setUploadProgress again', async () => {
    armTimerProbe();
    const upload = pendingUpload();
    mockProjectDetailHooks({ uploadDocument: upload.uploadDocument });

    const { container, unmount } = renderProjectDetail();
    await settle();
    selectMarkdownFile();

    await advance(600);
    expect(ranWithDelay(200)).toHaveLength(3);

    // A tick is pending at the moment the page goes away.
    const armedAtUnmount = withDelay(200).filter((c) => !c.ran);
    expect(armedAtUnmount.length).toBeGreaterThan(0);
    const scheduledBeforeUnmount = scheduled.length;

    unmount();
    await advance(10_000);

    // No armed tick ran after unmount → `setUploadProgress` was never called.
    expect(armedAtUnmount.filter((c) => c.ran)).toHaveLength(0);
    // And the chain did not re-arm itself from a post-unmount tick.
    expect(scheduled.length).toBe(scheduledBeforeUnmount);
    expect(withDelay(200).filter((c) => c.ran).length).toBe(3);
    expect(container.querySelector('.upload-progress-bar')).toBeNull();

    // The upload resolving after teardown must not reach the component either.
    upload.resolve({});
    await settle();
    expect(scheduled.length).toBe(scheduledBeforeUnmount);
  });

  it('Toast: the slide-out timer dismisses the toast while the parent is mounted', async () => {
    // Precondition for the unmount assertion below.
    armTimerProbe();
    const onDismiss = vi.fn();
    const item: ToastItem = { id: 7, message: 'Saved', type: 'success' };

    render(<ToastContainer toasts={[item]} onDismiss={onDismiss} />);

    fireEvent.click(screen.getByRole('button', { name: /dismiss/i }));
    expect(withDelay(300)).toHaveLength(1);
    expect(onDismiss).not.toHaveBeenCalled();

    await advance(300);
    expect(onDismiss).toHaveBeenCalledWith(7);
  });

  it('Toast: unmounting during the slide-out never calls onDismiss', async () => {
    armTimerProbe();
    const onDismiss = vi.fn();
    const item: ToastItem = { id: 8, message: 'Saved', type: 'success' };

    const { unmount } = render(<ToastContainer toasts={[item]} onDismiss={onDismiss} />);

    fireEvent.click(screen.getByRole('button', { name: /dismiss/i }));
    expect(withDelay(300)).toHaveLength(1);

    unmount();
    await advance(10_000);

    // `onDismiss` is the parent's `setToasts` call — it must not happen.
    expect(onDismiss).not.toHaveBeenCalled();
    expect(ranWithDelay(300)).toHaveLength(0);
    // The 5 s auto-dismiss timer was cancelled too.
    expect(ranWithDelay(5000)).toHaveLength(0);
  });

  it('VaultEntryForm: unmounting during the blur close never calls setShowGroupSuggestions', async () => {
    armTimerProbe();
    const onSubmit = vi.fn(async () => undefined);
    const onCancel = vi.fn();

    const { unmount } = render(
      <VaultEntryForm onSubmit={onSubmit} onCancel={onCancel} existingGroups={['/Work']} />,
    );

    const groupInput = screen.getByTestId('vault-form-group-path-input');
    fireEvent.focus(groupInput);
    expect(screen.getByTestId('vault-form-group-suggestion--Work')).toBeInTheDocument();

    fireEvent.blur(groupInput);
    const armed = withDelay(150);
    expect(armed).toHaveLength(1);
    expect(armed[0].ran).toBe(false);

    unmount();
    await advance(10_000);

    expect(ranWithDelay(150)).toHaveLength(0);
    expect(armed[0].ran).toBe(false);
  });

  it('VaultEntryForm: the blur close still works while the form is mounted', async () => {
    // Precondition: the 150 ms timer is the thing that hides the suggestions.
    armTimerProbe();
    const onSubmit = vi.fn(async () => undefined);
    const onCancel = vi.fn();

    render(<VaultEntryForm onSubmit={onSubmit} onCancel={onCancel} existingGroups={['/Work']} />);

    const groupInput = screen.getByTestId('vault-form-group-path-input');
    fireEvent.focus(groupInput);
    expect(screen.getByTestId('vault-form-group-suggestion--Work')).toBeInTheDocument();

    fireEvent.blur(groupInput);
    await advance(150);

    expect(ranWithDelay(150)).toHaveLength(1);
    expect(screen.queryByTestId('vault-form-group-suggestion--Work')).not.toBeInTheDocument();
  });
});
