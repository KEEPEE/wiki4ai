/**
 * MarkdownEditor — React wrapper around Monaco Editor for professional markdown editing.
 *
 * Features:
 * - Syntax highlighting for markdown
 * - Dark theme styled to match the project's cyan/magenta neon aesthetic
 * - Refined color palette: white line numbers (30%), cyan cursor, subtle selection highlight
 * - Configurable height, read-only mode, and change callbacks
 * - Uses ResizeObserver for reliable container height measurement
 * - MarkdownToolbar with formatting buttons above the editor
 */

import React, { useRef, useEffect, useState, useCallback } from 'react';
import Editor, { loader } from '@monaco-editor/react';
import type { editor } from 'monaco-editor';
import * as monaco from 'monaco-editor';
import MarkdownToolbar from './MarkdownToolbar';
import './MarkdownEditor.css';

export interface MarkdownEditorProps {
  /** Current editor content value */
  value?: string;
  /** Callback fired when the editor content changes */
  onChange?: (value: string | undefined) => void;
  /** Whether the editor is read-only. Default: false */
  readOnly?: boolean;
}

/**
 * Selection range for placing cursor after insertion.
 * { startOffset, endOffset } are character offsets within the inserted text
 * where the cursor should be positioned (or a selection made).
 */
interface InsertSelection {
  startOffset: number;
  endOffset: number;
}

/**
 * The "wiki4ai-dark" Monaco theme with a high-contrast palette.
 *
 * IMPORTANT: this must be registered on the Monaco instance that
 * @monaco-editor/react actually loads (via `loader.init()`), NOT on the
 * statically imported `monaco-editor` package — the two are separate
 * instances, and registering only on the static import leaves the live
 * editor on the default light "vs" token palette (black text on our dark
 * background). See the monacoReady gate in the component below.
 *
 * Color palette (all text colors pass WCAG AA on #12121f):
 * - Base text: #e8e8f0 bright cool near-white (~15:1) — comfortable for long editing
 * - Headings / list markers / table pipes (keyword.md): #00f0ff brand cyan (~13:1)
 * - Table header cells (keyword.table.header.md): #ffffff brightest white
 * - Bold (strong.md): #ffffff pure white, brighter than base
 * - Italic (emphasis.md): #c8b8ff soft lavender (~10:1)
 * - Inline code + code block content (variable*.md): #ffd08a warm amber (~13:1)
 * - Fenced code delimiters + indented code (string.md): #ffd08a warm amber
 * - Links (string.link.md): #00f0ff brand cyan
 * - Blockquote marker / HTML comments (comment.md): #9aa0b5 medium gray (~7:1)
 * - Horizontal rule (meta.separator.md): #7d8497 muted slate (~5:1)
 * - Inline HTML tags (tag.md): #ff9de6 soft magenta secondary accent
 * - Background: #12121f (deep dark matching --dark-2)
 * - Line numbers: subtle white 30% (unchanged)
 * - Cursor: #00f0ff bright cyan (unchanged)
 * - Selection: rgba(0, 240, 255, 0.2) gentle cyan highlight (unchanged)
 * - Find widget / scrollbars / current line: cyan tones (unchanged)
 */
const WIKI4AI_DARK_THEME: editor.IStandaloneThemeData = {
  base: 'vs-dark',
  inherit: true,
  rules: [
    // Base text — bright, high-contrast near-white with a cool tint
    { token: '', foreground: 'E8E8F0', background: '12121F' },
    // Headings (#/##/###), list markers (*, -, 1.) and table pipes
    { token: 'keyword.md', foreground: '00F0FF' },
    // Table header cell text — brightest white
    { token: 'keyword.table.header.md', foreground: 'FFFFFF' },
    // Bold (**text**) — pure white, brighter than base
    { token: 'strong.md', foreground: 'FFFFFF' },
    // Italic (*text*) — soft lavender
    { token: 'emphasis.md', foreground: 'C8B8FF' },
    // Inline code (`code`) and fenced code block content — warm amber
    { token: 'variable.md', foreground: 'FFD08A' },
    { token: 'variable.source.md', foreground: 'FFD08A' },
    // Fenced code delimiters (```/~~~) and 4-space indented code — warm amber
    { token: 'string.md', foreground: 'FFD08A' },
    // Links ([text](url)) — brand cyan
    { token: 'string.link.md', foreground: '00F0FF' },
    // Blockquote marker (>) and HTML comments — medium gray
    { token: 'comment.md', foreground: '9AA0B5' },
    // Horizontal rule (***) — muted slate
    { token: 'meta.separator.md', foreground: '7D8497' },
    // Inline HTML tags — soft magenta (secondary accent)
    { token: 'tag.md', foreground: 'FF9DE6' },
  ],
  colors: {
    'editor.background': '#12121f',
    'editor.foreground': '#e8e8f0',
    'editor.lineNumberForeground': '#666666',
    'editorCursor.foreground': '#00f0ff',
    'editor.selectionBackground': 'rgba(0, 240, 255, 0.2)',
    'editor.findWidget.background': 'rgba(0, 240, 255, 0.1)',
    'editor.findWidget.foreground': '#00f0ff',
    'scrollbarSlider.background': 'rgba(0, 240, 255, 0.12)',
    'scrollbarSlider.hoverBackground': 'rgba(0, 240, 255, 0.25)',
    'editor.lineHighlightBackground': 'rgba(0, 240, 255, 0.08)',
  },
};

/**
 * Register the wiki4ai-dark theme on the Monaco instance loaded by
 * @monaco-editor/react (CDN by default). Resolves once the theme exists on
 * that instance so the editor can be rendered with it from first paint.
 */
function registerWiki4aiTheme(): Promise<void> {
  return loader.init().then((loadedMonaco) => {
    try {
      loadedMonaco.editor.defineTheme('wiki4ai-dark', WIKI4AI_DARK_THEME);
    } catch {
      // Theme already defined (e.g. hot reload) — non-fatal
    }
  });
}

const MarkdownEditor: React.FC<MarkdownEditorProps> = ({
  value = '',
  onChange,
  readOnly = false,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const editorRef = useRef<editor.IStandaloneCodeEditor | null>(null);
  const [editorHeight, setEditorHeight] = useState<number>(600);
  // Gate: only render the Monaco Editor once wiki4ai-dark is registered on
  // the instance @monaco-editor/react actually loads. Rendering earlier would
  // make Monaco fall back to the default light "vs" token palette (black text
  // on our dark background) because the theme name would not be found there.
  const [monacoReady, setMonacoReady] = useState<boolean>(false);

  useEffect(() => {
    let cancelled = false;
    registerWiki4aiTheme()
      .catch(() => {
        // Monaco failed to load — render anyway so the user sees a usable
        // (default-themed) editor instead of an empty pane.
      })
      .finally(() => {
        if (!cancelled) setMonacoReady(true);
      });
    return () => {
      cancelled = true;
    };
  }, []);

  // Use ResizeObserver to measure the actual container height in pixels.
  // This is more reliable than CSS percentage heights because Monaco Editor
  // internally creates an iframe that needs explicit pixel dimensions.
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;

    // Initial measurement
    const updateHeight = () => {
      const newHeight = container.clientHeight;
      if (newHeight > 0) {
        setEditorHeight(newHeight);
      }
    };
    updateHeight();

    // Watch for size changes
    const observer = new ResizeObserver(updateHeight);
    observer.observe(container);

    return () => {
      observer.disconnect();
    };
  }, []);

  // Force Monaco to relayout after height changes
  useEffect(() => {
    if (editorRef.current) {
      editorRef.current.layout();
    }
  }, [editorHeight]);

  const handleEditorChange = (newValue: string | undefined) => {
    if (onChange) {
      onChange(newValue);
    }
  };

  const handleEditorMount = useCallback((ed: editor.IStandaloneCodeEditor) => {
    editorRef.current = ed;

    // Force initial layout after mount.
    // (Theme registration happens in the monacoReady gate above, on the same
    // Monaco instance this editor runs on.)
    requestAnimationFrame(() => {
      ed.layout();
    });
  }, []);

  /**
   * Insert text at the current cursor position in Monaco editor.
   * Optionally places the cursor (or selection) within the inserted text.
   */
  const handleInsert = useCallback((text: string, selection?: InsertSelection) => {
    const ed = editorRef.current;
    if (!ed) return;

    const model = ed.getModel();
    if (!model) return;

    const position = ed.getPosition();
    if (!position) return;

    // Use the editor's executeEdits API for proper undo/redo support
    const range = new monaco.Range(
      position.lineNumber,
      position.column,
      position.lineNumber,
      position.column,
    );

    ed.executeEdits('markdown-toolbar-insert', [
      {
        range,
        text: text,
      },
    ]);

    // If a selection was specified, place cursor/selection within the inserted text
    if (selection) {
      const startCol = position.column + selection.startOffset;
      const endCol = position.column + selection.endOffset;
      ed.setPosition({ lineNumber: position.lineNumber, column: startCol });
      if (startCol !== endCol) {
        const selRange = new monaco.Range(
          position.lineNumber,
          startCol,
          position.lineNumber,
          endCol,
        );
        ed.setSelection(selRange);
      }
    }

    // Focus the editor after insertion so user can continue typing immediately
    ed.focus();
  }, []);

  return (
    <div className="markdown-editor" ref={containerRef}>
      {!monacoReady ? (
        /* Waiting for Monaco + wiki4ai-dark theme registration */
        <div className="markdown-editor__loading">Loading editor…</div>
      ) : (
        <>
          {/* Formatting toolbar above the Monaco editor */}
          {!readOnly && (
            <MarkdownToolbar onInsert={handleInsert} />
          )}
          <Editor
            language="markdown"
            value={value}
            onChange={handleEditorChange}
            onMount={handleEditorMount}
            height={editorHeight}
            theme="wiki4ai-dark"
            options={{
              minimap: { enabled: false },
              lineNumbers: 'on',
              wordWrap: 'on',
              fontSize: 14,
              scrollBeyondLastLine: false,
              automaticLayout: true,
              tabSize: 2,
              insertSpaces: true,
              readOnly,
            }}
          />
        </>
      )}
    </div>
  );
};

export default MarkdownEditor;
