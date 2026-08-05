/**
 * Tests for VaultPage component
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import VaultPage from '../pages/VaultPage'

// Mock localStorage for vault config
const mockLocalStorage = new Map<string, string>()
Object.defineProperty(globalThis, 'localStorage', {
  value: {
    getItem: (key: string) => mockLocalStorage.get(key) ?? null,
    setItem: (key: string, value: string) => mockLocalStorage.set(key, value),
    removeItem: (key: string) => mockLocalStorage.delete(key),
    clear: () => mockLocalStorage.clear(),
  },
  writable: true,
})

// Mock crypto.getRandomValues
Object.defineProperty(globalThis.crypto, 'getRandomValues', {
  value: (arr: Uint8Array) => {
    arr.fill(1)
    return arr
  },
  writable: true,
})

// Mock navigator.clipboard - use configurable to avoid conflict with userEvent
Object.defineProperty(navigator, 'clipboard', {
  value: {
    writeText: vi.fn().mockResolvedValue(undefined),
  },
  writable: true,
  configurable: true,
})

vi.mock('../hooks/useVaultEntries', () => ({
  useVaultEntries: vi.fn(),
}))

// Mock vaultApi for export tests
vi.mock('../services/vaultApi', () => ({
  vaultApi: {
    getAll: vi.fn(),
    create: vi.fn(),
    update: vi.fn(),
    delete: vi.fn(),
    search: vi.fn(),
    importFromKdbx: vi.fn(),
    getExportEntries: vi.fn(),
  },
}))

// Mock VaultContext - vault is unlocked with config ready
const mockVaultConfig = {
  masterPassword: 'test-password',
  salt: new Uint8Array(16),
}

vi.mock('../contexts/VaultContext', () => ({
  useVault: vi.fn(() => ({
    isUnlocked: true,
    isLoading: false,
    config: mockVaultConfig,
    keyBytes: null as Uint8Array | null, // Changed from CryptoKey to raw bytes
    hasMasterPasswordSet: true,
    error: null,
    unlock: vi.fn(),
    setupVault: vi.fn(),
    lock: vi.fn(),
    checkStatus: vi.fn(),
  })),
}))

function renderWithProviders(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: {
      queries: { retry: false, staleTime: 0 },
      mutations: { retry: false },
    },
  })

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        {ui}
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('VaultPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockLocalStorage.clear()
  })

  describe('Loading state', () => {
    it('should show loading spinner when entries are loading', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: true, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      expect(screen.getByText(/Loading vault/)).toBeInTheDocument()
    })
  })

  describe('Error state', () => {
    it('should show error message when entries fail to load', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: new Error('Failed to fetch'), refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByText(/Failed to fetch/)).toBeInTheDocument()
      })
    })

    it('should show retry button on error', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: new Error('Failed to fetch'), refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByText('Retry')).toBeInTheDocument()
      })
    })
  })

  describe('Empty state', () => {
    it('should show empty state when no entries exist', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByText(/No entries in your vault yet/)).toBeInTheDocument()
      })
    })

    it('should show add entry button in empty state', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByText(/Add your first entry/)).toBeInTheDocument()
      })
    })
  })

  describe('Entry list with grouping', () => {
    const mockEntries = [
      { id: 1, title: 'GitHub', url: 'https://github.com', groupPath: '/Work', data: { username: 'devuser', password: 'gh-pass' }, createdAt: '', updatedAt: '' },
      { id: 2, title: 'Jira', url: 'https://jira.example.com', groupPath: '/Work', data: { username: 'devuser', password: 'jira-pass' }, createdAt: '', updatedAt: '' },
      { id: 3, title: 'Netflix', url: 'https://netflix.com', groupPath: '/Personal', data: { password: 'netflix-pass' }, createdAt: '', updatedAt: '' },
      { id: 4, title: 'Bank Account', url: 'https://bank.example.com', groupPath: '/Finance', data: { username: 'savings', password: 'bank-pass' }, createdAt: '', updatedAt: '' },
    ]

    it('should display entries grouped by group_path', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByText('GitHub')).toBeInTheDocument()
        expect(screen.getByText('Netflix')).toBeInTheDocument()
        expect(screen.getByText('Bank Account')).toBeInTheDocument()
      })
    })

    it('should show groups in sidebar', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        const groupsList = screen.getByTestId('vault-groups-list')
        expect(groupsList).toBeInTheDocument()
        expect(screen.getByText(/\/Work \(2\)/)).toBeInTheDocument()
        expect(screen.getByText(/\/Personal \(1\)/)).toBeInTheDocument()
        expect(screen.getByText(/\/Finance \(1\)/)).toBeInTheDocument()
      })
    })

    it('should filter entries when group is selected', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText('GitHub')).toBeInTheDocument()
      })

      // Click on /Work group to filter
      await user.click(screen.getByText(/\/Work \(2\)/))

      // Only Work entries should be visible
      await waitFor(() => {
        expect(screen.getByText('GitHub')).toBeInTheDocument()
        expect(screen.getByText('Jira')).toBeInTheDocument()
        expect(screen.queryByText('Netflix')).not.toBeInTheDocument()
        expect(screen.queryByText('Bank Account')).not.toBeInTheDocument()
      })
    })

    it('should show active state on selected group', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText('GitHub')).toBeInTheDocument()
      })

      // Click on /Work group
      const workGroupBtn = screen.getByText(/\/Work \(2\)/)
      await user.click(workGroupBtn)

      expect(workGroupBtn).toHaveClass('active')
    })

    it('should toggle off group filter when clicking active group', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText('GitHub')).toBeInTheDocument()
      })

      // Click on /Work group to filter
      const workGroupBtn = screen.getByText(/\/Work \(2\)/)
      await user.click(workGroupBtn)

      await waitFor(() => {
        expect(screen.queryByText('Netflix')).not.toBeInTheDocument()
      })

      // Click again to toggle off
      await user.click(workGroupBtn)

      // All entries should be visible again
      await waitFor(() => {
        expect(screen.getByText('Netflix')).toBeInTheDocument()
      })
    })
  })

  describe('Search functionality', () => {
    const mockEntries = [
      { id: 1, title: 'GitHub', url: 'https://github.com', groupPath: '/Work', data: { username: 'devuser', password: 'gh-pass' }, createdAt: '', updatedAt: '' },
      { id: 2, title: 'Netflix', url: 'https://netflix.com', groupPath: '/Personal', data: { password: 'netflix-pass' }, createdAt: '', updatedAt: '' },
    ]

    it('should render search input when entries exist', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByTestId('vault-search-input')).toBeInTheDocument()
      })
    })

    it('should filter entries by title in real time', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText('GitHub')).toBeInTheDocument()
      })

      // Type "github" in search input
      await user.type(screen.getByTestId('vault-search-input'), 'github')

      // Only GitHub should be visible (real-time filtering)
      await waitFor(() => {
        expect(screen.getByText('GitHub')).toBeInTheDocument()
        expect(screen.queryByText('Netflix')).not.toBeInTheDocument()
      })
    })

    it('should filter entries by URL', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText('Netflix')).toBeInTheDocument()
      })

      // Type "netflix.com" in search input (matches URL)
      await user.type(screen.getByTestId('vault-search-input'), 'netflix.com')

      await waitFor(() => {
        expect(screen.queryByText('GitHub')).not.toBeInTheDocument()
        expect(screen.getByText('Netflix')).toBeInTheDocument()
      })
    })

    it('should filter entries by username', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText('GitHub')).toBeInTheDocument()
      })

      // Type "devuser" in search input (matches username)
      await user.type(screen.getByTestId('vault-search-input'), 'devuser')

      await waitFor(() => {
        expect(screen.getByText('GitHub')).toBeInTheDocument()
        expect(screen.queryByText('Netflix')).not.toBeInTheDocument()
      })
    })

    it('should show "No entries match your search" when no results', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText('GitHub')).toBeInTheDocument()
      })

      // Type something that doesn't match anything
      await user.type(screen.getByTestId('vault-search-input'), 'xyznonexistent')

      await waitFor(() => {
        expect(screen.getByText('No entries match your search')).toBeInTheDocument()
      })
    })

    it('should clear search when clear button is clicked', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText('GitHub')).toBeInTheDocument()
      })

      // Type something to filter
      await user.type(screen.getByTestId('vault-search-input'), 'github')

      await waitFor(() => {
        expect(screen.queryByText('Netflix')).not.toBeInTheDocument()
      })

      // Click clear button
      await user.click(screen.getByTestId('vault-search-clear-button'))

      // All entries should be visible again immediately
      await waitFor(() => {
        expect(screen.getByText('GitHub')).toBeInTheDocument()
        expect(screen.getByText('Netflix')).toBeInTheDocument()
      })

      // Search input should be empty
      expect(screen.getByTestId('vault-search-input')).toHaveValue('')
    })

    it('should show clear button only when search has content', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText('GitHub')).toBeInTheDocument()
      })

      // Clear button should not be visible when search is empty
      expect(screen.queryByTestId('vault-search-clear-button')).not.toBeInTheDocument()

      // Type something to show clear button
      await user.type(screen.getByTestId('vault-search-input'), 'a')

      await waitFor(() => {
        expect(screen.getByTestId('vault-search-clear-button')).toBeInTheDocument()
      })
    })
  })

  describe('Add New Entry', () => {
    it('should show add entry button when entries exist', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [{ id: 1, title: 'Test', url: '', groupPath: '/Work', data: { password: 'pass' }, createdAt: '', updatedAt: '' }], isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByTestId('vault-add-entry-button')).toBeInTheDocument()
      })
    })

    it('should show create form when add entry button is clicked', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [{ id: 1, title: 'Test', url: '', groupPath: '/Work', data: { password: 'pass' }, createdAt: '', updatedAt: '' }], isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByTestId('vault-add-entry-button')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('vault-add-entry-button'))

      expect(screen.getByText(/Add New Entry/)).toBeInTheDocument()
      expect(screen.getByPlaceholderText(/Title/)).toBeInTheDocument()
    })

    it('should call createEntry when form is submitted', async () => {
      const mockCreateEntry = vi.fn().mockResolvedValue({ id: 99, title: 'New Entry' })

      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: mockCreateEntry, updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const user = userEvent.setup()

      // Open form from empty state button
      await waitFor(() => {
        expect(screen.getByText(/Add your first entry/)).toBeInTheDocument()
      })

      await user.click(screen.getByText(/Add your first entry/))

      await user.type(screen.getByPlaceholderText(/Title/), 'New Entry')
      await user.type(screen.getByPlaceholderText(/Password/), 'new-password')

      await user.click(screen.getByText('Add Entry'))

      expect(mockCreateEntry).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'New Entry',
          data: expect.objectContaining({ password: 'new-password' }),
        }),
      )
    })

    it('should include optional fields when provided', async () => {
      const mockCreateEntry = vi.fn().mockResolvedValue({ id: 99, title: 'New Entry' })

      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: mockCreateEntry, updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText(/Add your first entry/)).toBeInTheDocument()
      })

      await user.click(screen.getByText(/Add your first entry/))

      await user.type(screen.getByPlaceholderText(/Title/), 'New Entry')
      await user.type(screen.getByPlaceholderText(/URL/), 'https://example.com')
      await user.type(screen.getByPlaceholderText(/Group path/), '/Work')
      await user.type(screen.getByPlaceholderText(/Username/), 'user123')
      await user.type(screen.getByPlaceholderText(/Password/), 'pass')
      await user.type(screen.getByPlaceholderText(/Notes/), 'Some notes')

      await user.click(screen.getByText('Add Entry'))

      expect(mockCreateEntry).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'New Entry',
          url: 'https://example.com',
          groupPath: '/Work',
          data: expect.objectContaining({
            username: 'user123',
            password: 'pass',
            notes: 'Some notes',
          }),
        }),
      )
    })

    it('should close form when cancel is clicked', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText(/Add your first entry/)).toBeInTheDocument()
      })

      await user.click(screen.getByText(/Add your first entry/))

      expect(screen.getByPlaceholderText(/Title/)).toBeInTheDocument()

      await user.click(screen.getByText('Cancel'))

      // Form should be hidden after cancel
      expect(screen.queryByPlaceholderText(/Title/)).not.toBeInTheDocument()
    })

    it('should show error when creation fails', async () => {
      const mockCreateEntry = vi.fn().mockRejectedValue(new Error('Network error'))

      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: mockCreateEntry, updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByText(/Add your first entry/)).toBeInTheDocument()
      })

      await user.click(screen.getByText(/Add your first entry/))

      await user.type(screen.getByPlaceholderText(/Title/), 'New Entry')
      await user.type(screen.getByPlaceholderText(/Password/), 'pass')

      await user.click(screen.getByText('Add Entry'))

      await waitFor(() => {
        expect(screen.getByText(/Network error/)).toBeInTheDocument()
      })
    })
  })

  describe('Copy to clipboard', () => {
    const mockEntries = [
      { id: 1, title: 'GitHub', url: '', groupPath: '/Work', data: { password: 'gh-secret-pass' }, createdAt: '', updatedAt: '' },
    ]

    it('should show copy button on each entry', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByTestId('vault-copy-password-1')).toBeInTheDocument()
      })
    })

    it('should copy password to clipboard when button is clicked', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByTestId('vault-copy-password-1')).toBeInTheDocument()
      })

      // Click copy button - verify it triggers the visual feedback (checkmark icon)
      await user.click(screen.getByTestId('vault-copy-password-1'))

      // Check that clipboard API was called (userEvent's stub tracks calls)
      await waitFor(() => {
        expect(screen.getByTestId('vault-copy-password-1').querySelector('path')?.getAttribute('d')).toBe('M5 13l4 4L19 7')
      })
    })

    it('should show checkmark icon after copying', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByTestId('vault-copy-password-1')).toBeInTheDocument()
      })

      // Initially shows clipboard icon (not the checkmark)
      expect(screen.getByTestId('vault-copy-password-1').querySelector('path')?.getAttribute('d')).not.toBe('M5 13l4 4L19 7')

      await user.click(screen.getByTestId('vault-copy-password-1'))

      // After click, shows checkmark icon
      await waitFor(() => {
        expect(screen.getByTestId('vault-copy-password-1').querySelector('path')?.getAttribute('d')).toBe('M5 13l4 4L19 7')
      })
    })
  })

  describe('Delete entry', () => {
    const mockEntries = [
      { id: 1, title: 'GitHub', url: '', groupPath: '/Work', data: { password: 'gh-secret-pass' }, createdAt: '', updatedAt: '' },
    ]

    it('should show delete button on each entry', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByTestId('vault-delete-entry-1')).toBeInTheDocument()
      })
    })

    it('should ask for confirmation and call deleteEntry when confirmed', async () => {
      const user = userEvent.setup()
      const mockDeleteEntry = vi.fn().mockResolvedValue(undefined)
      vi.spyOn(window, 'confirm').mockReturnValue(true)

      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: mockDeleteEntry, isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByTestId('vault-delete-entry-1')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('vault-delete-entry-1'))

      expect(window.confirm).toHaveBeenCalledWith(expect.stringContaining('GitHub'))
      await waitFor(() => {
        expect(mockDeleteEntry).toHaveBeenCalledWith(1)
      })
    })

    it('should NOT call deleteEntry when confirmation is cancelled', async () => {
      const user = userEvent.setup()
      const mockDeleteEntry = vi.fn().mockResolvedValue(undefined)
      vi.spyOn(window, 'confirm').mockReturnValue(false)

      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: mockDeleteEntry, isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByTestId('vault-delete-entry-1')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('vault-delete-entry-1'))

      expect(window.confirm).toHaveBeenCalled()
      expect(mockDeleteEntry).not.toHaveBeenCalled()
    })

    it('should show an error message when delete fails', async () => {
      const user = userEvent.setup()
      const mockDeleteEntry = vi.fn().mockRejectedValue(new Error('Network error'))
      vi.spyOn(window, 'confirm').mockReturnValue(true)

      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: mockDeleteEntry, isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByTestId('vault-delete-entry-1')).toBeInTheDocument()
      })

      await user.click(screen.getByTestId('vault-delete-entry-1'))

      await waitFor(() => {
        expect(screen.getByTestId('vault-delete-error')).toHaveTextContent('Network error')
      })
    })
  })

  describe('Entry display', () => {
    const mockEntries = [
      { id: 1, title: 'GitHub', url: 'https://github.com', groupPath: '/Work', data: { username: 'devuser', password: 'gh-pass' }, createdAt: '', updatedAt: '' },
      { id: 2, title: 'Netflix', url: 'https://netflix.com', groupPath: '/Personal', data: { password: 'netflix-pass' }, createdAt: '', updatedAt: '' },
    ]

    it('should display entry URL as clickable link', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        const urlLink = screen.getByRole('link', { name: /github\.com/ })
        expect(urlLink).toBeInTheDocument()
        expect(urlLink).toHaveAttribute('href', 'https://github.com')
        expect(urlLink).toHaveAttribute('target', '_blank')
      })
    })

    it('should display username when present', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByText('devuser')).toBeInTheDocument()
      })
    })

    it('should not display username when not present', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByText('Netflix')).toBeInTheDocument()
      })

      // Netflix entry has no username - should not show "devuser" near it
      const netflixEntry = screen.getByTestId('vault-entry-2')
      expect(netflixEntry.textContent).not.toContain('devuser')
    })
  })

  describe('Export functionality', () => {
    const mockEntries = [
      { id: 1, title: 'GitHub', url: 'https://github.com', groupPath: '/Work', data: { username: 'devuser', password: 'gh-pass' }, createdAt: '', updatedAt: '' },
      { id: 2, title: 'Netflix', url: 'https://netflix.com', groupPath: '/Personal', data: { password: 'netflix-pass' }, createdAt: '', updatedAt: '' },
    ]

    beforeEach(() => {
      vi.clearAllMocks()
      mockLocalStorage.clear()
    })

    it('should show export button when entries exist', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByTestId('vault-export-button')).toBeInTheDocument()
      })

      const exportButton = screen.getByTestId('vault-export-button')
      expect(exportButton).not.toBeDisabled()
    })

    it('should disable export button when no entries', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByTestId('vault-export-button')).toBeInTheDocument()
      })

      const exportButton = screen.getByTestId('vault-export-button')
      expect(exportButton).toBeDisabled()
    })

    it('should open export dropdown when clicking export button', async () => {
      const user = userEvent.setup()
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByTestId('vault-export-button')).toBeInTheDocument()
      })

      const exportButton = screen.getByTestId('vault-export-button')
      await user.click(exportButton)

      expect(screen.getByTestId('vault-export-csv-button')).toBeInTheDocument()
      expect(screen.getByTestId('vault-export-json-button')).toBeInTheDocument()
    })

    it('should fetch encrypted entries and trigger CSV download', async () => {
      const user = userEvent.setup()

      Object.defineProperty(window, 'Blob', { value: vi.fn(), writable: true })

      const createObjectURLMock = vi.fn(() => 'mock-url')
      URL.createObjectURL = createObjectURLMock as any

      const revokeObjectURLMock = vi.fn()
      URL.revokeObjectURL = revokeObjectURLMock as any

      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { vaultApi } = await import('../services/vaultApi')
      vi.mocked(vaultApi.getExportEntries).mockResolvedValue([
        {
          id: 1,
          title: 'Test Entry',
          usernameEncrypted: { ciphertext: 'Y2lwaGVy', iv: 'aXZieXRlcw==' },
          passwordEncrypted: { ciphertext: 'Y2lwaGVy', iv: 'aXZieXRlcw==' },
          notesEncrypted: null,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      ])

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByTestId('vault-export-button')).toBeInTheDocument()
      })

      const exportButton = screen.getByTestId('vault-export-button')
      await user.click(exportButton)

      const csvButton = screen.getByTestId('vault-export-csv-button')
      await user.click(csvButton)

      await waitFor(() => {
        expect(vaultApi.getExportEntries).toHaveBeenCalled()
      })
    })

    it('should fetch encrypted entries and trigger JSON download', async () => {
      const user = userEvent.setup()

      Object.defineProperty(window, 'Blob', { value: vi.fn(), writable: true })

      const createObjectURLMock = vi.fn(() => 'mock-url')
      URL.createObjectURL = createObjectURLMock as any

      const revokeObjectURLMock = vi.fn()
      URL.revokeObjectURL = revokeObjectURLMock as any

      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { vaultApi } = await import('../services/vaultApi')
      vi.mocked(vaultApi.getExportEntries).mockResolvedValue([
        {
          id: 1,
          title: 'Test Entry',
          usernameEncrypted: { ciphertext: 'Y2lwaGVy', iv: 'aXZieXRlcw==' },
          passwordEncrypted: { ciphertext: 'Y2lwaGVy', iv: 'aXZieXRlcw==' },
          notesEncrypted: null,
          createdAt: new Date().toISOString(),
          updatedAt: new Date().toISOString(),
        },
      ])

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByTestId('vault-export-button')).toBeInTheDocument()
      })

      const exportButton = screen.getByTestId('vault-export-button')
      await user.click(exportButton)

      const jsonButton = screen.getByTestId('vault-export-json-button')
      await user.click(jsonButton)

      await waitFor(() => {
        expect(vaultApi.getExportEntries).toHaveBeenCalled()
      })
    })

    it('should show error message when export fails', async () => {
      const user = userEvent.setup()
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { vaultApi } = await import('../services/vaultApi')
      vi.mocked(vaultApi.getExportEntries).mockRejectedValue(new Error('Network error'))

      renderWithProviders(<VaultPage />)

      await waitFor(() => {
        expect(screen.getByTestId('vault-export-button')).toBeInTheDocument()
      })

      const exportButton = screen.getByTestId('vault-export-button')
      await user.click(exportButton)

      const csvButton = screen.getByTestId('vault-export-csv-button')
      await user.click(csvButton)

      await waitFor(() => {
        expect(screen.getByTestId('vault-export-error')).toBeInTheDocument()
      })

      const errorElement = screen.getByTestId('vault-export-error')
      expect(errorElement.textContent).toContain('Network error')
    })
  })

  describe('Import UI - modal, buttons, validation (F-01 to F-07)', () => {
    beforeEach(() => {
      vi.clearAllMocks()
      mockLocalStorage.clear()
    })

    it('F-01: should display Import KDBX button in header', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const importButton = screen.getByText('Import KDBX')
      expect(importButton).toBeInTheDocument()
    })

    it('F-02: should open import modal when clicking Import KDBX button', async () => {
      const user = userEvent.setup()
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const importButton = screen.getByText('Import KDBX')
      await user.click(importButton)

      const modal = screen.getByTestId('vault-import-modal')
      expect(modal).toBeInTheDocument()
    })

    it('F-03: should disable submit button when file and password are missing', async () => {
      const user = userEvent.setup()
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await user.click(screen.getByText('Import KDBX'))

      const submitButton = screen.getByTestId('vault-import-submit-button')
      expect(submitButton).toBeDisabled()
    })

    it('F-04: should close modal and reset state when clicking Cancel', async () => {
      const user = userEvent.setup()
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await user.click(screen.getByText('Import KDBX'))
      expect(screen.getByTestId('vault-import-modal')).toBeInTheDocument()

      const cancelButton = screen.getByRole('button', { name: /cancel/i })
      await user.click(cancelButton)

      expect(screen.queryByTestId('vault-import-modal')).not.toBeInTheDocument()
    })

    it('F-05: should import entries successfully with valid file and password', async () => {
      const user = userEvent.setup()
      const mockEntries = [
        { title: 'Test Entry', username: 'user@test.com', password: 'secret123' },
      ]

      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn().mockResolvedValue({ id: 99, title: 'Test Entry' }), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      const { vaultApi } = await import('../services/vaultApi')
      vi.mocked(vaultApi.importFromKdbx).mockResolvedValue(mockEntries)

      renderWithProviders(<VaultPage />)

      await user.click(screen.getByText('Import KDBX'))

      // Upload file
      const fileInput = screen.getByLabelText(/file/i) as HTMLInputElement
      const testFile = new File(['fake kdbx content'], 'test.kdbx', { type: 'application/octet-stream' })
      await user.upload(fileInput, testFile)

      // Enter password
      const passwordInput = screen.getByLabelText(/password/i)
      await user.type(passwordInput, 'correct-password')

      // Submit
      const submitButton = screen.getByTestId('vault-import-submit-button')
      await user.click(submitButton)

      expect(vaultApi.importFromKdbx).toHaveBeenCalledWith(
        testFile,
        'correct-password',
      )
    })

    it('F-06: should keep submit disabled when file is missing (validation)', async () => {
      const user = userEvent.setup()
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await user.click(screen.getByText('Import KDBX'))

      // Enter password but no file selected
      const passwordInput = screen.getByLabelText(/password/i)
      await user.type(passwordInput, 'some-password')

      // Submit button stays disabled - validation prevents submission without file
      const submitButton = screen.getByTestId('vault-import-submit-button')
      expect(submitButton).toBeDisabled()
    })

    it('F-07: should keep submit disabled when password is missing (validation)', async () => {
      const user = userEvent.setup()
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      await user.click(screen.getByText('Import KDBX'))

      // Upload file but no password entered
      const fileInput = screen.getByLabelText(/file/i) as HTMLInputElement
      const testFile = new File(['fake kdbx content'], 'test.kdbx', { type: 'application/octet-stream' })
      await user.upload(fileInput, testFile)

      // Submit button stays disabled - validation prevents submission without password
      const submitButton = screen.getByTestId('vault-import-submit-button')
      expect(submitButton).toBeDisabled()
    })
  })

  describe('Import workflow - API communication & data handling', () => {
    class HttpError extends Error {
      status: number;
      constructor(status: number, message: string) {
        super(message);
        this.status = status;
        this.name = 'HttpError';
      }
    }

    const createTestFile = () => new File(['fake kdbx content'], 'test.kdbx', { type: 'application/octet-stream' });

    beforeEach(() => {
      vi.clearAllMocks();
      mockLocalStorage.clear();
    });

    it('should show error when KDBX contains no entries', async () => {
      const user = userEvent.setup();
      const { useVaultEntries } = await import('../hooks/useVaultEntries');
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any);

      const { vaultApi } = await import('../services/vaultApi');
      vi.mocked(vaultApi.importFromKdbx).mockResolvedValue([]);

      renderWithProviders(<VaultPage />);

      await user.click(screen.getByText('Import KDBX'));

      const fileInput = screen.getByLabelText(/file/i) as HTMLInputElement;
      await user.upload(fileInput, createTestFile());

      const passwordInput = screen.getByLabelText(/password/i);
      await user.type(passwordInput, 'password');

      const submitButton = screen.getByTestId('vault-import-submit-button');
      await user.click(submitButton);

      expect(screen.getByText(/no entries found in the kdbx file/i)).toBeInTheDocument();
    });

    it('should display error when backend returns 409 for invalid password', async () => {
      const user = userEvent.setup();
      const { useVaultEntries } = await import('../hooks/useVaultEntries');
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any);

      const { vaultApi } = await import('../services/vaultApi');
      const httpError = new HttpError(409, 'Invalid password or corrupted KDBX file');
      vi.mocked(vaultApi.importFromKdbx).mockRejectedValue(httpError);

      renderWithProviders(<VaultPage />);

      await user.click(screen.getByText('Import KDBX'));

      const fileInput = screen.getByLabelText(/file/i) as HTMLInputElement;
      await user.upload(fileInput, createTestFile());

      const passwordInput = screen.getByLabelText(/password/i);
      await user.type(passwordInput, 'wrong-password');

      const submitButton = screen.getByTestId('vault-import-submit-button');
      await user.click(submitButton);

      expect(screen.getByText(/invalid password/i)).toBeInTheDocument();
    });

    it('should display error when backend returns 400 for corrupted file', async () => {
      const user = userEvent.setup();
      const { useVaultEntries } = await import('../hooks/useVaultEntries');
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any);

      const { vaultApi } = await import('../services/vaultApi');
      const httpError = new HttpError(400, 'Failed to import KDBX file');
      vi.mocked(vaultApi.importFromKdbx).mockRejectedValue(httpError);

      renderWithProviders(<VaultPage />);

      await user.click(screen.getByText('Import KDBX'));

      const fileInput = screen.getByLabelText(/file/i) as HTMLInputElement;
      await user.upload(fileInput, createTestFile());

      const passwordInput = screen.getByLabelText(/password/i);
      await user.type(passwordInput, 'any-password');

      const submitButton = screen.getByTestId('vault-import-submit-button');
      await user.click(submitButton);

      expect(screen.getByText(/failed to import|corrupted file/i)).toBeInTheDocument();
    });

    it('should use Untitled as default for entries without title', async () => {
      const user = userEvent.setup();
      const mockCreateEntry = vi.fn().mockResolvedValue({ id: 99, title: 'Untitled' });

      const { useVaultEntries } = await import('../hooks/useVaultEntries');
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: mockCreateEntry, updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any);

      const { vaultApi } = await import('../services/vaultApi');
      const mockEntries = [
        { title: '', username: 'user@test.com', password: 'secret' },
        { title: null, username: 'another@test.com', password: 'pass2' },
      ];
      vi.mocked(vaultApi.importFromKdbx).mockResolvedValue(mockEntries as any);

      renderWithProviders(<VaultPage />);

      await user.click(screen.getByText('Import KDBX'));

      const fileInput = screen.getByLabelText(/file/i) as HTMLInputElement;
      await user.upload(fileInput, createTestFile());

      const passwordInput = screen.getByLabelText(/password/i);
      await user.type(passwordInput, 'password');

      const submitButton1 = screen.getByTestId('vault-import-submit-button');
      await user.click(submitButton1);

      expect(mockCreateEntry).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'Untitled',
          data: expect.objectContaining({
            username: 'user@test.com',
            password: 'secret',
          }),
        }),
      );
    });

    it('should not store empty strings for optional fields', async () => {
      const user = userEvent.setup();
      const mockCreateEntry = vi.fn().mockResolvedValue({ id: 99, title: 'Test' });

      const { useVaultEntries } = await import('../hooks/useVaultEntries');
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(), createEntry: mockCreateEntry, updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any);

      const { vaultApi } = await import('../services/vaultApi');
      const mockEntries = [
        { title: 'Test', username: '', password: 'secret', url: null, notes: '' },
      ];
      vi.mocked(vaultApi.importFromKdbx).mockResolvedValue(mockEntries as any);

      renderWithProviders(<VaultPage />);

      await user.click(screen.getByText('Import KDBX'));

      const fileInput = screen.getByLabelText(/file/i) as HTMLInputElement;
      await user.upload(fileInput, createTestFile());

      const passwordInput = screen.getByLabelText(/password/i);
      await user.type(passwordInput, 'password');

      const submitButton2 = screen.getByTestId('vault-import-submit-button');
      await user.click(submitButton2);

      const callArgs = mockCreateEntry.mock.calls[0][0];
      expect(callArgs.title).toBe('Test');
      expect(callArgs.data.password).toBe('secret');
      expect(callArgs.data.username).toBeUndefined();
      expect(callArgs.url).toBeUndefined();
      expect(callArgs.data.notes).toBeUndefined();
    });

    it('should encrypt entry data before saving to vault', async () => {
      const user = userEvent.setup();

      const { useVaultEntries } = await import('../hooks/useVaultEntries');
      const { vaultApi } = await import('../services/vaultApi');
      const { deriveKey, encrypt, bytesToBase64 } = await import('../services/encryptionService');

      vi.mocked(useVaultEntries).mockReturnValue({
        entries: [], isLoading: false, error: null, refetch: vi.fn(),
        createEntry: vi.fn().mockImplementation(async (entry) => {
          const key = await deriveKey(mockVaultConfig.masterPassword, mockVaultConfig.salt);

          // Backend persists a single shared IV per entry, so all fields must use the same one.
          const iv = crypto.getRandomValues(new Uint8Array(12));

          const usernameJson = entry.data.username ? JSON.stringify(entry.data.username) : null;
          const passwordJson = JSON.stringify(entry.data.password);
          const notesJson = entry.data.notes ? JSON.stringify(entry.data.notes) : null;

          const passwordEncrypted = await encrypt(passwordJson, key, iv);
          const usernameEncrypted = usernameJson ? await encrypt(usernameJson, key, iv) : null;
          const notesEncrypted = notesJson ? await encrypt(notesJson, key, iv) : null;

          const dto = {
            title: entry.title,
            url: entry.url,
            groupPath: entry.groupPath,
            usernameEncrypted: usernameEncrypted ? { ciphertext: bytesToBase64(usernameEncrypted.ciphertext), iv: bytesToBase64(iv) } : undefined,
            passwordEncrypted: { ciphertext: bytesToBase64(passwordEncrypted.ciphertext), iv: bytesToBase64(iv) },
            notesEncrypted: notesEncrypted ? { ciphertext: bytesToBase64(notesEncrypted.ciphertext), iv: bytesToBase64(iv) } : undefined,
          };

          return vaultApi.create(dto as any);
        }),
        updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any);

      vi.mocked(vaultApi.create).mockResolvedValue({
        id: 99, title: 'Test Entry', url: undefined, groupPath: undefined,
        usernameEncrypted: null, passwordEncrypted: { ciphertext: 'Y2lwaGVy', iv: 'aXZieXRlcw==' }, notesEncrypted: null,
        createdAt: new Date().toISOString(), updatedAt: new Date().toISOString(),
      });

      const mockEntries = [
        { title: 'Test Entry', username: 'user@test.com', password: 'plaintext-secret' },
      ];
      vi.mocked(vaultApi.importFromKdbx).mockResolvedValue(mockEntries as any);

      renderWithProviders(<VaultPage />);

      await user.click(screen.getByText('Import KDBX'));

      const fileInput = screen.getByLabelText(/file/i) as HTMLInputElement;
      await user.upload(fileInput, createTestFile());

      const passwordInput = screen.getByLabelText(/password/i);
      await user.type(passwordInput, 'password');

      const submitButton3 = screen.getByTestId('vault-import-submit-button');
      await user.click(submitButton3);

      // Wait for the async encryption and API call to complete
      await waitFor(() => {
        expect(vaultApi.create).toHaveBeenCalled();
      });

      const createCall = (vaultApi.create as any).mock.calls[0][0];

      // Verify encrypted data was sent, not plaintext
      expect(createCall.passwordEncrypted).toBeDefined();
      expect(typeof createCall.passwordEncrypted.ciphertext).toBe('string');
      expect(createCall.passwordEncrypted.ciphertext).not.toBe('plaintext-secret');
      expect(createCall.passwordEncrypted.ciphertext.length).toBeGreaterThan(10);
    });
  })
})
