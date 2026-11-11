/**
 * WIKI4AI-208 — stráž hybnosti (motion tokens).
 *
 * Dva nezávislé dôvody, prečo existuje:
 *
 * 1. `prefers-reduced-motion` v `base.css` nuluje `--dur-fast/med/slow` na
 *    `0ms`. Kto napíše `transition: all 0.3s ease` s číslom, ten obíde
 *    prepínač — globálny fallback `.01ms !important` síce pomôže, ale
 *    spoliehať sa na `!important` ako na jedinu poistku je slabé.
 *
 * 2. `--ease-pop` je overshoot krivka (`cubic-bezier(…, 1.275)`). Na texte
 *    alebo rade tabuľky pôsobí ako.triasenie, preto patrí len na prvky,
 *    ktoré „priletia“ — modal. Test to pripúta, lebo pri 30+ prechodoch sa
 *    to nedá ustrážiť reviewom.
 *
 * Ratchet je obojsmerný: súbory, ktoré už tokeny majú, sa nesmú vrátiť
 * k číslam, a nový súbor s číslom padne.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { describe, expect, it } from 'vitest'

const here = dirname(fileURLToPath(import.meta.url))
const srcRoot = resolve(here, '..')

function stripComments(css: string): string {
  return css.replace(/\/\*[\s\S]*?\*\//g, ' ')
}

/** `0.3s`, `.4s`, `300ms` — to, čo nesmie stáť mimo tokenov. */
const LITERAL_DURATION = /\b\d*\.?\d+(?:ms|s)\b/

/** `tokens.css` definuje trvania a `base.css` má reduced-motion fallback
 *  s `.01ms !important` — tam čísla PATRIA. */
const EXEMPT = new Set(['styles/tokens.css', 'styles/base.css'])

function cssFiles(): string[] {
  const acc: string[] = []
  const walk = (dir: string): void => {
    for (const entry of readdirSync(dir)) {
      const abs = join(dir, entry)
      if (statSync(abs).isDirectory()) walk(abs)
      else if (entry.endsWith('.css')) acc.push(abs.slice(srcRoot.length + 1).split(/[\\/]/).join('/'))
    }
  }
  walk(srcRoot)
  return acc.sort()
}

const FILES = cssFiles()

/** Deklarácia hocikoľvek v pravidle — NIE len na začiatku riadku.
 *  Prvá verzia tejto stráže bola kotvená na riadok a jednoriadkové
 *  pravidlo `.x { transition: all 0.3s ease; }` jej prešlo. Test, ktorý
 *  nevie chytiť vlastný probe, je dekorácia. */
function declarations(css: string, prop: string, skip: string[] = []): string[] {
  const re = new RegExp(`(?:^|[;{\\n])\\s*${prop}((?:-[a-z-]+)?)\\s*:\\s*([^;}]+)`, 'gi')
  return [...css.matchAll(re)]
    .filter((m) => !skip.includes(m[1].toLowerCase()))
    .map((m) => m[2].trim())
}

describe('motionTokens — trvania sa skladajú z tokenov', () => {
  it('v `transition` nie je jediné literálne trvanie mimo tokens.css a base.css', () => {
    const offenders: string[] = []
    for (const rel of FILES) {
      if (EXEMPT.has(rel)) continue
      const css = stripComments(readFileSync(join(srcRoot, rel), 'utf8'))
      for (const value of declarations(css, 'transition')) {
        if (LITERAL_DURATION.test(value)) offenders.push(`${rel} → transition: ${value}`)
      }
    }
    expect(offenders, `literálne trvanie v transition: ${offenders.join('; ')}`).toEqual([])
  })

  it('v `animation` nie je literálne trvanie okrem slučkových indikátorov', () => {
    /* `spin`, `pulse`, `float`, `mermaid-spin`, `plantuml-spin` a
       `uploaded-image-shimmer` sú SLUČKY, nie prechody: 1.4s / 2.4s / 20s
       je perióda indikátora, nie doba, za ktorá prvok dorastie do konečného
       stavu. `--dur-*` trojica (150/200/300 ms) pre ne nedáva zmysel a
       vypína ich reduced-motion blokom. Zoznam je explicitný, aby sa sem
       nedostal nový `infinite` dekoratívny efekt zadarmo. */
    const LOOPS = /\b(spin|pulse|float|mermaid-spin|plantuml-spin|uploaded-image-shimmer)\b/
    const offenders: string[] = []
    for (const rel of FILES) {
      if (EXEMPT.has(rel)) continue
      const css = stripComments(readFileSync(join(srcRoot, rel), 'utf8'))
      /* `animation-delay` je POSUN, nie dĺžka: `-7s` / `-14s` v
         AmbientBackground.css rozfázujú 3 orby do seba a sú správne. */
      for (const value of declarations(css, 'animation', ['-delay'])) {
        if (LOOPS.test(value)) continue
        if (LITERAL_DURATION.test(value)) offenders.push(`${rel} → animation: ${value}`)
      }
    }
    expect(offenders, `literálne trvanie v animation: ${offenders.join('; ')}`).toEqual([])
  })

  it('--ease-pop (overshoot) patrí len na modal', () => {
    /* overshoot na texte alebo rade tabuľky vyzerá ako triasenie.
       Wiki4AI nemá animovaný dropdown — `Menu.css` má len `transition`,
       preto je `--ease-pop` v appke práve na jednom mieste. Kto ho pridá
       inde, musí vedome rozšíriť tento zoznam. */
    const users: Array<{ rel: string; selector: string }> = []
    for (const rel of FILES) {
      if (EXEMPT.has(rel)) continue
      const css = stripComments(readFileSync(join(srcRoot, rel), 'utf8'))
      const re = /([^{}]+)\{([^{}]*)\}/g
      for (const m of css.matchAll(re)) {
        if (!m[2].includes('var(--ease-pop)')) continue
        users.push({ rel, selector: m[1].trim().replace(/\s+/g, ' ') })
      }
    }
    expect(users.length, 'nikto nepoužíva --ease-pop — token je mŕtvy').toBeGreaterThan(0)
    for (const u of users) {
      expect(
        u.selector,
        `${u.rel}: --ease-pop je na selektore ${u.selector}, nie na modale`,
      ).toMatch(/\.modal-(content|backdrop|overlay)/)
    }
  })

  it('shimmer je len na skeleton obrázka, nie ako dekorácia', () => {
    /* DON'T zo zadania: „nekonečný shimmer“. Jediný shimmer v appke je
       `uploaded-image-shimmer` na loading placeholderi — tam je to
       indikátor stavu, nie výzdoba, a reduced-motion ho vypne. */
    const files = FILES.filter((rel) =>
      /@keyframes[\s\S]*shimmer|shimmer[\s\S]*@keyframes/.test(stripComments(readFileSync(join(srcRoot, rel), 'utf8'))))
    expect(files).toEqual(['components/UploadedImage.css'])

    const css = stripComments(readFileSync(join(srcRoot, 'components/UploadedImage.css'), 'utf8'))
    expect(css).toMatch(/animation:\s*uploaded-image-shimmer[^;]*infinite/)
    expect(css).toMatch(/\.uploaded-image--loading/)
  })

  it('riadok tabuľky sa pri hoveri nepohýba', () => {
    /* DON'T zo zadania: `transform` na hoveri riadku tabuľky. Posunutý
       riadok v stĺpci rozhodí oči pri čítaní a pri 200 riadkoch to vyzerá
       ako choroba. Zmeniť sa smie farba, nie geometria. */
    const offenders: string[] = []
    for (const rel of FILES) {
      const css = stripComments(readFileSync(join(srcRoot, rel), 'utf8'))
      for (const m of css.matchAll(/([^{}]*tr(?::hover|:hover[^{}]*))\s*\{([^{}]*)\}/g)) {
        if (/transform:/.test(m[2])) offenders.push(`${rel}: ${m[1].trim()}`)
      }
    }
    expect(offenders, `transform na hoveri riadku: ${offenders.join('; ')}`).toEqual([])
  })
})
