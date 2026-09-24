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
 *
 * WIKI4AI-79 (height runaway loop): the Monaco wrapper receives an explicit
 * pixel height from state. That state is measured with a ResizeObserver, so
 * the observed element MUST be a stable container whose own height does not
 * depend on the Monaco wrapper's explicit height. The editor therefore renders
 * inside a dedicated flex host (.markdown-editor__host, flex: 1; min-height: 0)
 * that is sized by the (viewport-bounded) flex chain — NOT in the outer
 * .markdown-editor container, which also holds the toolbar and whose content
 * height used to equal toolbar + Monaco wrapper (each observer cycle added the
 * toolbar's height back into the measured value → unbounded page growth).
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
 * - Selection: #00F0FF33 (= rgba(0, 240, 255, 0.2)) gentle cyan highlight
 * - Find widget / scrollbars / current line: cyan tones (unchanged)
 *
 * WIKI4AI-83: every value in `colors` MUST be a hex string (#RRGGBB or
 * #RRGGBBAA). Monaco's StandaloneTheme parses them with Color.fromHex(), which
 * silently falls back to #ff0000 (Color.red) for anything non-hex — the old
 * rgba() strings rendered the selection and line highlight bright red.
 */
export const WIKI4AI_DARK_THEME: editor.IStandaloneThemeData = {
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
    // WIKI4AI-83: hex8 (#RRGGBBAA) equivalents of the old rgba() values — see the
    // fromHex/#ff0000 note in the header comment above.
    'editor.selectionBackground': '#00F0FF33', // was rgba(0, 240, 255, 0.2)
    'editor.findWidget.background': '#00F0FF1A', // was rgba(0, 240, 255, 0.1)
    'editor.findWidget.foreground': '#00f0ff',
    'scrollbarSlider.background': '#00F0FF1F', // was rgba(0, 240, 255, 0.12)
    'scrollbarSlider.hoverBackground': '#00F0FF40', // was rgba(0, 240, 255, 0.25)
    'editor.lineHighlightBackground': '#00F0FF14', // was rgba(0, 240, 255, 0.08)
    // WIKI4AI-83: defensive neutral for the orphan `]` of [[WikiLink]] — the
    // markdown tokenizer swallows `[[…` up to the first `]` into one string.link
    // token, leaving the final bracket unbalanced and decorated with
    // "unexpected-closing-bracket". Primary fix is bracketPairColorization: false
    // below; this only guards against re-enabling it in the future.
    'editorBracketHighlight.unexpectedBracket.foreground': '#E8E8F0',
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
  // WIKI4AI-79: the stable measurement target — a flex host whose height is
  // determined by the viewport-bounded flex chain, independent of the Monaco
  // wrapper's explicit pixel height (see file header).
  const hostRef = useRef<HTMLDivElement>(null);
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
  //
  // WIKI4AI-79: observe the dedicated flex host, not .markdown-editor itself.
  // The host's height comes from the viewport-bounded flex chain (flex: 1;
  // min-height: 0), so it is stable regardless of the Monaco wrapper's
  // explicit pixel height — observing the outer container closed a feedback
  // loop (container height = toolbar + Monaco wrapper → measured value fed
  // back into the wrapper height → unbounded page growth). The host only
  // exists once monacoReady, hence the dependency.
  useEffect(() => {
    const host = hostRef.current;
    if (!host) return;

    // Initial measurement
    const updateHeight = () => {
      const newHeight = host.clientHeight;
      if (newHeight > 0) {
        setEditorHeight(newHeight);
      }
    };
    updateHeight();

    // Watch for size changes
    const observer = new ResizeObserver(updateHeight);
    observer.observe(host);

    return () => {
      observer.disconnect();
    };
  }, [monacoReady]);

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
    <div className="markdown-editor">
      {!monacoReady ? (
        /* Waiting for Monaco + wiki4ai-dark theme registration */
        <div className="markdown-editor__loading">Loading editor…</div>
      ) : (
        <>
          {/* Formatting toolbar above the Monaco editor */}
          {!readOnly && (
            <MarkdownToolbar onInsert={handleInsert} />
          )}
          {/* WIKI4AI-79: stable measurement host — sized by the flex chain,
              independent of the Monaco wrapper's explicit pixel height. */}
          <div className="markdown-editor__host" ref={hostRef}>
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
                // WIKI4AI-83: disable bracket pair colorization. The markdown
                // tokenizer emits `[[WikiLink]` as a single string.link token, so
                // the final `]` of every valid wiki link looks like an unbalanced
                // closing bracket and Monaco paints it red
                // (unexpected-closing-bracket). Disabling the colorization provider
                // removes those decorations at the source; brackets keep their
                // normal token colors. Hover matching (bracketPairColorization's
                // sibling, bracketMatching) stays enabled.
                bracketPairColorization: { enabled: false },
              }}
            />
          </div>
        </>
      )}
    </div>
  );
};

export default MarkdownEditor;
