import { useMemo } from 'react';

import { useTheme } from './useTheme';

/**
 * WIKI4AI-185: farebné hodnoty pre JS renderéry (canvas, Monaco, Mermaid,
 * force-graph), ktoré vedia kresliť len konkrétny string — nie `var(--token)`.
 *
 * Jediný zdroj pravdy sú CSS tokeny v `src/styles/tokens.css`: hodnoty sa
 * čítajú z `<html>` cez `getComputedStyle`, takže JS paleta NIKDY nezaostane za
 * témou. Ak token nie je dostupný (jsdom bez CSS, meranie pred paintom),
 * padáme na FALLBACK = paleta default témy glass-dark.
 */
export interface ThemeColors {
  primary: string;
  secondary: string;
  accent: string;
  bg: string;
  surface: string;
  text: string;
  textMuted: string;
  border: string;
  glow: string;
  success: string;
  warning: string;
  danger: string;
  info: string;
}

/** Glass-dark tokeny — zhodné s `[data-theme='glass-dark']` v tokens.css.
 *  Fallback pre prostredie bez CSS (jsdom) MUSÍ byť DEFAULT téma, inak sa
 *  JS paleta líši od tej, čo používateľ reálne vidí pri prvom painte. */
const FALLBACK: ThemeColors = {
  primary: '#34d399',
  secondary: '#d4a574',
  accent: '#e07a5f',
  bg: '#070f0b',
  surface: '#0d1a14',
  text: '#f5f5f4',
  textMuted: 'rgba(245, 245, 244, 0.8)',
  border: 'rgba(255, 255, 255, 0.12)',
  glow: 'rgba(52, 211, 153, 0.4)',
  success: '#22c55e',
  warning: '#eab308',
  danger: '#f87171',
  info: '#38bdf8',
};

const TOKENS: Record<keyof ThemeColors, string> = {
  primary: '--primary',
  secondary: '--secondary',
  accent: '--accent',
  bg: '--bg',
  surface: '--surface-raised',
  text: '--text-primary',
  textMuted: '--text-secondary',
  border: '--border-default',
  glow: '--glow-cyan',
  success: '--success',
  warning: '--warning',
  danger: '--danger',
  info: '--info',
};

/**
 * Prečíta aktuálne tokeny z <html>. Volá sa po painte témy (useTheme already
 * applied `data-theme`), inak by sme čítali predchádzajúcu tému.
 */
export function readThemeColors(): ThemeColors {
  if (typeof document === 'undefined') return FALLBACK;
  const styles = getComputedStyle(document.documentElement);
  const out = { ...FALLBACK };
  for (const key of Object.keys(TOKENS) as (keyof ThemeColors)[]) {
    const value = styles.getPropertyValue(TOKENS[key]).trim();
    if (value) out[key] = value;
  }
  return out;
}

/**
 * Paleta viazaná na tému. `theme` je v deps schválne — React by inak vrátil
 * rovnaký objekt aj po prepnutí témy (tokeny sa menia až po efekte ThemeProvider,
 * preto sa čítajú memoizovane po zmene témy).
 */
export function useThemeColors(): ThemeColors {
  const { theme } = useTheme();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  return useMemo(() => readThemeColors(), [theme]);
}

/* ===== Chart paleta (WIKI4AI-208) =====================================
   9 sérií pre akúkoľvek vizualizáciu dát — uzly grafu, progress bary,
   legendy. Predtým si GraphView skladal paletu z akcentov témy
   (primary/secondary/accent/info/success/warning/danger/text):
     - `colors.text` bol uzol (textová farba ako výplň uzla — nezámerne),
     - 7. a 8. séria boli `--danger` a `--text`, teda stav a text, nie
       dátová séria,
     - CSS ratchet (`designTokens.test.ts`) paletu nevidel, lebo žije v TS
       a kreslí sa do canvasu — presne slepé miesto, ktoré už dokázal
       `Toast.tsx` gradient.
   Dnes je jediný zdroj `--chart-0..8` v `tokens.css`, per identita.
   `contrastBudget.test.ts` meria, že každá séria má na čitateľskom
   pozadí ≥ 3:1 (WCAG 1.4.11 ne-textový kontrast) a že sa série navzájom
   líšia — paleta teda nie je len dekoratívny zoznam hexov. */

/** Poradie je stabilné: index = ID série, nie umytie farby. */
export const CHART_TOKENS = [
  '--chart-0', '--chart-1', '--chart-2', '--chart-3', '--chart-4',
  '--chart-5', '--chart-6', '--chart-7', '--chart-8',
] as const

/** glass-dark hodnoty — fallback MUSÍ byť default téma, inak sa JS paleta
 *  líši od tej, čo používateľ vidí pri prvom painte (pozri FALLBACK vyššie). */
const CHART_FALLBACK = [
  '#34d399', '#d4a574', '#e07a5f', '#38bdf8', '#a3e635',
  '#f0abfc', '#fbbf24', '#7dd3fc', '#f09b83',
] as const

/** Canvas, Monaco ani Mermaid nevedia kresliť `var(--token)` — preto sa
 *  hodnoty čítajú z `<html>`. `getPropertyValue` vracia NErozbalené
 *  `var(...)` len pri neexistujúcom tokene, preto kontrola `|| fallback`. */
export function readChartPalette(): string[] {
  if (typeof document === 'undefined') return [...CHART_FALLBACK];
  const styles = getComputedStyle(document.documentElement);
  return CHART_TOKENS.map((token, i) => styles.getPropertyValue(token).trim() || CHART_FALLBACK[i]);
}

/** Memoizácia na `theme` — tokeny sa menia až v efekte ThemeProvider,
 *  bez deps by React vrátil starú paletu aj po prepnutí identity. */
export function useChartPalette(): string[] {
  const { theme } = useTheme();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  return useMemo(() => readChartPalette(), [theme]);
}
