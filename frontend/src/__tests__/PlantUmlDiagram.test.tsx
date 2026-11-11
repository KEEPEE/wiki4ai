/**
 * Tests for the PlantUmlDiagram component (WIKI4AI-63).
 * Covers: loading state, successful SVG render via mocked fetch (URL + payload),
 * graceful degradation on network error and HTTP error (raw source + hint, no
 * crash), empty input, and re-render when code changes.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, act } from '@testing-library/react';
import PlantUmlDiagram from '../components/PlantUmlDiagram';
import { encodePlantUml } from '../utils/plantUmlEncoding';

const mockSvg = '<svg xmlns="http://www.w3.org/2000/svg" class="stub-plantuml-svg"><text>Mock PlantUML</text></svg>';

const CLASS_DIAGRAM = [
  '@startuml',
  'class Payment {',
  '  +string id',
  '}',
  'class Order {',
  '  +string id',
  '}',
  'Order "1" --> "*" Payment : has',
  '@enduml',
].join('\n');

function mockFetchOk() {
  return vi.fn(async (_url: string) => ({
    ok: true,
    status: 200,
    text: async () => mockSvg,
  }));
}

describe('PlantUmlDiagram', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  describe('Loading state', () => {
    it('shows the loading spinner while the SVG is fetched', async () => {
      let resolveFetch: (v: unknown) => void = () => {};
      const fetchMock = vi.fn(
        () =>
          new Promise((resolve) => {
            resolveFetch = resolve;
          }),
      );
      vi.stubGlobal('fetch', fetchMock);

      render(<PlantUmlDiagram code={CLASS_DIAGRAM} />);

      expect(screen.getByText('Loading diagram…')).toBeInTheDocument();

      // Resolve the fetch — loading disappears.
      await act(async () => {
        resolveFetch({ ok: true, status: 200, text: async () => mockSvg });
      });
      await waitFor(() => {
        expect(screen.queryByText('Loading diagram…')).not.toBeInTheDocument();
      });
    });
  });

  describe('Successful SVG render', () => {
    it('fetches the kroki SVG at /plantuml/svg/<encoded> and renders it into the DOM', async () => {
      const fetchMock = mockFetchOk();
      vi.stubGlobal('fetch', fetchMock);

      const { container } = render(<PlantUmlDiagram code={CLASS_DIAGRAM} />);

      await waitFor(() => {
        expect(container.querySelector('.plantuml-diagram svg')).not.toBeNull();
      });

      // Exactly one request, to the correct same-origin kroki URL.
      expect(fetchMock).toHaveBeenCalledTimes(1);
      const [url] = fetchMock.mock.calls[0];
      expect(url).toBe(`/plantuml/svg/${encodePlantUml(CLASS_DIAGRAM)}`);

      // The SVG text from kroki is in the DOM.
      expect(screen.getByText('Mock PlantUML')).toBeInTheDocument();
    });
  });

  describe('Error handling — graceful degradation', () => {
    it('shows raw source + hint when kroki is unreachable (network error), without crashing', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn(async () => {
          throw new TypeError('Failed to fetch');
        }),
      );

      const { container } = render(<PlantUmlDiagram code={CLASS_DIAGRAM} />);

      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeInTheDocument();
      });

      // The raw PlantUML source is shown as a fallback…
      const rawCode = container.querySelector('.plantuml-diagram__raw code');
      expect(rawCode?.textContent).toBe(CLASS_DIAGRAM);

      // …with a hint, and no SVG.
      expect(screen.getByText(/kroki service may be unavailable/i)).toBeInTheDocument();
      expect(container.querySelector('.plantuml-diagram svg')).toBeNull();
    });

    it('shows raw source + hint on HTTP error from kroki (e.g. 502)', async () => {
      vi.stubGlobal(
        'fetch',
        vi.fn(async () => ({ ok: false, status: 502, text: async () => 'Bad Gateway' })),
      );

      const { container } = render(<PlantUmlDiagram code={CLASS_DIAGRAM} />);

      await waitFor(() => {
        expect(screen.getByRole('alert')).toBeInTheDocument();
      });

      expect(screen.getByText(/HTTP 502/)).toBeInTheDocument();
      expect(container.querySelector('.plantuml-diagram__raw code')?.textContent).toBe(
        CLASS_DIAGRAM,
      );
    });
  });

  describe('Empty / whitespace input', () => {
    it('handles empty string gracefully without fetch or crash', async () => {
      const fetchMock = mockFetchOk();
      vi.stubGlobal('fetch', fetchMock);

      const { container } = render(<PlantUmlDiagram code="" />);

      await waitFor(() => {
        expect(screen.queryByText('Loading diagram…')).not.toBeInTheDocument();
      });

      expect(fetchMock).not.toHaveBeenCalled();
      expect(container.querySelector('.plantuml-diagram')).toBeInTheDocument();
    });

    it('handles whitespace-only string gracefully', async () => {
      const fetchMock = mockFetchOk();
      vi.stubGlobal('fetch', fetchMock);

      // Note: JSX attribute strings do not process escape sequences, so the
      // real newline must be passed via a JS expression.
      render(<PlantUmlDiagram code={'   \n  '} />);

      await waitFor(() => {
        expect(screen.queryByText('Loading diagram…')).not.toBeInTheDocument();
      });

      expect(fetchMock).not.toHaveBeenCalled();
    });
  });

  describe('Re-render on code change', () => {
    it('refetches when the code changes and does not leak stale results', async () => {
      const fetchMock = mockFetchOk();
      vi.stubGlobal('fetch', fetchMock);

      const { rerender } = render(<PlantUmlDiagram code={CLASS_DIAGRAM} />);

      await waitFor(() => {
        expect(screen.getByText('Mock PlantUML')).toBeInTheDocument();
      });

      const other = '@startuml\nactor A\nA --> B\n@enduml';
      rerender(<PlantUmlDiagram code={other} />);

      await waitFor(() => {
        expect(fetchMock).toHaveBeenCalledTimes(2);
      });
      expect(fetchMock.mock.calls[1][0]).toBe(`/plantuml/svg/${encodePlantUml(other)}`);
    });
  });
});

/**
 * WIKI4AI-198: kroki je cudzí proces, ktorému posielame ľubovoľný PlantUML
 * text od autora dokumentu, a jeho odpoveď putuje priamo do
 * `dangerouslySetInnerHTML`. Test tvrdí absenciu vykonateľného uzla v DOM —
 * nie to, že sa sanitizácia „volala".
 */
describe('PlantUmlDiagram — sank kroki SVG (WIKI4AI-198)', () => {
  /** Atribút `on…`, `<script>` alebo `javascript:` URL v navrstvenom DOM. */
  const triggers = (root: ParentNode): string[] => {
    const found: string[] = [];
    root.querySelectorAll('*').forEach((el) => {
      for (const attr of Array.from(el.attributes)) {
        if (/^on/i.test(attr.name)) found.push(`${el.tagName.toLowerCase()}@${attr.name}`);
        if (/^\s*javascript:/i.test(attr.value)) found.push(`${el.tagName.toLowerCase()}@${attr.name}=javascript:`);
      }
      if (el.tagName.toUpperCase() === 'SCRIPT') found.push('script');
    });
    return found;
  };

  // Kroki odpoveď s útočníkom: event handler, script a javascript: URL.
  const hostileKrokiResponse =
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 120 60">' +
    '<text fill="#000000" font-family="sans-serif" font-size="14">Mock PlantUML</text>' +
    '<g><image href="x" onload="window.__krokiSinkPwned=1"/>' +
    '<text onmouseover="window.__krokiSinkPwned=2">hover</text></g>' +
    '<a href="javascript:window.__krokiSinkPwned=3" target="_blank"><text>click</text></a>' +
    '<script>window.__krokiSinkPwned=4</script>' +
    '</svg>';

  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('nevyrenderuje on*/script/javascript: z kroki odpovede do DOM', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url: string) => ({
        ok: true,
        status: 200,
        text: async () => hostileKrokiResponse,
      })),
    );

    const { container } = render(<PlantUmlDiagram code={'@startuml\nA -> B\n@enduml'} />);

    await waitFor(() => {
      expect(container.querySelector('.plantuml-diagram__svg svg')).toBeInTheDocument();
    });

    const sink = container.querySelector('.plantuml-diagram__svg') as HTMLElement;
    expect(sink.textContent).toContain('Mock PlantUML');
    expect(triggers(sink)).toEqual([]);
    expect((window as unknown as Record<string, unknown>).__krokiSinkPwned).toBeUndefined();
  });

  it('nechá kroki SVG kresliť aj po sanitizácii (rozmery, text, link, štyly)', async () => {
    const realKrokiResponse =
      '<svg xmlns="http://www.w3.org/2000/svg" contentStyleType="text/css" height="151px"' +
      ' preserveAspectRatio="none" style="width:117px;height:151px;background:#FFFFFF;"' +
      ' version="1.1" viewBox="0 0 117 151" width="117px" zoomAndPan="magnify">' +
      '<g><line x1="17.5" x2="17.5" y1="30" y2="110.5" stroke="#000000" stroke-width="0.5"' +
      ' stroke-dasharray="5,5"/></g>' +
      '<g><text fill="#000000" font-family="sans-serif" font-size="14" x="12" y="24.9951">Alice</text></g>' +
      '<a href="https://example.com/" target="_blank"><text x="35" y="80">link</text></a>' +
      '</svg>';

    vi.stubGlobal(
      'fetch',
      vi.fn(async (_url: string) => ({ ok: true, status: 200, text: async () => realKrokiResponse })),
    );

    const { container } = render(<PlantUmlDiagram code={'@startuml\nA -> B\n@enduml'} />);

    await waitFor(() => {
      expect(container.querySelector('.plantuml-diagram__svg svg')).toBeInTheDocument();
    });

    const svg = container.querySelector('.plantuml-diagram__svg svg') as SVGSVGElement;
    expect(svg.getAttribute('viewBox')).toBe('0 0 117 151');
    expect(svg.getAttribute('preserveAspectRatio')).toBe('none');
    expect(svg.getAttribute('style')).toContain('background:#FFFFFF');
    expect(svg.querySelector('line')?.getAttribute('stroke-dasharray')).toBe('5,5');
    expect(svg.querySelector('text')?.textContent).toBe('Alice');
    const link = svg.querySelector('a');
    expect(link?.getAttribute('href')).toBe('https://example.com/');
    // cieľové okno ostáva a dostáva rel="noopener", aby sa nedostalo na opener
    expect(link?.getAttribute('target')).toBe('_blank');
    expect(link?.getAttribute('rel')).toBe('noopener');
  });
});
