/**
 * WIKI4AI-198: sanitizácia SVG, ktoré končia v `dangerouslySetInnerHTML`.
 *
 * Frontend má dva SVG sinky, do ktorých sa dostáva markup, koho textový zdroj
 * píše autor dokumentu:
 *
 *  - `MermaidDiagram` — Mermaid 11 volá na konci renderu
 *    `DOMPurify.sanitize(svgCode, …)` len ak `securityLevel` **nie je**
 *    `loose` (overené v `node_modules/mermaid/dist/mermaid.core.mjs`, vetva
 *    `else if (!isLooseSecurityLevel)` — je to jediná vetva, na ktorú
 *    `securityLevel` vôbec reaguje). Od WIKI4AI-198 beží `strict`, takže táto
 *    pas beží a naša je druhá vrstva. Predtým, ako to tak bolo, bol `loose`
 *    a do `innerHTML` tekulo surové SVG — presne preto táto druhá vrstva
 *    existuje a `strict` ju nenahrádza: keby niekto `securityLevel` opäť
 *    prepol (alebo Mermaid zmenil správanie), sink musí ostať čistý sám.
 *  - `PlantUmlDiagram` — SVG prichádza z **kroki**, teda z cudzieho procesu,
 *    ktorý dostáva ľubovoľný PlantUML text.
 *
 * Prečo HTML režim a nie `USE_PROFILES: { svg: true }` / `NAMESPACE: 'svg'`:
 * Mermaid kreslí labely ako HTML vnútri `<foreignObject>`. Keby sme parsowali
 * celý dokument ako SVG, deti `foreignObject` by spadli do SVG menného priestoru
 * a labely by sa nekreslili. DOMPurify si v default (HTML) režime SVG nevyberá
 * len z HTML zoznamu — ten obsahuje aj SVG a MathML — a `foreignobject` sa ako
 * HTML integračný bod parsí HTML parserom. Presne toto používa sám Mermaid,
 * preto je to aj jediná konfigurácia, o ktorej vieme, že diagram nerozbije.
 *
 * Dôkaz, že diagram nepoškodí: na reálnom Mermaid SVG (flowchart s `<br/>`
 * a `<b>` v labeloch) táto konfigurácia neoberie ani jeden tag ani atribút a
 * na reálnom kroki SVG odoberie iba `contentStyleType` a `<?plantuml …?>`
 * inštrukciu — oboje prehliadač aj tak nepoužíva. `viewBox`, `<style>`,
 * `marker-end`/`<marker>` (šípky), `style`, `preserveAspectRatio` a
 * `foreignObject` zostávajú. Detaily sú v testoch
 * `src/__tests__/svgSanitizer.test.ts`.
 */

import DOMPurify from 'dompurify';
import type { Config } from 'dompurify';

/**
 * Povolené navyše oproti default profilu DOMPurify.
 *
 * `foreignobject` nie je v default HTML zozname a `dominant-baseline` nie je
 * v default zozname atribútov — bez nich by Mermaid prišiel o labely a
 * o vertikálne zarovnanie textu. Zhoduje sa to s konfiguráciou, ktorú na
 * vlastný výstup používa samotný Mermaid.
 *
 * `target` je v default zozname pre HTML `<a>`, ale DOMPurify má pre prvky
 * v SVG mennom priestoru samostatný zoznam a tam `target` nie je. Mermaid
 * ani PlantUML nerobia link ako HTML `<a>` ale ako SVG `<a>`, preto by bez
 * tohto riadku link v diagrame prišiel o cieľové okno (overené na reálnom
 * kroki SVG). `rel="noopener"` k nemu dopĺňa hook nižšie.
 */
export const DIAGRAM_SVG_CONFIG: Config = {
  ADD_TAGS: ['foreignobject'],
  ADD_ATTR: ['dominant-baseline', 'target'],
  HTML_INTEGRATION_POINTS: { foreignobject: true },
};

// Hook na `addHook` je globálny pre celú inštanciu DOMPurify, preto sa
// registruje práve raz — inak by sa pri každom volaní reťazil znova.
let noopenerHookRegistered = false;

/**
 * `<a target="_blank">` bez `rel="noopener"` nechá otvorené okno s prístupom
 * k `window.opener`. Mermaid vie taký link vygenerovať z `link` príkazu,
 * preto to doplníme po sanitizácii atribútov.
 *
 * Porovnanie je bez rozlišovania veľkosti, lebo SVG je case-sensitive a
 * `tagName` SVG kotvy je malé `a`, zatiaľ čo HTML kotva má veľké `A`.
 * Mermaid si vo vlastnom hooku predpokladá tá veľká verzia, preto mu
 * `rel="noopener"` na SVG `<a>` vôbec nenaskočí — a práve SVG `<a>` je to,
 * čo končí v našom sinku. Overené v teste `doplní rel="noopener"`.
 */
function registerNoopenerHook(): void {
  if (noopenerHookRegistered) return;
  noopenerHookRegistered = true;
  DOMPurify.addHook('afterSanitizeAttributes', (node: Element) => {
    if (node.tagName.toUpperCase() !== 'A') return;
    if (node.getAttribute('target') !== '_blank') return;
    const rel = node.getAttribute('rel') ?? '';
    if (!/\bnoopener\b/i.test(rel)) {
      node.setAttribute('rel', (rel + ' noopener').trim());
    }
  });
}

/**
 * Vyčistí SVG reťazec tak, aby z neho zostala len štruktúra, ktorú
 * prehliadač vykreslí, ale nespustí: žiadny `<script>`, žiadne `on*`
 * handlery, žiadne `javascript:` URL.
 *
 * @param svg surový SVG markup z Mermaidu alebo z kroki
 * @returns sanitizovaný markup pripravený na `dangerouslySetInnerHTML`
 */
export function sanitizeDiagramSvg(svg: string): string {
  if (!svg) return svg;
  registerNoopenerHook();
  return DOMPurify.sanitize(svg, DIAGRAM_SVG_CONFIG);
}
