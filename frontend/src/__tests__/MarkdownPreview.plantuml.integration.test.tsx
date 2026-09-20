/**
 * INTEGRATION tests for PlantUML block detection in MarkdownPreview (WIKI4AI-63).
 *
 * Runs the REAL react-markdown v10 + remark-gfm pipeline under React 19 (same
 * approach as MarkdownPreview.integration.test.tsx from WIKI4AI-62), so the
 * custom `pre` component receives exactly the production element shape: a
 * single inner <code> React element with className="language-plantuml".
 *
 * Only the kroki HTTP call (fetch) is stubbed; react-markdown, remark-gfm and
 * PlantUmlDiagram are fully real. The mermaid engine stays unmocked here — the
 * mermaid regression is covered by MarkdownPreview.integration.test.tsx, which
 * remains green and unchanged.
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import MarkdownPreview from '../components/MarkdownPreview';

const mockSvg =
  '<svg xmlns="http://www.w3.org/2000/svg" class="stub-plantuml-svg"><text>Mock PlantUML</text></svg>';

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

const USE_CASE_DIAGRAM = [
  '@startuml',
  'actor Customer',
  'use case "Place order" as UC1',
  'Customer --> UC1',
  '@enduml',
].join('\n');

describe('MarkdownPreview plantuml integration (real react-markdown, no mock)', () => {
  beforeEach(() => {
    vi.unstubAllGlobals();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('renders a ```plantuml block as a PlantUmlDiagram, not raw code text', async () => {
    const fetchMock = vi.fn(async (_url: string) => ({ ok: true, status: 200, text: async () => mockSvg }));
    vi.stubGlobal('fetch', fetchMock);

    const { container } = render(
      <MarkdownPreview content={`\`\`\`plantuml\n${CLASS_DIAGRAM}\n\`\`\``} />,
    );

    // Detection must not fall through to the raw <pre><code class="language-plantuml">.
    expect(container.querySelector('pre code.language-plantuml')).toBeNull();

    const diagram = container.querySelector('.plantuml-diagram');
    expect(diagram).not.toBeNull();

    // The (stubbed) kroki fetch completes into an SVG in the DOM.
    await waitFor(() => {
      expect(container.querySelector('.plantuml-diagram svg')).not.toBeNull();
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it('passes the exact plantuml source to the kroki URL', async () => {
    const fetchMock = vi.fn(async (_url: string) => ({ ok: true, status: 200, text: async () => mockSvg }));
    vi.stubGlobal('fetch', fetchMock);

    render(<MarkdownPreview content={`\`\`\`plantuml\n${USE_CASE_DIAGRAM}\n\`\`\``} />);

    await waitFor(() => {
      expect(fetchMock).toHaveBeenCalledTimes(1);
    });

    // The URL must end with the base64url encoding of the exact source
    // (react-markdown keeps the fenced block's trailing newline — kroki tolerates it).
    const [url] = fetchMock.mock.calls[0];
    expect(url.startsWith('/plantuml/svg/')).toBe(true);
    const encoded = url.slice('/plantuml/svg/'.length);
    expect(encoded).toMatch(/^[A-Za-z0-9_-]+$/);
    // Decode check: inflate the URL payload and compare with the source.
    const { inflate } = await import('pako');
    const bytes = Uint8Array.from(atob(encoded.replace(/-/g, '+').replace(/_/g, '/')), (c) =>
      c.charCodeAt(0),
    );
    const decoded = new TextDecoder().decode(inflate(bytes));
    expect(decoded.trim()).toBe(USE_CASE_DIAGRAM);
  });

  it('renders multiple plantuml blocks as separate diagrams', async () => {
    const fetchMock = vi.fn(async (_url: string) => ({ ok: true, status: 200, text: async () => mockSvg }));
    vi.stubGlobal('fetch', fetchMock);

    const content = `# Diagrams

\`\`\`plantuml
${CLASS_DIAGRAM}
\`\`\`

Text between diagrams.

\`\`\`plantuml
${USE_CASE_DIAGRAM}
\`\`\``;

    const { container } = render(<MarkdownPreview content={content} />);

    await waitFor(() => {
      expect(container.querySelectorAll('.plantuml-diagram svg').length).toBe(2);
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });

  it('degrades gracefully to raw source when kroki is down (no crash)', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn(async () => {
        throw new TypeError('Failed to fetch');
      }),
    );

    const { container } = render(
      <MarkdownPreview content={`\`\`\`plantuml\n${CLASS_DIAGRAM}\n\`\`\``} />,
    );

    await waitFor(() => {
      expect(container.querySelector('.plantuml-diagram__error')).not.toBeNull();
    });
    // Raw source is shown as fallback (react-markdown preserves the fenced
    // block's trailing newline — assert on trimmed content).
    const rawCode = container.querySelector('.plantuml-diagram__raw code');
    expect(rawCode?.textContent?.trim()).toBe(CLASS_DIAGRAM);
  });

  it('renders a non-plantuml fenced block as a regular <pre><code> (no fetch)', () => {
    const fetchMock = vi.fn(async (_url: string) => ({ ok: true, status: 200, text: async () => mockSvg }));
    vi.stubGlobal('fetch', fetchMock);

    const { container } = render(
      <MarkdownPreview content={`\`\`\`python\nprint("hi")\n\`\`\``} />,
    );

    expect(container.querySelector('pre > code.language-python')).not.toBeNull();
    expect(container.querySelector('.plantuml-diagram')).toBeNull();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
