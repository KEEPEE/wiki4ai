/**
 * WIKI4AI-198: dôkaz, že `sanitizeDiagramSvg` skutočne filtruje, a súčasne
 * dôkaz, že diagram nerozbiťe.
 *
 * Dve polovice, obe potrebné:
 *
 *  1. FILTRÁCIA — pre každý útočný vstup test tvrdí, že **výsledné DOM**
 *     neobsahuje spúšťač (príkazový prvok, `on*` handler, `javascript:` URL).
 *     Netvrdíme „DOMPurify sa volal" — to by prešlo aj pri konfigurácii, ktorá
 *     by útočníka pustila dnu. Každý test preto najprv tvrdí, že NEČISTÝ vstup
 *     spúšťač obsahuje (inak by test mohol prejsť len preto, že payload sme
 *     nikdy nedostali dovnútra), a až potom jeho absenciu po sanitizácii.
 *  2. ZACHOVANIE — na reálnych výstupoch Mermaidu a kroki test tvrdí, že
 *     sanitizácia neodobere nič, čo diagram kreslí (šípky z `<marker>` +
 *     `marker-end`, `viewBox`, `<style>`, `foreignObject` s HTML labelmi,
 *     `<br/>`, `<b>`, `style`, `preserveAspectRatio`).
 *
 * Reálne fixtúry: `MERMAID_FIXTURE` je skutočný výstup `mermaid.render()`
 * (flowchart s `<br/>` a `<b>` v labeloch) — nasbíraný v režime `loose`,
 * teda v tom horšom z dvojice, lebo v ňom Mermaid svoju finálnu pasu
 * neurobil. Fixtúra je preto prísnejšia ako súčasný `strict` výstup a sank
 * ju musí zvládnuť oboje. `KROKI_FIXTURE` je skutočná odpoveď
 * `GET /plantuml/svg/...` z dev kroki — tam Mermaid vôbec nezasahuje.
 * Obidve sú skrátené na podstatné časti, štruktúra tagov a atribútov zachovaná.
 */

import { describe, it, expect } from 'vitest';
import DOMPurify from 'dompurify';
import { sanitizeDiagramSvg, DIAGRAM_SVG_CONFIG } from '../utils/svgSanitizer';

// ── Reálna fixtúra: Mermaid flowchart, nasbíraná v režime loose ────────
const MERMAID_FIXTURE = [
  '<svg id="cap-flow" width="100%" xmlns="http://www.w3.org/2000/svg" class="flowchart"',
  ' style="max-width: 116px;" viewBox="-8 -8 116 36" role="graphics-document document"',
  ' aria-roledescription="flowchart-v2">',
  '<style>#cap-flow{font-family:"trebuchet ms",verdana,arial,sans-serif;font-size:16px;fill:#ccc;}',
  '#cap-flow .edge-thickness-normal{stroke-width:1px;}',
  '#cap-flow :root{--mermaid-font-family:"trebuchet ms",verdana,arial,sans-serif;}</style><g>',
  '<marker id="cap-flow_flowchart-v2-pointEnd" class="marker flowchart-v2" viewBox="0 0 10 10"',
  ' refX="5" refY="5" markerUnits="userSpaceOnUse" markerWidth="8" markerHeight="8" orient="auto">',
  '<path d="M 0 0 L 10 5 L 0 10 z" class="arrowMarkerPath" style="stroke-width: 1; stroke-dasharray: 1,0;">',
  '</path></marker></g>',
  '<g class="edges"><path d="M 50 28 L 50 4" id="L_A_B_0" class="edge-thickness-normal edge-pattern-solid flowchart-link-A-B"',
  ' style="undefined;stroke-width: 1;" marker-end="url(#cap-flow_flowchart-v2-pointEnd)"/></g>',
  '<g class="edgeLabels"><g class="edgeLabel"><g class="label" data-id="L_A_B_0" transform="translate(0, 0)">',
  '<foreignObject width="0" height="0"><div style="display: table-cell; white-space: nowrap;"',
  ' xmlns="http://www.w3.org/1999/xhtml" class="labelBkg"><span class="edgeLabel"></span></div></foreignObject></g></g></g>',
  '<g class="nodes"><g class="node" data-look="classic" id="flowchart-A-0" transform="translate(50, 28)">',
  '<rect style="fill:#16281e;stroke:#34d399;stroke-width:1px;" rx="0" ry="0" width="100" height="18"/>',
  '<g transform="translate(-50, -9)" style="color: rgb(245, 245, 244);" class="label">',
  '<foreignObject width="100" height="18"><div xmlns="http://www.w3.org/1999/xhtml" class="nodeLabel">',
  'Line one<br/><b>bold</b></div></foreignObject></g></g></g></svg>',
].join('');

// ── Reálna fixtúra: kroki / PlantUML sequence diagram ──────────────────────
const KROKI_FIXTURE = [
  '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink"',
  ' contentStyleType="text/css" data-diagram-type="SEQUENCE" height="151px" preserveAspectRatio="none"',
  ' style="width:117px;height:151px;background:#FFFFFF;" version="1.1" viewBox="0 0 117 151"',
  ' width="117px" zoomAndPan="magnify"><?plantuml 1.2026.6?><defs/><g>',
  '<g class="participant-lifeline" data-entity-uid="part1" data-quadrant="1"><g>',
  '<rect fill="#000000" height="0.5" opacity="0" width="11" x="12" y="30" class="st13"/>',
  '<line x1="17.5" x2="17.5" y1="30" y2="110.5" stroke="#000000" stroke-width="0.5"',
  ' stroke-dasharray="5,5" class="st12"/></g></g>',
  '<g><text fill="#000000" font-family="sans-serif" font-size="14" lengthAdjust="spacing"',
  ' textLength="33.667" x="12" y="24.9951">Alice</text></g>',
  '<g><text fill="#000000" font-family="sans-serif" font-size="13" font-weight="700"',
  ' lengthAdjust="spacing" textLength="31.9985" x="35.8335" y="63.5278">bold</text></g>',
  '<a href="https://example.com/" rel="noopener" target="_blank"><text fill="#000000"',
  ' font-family="sans-serif" font-size="13" x="35" y="80">link</text></a>',
  '</g></svg>',
].join('');

// ── Pomocné funkcie ────────────────────────────────────────────────────────

/** Všetky atribúty v strome, ktoré vyzerajú ako event handler (`on…`). */
function eventHandlerAttributes(root: ParentNode): string[] {
  const found: string[] = [];
  root.querySelectorAll('*').forEach((el) => {
    for (const attr of Array.from(el.attributes)) {
      if (/^on/i.test(attr.name)) found.push(`${el.tagName.toLowerCase()}@${attr.name}`);
    }
  });
  return found;
}

/** Všetky atribúty obsahujúce `javascript:` URL. */
function javascriptUrls(root: ParentNode): string[] {
  const found: string[] = [];
  root.querySelectorAll('*').forEach((el) => {
    for (const attr of Array.from(el.attributes)) {
      if (/^\s*javascript:/i.test(attr.value)) found.push(`${el.tagName.toLowerCase()}@${attr.name}`);
    }
  });
  return found;
}

/**
 * Spúšťače, ktoré by prehliadač po vložení do DOM naozaj vykonal:
 * event handlery, `<script>`, `javascript:` URL a SMIL animácie viazané
 * na event (`<set>`/`<animate>` s `begin="…click…"`).
 */
function executableNodes(root: ParentNode): string[] {
  const found: string[] = [
    ...eventHandlerAttributes(root),
    ...javascriptUrls(root),
    ...Array.from(root.querySelectorAll('script')).map((el) => `${el.tagName.toLowerCase()}`),
  ];
  // `<a>` bez `href` nie je spúšťač; `<a href="javascript:…">` už áno — to
  // ošetrilo `javascriptUrls`. SMIL viazaný na klik je tretia cesta.
  root.querySelectorAll('set, animate').forEach((el) => {
    const begin = el.getAttribute('begin') ?? '';
    if (/click|mouseover|load/i.test(begin)) found.push(`${el.tagName.toLowerCase()}@begin=${begin}`);
  });
  return found;
}

/** Navrství HTML do skutočného document a vráti jeho koreň. */
function mount(html: string): HTMLElement {
  const host = document.createElement('div');
  host.innerHTML = html;
  document.body.appendChild(host);
  return host;
}

// ── 1. FILTRÁCIA: útočné vstupy ────────────────────────────────────────────

/**
 * Každý riadok: [název, nečistý SVG, regex ktorým nečistý SVG dokážeme
 * ako prítomnosť spúšťača]. Sanitized výstup musí mať v DOM nula spúšťačov.
 */
const ATTACKS: Array<[string, string, RegExp]> = [
  [
    'img onerror v Mermaid HTML labeli',
    '<svg xmlns="http://www.w3.org/2000/svg"><foreignObject><div xmlns="http://www.w3.org/1999/xhtml">' +
      '<img src="x" onerror="window.__pwned_img=1">' +
      '</div></foreignObject></svg>',
    /<img[^>]*onerror/i,
  ],
  [
    'svg onload',
    '<svg xmlns="http://www.w3.org/2000/svg"><svg onload="window.__pwned_load=1"><text>hi</text></svg></svg>',
    /<svg[^>]*onload/i,
  ],
  [
    'script element',
    '<svg xmlns="http://www.w3.org/2000/svg"><foreignObject><div xmlns="http://www.w3.org/1999/xhtml">' +
      '<script>window.__pwned_script=1</script></div></foreignObject></svg>',
    /<script/i,
  ],
  [
    'javascript: href na <a> (Mermaid `click … "javascript:…"`)',
    '<svg xmlns="http://www.w3.org/2000/svg"><a href="javascript:window.__pwned_href=1"><text>click</text></a></svg>',
    /href="javascript:/i,
  ],
  [
    'javascript: v xlink:href',
    '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink">' +
      '<a xlink:href="javascript:window.__pwned_xlink=1"><text>click</text></a></svg>',
    /xlink:href="javascript:/i,
  ],
  [
    'div onfocus v foreignObject labeli',
    '<svg xmlns="http://www.w3.org/2000/svg"><foreignObject><div xmlns="http://www.w3.org/1999/xhtml">' +
      '<div onfocus="window.__pwned_focus=1" autofocus>x</div></div></foreignObject></svg>',
    /<div[^>]*onfocus/i,
  ],
  [
    'SMIL <set> spúšťaný klikom (mutation XSS cez attributeName)',
    '<svg xmlns="http://www.w3.org/2000/svg"><a><text>click me</text>' +
      '<set attributeName="onmouseover" to="window.__pwned_smil=1" begin="click"/></a></svg>',
    /<set[^>]*attributeName="onmouseover"/i,
  ],
  [
    'SMIL <animate> meniaci href na javascript:',
    '<svg xmlns="http://www.w3.org/2000/svg"><a href="#"><text>click</text>' +
      '<animate attributeName="href" values="javascript:window.__pwned_anim=1" begin="click" dur="0.001s" fill="freeze"/>' +
      '</a></svg>',
    /values="javascript:/i,
  ],
  [
    'iframe s javascript: src',
    '<svg xmlns="http://www.w3.org/2000/svg"><foreignObject><div xmlns="http://www.w3.org/1999/xhtml">' +
      '<iframe src="javascript:window.__pwned_iframe=1"></iframe></div></foreignObject></svg>',
    /<iframe[^>]*javascript:/i,
  ],
];

describe('sanitizeDiagramSvg — filtruje spúšťače', () => {
  it.each(ATTACKS)('%s', (_name, dirty, triggerInDirty) => {
    // (a) nečistý vstup skutočne obsahuje to, pred čím sa bránime
    expect(dirty).toMatch(triggerInDirty);

    const clean = sanitizeDiagramSvg(dirty);
    const host = mount(clean);
    try {
      // (b) vo výstupe nie je žiadny vykonateľný uzol
      expect(executableNodes(host)).toEqual([]);
      // (c) ani v reťazci nezostal `on…=` handler ani `javascript:`
      expect(clean).not.toMatch(/\son[a-z]+\s*=/i);
      expect(clean).not.toMatch(/javascript:/i);
    } finally {
      host.remove();
    }
  });

  it('nezabije legitímny obsah okolo útočníka', () => {
    const dirty =
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 100 40"><text>poradna</text>' +
      '<foreignObject><div xmlns="http://www.w3.org/1999/xhtml">label <b>bold</b>' +
      '<img src="x" onerror="boom()"></div></foreignObject></svg>';
    const host = mount(sanitizeDiagramSvg(dirty));
    try {
      expect(host.textContent).toContain('poradna');
      expect(host.querySelector('text')).not.toBeNull();
      expect(host.querySelector('foreignObject')).not.toBeNull();
      expect(host.querySelector('b')?.textContent).toBe('bold');
      // obrázok zostal (je to súčasť diagramu), len bez handleru
      expect(host.querySelector('img')).not.toBeNull();
      expect(host.querySelector('img')?.hasAttribute('onerror')).toBe(false);
    } finally {
      host.remove();
    }
  });

  it('doplní rel="noopener" k <a target="_blank"> a inak ho nechá na pokoji', () => {
    const dirty =
      '<svg xmlns="http://www.w3.org/2000/svg"><a href="https://example.com/" target="_blank"><text>a</text></a>' +
      '<a href="https://example.com/" rel="nofollow" target="_blank"><text>b</text></a></svg>';
    const host = mount(sanitizeDiagramSvg(dirty));
    try {
      const links = Array.from(host.querySelectorAll('a'));
      expect(links).toHaveLength(2);
      for (const link of links) {
        expect(link.getAttribute('rel')).toMatch(/noopener/);
      }
      expect(links[1].getAttribute('rel')).toMatch(/nofollow/);
    } finally {
      host.remove();
    }
  });

  it('nechá prázdny vstup prázdny (žiadne volanie, žiadna výnimka)', () => {
    expect(sanitizeDiagramSvg('')).toBe('');
  });
});

// ── 2. ZACHOVANIE: reálne diagramy sa nepokazia ───────────────────────────

/** Počet výskytov každého tagu a každého atribútu v markupu. */
function shape(markup: string): { tags: Record<string, number>; attrs: Record<string, number> } {
  const tags: Record<string, number> = {};
  const attrs: Record<string, number> = {};
  for (const m of markup.matchAll(/<\/?([a-zA-Z][a-zA-Z0-9:-]*)/g)) {
    const k = m[1].toLowerCase();
    tags[k] = (tags[k] ?? 0) + 1;
  }
  for (const m of markup.matchAll(/([a-zA-Z-][a-zA-Z0-9:-]*)\s*=\s*"/g)) {
    const k = m[1].toLowerCase();
    attrs[k] = (attrs[k] ?? 0) + 1;
  }
  return { tags, attrs };
}

/** Vrátí tagy/atribúty, ktoré sanitizácia oproti vstupu **odobrala**. */
function removedParts(dirty: string, clean: string): string[] {
  const a = shape(dirty);
  const b = shape(clean);
  const gone: string[] = [];
  for (const [k, n] of Object.entries(a.tags)) {
    if ((b.tags[k] ?? 0) < n) gone.push(`tag:${k} ${n}->${b.tags[k] ?? 0}`);
  }
  for (const [k, n] of Object.entries(a.attrs)) {
    if ((b.attrs[k] ?? 0) < n) gone.push(`attr:${k} ${n}->${b.attrs[k] ?? 0}`);
  }
  return gone;
}

describe('sanitizeDiagramSvg — nerozbiť diagram', () => {
  it('neoberie Mermaidovi nič, čo kreslí (šípky, viewBox, style, HTML labely)', () => {
    const clean = sanitizeDiagramSvg(MERMAID_FIXTURE);

    expect(removedParts(MERMAID_FIXTURE, clean)).toEqual([]);

    // šípky: definícia markeru aj väzba na ne musia ostať
    expect(clean).toContain('<marker');
    expect(clean).toContain('marker-end="url(#cap-flow_flowchart-v2-pointEnd)"');
    // rozmery a téma
    expect(clean).toContain('viewBox="-8 -8 116 36"');
    expect(clean).toContain('<style>');
    expect(clean).toContain('--mermaid-font-family');
    expect(clean).toContain('style="fill:#16281e;stroke:#34d399;stroke-width:1px;"');
    // HTML labely v foreignObject — to je presne to, čo by USE_PROFILES:{svg} zabil
    expect(clean).toContain('<foreignObject');
    expect(clean).toContain('xmlns="http://www.w3.org/1999/xhtml"');
    expect(clean).toMatch(/<br\s*\/?>/i);
    expect(clean).toContain('<b>bold</b>');
    // prístupové atribúty
    expect(clean).toContain('role="graphics-document document"');
    expect(clean).toContain('aria-roledescription="flowchart-v2"');
  });

  it('neoberie kroki SVG nič, čo kreslí (texty, čiary, rozmery, link)', () => {
    const clean = sanitizeDiagramSvg(KROKI_FIXTURE);

    // jediné dve obete, obe nepoužívané prehliadačom: atribút
    // `contentStyleType` a inštrukcia `<?plantuml …?>`. Test tvrdí presne
    // túto množinu, aby budúcá zmena konfigurácie neodstrhla niečo podstatné
    // (a zároveň pripomenula, prečo je v ADD_ATTR `target` — inak by sem
    // pribudol `attr:target 1->0` a link v diagrame by stratil cieľové okno).
    expect(removedParts(KROKI_FIXTURE, clean)).toEqual(['attr:contentstyletype 1->0']);
    expect(clean).not.toContain('<?plantuml');

    expect(clean).toContain('viewBox="0 0 117 151"');
    expect(clean).toContain('preserveAspectRatio="none"');
    expect(clean).toContain('style="width:117px;height:151px;background:#FFFFFF;"');
    expect(clean).toContain('stroke-dasharray="5,5"');
    expect(clean).toContain('font-family="sans-serif"');
    expect(clean).toContain('>Alice</text>');
    expect(clean).toContain('font-weight="700"');
    expect(clean).toContain('href="https://example.com/"');
  });

  it('je idempotentná — druhá sanitizácia už nemení nič', () => {
    for (const fixture of [MERMAID_FIXTURE, KROKI_FIXTURE]) {
      const once = sanitizeDiagramSvg(fixture);
      expect(sanitizeDiagramSvg(once)).toBe(once);
    }
  });

  it('nepoužíva prísny SVG profil, ktorý by zabil foreignObject labely', () => {
    // Kontrola samotnej konfigurácie: keby niekto „pre istotu" pridal
    // USE_PROFILES: { svg: true }, HTML deti foreignObject spadnú do SVG
    // menného priestoru a labely sa nekreslia.
    expect(DIAGRAM_SVG_CONFIG.USE_PROFILES).toBeUndefined();
    expect(DIAGRAM_SVG_CONFIG.NAMESPACE).toBeUndefined();
    expect(DIAGRAM_SVG_CONFIG.HTML_INTEGRATION_POINTS).toEqual({ foreignobject: true });

    // Overenie na DOM: div vnútri foreignObject musí ostať HTML element.
    const clean = sanitizeDiagramSvg(MERMAID_FIXTURE);
    const host = mount(clean);
    try {
      const div = host.querySelector('foreignObject > div');
      expect(div).not.toBeNull();
      expect(div?.namespaceURI).toBe('http://www.w3.org/1999/xhtml');
    } finally {
      host.remove();
    }
  });

  it('nezdieľa hook s ostatnými používateľmi DOMPurify dvakrát', () => {
    // Hook sa registruje raz; keby sa reťazil pri každom volaní,
    // rel="noopener" by pribúdalo opakovane.
    const link = '<svg xmlns="http://www.w3.org/2000/svg"><a href="https://x.test" target="_blank"><text>a</text></a></svg>';
    for (let i = 0; i < 5; i++) sanitizeDiagramSvg(link);
    const host = mount(sanitizeDiagramSvg(link));
    try {
      const rel = host.querySelector('a')?.getAttribute('rel') ?? '';
      expect(rel.match(/noopener/g)).toHaveLength(1);
    } finally {
      host.remove();
    }
  });
});

// Poistka, že test nepomýlil si vlastnú inštanciu DOMPurify s tou v produkte.
describe('DIAGRAM_SVG_CONFIG', () => {
  it('je to konfigurácia, ktorú DOMPurify akceptuje bez varovania', () => {
    expect(() => DOMPurify.sanitize('<svg></svg>', DIAGRAM_SVG_CONFIG)).not.toThrow();
  });
});
