/**
 * WIKI4AI-186: the shared pin toggle control.
 * Checks the affordances the story asks for — a clear pinned/unpinned
 * distinction (aria-pressed + filled icon + state class), an accessible name
 * and tooltip, keyboard activation, and that clicking it never triggers the
 * surrounding card/row navigation.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import PinButton from '../components/PinButton'

function renderButton(overrides: Partial<React.ComponentProps<typeof PinButton>> = {}) {
  const onToggle = vi.fn()
  render(
    <PinButton
      pinned={false}
      onToggle={onToggle}
      name="glass-admin"
      kind="project"
      data-testid="pin-project-glass-admin"
      {...overrides}
    />,
  )
  return { onToggle, button: screen.getByTestId('pin-project-glass-admin') as HTMLButtonElement }
}

describe('PinButton (WIKI4AI-186)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('labels itself for screen readers and hover, and reports the pressed state', () => {
    const { button } = renderButton()
    expect(button).toHaveAccessibleName('Pin project glass-admin to the top')
    expect(button).toHaveAttribute('title', 'Pin project glass-admin to the top')
    expect(button).toHaveAttribute('aria-pressed', 'false')
    expect(button).not.toHaveClass('pin-button-active')
  })

  it('switches label, pressed state and filled icon when pinned', () => {
    const { button } = renderButton({ pinned: true })
    expect(button).toHaveAccessibleName('Unpin project glass-admin from the top')
    expect(button).toHaveAttribute('aria-pressed', 'true')
    expect(button).toHaveClass('pin-button-active')
    // The state is also carried by shape: the pinned icon is a filled <path>.
    expect(button.querySelector('svg')?.getAttribute('fill')).toBe('currentColor')
  })

  it('uses the document wording for documents', () => {
    const { button } = renderButton({ kind: 'document', name: 'Readme', pinned: true })
    expect(button).toHaveAccessibleName('Unpin document Readme from the top')
  })

  it('activates from the keyboard (Enter and Space)', async () => {
    const user = userEvent.setup()
    const { onToggle, button } = renderButton()
    button.focus()
    await user.keyboard('{Enter}')
    expect(onToggle).toHaveBeenCalledTimes(1)
    await user.keyboard(' ')
    expect(onToggle).toHaveBeenCalledTimes(2)
  })

  it('does not bubble the click to the surrounding card or row', async () => {
    const user = userEvent.setup()
    const onParent = vi.fn()
    const onToggle = vi.fn()
    render(
      <div onClick={onParent} data-testid="card">
        <PinButton
          pinned={false}
          onToggle={onToggle}
          name="glass-admin"
          data-testid="pin-project-glass-admin"
        />
      </div>,
    )
    await user.click(screen.getByTestId('pin-project-glass-admin'))
    expect(onToggle).toHaveBeenCalledTimes(1)
    expect(onParent).not.toHaveBeenCalled()
  })

  it('is inert while a pin request is in flight', async () => {
    const user = userEvent.setup()
    const { onToggle, button } = renderButton({ disabled: true })
    expect(button).toBeDisabled()
    await user.click(button).catch(() => {})
    expect(onToggle).not.toHaveBeenCalled()
  })
})
