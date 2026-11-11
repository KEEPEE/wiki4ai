/**
 * Identita tém — DOM-free jadro (WIKI4AI-192, DIZ-1).
 *
 * Tento súbor NESMÍ siať po `document` ani `localStorage`. Dôvod nie je
 * estetický: `vite.config.ts` importuje `themeBootstrap.ts`, ktorý importuje
 * odtiaľto konštanty, a Vite kompiluje svoju konfiguráciu pod
 * `tsconfig.node.json` (lib bez `DOM`). Ak by tu bol čo i len jeden prístup
 * k DOM, `npm run build` by zomrel na `Cannot find name 'document'` — a
 * `npm run test:run` by to nechal nepovšimnuté, lebo testy bežia v jsdom.
 *
 * Témy sú KANONICKÉ identity — `data-theme` na `<html>` ich posiela priamo do
 * `src/styles/tokens.css`, kde každá predefinuje rovnakú sadu tokenov:
 *
 *   glass-dark   DEFAULT — deep-forest glassmorphismus, emerald + gold
 *                (TemplateMo 607 „Glass Admin")
 *   glass-light  svetlá varianta tej istej identity
 *   nexaverse    neon cyan / magenta / purple (TemplateMo 603)
 *
 * Predvolená je GLASS-DARK, nie nexaverse: wiki sa číta cez deň a deep-forest
 * je čitateľnejší než neon (rovnaké rozhodnutie ako forum4Ai).
 *
 * Staré hodnoty (`dark`, `light`) sa pri čítaní stále akceptujú a mapujú na
 * novú identitu, aby používateľ s voľbou z éry WIKI4AI-185 nespadol do
 * defaultu.
 */

export const THEMES = ['glass-dark', 'glass-light', 'nexaverse'] as const

export type ThemeId = (typeof THEMES)[number]

/**
 * Kľúč v localStorage — vlastný, aby sa nezbil s inou appkou na rovnakom
 * doméne/porte (glass-admin originál používa generický 'theme').
 */
export const THEME_STORAGE_KEY = 'wiki4ai-theme'

export const DEFAULT_THEME: ThemeId = 'glass-dark'

/**
 * Poradie = poradie segmentov v prepínačovi aj cyklus tém.
 * Jeden zdroj pravdy pre cyklus (forum4Ai `THEME_ORDER`).
 */
export const THEME_ORDER: readonly ThemeId[] = THEMES

/** staré hodnoty → kanonická téma (záchrana uložených volieb z WIKI4AI-185) */
export const LEGACY_THEMES: Readonly<Record<string, ThemeId>> = {
  dark: 'glass-dark',
  light: 'glass-light',
}

/** ikony segmentov: mesiac / slnko / neonová hviezda (forum4Ai `THEME_ICONS`) */
export const THEME_ICONS: Record<ThemeId, string> = {
  'glass-dark': '☾',
  'glass-light': '☀',
  nexaverse: '✦',
}

export function isThemeId(value: unknown): value is ThemeId {
  return typeof value === 'string' && (THEMES as readonly string[]).includes(value)
}

/**
 * Normalizuje uloženú aj legacy hodnotu; neznáma hodnota = default.
 *
 * `hasOwnProperty` nie je paranoidný detail: bez neho by `normalizeTheme(
 * 'constructor')` vrátila konštruktor `Object` (dedená vlastnosť) a
 * `<html data-theme>` by niesla reťazec funkcie.
 */
export function normalizeTheme(value: unknown): ThemeId {
  if (isThemeId(value)) return value
  if (typeof value === 'string' && Object.prototype.hasOwnProperty.call(LEGACY_THEMES, value)) {
    return LEGACY_THEMES[value]
  }
  return DEFAULT_THEME
}

/** Cyklus v poradí `THEME_ORDER` (glass-dark → glass-light → nexaverse → …). */
export function nextTheme(current: ThemeId): ThemeId {
  const index = THEME_ORDER.indexOf(normalizeTheme(current))
  return THEME_ORDER[(index + 1) % THEME_ORDER.length] ?? DEFAULT_THEME
}
