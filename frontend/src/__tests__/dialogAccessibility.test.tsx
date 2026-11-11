/**
 * WIKI4AI-195 (DIZ-4 item 5) — WCAG 4.1.2 Name, Role, Value na dialógoch.
 *
 * PREČO TENTO TEST VÔBEC EXISTUJE. Päť dialógov v appke malo `aria-labelledby`
 * a testy boli zelené. Prístupný názov to však nedalo žiadny — atribút bol na
 * vnútornom `<div className="modal-content">`, nie na `<dialog>`. Role
 * `dialog` nema meno z obsahu (name-from-author), takže div s generic role
 * meno dostal (a AT ho aj tak ignoruje) a dialógostal bez mena.
 *
 * Overene dvoma nezávislymi zdrojmi:
 *   - MDN `ARIA: dialog role`: „The best way to achieve this is by using the
 *     aria-labelledby attribute **to the role="dialog" element**."
 *   - Zivy Chrome: create-modal mal `aria-labelledby="create-modal-title"` na
 *     vnutornom dive, nadpis to id mal, a predsa
 *     `getByRole('dialog', { name: /Vytvorit/ })` vrátil 0 zasahov,
 *     `getByRole('dialog')` vrátil 1. Dialog existoval, meno nemal.
 *
 * Obe predtajsie testy to nevideli, lebo tvrdili `expect(h2).toBeInTheDocument()`
 * — to tvrdi, ze nadpis existuje, nie ze dialog ma meno. Test preto pocitame
 * accessible name tak, ako ho pocituje AT: `aria-labelledby` -> element -> text.
 */
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

import { describe, expect, it } from 'vitest'
import { fireEvent, render, screen } from '@testing-library/react'

import MarkdownToolbar from '../components/MarkdownToolbar'


const here = dirname(fileURLToPath(import.meta.url))
const srcRoot = resolve(here, '..')

const read = (rel: string): string => readFileSync(join(srcRoot, rel), 'utf8')

/**
 * Komentáre sa MUSIA odstarnit pred skenovanim. AdminUsersPage ma v JSDoc
 * vetu „… native <dialog> — focus trap …" a skenator by v nej nasiel
 * <dialog> bez atributov a hlasal by, ze dialog nema meno — falyz pozitiv
 * na tom spravnom mieste by casom viedol k zmazaniu testu.
 *
 * Single-line "//" strip tu zamer nerobime: v retazcoch by zral https://…
 * a `<dialog>` v takom komentari by odhalil az druhy skenator.
 */
function stripBlockComments(src: string): string {
  return src.replace(/\/\*[\s\S]*?\*\//g, (m) => m.replace(/[^\n]/g, ' '))
}

function tsxFiles(dir: string, acc: string[] = []): string[] {
  for (const entry of readdirSync(dir)) {
    const abs = join(dir, entry)
    if (statSync(abs).isDirectory()) tsxFiles(abs, acc)
    else if (entry.endsWith('.tsx') && !abs.includes('__tests__')) acc.push(abs)
  }
  return acc
}

/**
 * Nájde otváraciu značku `<dialog ...>` vrátane celej atribútovej časti.
 *
 * Naivný regex `<dialog[\s\S]*?>` tu nestačí: atribúty obsahujú
 * `onClick={(e) => { … }}` a `>` v `=>` by značku ukončilo pred ostatnými
 * atribútmi. Skenátor preto sleduje hĺbku zátvoriek a `>` na hĺbke 0 je
 * skutočný koniec značky. (Limitácia: `>` v reťazci vnútri atribútu by to
 * zmiasť — v našich dialógoch žiadne nie je a test by to hlasno povedal.)
 */
function openingTag(src: string, start: number): string {
  let depth = 0
  for (let i = start; i < src.length; i++) {
    const c = src[i]
    if (c === '{' || c === '(' || c === '[') depth++
    else if (c === '}' || c === ')' || c === ']') depth--
    else if (c === '>' && depth === 0) return src.slice(start, i + 1)
  }
  return ''
}

describe('4.1.2 — každý <dialog> má prístupný názov', () => {
  it('žiadny <dialog> v src nema aria-labelledby, ktorého cieľ v tom istom súbore neexistuje', () => {
    const offenders: string[] = []
    const unnamed: string[] = []

    for (const abs of tsxFiles(srcRoot)) {
      const rel = abs.slice(srcRoot.length + 1)
      const src = stripBlockComments(read(rel))
      let idx = -1
      while ((idx = src.indexOf('<dialog', idx + 1)) !== -1) {
        const tag = openingTag(src, idx)
        if (!tag) {
          offenders.push(`${rel}: <dialog> bez konca značky`)
          continue
        }
        /* Dva tvary: literál aria-labelledby="id" a dynamický
         * aria-labelledby={premenna}. Druhy tvar je prípad InsertModal —
         * jedna zložka, dva dialógy, preto sa id posúva propom. Strážny test
         * ho NESMÍ pokladať za menej cenný: bez neho by `aria-label={x}` s
         * prázdnu premennou prešiel ako "pomenovaný". */
        const labelledby =
          /aria-labelledby="([^"]+)"/.exec(tag) ||
          /aria-labelledby=\{\s*([A-Za-z_$][\w$]*)\s*\}/.exec(tag)
        const label =
          /aria-label="([^"]+)"/.exec(tag) ||
          /aria-label=\{\s*([A-Za-z_$][\w$]*)\s*\}/.exec(tag)
        if (!labelledby && !label) {
          unnamed.push(rel)
          continue
        }
        if (labelledby) {
          const value = labelledby[1]
          if (labelledby[0].startsWith('aria-labelledby="')) {
            /* literál — cieľ MUSÍ existovať v tom istom súbore */
            if (!src.includes(`id="${value}"`)) {
              offenders.push(`${rel}: aria-labelledby="${value}" -> žiadne id="${value}" v súbore`)
            }
          } else {
            /* dynamika — aspoň musí byť ten istý výraz použitý ako id,
             * inak ukazuje do prázdna */
            if (!src.includes(`id={${value}}`)) {
              offenders.push(`${rel}: aria-labelledby={${value}} -> žiadne id={${value}} v súbore`)
            }
          }
        }
      }
    }

    expect(unnamed, `<dialog> bez prístupného názvu: ${unnamed.join(', ')}`).toEqual([])
    expect(offenders, `rozbité aria-labelledby: ${offenders.join(', ')}`).toEqual([])
  })

  it('5 dialógov, ktoré Opus menoval, majú meno na <dialog>, nie na vnútornom dive', () => {
    const files = [
      'pages/Dashboard.tsx',
      'pages/AdminUsersPage.tsx',
      'pages/ProjectSettings.tsx',
    ]
    for (const rel of files) {
      const src = stripBlockComments(read(rel))
      let idx = -1
      let dialogs = 0
      while ((idx = src.indexOf('<dialog', idx + 1)) !== -1) {
        const tag = openingTag(src, idx)
        dialogs++
        expect(
          /aria-(label|labelledby)=/.test(tag),
          `${rel}: <dialog> bez aria-label/aria-labelledby na sebe`,
        ).toBe(true)
      }
      expect(dialogs, `${rel}: neocekávane nič dialógov`).toBeGreaterThan(0)
    }
    /* Dashboard mal aria-labelledby na <div className="modal-content"> —
       presne táto past. Na dialógu musi zostať, na dive byť nemusí. */
    const dashboard = read('pages/Dashboard.tsx')
    expect(dashboard).not.toMatch(/<div className="modal-content[^"]*" aria-labelledby=/)
  })
})

describe('4.1.2 — computed accessible name (nie prítomnosť atribútu)', () => {
  /**
   * Presne to, čo predtavné testy nevideli. `expect(h2).toBeInTheDocument()`
   * tvrdí, že nadpis existuje. 4.1.2 vyžaduje, aby MENO mal DIALÓG. Takže
   * nepozeráme na atribút — vyrežeme meno tak, ako ho počíta AT:
   * aria-labelledby -> element -> text.
   */
  const accessibleName = (el: Element): string | null => {
    const id = el.getAttribute('aria-labelledby')
    if (id) {
      const target = document.getElementById(id)
      return target ? (target.textContent || '').trim() : null
    }
    return el.getAttribute('aria-label')
  }

  it('link modal je <dialog> a jeho prístupný názov je text nadpisu', () => {
    render(<MarkdownToolbar onInsert={() => {}} />)
    fireEvent.click(screen.getByTitle('Link'))

    const dlg = document.querySelector('dialog[open]')
    expect(dlg, 'link modal musí byť <dialog>, nie <div>').not.toBeNull()
    expect(dlg!.tagName).toBe('DIALOG')
    expect(accessibleName(dlg!)).toBe('Insert Link')
  })

  it('image modal je <dialog> a jeho prístupný názov je text nadpisu', () => {
    render(<MarkdownToolbar onInsert={() => {}} />)
    fireEvent.click(screen.getByTitle('Image'))

    const dlg = document.querySelector('dialog[open]')
    expect(dlg, 'image modal musí byť <dialog>, nie <div>').not.toBeNull()
    expect(accessibleName(dlg!)).toBe('Insert Image')
  })

  it('dialóg má úlohu dialog bez role atribútu a aspoň jedno focusovateľné ovládanie', () => {
    render(<MarkdownToolbar onInsert={() => {}} />)
    fireEvent.click(screen.getByTitle('Link'))

    const dlg = document.querySelector('dialog[open]')!
    /* native <dialog> dáva úlohu z tagu — role="dialog" navyše by bola
       duplicita, ktorú MDN neodporúča riešiť */
    expect(dlg.getAttribute('role')).toBeNull()
    /* MDN: „Dialogs should always have at least one focusable control." */
    expect(dlg.querySelectorAll('button, input, select, textarea, [tabindex]').length)
      .toBeGreaterThan(0)
  })

  it('link a image modal majú RÔZNE id nadpisu, aby sa nemohli podeliť o jedno meno', () => {
    const src = stripBlockComments(read('components/MarkdownToolbar.tsx'))
    expect(src).toContain('titleId="toolbar-link-modal-title"')
    expect(src).toContain('titleId="toolbar-image-modal-title"')
  })
})

describe('migrácia overlayov — čo sa zmenilo pod testmi', () => {
  it('Vault import modal aj MarkdownToolbar overlay sú <dialog>, nie <div>', () => {
    const vault = stripBlockComments(read('pages/VaultPage.tsx'))
    const toolbar = stripBlockComments(read('components/MarkdownToolbar.tsx'))

    expect(vault).not.toMatch(/<div className="import-modal-overlay"/)
    expect(toolbar).not.toMatch(/<div className="toolbar-modal-overlay"/)
    expect(vault).toMatch(/<dialog[\s\S]{0,400}import-modal-overlay/)
    expect(toolbar).toMatch(/<dialog[\s\S]{0,400}toolbar-modal-overlay/)
  })

  /**
   * Past, na ktorú manager výslovne upozornil: `data-testid` sedelo na
   * backdrop elemente (`vault-import-modal`, `.toolbar-modal-overlay`) a
   * testy klikali na neho. Po migrácii je to stále TEN ISTÝ element — len
   * je z neho <dialog> — a klik mimo panela rieši `e.target ===
   * e.currentTarget`, nie `stopPropagation`. Test to drží, lebo presne
   * toto sa ticho rozbíja, keď sa backdrop presunie na vnútorný panel.
   */
  it('testidy ostali na backdrop elemente, nie na vnútornom paneli', () => {
    const vault = stripBlockComments(read('pages/VaultPage.tsx'))
    const dialogTag = openingTag(vault, vault.indexOf('<dialog'))
    expect(dialogTag).toContain('data-testid="vault-import-modal"')
    expect(dialogTag).toContain('aria-labelledby="vault-import-title"')
    expect(dialogTag).toContain('onCancel={closeImportModal}')
  })

  it('overlay CSS nesmie mať vlastné stmavenie, inak sa scrim maľuje dvakrát', () => {
    const cases = [
      ['pages/VaultPage.css', '.import-modal-overlay'],
      ['components/MarkdownToolbar.css', '.toolbar-modal-overlay'],
    ] as const
    for (const [rel, sel] of cases) {
      const css = stripBlockComments(read(rel))
      const block = new RegExp('\\' + sel + '\\s*\\{([^}]*)\\}').exec(css)
      if (block) {
        expect(block[1], `${rel}: stmavenie patrí na ::backdrop z --scrim`).not.toMatch(/background/)
      }
    }
  })
})
