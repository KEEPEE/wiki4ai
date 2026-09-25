/**
 * WIKI4AI-73 (originally in ProfilePage) / WIKI4AI-85: switch the UI language.
 *
 * The preference is persisted via PUT /api/v1/auth/me { language } and applied
 * instantly through applyUserLanguage(). On failure the previous language is
 * restored. Extracted from ProfilePage because WIKI4AI-85 moved the language
 * switch out of the Profile page into the top-nav user menu.
 */
import { useCallback, useState } from 'react';
import { useAuth } from '../contexts/AuthContext';
import { apiPut } from '../services/apiClient';
import { applyUserLanguage, isSupportedLanguage, type AppLanguage } from '../i18n';

const API_BASE_URL = import.meta.env.VITE_API_URL || '/api/v1';

// WIKI4AI-73: mirrors USER_INFO_KEY in AuthContext — kept in sync so a language
// change is applied instantly on reload, before the /auth/me re-sync returns.
const USER_INFO_KEY = 'wiki4ai_user_info';

export interface UseUserLanguageResult {
  /** The user's current (persisted) UI language. */
  language: AppLanguage;
  /** True while the PUT /auth/me persistence roundtrip is in flight. */
  isUpdating: boolean;
  /** Switch to `lang` — applies immediately, persists via API, rolls back on failure. */
  change: (lang: AppLanguage) => Promise<void>;
}

export function useUserLanguage(): UseUserLanguageResult {
  const { user } = useAuth();
  const [language, setLanguage] = useState<AppLanguage>(
    isSupportedLanguage(user?.language) ? user.language : 'en',
  );
  const [isUpdating, setIsUpdating] = useState(false);

  const change = useCallback(
    async (lang: AppLanguage) => {
      if (!isSupportedLanguage(lang) || lang === language) return;

      setIsUpdating(true);
      const previous = language;
      setLanguage(lang);
      applyUserLanguage(lang); // instant UI switch, no reload

      try {
        await apiPut<unknown>(`${API_BASE_URL}/auth/me`, { language: lang });
        // Refresh the cached user so a page reload applies the new language
        // instantly, before the /auth/me re-sync in AuthContext returns.
        const cached = localStorage.getItem(USER_INFO_KEY);
        if (cached) {
          try {
            const parsed = JSON.parse(cached);
            parsed.language = lang;
            localStorage.setItem(USER_INFO_KEY, JSON.stringify(parsed));
          } catch {
            // Corrupted cache — the /auth/me re-sync will fix it on next load.
          }
        }
      } catch {
        // Roll back the UI switch if persistence failed. The menu reflects the
        // reverted language immediately, so no separate error surface is needed.
        setLanguage(previous);
        applyUserLanguage(previous);
      } finally {
        setIsUpdating(false);
      }
    },
    [language],
  );

  return { language, isUpdating, change };
}
