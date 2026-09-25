/**
 * MermaidDiagram component.
 * Renders Mermaid syntax code as an SVG diagram using the mermaid library.
 * Supports loading states, error handling, and multiple independent diagrams on one page.
 *
 * Key design decisions:
 * - Stable ID based on content hash (not a global counter) to prevent duplicate ID errors
 *   when components rapidly mount/unmount during split-view editing
 * - useEffect cleanup removes mermaid's DOM element to prevent memory leaks
 * - Cancelled flag prevents race conditions where stale async results overwrite state
 */

import React, { useState, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import mermaid from 'mermaid';
import './MermaidDiagram.css';

// Initialize mermaid once — idempotent call
// Dark theme with custom variables for Nexaverse cyan/magenta neon aesthetic
mermaid.initialize({
  startOnLoad: false,
  theme: 'dark',
  securityLevel: 'loose',
  themeVariables: {
    primaryColor: '#1a1a2e',
    primaryTextColor: 'rgba(255, 255, 255, 0.85)',
    secondaryColor: '#16213e',
    tertiaryColor: '#0f3460',
    lineColor: '#00f0ff',
    fontSize: '14px',
  },
});

/**
 * Simple hash function for generating stable diagram IDs from content.
 * Uses DJB2 algorithm — fast, deterministic, good distribution.
 */
function simpleHash(str: string): string {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    const char = str.charCodeAt(i);
    hash = ((hash << 5) - hash) + char;
    hash |= 0; // Convert to 32-bit integer
  }
  return Math.abs(hash).toString(16);
}

/**
 * WIKI4AI-84: force the rendered SVG to its natural pixel size.
 *
 * Mermaid emits a `viewBox` plus either an explicit width or `width="100%"`.
 * With `width="100%"` the CSS rule `max-width: none` alone cannot restore the
 * intrinsic size, so wide diagrams (e.g. the Architecture Overview flowchart)
 * were downscaled to ~50% and labels became unreadable. Mermaid draws in px
 * units at 96 dpi, so the viewBox width/height ARE the natural pixel size —
 * rewrite the width/height attributes from it. SVGs without a viewBox (e.g.
 * test mocks) are returned unchanged. The wrapper then scrolls horizontally
 * when the diagram is wider than the column instead of shrinking it.
 */
export function withNaturalSize(renderedSvg: string): string {
  const vb = renderedSvg.match(/<svg[^>]*\bviewBox="([^"]+)"/);
  if (!vb) return renderedSvg;
  const parts = vb[1].trim().split(/[\s,]+/).map(Number);
  if (parts.length < 4 || !Number.isFinite(parts[2]) || !Number.isFinite(parts[3])) {
    return renderedSvg;
  }
  const w = Math.max(1, Math.round(parts[2]));
  const h = Math.max(1, Math.round(parts[3]));
  // Operate on the ROOT svg tag only (up to its closing '>'). Mermaid output
  // contains nested elements with their own width/height attributes (e.g. the
  // background <rect>), and a whole-string replace would clobber them instead
  // of sizing the root — found live on .219 during WIKI4AI-84 verification.
  const tagStart = vb.index ?? 0;
  const tagEnd = renderedSvg.indexOf('>', tagStart);
  if (tagEnd === -1) return renderedSvg;
  let tag = renderedSvg.slice(0, tagEnd + 1);
  if (/\bwidth="[^"]*"/.test(tag)) {
    tag = tag.replace(/\bwidth="[^"]*"/, `width="${w}"`);
  } else {
    tag = tag.replace(/<svg/, `<svg width="${w}"`);
  }
  if (/\bheight="[^"]*"/.test(tag)) {
    tag = tag.replace(/\bheight="[^"]*"/, `height="${h}"`);
  } else {
    tag = tag.replace(/<svg/, `<svg height="${h}"`);
  }
  return tag + renderedSvg.slice(tagEnd + 1);
}

interface MermaidDiagramProps {
  /** Mermaid syntax code to render */
  code: string;
  /** Optional CSS class names */
  className?: string;
}

const MermaidDiagram: React.FC<MermaidDiagramProps> = ({ code, className }) => {
  const { t } = useTranslation();
  const [svg, setSvg] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Stable ID based on content hash — same diagram code always produces the same ID.
  // This prevents duplicate ID errors when components rapidly mount/unmount during editing.
  const stableId = useMemo(
    () => `mermaid-${simpleHash(code.slice(0, 100))}`,
    [code],
  );

  useEffect(() => {
    // Handle empty/whitespace-only input
    if (!code?.trim()) {
      setSvg(null);
      setError(null);
      setIsLoading(false);
      return;
    }

    setIsLoading(true);
    setError(null);
    setSvg(null);

    let cancelled = false;

    mermaid.render(stableId, code)
      .then(({ svg: renderedSvg }) => {
        if (!cancelled) {
          // WIKI4AI-84: pin the SVG to its natural pixel size so the CSS can
          // scroll wide diagrams instead of downscaling them.
          setSvg(withNaturalSize(renderedSvg));
        }
      })
      .catch((err) => {
        if (!cancelled) {
          const message = err instanceof Error ? err.message : t('diagrams.renderFailed');
          setError(message);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setIsLoading(false);
        }
      });

    return () => {
      cancelled = true;
      // Clean up mermaid's temporary DOM element to prevent duplicate ID errors
      // and memory leaks when the component unmounts or code changes rapidly
      const el = document.getElementById(stableId);
      if (el) {
        el.remove();
      }
    };
  }, [code, stableId]);

  return (
    <div className={`mermaid-diagram ${className ?? ''}`}>
      {isLoading && (
        <div className="mermaid-diagram__loading" role="status">
          <div className="mermaid-diagram__spinner" />
          <span>{t('diagrams.loading')}</span>
        </div>
      )}

      {error && !isLoading && (
        <div className="mermaid-diagram__error" role="alert">
          <strong>{t('diagrams.errorLabel')}:</strong>
          <p>{error}</p>
        </div>
      )}

      {!isLoading && !error && svg && (
        <div
          className="mermaid-diagram__svg"
          dangerouslySetInnerHTML={{ __html: svg }}
        />
      )}
    </div>
  );
};

export default MermaidDiagram;
