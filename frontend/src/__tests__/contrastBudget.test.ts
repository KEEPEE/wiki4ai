/**
 * contrastBudget.test.ts — kontrastný rozpočet WIKI4AI-208.
 *
 * Prečo existuje: WIKI4AI-208 odhalil, že ambientné vrstvy (gradient, mlhy,
 * orb-y) sa v DOM kreslili, no nepriehľadné `background-color` na <body> ich
 * zakrylo — `body` sa maľuje v kroku 3 paint orderu root stacking contextu,
 * `.background` so `z-index: -2` v kroku 2. Po oprave (base.css) sa mlha
 * skutočne zobrazí, a tým klesne kontrast textu, ktorý na nej sedí. Tento test
 * je jediné miesto, kde sa tá úloha dá udržať: bez neho môže ktokoľvek
 * „zintenzívniť pozadie" a ticho rozbiť WCAG 1.4.3.
 *
 * Dve vlastnosti, ktoré z neho robia dôkaz a nie komentár:
 *
 *  1. CITA SKUTOČNÉ HODNOTY. Žiadne pevné čísla v testi — tokeny sa parsujú
 *     regexom z `tokens.css` a `var()` odkazy sa RESOLVUJÚ, inak by
 *     `--focus-ring-color: var(--primary)` vyzeralo ako „žiadna farba" a test
 *     by tvrdil 1:1.
 *  2. MODEL JE KONZERVATÍVNY. Pozadie sa skladá z NAJSVETLEJŠIEHO gradientného
 *     stopu (pre tmavé identity najhorší prípad pre svetlý text) a mlhy/orbov
 *     pri ŠPIČKOVEJ alfe — najsvetlejší stop uprostred svojho radiálneho
 *     gradientu. Dve priehľadné vrstvy sa spájajú kompozitne,
 *     `1 − (1−a₁)(1−a₂)` (source-over), nie sčítaním. Text sa hodnotí v
 *     konečnej farbe po kompozícii, lebo WCAG meria kontrast finálnych pixelov.
 *
 * Antihack: test aj BRÁNÍ ústupu. Keby niekto „opravil" kontrast stiahnutím
 * mlhy na nulu, rozpočet by prešiel a zmizla by práve tá živá, ktorú ticket
 * prináša — preto asertuje aj dolnú hranicu intenzity.
 */
import { readFileSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'

const here = dirname(fileURLToPath(import.meta.url))
const tokens = readFileSync(resolve(here, '../styles/tokens.css'), 'utf8')

/* ===== Parsovanie tokenov ============================================== */

/** Deklarácie jedného bloku. Komentáre idú preč skôr, než sa začne čítať
 *  dvojica `name: value` — inak by sa text komentára mohol tváriť ako
 *  deklarácia (a naopak, prose o aliasoch by sa tváril ako selektor). */
function parseBlock(selector: string): Record<string, string> {
  const start = tokens.indexOf(selector)
  expect(start, `v tokens.css nie je blok ${selector}`).toBeGreaterThan(-1)
  const open = tokens.indexOf('{', start)
  const close = tokens.indexOf('}', open)
  const out: Record<string, string> = {}
  for (const raw of tokens.slice(open + 1, close).split('\n')) {
    const line = raw.replace(/\/\*.*?\*\//g, '').trim()
    const sep = line.indexOf(':')
    if (sep === -1) continue
    const name = line.slice(0, sep).trim()
    if (!name.startsWith('--')) continue
    out[name] = line.slice(sep + 1).replace(/;$/, '').trim()
  }
  return out
}

/** Rozbalí `var(--x)` rekurzívne. Bez toho by aliasy
 *  (`--focus-ring-color: var(--primary)`) boli nevyhodnotiteľné. */
function valueOf(block: Record<string, string>, name: string, depth = 0): string | undefined {
  const v = block[name]
  if (v === undefined) return undefined
  if (depth > 8) return v
  return v.replace(/var\(\s*(--[a-z0-9-]+)\s*\)/gi, (_, ref: string) => valueOf(block, ref, depth + 1) ?? '')
}

type RGBA = [number, number, number, number]

function parseColor(value: string | undefined): RGBA | null {
  if (!value) return null
  const v = value.trim()
  const hex6 = /^#([0-9a-fA-F]{6})$/.exec(v)
  if (hex6) {
    const h = hex6[1]
    return [parseInt(h.slice(0, 2), 16), parseInt(h.slice(2, 4), 16), parseInt(h.slice(4, 6), 16), 1]
  }
  const fn = /^rgba?\(\s*([\d.]+)\s*,\s*([\d.]+)\s*,\s*([\d.]+)\s*(?:,\s*([\d.]+)\s*)?\)$/.exec(v)
  if (fn) return [+fn[1], +fn[2], +fn[3], fn[4] === undefined ? 1 : +fn[4]]
  return null
}

const colorOf = (block: Record<string, string>, name: string): RGBA => {
  const raw = valueOf(block, name)
  const c = parseColor(raw)
  expect(c, `token ${name} sa nedá prečítať ako farba: ${raw}`).not.toBeNull()
  return c as RGBA
}

/* ===== Vrstvy a WCAG ==================================================== */

/** source-over: priehľadné popredie cez nepriehľadné pozadie. */
function over(fg: RGBA, bg: RGBA): RGBA {
  const a = fg[3]
  if (a >= 1) return [fg[0], fg[1], fg[2], 1]
  return [fg[0] * a + bg[0] * (1 - a), fg[1] * a + bg[1] * (1 - a), fg[2] * a + bg[2] * (1 - a), 1]
}

/** Lineárna interpolácia dvoch nepriehľadných farieb — presne to, čo robí
 *  CSS gradient v sRGB. Luminancia je pozdĺž priamky lineárna, takže
 *  najhorší kontrast je vždy na jednom zo stopov; prostredok je poistka. */
function mix(a: RGBA, b: RGBA, t: number): RGBA {
  return [0, 1, 2].map((i) => a[i] + (b[i] - a[i]) * t).concat([1]) as unknown as RGBA
}

/** Dve priehľadné vrstvy nad sebou. Sčítať alfy by bolo optimistické —
 *  dalo by im väčšiu kryciu schopnosť, než skutočne majú. */
function combineAlpha(a1: number, a2: number): number {
  return 1 - (1 - a1) * (1 - a2)
}

function luminance(c: RGBA): number {
  const [r, g, b] = [c[0], c[1], c[2]].map((v) => {
    const s = v / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** WCAG 2.1 kontrastný pomer — https://www.w3.org/TR/WCAG21/#contrast-minimum */
function contrast(a: RGBA, b: RGBA): number {
  const la = luminance(a)
  const lb = luminance(b)
  const [hi, lo] = la > lb ? [la, lb] : [lb, la]
  return Math.round(((hi + 0.05) / (lo + 0.05)) * 100) / 100
}

/** Kontrast tokenu ako textu na pozadí. Token môže mať alfu, preto sa
 *  najprv skomponuje s pozadím a až potom meria. */
function textContrast(block: Record<string, string>, name: string, bg: RGBA): number {
  return contrast(over(colorOf(block, name), bg), bg)
}

/** `color-mix(in srgb, var(--X) N%, transparent)` nad pozadím. Test nesmie
 *  tvrdiť hex — musí vyhodnotiť to isté, čo vyhodnotí prehliadač, inak by
 *  odvodzovanie od trojice bolo nenapadnuteľné. */
function mixOver(block: Record<string, string>, token: string, bg: RGBA): RGBA {
  const raw = block[token] ?? ''
  const m = /color-mix\(\s*in srgb,\s*var\((--[\w-]+)\)\s+(\d+(?:\.\d+)?)\s*%,\s*transparent\s*\)/i.exec(raw)
  if (!m) return colorOf(block, token)
  return mix(colorOf(block, m[1]), bg, 1 - Number(m[2]) / 100)
}

const MIST_STOPS = ['--bg-mist-1', '--bg-mist-2', '--bg-mist-3'] as const
const GRAD_STOPS = ['--bg-grad-1', '--bg-grad-2', '--bg-grad-3'] as const

/** Najhorší možný list, na ktorom môže sedieť text: najsvetlejší gradientný
 *  stop + mlha aj orb-y naraz na svojom špičkovom bode. */
function worstCasePage(block: Record<string, string>): { page: RGBA; peak: number } {
  const gradients = GRAD_STOPS.map((n) => colorOf(block, n))
  const base = gradients.reduce((a, b) => (luminance(a) >= luminance(b) ? a : b))

  const mists = MIST_STOPS.map((n) => colorOf(block, n))
  const brightest = mists.reduce((a, b) =>
    luminance([b[0], b[1], b[2], 1]) >= luminance([a[0], a[1], a[2], 1]) ? b : a,
  )

  const mistOpacity = Number(valueOf(block, '--bg-mist-opacity'))
  const orbOpacity = Number(valueOf(block, '--orb-opacity'))
  expect(Number.isFinite(mistOpacity), '--bg-mist-opacity nie je číslo').toBe(true)
  expect(Number.isFinite(orbOpacity), '--orb-opacity nie je číslo').toBe(true)

  const peak = combineAlpha(brightest[3] * mistOpacity, brightest[3] * orbOpacity)
  return { page: over([brightest[0], brightest[1], brightest[2], peak], base), peak }
}

/* ===== Identity ========================================================= */

const IDENTITIES = {
  nexaverse: "[data-theme='nexaverse']",
  'glass-dark': "[data-theme='glass-dark']",
  'glass-light': "[data-theme='glass-light']",
} as const

type Identity = keyof typeof IDENTITIES

const BLOCKS: Record<Identity, Record<string, string>> = {
  nexaverse: parseBlock(IDENTITIES.nexaverse),
  'glass-dark': parseBlock(IDENTITIES['glass-dark']),
  'glass-light': parseBlock(IDENTITIES['glass-light']),
}

/** Text, ktorý MUSÍ držať 4,5:1 (WCAG 1.4.3, bežná veľkosť). */
const BODY_TEXT = ['--text-1', '--text-2', '--text-muted', '--text-dim'] as const
/** Akcentový text: odkazy, aktívne stavy, sémantické stavové farby. */
const ACCENT_TEXT = [
  '--primary',
  '--ok',
  '--info',
  '--warn',
  '--error',
  '--color-success-text',
  '--color-error-text',
  '--color-warning-text',
  '--color-info-text',
] as const

describe.each(Object.keys(BLOCKS) as Identity[])('contrastBudget — %s', (identity) => {
  const block = BLOCKS[identity]
  const { page, peak } = worstCasePage(block)
  const card = over(colorOf(block, '--surface-1'), page)
  const cardRaised = over(colorOf(block, '--surface-2'), page)

  it('pozadie má pri špičkovej alfa dostatok hlavy pre bežný aj tlmený text', () => {
    const offenders: string[] = []
    for (const name of BODY_TEXT) {
      for (const [label, bg] of [['page', page], ['card', card], ['card-raised', cardRaised]] as const) {
        const ratio = textContrast(block, name, bg)
        if (ratio < 4.5) offenders.push(`${name}@${label} ${ratio}:1`)
      }
    }
    expect(offenders, `pod 4,5:1 pri peak alfe ${peak.toFixed(3)}: ${offenders.join('; ')}`).toEqual([])
  })

  it('akcentový text (odkazy, stavy) drží 4,5:1 na karte', () => {
    const offenders: string[] = []
    for (const name of ACCENT_TEXT) {
      if (valueOf(block, name) === undefined) continue
      for (const [label, bg] of [['card', card], ['card-raised', cardRaised]] as const) {
        const ratio = textContrast(block, name, bg)
        if (ratio < 4.5) offenders.push(`${name}@${label} ${ratio}:1`)
      }
    }
    expect(offenders, `akcentový text pod 4,5:1: ${offenders.join('; ')}`).toEqual([])
  })

  it('sémantický text na VLASTNOM odtieni (badge, toast, alert) drží 4,5:1', () => {
    /* WIKI4AI-208: `--color-*-bg`/`-border` sa už nekladú ako hotové rgba,
       ale odvádzajú od trojice `--ok/--info/--warn/--error` cez `color-mix`.
       Posun odtieňa je malý, no práve badge a toast kreslia text NA tom
       odtieni — preto sa meria tento pár, nie len token oproti karte. */
    const pairs = [
      ['--color-success-text', '--color-success-bg'],
      ['--color-error-text', '--color-error-bg'],
      ['--color-warning-text', '--color-warning-bg'],
      ['--color-info-text', '--color-info-bg'],
    ] as const
    const offenders: string[] = []
    for (const [text, bgToken] of pairs) {
      const bg = mixOver(block, bgToken, card)
      const ratio = textContrast(block, text, bg)
      if (ratio < 4.5) offenders.push(`${text} na ${bgToken} ${ratio}:1`)
    }
    expect(offenders, `text na sémantickom odtieni pod 4,5:1: ${offenders.join('; ')}`).toEqual([])
  })

  it('--color-*-bg/-border sa skutočne odvádzajú od trojice (nie sú to mŕtve tokeny)', () => {
    const map = [
      ['--ok', ['--color-success-bg', '--color-success-border']],
      ['--info', ['--color-info-bg', '--color-info-border']],
      ['--warn', ['--color-warning-bg', '--color-warning-border']],
      ['--error', ['--color-error-bg', '--color-error-border']],
    ] as const
    for (const [base, derived] of map) {
      for (const name of derived) {
        const raw = block[name] ?? ''
        expect(raw, `${identity}: ${name} neodvodzuje od ${base}`).toContain(`var(${base})`)
        expect(raw, `${identity}: ${name} má znova tvrdé číslo`).not.toMatch(/#[0-9a-fA-F]{3,8}|\brgba?\(/)
      }
    }
  })

  it('primárna akcia a aktívny pill: atrament na SKUTOČNOM gradiente ≥ 4,5:1', () => {
    /* WIKI4AI-206 merané pred opravou:
         .vault-primary-button      3,33:1 (glass-light, stop --accent #c2603f)
                                    4,29:1 (nexaverse, stop --accent #9d4edd)
                                    4,39:1 (glass-light, už na prvom stop-e)
         .btn-create-new            4,39:1 (glass-light, --dark-1 #e2e8db
                                    na --primary #047857)
         .tab.active                3,83:1 (glass-dark) / 3,82:1 (glass-light)
         .cal-view-option-active    3,83:1 / 3,82:1
       Test NEpredpokladá mená stopov — číta ich priamo z `--action-gradient`.
       Kto vráti `--accent`, padne sám od seba na 3,33:1. Luminancia je
       pozdĺž sRGB priamky lineárna, takže najhorší bod je na stop-e;
       prostredok je len poistka proti inému typu gradientu. */
    /* Mená stopov sa čítajú z NErozbaleného tokenu — `valueOf` by z neho
       urobil hex reťazec a stop-y by boli neidentifikovateľné. */
    const raw = block['--action-gradient'] ?? ''
    const stops = [...raw.matchAll(/var\((--[\w-]+)\)/gi)].map((m) => m[1])
    expect(stops.length, `--action-gradient neočakáva 2 stop-y: ${raw}`).toBe(2)

    const ink = colorOf(block, '--text-inverse')
    const fills = stops.map((s) => colorOf(block, s))
    const ratios = fills.map((f) => contrast(over(ink, f), f))
    const mid = mix(fills[0], fills[1], 0.5)
    ratios.push(contrast(over(ink, mid), mid))

    const worst = Math.min(...ratios)
    expect(
      worst,
      `${identity}: atrament na ${stops.join(' → ')} má len ${worst}:1 (${ratios.join(' / ')})`,
    ).toBeGreaterThanOrEqual(4.5)

    /* Pill a navigácia musia používať TEN ISTÝ recept — inak by sa jedna
       plocha ticho vrátila pod 4,5:1. */
    for (const name of ['--pill-active-bg', '--nav-active-bg'] as const) {
      expect(valueOf(block, name), `${identity}: ${name} nie je akčný gradient`)
        .toBe(valueOf(block, '--action-gradient'))
    }
    for (const name of ['--pill-active-color', '--nav-active-color'] as const) {
      expect(valueOf(block, name), `${identity}: ${name} nie je atrament`)
        .toBe(valueOf(block, '--text-inverse'))
    }
  })

  it('focus kruh a hrana pola dosahujú 3:1 (WCAG 1.4.11)', () => {
    const ringRatio = contrast(over(colorOf(block, '--focus-ring-color'), card), card)
    expect(ringRatio, `focus ring oproti karte má len ${ringRatio}:1`).toBeGreaterThanOrEqual(3)

    const edgeRatio = contrast(over(colorOf(block, '--border-input'), card), card)
    expect(edgeRatio, `--border-input oproti karte má len ${edgeRatio}:1`).toBeGreaterThanOrEqual(3)
  })

  it('intenzitu si nikto nezbaví, aby test prešiel zadarmo (anti-regresia)', () => {
    /* Bez tohto asertu by sa dal kontrast „opraviť" vypnutím mlhy a ticket by
       ticho zmizol. Rozpočet preto viaže oboje naraz. */
    const mist = Number(valueOf(block, '--bg-mist-opacity'))
    const orb = Number(valueOf(block, '--orb-opacity'))
    const vignette = Number(valueOf(block, '--vignette-opacity'))
    expect(mist, `${identity}: --bg-mist-opacity`).toBeGreaterThanOrEqual(0.3)
    expect(orb, `${identity}: --orb-opacity`).toBeGreaterThanOrEqual(0.25)
    expect(vignette, `${identity}: --vignette-opacity`).toBeGreaterThan(0)
    /* povrch musí ostať priesvitný — inak nie je čo blurovať a sklo zmizne,
       hoci by kontrast bol excelentný */
    expect(colorOf(block, '--surface-1')[3], `${identity}: --surface-1 je nepriehľadná`).toBeLessThan(1)
  })

  it('sémantická zelená nie je značka (--ok sa líši od --primary)', () => {
    /* Opus 5.8: ak je `--ok` totožný so zelenou značky, „úspech" neznamená
       nič — každá emerald plocha by ho tvárila. Porovnáva sa RESOLVOVANÁ
       hodnota, inak by alias `--ok: var(--primary)` prešiel ako „iný". */
    const ok = valueOf(block, '--ok')
    const primary = valueOf(block, '--primary')
    expect(ok, `${identity}: chýba --ok`).toBeTruthy()
    expect((ok as string).toLowerCase(), `${identity}: --ok je kópia --primary (${primary})`).not.toBe(
      (primary as string).toLowerCase(),
    )
  })
})

describe('contrastBudget — legacy identity', () => {
  it('dark → glass-dark a light → glass-light sú alias, takže dedija ten istý rozpočet', () => {
    expect(tokens).toMatch(/\[data-theme='glass-dark'\],\n\[data-theme='dark'\]/)
    expect(tokens).toMatch(/\[data-theme='glass-light'\],\n\[data-theme='light'\]/)
    /* Kód, nie komentár: hlavička súboru o aliasoch PÍŠE, takže regex cez celý
       súbor by našiel prose. Správna otázka nie je „kde sa to vyskytuje", ale
       „kreslí sa z toho samostatný blok": alias je VŽDY posledný člen zoznamu
       selektorov, teda pred ním je čiarka. Samostatný blok by znamenal, že
       legacy identita má vlastné hodnoty a rozpočet by sa jej netýkal. */
    const code = tokens.replace(/\/\*[\s\S]*?\*\//g, ' ')
    for (const legacy of ['dark', 'light'] as const) {
      const sel = `[data-theme='${legacy}']`
      const aliasedOnly = new RegExp(`,\\s*${sel.replace(/[[\]]/g, '\\$&')}`)
      const total = code.split(sel).length - 1
      const aliased = code.split(aliasedOnly).length - 1
      expect(total, `${sel}: v kóde nie je žiadny alias`).toBeGreaterThan(0)
      expect(aliased, `${sel}: ${total} výskytov, len ${aliased} je alias v zozname selektorov`).toBe(total)
    }
  })
})

describe('contrastBudget — tokeny, ktoré rozpočet predpokladá', () => {
  it('každá identita má sémantické triády --ok/info/warn/error + -soft + -glow', () => {
    for (const [identity, block] of Object.entries(BLOCKS)) {
      for (const base of ['--ok', '--info', '--warn', '--error']) {
        for (const suffix of ['', '-soft', '-glow']) {
          expect(block, `${identity}: chýba ${base}${suffix}`).toHaveProperty(`${base}${suffix}`)
        }
      }
    }
  })

  it('-soft a -glow sa odvádzajú od triády, nie od nového čísla (ratchet 0 farieb)', () => {
    for (const [identity, block] of Object.entries(BLOCKS)) {
      for (const base of ['--ok', '--info', '--warn', '--error']) {
        for (const suffix of ['-soft', '-glow'] as const) {
          const value = block[`${base}${suffix}`]
          expect(value, `${identity}: ${base}${suffix}`).toContain(`var(${base})`)
          expect(value, `${identity}: ${base}${suffix} má tvrdé číslo`).not.toMatch(/#[0-9a-fA-F]{3,8}|\brgba?\(/)
        }
      }
    }
  })

  it('každá identita má chart paletu --chart-0..8', () => {
    for (const [identity, block] of Object.entries(BLOCKS)) {
      for (let i = 0; i <= 8; i++) {
        expect(block, `${identity}: chýba --chart-${i}`).toHaveProperty(`--chart-${i}`)
        expect(parseColor(block[`--chart-${i}`]), `${identity}: --chart-${i} nie je farba`).not.toBeNull()
      }
    }
  })

  it('motion tokeny existujú a --transition-* sa skladá z --dur-* / --ease-*', () => {
    /* `:root {` sa v kóde vyskytuje práve raz — bloky identít majú selektor
       `:root,\n[data-theme='…']`, takže prvý výskyt je zdieľaný blok. Parsovať
       ho musíme ako blok, nie ako reťazec. */
    const shared = parseBlock(':root {')
    for (const name of ['--dur-fast', '--dur-med', '--dur-slow', '--ease-pop', '--ease-soft']) {
      expect(shared, `chýba ${name}`).toHaveProperty(name)
    }
    for (const name of ['--transition-fast', '--transition-base', '--transition-slow']) {
      expect(shared[name], `${name} sa neskladá z dur tokenu`).toMatch(/var\(--dur-/)
      expect(shared[name], `${name} nemá ease token`).toMatch(/var\(--ease-/)
    }
  })

  it('tichá zóna má vlastné nepriehľadné pozadie (--prose-bg, --diagram-bg)', () => {
    for (const [identity, block] of Object.entries(BLOCKS)) {
      for (const name of ['--prose-bg', '--diagram-bg']) {
        const c = parseColor(valueOf(block, name))
        expect(c, `${identity}: chýba ${name}`).not.toBeNull()
        expect((c as RGBA)[3], `${identity}: ${name} musí byť plne nepriehľadné`).toBe(1)
      }
    }
  })
})

describe('contrastBudget — prefers-reduced-motion (Opus 7)', () => {
  const base = readFileSync(join(here, '../styles/base.css'), 'utf8')
  const block = /@media \(prefers-reduced-motion: reduce\)[\s\S]*?\n\}/.exec(base)

  it('nulové trvania stoja na html[data-theme], nie na :root', () => {
    /* Bloky tém majú špecifickosť (0,1,0) a `:root` tiež — kto vyhrá, rozhoduje
       iba poradie v súbore, a témy sú v `tokens.css` neskoršie, takže by `:root`
       v tomto bloku prehral a trvania by ostala nenulová. `html[data-theme]`
       má (0,1,1) a vyhráva vždy. */
    expect(block, 'v base.css nie je reduced-motion blok').not.toBeNull()
    expect(block?.[0]).toMatch(/html\[data-theme\]\s*\{[^}]*--dur-fast:\s*0ms/)
    expect(block?.[0]).not.toMatch(/(^|[^-\w]):root\s*\{[^}]*--dur-fast/)
  })

  it('animácie sa spomalia na .01ms, nie vypnú na none', () => {
    /* `animation: none` by `fadeIn`/`pop-in` zastavil v počiatočnom stave,
       teda na `opacity: 0` — prvok by bol neviditeľný. `.01ms` animáciu nechá
       ihneď dokončiť do konečného stavu. */
    expect(block, 'v base.css nie je reduced-motion blok').not.toBeNull()
    expect(block?.[0]).toMatch(/animation-duration:\s*\.?0?\.01ms\s*!important/)
    expect(block?.[0]).toMatch(/transition-duration:\s*\.?0?\.01ms\s*!important/)
    expect(block?.[0]).not.toMatch(/animation:\s*none\s*!important/)
  })
})

describe('contrastBudget — WIKI4AI-206: kde sa kontrast meria, tam aj kreslí', () => {
  /* Tokeny samy kontrast nedržia — držia ho pravidlá, ktoré ich použijú.
     Tento blok číta SKUTOČNÉ CSS komponentov a kontroluje, že každé miesto,
     ktoré WIKI4AI-206 hlásil, (a) nepoužíva `--glow-cyan` ako VÝPLŇ a
     (b) sia po gradientovom tokene + atramente.
     `--glow-cyan` je SLOT s alfa 0.4 (glass-dark) / 0.25 (glass-light) —
     ako výplň zalia text; v repozitári to priznáva vlastný komentár pri
     blockquote v `MarkdownPreview.css` („ako výplň blockquote zalia text"),
     a `.tab.active` to napriek tomu robil s komentárom „cyan at 8% opacity". */
  const src = (rel: string): string =>
    readFileSync(join(here, '..', rel), 'utf8').replace(/\/\*[\s\S]*?\*\//g, ' ')

  function rule(rel: string, selector: string): string {
    const css = src(rel)
    const at = css.indexOf(selector)
    expect(at, `${rel}: nenašiel som selektor ${selector}`).toBeGreaterThan(-1)
    const open = css.indexOf('{', at)
    return css.slice(open + 1, css.indexOf('}', open))
  }

  const SITES: Array<[string, string, string]> = [
    ['styles/components.css', '.btn-primary {', '--action-gradient'],
    ['components/SidebarNav.css', '.nav-link-active {', '--nav-active-bg'],
    ['pages/ProjectDetail.css', '.tab.active {', '--pill-active-bg'],
    ['pages/ProjectDetail.css', '.tab[href].active {', '--pill-active-bg'],
    ['pages/CalendarPage.css', '.cal-view-option-active {', '--pill-active-bg'],
    ['pages/Dashboard.css', '.btn-create-new {', '--action-gradient'],
    ['pages/VaultPage.css', '.btn-create-new {', '--action-gradient'],
    ['pages/VaultPage.css', '.vault-primary-button {', '--action-gradient'],
  ]

  it.each(SITES)('%s · %s kreslí gradient a atrament, nie --glow-cyan', (rel, sel, token) => {
    const body = rule(rel, sel)
    expect(body, `${rel}${sel} nepoužíva ${token}`).toContain(`var(${token})`)
    expect(body, `${rel}${sel} nemá atrament`).toMatch(
      /color:\s*var\(--(?:text-inverse|pill-active-color|nav-active-color)\)/,
    )
    expect(body, `${rel}${sel} má --glow-cyan ako výplň`).not.toMatch(/background[^;]*var\(--glow-cyan\)/)
    expect(body, `${rel}${sel} má tvrdú farbu`).not.toMatch(/#[0-9a-fA-F]{3,8}\b|\brgba?\(/)
  })

  it('žiadne z miest WIKI4AI-206 nepoužíva --dark-1 ako atrament', () => {
    /* `--dark-1` je TMAVÁ ŠKÁLA identity, nie ink. V glass-light je
       #e2e8db — takmer biela — a na `--primary` #047857 to bolo 4,39:1,
       tesne pod 4,5. Atrament je `--text-inverse` (5,25:1). */
    const offenders = SITES.filter(([rel, sel]) => /color:\s*var\(--dark-1\)/.test(rule(rel, sel)))
    expect(offenders.map(([rel, sel]) => `${rel}${sel}`).join(', ')).toBe('')
  })

  it('hover aktívnej položky nestratí gradient (background shorthand)', () => {
    /* `.nav-link:hover` a `.cal-view-option:hover:not(:disabled)` majú
       shorthand `background:`, ktorý RUŠÍ `background-image`. Bez explicitného
       hover-pravidla pre aktívnu položku by sa gradient pri prejdení myšou
       stratil — na to sa pri statickej kontrole tokenov prísť nedá. */
    const nav = src('components/SidebarNav.css')
    expect(nav).toMatch(/\.nav-link-active:hover\s*\{[^}]*background-image:\s*var\(--nav-active-bg\)/)
    const cal = src('pages/CalendarPage.css')
    expect(cal).toMatch(
      /\.cal-view-option-active:hover:not\(:disabled\)\s*\{[^}]*background-image:\s*var\(--pill-active-bg\)/,
    )
  })
})

describe('contrastBudget — tichá zóna (data-mood)', () => {
  /* `data-mood='quiet'` je druhá os: znižuje intenzitu ambientu na
     čítačke, editore a hľadaní. Musí (a) platiť pre VŠETKY identity aj
     legacy aliasy, (b) skutočne znižovať hodnoty identity, nie len
     existovať, a (c) nesmie byť to jediné, na čom kontrast stojí —
     identita musí zvládať 4,5:1 aj sama o sebe (testy vyššie). */
  const code = tokens.replace(/\/\*[\s\S]*?\*\//g, ' ')

  function quietBlock(selector: string): Record<string, string> {
    const at = code.indexOf(selector)
    expect(at, `v tokens.css nie je blok ${selector}`).toBeGreaterThan(-1)
    const open = code.indexOf('{', at)
    const body = code.slice(open + 1, code.indexOf('}', open))
    const out: Record<string, string> = {}
    for (const line of body.split('\n')) {
      const i = line.indexOf(':')
      if (i === -1) continue
      const name = line.slice(0, i).trim()
      if (name.startsWith('--')) out[name] = line.slice(i + 1).trim().replace(/;$/, '')
    }
    return out
  }

  const QUIET_SELECTORS: Array<[string, Identity]> = [
    ["[data-theme='glass-dark'][data-mood='quiet']", 'glass-dark'],
    ["[data-theme='glass-light'][data-mood='quiet']", 'glass-light'],
    ["[data-theme='nexaverse'][data-mood='quiet']", 'nexaverse'],
  ]

  it.each(QUIET_SELECTORS)('%s tlmí mlhu aj orby oproti hodnote identity', (selector, identity) => {
    const quiet = quietBlock(selector)
    const base = BLOCKS[identity]
    for (const name of ['--bg-mist-opacity', '--orb-opacity'] as const) {
      const q = Number(quiet[name])
      const b = Number(valueOf(base, name))
      expect(q, `${identity}: ${name} v tichom režime chýba`).toBeGreaterThan(0)
      expect(q, `${identity}: ${name} v tichom režime neklesá (${q} vs ${b})`).toBeLessThan(b)
    }
    const v = Number(quiet['--vignette-opacity'])
    expect(v, `${identity}: --vignette-opacity v tichom režime neklesá`).toBeGreaterThan(
      Number(valueOf(base, '--vignette-opacity')),
    )
  })

  it('tichá zóna platí aj pre legacy dark/light — inak by alias dostal plnú mlhu', () => {
    for (const legacy of ['dark', 'light'] as const) {
      expect(code, `alias ${legacy} nemá tichú zónu`).toContain(
        `[data-theme='${legacy}'][data-mood='quiet']`,
      )
    }
  })

  it('Layout nastavuje aj upratuje data-mood', () => {
    const src = readFileSync(join(here, '../components/Layout.tsx'), 'utf8')
    expect(src).toMatch(/setAttribute\('data-mood',\s*'quiet'\)/)
    expect(src).toMatch(/removeAttribute\('data-mood'\)/)
    /* Úklid v return-e efektu: Layout sa odmontáva pri odhlásení a atribút
       by zostal na `<html>` aj na prihlasovacej obrazovke. */
    expect(src).toMatch(/return\s*\(\)\s*=>\s*root\.removeAttribute\('data-mood'\)/)
    expect(src).toMatch(/\/\(view\|edit\)\)\?\$/)
  })

  it('proza aj diagramy majú nepriehľadné pozadie a žiadny blur', () => {
    /* DON'T zo zadania: priesvitný Mermaid/kód nad mlhou. `--glass-bg`
       má alfu .03–.07, takže predtým stáli priamo na mlhe. Pod
       nepriehľadnou vrstvou nemá `backdrop-filter` čo rozmazať. */
    const cases: Array<[string, string, string]> = [
      ['components/MarkdownPreview.css', '.markdown-preview {', '--prose-bg'],
      ['components/MermaidDiagram.css', '.mermaid-diagram {', '--diagram-bg'],
      ['components/PlantUmlDiagram.css', '.plantuml-diagram {', '--diagram-bg'],
    ]
    for (const [rel, sel, token] of cases) {
      const css = readFileSync(join(here, '..', rel), 'utf8').replace(/\/\*[\s\S]*?\*\//g, ' ')
      const at = css.indexOf(sel)
      expect(at, `${rel}: nie je ${sel}`).toBeGreaterThan(-1)
      const rule = css.slice(at, css.indexOf('}', at))
      expect(rule, `${rel}: ${sel} nepoužíva ${token}`).toContain(`var(${token})`)
      expect(rule, `${rel}: ${sel} má zbytočný backdrop-filter`).not.toMatch(/backdrop-filter/)
      expect(rule, `${rel}: ${sel} je priesvitné`).not.toContain('var(--glass-bg)')
    }
  })
})

describe('contrastBudget — chart paleta --chart-0..8', () => {
  /* Wiki4AI nemá knižnicu grafov: `MetricsPage.tsx` si opisuje vlastne
     („No chart library: a bar is a div whose width is that percent") a
     `GraphView` kresli uzly do canvasu. Canvas nevie kresliť
     `var(--token)`, preto sa paleta číta z `<html>` — a práve preto ju
     CSS ratchet v `designTokens.test.ts` NIKDY nevidel. Slepé miesto už
     dokázal `Toast.tsx` gradient. Tento blok ho uzatvára. */
  const CHART = Array.from({ length: 9 }, (_, i) => `--chart-${i}`)

  /** sRGB euklidovská vzdialenosť. Nie perceptuálna, ale dosť dobrá na to,
   *  aby zachytila dve takmer identické série — a na rozdiel od kontrastu
   *  nesmie dve rôzne farby s rovnakou svetlosťou (neon žltá vs neon
   *  fialová majú kontrast 1,0 a sú na nerozoznanie len v šedom). */
  function rgbDistance(a: RGBA, b: RGBA): number {
    return Math.hypot(a[0] - b[0], a[1] - b[1], a[2] - b[2])
  }

  it.each(Object.keys(BLOCKS) as Identity[])('%s: 9 sérií je čitateľných a navzájom odlíšiteľných', (identity) => {
    const block = BLOCKS[identity]
    const prose = colorOf(block, '--prose-bg')
    const card = over(colorOf(block, '--surface-1'), colorOf(block, '--bg-grad-2'))
    const series = CHART.map((name) => {
      const c = parseColor(valueOf(block, name))
      expect(c, `${identity}: chýba ${name}`).not.toBeNull()
      return { name, c: c as RGBA }
    })

    const offenders: string[] = []
    for (const { name, c } of series) {
      for (const [label, bg] of [['prose', prose], ['karta', card]] as const) {
        const ratio = contrast(c, bg)
        /* WCAG 1.4.11 — grafické prvky potrebujú 3:1, nie 4,5:1. */
        if (ratio < 3) offenders.push(`${name} na ${label} ${ratio}:1`)
      }
    }
    expect(offenders, `${identity}: séria pod 3:1: ${offenders.join('; ')}`).toEqual([])

    let minDistance = Number.POSITIVE_INFINITY
    let worstPair = ''
    for (let i = 0; i < series.length; i += 1) {
      for (let j = i + 1; j < series.length; j += 1) {
        const d = rgbDistance(series[i].c, series[j].c)
        if (d < minDistance) {
          minDistance = d
          worstPair = `${series[i].name}/${series[j].name}`
        }
      }
    }
    expect(
      minDistance,
      `${identity}: série ${worstPair} sú od seba len ${minDistance.toFixed(1)} v sRGB — na grafe sa nedajú rozlíšiť`,
    ).toBeGreaterThanOrEqual(40)
  })

  it('paleta nežije len v tokenoch — číta ju canvas (GraphView) aj CSS (share bar)', () => {
    const ts = readFileSync(join(here, '../theme/themeColors.ts'), 'utf8')
    for (const name of CHART) {
      expect(ts, `themeColors.ts nečíta ${name}`).toContain(`'${name}'`)
    }
    const graph = readFileSync(join(here, '../components/GraphView.tsx'), 'utf8')
    expect(graph).toContain('useChartPalette')
    expect(graph, 'GraphView sa vrátil k palete akcentov').not.toContain('buildNodePalette')

    const metrics = readFileSync(join(here, '../pages/MetricsPage.css'), 'utf8').replace(/\/\*[\s\S]*?\*\//g, ' ')
    const fill = metrics.slice(metrics.indexOf('.metrics-share-bar-fill {'))
    expect(fill, 'share bar nepoužíva chart paletu').toContain('var(--chart-0)')
    expect(fill).not.toMatch(/#[0-9a-fA-F]{3,8}/)
  })
})
