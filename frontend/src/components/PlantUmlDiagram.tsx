/**
 * PlantUmlDiagram component (WIKI4AI-63).
 * Renders PlantUML syntax code as an SVG diagram via the self-hosted kroki
 * service (nginx proxies /plantuml/ → kroki container in the docker network).
 *
 * Key design decisions (mirrors MermaidDiagram):
 * - Encoding: zlib deflate + base64url (see utils/plantUmlEncoding.ts)
 * - Loading state while the SVG is fetched from kroki
 * - Graceful degradation on error (kroki down / invalid syntax): shows a hint
 *   plus the raw PlantUML source in a <pre> block — never crashes the page
 * - Cancelled flag prevents race conditions where stale async results
 *   overwrite state when code changes rapidly during split-view editing
 */

import React, { useState, useEffect } from 'react';
import { plantUmlSvgUrl } from '../utils/plantUmlEncoding';
import './PlantUmlDiagram.css';

interface PlantUmlDiagramProps {
  /** PlantUML syntax code to render */
  code: string;
  /** Optional CSS class names */
  className?: string;
}

const PlantUmlDiagram: React.FC<PlantUmlDiagramProps> = ({ code, className }) => {
  const [svg, setSvg] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

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

    fetch(plantUmlSvgUrl(code))
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`Kroki server returned HTTP ${response.status}`);
        }
        const svgText = await response.text();
        if (!cancelled) {
          setSvg(svgText);
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          // Graceful degradation: kroki unavailable or invalid diagram syntax —
          // show the raw source instead of crashing.
          const message = err instanceof Error ? err.message : 'Failed to render diagram';
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
    };
  }, [code]);

  return (
    <div className={`plantuml-diagram ${className ?? ''}`}>
      {isLoading && (
        <div className="plantuml-diagram__loading" role="status">
          <div className="plantuml-diagram__spinner" />
          <span>Loading diagram…</span>
        </div>
      )}

      {error && !isLoading && (
        <div className="plantuml-diagram__error" role="alert">
          <strong>PlantUML diagram not rendered:</strong>
          <p>{error}</p>
          <p className="plantuml-diagram__hint">
            The self-hosted kroki service may be unavailable, or the diagram source has a syntax
            error. Raw PlantUML source:
          </p>
          <pre className="plantuml-diagram__raw">
            <code>{code}</code>
          </pre>
        </div>
      )}

      {!isLoading && !error && svg && (
        <div
          className="plantuml-diagram__svg"
          dangerouslySetInnerHTML={{ __html: svg }}
        />
      )}
    </div>
  );
};

export default PlantUmlDiagram;
