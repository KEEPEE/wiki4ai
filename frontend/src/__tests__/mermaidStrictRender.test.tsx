/**
 * WIKI4AI-198: dôkaz, že prepnutie Mermaidu z `loose` na `strict` nerozbiť
 * diagramy, ktoré už v wiki existujú.
 *
 * Kampaňové zadanie sa obávalo, že `strict` zničí HTML v labeloch, a teda že
 * prepnutie rozbije 33 dokumentov na dev. Tento súbor to meria na **skutočnom
 * Mermaide** a s **skutočnou produkčnou konfiguráciou** (`applyMermaidTheme`),
 * nie na fixtúre:
 *
 *  1. `<br/>` v labele v `strict` **prežíva** a text je skutočne rozdelený na
 *     dva riadky. Mermaid `<br>` rieši už pri parsovaní labelu
 *     (`breakToPlaceholder` → `getRows` → `placeholderToBreak`), nie až vo
 *     finálnom SVG pase, ktorú `loose` preskakuje — preto naň `strict`
 *     nedosahuje.
 *  2. `<b>` v labele prežíva taktiež.
 *  3. Na výstupe `strict` už naša sink sanitizácia **nemá čo meniť** — to je
 *     priamy dôkaz, že knižnica si finálny DOMPurify pas urobila sama a že
 *     naša pas je navyše, nie jediná.
 *  4. Útočný `onerror` napísaný v labele sa do strict SVG vôbec nedostane.
 *
 * Mermaid potrebuje merať text; jsdom layout engine nemá, preto stubujeme
 * `getBBox` a `getComputedTextLength`. Bez toho render zlyhá na
 * `text.getBBox is not a function` a test by nič nemeral.
 */

import { describe, it, expect, beforeAll } from 'vitest';
import mermaid from 'mermaid';
import { applyMermaidTheme } from '../components/MermaidDiagram';
import { sanitizeDiagramSvg } from '../utils/svgSanitizer';

beforeAll(() => {
  const proto = window.SVGElement.prototype as unknown as Record<string, unknown>;
  proto.getBBox = () => ({ x: 0, y: 0, width: 100, height: 20 });
  proto.getComputedTextLength = () => 100;
});

/**
 * Produkčná konfigurácia: `applyMermaidTheme` je jediné miesto, kde sa
 * `securityLevel` nastavuje, takže test meria presne to, čo ide do prehliadača.
 */
function renderWithProductionConfig(id: string, code: string): Promise<string> {
  applyMermaidTheme('glass-dark');
  return mermaid.render(id, code).then(({ svg }) => svg);
}

/**
 * Rozparsuje SVG reťazec na DOM, aby sme tvrdili o uzloch, nie o podreťazcoch.
 *
 * Parsovaný je ako `text/html`, nie `image/svg+xml` — a to nie je detail:
 * Mermaid serializuje zalomenie riadku ako `<br>`, čo nie je samouzavierací
 * tag, a XML parser na ňom celému dokumentu padne (výsledok je parsererror a
 * `querySelector` then nájde null aj keď tam label je). HTML parser je
 * presne to, čo robí prehliadač v `dangerouslySetInnerHTML`: `<svg>` dostane
 * SVG menný priestor a deti `foreignObject` HTML menný priestor.
 */
function parseSvg(svg: string): Element {
  return new DOMParser().parseFromString(svg, 'text/html').body;
}

describe('Mermaid v strict režime (WIKI4AI-198)', () => {
  it('<br/> v labele prežíva a text je rozdelený na dva riadky', async () => {
    const svg = await renderWithProductionConfig(
      'strict-br',
      'flowchart TD\n  A["Line one<br/>Line two"] --> B[Next]',
    );

    // 1) hrubý doklad: v reťazci je `<br` a nie je escapovaný na `&lt;br`
    expect(svg).toMatch(/<br(\s*\/?)?>/i);
    expect(svg).not.toContain('&lt;br');

    // 2) doklad na DOM: label obsahuje dva textové kusy oddelené uzlom <br>
    const root = parseSvg(svg);
    const label = root.querySelector('.nodeLabel') ?? root.querySelector('foreignObject div');
    expect(label, 'Mermaid nevykreslil HTML label v foreignObject').not.toBeNull();

    // Mermaid obaluje text riadkov do `<p>` (a `<span class="nodeLabel">` okolo),
    // takže samotné textové uzly a `<br>` hľadáme v najvnútornejšom kontajneri.
    const lineContainer = label!.querySelector('p') ?? label!;

    const texts = Array.from(lineContainer.childNodes)
      .filter((n) => n.nodeType === 3)
      .map((n) => n.textContent?.trim());
    const breaks = Array.from(lineContainer.childNodes).filter(
      (n) => n.nodeType === 1 && (n as Element).tagName.toLowerCase() === 'br',
    );

    expect(texts).toEqual(['Line one', 'Line two']);
    expect(breaks).toHaveLength(1);
    // poradie: text, <br>, text — teda skutočný zalomený riadok, nie zlepenec
    const kinds = Array.from(lineContainer.childNodes)
      .filter((n) => n.nodeType === 1 || (n.nodeType === 3 && (n.textContent ?? '').trim()))
      .map((n) => (n.nodeType === 1 ? (n as Element).tagName.toLowerCase() : 'text'));
    expect(kinds).toEqual(['text', 'br', 'text']);
  }, 30000);

  it('<b> v labele prežíva a ostáva tučným uzlom', async () => {
    const svg = await renderWithProductionConfig(
      'strict-bold',
      'flowchart TD\n  A["plain <b>bold</b> plain"] --> B[Next]',
    );

    expect(svg).toMatch(/<b>/i);
    expect(svg).not.toContain('&lt;b&gt;');

    const bold = parseSvg(svg).querySelector('b');
    expect(bold).not.toBeNull();
    expect(bold?.textContent).toBe('bold');
    // `<b>` musí byť HTML uzol v XHTML mennom priestore, nie text
    expect(bold?.namespaceURI).toBe('http://www.w3.org/1999/xhtml');
  }, 30000);

  it('na strict výstupe už naša sanitizácia nemá čo meniť (obrana do hĺbky)', async () => {
    const svg = await renderWithProductionConfig(
      'strict-noop',
      'flowchart TD\n  A["Line one<br/>Line two"] --> B["<b>bold</b>"]',
    );

    // Ak by Mermaid v strict robil to čo v loose (t.j. žiadnu finálnu pasu),
    // naša sanitizácia by na výstupe niečo obrala. Žiadna zmena = knižnica
    // si own pas urobila sama a tá naša je druhá vrstva, nie jediná.
    expect(sanitizeDiagramSvg(svg)).toBe(svg);
  }, 30000);

  it('útočný onerror v labele sa do strict SVG vôbec nedostane', async () => {
    const svg = await renderWithProductionConfig(
      'strict-evil',
      // Bez <img>: jsdom by sa pokúsil nahrať `src=x` a Mermaid na načítanie
      // obrázka pri meraní labela čaká, čo test zavesí (nie je to zlyhanie
      // sanitizácie, je to chýbajúci resource loader v jsdom).
      'flowchart TD\n  A["<b onmouseover=window.__strictPwned=1>bold</b> label"] --> B[Next]',
    );

    expect(svg).not.toMatch(/\son[a-z]+\s*=/i);
    expect(svg).not.toMatch(/javascript:/i);
    expect((window as unknown as Record<string, unknown>).__strictPwned).toBeUndefined();
    // samotný diagram sa stále kresli: šípka z markeru musí ostať
    expect(svg).toContain('<marker');
    expect(svg).toMatch(/marker-end="url\(#/);
  }, 30000);
});
