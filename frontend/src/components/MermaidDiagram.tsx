/**
 * MermaidDiagram component.
 * Renders Mermaid syntax code as an SVG diagram using the mermaid library.
 * Supports loading states, error handling, and multiple independent diagrams on one page.
 */

import React, { useState, useEffect, useRef, useCallback } from 'react';
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

// Global counter for unique diagram IDs
let diagramIdCounter = 0;

interface MermaidDiagramProps {
  /** Mermaid syntax code to render */
  code: string;
  /** Optional CSS class names */
  className?: string;
}

const MermaidDiagram: React.FC<MermaidDiagramProps> = ({ code, className }) => {
  const [svg, setSvg] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Each instance gets a stable unique ID
  const diagramId = useRef(`mermaid-diagram-${diagramIdCounter++}`);

  const renderDiagram = useCallback(async () => {
    if (!code || !code.trim()) {
      setSvg(null);
      setError(null);
      setIsLoading(false);
      return;
    }

    setIsLoading(true);
    setError(null);
    setSvg(null);

    try {
      // mermaid.render() returns { svg: string }
      const { svg: renderedSvg } = await mermaid.render(diagramId.current, code);
      setSvg(renderedSvg);
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to render diagram';
      setError(message);
    } finally {
      setIsLoading(false);
    }
  }, [code]);

  useEffect(() => {
    renderDiagram();
  }, [renderDiagram]);

  return (
    <div className={`mermaid-diagram ${className ?? ''}`}>
      {isLoading && (
        <div className="mermaid-diagram__loading" role="status">
          <div className="mermaid-diagram__spinner" />
          <span>Loading diagram…</span>
        </div>
      )}

      {error && !isLoading && (
        <div className="mermaid-diagram__error" role="alert">
          <strong>Diagram Error:</strong>
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
