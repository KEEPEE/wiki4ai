/**
 * Tests for GraphView component - specifically verifying correct slug usage
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Document } from '../types/document'

// Mock react-force-graph-2d to render clickable node labels we can test with
vi.mock('react-force-graph-2d', () => {
  // Create a mock component that renders document nodes as clickable divs
  const ForceGraph2DMock = (props: any) => {
    const graphData = props.graphData || { nodes: [], links: [] }
    return `
      <div class="force-graph-mock">
        ${graphData.nodes.map((node: any) => 
          `<div class="graph-node" data-doc-id="${node.id}" onclick="window.__onNodeClick?.(${JSON.stringify(node)})">${node.label}</div>`
        ).join('\n')}
      </div>
    `
  }
  
  return { default: ForceGraph2DMock }
})

import GraphView from '../components/GraphView'

const createMockDocument = (overrides: Partial<Document> = {}): Document => ({
  id: 1,
  title: 'Test Document',
  slug: 'test-document',
  content: '# Hello',
  projectId: 1,
  linkedDocuments: [],
  createdAt: '2026-05-19T00:00:00Z',
  updatedAt: '2026-05-19T00:00:00Z',
  ...overrides,
})

describe('GraphView — slug usage in onNodeClick', () => {
  it('should use doc.slug when available for navigation', async () => {
    const onNodeClick = vi.fn()
    const user = userEvent.setup()

    const documents: Document[] = [
      createMockDocument({ id: 1, title: 'Test Document', slug: 'test-document' }),
      createMockDocument({ id: 2, title: 'Úvod do AI', slug: 'uvod-do-ai' }),
    ]

    render(<GraphView documents={documents} onNodeClick={onNodeClick} />)

    // Click the first node (Test Document) — rendered as text in the graph label
    const testDocNode = screen.getByText('Test Document')
    await user.click(testDocNode)

    expect(onNodeClick).toHaveBeenCalledWith('test-document')
  })

  it('should use doc.slug for documents with special characters in title', async () => {
    const onNodeClick = vi.fn()
    const user = userEvent.setup()

    // Document with Slovak diacritics — title-based slug generation would produce wrong result
    const documents: Document[] = [
      createMockDocument({ id: 1, title: 'Úvod do AI', slug: 'uvod-do-ai' }),
    ]

    render(<GraphView documents={documents} onNodeClick={onNodeClick} />)

    // Click the node
    const node = screen.getByText('Úvod do AI')
    await user.click(node)

    expect(onNodeClick).toHaveBeenCalledWith('uvod-do-ai')
  })

  it('should fall back to title-based slug when doc.slug is undefined', async () => {
    const onNodeClick = vi.fn()
    const user = userEvent.setup()

    // Document without slug — should use fallback
    const documents: Document[] = [
      createMockDocument({ id: 1, title: 'Simple Doc', slug: undefined }),
    ]

    render(<GraphView documents={documents} onNodeClick={onNodeClick} />)

    const node = screen.getByText('Simple Doc')
    await user.click(node)

    expect(onNodeClick).toHaveBeenCalledWith('simple-doc')
  })

  it('should pass correct slug for each document in a multi-document graph', async () => {
    const onNodeClick = vi.fn()
    const user = userEvent.setup()

    const documents: Document[] = [
      createMockDocument({ id: 1, title: 'Doc A', slug: 'doc-a' }),
      createMockDocument({ id: 2, title: 'Doc B', slug: 'doc-b' }),
      createMockDocument({ id: 3, title: 'C++ Basics', slug: 'c-basics' }),
    ]

    render(<GraphView documents={documents} onNodeClick={onNodeClick} />)

    // Click each node and verify correct slug is passed
    const docA = screen.getByText('Doc A')
    await user.click(docA)
    expect(onNodeClick).toHaveBeenCalledWith('doc-a')

    const docB = screen.getByText('Doc B')
    await user.click(docB)
    expect(onNodeClick).toHaveBeenCalledWith('doc-b')

    // C++ has special chars — should use pre-computed slug, not "c-+-basics"
    const cppDoc = screen.getByText('C++ Basics')
    await user.click(cppDoc)
    expect(onNodeClick).toHaveBeenCalledWith('c-basics')
  })

  it('should render empty state when no documents exist', () => {
    render(<GraphView documents={[]} />)

    expect(screen.getByText('No document connections to display yet.')).toBeInTheDocument()
  })
})
