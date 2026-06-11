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
    expect(screen.getByTitle('Tučné (Ctrl+B)')).toBeInTheDocument();
  });

  it('should have all 10 toolbar buttons visible on desktop', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    // Check each button by tooltip/title (Heading is a dropdown with different title)
    expect(screen.getByTitle('Tučné (Ctrl+B)')).toBeInTheDocument();
    expect(screen.getByTitle('Kurzíva (Ctrl+I)')).toBeInTheDocument();
    expect(screen.getByTitle('Vybrať nadpis')).toBeInTheDocument(); // Heading dropdown toggle
    expect(screen.getByTitle('Blok kódu')).toBeInTheDocument();
    expect(screen.getByTitle('Riadkový kód')).toBeInTheDocument();
    expect(screen.getByTitle('Citácia')).toBeInTheDocument();
    expect(screen.getByTitle('Odkaz')).toBeInTheDocument();
    expect(screen.getByTitle('Obrázok')).toBeInTheDocument();
    expect(screen.getByTitle('Oddelič')).toBeInTheDocument();
    expect(screen.getByTitle('Tabuľka 3x3')).toBeInTheDocument();
  });

  it('should call onInsert with **bold** when Bold button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const boldBtn = screen.getByTitle('Tučné (Ctrl+B)');
    fireEvent.click(boldBtn);
    
    expect(mockOnInsert).toHaveBeenCalledWith(
      '**bold**',
      { startOffset: 2, endOffset: 6 },
    );
  });

  it('should call onInsert with *italic* when Italic button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const italicBtn = screen.getByTitle('Kurzíva (Ctrl+I)');
    fireEvent.click(italicBtn);
    
    expect(mockOnInsert).toHaveBeenCalledWith(
      '*italic*',
      { startOffset: 1, endOffset: 7 },
    );
  });

  it('should call onInsert with code block template when Code Block button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const codeBlockBtn = screen.getByTitle('Blok kódu');
    fireEvent.click(codeBlockBtn);
    
    expect(mockOnInsert).toHaveBeenCalledWith(
      '\n```\ncode\n```\n',
      { startOffset: 5, endOffset: 9 },
    );
  });

  it('should call onInsert with inline code template when Inline Code button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const inlineCodeBtn = screen.getByTitle('Riadkový kód');
    fireEvent.click(inlineCodeBtn);
    
    expect(mockOnInsert).toHaveBeenCalledWith(
      '`code`',
      { startOffset: 1, endOffset: 5 },
    );
  });

  it('should call onInsert with > when Quote button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const quoteBtn = screen.getByTitle('Citácia');
    fireEvent.click(quoteBtn);
    
    expect(mockOnInsert).toHaveBeenCalledWith('> ');
  });

  it('should open Link modal when Link button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const linkBtn = screen.getByTitle('Odkaz');
    fireEvent.click(linkBtn);
    
    expect(screen.getByText('Vložiť odkaz')).toBeInTheDocument();
    expect(screen.getByLabelText('URL')).toBeInTheDocument();
  });

  it('should open Image modal when Image button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const imageBtn = screen.getByTitle('Obrázok');
    fireEvent.click(imageBtn);
    
    expect(screen.getByText('Vložiť obrázok')).toBeInTheDocument();
    expect(screen.getByLabelText('URL obrázku')).toBeInTheDocument();
  });

  it('should call onInsert with --- when Divider button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const dividerBtn = screen.getByTitle('Oddelič');
    fireEvent.click(dividerBtn);
    
    expect(mockOnInsert).toHaveBeenCalledWith('\n---\n');
  });

  it('should call onInsert with table template when Table button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const tableBtn = screen.getByTitle('Tabuľka 3x3');
    fireEvent.click(tableBtn);
    
    expect(mockOnInsert).toHaveBeenCalledOnce();
    const insertedText = mockOnInsert.mock.calls[0][0];
    expect(insertedText).toContain('| Hlavička 1 |');
    expect(insertedText).toContain('|------------|');
    expect(insertedText.split('\n').length).toBe(5); // 3 header + separator + 2 data rows = 5 lines
  });

  it('should toggle Heading dropdown when Heading button is clicked', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const headingBtn = screen.getByTitle('Vybrať nadpis');
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
    
    const headingBtn = screen.getByTitle('Vybrať nadpis');
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
    
    const linkBtn = screen.getByTitle('Odkaz');
    fireEvent.click(linkBtn);
    
    expect(screen.getByText('Vložiť odkaz')).toBeInTheDocument();
    
    const cancelBtn = screen.getByText('Zrušiť');
    fireEvent.click(cancelBtn);
    
    expect(screen.queryByText('Vložiť odkaz')).not.toBeInTheDocument();
  });

  it('should insert link markdown when Link modal is submitted', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const linkBtn = screen.getByTitle('Odkaz');
    fireEvent.click(linkBtn);
    
    const urlInput = screen.getByLabelText('URL');
    const textInput = screen.getByLabelText('Text odkazu');
    
    fireEvent.change(urlInput, { target: { value: 'https://example.com' } });
    fireEvent.change(textInput, { target: { value: 'Example Link' } });
    
    const submitBtn = screen.getByText('Vložiť');
    fireEvent.click(submitBtn);
    
    expect(mockOnInsert).toHaveBeenCalledWith('[Example Link](https://example.com)');
  });

  it('should insert image markdown when Image modal is submitted', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const imageBtn = screen.getByTitle('Obrázok');
    fireEvent.click(imageBtn);
    
    const urlInput = screen.getByLabelText('URL obrázku');
    const altInput = screen.getByLabelText('Alt text');
    
    fireEvent.change(urlInput, { target: { value: 'https://example.com/img.png' } });
    fireEvent.change(altInput, { target: { value: 'Nice image' } });
    
    const submitBtn = screen.getByText('Vložiť');
    fireEvent.click(submitBtn);
    
    expect(mockOnInsert).toHaveBeenCalledWith('![Nice image](https://example.com/img.png)');
  });

  it('should close modal when clicking overlay background', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const linkBtn = screen.getByTitle('Odkaz');
    fireEvent.click(linkBtn);
    
    expect(screen.getByText('Vložiť odkaz')).toBeInTheDocument();
    
    // Click the overlay (background)
    const overlay = document.querySelector('.toolbar-modal-overlay') as HTMLElement;
    fireEvent.click(overlay);
    
    expect(screen.queryByText('Vložiť odkaz')).not.toBeInTheDocument();
  });

  it('should not close modal when clicking inside the modal content', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const linkBtn = screen.getByTitle('Odkaz');
    fireEvent.click(linkBtn);
    
    expect(screen.getByText('Vložiť odkaz')).toBeInTheDocument();
    
    // Click inside the modal (should not close)
    const modalContent = document.querySelector('.toolbar-modal') as HTMLElement;
    fireEvent.click(modalContent);
    
    expect(screen.getByText('Vložiť odkaz')).toBeInTheDocument();
  });

  it('should have proper glassmorphism CSS class on toolbar', () => {
    const { container } = render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const desktopToolbar = container.querySelector('.markdown-toolbar-desktop');
    expect(desktopToolbar).toHaveClass('markdown-toolbar');
  });

  it('should have all buttons with proper aria-label attributes', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    // Check a few key buttons for accessibility
    const boldBtn = screen.getByLabelText('Tučné (Ctrl+B)');
    expect(boldBtn).toHaveAttribute('aria-label', 'Tučné (Ctrl+B)');
    
    const italicBtn = screen.getByLabelText('Kurzíva (Ctrl+I)');
    expect(italicBtn).toHaveAttribute('aria-label', 'Kurzíva (Ctrl+I)');
  });

  it('should have dropdown toggle with aria-expanded attribute', () => {
    render(<MarkdownToolbar onInsert={mockOnInsert} />);
    
    const headingToggle = screen.getByTitle('Vybrať nadpis');
    expect(headingToggle).toHaveAttribute('aria-expanded', 'false');
    
    fireEvent.click(headingToggle);
    expect(headingToggle).toHaveAttribute('aria-expanded', 'true');
  });
});
