/**
 * INTEGRATION tests for MarkdownPreview — REAL react-markdown renderer (WIKI4AI-62).
 *
 * Unlike `MarkdownPreview.test.tsx` (which mocks react-markdown), these tests run
 * the actual react-markdown v10 + remark-gfm pipeline under React 19, so the
 * custom `pre` component receives exactly the same element shape as in
 * production: a single inner <code> React element with
 * className="language-mermaid" and a text-string body.
 *
 * This is the regression guard for WIKI4AI-62: mermaid detection previously
 * always returned false because it inspected one level too deep (the code's
 * text string instead of the <code> element), and the mocked unit tests could
 * not catch it — they passed a wrapped <pre> shape that the buggy code happened
 * to handle.
 *
 * Only the `mermaid` library itself is stubbed (it needs a real browser DOM for
 * SVG layout); react-markdown, remark-gfm and MermaidDiagram are fully real.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';

// ── Stub ONLY the mermaid rendering engine (hoisted for vi.mock) ──────────

const escapeHtml = (s: string): string =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');

const { mockMermaidRender, mockApiRequest } = vi.hoisted(() => ({
  mockMermaidRender: vi.fn(async (_id: string, code: string) => ({
    svg: `<svg xmlns="http://www.w3.org/2000/svg" class="stub-mermaid-svg"><text>${escapeHtml(
      code,
    )}</text></svg>`,
    bindFunctions: () => {},
  })),
  mockApiRequest: vi.fn(),
}));

vi.mock('mermaid', () => ({
  default: {
    initialize: vi.fn(),
    render: (id: string, code: string) => mockMermaidRender(id, code),
  },
}));

// WIKI4AI-64: the authenticated API client is mocked too — react-markdown,
// remark-gfm and the real UploadedImage/imageApi pipeline stay fully real.
vi.mock('../services/apiClient', () => ({
  apiRequest: (url: string, options?: unknown) => mockApiRequest(url, options),
}));

// Import under test AFTER the mocks are registered.
import MarkdownPreview from '../components/MarkdownPreview';
import { clearUploadedImageCache } from '../services/imageApi';

const CLASS_DIAGRAM = [
  'classDiagram',
  '  class Payment {',
  '    +string id',
  '    +amount()',
  '  }',
  '  class Order {',
  '    +string id',
  '    +items()',
  '  }',
  '  Order "1" --> "many" Payment : has',
].join('\n');

const SEQUENCE_DIAGRAM = [
  'sequenceDiagram',
  '  participant Alice',
  '  participant Bob',
  '  participant Carol',
  '  Alice->>Bob: Hello',
  '  Bob->>Carol: Forward',
  '  Carol-->>Alice: Reply',
].join('\n');

describe('MarkdownPreview integration (real react-markdown, no mock)', () => {
  beforeEach(() => {
    mockMermaidRender.mockClear();
  });

  it('renders a ```mermaid block as an SVG diagram, not raw code text', async () => {
    const { container } = render(
      <MarkdownPreview content={`\`\`\`mermaid\n${CLASS_DIAGRAM}\n\`\`\``} />,
    );

    // The bug (WIKI4AI-62): the block was rendered as raw <pre><code class="language-mermaid">.
    expect(container.querySelector('pre code.language-mermaid')).toBeNull();

    // Detection must map the block to MermaidDiagram…
    const diagram = container.querySelector('.mermaid-diagram');
    expect(diagram).not.toBeNull();

    // …and the (stubbed) mermaid.render must complete into an SVG in the DOM.
    await waitFor(() => {
      expect(container.querySelector('.mermaid-diagram svg')).not.toBeNull();
    });
    const text = container.querySelector('.mermaid-diagram svg text');
    expect(text?.textContent).toContain('classDiagram');
  });

  it('passes the exact mermaid source to mermaid.render', async () => {
    render(<MarkdownPreview content={`\`\`\`mermaid\n${SEQUENCE_DIAGRAM}\n\`\`\``} />);

    await waitFor(() => {
      expect(mockMermaidRender).toHaveBeenCalledTimes(1);
    });
    // react-markdown keeps the trailing newline of the fenced block; mermaid
    // tolerates it — assert on trimmed content.
    const [, code] = mockMermaidRender.mock.calls[0];
    expect(code.trim()).toBe(SEQUENCE_DIAGRAM);
  });

  it('renders multiple mermaid blocks as separate SVG diagrams', async () => {
    const content = `# Diagrams

\`\`\`mermaid
${CLASS_DIAGRAM}
\`\`\`

Some text between diagrams.

\`\`\`mermaid
${SEQUENCE_DIAGRAM}
\`\`\``;

    const { container } = render(<MarkdownPreview content={content} />);

    await waitFor(() => {
      expect(container.querySelectorAll('.mermaid-diagram svg').length).toBe(2);
    });
    expect(mockMermaidRender).toHaveBeenCalledTimes(2);
  });

  it('renders a non-mermaid fenced block as a regular <pre><code>', () => {
    const { container } = render(
      <MarkdownPreview content={`\`\`\`javascript\nconst x = 42;\n\`\`\``} />,
    );

    const code = container.querySelector('pre > code.language-javascript');
    expect(code).not.toBeNull();
    // react-markdown preserves the fenced block's trailing newline.
    expect(code?.textContent?.trim()).toBe('const x = 42;');
    expect(container.querySelector('.mermaid-diagram')).toBeNull();
    expect(mockMermaidRender).not.toHaveBeenCalled();
  });

  it('renders a language-less fenced block as plain <pre><code>', () => {
    const { container } = render(<MarkdownPreview content={`\`\`\`\nsome plain code\n\`\`\``} />);

    expect(container.querySelector('pre > code')).not.toBeNull();
    expect(container.querySelector('.mermaid-diagram')).toBeNull();
    expect(mockMermaidRender).not.toHaveBeenCalled();
  });
});

/**
 * WIKI4AI-64 (Cesta A): uploaded images are stored at relative URLs of the form
 * /images/{projectSlug}/{uuid}.{ext} and served ONLY to authenticated users.
 * MarkdownPreview must intercept such <img> sources, fetch them through the
 * auth API client and render via a blob URL. External https URLs must pass
 * through completely unchanged (regression guard).
 */
describe('MarkdownPreview images (WIKI4AI-64, real react-markdown)', () => {
  const PNG_BYTES = new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]);
  let objectUrlCounter = 0;

  beforeEach(() => {
    mockApiRequest.mockReset();
    // jsdom does not implement object URLs — polyfill for the blob pipeline.
    URL.createObjectURL = vi.fn(() => `blob:mock-${++objectUrlCounter}`);
    URL.revokeObjectURL = vi.fn();
    clearUploadedImageCache(); // no blob-URL leaks between tests
  });

  it('intercepts an uploaded image (/images/...) and renders it via a blob URL', async () => {
    const src = '/images/test-proj/123e4567-e89b-42d3-a456-426614174000.png';
    mockApiRequest.mockResolvedValue({
      ok: true,
      status: 200,
      blob: async () => new Blob([PNG_BYTES], { type: 'image/png' }),
    });

    const { container } = render(<MarkdownPreview content={`![alt text](${src})`} />);

    // Never an <img> pointing at the raw /images/ path (that would 401).
    expect(container.querySelector('img[src^="/images/"]')).toBeNull();

    await waitFor(() => {
      const img = container.querySelector('img[alt="alt text"]');
      expect(img).not.toBeNull();
      expect(img?.getAttribute('src')?.startsWith('blob:')).toBe(true);
    });

    // Fetched exactly once, via the authenticated API endpoint.
    expect(mockApiRequest).toHaveBeenCalledTimes(1);
    expect(String(mockApiRequest.mock.calls[0][0])).toContain(`/api/v1${src}`);
  });

  it('leaves external https image URLs untouched (no fetch, same src)', () => {
    const external = 'https://example.com/photos/cat.png';
    const { container } = render(<MarkdownPreview content={`![cat](${external})`} />);

    const img = container.querySelector('img[alt="cat"]');
    expect(img).not.toBeNull();
    expect(img?.getAttribute('src')).toBe(external);
    expect(mockApiRequest).not.toHaveBeenCalled();
  });

  it('shows an error placeholder when the authenticated fetch fails', async () => {
    const src = '/images/test-proj/123e4567-e89b-42d3-a456-426614174000.png';
    mockApiRequest.mockResolvedValue({ ok: false, status: 404, blob: async () => new Blob() });

    const { container } = render(<MarkdownPreview content={`![gone](${src})`} />);

    await waitFor(() => {
      expect(container.querySelector('.uploaded-image--error')).not.toBeNull();
    });
    expect(container.querySelector('img[src^="blob:"]')).toBeNull();
  });

  it('renders an uploaded image next to a mermaid diagram without interference', async () => {
    const src = '/images/test-proj/123e4567-e89b-42d3-a456-426614174000.png';
    mockApiRequest.mockResolvedValue({
      ok: true,
      status: 200,
      blob: async () => new Blob([PNG_BYTES], { type: 'image/png' }),
    });

    const content = [
      `![shot](${src})`,
      '',
      '```mermaid',
      ...CLASS_DIAGRAM.split('\n'),
      '```',
    ].join('\n');

    const { container } = render(<MarkdownPreview content={content} />);

    await waitFor(() => {
      expect(container.querySelector('img[src^="blob:"]')).not.toBeNull();
      expect(container.querySelector('.mermaid-diagram svg')).not.toBeNull();
    });
  });
});
