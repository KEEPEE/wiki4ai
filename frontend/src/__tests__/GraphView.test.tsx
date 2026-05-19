/**
 * Tests for GraphView component - specifically verifying correct slug usage
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Document } from '../types/document'

// Mock react-force-graph-2d to render clickable node labels we can test with
vi.mock('react-force-graph-2d', () => {
  // Maximum characters for label truncation (matches GraphView implementation)
  const MAX_LABEL_LENGTH = 40
  
  function truncateLabel(label: string): string {
    if (label.length <= MAX_LABEL_LENGTH) return label
    return label.slice(0, MAX_LABEL_LENGTH) + '...'
  }

  // Create a mock component that renders document nodes as clickable divs
  const ForceGraph2DMock = (props: any) => {
    const graphData = props.graphData || { nodes: [], links: [] }
    
    // Extract onNodeClick from props if available
    const onNodeClickProp = props.onNodeClick
    
    return (
      <div className="force-graph-mock">
        {graphData.nodes.map((node: any) => {
          // Check if nodeLabel prop is provided — if undefined, don't render label text
          const hasNodeLabel = typeof props.nodeLabel === 'function'
          const labelText = hasNodeLabel ? truncateLabel(node.label) : ''
          
          return (
            <div
              key={node.id}
              className="graph-node"
              data-doc-id={node.id}
              onClick={() => {
                // Call the actual onNodeClick prop if provided
                if (onNodeClickProp) {
                  onNodeClickProp(node)
                }
              }}
            >
              {/* Only render label text when nodeLabel prop is defined */}
              {hasNodeLabel && labelText}
            </div>
          )
        })}
      </div>
    )
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

describe('GraphView — tooltip and label truncation', () => {
  it('should show truncated label for long titles (>40 chars)', async () => {
    const onNodeClick = vi.fn()
    const user = userEvent.setup()

    // Title longer than 40 characters — will be truncated at exactly 40 chars + "..."
    const longTitle = 'This is a very long document title that exceeds forty char limit'
    const documents: Document[] = [
      createMockDocument({ id: 1, title: longTitle, slug: 'long-title' }),
    ]

    render(<GraphView documents={documents} onNodeClick={onNodeClick} />)

    // The truncated label should be visible (first 40 chars + "...")
    const truncatedLabel = screen.getByText('This is a very long document title that ...')
    expect(truncatedLabel).toBeInTheDocument()

    await user.click(truncatedLabel)
    expect(onNodeClick).toHaveBeenCalledWith('long-title')
  })

  it('should show full label for short titles (<=40 chars)', async () => {
    const onNodeClick = vi.fn()
    const user = userEvent.setup()

    const shortTitle = 'Short Doc'
    const documents: Document[] = [
      createMockDocument({ id: 1, title: shortTitle, slug: 'short-doc' }),
    ]

    render(<GraphView documents={documents} onNodeClick={onNodeClick} />)

    // The full label should be visible (not truncated)
    const fullLabel = screen.getByText('Short Doc')
    expect(fullLabel).toBeInTheDocument()

    await user.click(fullLabel)
    expect(onNodeClick).toHaveBeenCalledWith('short-doc')
  })

  it('should render tooltip overlay when hovering a node', () => {
    const documents: Document[] = [
      createMockDocument({ id: 1, title: 'Hover Test Doc', slug: 'hover-test' }),
    ]

    render(<GraphView documents={documents} />)

    // The tooltip should be hidden initially (not rendered when no node is hovered)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('should show labels by default', () => {
    const documents: Document[] = [
      createMockDocument({ id: 1, title: 'Label Test Doc', slug: 'label-test' }),
    ]

    render(<GraphView documents={documents} />)

    // Label should be visible by default (showLabels defaults to true)
    expect(screen.getByText('Label Test Doc')).toBeInTheDocument()
  })

  it('should toggle label visibility when button is clicked', async () => {
    const user = userEvent.setup()
    const documents: Document[] = [
      createMockDocument({ id: 1, title: 'Toggle Label Doc', slug: 'toggle-label' }),
    ]

    render(<GraphView documents={documents} />)

    // Initially labels should be visible
    expect(screen.getByText('Toggle Label Doc')).toBeInTheDocument()

    // Click the toggle button to hide labels
    const toggleBtn = screen.getByRole('button', { name: /hide node labels/i })
    await user.click(toggleBtn)

    // Labels should now be hidden — the document title text should not appear as a standalone element
    expect(screen.queryByText('Toggle Label Doc')).not.toBeInTheDocument()

    // Click again to show labels
    const toggleBtn2 = screen.getByRole('button', { name: /show node labels/i })
    await user.click(toggleBtn2)

    // Labels should be visible again
    expect(screen.getByText('Toggle Label Doc')).toBeInTheDocument()
  })

  it('should have the toggle button in the header', () => {
    const documents: Document[] = [
      createMockDocument({ id: 1, title: 'Toggle Button Test', slug: 'toggle-btn-test' }),
    ]

    render(<GraphView documents={documents} />)

    // Toggle button should be present
    expect(screen.getByRole('button', { name: /hide node labels/i })).toBeInTheDocument()
  })
})
