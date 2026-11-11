# Wiki4AI WebUI — témy (WIKI4AI-185)

> **⚠️ ČASŤ TOHOTO DOKUMENTU JE NEAKTUÁLNA (WIKI4AI-199).** Épa WIKI4AI-185
> mala 3 témy `light`/`dark`/`nexaverse` s **predvolenou nexaverse** a tokeny v
> `globals.css`. Kampaň WIKI4AI-191 to zmenila: identity sú `glass-dark`
> (**DEFAULT**), `glass-light`, `nexaverse`, legacy `dark`/`light` sú aliasy, a
> farebné tokeny žijú výhradne v `src/styles/tokens.css` (4-vrstvové poradie
> importov). Kanonický zdroj je **[docs/design-system.md](design-system.md)**.
> Popis mechanizmu prepínania, perzistencie a FOUC guardu nižšie platí naďalej.

WebUI má **3 prepínateľné témy**: `light`, `dark`, `nexaverse`. Podkladom je
šablóna **TemplateMo 607 Glass Admin** (sidebar WIKI / COORDINATION / ACCOUNT,
`.background` + 3 `.orb`, glass karty); `nexaverse` paleta je prevzatá zo
šablóny 603 a je **predvolená** — existujúce screenshoty, testy aj E2E ostanú platné.

## Ako sa prepína

- Jediný mechanizmus je atribút **`data-theme` na `<html>`** (`light` | `dark` | `nexaverse`).
- Komponent `src/components/ThemeSwitch.tsx` (3 segmenty, `role="group"`,
  `aria-pressed`) je v sidebari, v mobilnej lište a **na auth stránkach**
  (Login/Register nemajú Layout — testid `auth-theme-switch`).
- Testidy: `theme-switch`, `theme-option-<id>` (v top bare prefix `theme-top`).
- Stav drží `ThemeProvider` (`src/theme/ThemeProvider.tsx`), komponenty čítajú
  cez `useTheme()` (`src/theme/useTheme.ts` — context je mimo providera
  kvôli Fast Refresh pravidlám).

## Perzistencia

- Kľúč v `localStorage`: **`wiki4ai-theme`** (vlastný, aby sa nezbil s inou
  appkou na rovnakom doméne/portu).
- Predvolená hodnota: **`nexaverse`** (pri prázdnom alebo blokovanom localStorage).
- **FOUC guard** v `index.html`: malý IIFE skript pred prvým paintom prečíta
  `localStorage` a nastaví `data-theme`. Kľúč, povolené hodnoty aj predvolená
  téma MUSIA zostať synchronizované s `src/theme/themes.ts` — zmeníš jedno,
  uprav obe.

## Kde sú farby

- **Jediný zdroj pravdy** sú CSS tokeny v `src/styles/globals.css` — 3 bloky
  (`:root, [data-theme='nexaverse']`, `[data-theme='light']`, `[data-theme='dark']`).
- V `pages/*.css` a `components/*.css` sú **0 hex literálov mimo `var()`
  fallbackov**; zostávajúce `rgba()` alphy sú zámerne sémantické odtiene
  čitateľné vo všetkých troch témach.
- JS renderéry (Monaco, Mermaid, GraphView/canvas) nevedia `var(--token)` —
  palety sa im dodávajú z tokenov cez `getComputedStyle`
  (`src/theme/themeColors.ts`, `useThemeColors()`, `buildNodePalette()`),
  takže nikdy nezaostanú za témou. Fallback = nexaverse.

## Čo urobiť pri novej téme

1. `src/theme/themes.ts` — pridať id do `THEMES`, `ThemeId` a `THEME_META`
   (i18n kľúče `layout.theme.*` v `src/locales/en.json` aj `sk.json`).
2. `src/styles/globals.css` — pridať blok `[data-theme='<nova>']` so **všetkými**
   tokenmi (pozadie, povrch, glass, texty, akcenty, `--on-accent`, sémantické
   `--color-{success,error,warning,info}-{bg,border,text}`, aliasy `--orb-*`,
   `--grid-opacity`, `--color-scheme`, …).
3. `index.html` — doplniť id do pole `ALLOWED` vo FOUC guardi.
4. **Monaco:** pridať hex paletu do `MONACO_THEME_BY_APP_THEME` a definíciu
   témy. Pozor: Monaco nedokáže čítať nehex hodnoty ([WIKI4AI-83] — padá na
   červený fallback), palety sa píšu ako ručné hex, nie z `getComputedStyle`.
   Názov `wiki4ai-dark` je zámerne viazaný na nexaverse (viaže sa naň test).
5. **Mermaid:** doplniť paletu per téma — `applyMermaidTheme()` sa volá pred
   každým renderom a téma je súčasťou hashu ID diagramu.
6. **GraphView:** farby idú z tokenov automaticky (`buildNodePalette()`),
   stačí aby nová téma definovala `--primary/--secondary/--accent`.
7. Testy: `src/__tests__/themeSwitch.test.tsx` kontroluje default, perzistenciu,
   `data-theme` na `<html>` a prístupnosť — rozšíriť na novú tému.

## Známé kozmetické poznámky

- V light téme je header tabuľky svetlosivý pruh — čitateľné, pri ďalšej
  iterácii sa dá zjemniť.
- Fonty ostávajú Outfit + Syne (Space Mono zo šablóny zámerne neprevzaté,
  aby nexaverse vzhľad zostal zachovaný).
