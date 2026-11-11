import { createContext, useContext } from 'react';

import { DEFAULT_THEME } from './themes';
import type { ThemeId } from './themes';

export interface ThemeContextValue {
  theme: ThemeId;
  setTheme: (theme: ThemeId) => void;
}

/**
 * Context + hook mimo `ThemeProvider.tsx` — Fast refresh funguje len súborom,
 * ktoré exportujú iba komponenty (react-refresh `only-export-components`).
 */
export const ThemeContext = createContext<ThemeContextValue>({
  theme: DEFAULT_THEME,
  setTheme: () => undefined,
});

export function useTheme(): ThemeContextValue {
  return useContext(ThemeContext);
}
