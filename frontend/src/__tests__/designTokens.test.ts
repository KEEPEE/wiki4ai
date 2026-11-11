/**
 * Strážne testy diznového systému (WIKI4AI-192, DIZ-1).
 *
 * Inšpirácia: forum4Ai `packages/ui/test/tokens.test.ts`. Rozdiel: Wiki4AI má
 * 32 komponentových CSS ešte nestrhnutých na tokeny, preto pravidlo „žiadna
 * tvrdá farba mimo `tokens.css`“ je RATCHET — sľub, ktorý sa dá len zlepšovať.
 * Kľúč je súbor, hodnota je maximálny počet výskytov. Nový súbor musí mať 0 a
 * migrácia súbor smie iba znížiť. Bez tohto kompromisu by story blokoval
 * červený test skôr, než by bola migrácia vôbec v pláne.
 */
import { existsSync, readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'

import {
  DEFAULT_THEME,
  LEGACY_THEMES,
  THEMES,
  THEME_ORDER,
  THEME_STORAGE_KEY,
} from '../theme/themes'
import { buildThemeBootstrapScript } from '../theme/themeBootstrap'

const here = dirname(fileURLToPath(import.meta.url))
const srcRoot = resolve(here, '..')

const read = (rel: string): string => {
  const path = join(srcRoot, rel)
  expect(existsPath(path), `chýba súbor ${rel}`).toBe(true)
  return readFileSync(path, 'utf8')
}

function existsPath(path: string): boolean {
  return existsSync(path)
}

/** komentáre nesmú hovoriť do strážneho testu — pravidlá počítame v kóde */
function stripComments(css: string): string {
  return css.replace(/\/\*[\s\S]*?\*\//g, ' ').replace(/^\s*\/\/.*$/gm, ' ')
}

/** `#hex`, `rgb(`, `rgba(` — presne to, čo musí žiť v `tokens.css`. */
const HARD_COLOR = /#[0-9a-fA-F]{3,8}\b|\brgba?\(/g

/**
 * Mená farieb (`color: white`) — `HARD_COLOR` nevidí `DocumentEditor.css:118`
 * (`background-color: white`) a `:159` (`color: white`), pritom v glass-light
 * je biely text takmer na bielom pilulke nečítateľný. Lookbehind/lookahead
 * nesmú chytiť `white-space` ani `--glow-cyan` (pomlčka je word boundary).
 */
const NAMED_COLOR =
  /(?<![-\w#])(white|black|red|green|blue|gray|grey|orange|purple|yellow|cyan|magenta|silver|maroon|navy|teal|olive|lime|aqua|fuchsia|ivory|beige|coral|gold|salmon|indigo|violet|crimson|tan|wheat|khaki)(?![\w-])/g

/** Tvrdé + pomenované farby v súbore — koľkýkrát sa v ňom farba opakuje. */
function countColors(css: string): number {
  const body = stripComments(css)
  return (body.match(HARD_COLOR) ?? []).length + (body.match(NAMED_COLOR) ?? []).length
}

/**
 * RATCHET: povolený počet tvrdých ANI pomenovaných farieb mimo `tokens.css`.
 * História: 171 výskytov v 31 súboroch pri zavedení testu (WIKI4AI-192) →
 * 173 po započítaní pomenovaných farieb (WIKI4AI-194, `DocumentEditor.css`
 * 5 → 7, dvakrát `white`) → 155 po primitíve `.btn` (WIKI4AI-194, DIZ-3
 * item 2), ktorý zrazil `VaultEntryForm.css` na 0, `Profile.css` 36 → 32,
 * `Dashboard.css` 7 → 3, `CalendarPage.css` 4 → 2 a `AgentsPage.css` 4 → 3.
 * Budget sa PÍŠE z nameraných hodnôt — ratchet je obojsmerný, takže ho
 * nemožno nechať voľnejší, než je skutočný stav.
 * `styles/base.css` (2) je neupravovateľná kópia Tailwind preflightu
 * (`background-color:#0000`); `.form-select` chevron má `%239ca3af` v
 * `data:` URL — ten regex nechytí, lebo `#` je percent-encode, a reťazec v
 * `url()` aj tak nedokáže čítať CSS premennú.
 * WIKI4AI-208: 98 → 90 → 89 (`pages/MetricsPage.css` 4 → 3 — `#7ad7ff`
 * na dátovom povrchu nahradený `--chart-7`). `components/Toast.css` 8 → 0 — posledných 8 tvrdých
 * farieb mimo `tokens.css` boli tieň `rgba(0, 0, 0, 0.3)` (v glass-light
 * príliš tvrdý) a stavové border/glow `rgba(22,163,74,.3)` /
 * `rgba(16,185,129,.3)` / `rgba(220,38,38,.3)` / `rgba(239,68,68,.3)`.
 * Tieň je `--glass-shadow` (per-identita), stavy idú cez
 * `--color-*-border` + `--{ok,error,info}-glow`, ktoré sa od sémantickej
 * trojice odvádzajú `color-mix()` — teda bez jediného nového čísla.
 */
const HARD_COLOR_BUDGET: Record<string, number> = {
  'components/Breadcrumb.css': 1,
  'components/DocumentLinks.css': 1,
  'components/EventFormModal.css': 2,
  'components/GraphView.css': 1,
  'components/Layout.css': 1,
  'components/MarkdownToolbar.css': 6,
  'components/Menu.css': 2,
  'components/MermaidDiagram.css': 3,
  'components/PlantUmlDiagram.css': 4,
  'components/SidebarNav.css': 1,
  'components/Toast.css': 0,
  'pages/AdminUsers.css': 2,
  'pages/AgentsPage.css': 3,
  'pages/BoardPage.css': 3,
  'pages/Dashboard.css': 1,
  'pages/DocumentViewer.css': 1,
  'pages/Login.css': 2,
  'pages/MetricsPage.css': 3,
  'pages/NotFound.css': 1,
  'pages/Profile.css': 18,
  'pages/ProjectDetail.css': 9,
  'pages/ProjectSettings.css': 2,
  'pages/ResourcesPage.css': 4,
  'pages/SearchPage.css': 2,
  'pages/VaultPage.css': 9,
  'pages/WorkItemDetailPage.css': 5,
  'styles/base.css': 2,
}

/** Vyber deklarácie z bloku, ktorý nasleduje po `selector`. */
function parseBlock(css: string, selector: string): Record<string, string> {
  const start = css.indexOf(selector)
  expect(start, `v tokens.css nie je blok ${selector}`).toBeGreaterThan(-1)
  const open = css.indexOf('{', start)
  const close = css.indexOf('}', open)
  const body = css.slice(open + 1, close)
  const declarations: Record<string, string> = {}
  for (const rawLine of body.split('\n')) {
    const line = rawLine.replace(/\/\*.*?\*\//g, '').trim()
    if (line === '' || line.startsWith('*') || line.startsWith('/')) continue
    const separator = line.indexOf(':')
    if (separator === -1) continue
    const name = line.slice(0, separator).trim()
    const value = line.slice(separator + 1).replace(/;$/, '').trim()
    if (name.startsWith('--')) declarations[name] = value
  }
  return declarations
}

/** Všetky CSS súbory v `src/` okrem `tokens.css` (cesty relatívne k `src/`). */
function cssFilesOutsideTokens(): string[] {
  const acc: string[] = []
  const walk = (dir: string): void => {
    for (const entry of readdirSync(dir)) {
      const abs = join(dir, entry)
      if (statSync(abs).isDirectory()) {
        walk(abs)
      } else if (entry.endsWith('.css') && entry !== 'tokens.css') {
        acc.push(abs.slice(srcRoot.length + 1).split(/[\\/]/).join('/'))
      }
    }
  }
  walk(srcRoot)
  return acc.sort()
}


/**
 * WCAG 2.1 relatívny jas (reluminancia) a kontrastný pomer.
 * https://www.w3.org/TR/WCAG21/#dfn-relative-luminance
 *
 * Vlastná implementácia, nie knižnica — test je tým pádom nezávislý od
 * toho, čo si niekto nainštaluje, a vzorec sa dá prečítať v jednom oku.
 */
function relativeLuminance(hex: string): number {
  const clean = hex.replace('#', '')
  const [r, g, b] = [0, 2, 4].map((i) => {
    const v = parseInt(clean.slice(i, i + 2), 16) / 255
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

function contrastRatio(a: string, b: string): number {
  const la = relativeLuminance(a)
  const lb = relativeLuminance(b)
  const [hi, lo] = la > lb ? [la, lb] : [lb, la]
  return Math.round(((hi + 0.05) / (lo + 0.05)) * 100) / 100
}


describe('tokens.css — jediný zdroj farieb', () => {
  const tokens = read('styles/tokens.css')

  it('nexaverse blok je 1:1 kópia :root zo šablóny tm-603', () => {
    const nexaverse = parseBlock(tokens, "[data-theme='nexaverse']")
    const template: Record<string, string> = {
      '--primary': '#00f0ff',
      '--secondary': '#ff00d4',
      '--accent': '#9d4edd',
      '--dark-1': '#0a0a12',
      '--dark-2': '#12121f',
      '--dark-3': '#1a1a2e',
      '--glass-bg': 'rgba(255, 255, 255, 0.03)',
      '--glass-border': 'rgba(255, 255, 255, 0.08)',
      '--glow-cyan': 'rgba(0, 240, 255, 0.4)',
      '--glow-magenta': 'rgba(255, 0, 212, 0.4)',
    }
    for (const [name, value] of Object.entries(template)) {
      expect(nexaverse[name], `token ${name}`).toBe(value)
    }
  })

  it('definuje kanonické mená z tm-603 aj DERIVED aliasy', () => {
    const glassDark = parseBlock(tokens, "[data-theme='glass-dark']")
    const required = [
      '--primary', '--secondary', '--accent', '--dark-1', '--dark-2', '--dark-3',
      '--glass-bg', '--glass-border', '--glow-cyan', '--glow-magenta',
      '--bg-0', '--bg-grad-1', '--bg-grad-2', '--bg-grad-3',
      '--bg-mist-1', '--bg-mist-2', '--bg-mist-3', '--bg-mist-opacity',
      '--orb-opacity', '--grid-line', '--grid-opacity',
      '--surface-1', '--surface-2', '--surface-3', '--surface-sunken', '--surface-accent',
      '--glass-bg-hover', '--glass-bg-accent', '--glass-strong', '--glass-highlight', '--glass-hairline',
      '--text-1', '--text-2', '--text-muted', '--text-dim', '--text-inverse',
      '--primary-strong', '--primary-deep', '--secondary-strong',
    ]
    for (const name of required) {
      expect(glassDark, `glass-dark nedefinuje ${name}`).toHaveProperty(name)
    }
  })

  it('glass-dark je emerald + gold (deep forest), nie neon', () => {
    const glassDark = parseBlock(tokens, "[data-theme='glass-dark']")
    expect(glassDark['--primary']).toBe('#34d399')
    expect(glassDark['--secondary']).toBe('#d4a574')
    /* `--glow-cyan` je SLOT, nie farba: v glass téme je emerald. */
    expect(glassDark['--glow-cyan']).toContain('52, 211, 153')
    expect(glassDark['--color-scheme']).toBe('dark')
  })

  it('všetky 3 identity definujú ROVNAKÚ množinu tokenov', () => {
    const sets = {
      nexaverse: parseBlock(tokens, "[data-theme='nexaverse']"),
      'glass-dark': parseBlock(tokens, "[data-theme='glass-dark']"),
      'glass-light': parseBlock(tokens, "[data-theme='glass-light']"),
    }
    const reference = Object.keys(sets['glass-dark']).sort()
    expect(reference.length).toBeGreaterThan(60)
    for (const [name, block] of Object.entries(sets)) {
      expect(Object.keys(block).sort(), `téme ${name} chýba token`).toEqual(reference)
    }
  })

  it('poradie blokov je root/nexaverse → glass-dark → glass-light', () => {
    const at = (sel: string) => tokens.indexOf(sel)
    expect(at(":root,\n[data-theme='nexaverse']")).toBeGreaterThan(-1)
    expect(at(":root,\n[data-theme='nexaverse']")).toBeLessThan(at("[data-theme='glass-dark']"))
    expect(at("[data-theme='glass-dark']")).toBeLessThan(at("[data-theme='glass-light']"))
  })

  it('legacy selektory dark/light sú alias na glass identity', () => {
    expect(tokens).toMatch(/\[data-theme='glass-dark'\],\n\[data-theme='dark'\]/)
    expect(tokens).toMatch(/\[data-theme='glass-light'\],\n\[data-theme='light'\]/)
  })
  /* DIZ-4 item 9 (WIKI4AI-195, WCAG 1.4.3): Monaco má tri vlastné palety
     s vlastnými hex hodnotami — CSS ratchet ich nevidí, lebo nie sú v CSS.
     Čísla riadkov sú TEXT, nie dekorácia, takže na ne platí 4.5:1.
     Namerané pred opravou: glass-light 2.64:1, nexaverse 3.23:1,
     glass-dark 3.59:1. Test počíta kontrast priamo z hex v zdroji, aby
     sa to nedalo vrátiť bez toho, aby to niekto zahol. */
  it('Monaco čísla riadkov majú ≥ 4.5:1 na vlastnom pozadí (všetky 3 palety)', () => {
    const src = stripComments(read('components/MarkdownEditor.tsx'))
    const blocks = src.match(/'editor\.background': '#[0-9a-fA-F]{6}',[\s\S]*?'editor\.lineNumberForeground': '#[0-9a-fA-F]{6}',/g)
    if (!blocks) {
      throw new Error('Monaco palety sa nedajú nájsť — zmenilo sa formátovanie zdroja?')
    }
    expect(blocks, 'palety Monaco sa musia dať nájsť').toHaveLength(3)

    for (const block of blocks) {
      const bg = block.match(/'editor\.background': '(#[0-9a-fA-F]{6})'/)![1]
      const fg = block.match(/'editor\.lineNumberForeground': '(#[0-9a-fA-F]{6})'/)![1]
      const ratio = contrastRatio(fg, bg)
      expect(ratio, `číslo riadka ${fg} na ${bg} má len ${ratio}:1`).toBeGreaterThanOrEqual(4.5)
    }
  })

  /* DIZ-4 item 10 (WCAG 2.1.2 No Keyboard Trap): Monaco berie Tab. Únik
     existuje (editor.action.toggleTabFocusMode), ale je PRÍSLOVEČNÝ —
     2.1.2 vyžaduje, aby bol spôsob úniku známy, preto musí byť napísaný
     v UI. Test viaže text na obidva lokály, aby nezmizol pri prepínaní. */
  it('editor vypisuje únik z Monaco Tab capture (2.1.2)', () => {
    const editor = read('components/MarkdownEditor.tsx')
    expect(editor).toContain('editor-keyboard-hint')
    expect(editor).toContain('2.1.2')

    for (const locale of ['en', 'sk'] as const) {
      const json = JSON.parse(read(`locales/${locale}.json`))
      expect(json.editor?.tabHint, `${locale}: editor.tabHint chýba`).toBeTruthy()
      expect(json.editor.tabHint, `${locale}: tabHint musí interpolovať klávesu`).toContain('{{key}}')
      expect(json.editor?.tabHintWin, `${locale}: tabHintWin chýba`).toContain('Ctrl+M')
      expect(json.editor?.tabHintMac, `${locale}: tabHintMac chýba`).toBeTruthy()
    }
  })


})

describe('farby mimo tokens.css — ratchet', () => {
  const files = cssFilesOutsideTokens()

  it('žiadny súbor mimo tokens.css nemá viac farieb, než sľubuje budget', () => {
    const offenders: string[] = []
    for (const rel of files) {
      const found = countColors(read(rel))
      const allowed = HARD_COLOR_BUDGET[rel] ?? 0
      if (found > allowed) offenders.push(`${rel}: ${found} > ${allowed}`)
    }
    expect(offenders, `nové tvrdé farby: ${offenders.join('; ')}`).toEqual([])
  })

  /* WIKI4AI-194 (DIZ-3): predtým test blokoval ILE NARASTENIE. Migrácia
     `Tag.css` z 14 na 0 nechala budget na 14 a farby sa mohli vrátiť bez
     červeného testu. Migrácia preto musí znížiť budget v tom istom commite. */
  it('budget nesmie ostať voľnejší, než je nameraný stav (ratchet aj škrtí)', () => {
    const stale: string[] = []
    for (const rel of files) {
      const found = countColors(read(rel))
      const allowed = HARD_COLOR_BUDGET[rel] ?? 0
      if (found < allowed) stale.push(`${rel}: namerané ${found}, sľub ${allowed} → zníž budget na ${found}`)
    }
    expect(stale, `ratchet ticho zmrzol: ${stale.join('; ')}`).toEqual([])
  })

  it('budget neobsahuje súbor, ktorý už neexistuje (inak ratchet ticho zmrzne)', () => {
    const missing = Object.keys(HARD_COLOR_BUDGET).filter((rel) => !files.includes(rel))
    expect(missing, `v budgete sú súbory, ktoré neexistujú: ${missing.join(', ')}`).toEqual([])
  })

  it('pomenované farby počítame — inak `color: white` prejde ako žiadna farba', () => {
    expect(countColors('a { color: white }')).toBe(1)
    expect(countColors('a { color: var(--text-inverse) }')).toBe(0)
    /* `white-space` a `--glow-cyan` nie sú farby — bez lookbehind/lookahead
       by regex zaličil polovicu appky */
    expect(countColors('a { white-space: nowrap; box-shadow: 0 0 4px var(--glow-cyan) }')).toBe(0)
    expect(countColors('a { background: rgba(0, 0, 0, 0.5) }')).toBe(1)
  })
})

describe('DIZ-4 (WIKI4AI-195) — kontrastné podlahy a focus ring', () => {
  const tokens = read('styles/tokens.css')

  it('všetky 3 identity majú --border-input a --scrim s dostatočnou alfa', () => {
    for (const selector of ["[data-theme='nexaverse']", "[data-theme='glass-dark']", "[data-theme='glass-light']"]) {
      const block = parseBlock(tokens, selector)
      for (const name of ['--border-input', '--scrim']) {
        expect(block, `${selector} nedefinuje ${name}`).toHaveProperty(name)
      }
      /* --glass-border má na karte len 1.22–1.43:1 a výplň pola je takmer
         totožná s kartou — orámovanie je jediná hrana, preto musí mať
         alfa aspoň .40 (namerané 3.2–3.8:1, WCAG 1.4.11) */
      const alpha = Number(block['--border-input'].match(/,\s*([\d.]+)\)$/)?.[1] ?? 0)
      expect(alpha, `${selector}: --border-input alfa ${alpha} nedosiahne 3:1`).toBeGreaterThanOrEqual(0.4)
      const scrim = Number(block['--scrim'].match(/,\s*([\d.]+)\)$/)?.[1] ?? 0)
      expect(scrim, `${selector}: --scrim alfa ${scrim} zatemní pozadie málo`).toBeGreaterThanOrEqual(0.5)
    }
  })

  it('glass-light --text-dim je nad 4.5:1 (pôvodných .6 malo 4.31:1)', () => {
    const light = parseBlock(tokens, "[data-theme='glass-light']")
    const alpha = Number(light['--text-dim'].match(/,\s*([\d.]+)\)$/)?.[1] ?? 0)
    expect(alpha, 'glass-light --text-dim').toBeGreaterThanOrEqual(0.66)
  })

  /* Opus 4.2: focus sa nesmie riešiť ďalším glowom — `0 0 20px var(--glow-cyan)`
     nemá tvrdú hranu a 3:1 nesplní nikdy. Ratchet na tvrdú orámovaciu čiaru. */
  it('--focus-ring je pevná orámovacia čiara, nie glow halo', () => {
    const shared = tokens.slice(tokens.lastIndexOf(':root {'))
    expect(shared).toMatch(/--focus-ring: \d+px solid var\(--focus-ring-color\)/)
    expect(shared).toMatch(/--focus-ring-offset: \d+px/)
    expect(shared).not.toMatch(/--focus-ring:[^;]*blur|--focus-ring:[^;]*glow/)
  })

  /* `--on-accent` je biela vo všetkých 3 témach a komentár tvrdil, že je to
     bezpečné. Nie je: 1.92:1 na #34d399 a 1.41:1 na #00f0ff. Kým ho nikto
     nepoužíva, je to pasca — preto ratchet na jeho nepoužitie. */
  it('--on-accent má zdokumentovaný kontrastný strop a nikto ho nepoužíva', () => {
    expect(tokens).toMatch(/1\.41:1/)
    expect(tokens).toMatch(/1\.92:1/)
    const users = cssFilesOutsideTokens().filter((rel) => stripComments(read(rel)).includes('--on-accent'))
    expect(users, `--on-accent sa používa: ${users.join(', ')}`).toEqual([])
  })

  /* Globálny ring v `base.css` je `:where(...)`, teda so ZÁMERNE nulovou
     specificitou — akékoľvek `outline: none` triedy ho prerazí a 2.4.7
     zasa ticho zlyhá. Jediná povolená výnimka je Monaco inputarea. */
  it('base.css kreslí focus ring a nikto ho neruší outline: none', () => {
    const base = read('styles/base.css')
    expect(base).toMatch(/:where\([^)]*button[^)]*\):focus-visible\s*\{\s*\n\s*outline: var\(--focus-ring\)/)
    const OUTLINE_NONE = /^\s*outline:\s*none\s*;/m
    const ALLOWED = new Set(['components/MarkdownEditor.css'])
    const offenders = cssFilesOutsideTokens()
      .filter((rel) => !ALLOWED.has(rel))
      .filter((rel) => OUTLINE_NONE.test(stripComments(read(rel))))
    expect(offenders, `outline: none ruší globálny focus ring v: ${offenders.join(', ')}`).toEqual([])
  })
})

/* WIKI4AI-194 (DIZ-3 item 2). `DocumentEditor` a `AgentsPage` sú lazy
   chunky (`App.tsx:31,39`), takže ich CSS sa injectuje až PO hlavný
   bundle. Kým existovalo `.btn-primary` v stránkovom CSS, otvorením
   editora sa víťaz cascade zmenil pre celú appku — až po reload.
   Jediný spôsob, ako to udržať opravené, je nepovoliť ďalšiu kópiu. */
describe('primitíva — stránkové CSS nesmie duplicovať triedy systému', () => {
  const PRIMITIVE_RULE = /(^|\})\s*[^{}]*\.btn-(primary|secondary|ghost|danger)(?![-\w])[^{}]*\{/

  it('.btn varianty definuje iba styles/components.css', () => {
    const offenders = cssFilesOutsideTokens()
      .filter((rel) => rel !== 'styles/components.css')
      .filter((rel) => PRIMITIVE_RULE.test(stripComments(read(rel))))
    expect(
      offenders,
      `kópie primitíva .btn (víťaz by závisel od histórie prehliadania): ${offenders.join(', ')}`,
    ).toEqual([])
  })

  it('primitívum .btn existuje so všetkými 4 variantmi a textom --text-inverse', () => {
    const components = stripComments(read('styles/components.css'))
    for (const variant of ['.btn-primary', '.btn-secondary', '.btn-ghost', '.btn-danger']) {
      expect(components, `chýba variant ${variant}`).toMatch(new RegExp(`^\\${variant} \\{`, 'm'))
    }
    /* 1.4.3: text na plnej výplni MUSÍ byť --text-inverse. `--dark-1` má na
       --primary len 4.39:1 a `--on-accent` (biela) 1.41–1.92:1. */
    const primary = components.slice(components.indexOf('.btn-primary {'))
    expect(primary).toMatch(/color: var\(--text-inverse\)/)
    expect(primary.split('.btn-secondary')[0]).not.toMatch(/color: var\(--dark-1\)|color: var\(--on-accent\)/)
  })
  /* DIZ-3 item 6: `.form-input` bolo 3x globálne a vždy iné (padding
     14px/0.5rem/0.625rem, radius 12/8/8, --glass-strong vs --glass-bg).
     Rovnaká kolízia ako pri tlačidlách — víťaz závisel od histórie
     prehliadania. */
  it('.form-input definuje iba styles/components.css', () => {
    const RULE = /(^|\})\s*[^{}]*\.form-(?:input|textarea)(?![-\w])[^{}]*\{/
    const offenders = cssFilesOutsideTokens()
      .filter((rel) => rel !== 'styles/components.css')
      .filter((rel) => RULE.test(stripComments(read(rel))))
    expect(offenders, `kópie primitíva .form-input: ${offenders.join(', ')}`).toEqual([])
  })

  /* DIZ-3 item 6 + DIZ-4 item 9: data: URL chevronu bol jediný dôvod,
     prečo `components.css` musel mať tvrdú farbu. Nahradený gradientmi,
     preto sa sem žiadne `data:image` nesmie vrátiť. */
  it('components.css neobsahuje data: URL a má 0 tvrdých farieb', () => {
    const components = stripComments(read('styles/components.css'))
    expect(components).not.toContain('data:image')
    expect(countColors(read('styles/components.css')), 'components.css má tvrdú farbu').toBe(0)
  })

  /* DIZ-4 item 9: hranica poľa je na svetlom pozadí jediná hrana —
     `--glass-border` má 1.36:1, preto primitívum MUSÍ používať
     `--border-input` (3.43 / 3.80 / 3.82:1). */
  it('.form-input a .form-select majú hranicu z --border-input', () => {
    const components = read('styles/components.css')
    expect(components).toContain('.form-input,')
    expect(components).toContain('.form-select {')
    const inputs = components.slice(components.indexOf('.form-input,'))
    expect(inputs).toContain('border: 1px solid var(--border-input)')
    expect(components.slice(components.indexOf('.form-select {'))).toContain('border: 1px solid var(--border-input)')
  })


  /* DIZ-3 item 3: tri rôzne štýly hlavičky (AdminUsers veta-case na
     --glass-strong, ResourcesPage uppercase 0.72rem, MarkdownPreview
     rgba(0,0,0,.3) šedá plotňa). Hover riadku bol `--glow-cyan` — SLOT s
     alfou 0.4, ktorý riadok zaliel emeraldom. */
  it('.data-table definuje iba components.css a hover riadku nie je glow slot', () => {
    /* Layout pravidlo (margin-top) na samotnom <table> je v poriadku —
       zakázané je štylizovať BUNKY vlastnej tabuľky. */
    const RULE = /(^|\})\s*[^{}]*\.(?:admin-table|resource-table)(?![-\w])[^{}]*\b(?:th|td|thead|tbody|tr)\b[^{}]*\{/
    const offenders = cssFilesOutsideTokens()
      .filter((rel) => rel !== 'styles/components.css')
      .filter((rel) => RULE.test(stripComments(read(rel))))
    expect(offenders, `kópie primitíva .data-table: ${offenders.join(', ')}`).toEqual([])
    const dt = read('styles/components.css')
    const block = dt.slice(dt.indexOf('.data-table {'))
    expect(block).toContain('background-color: var(--glass-bg-hover)')
    expect(block.split('.form-input')[0]).not.toContain('var(--glow-cyan)')
  })


  /* DIZ-3 item 11: `.error` bolo 4× globálne s rôznym box modelom,
     `.error-message` 3× a `.empty-state` 2×. Návrat nejakej kópie by
     vrátil aj rozdielny box model medzi stránkami. */
  it('.error / .error-message / .empty-state už nesmú byť definované', () => {
    const RULE = /(^|\})\s*\.error-message(?![-\w])[^{}]*\{|(^|\})\s*\.error(?![-\w])[^{}]*\{|(^|\})\s*\.empty-state(?![-\w])[^{}]*\{/
    const offenders = cssFilesOutsideTokens().filter((rel) => RULE.test(stripComments(read(rel))))
    expect(offenders, `staré duplicáty stavov: ${offenders.join(', ')}`).toEqual([])
  })

  it('primitíva .state-empty a .state-error existujú a ladia s tokenmi', () => {
    const c = read('styles/components.css')
    expect(c).toMatch(/^\.state-empty \{$/m)
    expect(c).toMatch(/^\.state-error \{$/m)
    const empty = c.slice(c.indexOf('.state-empty {'))
    expect(empty).toContain('var(--glass-bg-accent)')
    const err = c.slice(c.indexOf('.state-error {'))
    expect(err).toContain('var(--color-error-bg)')
    expect(err).toContain('var(--color-error-border)')
  })


  /* DIZ-4 item 3 (WCAG 1.4.1 Use of Color): `--primary` je od textu len
     1.41:1 (light) / 1.76:1 (dark), takže farba sama nesmie byť jediným
     príznakom odkazu. Ratchet na trvalé podčiarknutie. */
  it('odkazy v Markdowne majú underline, nie len farbu', () => {
    const md = stripComments(read('components/MarkdownPreview.css'))
    const link = md.slice(md.indexOf('.markdown-preview a {'))
    const base = link.split('.markdown-preview a:hover')[0]
    expect(base, 'odkaz musí mať underline aj bez hoveru').toContain('text-decoration: underline')
    expect(base).toContain('text-decoration-color: var(--glow-cyan)')
    expect(base).toContain('text-underline-offset: 3px')
    expect(base).not.toMatch(/text-decoration:\s*none/)
  })

  it('Markdown preview nemá žiadnu tvrdú farbu (budget 3 → 0)', () => {
    expect(countColors(read('components/MarkdownPreview.css')), 'MarkdownPreview.css').toBe(0)
    const md = read('components/MarkdownPreview.css')
    for (const sel of ['.markdown-preview pre {', '.markdown-preview code {', '.markdown-preview thead {']) {
      const block = md.slice(md.indexOf(sel))
      expect(block, `${sel} používa čierny overlay`).not.toMatch(/rgba\(0, 0, 0/)
    }
    /* --glow-cyan je SLOT s alfa 0.4 — ako výplň blockquote zalia text */
    const bq = md.slice(md.indexOf('.markdown-preview blockquote {'))
    expect(bq).toContain('background: var(--glass-bg-accent)')
  })


  /* DIZ-3 item 5: `.modal-backdrop` (Dashboard, ProjectSettings) a
     `.modal-overlay` (AdminUsers) boli DVA rôzne shell-y toho istého
     modalu s rôznym paddingom (2rem vs 1.5rem), max-width (500 vs 420) a
     AdminUsers mal tvrdé `rgba(0, 0, 0, 0.6)`. Návrat kópie by vrátil
     dva rôzne modaly v jednej appke. */
  it('shell modalu definuje iba styles/components.css', () => {
    const RULE = /(^|\})\s*[^{}]*\.modal-(?:backdrop|overlay|content|close|title|text|actions)(?![-\w])[^{}]*\{/
    const offenders = cssFilesOutsideTokens()
      .filter((rel) => rel !== 'styles/components.css')
      .filter((rel) => RULE.test(stripComments(read(rel))))
    expect(offenders, `kópie shellu modalu: ${offenders.join(', ')}`).toEqual([])
  })

  /* DIZ-4 items 5, 6, 10: stmavenie dáva `::backdrop` z --scrim, nie
     background prvku — inak by sa scrim maloval dvakrát. Pravidlo musí
     byť viazané na `[open]`, inak prepíše UA `display: none` zatvoreného
     dialogu a modal bude viditeľný aj zamknutý. */
  it('modal používa native <dialog> vrstvy: [open] a ::backdrop so --scrim', () => {
    const c = read('styles/components.css')
    expect(c).toMatch(/^\.modal-backdrop\[open\],$/m)
    expect(c).toMatch(/^\.modal-backdrop::backdrop,$/m)
    const backdrop = c.slice(c.indexOf('.modal-backdrop::backdrop'))
    expect(backdrop).toContain('background: var(--scrim)')
    const shell = c.slice(c.indexOf('.modal-backdrop[open]'))
    expect(shell).toContain('background: transparent')
    const content = c.slice(c.indexOf('.modal-content {'))
    expect(content).toContain('var(--surface-3)')
    expect(content).toContain('var(--shadow-card-strong)')
    expect(content).toContain('var(--shadow-inset-hairline)')
  })

  /* DIZ-3 item 5: `@keyframes fadeIn` bol definovaný 2x v dvoch
     súboroch — keyframes sú globálne, takže jeden ticho prepísal druhý. */
  it('@keyframes fadeIn nie je duplikovaný', () => {
    const dupes = cssFilesOutsideTokens().filter((rel) =>
      /@keyframes\s+fadeIn\s*\{/.test(read(rel)))
    expect(dupes, `@keyframes fadeIn v: ${dupes.join(', ')}`).toEqual([])
  })


  /* DIZ-3 item 7: accent lištu toastu mali na starosti tri hex gradienty
     priamo v `Toast.tsx` — CSS ratchet ich nevidel, lebo boli v TSX. */
  it('Toast.tsx nemá tvrdú farbu v gradientoch', () => {
    const src = stripComments(read('components/Toast.tsx'))
    const gradients = src.slice(
      src.indexOf('const accentGradients'),
      src.indexOf('};', src.indexOf('const accentGradients')),
    )
    expect(gradients, `hex v gradientoch: ${gradients}`).not.toMatch(/#[0-9a-f]{3,6}/i)
    for (const tok of ['--color-success-text', '--color-error-text', '--color-info-text']) {
      expect(gradients, `chýba ${tok}`).toContain(`var(${tok})`)
    }
  })


  /* DIZ-3 item 8: `.tag` (components), `.role-badge` (Profile),
     `.role-badge` (AdminUsers — mŕtva kópia) a `.admin-role-badge`
     (AdminUsers) boli štyri implementácie toho istého štítku.
     `.role-admin` súťažilo s `.admin-role-badge.role-admin` o ten istý
     element a vyhrávalo len špecifiko. */
  it('štítok definuje styles/components.css a nemá jedinú tvrdú farbu', () => {
    const RULE = /(^|\})\s*[^{}]*\.(?:role-badge|admin-role-badge)(?![-\w])[^{}]*\{/
    const offenders = cssFilesOutsideTokens().filter((rel) => RULE.test(stripComments(read(rel))))
    expect(offenders, `kópie štítku: ${offenders.join(', ')}`).toEqual([])

    /* `components/Tag.css` musel zaniknúť: importoval ho iba `Tag.tsx`,
       ktorý nikto neimportoval, takže Vite stylesheet vôcebundloval a
       `.tag` prišiel na stránku neštýlovaný (overené live v prehliadači:
       background transparent, border 0px, border-radius 0px). */
    expect(existsPath(join(srcRoot, 'components/Tag.css')), 'Tag.css musí byť preč').toBe(false)
    expect(read('components/Tag.tsx')).not.toMatch(/import\s+['"]\.\/Tag\.css['"]/)

    const components = stripComments(read('styles/components.css'))
    const start = components.indexOf('.tag {')
    const end = components.indexOf('.tag-user {')
    expect(start, '.tag nie je v styles/components.css').toBeGreaterThan(-1)
    const section = components.slice(start, components.indexOf('}', end) + 1)
    expect(countColors(section), 'sekcia štítoku má tvrdú farbu').toBe(0)

    /* mŕtve fallbacky `var(--token, #hex)` klamú, že token môže chýbať */
    expect(section).not.toMatch(/var\(--[a-z-]+,\s*#/)
    /* hover na prvku s `cursor: default` je klamná afordancia */
    expect(section).not.toMatch(/\.tag:hover/)
    expect(section).toContain(".tag[role='button']:hover")
  })

  /* DIZ-3 item 6: `.data-table` bez sticky hlavičky a bez skleneného
     lesku nebol referenčný jazyk, len tabuľka. A bez nepriehľadného
     podkladu pod gradientom sa pod text hlavičky presvitajú riadky. */
  it('.data-table thead th je sticky s gradientom na nepriehľadnom podklade', () => {
    const components = stripComments(read('styles/components.css'))
    const rule = components.slice(
      components.indexOf('.data-table thead th {'),
      components.indexOf('}', components.indexOf('.data-table thead th {')) + 1,
    )
    expect(rule).toContain('position: sticky')
    expect(rule).toContain('top: 0')
    expect(rule).toContain('background-image: linear-gradient(180deg, var(--surface-2), var(--surface-1))')
    expect(rule).toContain('background-color: var(--surface-3)')
  })


})

describe('identities — komponentové CSS nepozná mená tém', () => {
  /* DIZ-1: komponentové CSS nesmie vetviť PODĽA NÁZVU identity. Pôvodne to
     boli tri selektory `[data-theme='nexaverse']` v AmbientBackground.css,
     ktoré ticho prepísali tokeny a identitu sa nedalo prepnúť späť.
     WIKI4AI-208 rozlišuje: bezhodnotový `html[data-theme]` (bez `=`) NIE JE
     vetvenie podľa mena — je to jeden selektor platný rovnako pre všetky tri
     identity. Presne ten vyžaduje reduced-motion blok, lebo potrebuje
     špecifickosť (0,1,1), aby porazil tematické bloky `[data-theme='x']`
     s (0,1,0); `:root` s (0,0,1) by neporazil. Test preto hľadá iba
     selektor S HODNOTOU — inak by zakázal vlastné riešenie, ktoré DIZ-1
     neporušuje. */
  it('žiadne CSS okrem tokens.css neselektuje [data-theme=…] s hodnotou', () => {
    const offenders = cssFilesOutsideTokens()
      .filter((rel) => /\[data-theme\s*=/.test(stripComments(read(rel))))
    expect(offenders, `[data-theme=…] v komponentovom CSS: ${offenders.join(', ')}`).toEqual([])
  })

  /* DIZ-1 + WIKI4AI-208: bezhodnotový tvar je povolený, ale na jedinom
     mieste. Roztrúsenie `html[data-theme]` po komponentoch by znamenal,
     že sa vracia vetvenie identitou len inou formou. */
  it('bezhodnotový `html[data-theme]` je len v base.css (reduced-motion)', () => {
    const users = cssFilesOutsideTokens()
      .filter((rel) => /html\[data-theme\]/.test(stripComments(read(rel))))
    expect(users, `html[data-theme] v: ${users.join(', ')}`).toEqual(['styles/base.css'])
  })
})

describe('FOUC bootstrap — jeden zdroj pravdy pre index.html', () => {
  const script = buildThemeBootstrapScript()
  const html = read('../index.html')

  it('generovaný skript pozná kľúč, všetky identity, legacy mapping aj default', () => {
    expect(script).toContain(`'${THEME_STORAGE_KEY}'`)
    for (const id of THEME_ORDER) expect(script, `chýba identita ${id}`).toContain(`'${id}'`)
    for (const [legacy, target] of Object.entries(LEGACY_THEMES)) {
      expect(script, `chýba legacy mapping ${legacy}`).toContain(`${legacy}:'${target}'`)
    }
    expect(script).toContain(`var FALLBACK = '${DEFAULT_THEME}'`)
  })

  it('index.html má placeholder a fallback data-theme = DEFAULT_THEME', () => {
    expect(html).toContain('<!--theme-bootstrap-->')
    expect(html).toMatch(new RegExp(`<html lang="[a-z]+" data-theme="${DEFAULT_THEME}">`))
    /* ručne prepísaný zoznam hodnôt je zdroj driftu — nesmie sa vrátiť */
    expect(html).not.toContain("var ALLOWED")
  })

  it('THEME_ORDER je zhodný s THEMES a default je prvá identita', () => {
    expect(THEME_ORDER).toEqual(THEMES)
    expect(THEME_ORDER[0]).toBe(DEFAULT_THEME)
  })
})

describe('globals.css — poradie vrstiev je súčasťou dizajnu', () => {
  it('importuje tokens → base → components → app a nič iné neobsahuje', () => {
    const globals = read('styles/globals.css')
    const order = ['tokens.css', 'base.css', 'components.css', 'app.css']
    const positions = order.map((name) => globals.indexOf(`./${name}`))
    positions.forEach((p, i) => expect(p, `chýba import ${order[i]}`).toBeGreaterThan(-1))
    expect(positions).toEqual([...positions].sort((a, b) => a - b))
    /* globals.css je len importovací manifest — pravidlá patria do vrstiev */
    expect(globals).not.toMatch(/\n\s*(html|body|\.[a-z-]+)\s*\{/)
  })
})

describe('DIZ-2 — ambientné vrstvy a povrchy kariet', () => {
  const ambient = stripComments(read('components/AmbientBackground.css'))
  const tokens = read('styles/tokens.css')

  it('AmbientBackground.css je bez tvrdých farieb a bez mien identít', () => {
    expect(ambient.match(HARD_COLOR) ?? []).toEqual([])
    expect(ambient).not.toContain('[data-theme')
  })

  it('kreslí 4 vrstvy a každú riadi token, nie konštanta', () => {
    for (const layer of ['.background {', '.background::before', '.ambient-orb--1', '.ambient-grid']) {
      expect(ambient, `chýba vrstva ${layer}`).toContain(layer)
    }
    for (const token of ['--bg-grad-1', '--bg-mist-1', '--bg-mist-opacity', '--orb-opacity', '--grid-line', '--grid-opacity']) {
      expect(ambient, `vrstva nepoužíva ${token}`).toContain(`var(${token})`)
    }
  })

  it('intenzity mlhy a orb-ov sú ladené (audit forum4Ai P2-9)', () => {
    for (const selector of ["[data-theme='nexaverse']", "[data-theme='glass-dark']", "[data-theme='glass-light']"]) {
      const block = parseBlock(tokens, selector)
      const mist = Number(block['--bg-mist-opacity'])
      const orb = Number(block['--orb-opacity'])
      expect(mist, `${selector}: --bg-mist-opacity`).toBeGreaterThan(0)
      expect(mist, `${selector}: mlha nad 0.60 zlieka susedné karty`).toBeLessThanOrEqual(0.6)
      expect(orb, `${selector}: --orb-opacity`).toBeGreaterThan(0)
      expect(orb, `${selector}: orb-y nad 0.60 zliekajú susedné karty`).toBeLessThanOrEqual(0.6)
    }
  })

  it('karta má vlas a tieň — odlíšenie nesmie závisieť len od farby', () => {
    const dashboard = stripComments(read('pages/Dashboard.css'))
    const card = dashboard.slice(dashboard.indexOf('.project-card {'))
    expect(card).toContain('var(--surface-1)')
    expect(card).toContain('var(--shadow-card)')
    expect(card).toContain('var(--shadow-inset-hairline)')
    /* hover podklad je výplň, nie glow slot — slot má alfu 0.4 a kartu zalia */
    expect(card).toContain('background-color: var(--glass-bg-hover)')
  })

  it('prefers-reduced-motion vypína ambientné animácie a platí app-wide', () => {
    expect(ambient).toMatch(/@media \(prefers-reduced-motion: reduce\)[\s\S]*\.ambient-orb \{[\s\S]*animation: none/)
    const base = read('styles/base.css')
    expect(base).toMatch(/@media \(prefers-reduced-motion: reduce\)/)
    /* spinner nesmie byť obeťou globálneho vypínača — nesie informáciu */
    const components = read('styles/components.css')
    expect(components).toMatch(/prefers-reduced-motion[\s\S]*\.loader-ring::before[\s\S]*animation:[^;]*!important/)
  })

  /* DIZ-4 item 11 (WIKI4AI-195, WCAG 2.3.3 Animation from Interactions):
     kill-switch v base.css je CSS — d3 zoom transition a force simulácia
     bežia v requestAnimationFrame a CSS ich nevypne. Graf by sa hýbal aj
     pri zapnutom prefers-reduced-motion. */
  it('prefers-reduced-motion vypína aj JS animácie grafu (2.3.3)', () => {
    const graph = stripComments(read('components/GraphView.tsx'))
    expect(graph, 'GraphView sa musí pýtať na prefers-reduced-motion').toContain(
      "'(prefers-reduced-motion: reduce)'",
    )
    /* zoomToFit(transitionDuration, padding) — overené v zdroji force-graph,
       prvý parameter je dĺžka animácie, preto musí byť podmienený. */
    expect(graph).toMatch(/zoomToFit\(\s*reducedMotion \? 0 : 400\s*,\s*60\s*\)/)
    expect(graph).toMatch(/warmupTicks=\{reducedMotion \? \d+ : 0\}/)
  })


})
