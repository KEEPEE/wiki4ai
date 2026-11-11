# Wiki4AI — dizajnový systém (WIKI4AI-192…208, DIZ-1…4)

Dokument pre človeka, ktorý dizajnový systém **nevidel**. Popisuje identity,
tokeny, pravidlá, poradie vrstiev, WCAG podlahy a **známe výnimky aj so
menami súborov** — aby sa dal systém meniť bez toho, niekto niečo ticho
pokazil.

Kontext: kampaň WIKI4AI-191 priniesla dizajnový systém inšpirovaný forum4Ai
(`packages/ui`). Staršia dokumentácia k témam je [`docs/ui-themes.md`](ui-themes.md)
(épa WIKI4AI-185) a **je vo viacerých bodoch neaktuálna** — predvolená téma je
dnes `glass-dark` (nie `nexaverse`) a tokeny žijú v `tokens.css` (nie
v `globals.css`). Kanonický zdroj je tento súbor + `src/styles/tokens.css`.

---

## 1. Identita = sada tokenov, nie téma

`data-theme` na `<html>` vyberá **identitu**. Každá identita je blok v
`src/styles/tokens.css`, ktorý predefinuje **rovnakú sadu 104 farebných
tokenov**. Zmena identity preto prekreslí celú aplikáciu bez jediného
pravidla v komponentovom CSS.

| identita | `data-theme` | povaha | primárna | sekundárna | accent |
|---|---|---|---|---|---|
| **glass-dark** (DEFAULT) | `glass-dark` | deep-forest glassmorphismus, emerald + gold (TemplateMo 607 „Glass Admin“) | `#34d399` | `#d4a574` | `#e07a5f` |
| **glass-light** | `glass-light` | svetlá varianta tej istej identity | `#047857` | `#8a6d3b` | `#c2603f` |
| **nexaverse** | `nexaverse` | neon tyrkysová / magenta / fialová (TemplateMo 603) | `#00f0ff` | `#ff00d4` | `#9d4edd` |

**Predvolená je glass-dark**, nie nexaverse: wiki sa číta cez deň a deep-forest
je čitateľnejší než neon. Rozhodnutie je v `src/theme/themeIdentity.ts`
(`DEFAULT_THEME`), nie v CSS.

### Legacy hodnoty

`[data-theme='dark']` a `[data-theme='light']` sú **aliasy** na glass identity
priamo v selektore (`[data-theme='glass-dark'], [data-theme='dark']`). Zároveň
`LEGACY_THEMES` v `themeIdentity.ts` prepíše uloženú hodnotu `dark` →
`glass-dark` a `light` → `glass-light` pri čítaní z `localStorage`, takže
prepínač má aktívny segment. Neznáma hodnota = default.

---

## 2. Kde čo žije

```
frontend/src/
  styles/tokens.css        ← JEDINÝ zdroj farieb; 3 identity + 13 zdieľaných tokenov
  styles/base.css          ← reset (Tailwind preflight v @layer base), typografia, pozadie
  styles/components.css    ← zdieľané primitíva (.btn, .form-input, .data-table, .modal)
  styles/app.css           ← vrstva aplikácie (.brand-name, .section-title, text utility)
  styles/globals.css       ← ŽIADNE pravidlá — len 4 importy v poradí
  theme/themeIdentity.ts   ← DOM-free jadro: mená, default, legacy mapping, cyklus
  theme/themes.ts          ← localStorage + <html data-theme>
  theme/themeBootstrap.ts  ← FOUC guard (importuje vite.config.ts)
  theme/themeColors.ts     ← paleta pre JS renderéry z getComputedStyle
  theme/ThemeProvider.tsx  ← React kontext
  components/ThemeSwitch.tsx ← 3-segmentový prepínač
  __tests__/designTokens.test.ts ← strážne testy (ratchet, kontrast, poradie)
```

### Poradie importov je súčasť dizajnu

`globals.css` importuje 4 vrstvy **v tomto poradí** a zmena poradia mení vzhľad:

```
1. tokens.css      → 2. base.css → 3. components.css → 4. app.css
```

Dôvod: `:root` a `[data-theme='…']` majú rovnakú špecifickosť (0,1,0) a
`@layer base` vždy prehrá s nevrstvenými pravidlami — preto reset musí ostať v
layeri a `app.css` musí byť posledne, aby prepisoval predchádzajúce vrstvy.
Strážne: `designTokens.test.ts` → *„poradie importov je tokens → base →
components → app“*.

---

## 3. Anatómia tokenov

`tokens.css` má 4 bloky:

| blok | tokenov | čo definuje |
|---|---|---|
| `:root, [data-theme='nexaverse']` | 104 | kanonická paleta tm-603 (1:1 kópia šablóny) |
| `[data-theme='glass-dark'], [data-theme='dark']` | 104 | emerald + gold |
| `[data-theme='glass-light'], [data-theme='light']` | 104 | svetlá varianta |
| `:root` (posledný) | 13 | tokeny nezávislé od identity (typografia, rozmery, prechody, focus ring) |

Tri identity definujú **maticu tokenov bez driftu** — žiadnej identite nechýba
žiadny token, ktorý majú ostatní. Strážne: *„všetky 3 identity definujú
ROVNAKÚ množinu tokenov“*.

### Kanonické mená vs. DERIVED

- **Kanonické** mená prebraté z tm-603: `--primary`, `--secondary`, `--accent`,
  `--dark-1..3`, `--glass-bg`, `--glass-border`, `--glow-cyan`,
  `--glow-magenta`. Každá identita ich predefinuje; komponenty sa tvária podľa
  nich.
- **DERIVED** aliasy (označené komentárom) sú vlastné doplnky Wiki4AI:
  `--text-1/2/3`, `--surface-*`, `--border-input`, `--scrim`, `--on-accent`,
  `--brand-gradient`, `--color-{success,error,warning,info}-{bg,border,text}`,
  `--orb-*`, `--grid-opacity`, `--color-scheme`, `--focus-ring*`.

### Dva sloty, nie dve farby

`--glow-cyan` a `--glow-magenta` sú **sloty**, nie názvy farieb. V nexaverse sú
to skutočne cyan a magenta; v glass identitách sú to **emerald a gold**.
Kto v komponente napíše `var(--glow-cyan)`, dostane v glass-dark emerald — a je
to správne. Kto napíše `rgba(0, 229, 255, …)`, zbaví prvok temovateľnosti.

### Farebné podtémy, ktoré nesú identitu

`--color-scheme` (`dark` | `light`) hovorí prehliadaču, ako maľovať natívne
kontroly a scrollbary. glass-light má `light`, zvyšné dve `dark`.

---

## 4. Ako prepínač funguje

- Jediný mechanizmus je atribút **`data-theme` na `<html>`**. Nič iné sa
  nenastavuje.
- `ThemeSwitch` (3 segmenty, `role="group"`, `aria-pressed`) je v sidebari,
  v mobilnej lište aj na auth stránkach (Login/Register nemajú Layout —
  testid `auth-theme-switch`). Testidy: `theme-switch`, `theme-option-<id>`.
- Perzistencia: `localStorage`, kľúč **`wiki4ai-theme`** (vlastný, aby sa
  nezbil s inou appkou na rovnakom portе).
- **FOUC guard**: `themeBootstrap.ts` vygeneruje IIFE, ktorý sa spustí pred
  prvým paintom, prečíta `localStorage` a nastaví `data-theme`. Kľúč, povolené
  hodnoty a default sa **nesmú rozísť** s `themeIdentity.ts` — strážne testy
  `themeBootstrap` kontrolujú zhodu.
- `themeIdentity.ts` **nesmie siať po `document` ani `localStorage`**:
  importuje ho `vite.config.ts`, ktoré sa kompiluje bez `DOM` lib. Keby tam
  bol prístup k DOM, `npm run build` zomrie a `npm run test:run` (jsdom) to
  neodhalí.

---

## 5. Čo patrí do `tokens.css` a čo nie

**Patrí:** akákoľvek farebná hodnota — hex, `rgb()`, `rgba()`, `hsl()`,
`hsla()`, pomenovaná farba. Vrstvy `base.css`, `components.css`, `app.css` aj
komponentové CSS píšu farby **výhradne** cez `var(--…)`.

**Neprepisujú sa** (13 tokenov v poslednom `:root` — identity nezávislé):
`--font-ui`, `--font-display`, `--font-mono`, `--radius`, `--radius-sm`,
`--card-pad`, `--sidebar-w`, `--transition-{fast,base,slow}`, `--focus-ring`,
`--focus-ring-offset`, `--mask-solid`. Posledný menovaný (`--mask-solid:
#000000`) je vedome **farebný, ale identity-nezávislý** — maska musí byť čierna
v každej identite; je to ďalšia z výnimiek, ktoré majú hex, ale žijú v
`tokens.css`, takže ratchetu nevadia.

**Výnimky, ktoré vedome mimo tokenov sú** (úplný zoznam, namerané
WIKI4AI-199):

1. **Monaco palety** — `components/MarkdownEditor.tsx`, 3 palety s vlastnými
   hex. Monaco nečíta nehex hodnoty ([WIKI4AI-83]: padá na červený fallback),
   preto sa palety píšu ako ručné hex. Ich kontrast je WCAG-stráženy testom
   priamo zo zdroja.
2. **Mermaid palety** — `components/MermaidDiagram.tsx` (`MERMAID_PALETTES`),
   hex per identita; téma je súčasť hashu ID diagramu, takže prekreslenie po
   zmene identity funguje.
3. **Tailwind preflight** v `styles/base.css` — verbatim kópia nasadeného
   buildu, obsahuje 2× `background-color:#0000`. Nesmie sa „upratovať“, lebo
   ide o kopiu, ktorá musí zodpovedať kaskáde buildu.
4. **`data:` URL chevron** `.form-select` — `%239ca3af` v `url()`; regex na
   hex ho nevidí a `url()` nevie čítať CSS premennú. Primitívum `.form-select`
   je preto obmedzené tak, aby `data:image` v `components.css` už nesmel byť
   (strážny test).

### Ratchet, nie sľub

Sľub „všetky farby len v `tokens.css`“ je implementovaný ako **ratchet**:
`HARD_COLOR_BUDGET` v `designTokens.test.ts` má per-súbor maximum výskytov.
Nový súbor musí mať 0, migrácia smie hodnotu **len znižovať**.

**Budget po WIKI4AI-208: 89 farebných hodnôt mimo `tokens.css` v 27 súboroch**
— a súčet budgetu je presne 89, teda ratchet je v tesnej zhode so
skutočnosťou (žiadny voľný priestor na regresiu). Oproti meraniu WIKI4AI-199
(2026-11-09), ktoré malo 98 hodnôt, klesli `pages/MetricsPage.css` 4 → 3
(`#7ad7ff` na dátovom povrchu nahradený `--chart-7`) a
`components/Toast.css` 8 → 0 (tien ide cez `--glass-shadow`, stavy cez
`--color-*-border` + `--{ok,error,info}-glow`, teda bez jediného nového čísla).
Rozpad merania WIKI4AI-199:
**64 chromatických** `rgba()` (nesú odtieň, takže neprejdú identitou),
**29 neutrálnych** alfa `rgba(0,0,0,x)` / `rgba(255,255,255,x)` (tiene,
scrimy), **4 hex** literály (`#7ad7ff` 2× v gradientoch, `#0000` 2× v
preflighte), **1 hex fallback** `var(--color-text-muted, #8b8fa3)` a
**0 pomenovaných farieb**. `hsl()`/`hsla()` mimo tokenov: 0.

Najväčší dlh: `pages/Profile.css` (18), `pages/ProjectDetail.css` (9),
`pages/VaultPage.css` (9), `components/Toast.css` (8),
`components/MarkdownToolbar.css` (6).

---

## 6. WCAG požiadavky

Strážne testy počítajú kontrast priamo zo zdrojových hodnôt
(`relativeLuminance` + `contrastRatio` v `designTokens.test.ts`), nie z
screenshotu — takže podlaha sa nedá odstrániť bez toho, aby niekto test zahol.

| pravidlo | podlaha | kde sa stráže |
|---|---|---|
| 1.4.3 Text | bežný text ≥ **4.5:1** | `--text-dim` glass-light ≥ alfa .66 (pôvodných .6 malo 4.31:1) |
| 1.4.3 Text v JS renderéri | číslo riadka Monaco ≥ **4.5:1** na vlastnom pozadí, pre **všetky 3 palety** (pred opravou 2.64 / 3.23 / 3.59:1) | `Monaco čísla riadkov majú ≥ 4.5:1` |
| 1.4.11 Ne-textový kontrast | hranica vstupného poľa ≥ **3:1** → `--border-input` alfa ≥ **.40** (namerané 3.2–3.8:1; `--glass-border` má na karte len 1.22–1.43:1, preto primitíva `.form-input`/`.form-select` **musia** používať `--border-input`) | `všetky 3 identity majú --border-input a --scrim` |
| 1.4.11 Focus | focus ring je **pevná orámovacia čiara** `--focus-ring: Npx solid var(--focus-ring-color)`, nie glow halo — `0 0 20px var(--glow-cyan)` nemá tvrdú hranu a 3:1 nesplní nikdy | `--focus-ring je pevná orámovacia čiara` |
| Scrim modalu | `--scrim` alfa ≥ **.50** | rovnaký test ako `--border-input` |
| 2.4.7 / 2.1.2 | focus viditeľný, Monaco nepohlcuje Tab (WCAG 2.1.2 No Keyboard Trap) | DIZ-4 item 10 |

**Past, na ktorú si dať pozor:** `--on-accent` je `#ffffff` vo všetkých 3
identitách. Na `#34d399` (glass-dark primárna) má biela len **1.92:1** a na
`#00f0ff` (nexaverse) **1.41:1**. Preto sa na accent pozadíach nesmie
spoliehať na `--on-accent` — gradient na fill tlačidlách bol odpustený, lebo
končil na `#c2603f` s pomerom 3.33:1 (1.4.3 failure).

---

## 7. Ako pridať novú identitu

1. **`src/theme/themeIdentity.ts`** — pridať id do `THEMES` (unia `ThemeId`),
   do `THEME_ORDER` (poradie segmentov aj cyklus) a do `THEME_ICONS` (glyf,
   nie emoji). Pozor: súbor je DOM-free.
2. **`src/styles/tokens.css`** — pridať blok `[data-theme='<nova>']` so
   **všetkými 104 tokenmi**. Blok sa vkladá **za** existujúce identity (poradie
   blokov je dizajn). Definovať aj `--color-scheme`, `--on-accent`,
   `--brand-gradient`, sémantické `--color-{success,error,warning,info}-*`,
   `--border-input`, `--scrim`, `--focus-ring-color`, `--orb-*`,
   `--grid-opacity`.
3. **`src/theme/themeBootstrap.ts`** — doplniť id do povolených hodnôt FOUC
   guardu (inak sa prvý paint vráti do defaultu).
4. **i18n** — `layout.theme.<id>` a `layout.theme.<id>Hint` v
   `src/locales/en.json` **aj** `sk.json` (test `i18nParity` kontroluje zhodu).
5. **Monaco** — pridať hex paletu do mapy v `MarkdownEditor.tsx` a definíciu
   témy. Hex, nie `var()` (WIKI4AI-83). Kontrast čísel riadkov ≥ 4.5:1.
6. **Mermaid** — doplniť záznam do `MERMAID_PALETTES` pre nové id.
7. **GraphView / canvas** — farby idú automaticky z tokenov
   (`themeColors.ts` → `getComputedStyle`), stačí definovať
   `--primary/--secondary/--accent`. Fallback v `themeColors.ts` ale musí
   zodpovedať **default** identite.
8. **Testy** — `designTokens.test.ts` (ratchet, kompletnosť blokov, kontrastné
   podlahy počítané per identita), `themeSwitch.test.tsx` (default,
   perzistencia, `data-theme`, prístupnosť), `mermaidThemePalettes.test.ts`,
   `toolchain.test.ts`. Všetky iterujú `THEME_ORDER`, takže nová identita sa
   v nich musí objaviť automaticky — ak nie, test si hardcodeuje 3 identity.

---

## 8. Čo kontrolujú strážne testy (rejstík)

`src/__tests__/designTokens.test.ts` — 30+ testov, najdôležitejšie:

- `tokens.css` je jediný zdroj farieb; bloky v poradí nexaverse → glass-dark →
  glass-light; legacy `dark`/`light` sú aliasy
- ratchet `HARD_COLOR_BUDGET` — žiadny súbor mimo `tokens.css` nesmie mať viac
  farieb, než sľubuje budget (a budget sa píše z nameraných hodnôt)
- `components.css` má **0** tvrdých farieb a žiadne `data:` URL
- `.form-input`/`.form-select` hranica z `--border-input`; `.data-table`
  definuje iba `components.css` (žiadne kópie primitíva)
- `--focus-ring` je orámovacia čiara; `--border-input`/`--scrim` alfy;
  `--text-dim` podlaha; Monaco kontrast per paleta
- žiadne CSS okrem `tokens.css` nesmie selektovať `[data-theme=…]`
  (overrides žijú v tokenoch)
- poradie importov v `globals.css`
- `--glass-bg` mlha a `--orb-*` nesmú presiahnuť alfu 0.60 (zlievajú susedné
  karty)

---

## 9. Známe slabé miesta systému (otvorené k WIKI4AI-199)

1. **89 tvrdých farieb mimo tokenov** (pri meraní WIKI4AI-199 ich bolo 98) —
   z toho 64 chromatických. Chromatické
   literály sa **neprekreslia** pri zmene identity. Konkrétne:
   `rgba(52, 211, 153, 0.6)` (emerald glass-dark accent) je natvrdo v
   `pages/BoardPage.css:254` a `pages/WorkItemDetailPage.css:492`;
   `rgba(0, 229, 255, 0.3)` (nexaverse cyan) v
   `components/DocumentLinks.css:94`; `rgba(0, 200, 220, 0.15)` v
   `pages/VaultPage.css:289`; `#7ad7ff` v `pages/ResourcesPage.css:135`. V glass identitách tieto prvky svietia
   nexaverse farbou a naopak.
2. **Sémantické stavové farby sú natvrdo per stránka** — Tailwind paleta
   (`rgba(239,68,68)` red-500, `rgba(245,158,11)` amber-500,
   `rgba(34,197,94)` green-500, `rgba(59,130,246)` blue-500,
   `rgba(139,92,246)` violet-500, `rgba(16,185,129)` emerald-500) obchádza
   identity-špecifické `--color-{success,error,warning,info}-*` tokeny, ktoré
   `tokens.css` definuje inak pre každú identitu. Najhustejšie:
   `pages/Profile.css` (14), `pages/ProjectDetail.css` (6),
   `pages/VaultPage.css` (6).
   **Dotiahnuté čiastočne (WIKI4AI-208):** `--color-{success,error,warning,info}-{bg,border}`
   a `--success/--warning/--danger` sú dnes **odvodené** od triád
   `--ok/--warn/--error/--info` cez `color-mix`, takže stránky, ktoré
   `--color-*-` tokeny už používajú, idú cez identitu. Priame použitie
   `var(--ok)` mimo `tokens.css` má zatiaľ len `components/DocumentLinks.css`.
   Zostáva **31 surových Tailwind `rgba()` literálov** v stránkovom CSS
   (`Profile.css`, `SearchPage.css`, `VaultPage.css`, `ProjectDetail.css`,
   `DocumentEditor.css`) — tie otvára WIKI4AI-207.
3. **`docs/ui-themes.md` je neaktuálny** (default nexaverse, tokeny v
   `globals.css`, 3 témy light/dark/nexaverse). Kanonický zdroj je tento súbor.

---

## 10. Épa WIKI4AI-206/208: sýtosť, nie štruktúra

Zadanie znelo „aiforum má webUI farebnejší a živší, sprav to aj tu" — **grafika**,
nie funkčnosť. Referencia bola `forum4ai` a rozdiel bol merateľný: 172 tokenov
versus 117, 38 `linear-gradient` versus 5, 66 `box-shadow` versus 5, 25
`transition:` versus 7.

Prekvapenie: **ambientná vrstva už existovala** —
`src/components/AmbientBackground.tsx` + `.css` (9 `radial-gradient`-ov, 3
nezávisle animované gule, mriežka, vinštka), mountovaná v `Layout.tsx` a na
auth stránkach. Bola len **neviditeľná**: `body` malo nepriehľadné
`background-color: var(--bg-0)` a background-propagation z `body` na canvas
prekrylo `.background` vrstvu s `z-index: -2`. Oprava bola jedna deklarácia
(`base.css`), dopad 97,9 % pixelov viewportu.

Poučenie pre ďalšieho čitateľa: **vrstva nie je v `styles/`**, je v
`components/`. Kto hľadá `radial-gradient` len v `src/styles/`, tvrdí, že
vrstva neexistuje.

Čo épa pridala, celé cez tokeny:

| token | čo robí |
|---|---|
| `--action-gradient` / `--action-gradient-hover` / `--action-glow` | gradient a glow primárnej akcie (`--primary → --secondary`, nie `--accent`) |
| `--pill-active-bg` / `--pill-active-color` / `--pill-active-border` | aktívny pill; jediné miesto, kde sa identity líšia |
| `--nav-active-bg` / `--nav-active-color` / `--nav-active-shadow` | aktívny bod navigácie |
| `--ok` `--info` `--warn` `--error` + `-soft` / `-glow` | sémantické triády; `-soft`/`-glow` cez `color-mix`, teda bez nových hex čísel |
| `--chart-0..8` | paleta grafov per identita, napojená na canvas aj share bar |
| `--bg-mist-opacity` / `--orb-opacity` / `--vignette-opacity` | intenzita ambientnej vrstvy per identita |

**Tichá zóna.** `data-mood` je atribút na `<html>` (nemôže byť na obale, lebo
hmla je na `body`) a násobí intenzitu hmly a gúľ. Cesty document / editor /
search dostávajú `data-mood="quiet"` → hmla `.16`, gule `.14`,
`.markdown-preview` aj `.mermaid-diagram` dostanú nepriehľadné pozadie a
`backdrop-filter: none`. Wiki4AI je čítačka dokumentov — živosť patrí na
dashboard a zoznamy, nie pod dlhú prózu.

**`prefers-reduced-motion`** blok musí použíť `html[data-theme]` (nie `:root`,
inak ho predpísanosť bloku identít prepíše) a `animation-duration: .01ms`
(nie `animation: none`, čo by nechalo `fadeIn` prvky na `opacity: 0`).

### Dve pasce, v ktorých sa merá klame

1. **`background-image` gradient úplne prekrýva `background-color`.** Ak pri
   meraní kontrastu počítaš aj voči `background-color`, dostaneš falošné
   zlyhanie — skutočná výplň nie je na obrazovke vôbec vidieť. Ak existuje
   gradient, počítaj **iba jeho stop-y**.
2. **`transition` na `color` klame pri prepínaní tém.** Hneď po nastavení
   `data-theme` `getComputedStyle` vráti farbu **predchádzajúcej** identity a
   po časti prechodu prostrednú hodnotu. Pred odberom vlož
   `*{transition:none!important;animation:none!important;}`. Inak meríš
   animáciu, nie dizajn.

### Kontrast na nasadenom buildu (WCAG 1.4.3, podlaha 4,5:1)

| prvok | glass-dark | glass-light | nexaverse |
|---|---|---|---|
| primárna akcia | 7,47 | 4,64 | 5,97 |
| aktívny pill | 7,47 | 4,64 | 5,97 |
| prepínač kalendára (aktívny) | 7,47 | 4,64 | 5,97 |
| prepínač kalendára (neaktívny) | 11,68 | 6,84 | 12,11 |

Pred týmto kolom boli tie isté prvky na 2,94 – 4,39:1. Príčina bola dvojitá:
`--dark-1` **nie je atrament** (je to tmavá *škála* identity, v glass-light
`#e2e8db` takmer biela — správny token je `--text-inverse`) a
`--glow-cyan` / `--glow-magenta` sú **sloty** s alfa 0,4 / 0,25, nie farby —
ako `background` výplň zalia text.

### Čo z épy zostalo nedokončené

- `--glow-primary` token bol navrhnutý, v `tokens.css` **nie je** — na živom
  buildu je prázdny.
- Alias `--mist-1..3` / `--orb-1..3` má **0 použití** (mŕtve aliasy nad
  `--bg-mist-*`). Zapojiť alebo zmazať, aby ich ďalší čitateľ nemýlil.
