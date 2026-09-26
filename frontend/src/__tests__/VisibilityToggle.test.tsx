/**
 * WIKI4AI-100: tests for the shared Public/Private visibility toggle.
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import VisibilityToggle from '../components/VisibilityToggle'

describe('VisibilityToggle', () => {
  it('should render both options with the given value active', () => {
    render(<VisibilityToggle value="public" onChange={vi.fn()} testIdPrefix="unit" />)

    expect(screen.getByTestId('unit-visibility-public')).toBeInTheDocument()
    expect(screen.getByTestId('unit-visibility-private')).toBeInTheDocument()
    expect(screen.getByTestId('unit-visibility-public')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('unit-visibility-private')).toHaveAttribute('aria-pressed', 'false')
  })

  it('should call onChange with the clicked value and update the active state', async () => {
    const onChange = vi.fn()
    render(<VisibilityToggle value="public" onChange={onChange} testIdPrefix="unit" />)

    const user = userEvent.setup()
    await user.click(screen.getByTestId('unit-visibility-private'))

    expect(onChange).toHaveBeenCalledWith('private')
  })

  it('should expose the Public/Private labels and a group aria-label', () => {
    render(<VisibilityToggle value="private" onChange={vi.fn()} testIdPrefix="unit" />)

    // EN default catalog: "Public" / "Private" (common.public / common.private)
    expect(screen.getByText('Public')).toBeInTheDocument()
    expect(screen.getByText('Private')).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Visibility' })).toBeInTheDocument()
  })

  it('should disable both options when disabled', () => {
    render(<VisibilityToggle value="public" onChange={vi.fn()} testIdPrefix="unit" disabled />)

    expect(screen.getByTestId('unit-visibility-public')).toBeDisabled()
    expect(screen.getByTestId('unit-visibility-private')).toBeDisabled()
  })
})
