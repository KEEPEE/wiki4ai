/**
 * GraphView component - Interactive force-directed graph visualization of document connections.
 * Uses react-force-graph to display documents as nodes and their links as edges.
 * 
 * Features:
 * - Permanent label rendering via nodeCanvasObject (labels always visible on canvas)
 * - Toggle between always-visible labels and hover-only labels (via tooltip)
 * - Zoom-to-fit after simulation settles so all nodes are in viewport
 * - Tooltip overlay on node hover showing full document title
 */

import React, { useMemo, useState, useRef, useCallback, useEffect } from 'react';
import ForceGraph2D from 'react-force-graph-2d';
import type { Document } from '../types/document';
import { generateSlug } from '../utils/slugify';
import { useTranslation } from 'react-i18next';
// WIKI4AI-87: SVG icons (no emoji — the container has no emoji font)
import { TagIcon, EyeIcon } from './icons';
import './GraphView.css';

export interface GraphViewProps {
  documents: Document[];
  onNodeClick?: (docSlug: string) => void;
}

// Neon color palette for dark background — Nexaverse inspired
const NODE_COLORS = [
  '#00f0ff', // cyan neon
  '#ff00d4', // magenta neon
  '#9d4edd', // purple neon
  '#0891b2', // teal
  '#059669', // emerald
  '#7c3aed', // violet
  '#db2777', // pink
  '#ffffff', // white
];

// Maximum characters to show in the node label before truncating
const MAX_LABEL_LENGTH = 40;

interface GraphNode {
  id: number;
  label: string;
  color: string;
  x?: number;
  y?: number;
}

interface GraphLink {
  source: number | GraphNode;
  target: number | GraphNode;
}

/**
 * Truncates a label to MAX_LABEL_LENGTH characters, adding "..." if needed.
 */
function truncateLabel(label: string): string {
  if (label.length <= MAX_LABEL_LENGTH) {
    return label;
  }
  return label.slice(0, MAX_LABEL_LENGTH) + '...';
}

const GraphView: React.FC<GraphViewProps> = ({ documents, onNodeClick }) => {
  const { t } = useTranslation();
  const [hoveredNode, setHoveredNode] = useState<{ node: GraphNode } | null>(null);
  const [tooltipPos, setTooltipPos] = useState({ x: 0, y: 0 });
  const [showLabels, setShowLabels] = useState(true);
  const graphRef = useRef<any>(null);
  const containerRef = useRef<HTMLDivElement>(null);
  // Track current zoom scale for dynamic arrow sizing.
  // Using a ref avoids re-rendering the entire component on every zoom event,
  // while still giving the linkCanvasObject access to the latest scale value.
  const currentZoomScale = useRef(1);
  // Explicit canvas dimensions measured via ResizeObserver.
  // This prevents CSS scaling from distorting the internal coordinate system,
  // which caused labels and hover detection to appear at wrong positions.
  const [canvasSize, setCanvasSize] = useState({ width: 800, height: 600 });

  // Measure container dimensions using ResizeObserver for accurate canvas sizing
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    // Set initial size from current container dimensions
    setCanvasSize({
      width: Math.max(1, Math.floor(container.clientWidth)),
      height: Math.max(1, Math.floor(container.clientHeight)),
    });

    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        const { width, height } = entry.contentRect;
        setCanvasSize(prev =>
          Math.abs(prev.width - width) > 1 || Math.abs(prev.height - height) > 1
            ? { width: Math.max(1, Math.floor(width)), height: Math.max(1, Math.floor(height)) }
            : prev
        );
      }
    });

    observer.observe(container);
    return () => observer.disconnect();
  }, []);

  // Configure D3 force simulation: increase node repulsion and link distance
  // so nodes are spread out more for better readability.
  useEffect(() => {
    const graph = graphRef.current;
    if (!graph) return;

    // Stronger charge (repulsion) pushes nodes further apart
    graph.d3Force('charge').strength(-800);
    // Longer link distance keeps connected nodes at a comfortable spacing
    graph.d3Force('link').distance(150);
  }, []);

  const graphData = useMemo(() => {
    if (documents.length === 0) {
      return { nodes: [], links: [] };
    }

    // Create nodes from documents
    const nodes: GraphNode[] = documents.map((doc, index) => ({
      id: doc.id,
      label: doc.title,
      color: NODE_COLORS[index % NODE_COLORS.length],
    }));

    // Create links from linkedDocuments references
    const links: GraphLink[] = [];
    for (const doc of documents) {
      if (doc.linkedDocuments && Array.isArray(doc.linkedDocuments)) {
        for (const targetId of doc.linkedDocuments) {
          // Avoid duplicate links and self-links
          if (targetId !== doc.id) {
            links.push({ source: doc.id, target: targetId });
          }
        }
      }
    }

    return { nodes, links };
  }, [documents]);

  const isEmpty = graphData.nodes.length === 0;

  /**
   * After the force simulation settles, zoom to fit all nodes in viewport.
   * This ensures no nodes appear off-screen.
   */
  const handleEngineStop = useCallback(() => {
    if (graphRef.current && !isEmpty) {
      // Use zoomToFit with padding so nodes are nicely centered and visible
      graphRef.current.zoomToFit(400, 60);
    }
  }, [isEmpty]);

  /**
   * Track zoom level changes for dynamic arrow sizing.
   * Updates the ref so linkCanvasObject can read the current scale
   * without triggering a full component re-render on every zoom event.
   */
  const handleZoom = useCallback(() => {
    if (graphRef.current) {
      currentZoomScale.current = graphRef.current.graph2dZoom?.()?.k ?? 1;
    }
  }, []);

  /**
   * Custom link canvas object — draws the connection line AND directional arrow.
   * Arrow size scales with zoom level so it remains visible at all zoom levels.
   * This replaces the default linkDirectionalArrowLength/RelPos which use fixed pixel values.
   */
  const drawLink = useCallback(
    (link: any, ctx: CanvasRenderingContext2D, globalScale: number) => {
      const sx = link.source.x;
      const sy = link.source.y;
      const tx = link.target.x;
      const ty = link.target.y;

      // Line width: stays visually constant across zoom levels (canvas context is already scaled)
      const lineWidth = Math.max(1.2, 2.5 / globalScale);

      // Arrow size: clamp to a minimum at high zoom so arrows stay visible.
      // The canvas context is already scaled by globalScale, so dividing by it
      // would make arrows invisible when zoomed in (high scale).
      // We use sqrt scaling for moderate zoom-out compensation, then clamp
      // with smooth transitions to prevent arrows from disappearing or jumping.
      const arrowBaseSize = 12;

      let arrowLength: number;
      if (globalScale <= 1) {
        // Zoomed out or 1:1 — scale up proportionally (sqrt for moderate feel)
        arrowLength = Math.max(4, arrowBaseSize / Math.pow(globalScale, 0.5));
      } else if (globalScale < 8) {
        // Moderate zoom-in — gradually shrink but keep visible
        const t = (globalScale - 1) / 7; // 0..1 over range [1, 8)
        arrowLength = Math.max(6, arrowBaseSize * (1 - t * 0.5));
      } else if (globalScale < 30) {
        // High zoom — clamp to minimum so arrows never disappear
        arrowLength = 14;
      } else {
        // Very high zoom (>30x) — smoothly increase size so arrows stay clear
        const extraZoom = globalScale - 30;
        arrowLength = Math.min(28, 14 + extraZoom * 0.3);
      }

      arrowLength = Math.max(4, arrowLength); // absolute safety floor

      const arrowWidth = arrowLength * 0.5;

      // Draw the connection line — cyan at 30% opacity (Nexaverse style)
      const dx = tx - sx;
      const dy = ty - sy;
      const dist = Math.sqrt(dx * dx + dy * dy);
      if (dist === 0) return;

      const nx = dx / dist; // normalized direction x
      const ny = dy / dist; // normalized direction y

      // Line ends before the arrow starts
      const lineEndX = tx - nx * arrowLength;
      const lineEndY = ty - ny * arrowLength;

      ctx.beginPath();
      ctx.moveTo(sx, sy);
      ctx.lineTo(lineEndX, lineEndY);
      ctx.strokeStyle = 'rgba(0, 240, 255, 0.3)'; // cyan at 30% opacity
      ctx.lineWidth = lineWidth;
      ctx.stroke();

      // Draw the directional arrowhead — neon cyan (primary color)
      const perpX = -ny; // perpendicular x
      const perpY = nx;  // perpendicular y

      ctx.beginPath();
      ctx.moveTo(tx, ty);
      ctx.lineTo(
        tx - nx * arrowLength + perpX * arrowWidth,
        ty - ny * arrowLength + perpY * arrowWidth
      );
      ctx.lineTo(
        tx - nx * arrowLength - perpX * arrowWidth,
        ty - ny * arrowLength - perpY * arrowWidth
      );
      ctx.closePath();
      ctx.fillStyle = '#00f0ff'; // var(--primary) neon cyan
      ctx.fill();
    },
    [],
  );

  /**
   * Handle node hover — track the hovered node for tooltip rendering.
   */
  const handleNodeHover = (node: GraphNode | null) => {
    setHoveredNode(node ? { node } : null);
  };

  /**
   * Track mouse position within graph container for tooltip positioning.
   */
  const handleMouseMove = (e: React.MouseEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    setTooltipPos({ x: e.clientX - rect.left, y: e.clientY - rect.top });
  };

  /**
   * Handle node click — find the document and navigate using its actual slug.
   */
  const handleNodeClick = (node: GraphNode) => {
    const docId = node.id;
    const doc = documents.find((d) => d.id === docId);
    if (doc && onNodeClick) {
      const slug = doc.slug ?? generateSlug(doc.title);
      onNodeClick(slug);
    }
  };

  /**
   * Custom node canvas object — draws the node circle with neon glow AND its label permanently.
   * Dark theme: white labels on dark background, cyan/magenta glow effects.
   */
  const drawNode = useCallback(
    (node: GraphNode, ctx: CanvasRenderingContext2D, globalScale: number) => {
      // When "Labels on" is active, labels are ALWAYS visible regardless of zoom.
      const shouldShowLabels = showLabels;

      // Draw node circle with neon glow effect
      const radius = Math.max(8, 6 / globalScale); // Scale radius with zoom
      
      // Neon glow: use shadowBlur for cyan/magenta glow around each node
      ctx.shadowBlur = 15 / globalScale; // Scale glow with zoom
      ctx.shadowColor = '#00f0ff'; // Cyan glow (var(--glow-cyan))
      
      ctx.beginPath();
      ctx.arc(node.x || 0, node.y || 0, radius, 0, 2 * Math.PI);
      ctx.fillStyle = node.color || '#00f0ff';
      ctx.fill();

      // Draw label if toggle is on — dark theme styling (Nexaverse)
      if (shouldShowLabels) {
        const fontSize = Math.max(8, 11 / globalScale); // Scale font with zoom
        
        // Reset shadow for text rendering to keep it crisp
        ctx.shadowBlur = 0;
        ctx.shadowColor = 'transparent';
        
        // Use Outfit font family (Nexaverse style)
        ctx.font = `${fontSize}px 'Outfit', system-ui, -apple-system, sans-serif`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';

        const label = truncateLabel(node.label);
        const textWidth = ctx.measureText(label).width;

        // Background pill for readability on dark background
        const paddingX = 4 / globalScale;
        const paddingY = 2.5 / globalScale;
        const pillWidth = textWidth + paddingX * 2;
        const pillHeight = fontSize + paddingY * 2;

        // Semi-transparent dark pill background
        ctx.fillStyle = 'rgba(10, 10, 18, 0.75)';
        ctx.beginPath();
        // Rounded rectangle for the label background
        const cornerRadius = Math.min(pillHeight / 2, pillWidth / 4);
        const px = (node.x || 0) - pillWidth / 2;
        const py = (node.y || 0) + radius + 3 / globalScale;

        ctx.moveTo(px + cornerRadius, py);
        // Top edge
        ctx.lineTo(px + pillWidth - cornerRadius, py);
        // Top-right corner
        ctx.quadraticCurveTo(px + pillWidth, py, px + pillWidth, py + cornerRadius);
        // Right edge
        ctx.lineTo(px + pillWidth, py + pillHeight - cornerRadius);
        // Bottom-right corner
        ctx.quadraticCurveTo(px + pillWidth, py + pillHeight, px + pillWidth - cornerRadius, py + pillHeight);
        // Bottom edge
        ctx.lineTo(px + cornerRadius, py + pillHeight);
        // Bottom-left corner
        ctx.quadraticCurveTo(px, py + pillHeight, px, py + pillHeight - cornerRadius);
        // Left edge
        ctx.lineTo(px, py + cornerRadius);
        // Top-left corner
        ctx.quadraticCurveTo(px, py, px + cornerRadius, py);
        ctx.closePath();
        ctx.fill();

        // Draw text — white at 80% opacity (Nexaverse style)
        const textColor = 'rgba(255, 255, 255, 0.8)';
        ctx.fillStyle = textColor;
        ctx.fillText(label, node.x || 0, py + pillHeight / 2);
      }

      // Reset shadow for next draw operations
      ctx.shadowBlur = 0;
      ctx.shadowColor = 'transparent';
    },
    [showLabels],
  );

  if (isEmpty) {
    return (
      <div className="graph-view">
        <header className="graph-header">
          <h2>{t('graph.visualizationTitle')}</h2>
          <p className="graph-subtitle">{t('graph.emptySubtitle')}</p>
        </header>
        <div className="graph-container graph-empty">
          <p>{t('graph.emptyState')}</p>
          <p className="hint">{t('graph.emptyHint')}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="graph-view">
      <header className="graph-header">
        <h2>{t('graph.documentGraph')}</h2>
        <p className="graph-subtitle">
          {t('graph.statsSummary', { docCount: documents.length, linkCount: graphData.links.length })}
        </p>
        <div className="label-toggle">
          <button
            onClick={() => setShowLabels((prev) => !prev)}
            aria-label={showLabels ? t('graph.hideLabels') : t('graph.showLabels')}
            title={showLabels ? t('graph.hideLabelsTitle') : t('graph.showLabelsTitle')}
            className={`label-toggle-btn ${showLabels ? 'active' : ''}`}
          >
            {showLabels ? (
              <>
                <TagIcon size={14} /> {t('graph.labelsOn')}
              </>
            ) : (
              <>
                <EyeIcon size={14} /> {t('graph.hoverOnly')}
              </>
            )}
          </button>
        </div>
      </header>
      <div ref={containerRef} className="graph-container" onMouseMove={handleMouseMove}>
          <ForceGraph2D
            ref={graphRef}
            graphData={graphData as any}
            width={canvasSize.width}
            height={canvasSize.height}
            nodeColor={(node: GraphNode) => node.color || '#00f0ff'}
            nodeRelSize={6}
            nodeCanvasObject={drawNode}
            linkWidth={0}
            linkCanvasObject={drawLink}
            backgroundColor="#0a0a12"
            onNodeHover={handleNodeHover}
            onNodeClick={handleNodeClick}
            onZoom={handleZoom}
            cooldownTicks={100}
            onEngineStop={handleEngineStop}
          />

        {/* Tooltip overlay — positioned near the cursor when hovering a node */}
        {hoveredNode && (
          <div
            className="graph-tooltip"
            style={{
              left: `${tooltipPos.x}px`,
              top: `${tooltipPos.y}px`,
            }}
          >
            <div className="graph-tooltip-content">
              <div className="graph-tooltip-title">{hoveredNode.node.label}</div>
              {hoveredNode.node.label.length > MAX_LABEL_LENGTH && (
                <div className="graph-tooltip-truncated">
                  {truncateLabel(hoveredNode.node.label)}
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
};

export default GraphView;
