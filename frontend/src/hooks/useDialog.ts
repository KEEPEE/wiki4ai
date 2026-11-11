import { useEffect, useRef, type RefObject } from 'react';

/**
 * useDialog — WIKI4AI-194 (DIZ-3 item 5) + WIKI4AI-195 (DIZ-4 items 5, 6, 10)
 * ============================================================================
 * Modaly v tejto appke boli `<div>`-y, ktoré si focus trap, Escape a
 * "zvyšok stránky je nedostupný" museli riešiť samy. Nestíhali to: focus
 * unikol za modal, Escape ho nezatvoril a podklad bol naďalej tabovateľný.
 * Native `<dialog>` + `showModal()` dáva všetky tri zadarmo (top layer,
 * `::backdrop`, automatická `aria-modal`, focus trap aj návrat focusu).
 *
 * PREČO GUARD, NIE PRÍMOVÉ VOLANIE
 * --------------------------------
 * `jsdom` 29.1.1 `HTMLDialogElement.showModal` **neimplementuje** —
 * `typeof el.showModal === 'undefined'` (overené testom v
 * `__tests__/useDialog.test.tsx`, nie dohad). Pri priamom volaní by každý
 * jsdom test, ktorý modal vykreslí, spadol na `TypeError`. Preto fallback
 * na atribút `open`: v prehliadači behá skutočné `showModal()` (top-layer
 * + `::backdrop`), v testoch ostáva otvorený dialog so správnou
 * semantikou. Testy netestujú top layer — testujú markup a stav.
 *
 * KĽÚČOVÉ: `onCancel` na `<dialog>` je povinný. Pri Escape browser vypáli
 * `cancel` a dialog zavrie SÁM — React state by ostal `open === true` a
 * efekt by sa znova nespustil, takže by React považoval zatvorený modal za
 * otvorený. `onCancel={onClose}` drží state v súlade.
 */
function openDialog(el: HTMLDialogElement): void {
  if (typeof el.showModal === 'function') {
    el.showModal();
  } else {
    /* jsdom 29: showModal chýba. Atribút `open` dialog otvorí (bez
     * top-layeru), čo testom stačí. */
    el.setAttribute('open', '');
  }
}

function closeDialog(el: HTMLDialogElement): void {
  if (typeof el.close === 'function') {
    if (el.open) el.close();
  } else {
    el.removeAttribute('open');
  }
}

/**
 * WIKI4AI-201: je tento prvok skutočný kandidát na návrat focusu?
 *
 * `document.body` je to, čo browser nahlási, keď už o focus prišlo — nie je
 * to spúšťač. Uložiť si ho znamená neskôr zavolať `body.focus()`, čo je
 * no-op, ktorý tvári, že návrat focusu funguje. Presne takto WIKI4AI-201
 * maskoval sám seba: cleanup v AdminUsers volal `focus()` na `<body>` a
 * testy nemali ako zistiť, že sa nestalo nič.
 */
function isFocusableInvoker(el: Element | null): el is HTMLElement {
  if (!el || el === document.body || el === document.documentElement) return false;
  return typeof (el as HTMLElement).focus === 'function';
}

export function useDialog<T extends HTMLDialogElement>(open: boolean): RefObject<T | null> {
  const ref = useRef<T>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;

    if (!open) return;

    /* Spúšťač = prvok, ktorý mal focus tesne pred `showModal()`. Native
     * `close()` je to jediné, čo focus vráti späť, ale pri Escape to
     * nepomôže: browser dialog zavrie SÁM, `el.open` je potom false a
     * `closeDialog()` v cleanup ho preto už nevolá. React navyše dialog
     * odmontuje a focus padne na `<body>` — klávesnica ostala uprostred
     * stránky (2.4.3 Focus Order). Overené živým Chrome: po Escape na
     * ProjectSettings bol focus na BODY, nie na `delete-button`.
     *
     * WIKI4AI-201: toto funguje len vtedy, ak spúšťač v tento moment ešte
     * existuje. Ak sa dialog otvára z menu, položka menu už bola odmountovaná
     * a activeElement je `<body>` — preto Menu pri aktivácii položky vráti
     * focus na trigger SKÔR, než zavolá handler (pozri `components/Menu.tsx`).
     * `isFocusableInvoker` tu zámorne nezapisuje `<body>`: žiadny fallback
     * neznamená nič lepší ako falošný fallback. */
    const invoker = isFocusableInvoker(document.activeElement) ? document.activeElement : null;

    openDialog(el);

    return () => {
      closeDialog(el);

      /* Kedy vrátiť focus. React volá cleanup pasívneho efektu AŽ PO
       * odobraní DOM node, takže `el.contains(document.activeElement)` je
       * v tento moment už často false — focus stihol padnúť na <body>.
       * Spoliehame sa preto na dva signály, nezávisle od poradia:
       *   focus bol vnútri (dialog ešte v DOM), alebo
       *   focus sa stratil (activeElement je body — dialog už v DOM nie je).
       * Ak si používateľ focus sám presunul inam, nekradneme ho. */
      const active = document.activeElement;
      const focusWasInside = el.contains(active);
      const focusWasLost = active === null || active === document.body;

      if (
        (focusWasInside || focusWasLost) &&
        invoker &&
        invoker.isConnected &&
        typeof invoker.focus === 'function'
      ) {
        invoker.focus();
      }
    };
  }, [open]);

  return ref;
}
