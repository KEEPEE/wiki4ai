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
import './GraphView.css';

export interface GraphViewProps {
  documents: Document[];
  onNodeClick?: (docSlug: string) => void;
}

// Color palette for nodes based on document count
const NODE_COLORS = [
  '#4f46e5', // indigo
  '#0891b2', // cyan
  '#059669', // emerald
  '#d97706', // amber
  '#dc2626', // red
  '#7c3aed', // violet
  '#db2777', // pink
  '#2563eb', // blue,
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
  const [hoveredNode, setHoveredNode] = useState<{ node: GraphNode } | null>(null);
  const [tooltipPos, setTooltipPos] = useState({ x: 0, y: 0 });
  const [showLabels, setShowLabels] = useState(true);
  const graphRef = useRef<any>(null);
  const containerRef = useRef<HTMLDivElement>(null);
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
   * Custom node canvas object — draws the node circle AND its label permanently.
   * This replaces the default rendering so we can always show labels (or hide them).
   */
  const drawNode = useCallback(
    (node: GraphNode, ctx: CanvasRenderingContext2D, globalScale: number) => {
      // When "Labels on" is active, labels are ALWAYS visible regardless of zoom.
      // Previously there was a zoom-level threshold check that incorrectly hid labels
      // when the user was zoomed in (high globalScale), which caused the bug where
      // labels were invisible even with the toggle enabled.
      const shouldShowLabels = showLabels;

      // Draw node circle
      const radius = Math.max(8, 6 / globalScale); // Scale radius with zoom
      ctx.beginPath();
      ctx.arc(node.x || 0, node.y || 0, radius, 0, 2 * Math.PI);
      ctx.fillStyle = node.color || '#4f46e5';
      ctx.fill();

      // Draw label if toggle is on
      if (shouldShowLabels) {
        const fontSize = Math.max(8, 11 / globalScale); // Scale font with zoom
        ctx.font = `${fontSize}px system-ui, -apple-system, sans-serif`;
        ctx.textAlign = 'center';
        ctx.textBaseline = 'middle';

        const label = truncateLabel(node.label);
        const textWidth = ctx.measureText(label).width;

        // Background pill for readability
        const paddingX = 4 / globalScale;
        const paddingY = 2.5 / globalScale;
        const pillWidth = textWidth + paddingX * 2;
        const pillHeight = fontSize + paddingY * 2;

        ctx.fillStyle = 'rgba(255, 255, 255, 0.88)';
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

        // Draw text on top of the background
        const textColor = '#1a1a2e';
        ctx.fillStyle = textColor;
        ctx.fillText(label, node.x || 0, py + pillHeight / 2);
      }
    },
    [showLabels],
  );

  if (isEmpty) {
    return (
      <div className="graph-view">
        <header className="graph-header">
          <h2>Graph Visualization</h2>
          <p className="graph-subtitle">Document connections will appear here once documents have links</p>
        </header>
        <div className="graph-container graph-empty">
          <p>No document connections to display yet.</p>
          <p className="hint">Create documents with [[Wiki Link]] syntax to see connections in the graph.</p>
        </div>
      </div>
    );
  }

  return (
    <div className="graph-view">
      <header className="graph-header">
        <h2>Document Graph</h2>
        <p className="graph-subtitle">
          {documents.length} document{documents.length !== 1 ? 's' : ''} · {graphData.links.length} connection
          {graphData.links.length !== 1 ? 's' : ''}
        </p>
        <div className="label-toggle">
          <button
            onClick={() => setShowLabels((prev) => !prev)}
            aria-label={showLabels ? 'Hide node labels' : 'Show node labels'}
            title={showLabels ? 'Click to hide labels (hover only)' : 'Click to show labels always'}
            className={`label-toggle-btn ${showLabels ? 'active' : ''}`}
          >
            {showLabels ? '🏷️ Labels on' : '👁️ Hover only'}
          </button>
        </div>
      </header>
      <div ref={containerRef} className="graph-container" onMouseMove={handleMouseMove}>
        <ForceGraph2D
          ref={graphRef}
          graphData={graphData as any}
          width={canvasSize.width}
          height={canvasSize.height}
          nodeColor={(node: GraphNode) => node.color || '#4f46e5'}
          nodeRelSize={6}
          nodeCanvasObject={drawNode}
          linkColor={() => '#9ca3af'}
          linkWidth={1.5}
          backgroundColor="#fafafa"
          onNodeHover={handleNodeHover}
          onNodeClick={handleNodeClick}
          linkDirectionalArrowLength={3}
          linkDirectionalArrowRelPos={1}
          cooldownTicks={100}
          onEngineStop={handleEngineStop}
          d3Force={{
            charge: (charge) => charge.strength(-800),
            link: (link) => link.distance(150),
          }}
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
