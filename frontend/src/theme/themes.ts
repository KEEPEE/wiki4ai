/**
 * Témy WebUI Wiki4AI (WIKI4AI-185 → WIKI4AI-192, DIZ-1).
 *
 * Mená, poradie, legacy mapping a ikony žijú v `themeIdentity.ts` (DOM-free
 * jadro, ktoré môže importovať aj `vite.config.ts`). Tu je vrstva, ktorá
 * siaha po `localStorage` a `<html data-theme=…>`.
 *
 * Verejné rozhranie sa nemení: `themes.ts` stále exportuje všetko, čo
 * komponenty dovážali pred DIZ-1.
 */

import {
  DEFAULT_THEME,
  THEME_ICONS,
  THEME_ORDER,
  THEME_STORAGE_KEY,
  normalizeTheme,
} from './themeIdentity'
import type { ThemeId } from './themeIdentity'

export * from './themeIdentity'
export type { ThemeId } from './themeIdentity'

export interface ThemeMeta {
  id: ThemeId
  /** i18n kľúč popisku (src/locales/{en,sk}.json → layout.theme.*). */
  labelKey: string
  /** i18n kľúč tipu pre title/aria-label. */
  hintKey: string
  /** glyf segmentu — nie emoji, bežný Unicode symbol (DejaVu/Noto coverage). */
  icon: string
}

/** Poradie segmentov switcha = glass-dark → glass-light → nexaverse. */
export const THEME_META: ThemeMeta[] = THEME_ORDER.map((id) => ({
  id,
  labelKey: `layout.theme.${id}`,
  hintKey: `layout.theme.${id}Hint`,
  icon: THEME_ICONS[id],
}))

/**
 * Prečíta uloženú tému a raz ju prepíše na kanonickú identitu.
 *
 * Bez prepisu by používateľ s `dark` síce dostal glass-dark vzhľad, ale
 * `<html data-theme>` by niesol staré meno a prepínač by nemal žiadny
 * aktívny segment.
 */
export function readStoredTheme(): ThemeId {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY)
    const resolved = normalizeTheme(stored)
    if (stored !== resolved) localStorage.setItem(THEME_STORAGE_KEY, resolved)
    return resolved
  } catch {
    /* localStorage môže byť blokovaný (súkromný režim) — padáme na default */
  }
  return DEFAULT_THEME
}

/** Nastaví tému na <html data-theme=…>. Volá sa pri štarte aj pri zmene. */
export function applyTheme(theme: ThemeId): void {
  document.documentElement.setAttribute('data-theme', normalizeTheme(theme))
}
