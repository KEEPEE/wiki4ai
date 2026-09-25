/**
 * Tests for the shared Menu dropdown (WIKI4AI-85/86).
 * Covers: open/close, Esc-to-close with focus return, click-outside close,
 * arrow-key navigation, item activation, radio semantics.
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Menu, MenuItem, MenuDivider, MenuRadioItem, MenuSectionLabel } from '../components/Menu'

function renderMenu() {
  return render(
    <div>
      <button type="button" data-testid="outside-button">
        Outside
      </button>
      <Menu
        ariaLabel="Test menu"
        testId="menu-trigger"
        panelTestId="menu-panel"
        trigger={<span>Trigger</span>}
      >
        <MenuItem testId="item-a">Item A</MenuItem>
        <MenuDivider />
        <MenuSectionLabel>Group</MenuSectionLabel>
        <MenuItem danger testId="item-b">
          Item B
        </MenuItem>
      </Menu>
    </div>,
  )
}

describe('Menu (shared dropdown)', () => {
  it('should be closed initially and open on trigger click', async () => {
    const user = userEvent.setup()
    renderMenu()

    expect(screen.queryByTestId('menu-panel')).not.toBeInTheDocument()
    expect(screen.getByTestId('menu-trigger')).toHaveAttribute('aria-expanded', 'false')

    await user.click(screen.getByTestId('menu-trigger'))

    expect(screen.getByTestId('menu-panel')).toBeInTheDocument()
    expect(screen.getByTestId('menu-trigger')).toHaveAttribute('aria-expanded', 'true')
  })

  it('should close on second trigger click (toggle)', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByTestId('menu-trigger'))
    expect(screen.getByTestId('menu-panel')).toBeInTheDocument()

    // Focus is inside the panel after open; move back to the trigger first.
    screen.getByTestId('menu-trigger').focus()
    await user.click(screen.getByTestId('menu-trigger'))

    expect(screen.queryByTestId('menu-panel')).not.toBeInTheDocument()
  })

  it('should focus the first item on open', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByTestId('menu-trigger'))

    await waitFor(() => {
      expect(document.activeElement).toBe(screen.getByTestId('item-a'))
    })
  })

  it('should close on Escape and return focus to the trigger', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByTestId('menu-trigger'))
    await waitFor(() => {
      expect(document.activeElement).toBe(screen.getByTestId('item-a'))
    })

    await user.keyboard('{Escape}')

    expect(screen.queryByTestId('menu-panel')).not.toBeInTheDocument()
    expect(document.activeElement).toBe(screen.getByTestId('menu-trigger'))
  })

  it('should close on click outside the menu', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByTestId('menu-trigger'))
    expect(screen.getByTestId('menu-panel')).toBeInTheDocument()

    await user.click(screen.getByTestId('outside-button'))

    expect(screen.queryByTestId('menu-panel')).not.toBeInTheDocument()
  })

  it('should navigate with ArrowDown / ArrowUp (wrapping) and Home/End', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByTestId('menu-trigger'))
    await waitFor(() => {
      expect(document.activeElement).toBe(screen.getByTestId('item-a'))
    })

    await user.keyboard('{ArrowDown}')
    expect(document.activeElement).toBe(screen.getByTestId('item-b'))

    // Wraps back to the first item
    await user.keyboard('{ArrowDown}')
    expect(document.activeElement).toBe(screen.getByTestId('item-a'))

    await user.keyboard('{End}')
    expect(document.activeElement).toBe(screen.getByTestId('item-b'))

    await user.keyboard('{Home}')
    expect(document.activeElement).toBe(screen.getByTestId('item-a'))
  })

  it('should invoke the item handler and close when an item is activated', async () => {
    const user = userEvent.setup()
    const onActivate = vi.fn()
    render(
      <Menu ariaLabel="Test menu" testId="menu-trigger" panelTestId="menu-panel" trigger={<span>Trigger</span>}>
        <MenuItem testId="item-a" onClick={onActivate}>
          Item A
        </MenuItem>
      </Menu>,
    )

    await user.click(screen.getByTestId('menu-trigger'))
    await waitFor(() => {
      expect(document.activeElement).toBe(screen.getByTestId('item-a'))
    })

    await user.keyboard('{Enter}')

    expect(onActivate).toHaveBeenCalledTimes(1)
    expect(screen.queryByTestId('menu-panel')).not.toBeInTheDocument()
  })

  it('should expose radio semantics (aria-checked) and highlight the selected item', async () => {
    const user = userEvent.setup()
    render(
      <Menu ariaLabel="Role menu" testId="menu-trigger" panelTestId="menu-panel" trigger={<span>Role</span>}>
        <MenuSectionLabel>Role</MenuSectionLabel>
        <MenuRadioItem selected={false} testId="role-user">
          USER
        </MenuRadioItem>
        <MenuRadioItem selected testId="role-admin">
          ADMIN
        </MenuRadioItem>
      </Menu>,
    )

    await user.click(screen.getByTestId('menu-trigger'))

    const userOption = screen.getByTestId('role-user') as HTMLButtonElement
    const adminOption = screen.getByTestId('role-admin') as HTMLButtonElement
    expect(userOption).toHaveAttribute('role', 'menuitemradio')
    expect(userOption).toHaveAttribute('aria-checked', 'false')
    expect(adminOption).toHaveAttribute('aria-checked', 'true')
    expect(adminOption.className).toContain('menu-item-selected')
  })

  it('should skip disabled items during arrow navigation', async () => {
    const user = userEvent.setup()
    render(
      <Menu ariaLabel="Test menu" testId="menu-trigger" panelTestId="menu-panel" trigger={<span>Trigger</span>}>
        <MenuItem testId="item-a">Item A</MenuItem>
        <MenuItem disabled testId="item-disabled">
          Disabled
        </MenuItem>
        <MenuItem testId="item-b">Item B</MenuItem>
      </Menu>,
    )

    await user.click(screen.getByTestId('menu-trigger'))
    await waitFor(() => {
      expect(document.activeElement).toBe(screen.getByTestId('item-a'))
    })

    await user.keyboard('{ArrowDown}')
    // The disabled item is skipped entirely
    expect(document.activeElement).toBe(screen.getByTestId('item-b'))
  })
})
