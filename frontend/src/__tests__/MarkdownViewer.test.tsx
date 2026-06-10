/**
 * Tests for MarkdownViewer component and utility functions
 */

import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import MarkdownViewer from '../components/MarkdownViewer'

describe('slugify utility', () => {
  it('should handle Slovak diacritics in wiki links', async () => {
    const onLinkClick = vi.fn()
    
    render(
      <MarkdownViewer 
        content="Text s [[Špeciálny]] odkazom" 
        onLinkClick={onLinkClick}
      />
    )

    // Wiki link appears in both content (via MarkdownPreview) and links section
    const links = screen.getAllByText('Špeciálny')
    expect(links.length).toBeGreaterThanOrEqual(1)
  })
})

describe('MarkdownViewer', () => {
  describe('Content rendering', () => {
    it('should render heading levels', () => {
      const content = `# H1
## H2
### H3`

      render(<MarkdownViewer content={content} />)

      expect(screen.getByText('H1')).toBeInTheDocument()
      expect(screen.getByText('H2')).toBeInTheDocument()
      expect(screen.getByText('H3')).toBeInTheDocument()
    })

    it('should render paragraphs', () => {
      render(<MarkdownViewer content="This is a paragraph." />)

      expect(screen.getByText('This is a paragraph.')).toBeInTheDocument()
    })

    it('should render bold text', () => {
      render(<MarkdownViewer content="This is **bold** text." />)

      expect(screen.getByText('bold')).toBeInTheDocument()
    })

    it('should render italic text', () => {
      render(<MarkdownViewer content="This is *italic* text." />)

      expect(screen.getByText('italic')).toBeInTheDocument()
    })

    it('should render inline code', () => {
      render(<MarkdownViewer content="Use `code` here." />)

      const codeElement = screen.getByText('code')
      expect(codeElement.tagName).toBe('CODE')
    })

    it('should render unordered lists', () => {
      const content = `- Item 1
- Item 2
- Item 3`

      render(<MarkdownViewer content={content} />)

      expect(screen.getByText('Item 1')).toBeInTheDocument()
      expect(screen.getByText('Item 2')).toBeInTheDocument()
      expect(screen.getByText('Item 3')).toBeInTheDocument()
    })

    it('should render code blocks', () => {
      const content = '```javascript\nconst x = 1;\n```'

      render(<MarkdownViewer content={content} />)

      expect(screen.getByText('const x = 1;')).toBeInTheDocument()
    })

    it('should render blockquotes', () => {
      render(<MarkdownViewer content="> This is a quote" />)

      expect(screen.getByText('This is a quote')).toBeInTheDocument()
    })

    it('should render horizontal rules', () => {
      const { container } = render(<MarkdownViewer content="---" />)

      expect(container.querySelector('hr')).toBeInTheDocument()
    })
  })

  describe('Wiki links', () => {
    it('should render wiki-style links [[Document]] in content', () => {
      render(<MarkdownViewer content="Check out [[My Document]] for more info." />)

      // Wiki link appears twice: once in content (via MarkdownPreview), once in links section
      const links = screen.getAllByText('My Document')
      expect(links.length).toBeGreaterThanOrEqual(1)
    })

    it('should call onLinkClick when wiki link in links section is clicked', async () => {
      const onLinkClick = vi.fn()
      const user = userEvent.setup()

      const { container } = render(
        <MarkdownViewer 
          content="Check [[Test Doc]]" 
          onLinkClick={onLinkClick}
        />
      )

      // Target the link specifically within the links-section for reliable clicking
      const linksSection = container.querySelector('.links-section') as HTMLElement | null
      expect(linksSection).toBeInTheDocument()
      
      const linkInSection = within(linksSection!).getByText('Test Doc')
      await user.click(linkInSection)

      expect(onLinkClick).toHaveBeenCalledWith('test-doc')
    })

    it('should show links section when wikiLinks are provided', () => {
      render(
        <MarkdownViewer 
          content="Some content" 
          wikiLinks={['Doc 1', 'Doc 2']}
        />
      )

      expect(screen.getByText('Prepojenia')).toBeInTheDocument()
      expect(screen.getByText('Doc 1')).toBeInTheDocument()
      expect(screen.getByText('Doc 2')).toBeInTheDocument()
    })
  })

  describe('HTML content', () => {
    it('should render pre-rendered HTML when detected', () => {
      const htmlContent = '<div class="custom"><p>Custom HTML</p></div>'

      render(<MarkdownViewer content={htmlContent} />)

      expect(screen.getByText('Custom HTML')).toBeInTheDocument()
    })
  })

  describe('Empty content', () => {
    it('should handle empty string gracefully', () => {
      const { container } = render(<MarkdownViewer content="" />)

      // Should not crash - check that markdown-viewer div exists
      expect(container.querySelector('.markdown-viewer')).toBeInTheDocument()
    })
  })
})
