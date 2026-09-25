/**
 * Tests for the shared Menu dropdown (WIKI4AI-85/86).
 * Covers: open/close, Esc-to-close with focus return, click-outside close,
 * arrow-key navigation, item activation, radio semantics.
 * WIKI4AI-91 adds: portal rendering into document.body and fixed-position
 * placement (trigger rect → coords, flip-up, viewport clamp, reposition on
 * resize/scroll).
 */

import { describe, it, expect, vi, afterEach } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
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

// ── WIKI4AI-91: portal rendering + fixed positioning ───────────────────────

function makeRect(partial: Partial<DOMRect>): DOMRect {
  return {
    x: 0, y: 0, left: 0, top: 0, right: 0, bottom: 0, width: 0, height: 0,
    toJSON: () => ({}),
    ...partial,
  } as DOMRect
}

/**
 * Stub getBoundingClientRect for the menu trigger (by data-testid) and the
 * panel (by class); every other element keeps its original (zero) rect so
 * user-event pointer handling is unaffected. The returned objects are read
 * live, so tests can mutate them to simulate scrolling/resizing.
 */
function mockMenuRects(trigger: Partial<DOMRect>, panel: Partial<DOMRect>) {
  const original = HTMLElement.prototype.getBoundingClientRect
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(function (this: HTMLElement) {
    if (this.dataset?.testid === 'menu-trigger') return makeRect(trigger)
    if (this.classList.contains('menu-panel')) return makeRect(panel)
    return original.call(this)
  })
}

describe('Menu portal positioning (WIKI4AI-91)', () => {
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('should render the open panel in a portal on document.body, not inside the trigger root', async () => {
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByTestId('menu-trigger'))

    const panel = screen.getByTestId('menu-panel')
    expect(panel.parentElement).toBe(document.body)
    expect(panel.closest('.menu-root')).toBeNull()
  })

  it('should position the panel with fixed coordinates from the trigger rect (drop-down, align end)', async () => {
    // jsdom viewport: 1024x768. Trigger at (400,100)-(432,132), panel 220x300.
    mockMenuRects(
      { left: 400, top: 100, right: 432, bottom: 132, width: 32, height: 32 },
      { width: 220, height: 300 },
    )
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByTestId('menu-trigger'))

    const panel = screen.getByTestId('menu-panel') as HTMLDivElement
    await waitFor(() => {
      expect(panel.style.top).toBe(`${132 + 8}px`) // trigger.bottom + gap
      expect(panel.style.left).toBe(`${432 - 220}px`) // align end: trigger.right - width
    })
  })

  it('should flip up when there is not enough room below the viewport bottom', async () => {
    // Trigger near the bottom (top=600, bottom=632); panel height 300 does not
    // fit below (640+300 > 768-8) but fits above → top = 600 - 300 - 8 = 292.
    mockMenuRects(
      { left: 400, top: 600, right: 432, bottom: 632, width: 32, height: 32 },
      { width: 220, height: 300 },
    )
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByTestId('menu-trigger'))

    const panel = screen.getByTestId('menu-panel') as HTMLDivElement
    await waitFor(() => {
      expect(panel.style.top).toBe(`${600 - 300 - 8}px`)
    })
  })

  it('should clamp the panel inside the viewport horizontally', async () => {
    // Trigger near the right edge (right=1022); panel width 300 would end at
    // 1022 > 1024-8 → clamped to left = 1024 - 300 - 8 = 716.
    mockMenuRects(
      { left: 990, top: 100, right: 1022, bottom: 132, width: 32, height: 32 },
      { width: 300, height: 100 },
    )
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByTestId('menu-trigger'))

    const panel = screen.getByTestId('menu-panel') as HTMLDivElement
    await waitFor(() => {
      expect(panel.style.left).toBe(`${1024 - 300 - 8}px`)
      expect(panel.style.top).toBe(`${132 + 8}px`)
    })
  })

  it('should reposition on window scroll and resize while open', async () => {
    const trigger = { left: 400, top: 100, right: 432, bottom: 132, width: 32, height: 32 }
    mockMenuRects(trigger, { width: 220, height: 300 })
    const user = userEvent.setup()
    renderMenu()

    await user.click(screen.getByTestId('menu-trigger'))
    const panel = screen.getByTestId('menu-panel') as HTMLDivElement
    await waitFor(() => expect(panel.style.top).toBe('140px'))

    // Page scroll moves the trigger down by 200px → panel follows.
    trigger.top = 300
    trigger.bottom = 332
    act(() => {
      window.dispatchEvent(new Event('scroll'))
    })
    await waitFor(() => expect(panel.style.top).toBe(`${332 + 8}px`))

    // Resize moves the trigger again → panel follows.
    trigger.left = 100
    trigger.right = 132
    trigger.top = 50
    trigger.bottom = 82
    act(() => {
      window.dispatchEvent(new Event('resize'))
    })
    await waitFor(() => {
      expect(panel.style.top).toBe(`${82 + 8}px`)
      // left = 132 - 220 = -88 → clamped to the viewport margin (8px)
      expect(panel.style.left).toBe('8px')
    })
  })

  it('should reposition when a nested scroller (e.g. .table-wrapper) scrolls while open', async () => {
    const trigger = { left: 400, top: 100, right: 432, bottom: 132, width: 32, height: 32 }
    mockMenuRects(trigger, { width: 220, height: 300 })
    const user = userEvent.setup()
    // Render the menu inside a scrollable-looking wrapper like .table-wrapper.
    render(
      <div data-testid="table-wrapper">
        <Menu ariaLabel="Test menu" testId="menu-trigger" panelTestId="menu-panel" trigger={<span>Trigger</span>}>
          <MenuItem testId="item-a">Item A</MenuItem>
        </Menu>
      </div>,
    )

    await user.click(screen.getByTestId('menu-trigger'))
    const panel = screen.getByTestId('menu-panel') as HTMLDivElement
    await waitFor(() => expect(panel.style.top).toBe('140px'))

    // The wrapper scrolls: the trigger's viewport rect changes. Scroll events
    // do not bubble, but the capture-phase document listener catches them.
    trigger.top = 250
    trigger.bottom = 282
    act(() => {
      screen.getByTestId('table-wrapper').dispatchEvent(new Event('scroll'))
    })
    await waitFor(() => expect(panel.style.top).toBe(`${282 + 8}px`))
  })
})
