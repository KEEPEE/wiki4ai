/**
 * Tests for MarkdownToolbar component.
 *
 * Verifies all toolbar buttons render correctly, tooltips are present,
 * heading dropdown works, and modals open/close properly.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import MarkdownToolbar from '../components/MarkdownToolbar';

describe('MarkdownToolbar', () => {
  const mockOnInsert = vi.fn();

  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('should render without errors', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    expect(screen.getByTitle('Bold (Ctrl+B)')).toBeInTheDocument();
  });

  it('should have all 10 toolbar buttons visible on desktop', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    // Check each button by tooltip/title (Heading is a dropdown with different title)
    expect(screen.getByTitle('Bold (Ctrl+B)')).toBeInTheDocument();
    expect(screen.getByTitle('Italic (Ctrl+I)')).toBeInTheDocument();
    expect(screen.getByTitle('Select heading')).toBeInTheDocument(); // Heading dropdown toggle
    expect(screen.getByTitle('Code block')).toBeInTheDocument();
    expect(screen.getByTitle('Inline code')).toBeInTheDocument();
    expect(screen.getByTitle('Quote')).toBeInTheDocument();
    expect(screen.getByTitle('Link')).toBeInTheDocument();
    expect(screen.getByTitle('Image')).toBeInTheDocument();
    expect(screen.getByTitle('Divider')).toBeInTheDocument();
    expect(screen.getByTitle('Table 3x3')).toBeInTheDocument();
  });

  it('should call onInsert with **bold** when Bold button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const boldBtn = screen.getByTitle('Bold (Ctrl+B)');
    fireEvent.click(boldBtn);
    
    expect(mockOnInsert).toHaveBeenCalledWith(
      '**bold**',
      { startOffset: 2, endOffset: 6 },
    );
  });

  it('should call onInsert with *italic* when Italic button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const italicBtn = screen.getByTitle('Italic (Ctrl+I)');
    fireEvent.click(italicBtn);
    
    expect(mockOnInsert).toHaveBeenCalledWith(
      '*italic*',
      { startOffset: 1, endOffset: 7 },
    );
  });

  it('should call onInsert with code block template when Code Block button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const codeBlockBtn = screen.getByTitle('Code block');
    fireEvent.click(codeBlockBtn);
    
    expect(mockOnInsert).toHaveBeenCalledWith(
      '\n```\ncode\n```\n',
      { startOffset: 5, endOffset: 9 },
    );
  });

  it('should call onInsert with inline code template when Inline Code button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const inlineCodeBtn = screen.getByTitle('Inline code');
    fireEvent.click(inlineCodeBtn);
    
    expect(mockOnInsert).toHaveBeenCalledWith(
      '`code`',
      { startOffset: 1, endOffset: 5 },
    );
  });

  it('should call onInsert with > when Quote button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const quoteBtn = screen.getByTitle('Quote');
    fireEvent.click(quoteBtn);
    
    expect(mockOnInsert).toHaveBeenCalledWith('> ');
  });

  it('should open Link modal when Link button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const linkBtn = screen.getByTitle('Link');
    fireEvent.click(linkBtn);
    
    expect(screen.getByText('Insert Link')).toBeInTheDocument();
    expect(screen.getByLabelText('URL')).toBeInTheDocument();
  });

  it('should open Image modal when Image button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const imageBtn = screen.getByTitle('Image');
    fireEvent.click(imageBtn);
    
    expect(screen.getByText('Insert Image')).toBeInTheDocument();
    expect(screen.getByLabelText('Image URL')).toBeInTheDocument();
  });

  it('should call onInsert with --- when Divider button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const dividerBtn = screen.getByTitle('Divider');
    fireEvent.click(dividerBtn);
    
    expect(mockOnInsert).toHaveBeenCalledWith('\n---\n');
  });

  it('should call onInsert with table template when Table button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const tableBtn = screen.getByTitle('Table 3x3');
    fireEvent.click(tableBtn);
    
    expect(mockOnInsert).toHaveBeenCalledOnce();
    const insertedText = mockOnInsert.mock.calls[0][0];
    expect(insertedText).toContain('| Hlavička 1 |');
    expect(insertedText).toContain('|------------|');
    expect(insertedText.split('\n').length).toBe(5); // 3 header + separator + 2 data rows = 5 lines
  });

  it('should toggle Heading dropdown when Heading button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const headingBtn = screen.getByTitle('Select heading');
    expect(headingBtn).toBeInTheDocument();
    
    // Open dropdown
    fireEvent.click(headingBtn);
    expect(screen.getByText('H1')).toBeInTheDocument();
    expect(screen.getByText('H6')).toBeInTheDocument();
    
    // Close dropdown
    fireEvent.click(headingBtn);
    expect(screen.queryByText('H1')).not.toBeInTheDocument();
  });

  it('should insert correct heading level when H option is selected', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const headingBtn = screen.getByTitle('Select heading');
    fireEvent.click(headingBtn); // Open dropdown
    
    const h3Btn = screen.getByText('H3');
    fireEvent.click(h3Btn);
    
    expect(mockOnInsert).toHaveBeenCalledWith(
      '### ',
      { startOffset: 4, endOffset: 4 },
    );
  });

  it('should have desktop toolbar visible by default', () => {
    const { container } = render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const desktopToolbar = container.querySelector('.markdown-toolbar-desktop');
    expect(desktopToolbar).toBeInTheDocument();
  });

  it('should have mobile menu toggle button', () => {
    const { container } = render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const mobileToggle = container.querySelector('.toolbar-menu-toggle');
    expect(mobileToggle).toBeInTheDocument();
  });

  it('should open/close mobile dropdown when menu toggle is clicked', () => {
    const { container } = render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const mobileToggle = container.querySelector('.toolbar-menu-toggle') as HTMLElement;
    
    // Initially closed
    expect(container.querySelector('.toolbar-mobile-dropdown')).not.toBeInTheDocument();
    
    // Open
    fireEvent.click(mobileToggle);
    expect(container.querySelector('.toolbar-mobile-dropdown')).toBeInTheDocument();
    
    // Close
    fireEvent.click(mobileToggle);
    expect(container.querySelector('.toolbar-mobile-dropdown')).not.toBeInTheDocument();
  });

  it('should close Link modal when cancel button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const linkBtn = screen.getByTitle('Link');
    fireEvent.click(linkBtn);
    
    expect(screen.getByText('Insert Link')).toBeInTheDocument();
    
    const cancelBtn = screen.getByText('Cancel');
    fireEvent.click(cancelBtn);
    
    expect(screen.queryByText('Insert Link')).not.toBeInTheDocument();
  });

  it('should insert link markdown when Link modal is submitted', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const linkBtn = screen.getByTitle('Link');
    fireEvent.click(linkBtn);
    
    const urlInput = screen.getByLabelText('URL');
    // WIKI4AI-73: EN default catalog
    const textInput = screen.getByLabelText('Link text');

    fireEvent.change(urlInput, { target: { value: 'https://example.com' } });
    fireEvent.change(textInput, { target: { value: 'Example Link' } });
    
    const submitBtn = screen.getByText('Insert');
    fireEvent.click(submitBtn);
    
    expect(mockOnInsert).toHaveBeenCalledWith('[Example Link](https://example.com)');
  });

  it('should insert image markdown when Image modal is submitted', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const imageBtn = screen.getByTitle('Image');
    fireEvent.click(imageBtn);
    
    const urlInput = screen.getByLabelText('Image URL');
    const altInput = screen.getByLabelText('Alt text');
    
    fireEvent.change(urlInput, { target: { value: 'https://example.com/img.png' } });
    fireEvent.change(altInput, { target: { value: 'Nice image' } });
    
    const submitBtn = screen.getByText('Insert');
    fireEvent.click(submitBtn);
    
    expect(mockOnInsert).toHaveBeenCalledWith('![Nice image](https://example.com/img.png)');
  });

  it('should close modal when clicking overlay background', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const linkBtn = screen.getByTitle('Link');
    fireEvent.click(linkBtn);
    
    expect(screen.getByText('Insert Link')).toBeInTheDocument();
    
    // Click the overlay (background)
    const overlay = document.querySelector('.toolbar-modal-overlay') as HTMLElement;
    fireEvent.click(overlay);
    
    expect(screen.queryByText('Insert Link')).not.toBeInTheDocument();
  });

  it('should not close modal when clicking inside the modal content', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const linkBtn = screen.getByTitle('Link');
    fireEvent.click(linkBtn);
    
    expect(screen.getByText('Insert Link')).toBeInTheDocument();
    
    // Click inside the modal (should not close)
    const modalContent = document.querySelector('.toolbar-modal') as HTMLElement;
    fireEvent.click(modalContent);
    
    expect(screen.getByText('Insert Link')).toBeInTheDocument();
  });

  it('should have proper glassmorphism CSS class on toolbar', () => {
    const { container } = render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const desktopToolbar = container.querySelector('.markdown-toolbar-desktop');
    expect(desktopToolbar).toHaveClass('markdown-toolbar');
  });

  it('should have all buttons with proper aria-label attributes', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    // Check a few key buttons for accessibility
    const boldBtn = screen.getByLabelText('Bold (Ctrl+B)');
    expect(boldBtn).toHaveAttribute('aria-label', 'Bold (Ctrl+B)');
    
    const italicBtn = screen.getByLabelText('Italic (Ctrl+I)');
    expect(italicBtn).toHaveAttribute('aria-label', 'Italic (Ctrl+I)');
  });

  it('should have dropdown toggle with aria-expanded attribute', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const headingToggle = screen.getByTitle('Select heading');
    expect(headingToggle).toHaveAttribute('aria-expanded', 'false');
    
    fireEvent.click(headingToggle);
    expect(headingToggle).toHaveAttribute('aria-expanded', 'true');
  });
});
