/**
 * WIKI4AI-198: `securityLevel` sa nastavuje na jedinom mieste a prepnutím z
 * `loose` na `strict` sa nesmie rozhodiť paleta ani jednej identity.
 *
 * Mermaid si farby kreslí do SVG sám (CSS tokeny mu nepomôžu), preto
 * `MermaidDiagram` duplicuje palety troch identít a `applyMermaidTheme` ich
 * posiela do `mermaid.initialize` pred každým renderom. `securityLevel` je
 * v tom istom volaní — test preto naraz stráži obe veci:
 *
 *  - `securityLevel` je `strict` (druhý pokus vrátiť `loose` sem padne),
 *  - pre každú identitu (`glass-dark`, `glass-light`, `nexaverse`) zostávajú
 *    hex hodnoty aj `theme` (`dark` / `default`) nedotknuté.
 *
 * Mermaid je zámok: netreba mu renderovať, meráme len to, čo mu pošleme.
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';

const initialize = vi.fn();

vi.mock('mermaid', () => ({
  default: {
    initialize: (...args: unknown[]) => initialize(...args),
    render: vi.fn(async () => ({ svg: '<svg></svg>' })),
  },
}));

// Import až po mocku — modul pri load volá `applyMermaidTheme(DEFAULT_THEME)`.
const { applyMermaidTheme } = await import('../components/MermaidDiagram');

type MermaidInit = {
  startOnLoad: boolean;
  theme: string;
  securityLevel: string;
  themeVariables: Record<string, string>;
};

function lastInitializeCall(): MermaidInit {
  const call = initialize.mock.calls.at(-1);
  expect(call, 'applyMermaidTheme nevolal mermaid.initialize').toBeDefined();
  return call![0] as MermaidInit;
}

describe('applyMermaidTheme (WIKI4AI-185 palety + WIKI4AI-198 securityLevel)', () => {
  beforeEach(() => {
    initialize.mockClear();
  });

  it('nastavuje securityLevel „strict" a nič iné', () => {
    applyMermaidTheme('glass-dark');
    const config = lastInitializeCall();
    expect(config.securityLevel).toBe('strict');
    // startOnLoad musí ostať false — inak by Mermaid pri loadu strieľal
    // render do prázdnej stránky a súbežné diagramy by si liezli do cesty.
    expect(config.startOnLoad).toBe(false);
  });

  const PALETTES: Array<[string, string, Record<string, string>]> = [
    ['glass-dark', 'dark', {
      primaryColor: '#16281e',
      secondaryColor: '#0f2118',
      tertiaryColor: '#08120e',
      lineColor: '#34d399',
    }],
    ['glass-light', 'default', {
      primaryColor: '#e6eed9',
      secondaryColor: '#d5e7d8',
      tertiaryColor: '#f4efdd',
      lineColor: '#047857',
    }],
    ['nexaverse', 'dark', {
      primaryColor: '#1a1a2e',
      secondaryColor: '#16213e',
      tertiaryColor: '#0f3460',
      lineColor: '#00f0ff',
    }],
  ];

  it.each(PALETTES)('identita %s si zachováva paletu aj po prepnutí na strict', (theme, mermaidTheme, colors) => {
    applyMermaidTheme(theme as 'glass-dark' | 'glass-light' | 'nexaverse');
    const config = lastInitializeCall();

    expect(config.theme).toBe(mermaidTheme);
    for (const [token, hex] of Object.entries(colors)) {
      expect(config.themeVariables[token]).toBe(hex);
    }
    // textová farba sa nesmie stratiť — bez nej by bol label neviditeľný
    expect(config.themeVariables.primaryTextColor).toMatch(/^(rgba?\(|#)/);
    expect(config.themeVariables.fontSize).toBe('14px');
    expect(config.securityLevel).toBe('strict');
  });

  it('neznáma identita nespadne, ale vráti predvolenú paletu', () => {
    applyMermaidTheme('neexistuje-téma' as 'glass-dark');
    const config = lastInitializeCall();
    // predvolená identita je glass-dark (tm-607), paleta musí byť jej
    expect(config.themeVariables.primaryColor).toBe('#16281e');
    expect(config.securityLevel).toBe('strict');
  });
});
