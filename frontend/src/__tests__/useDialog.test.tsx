import { fireEvent, render, screen, cleanup } from '@testing-library/react'
import { useState } from 'react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useDialog } from '../hooks/useDialog'

/**
 * WIKI4AI-194 (DIZ-3 item 5) — test drží dva veci naraz:
 *  1. `useDialog` otvorí a zatvorí `<dialog>` podľa `open`.
 *  2. Dokumentuje, PREČO hook volá `showModal()` za guardom: v jsdom
 *     29.1.1 `HTMLDialogElement.showModal` neexistuje. Ak jsdom niekedy
 *     implementáciu doplní, druhý test zčervenie a pripomene, že guard
 *     už nie je nutný — ale PRVÝ test musí zostať zelený v oboch
 *     svetoch, preto je to test chovania, nie test prítomnosti metódy.
 */
function Probe({ open }: { open: boolean }) {
  const ref = useDialog<HTMLDialogElement>(open)
  return (
    <dialog ref={ref} data-testid="dlg" className="modal-backdrop">
      <div className="modal-content">obsah</div>
    </dialog>
  )
}

describe('useDialog (WIKI4AI-194, DIZ-3 item 5)', () => {
  afterEach(cleanup)

  it('otvorí a zatvorí dialog podľa `open`', () => {
    const { rerender } = render(<Probe open={false} />)
    const dlg = screen.getByTestId('dlg')
    expect(dlg.hasAttribute('open')).toBe(false)

    rerender(<Probe open />)
    expect(dlg.hasAttribute('open')).toBe(true)

    rerender(<Probe open={false} />)
    expect(dlg.hasAttribute('open')).toBe(false)
  })

  it('jsdom 29 showModal neimplementuje — preto je guard potrebný', () => {
    const el = document.createElement('dialog')
    expect(
      typeof el.showModal,
      'jsdom doplnil showModal — guard v useDialog je stále správny, ' +
        'ale komentár v hooks/useDialog.ts treba aktualizovať',
    ).toBe('undefined')
  })

  it('<dialog> má úlohu dialog bez role atribútu (native sémantika)', () => {
    render(<Probe open />)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})

describe('useDialog — návrat focusu (WCAG 2.4.3 Focus Order)', () => {
  /**
   * Dôvod, prečo tento test existuje: live Chrome ukázal, že po Escape
   * padne focus na <body> a nie na spúšťača. Príčina nie je v `<dialog>`,
   * ale v poradí krokov: Escape zavrie dialog SÁM → `el.open` je false →
   * `close()` (jediná vec, ktorá vracia focus) sa už nevolá → React dialog
   * odmontuje a focus ostane na BODY. Na `<div>` modaloch to nebolo, lebo
   * Escape vôbec nezatváral.
   *
   * Na rozdiel od top-layeru sa TENTO gap v jsdom otestovať dá — návrat
   * focusu robíme explicitne v kóde, nie cez UA.
   *
   * `e.currentTarget.focus()` v kliku nie je dekorácia: prehliadač pri
   * kliknutí presunie focus na tlačidlo, jsdom áno. Bez toho by hook
   * zachytil ako spúšťača `<body>` a test by meral niečo iné, než appka
   * robí.
   */
  function Harness() {
    const [open, setOpen] = useState(false)
    const ref = useDialog<HTMLDialogElement>(open)
    return (
      <>
        <button
          data-testid="invoker"
          onClick={(e) => {
            e.currentTarget.focus()
            setOpen(true)
          }}
        >
          spúšťač
        </button>
        {open && (
          <dialog ref={ref} data-testid="dlg" className="modal-backdrop">
            <button data-testid="inside">focus vnútri</button>
            <button data-testid="close-btn" onClick={() => setOpen(false)}>
              zavrieť
            </button>
          </dialog>
        )}
      </>
    )
  }

  it('po zatvorení vráti focus spúšťačovi, nie <body>', () => {
    render(<Harness />)

    fireEvent.click(screen.getByTestId('invoker'))
    expect(screen.getByTestId('dlg')).toBeInTheDocument()

    // focus vnútri dialogu, tak ako to po `showModal()` býva
    const inside = screen.getByTestId('inside')
    inside.focus()
    expect(document.activeElement).toBe(inside)

    fireEvent.click(screen.getByTestId('close-btn'))

    expect(screen.queryByTestId('dlg')).toBeNull()
    expect(document.activeElement, 'focus mal zostať na spúšťačovi (2.4.3)').toBe(
      screen.getByTestId('invoker'),
    )
  })

  it('nevezme focus, ak v dialogu nebol — to by bola krádež', () => {
    render(<Harness />)

    fireEvent.click(screen.getByTestId('invoker'))
    screen.getByTestId('inside').focus()

    // focus odišle MIMO dialog skôr, než sa zavrie
    const elsewhere = document.createElement('button')
    elsewhere.textContent = 'niekde inde'
    document.body.appendChild(elsewhere)
    elsewhere.focus()
    expect(document.activeElement).toBe(elsewhere)

    fireEvent.click(screen.getByTestId('close-btn'))

    expect(document.activeElement, 'mimo-dialog focus sa nemá meniť').toBe(elsewhere)
    elsewhere.remove()
  })
})

/**
 * WIKI4AI-201 — `<body>` nie je spúšťač.
 *
 * AdminUsers otvára dialog z kebab menu: položka menu, ktorá mala focus, sa
 * kliknutím odmountuje a focus padne na `<body>` EŠTE predtým, než effect
 * zachytí `document.activeElement`. Hook si doteraz zapísal `<body>` a
 * cleanup na konci zavolal `body.focus()` — no-op, ktorý tváril, že 2.4.3
 * Focus Order je splnený. Práve vďaka tomu chyba prežila WIKI4AI-195.
 *
 * Test je zámerne o tom, čo sa NESTALO: žiadne `focus()` sa privolať nemá.
 * Vystupuje na starom kóde (ten `body.focus()` volal) a nespolieha sa na
 * top-layer, ktorý jsdom 29 neimplementuje.
 */
describe('useDialog — <body> nie je spúšťač (WIKI4AI-201)', () => {
  /** Otvára dialog bez toho, aby niečo siahlo na focus — presne ten stav,
   *  ktorý necháva browser na `<body>` po odmountovaní položky menu. */
  function BodyInvokerHarness() {
    const [open, setOpen] = useState(false)
    const ref = useDialog<HTMLDialogElement>(open)
    return (
      <>
        <button data-testid="open-no-focus" onClick={() => setOpen(true)}>
          spúšťač bez focusu
        </button>
        {open && (
          <dialog ref={ref} data-testid="dlg" className="modal-backdrop">
            <button data-testid="inside">focus vnútri</button>
            <button data-testid="close-btn" onClick={() => setOpen(false)}>
              zavrieť
            </button>
          </dialog>
        )}
      </>
    )
  }

  it('si nezapíše <body> a pri zatvorení neradí falošný návrat focusu', () => {
    render(<BodyInvokerHarness />)

    fireEvent.click(screen.getByTestId('open-no-focus'))
    expect(screen.getByTestId('dlg')).toBeInTheDocument()
    // Žiadny prvok nedostal focus — browser by tu nechal <body>.
    expect(document.activeElement).toBe(document.body)

    // Simuluj to, čo v prehliadači robí showModal(): focus ide dovnútra.
    const inside = screen.getByTestId('inside')
    inside.focus()
    expect(document.activeElement).toBe(inside)

    // Až sem merajame: cleanup nesmie zavolať focus() na <body>.
    const focusSpy = vi.spyOn(HTMLElement.prototype, 'focus')
    fireEvent.click(screen.getByTestId('close-btn'))

    expect(focusSpy, 'návrat focusu na <body> je no-op, ktorý maskuje chybu').not.toHaveBeenCalled()
    focusSpy.mockRestore()
  })
})
