/**
 * MarkdownToolbar — Horizontal formatting toolbar with pill-style buttons
 * for quick markdown syntax insertion at the Monaco editor cursor position.
 *
 * Features:
 * - 10 formatting buttons (Bold, Italic, Heading dropdown, Code block, Inline code, Quote, Link, Image, Divider, Table)
 * - Tooltips on hover
 * - Glassmorphism styling with cyan neon accents matching project theme
 * - Mobile responsive — collapsible toolbar below 768px
 * - Link/Image modals for URL + text input
 */

import React, { useState, useCallback } from 'react';
import { useTranslation } from 'react-i18next';
import './MarkdownToolbar.css';

export interface MarkdownToolbarProps {
  /** Callback to insert markdown at the current cursor position in Monaco editor */
  onInsert: (text: string, selection?: { startOffset: number; endOffset: number }) => void;
}

/* ── Inline SVG Icon Components ─────────────────────────────── */

const BoldIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
    <path d="M6 4h8a4 4 0 0 1 4 4 4 4 0 0 1-4 4H6z" />
    <path d="M6 12h9a4 4 0 0 1 4 4 4 4 0 0 1-4 4H6z" />
  </svg>
);

const ItalicIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
    <line x1="19" y1="4" x2="10" y2="4" />
    <line x1="14" y1="20" x2="5" y2="20" />
    <line x1="15" y1="4" x2="9" y2="20" />
  </svg>
);

const HeadingIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
    <path d="M6 4v16" />
    <path d="M18 4v16" />
    <path d="M6 12h12" />
  </svg>
);

const CodeBlockIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="16 18 22 12 16 6" />
    <polyline points="8 6 2 12 8 18" />
  </svg>
);

const InlineCodeIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="16 18 22 12 16 6" />
    <polyline points="8 6 2 12 8 18" />
    <line x1="14" y1="4" x2="10" y2="20" strokeWidth="1.5" opacity="0.5" />
  </svg>
);

const QuoteIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="currentColor">
    <path d="M10 8c-1.1 0-2 .9-2 2v2c0 1.1.9 2 2 2h1V8h-1zm5 0c-1.1 0-2 .9-2 2v2c0 1.1.9 2 2 2h1V8h-1zM8 6h3v2H8V6zm5 0h3v2h-3V6z" />
  </svg>
);

const LinkIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
    <path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07L10 8" />
    <path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07L14 16" />
  </svg>
);

const ImageIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
    <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
    <circle cx="8.5" cy="8.5" r="1.5" />
    <polyline points="21 15 16 10 5 21" />
  </svg>
);

const DividerIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
    <line x1="3" y1="8" x2="21" y2="8" />
    <line x1="3" y1="16" x2="21" y2="16" />
  </svg>
);

const TableIcon = () => (
  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
    <rect x="3" y="3" width="18" height="18" rx="2" />
    <line x1="3" y1="9" x2="21" y2="9" />
    <line x1="3" y1="15" x2="21" y2="15" />
    <line x1="9" y1="3" x2="9" y2="21" />
    <line x1="15" y1="3" x2="15" y2="21" />
  </svg>
);

const ChevronDownIcon = () => (
  <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
    <polyline points="6 9 12 15 18 9" />
  </svg>
);

const MenuIcon = () => (
  <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round">
    <line x1="3" y1="6" x2="21" y2="6" />
    <line x1="3" y1="12" x2="21" y2="12" />
    <line x1="3" y1="18" x2="21" y2="18" />
  </svg>
);

/* ── Modal for Link / Image insertion ─────────────────────── */

interface InsertModalProps {
  title: string;
  fieldLabel1: string;
  fieldPlaceholder1: string;
  fieldLabel2: string;
  fieldPlaceholder2: string;
  onSubmit: (field1: string, field2: string) => void;
  onClose: () => void;
}

const InsertModal: React.FC<InsertModalProps> = ({
  title,
  fieldLabel1,
  fieldPlaceholder1,
  fieldLabel2,
  fieldPlaceholder2,
  onSubmit,
  onClose,
}) => {
  const { t } = useTranslation();
  const [val1, setVal1] = useState('');
  const [val2, setVal2] = useState('');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (val1.trim()) {
      onSubmit(val1.trim(), val2.trim());
    }
  };

  return (
    <div className="toolbar-modal-overlay" onClick={onClose}>
      <div className="toolbar-modal" onClick={(e) => e.stopPropagation()}>
        <h3>{title}</h3>
        <form onSubmit={handleSubmit}>
          <label>
            {fieldLabel1}
            <input
              type="text"
              value={val1}
              onChange={(e) => setVal1(e.target.value)}
              placeholder={fieldPlaceholder1}
              autoFocus
            />
          </label>
          <label>
            {fieldLabel2}
            <input
              type="text"
              value={val2}
              onChange={(e) => setVal2(e.target.value)}
              placeholder={fieldPlaceholder2}
            />
          </label>
          <div className="toolbar-modal-actions">
            <button type="button" onClick={onClose} className="modal-cancel-btn">{t('common.cancel')}</button>
            <button type="submit" className="modal-submit-btn">{t('markdown.insert')}</button>
          </div>
        </form>
      </div>
    </div>
  );
};

/* ── Main Toolbar Component ───────────────────────────────── */

const MarkdownToolbar: React.FC<MarkdownToolbarProps> = ({ onInsert }) => {
  const { t } = useTranslation();
  const [headingOpen, setHeadingOpen] = useState(false);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [linkModalOpen, setLinkModalOpen] = useState(false);
  const [imageModalOpen, setImageModalOpen] = useState(false);

  /* ── Insert helpers ─────────────────────────────────────── */

  const insertWrap = useCallback(
    (before: string, after: string, placeholder: string) => {
      onInsert(`${before}${placeholder}${after}`, { startOffset: before.length, endOffset: before.length + placeholder.length });
    },
    [onInsert],
  );

  const insertText = useCallback(
    (text: string) => {
      onInsert(text);
    },
    [onInsert],
  );

  /* ── Button handlers ──────────────────────────────────── */

  const handleBold = () => insertWrap('**', '**', 'bold');
  const handleItalic = () => insertWrap('*', '*', 'italic');
  const handleHeading = (level: number) => {
    const prefix = '#'.repeat(level) + ' ';
    setHeadingOpen(false);
    setMobileMenuOpen(false);
    onInsert(prefix, { startOffset: prefix.length, endOffset: prefix.length });
  };
  const handleCodeBlock = () => insertWrap('\n```\n', '\n```\n', 'code');
  const handleInlineCode = () => insertWrap('`', '`', 'code');
  const handleQuote = () => insertText('> ');
  const handleLink = () => setLinkModalOpen(true);
  const handleImage = () => setImageModalOpen(true);
  const handleDivider = () => insertText('\n---\n');

  const handleTable = () => {
    const table = [
      '| Hlavička 1 | Hlavička 2 | Hlavička 3 |',
      '|------------|------------|------------|',
      '| Bunka 1    | Bunka 2    | Bunka 3    |',
      '| Bunka 4    | Bunka 5    | Bunka 6    |',
      '| Bunka 7    | Bunka 8    | Bunka 9    |',
    ].join('\n');
    insertText(table);
  };

  const handleLinkSubmit = (url: string, text: string) => {
    setLinkModalOpen(false);
    if (text) {
      onInsert(`[${text}](${url})`);
    } else {
      onInsert(`[](${url})`, { startOffset: 1, endOffset: 1 });
    }
  };

  const handleImageSubmit = (url: string, alt: string) => {
    setImageModalOpen(false);
    onInsert(`![${alt}](${url})`);
  };

  /* ── Toolbar button definitions ─────────────────────── */

  interface ToolbarButtonDef {
    id: string;
    icon: React.ReactNode;
    tooltip: string;
    onClick?: () => void;
    children?: React.ReactNode;
  }

  const toolbarButtons: ToolbarButtonDef[] = [
    { id: 'bold', icon: <BoldIcon />, tooltip: t('markdown.boldTooltip'), onClick: handleBold },
    { id: 'italic', icon: <ItalicIcon />, tooltip: t('markdown.italicTooltip'), onClick: handleItalic },
    {
      id: 'heading',
      icon: <HeadingIcon />,
      tooltip: t('markdown.headingTooltip'),
      children: (
        <div className="toolbar-dropdown">
          <button
            type="button"
            className={`dropdown-toggle ${headingOpen ? 'active' : ''}`}
            onClick={() => setHeadingOpen((o) => !o)}
            aria-expanded={headingOpen}
            title={t('markdown.selectHeading')}
          >
            H <ChevronDownIcon />
          </button>
          {headingOpen && (
            <div className="dropdown-menu">
              {[1, 2, 3, 4, 5, 6].map((level) => (
                <button key={level} type="button" onClick={() => handleHeading(level)}>
                  H{level}
                </button>
              ))}
            </div>
          )}
        </div>
      ),
    },
    { id: 'codeblock', icon: <CodeBlockIcon />, tooltip: t('markdown.codeBlockTooltip'), onClick: handleCodeBlock },
    { id: 'inlinecode', icon: <InlineCodeIcon />, tooltip: t('markdown.inlineCodeTooltip'), onClick: handleInlineCode },
    { id: 'quote', icon: <QuoteIcon />, tooltip: t('markdown.quoteTooltip'), onClick: handleQuote },
    { id: 'link', icon: <LinkIcon />, tooltip: t('markdown.linkTooltip'), onClick: handleLink },
    { id: 'image', icon: <ImageIcon />, tooltip: t('markdown.imageTooltip'), onClick: handleImage },
    { id: 'divider', icon: <DividerIcon />, tooltip: t('markdown.dividerTooltip'), onClick: handleDivider },
    { id: 'table', icon: <TableIcon />, tooltip: t('markdown.tableTooltip'), onClick: handleTable },
  ];

  /* ── Render a single toolbar button ─────────────────── */

  const renderButton = (def: ToolbarButtonDef) => {
    if (def.children) return def.children;
    return (
      <button
        key={def.id}
        type="button"
        className="toolbar-btn"
        title={def.tooltip}
        onClick={def.onClick}
        aria-label={def.tooltip}
      >
        {def.icon}
      </button>
    );
  };

  /* ── Render toolbar buttons (shared between desktop & mobile) ─ */

  const renderButtons = () => toolbarButtons.map(renderButton);

  return (
    <>
      {/* Desktop toolbar — hidden on mobile (<768px) */}
      <div className="markdown-toolbar markdown-toolbar-desktop">
        {renderButtons()}
      </div>

      {/* Mobile toggle button + dropdown menu */}
      <div className="markdown-toolbar markdown-toolbar-mobile">
        <button
          type="button"
          className="toolbar-btn toolbar-menu-toggle"
          title={t('markdown.formattingTitle')}
          onClick={() => setMobileMenuOpen((o) => !o)}
          aria-expanded={mobileMenuOpen}
          aria-label={t('markdown.openFormatMenu')}
        >
          <MenuIcon />
        </button>

        {mobileMenuOpen && (
          <div className="toolbar-mobile-dropdown">
            {renderButtons()}
          </div>
        )}
      </div>

      {/* Link insertion modal */}
      {linkModalOpen && (
        <InsertModal
          title={t('markdown.insertLinkTitle')}
          fieldLabel1={t('markdown.urlLabel')}
          fieldPlaceholder1="https://..."
          fieldLabel2={t('markdown.linkText')}
          fieldPlaceholder2={t('markdown.linkTextPlaceholder')}
          onSubmit={handleLinkSubmit}
          onClose={() => setLinkModalOpen(false)}
        />
      )}

      {/* Image insertion modal */}
      {imageModalOpen && (
        <InsertModal
          title={t('markdown.insertImageTitle')}
          fieldLabel1={t('markdown.imageUrlLabel')}
          fieldPlaceholder1="https://..."
          fieldLabel2={t('markdown.altTextLabel')}
          fieldPlaceholder2={t('markdown.imageAltPlaceholder')}
          onSubmit={handleImageSubmit}
          onClose={() => setImageModalOpen(false)}
        />
      )}
    </>
  );
};

export default MarkdownToolbar;
