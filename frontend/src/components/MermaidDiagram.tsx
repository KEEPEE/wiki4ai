/**
 * MermaidDiagram component.
 * Renders Mermaid syntax code as an SVG diagram using the mermaid library.
 * Supports loading states, error handling, and multiple independent diagrams on one page.
 *
 * Key design decisions:
 * - Stable ID based on content hash (not a global counter) to prevent duplicate ID errors
 *   when components rapidly mount/unmount during split-view editing
 * - useEffect cleanup removes mermaid's DOM element to prevent memory leaks
 * - Cancelled flag prevents race conditions where stale async results overwrite state
 */

import React, { useState, useEffect, useMemo } from 'react';
import { useTranslation } from 'react-i18next';
import mermaid from 'mermaid';
import { useTheme } from '../theme/useTheme';
import { DEFAULT_THEME } from '../theme/themes';
import type { ThemeId } from '../theme/themes';
import { sanitizeDiagramSvg } from '../utils/svgSanitizer';
import './MermaidDiagram.css';

/**
 * WIKI4AI-185: Mermaid init sleduje tému WebUI.
 *
 * Mermaid si farby kreslí do SVG sám — CSS tokeny mu nepomôžu, preto palety
 * duplicujeme tu (hex, nie var()). `applyMermaidTheme` sa volá pred každým
 * renderom; volanie `mermaid.initialize` je idempotentné a lacne.
 */
const MERMAID_PALETTES: Record<ThemeId, { theme: 'dark' | 'default'; primaryColor: string; primaryTextColor: string; secondaryColor: string; tertiaryColor: string; lineColor: string }> = {
  // glass-dark — DEFAULT, deep forest + emerald (tm-607)
  'glass-dark': {
    theme: 'dark',
    primaryColor: '#16281e',
    primaryTextColor: 'rgba(245, 245, 244, 0.88)',
    secondaryColor: '#0f2118',
    tertiaryColor: '#08120e',
    lineColor: '#34d399',
  },
  // glass-light — svetlé karty, tmavý text (inak by bol diagram čierny text
  // na svetlom SVG, ale čiary v tmavom neonovom tóne nečitelné)
  'glass-light': {
    theme: 'default',
    primaryColor: '#e6eed9',
    primaryTextColor: 'rgba(20, 32, 26, 0.9)',
    secondaryColor: '#d5e7d8',
    tertiaryColor: '#f4efdd',
    lineColor: '#047857',
  },
  // nexaverse — neon tyrkysová / magenta (tm-603)
  nexaverse: {
    theme: 'dark',
    primaryColor: '#1a1a2e',
    primaryTextColor: 'rgba(255, 255, 255, 0.85)',
    secondaryColor: '#16213e',
    tertiaryColor: '#0f3460',
    lineColor: '#00f0ff',
  },
};

export function applyMermaidTheme(theme: ThemeId): void {
  const palette = MERMAID_PALETTES[theme] ?? MERMAID_PALETTES[DEFAULT_THEME];
  mermaid.initialize({
    startOnLoad: false,
    theme: palette.theme,
    // WIKI4AI-198: `strict`, nie `loose`. Jediný behaviorálny rozdiel medzi
    // týmito dvoma režimami je, že v `loose` Mermaid **preskočí** vlastnú
    // DOMPurify pasu na výslednom SVG (v mermaid.core.mjs je to jediná vetva
    // na `securityLevel`). V `strict` teda beží navyše pas, ktorý knižnica
    // aplikuje sama a ktorý je stavaný tak, aby nepoškodil jej vlastný výstup.
    //
    // Cena tohto prepnutia je nula: z 773 dokumentov na dev má 74 Mermaid
    // blok, v 33 z nich je HTML — a vždy je to iba `<br/>`, ktoré `strict`
    // nepoškodí (rieši sa pri parsovaní labelu, nie vo finálnom SVG pase).
    // `click`, `link`, `linkStyle` a `javascript:` nepoužíva 0 dokumentov.
    // Strážca to má `mermaidStrictRender.test.tsx`.
    //
    // Sink sanitizáciu (`sanitizeDiagramSvg`) to nenahrádza a ani nezbytočnú
    // nerobí: tá je jedinú obranou pre kroki SVG, ktoré Mermaid vôbec nevidí.
    securityLevel: 'strict',
    themeVariables: {
      primaryColor: palette.primaryColor,
      primaryTextColor: palette.primaryTextColor,
      secondaryColor: palette.secondaryColor,
      tertiaryColor: palette.tertiaryColor,
      lineColor: palette.lineColor,
      fontSize: '14px',
    },
  });
}

// Predvolená téma (nexaverse) — inicializácia pri module load, aby prvý render
// mal správne nastavenie aj bez ThemeProvider (testy).
applyMermaidTheme(DEFAULT_THEME);

/**
 * Simple hash function for generating stable diagram IDs from content.
 * Uses DJB2 algorithm — fast, deterministic, good distribution.
 */
function simpleHash(str: string): string {
  let hash = 0;
  for (let i = 0; i < str.length; i++) {
    const char = str.charCodeAt(i);
    hash = ((hash << 5) - hash) + char;
    hash |= 0; // Convert to 32-bit integer
  }
  return Math.abs(hash).toString(16);
}

/**
 * WIKI4AI-84: force the rendered SVG to its natural pixel size.
 *
 * Mermaid emits a `viewBox` plus either an explicit width or `width="100%"`.
 * With `width="100%"` the CSS rule `max-width: none` alone cannot restore the
 * intrinsic size, so wide diagrams (e.g. the Architecture Overview flowchart)
 * were downscaled to ~50% and labels became unreadable. Mermaid draws in px
 * units at 96 dpi, so the viewBox width/height ARE the natural pixel size —
 * rewrite the width/height attributes from it. SVGs without a viewBox (e.g.
 * test mocks) are returned unchanged. The wrapper then scrolls horizontally
 * when the diagram is wider than the column instead of shrinking it.
 */
export function withNaturalSize(renderedSvg: string): string {
  const vb = renderedSvg.match(/<svg[^>]*\bviewBox="([^"]+)"/);
  if (!vb) return renderedSvg;
  const parts = vb[1].trim().split(/[\s,]+/).map(Number);
  if (parts.length < 4 || !Number.isFinite(parts[2]) || !Number.isFinite(parts[3])) {
    return renderedSvg;
  }
  const w = Math.max(1, Math.round(parts[2]));
  const h = Math.max(1, Math.round(parts[3]));
  // Operate on the ROOT svg tag only (up to its closing '>'). Mermaid output
  // contains nested elements with their own width/height attributes (e.g. the
  // background <rect>), and a whole-string replace would clobber them instead
  // of sizing the root — found live on .219 during WIKI4AI-84 verification.
  const tagStart = vb.index ?? 0;
  const tagEnd = renderedSvg.indexOf('>', tagStart);
  if (tagEnd === -1) return renderedSvg;
  let tag = renderedSvg.slice(0, tagEnd + 1);
  if (/\bwidth="[^"]*"/.test(tag)) {
    tag = tag.replace(/\bwidth="[^"]*"/, `width="${w}"`);
  } else {
    tag = tag.replace(/<svg/, `<svg width="${w}"`);
  }
  if (/\bheight="[^"]*"/.test(tag)) {
    tag = tag.replace(/\bheight="[^"]*"/, `height="${h}"`);
  } else {
    tag = tag.replace(/<svg/, `<svg height="${h}"`);
  }
  return tag + renderedSvg.slice(tagEnd + 1);
}

interface MermaidDiagramProps {
  /** Mermaid syntax code to render */
  code: string;
  /** Optional CSS class names */
  className?: string;
}

const MermaidDiagram: React.FC<MermaidDiagramProps> = ({ code, className }) => {
  const { t } = useTranslation();
  const [svg, setSvg] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  // Stable ID based on content hash — same diagram code always produces the same ID.
  // This prevents duplicate ID errors when components rapidly mount/unmount during editing.
  // WIKI4AI-185: téma sa zahŕňa do VSTUPU hashu (nie ako suffix ID) — mermaid
  // cache-uje render podľa id, takže bez toho by prepnutie témy nič neprekreslilo
  // a diagram by ostal v starej palete. Format ID ostáva `mermaid-<hex>`.
  const { theme } = useTheme();
  const stableId = useMemo(
    () => `mermaid-${simpleHash(`${code.slice(0, 100)}::${theme}`)}`,
    [code, theme],
  );

  useEffect(() => {
    // Handle empty/whitespace-only input
    if (!code?.trim()) {
      setSvg(null);
      setError(null);
      setIsLoading(false);
      return;
    }

    setIsLoading(true);
    setError(null);
    setSvg(null);

    let cancelled = false;

    // WIKI4AI-185: paleta musi byť nastavená pred renderom
    applyMermaidTheme(theme);

    mermaid.render(stableId, code)
      .then(({ svg: renderedSvg }) => {
        if (!cancelled) {
          // WIKI4AI-84: pin the SVG to its natural pixel size so the CSS can
          // scroll wide diagrams instead of downscaling them.
          //
          // WIKI4AI-198: `sanitizeDiagramSvg` je posledný krok pred tým, ako
          // markup putuje do state a odtiaľ do `dangerouslySetInnerHTML`.
          // Mermaid si od `strict` own pasu robí, ale tá naša je to, čomu
          // veríme: nezávisí od nastavenia knižnice ani od jej verzie.
          // Sanitizuje sa až po `withNaturalSize`, aby žiadna ďalšia úprava
          // markupu neprebila sanitizáciu.
          setSvg(sanitizeDiagramSvg(withNaturalSize(renderedSvg)));
        }
      })
      .catch((err) => {
        if (!cancelled) {
          const message = err instanceof Error ? err.message : t('diagrams.renderFailed');
          setError(message);
        }
      })
      .finally(() => {
        if (!cancelled) {
          setIsLoading(false);
        }
      });

    return () => {
      cancelled = true;
      // Clean up mermaid's temporary DOM element to prevent duplicate ID errors
      // and memory leaks when the component unmounts or code changes rapidly
      const el = document.getElementById(stableId);
      if (el) {
        el.remove();
      }
    };
  }, [code, stableId, theme]);

  return (
    <div className={`mermaid-diagram ${className ?? ''}`}>
      {isLoading && (
        <div className="mermaid-diagram__loading" role="status">
          <div className="mermaid-diagram__spinner" />
          <span>{t('diagrams.loading')}</span>
        </div>
      )}

      {error && !isLoading && (
        <div className="mermaid-diagram__error" role="alert">
          <strong>{t('diagrams.errorLabel')}:</strong>
          <p>{error}</p>
        </div>
      )}

      {!isLoading && !error && svg && (
        <div
          className="mermaid-diagram__svg"
          dangerouslySetInnerHTML={{ __html: svg }}
        />
      )}
    </div>
  );
};

export default MermaidDiagram;
