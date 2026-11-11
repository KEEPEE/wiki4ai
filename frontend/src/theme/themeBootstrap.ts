/**
 * FOUC bootstrap — jediný zdroj pre inline skript v `index.html` (DIZ-1).
 *
 * Téma sa musí dostať na `<html>` PRED prvým paintom: CSS sa loaduje
 * asynchrónne a bez tohto skriptu by prvý snímok bol v predvolenej téme a
 * až potom blikol na uloženú.
 *
 * Skript bol pred DIZ-1 ručne prepísaný v `index.html` a mal vlastný zoznam
 * povolených hodnôt — presne ten druh driftu, ktorý strážny test
 * `designTokens.test.ts` rieši, ale generovaním sa mu nedá vôbec prísť:
 * `vite.config.ts` importuje `buildThemeBootstrapScript()` a vkladá výsledok
 * do `index.html` cez `transformIndexHtml`.
 *
 * MUSÍ ostať ES5 a bez importov — beží v HTML pred tým, než existuje nejaký
 * bundler. Preto je telo skriptu reťazec, nie funkcia.
 */

import { DEFAULT_THEME, LEGACY_THEMES, THEME_ORDER, THEME_STORAGE_KEY } from './themeIdentity'

/**
 * Vygeneruje telo bootstrap skriptu (bez `<script>` tagu).
 *
 * Robí to isté čo `readStoredTheme()`: akceptuje kanonické identity,
 * normalizuje legacy `dark` / `light` na novú identitu a starú voľbu raz
 * prepíše späť do localStorage, aby `<html data-theme>` po zvyšok životnosti
 * niesol kanonické meno.
 */
export function buildThemeBootstrapScript(): string {
  const allowed = THEME_ORDER.map((id) => `'${id}'`).join(',')
  const legacy = Object.entries(LEGACY_THEMES)
    .map(([from, to]) => `${from}:'${to}'`)
    .join(',')

  return [
    '(function () {',
    `var KEY = '${THEME_STORAGE_KEY}';`,
    `var ALLOWED = [${allowed}];`,
    `var LEGACY = {${legacy}};`,
    `var FALLBACK = '${DEFAULT_THEME}';`,
    'var theme = FALLBACK;',
    'try {',
    'var stored = window.localStorage.getItem(KEY);',
    'if (stored) {',
    'if (ALLOWED.indexOf(stored) !== -1) { theme = stored; }',
    "else if (Object.prototype.hasOwnProperty.call(LEGACY, stored)) {",
    'theme = LEGACY[stored];',
    'try { window.localStorage.setItem(KEY, theme); } catch (w) { /* bez perzistencie */ }',
    '}',
    '}',
    '} catch (e) {',
    '/* localStorage blokovaný (súkromný režim) — ostáva predvolená téma */',
    '}',
    "document.documentElement.setAttribute('data-theme', theme);",
    '})();',
  ].join('')
}

/** Bootstrap skript vrátane `<script>` tagu, pripravený do `index.html`. */
export function buildThemeBootstrapTag(): string {
  return `<script>${buildThemeBootstrapScript()}</script>`
}
