import { useTranslation } from 'react-i18next';
import { useTheme } from '../theme/useTheme';
import { THEME_META } from '../theme/themes';
import './ThemeSwitch.css';

/**
 * WIKI4AI-185 → WIKI4AI-192 (DIZ-1): segmentový prepínač 3 identít.
 *
 * Poradie segmentov aj ich ikony prichádzajú z `THEME_META`, ktoré je
 * odvodené od `THEME_ORDER` — jeden zdroj pravdy pre cyklus tém
 * (glass-dark → glass-light → nexaverse).
 *
 * Ikony sú glyfy ☾ / ☀ / ✦ (forum4Ai `THEME_ICONS`), nie emoji: sú to
 * bežné Unicode symboly s pokrytím v DejaVu/Noto, preto sa vykreslia aj v
 * prostredí bez emoji fontu. Prístupnosť: `role="group"` + `aria-pressed`
 * na každom segmente, preto čítačka oznámi aj aktuálnu tému, nielen tri
 * tlačidlá bez rozdielu.
 */
interface ThemeSwitchProps {
  /**
   * Predpona testid. Shell renderuje prepínač DVAKRÁT (sidebar + topbar pre
   * mobil), a duplicitné `data-testid` by rozbilo `getByTestId` v testoch —
   * druhá inštancia sa volá s `testIdPrefix="theme-top"`.
   */
  testIdPrefix?: string;
}

export default function ThemeSwitch({ testIdPrefix = 'theme' }: ThemeSwitchProps) {
  const { t } = useTranslation();
  const { theme, setTheme } = useTheme();

  return (
    <div className="theme-switch" role="group" aria-label={t('layout.theme.switchAria')} data-testid={`${testIdPrefix}-switch`}>
      {THEME_META.map((meta) => {
        const active = theme === meta.id;
        const hint = t(meta.hintKey);
        return (
          <button
            key={meta.id}
            type="button"
            className={active ? 'theme-option theme-option-active' : 'theme-option'}
            title={hint}
            aria-label={hint}
            aria-pressed={active}
            data-testid={`${testIdPrefix}-option-${meta.id}`}
            onClick={() => setTheme(meta.id)}
          >
            <span className="theme-option-icon" aria-hidden="true">
              {meta.icon}
            </span>
            <span className="theme-option-label">{t(meta.labelKey)}</span>
          </button>
        );
      })}
    </div>
  );
}
