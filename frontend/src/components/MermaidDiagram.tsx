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
          setSvg(renderedSvg);
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
