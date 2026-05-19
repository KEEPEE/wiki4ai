/**
 * GraphView component - Interactive force-directed graph visualization of document connections.
 * Uses react-force-graph to display documents as nodes and their links as edges.
 */

import React, { useMemo } from 'react';
import ForceGraph2D from 'react-force-graph-2d';
import './GraphView.css';

interface Document {
  id: number;
  title: string;
  content?: string | null;
  projectId: number;
  linkedDocuments?: number[];
  createdAt: string;
  updatedAt: string;
}

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
  '#2563eb', // blue
];

interface GraphNode {
  id: number;
  label: string;
  color: string;
}

interface GraphLink {
  source: number | GraphNode;
  target: number | GraphNode;
}

const GraphView: React.FC<GraphViewProps> = ({ documents, onNodeClick }) => {
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
      </header>
      <div className="graph-container">
        <ForceGraph2D
          graphData={graphData as any}
          nodeLabel="label"
          nodeColor={(node: GraphNode) => node.color || '#4f46e5'}
          nodeRelSize={6}
          linkColor={() => '#9ca3af'}
          linkWidth={1.5}
          backgroundColor="#fafafa"
          onNodeClick={(node: GraphNode) => {
            const docId = node.id;
            // Find the document and navigate to it
            const doc = documents.find((d) => d.id === docId);
            if (doc && onNodeClick) {
              onNodeClick(doc.title.toLowerCase().replace(/\s+/g, '-'));
            }
          }}
          linkDirectionalArrowLength={3}
          linkDirectionalArrowRelPos={1}
          cooldownTicks={100}
        />
      </div>
    </div>
  );
};

export default GraphView;
