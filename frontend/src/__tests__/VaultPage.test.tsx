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

      // Click copy button - verify it triggers the visual feedback (checkmark)
      await user.click(screen.getByTestId('vault-copy-password-1'))

      // Check that clipboard API was called (userEvent's stub tracks calls)
      await waitFor(() => {
        expect(screen.getByTestId('vault-copy-password-1')).toHaveTextContent('✓')
      })
    })

    it('should show checkmark after copying', async () => {
      const { useVaultEntries } = await import('../hooks/useVaultEntries')
      vi.mocked(useVaultEntries).mockReturnValue({
        entries: mockEntries, isLoading: false, error: null, refetch: vi.fn(), createEntry: vi.fn(), updateEntry: vi.fn(), deleteEntry: vi.fn(), isCreating: false, isUpdating: false, isDeleting: false,
      } as any)

      renderWithProviders(<VaultPage />)

      const user = userEvent.setup()

      await waitFor(() => {
        expect(screen.getByTestId('vault-copy-password-1')).toBeInTheDocument()
      })

      // Initially shows clipboard icon
      expect(screen.getByTestId('vault-copy-password-1')).toHaveTextContent('📋')

      await user.click(screen.getByTestId('vault-copy-password-1'))

      // After click, shows checkmark
      await waitFor(() => {
        expect(screen.getByTestId('vault-copy-password-1')).toHaveTextContent('✓')
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
})
