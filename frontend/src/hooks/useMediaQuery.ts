import { useEffect, useState } from 'react';

/**
 * useMediaQuery — WIKI4AI-195 (DIZ-4 item 8, WCAG 2.4.3 Focus Order)
 * -------------------------------------------------------------------
 * Mobilný off-canvas sidebar je skrytý CSS-om (`transform: translateX(-100%)`
 * pod 900 px), ale skrytý prvok NIE je nedostupný: jeho odkazy zostávajú v
 * tab ráde, takže človek s klávesnicou odtabuje 20 odkadmi do prázdna predtým,
 * než sa dostane k obsahu. `inert` je to, čo z neho robí skutočne
 * nedostupný podstrom — CSS na to žiadny selektor nemá.
 *
 * Preto to JS musí vedieť: `inert` sa nedá viazať na media query v CSS,
 * takže ho treba poslať ako atribút z `matchMedia`. Hodnota sa drží v stave
 * a aktualizuje pri zmene, lebo okno sa dá premeniť za behu.
 */
export function useMediaQuery(query: string): boolean {
  const [matches, setMatches] = useState(() => {
    /* SSR / testovacie prostredie bez matchMedia: radšej `false`, teda
     * "nie je to úzke okno" — inert sa nepoužije a markup ostane
     * konzervatívne prístupný. */
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false;
    return window.matchMedia(query).matches;
  });

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return;
    const mql = window.matchMedia(query);
    const onChange = (event: MediaQueryListEvent) => setMatches(event.matches);

    setMatches(mql.matches);
    mql.addEventListener('change', onChange);
    return () => mql.removeEventListener('change', onChange);
  }, [query]);

  return matches;
}
