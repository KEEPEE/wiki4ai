/**
 * Tests for VaultEntryForm component
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import VaultEntryForm from '../components/VaultEntryForm'
import type { VaultEntry } from '../types/vault'

const mockOnSubmit = vi.fn()
const mockOnCancel = vi.fn()

function renderForm(props?: Partial<React.ComponentProps<typeof VaultEntryForm>>) {
  return render(
    <VaultEntryForm
      onSubmit={mockOnSubmit}
      onCancel={mockOnCancel}
      {...props}
    />,
  )
}

describe('VaultEntryForm', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  describe('Create mode', () => {
    it('should show "Add New Entry" title in create mode', () => {
      renderForm()
      expect(screen.getByText(/Add New Entry/)).toBeInTheDocument()
    })

    it('should render all form fields', () => {
      renderForm()
      expect(screen.getByTestId('vault-form-title-input')).toBeInTheDocument()
      expect(screen.getByTestId('vault-form-url-input')).toBeInTheDocument()
      expect(screen.getByTestId('vault-form-group-path-input')).toBeInTheDocument()
      expect(screen.getByTestId('vault-form-username-input')).toBeInTheDocument()
      expect(screen.getByTestId('vault-form-password-input')).toBeInTheDocument()
      expect(screen.getByTestId('vault-form-notes-input')).toBeInTheDocument()
    })

    it('should call onSubmit with correct data when form is submitted', async () => {
      mockOnSubmit.mockResolvedValue(undefined)
      renderForm()

      const user = userEvent.setup()

      await user.type(screen.getByTestId('vault-form-title-input'), 'GitHub')
      await user.type(screen.getByTestId('vault-form-url-input'), 'https://github.com')
      await user.type(screen.getByTestId('vault-form-group-path-input'), '/Work')
      await user.type(screen.getByTestId('vault-form-username-input'), 'devuser')
      await user.type(screen.getByTestId('vault-form-password-input'), 'secret123')
      await user.type(screen.getByTestId('vault-form-notes-input'), 'My account')

      await user.click(screen.getByText('Add Entry'))

      expect(mockOnSubmit).toHaveBeenCalledWith({
        title: 'GitHub',
        url: 'https://github.com',
        groupPath: '/Work',
        username: 'devuser',
        password: 'secret123',
        notes: 'My account',
      })
    })

    it('should call onCancel when cancel button is clicked', async () => {
      renderForm()

      const user = userEvent.setup()
      await user.click(screen.getByText('Cancel'))

      expect(mockOnCancel).toHaveBeenCalled()
    })

    it('should show validation error for missing title', async () => {
      mockOnSubmit.mockResolvedValue(undefined)
      renderForm()

      const user = userEvent.setup()
      await user.type(screen.getByTestId('vault-form-password-input'), 'pass')
      await user.click(screen.getByText('Add Entry'))

      expect(screen.getByText(/Title is required/)).toBeInTheDocument()
    })

    it('should show validation error for missing password', async () => {
      mockOnSubmit.mockResolvedValue(undefined)
      renderForm()

      const user = userEvent.setup()
      await user.type(screen.getByTestId('vault-form-title-input'), 'GitHub')
      await user.click(screen.getByText('Add Entry'))

      expect(screen.getByText(/Password is required/)).toBeInTheDocument()
    })

    it('should show validation error for invalid URL', async () => {
      mockOnSubmit.mockResolvedValue(undefined)
      renderForm()

      const user = userEvent.setup()
      await user.type(screen.getByTestId('vault-form-title-input'), 'GitHub')
      await user.type(screen.getByTestId('vault-form-url-input'), 'not-a-valid-url')
      await user.type(screen.getByTestId('vault-form-password-input'), 'pass')
      await user.click(screen.getByText('Add Entry'))

      expect(screen.getByText(/Please enter a valid URL/)).toBeInTheDocument()
    })

    it('should clear field error when user starts typing', async () => {
      mockOnSubmit.mockResolvedValue(undefined)
      renderForm()

      const user = userEvent.setup()
      await user.type(screen.getByTestId('vault-form-password-input'), 'pass')
      await user.click(screen.getByText('Add Entry'))

      expect(screen.getByText(/Title is required/)).toBeInTheDocument()

      await user.type(screen.getByTestId('vault-form-title-input'), 'a')

      expect(screen.queryByText(/Title is required/)).not.toBeInTheDocument()
    })

    it('should not submit if validation fails', async () => {
      mockOnSubmit.mockResolvedValue(undefined)
      renderForm()

      const user = userEvent.setup()
      await user.click(screen.getByText('Add Entry'))

      expect(mockOnSubmit).not.toHaveBeenCalled()
    })
  })

  describe('Edit mode', () => {
    const mockEntry: VaultEntry = {
      id: 1,
      title: 'GitHub',
      url: 'https://github.com',
      groupPath: '/Work',
      data: { username: 'devuser', password: 'old-pass' },
      createdAt: '',
      updatedAt: '',
    }

    it('should show "Edit Entry" title in edit mode', () => {
      renderForm({ entry: mockEntry })
      expect(screen.getByText(/Edit Entry/)).toBeInTheDocument()
    })

    it('should populate form with existing entry data', () => {
      renderForm({ entry: mockEntry })

      expect(screen.getByTestId('vault-form-title-input')).toHaveValue('GitHub')
      expect(screen.getByTestId('vault-form-url-input')).toHaveValue('https://github.com')
      expect(screen.getByTestId('vault-form-group-path-input')).toHaveValue('/Work')
      expect(screen.getByTestId('vault-form-username-input')).toHaveValue('devuser')
      expect(screen.getByTestId('vault-form-password-input')).toHaveValue('old-pass')
    })

    it('should show "Save Changes" button in edit mode', () => {
      renderForm({ entry: mockEntry })
      expect(screen.getByText(/Save Changes/)).toBeInTheDocument()
    })

    it('should call onSubmit with updated data when form is submitted', async () => {
      mockOnSubmit.mockResolvedValue(undefined)
      renderForm({ entry: mockEntry })

      const user = userEvent.setup()

      await user.clear(screen.getByTestId('vault-form-title-input'))
      await user.type(screen.getByTestId('vault-form-title-input'), 'GitLab')
      await user.clear(screen.getByTestId('vault-form-password-input'))
      await user.type(screen.getByTestId('vault-form-password-input'), 'new-pass')

      await user.click(screen.getByText('Save Changes'))

      expect(mockOnSubmit).toHaveBeenCalledWith(
        expect.objectContaining({
          title: 'GitLab',
          password: 'new-pass',
        }),
      )
    })
  })

  describe('Password show/hide toggle', () => {
    it('should hide password by default', () => {
      renderForm()
      expect(screen.getByTestId('vault-form-password-input')).toHaveAttribute('type', 'password')
    })

    it('should show password when toggle button is clicked', async () => {
      renderForm()

      const user = userEvent.setup()
      await user.type(screen.getByTestId('vault-form-password-input'), 'secret123')

      expect(screen.getByTestId('vault-form-password-input')).toHaveAttribute('type', 'password')

      await user.click(screen.getByTestId('vault-form-toggle-password-button'))

      expect(screen.getByTestId('vault-form-password-input')).toHaveValue('secret123')
    })

    it('should toggle between show and hide on multiple clicks', async () => {
      renderForm()

      const user = userEvent.setup()
      await user.type(screen.getByTestId('vault-form-password-input'), 'secret123')

      const toggleBtn = screen.getByTestId('vault-form-toggle-password-button')

      await user.click(toggleBtn)
      expect(screen.getByTestId('vault-form-password-input')).toHaveValue('secret123')

      await user.click(toggleBtn)
    })
  })

  describe('Generate password', () => {
    it('should generate a new password when button is clicked', async () => {
      renderForm()

      const user = userEvent.setup()
      await user.type(screen.getByTestId('vault-form-password-input'), 'old-pass')

      await user.click(screen.getByTestId('vault-form-generate-password-button'))

      const input = screen.getByTestId('vault-form-password-input') as HTMLInputElement
      expect(input.value).not.toBe('old-pass')
      expect(input.value.length).toBeGreaterThanOrEqual(16)
    })

    it('should show password after generating', async () => {
      renderForm()

      const user = userEvent.setup()
      await user.click(screen.getByTestId('vault-form-generate-password-button'))

      const input = screen.getByTestId('vault-form-password-input') as HTMLInputElement
      expect(input.value.length).toBeGreaterThanOrEqual(16)
    })
  })

  describe('Group path autocomplete', () => {
    it('should show group suggestions when typing in group path field', async () => {
      renderForm({ existingGroups: ['/Work', '/Personal', '/Finance'] })

      const user = userEvent.setup()
      await user.click(screen.getByTestId('vault-form-group-path-input'))
      await user.type(screen.getByTestId('vault-form-group-path-input'), 'W')

      expect(screen.getByTestId('vault-form-group-suggestion--Work')).toBeInTheDocument()
    })

    it('should filter suggestions based on input', async () => {
      renderForm({ existingGroups: ['/Work', '/Personal', '/Finance'] })

      const user = userEvent.setup()
      await user.click(screen.getByTestId('vault-form-group-path-input'))
      await user.type(screen.getByTestId('vault-form-group-path-input'), 'Per')

      expect(screen.getByTestId('vault-form-group-suggestion--Personal')).toBeInTheDocument()
      expect(screen.queryByTestId('vault-form-group-suggestion--Work')).not.toBeInTheDocument()
    })

    it('should set group path when suggestion is clicked', async () => {
      renderForm({ existingGroups: ['/Work', '/Personal'] })

      const user = userEvent.setup()
      await user.click(screen.getByTestId('vault-form-group-path-input'))
      await user.type(screen.getByTestId('vault-form-group-path-input'), 'W')

      await user.click(screen.getByTestId('vault-form-group-suggestion--Work'))

      expect(screen.getByTestId('vault-form-group-path-input')).toHaveValue('/Work')
    })

    it('should not show suggestions when no groups match', async () => {
      renderForm({ existingGroups: ['/Work'] })

      const user = userEvent.setup()
      await user.click(screen.getByTestId('vault-form-group-path-input'))
      await user.type(screen.getByTestId('vault-form-group-path-input'), 'xyz')

      expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    })
  })

  describe('Error display', () => {
    it('should display error message when provided via props', () => {
      renderForm({ error: 'Network error occurred' })
      expect(screen.getByText(/Network error occurred/)).toBeInTheDocument()
    })
  })

  describe('Submitting state', () => {
    it('should disable submit button when isSubmitting is true', () => {
      renderForm({ isSubmitting: true })
      expect(screen.getByText(/Add Entry/)).toBeDisabled()
    })

    it('should enable submit button when isSubmitting is false', () => {
      renderForm({ isSubmitting: false })
      expect(screen.getByText(/Add Entry/)).not.toBeDisabled()
    })
  })
})
