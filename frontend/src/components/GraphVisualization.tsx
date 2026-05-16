/**
 * Graph Visualization component.
 * Displays document links as a graph/nodes visualization.
 * Uses SVG for rendering nodes and edges.
 */

import React, { useMemo } from 'react';
import './GraphVisualization.css';

interface Node {
  id: number;
  label: string;
  x: number;
  y: number;
}

interface Edge {
  source: number;
  target: number;
}

interface GraphVisualizationProps {
  nodes?: Node[];
  edges?: Edge[];
}

const defaultNodes: Node[] = [
  { id: 1, label: 'Home', x: 400, y: 50 },
  { id: 2, label: 'Getting Started', x: 200, y: 200 },
  { id: 3, label: 'API Reference', x: 600, y: 200 },
  { id: 4, label: 'Models', x: 150, y: 380 },
  { id: 5, label: 'Services', x: 400, y: 380 },
  { id: 6, label: 'Controllers', x: 650, y: 380 },
];

const defaultEdges: Edge[] = [
  { source: 1, target: 2 },
  { source: 1, target: 3 },
  { source: 2, target: 4 },
  { source: 2, target: 5 },
  { source: 3, target: 5 },
  { source: 3, target: 6 },
];

const GraphVisualization: React.FC<GraphVisualizationProps> = ({
  nodes = defaultNodes,
  edges = defaultEdges,
}) => {
  const svgContent = useMemo(() => {
    return (
      <svg className="graph-svg" viewBox="0 0 800 500">
        {/* Draw edges */}
        {edges.map((edge, index) => {
          const sourceNode = nodes.find(n => n.id === edge.source);
          const targetNode = nodes.find(n => n.id === edge.target);
          if (!sourceNode || !targetNode) return null;

          return (
            <line
              key={`edge-${index}`}
              x1={sourceNode.x}
              y1={sourceNode.y}
              x2={targetNode.x}
              y2={targetNode.y}
              className="graph-edge"
            />
          );
        })}

        {/* Draw nodes */}
        {nodes.map((node) => (
          <g key={`node-${node.id}`} className="graph-node-group">
            <circle
              cx={node.x}
              cy={node.y}
              r={30}
              className="graph-node"
            />
            <text
              x={node.x}
              y={node.y + 5}
              textAnchor="middle"
              className="graph-node-label"
            >
              {node.label.length > 12 ? node.label.slice(0, 10) + '...' : node.label}
            </text>
          </g>
        ))}
      </svg>
    );
  }, [nodes, edges]);

  return (
    <div className="graph-visualization">
      <header className="graph-header">
        <h2>Document Graph</h2>
        <p className="graph-subtitle">Visualizing connections between documents</p>
      </header>
      <div className="graph-container">
        {svgContent}
      </div>
    </div>
  );
};

export default GraphVisualization;
