/**
 * WIKI4AI-185 → WIKI4AI-192 (DIZ-1): 3 identity glass-dark / glass-light / nexaverse.
 *
 * Testy držia to, na čom závisí aj E2E a screenshoty:
 *  • predvolená téma je GLASS-DARK (emerald + gold, forum4Ai identita),
 *  • poradie segmentov aj cyklus idú podľa `THEME_ORDER`,
 *  • voľba prežíva reload (localStorage kľúč `wiki4ai-theme`),
 *  • `data-theme` sa píše na <html> (cez čo `tokens.css` vyberá paletu),
 *  • LEGACY voľba (`dark` / `light`) nespadne do defaultu — normalizuje sa
 *    na novú identitu a raz sa prepíše do localStorage,
 *  • nepoznatá hodnota v localStorage nesmie rozhodiť aplikáciu.
 */
import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

import ThemeSwitch from '../components/ThemeSwitch'
import { useTheme } from '../theme/useTheme';
import type { ThemeId } from '../theme/themes'
import { ThemeProvider } from '../theme/ThemeProvider'
import {
  DEFAULT_THEME,
  LEGACY_THEMES,
  THEMES,
  THEME_ICONS,
  THEME_ORDER,
  THEME_STORAGE_KEY,
  applyTheme,
  isThemeId,
  nextTheme,
  normalizeTheme,
  readStoredTheme,
} from '../theme/themes'

function renderSwitch() {
  return render(
    <ThemeProvider>
      <ThemeSwitch />
    </ThemeProvider>,
  )
}

describe('theme/themes.ts', () => {
  beforeEach(() => localStorage.clear())

  it('exposes exactly glass-dark / glass-light / nexaverse', () => {
    expect(THEMES).toEqual(['glass-dark', 'glass-light', 'nexaverse'])
    expect(THEME_ORDER).toEqual(THEMES)
  })

  it('defaults to glass-dark (deep-forest emerald + gold)', () => {
    expect(DEFAULT_THEME).toBe('glass-dark')
    expect(readStoredTheme()).toBe('glass-dark')
  })

  it('reads a stored theme back (persistence across reload)', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'nexaverse')
    expect(readStoredTheme()).toBe('nexaverse')
  })

  it('normalizes legacy dark → glass-dark and rewrites storage once', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    expect(readStoredTheme()).toBe('glass-dark')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('glass-dark')
  })

  it('normalizes legacy light → glass-light and rewrites storage once', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'light')
    expect(readStoredTheme()).toBe('glass-light')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('glass-light')
  })

  it('leaves an unknown stored value on the default without crashing', () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'halloween')
    expect(readStoredTheme()).toBe('glass-dark')
  })

  it('normalizeTheme maps every legacy id and keeps canonical ids', () => {
    for (const [legacy, target] of Object.entries(LEGACY_THEMES)) {
      expect(normalizeTheme(legacy)).toBe(target)
    }
    for (const id of THEMES) {
      expect(normalizeTheme(id)).toBe(id)
    }
    expect(normalizeTheme(undefined)).toBe(DEFAULT_THEME)
    /* kľuky Object.prototype nesmú prejsť ako legacy identita */
    expect(normalizeTheme('constructor')).toBe(DEFAULT_THEME)
    expect(normalizeTheme('toString')).toBe(DEFAULT_THEME)
  })

  it('isThemeId rejects anything outside the three ids', () => {
    expect(isThemeId('glass-dark')).toBe(true)
    expect(isThemeId('dark')).toBe(false)
    expect(isThemeId('halloween')).toBe(false)
    expect(isThemeId(null)).toBe(false)
  })

  it('cycles in THEME_ORDER (glass-dark → glass-light → nexaverse → …)', () => {
    expect(nextTheme('glass-dark')).toBe('glass-light')
    expect(nextTheme('glass-light')).toBe('nexaverse')
    expect(nextTheme('nexaverse')).toBe('glass-dark')
  })

  it('gives every identity a distinct icon (☾ / ☀ / ✦)', () => {
    expect(THEME_ICONS).toEqual({ 'glass-dark': '☾', 'glass-light': '☀', nexaverse: '✦' })
    expect(new Set(Object.values(THEME_ICONS)).size).toBe(THEMES.length)
  })

  it('applyTheme writes the canonical data-theme on <html>', () => {
    applyTheme('glass-light')
    expect(document.documentElement.getAttribute('data-theme')).toBe('glass-light')
    applyTheme('nexaverse')
    expect(document.documentElement.getAttribute('data-theme')).toBe('nexaverse')
  })
})

describe('ThemeSwitch', () => {
  beforeEach(() => localStorage.clear())

  it('renders three segments in THEME_ORDER and marks glass-dark active by default', () => {
    const { container } = renderSwitch()
    const buttons = Array.from(container.querySelectorAll<HTMLButtonElement>('.theme-option'))
    expect(buttons.map((b) => b.dataset.testid)).toEqual(
      THEME_ORDER.map((id) => `theme-option-${id}`),
    )
    expect(screen.getByTestId('theme-option-glass-dark')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('theme-option-nexaverse')).toHaveAttribute('aria-pressed', 'false')
  })

  it('shows the identity glyph in every segment', () => {
    renderSwitch()
    for (const id of THEME_ORDER) {
      expect(screen.getByTestId(`theme-option-${id}`)).toHaveTextContent(THEME_ICONS[id])
    }
  })

  it('switches theme, sets data-theme and persists to localStorage', async () => {
    const user = userEvent.setup()
    renderSwitch()

    await user.click(screen.getByTestId('theme-option-nexaverse'))

    expect(document.documentElement.getAttribute('data-theme')).toBe('nexaverse')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('nexaverse')
    expect(screen.getByTestId('theme-option-nexaverse')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByTestId('theme-option-glass-dark')).toHaveAttribute('aria-pressed', 'false')
  })

  it('starts from the stored theme (reload keeps the choice)', async () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'glass-light')
    renderSwitch()
    expect(screen.getByTestId('theme-option-glass-light')).toHaveAttribute('aria-pressed', 'true')
  })

  it('a legacy stored choice activates the renamed segment, not the default', async () => {
    localStorage.setItem(THEME_STORAGE_KEY, 'dark')
    renderSwitch()
    expect(screen.getByTestId('theme-option-glass-dark')).toHaveAttribute('aria-pressed', 'true')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('glass-dark')
  })

  it('is exposed as an accessible group', () => {
    renderSwitch()
    expect(screen.getByRole('group', { name: /color theme/i })).toBeInTheDocument()
  })
})

/**
 * WIKI4AI-194 (DIZ-3 item 12): farby grafu meškali o jedno prepnutie.
 * `useThemeColors()` číta `getComputedStyle(<html>)` počas renderu, ale
 * `applyTheme` behal len v efekte — teda PO painte. Render po `setTheme`
 * preto čítal tokeny starej témy a ďalší render bez zmeny deps nenastal.
 *
 * Test meria presne tú vlastnosť, na ktorej graf závisí: v okamihu,
 * keď `setTheme` vráti kontrolu, musí byť `<html data-theme>` prepísaný.
 * Volanie je zámerne mimo `act()` — netestujeme render, testujeme
 * okamžitý vedľajší účinok.
 */
describe('setTheme — synchronný zápis data-theme (WIKI4AI-194, DIZ-3 item 12)', () => {
  it('prepíše <html data-theme> skôr, než vráti kontrolu', () => {
    let setThemeRef: ((next: ThemeId) => void) | null = null

    function Probe() {
      const { setTheme } = useTheme()
      setThemeRef = setTheme
      return null
    }

    render(
      <ThemeProvider>
        <Probe />
      </ThemeProvider>,
    )
    applyTheme(DEFAULT_THEME)
    expect(document.documentElement.getAttribute('data-theme')).toBe(DEFAULT_THEME)

    setThemeRef!('glass-light')
    expect(
      document.documentElement.getAttribute('data-theme'),
      'data-theme musí byť prepísaný synchronne — graf si číta tokeny počas renderu',
    ).toBe('glass-light')

    /* localStorage musí ostať synchronizovaný, lebo FOUC guard v
       index.html číta práve tento kľúč. */
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('glass-light')
    localStorage.removeItem(THEME_STORAGE_KEY)
  })
})
