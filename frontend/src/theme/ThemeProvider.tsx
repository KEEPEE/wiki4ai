import { useCallback, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';

import { THEME_STORAGE_KEY, applyTheme, readStoredTheme } from './themes';
import type { ThemeId } from './themes';
import { ThemeContext } from './useTheme';

/**
 * Drží aktuálnu tému, zapisuje ju na `<html data-theme=…>` a do localStorage.
 *
 * Počiatočná hodnota sa číta v `useState` inicializátore, takže prvý render je
 * v tej istej téme, akú nastavil inline skript v `index.html` (FOUC guard) —
 * medzi prvým paintom a hydrataciou teda nedôjde k prebliknutiu.
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [theme, setThemeState] = useState<ThemeId>(() => readStoredTheme());

  useEffect(() => {
    applyTheme(theme);
  }, [theme]);

  const setTheme = useCallback((next: ThemeId) => {
    /* WIKI4AI-194 (DIZ-3 item 12): farby grafu meškali o jedno prepnutie.
     * `applyTheme` behal len v efekte, teda PO painte. `useThemeColors()`
     * ale číta `getComputedStyle(document.documentElement)` POČAS renderu
     * (`themeColors.ts:70-77`), takže render, ktorý nasleduje po
     * `setThemeState`, čítal tokeny ZATVORENÉHO `data-theme` — a ďalší
     * render už bez zmeny deps nenastal. Výsledok: po prepnutí témy mal
     * graf farby predchádzajúcej témy, kým sa niečo neprekreslilo.
     *
     * `applyTheme(next)` preto volám SYNCHRONNE PRED `setThemeState`:
     * `<html data-theme>` je prepísané skôr, než React vôbec začne
     * renderovať, takže `getComputedStyle` vidí nové tokeny. Efekt nižšie
     * ostáva — kryje počiatočný mount a ostatné cesty, ktoré menia tému
     * mimo tohto callbacku; `applyTheme` je idempotentný.
     *
     * Zmena patrí SEM, nie do `themeIdentity.ts`: ten súbor musí zostať
     * bez DOM (`vite.config.ts` kompiluje bez DOM lib), jsdom testy by
     * `Cannot find name 'document'` neodhalili. */
    applyTheme(next);
    setThemeState(next);
    try {
      localStorage.setItem(THEME_STORAGE_KEY, next);
    } catch {
      /* bez perzistencie — meníme tému len pre toto sedenie */
    }
  }, []);

  const value = useMemo(() => ({ theme, setTheme }), [theme, setTheme]);

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
}
