/**
 * WIKI4AI-73: WebUI internationalization (i18next + react-i18next).
 *
 * Locales: `en` (default + fallback) and `sk`. The active language is driven by
 * the authenticated user's saved preference (`users.language`, persisted via
 * PUT /api/v1/auth/me):
 *  - after login → taken from the login response (no extra roundtrip);
 *  - on page load with a stored session → applied immediately from the cached
 *    user info, then re-synced from GET /api/v1/auth/me (authoritative — this is
 *    what makes a preference change on another device take effect);
 *  - anonymous pages (login/setup/register) and after logout → always `en`.
 */
import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import en from './locales/en.json';
import sk from './locales/sk.json';

export const SUPPORTED_LANGUAGES = ['en', 'sk'] as const;
export type AppLanguage = (typeof SUPPORTED_LANGUAGES)[number];

/** Default UI language for anonymous visitors and users without a saved preference. */
export const DEFAULT_LANGUAGE: AppLanguage = 'en';

export function isSupportedLanguage(value: unknown): value is AppLanguage {
  return typeof value === 'string' && (SUPPORTED_LANGUAGES as readonly string[]).includes(value);
}

i18n.use(initReactI18next).init({
  resources: {
    en: { translation: en },
    sk: { translation: sk },
  },
  lng: DEFAULT_LANGUAGE,
  fallbackLng: DEFAULT_LANGUAGE,
  interpolation: {
    // React already escapes rendered values.
    escapeValue: false,
  },
  returnNull: false,
});

/**
 * Apply a user's saved language preference to the running app.
 * Unknown/missing values fall back to the default ('en'). Returns the resolved
 * language so callers can persist or assert on it.
 */
export function applyUserLanguage(language?: string | null): AppLanguage {
  const resolved: AppLanguage = isSupportedLanguage(language) ? language : DEFAULT_LANGUAGE;
  if (i18n.language !== resolved && i18n.resolvedLanguage !== resolved) {
    void i18n.changeLanguage(resolved);
  }
  return resolved;
}

export default i18n;
