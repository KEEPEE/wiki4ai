/**
 * WIKI4AI-85: single top-nav user menu.
 *
 * Replaces the old debug-styled auth cluster (Vault / Admin / username / Logout
 * badges with hardcoded inline backgrounds) with ONE initials-avatar + chevron
 * trigger opening a shared <Menu> dropdown:
 *   Profile · Vault · Admin (role-gated, ADMIN only) · Language (EN/SK switch,
 *   moved here from the Profile page) · Logout.
 *
 * All colors come from theme variables (see Menu.css); no inline colors.
 */
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../contexts/AuthContext';
import { useUserLanguage } from '../hooks/useUserLanguage';
import { SUPPORTED_LANGUAGES, type AppLanguage } from '../i18n';
import { Menu, MenuItem, MenuDivider } from './Menu';

// ── Inline SVG icons (no emoji — the container has no emoji font) ─────────

function UserIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
      <circle cx="12" cy="7" r="4" />
    </svg>
  );
}

function VaultIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 7h-3a2 2 0 0 0-2-2H9a2 2 0 0 0-2 2H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2z" />
      <rect x="10" y="11" width="4" height="4" rx="1" />
    </svg>
  );
}

function ShieldIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
    </svg>
  );
}

function LogoutIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
      <polyline points="16 17 21 12 16 7" />
      <line x1="21" y1="12" x2="9" y2="12" />
    </svg>
  );
}

function ChevronDownIcon() {
  return (
    <svg className="user-menu-chevron" width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <polyline points="6 9 12 15 18 9" />
    </svg>
  );
}

export default function UserMenu() {
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const { language, isUpdating, change: changeLanguage } = useUserLanguage();

  if (!user) return null;

  const isAdmin = (user as { role?: string }).role === 'ADMIN';
  const initials = (user.username || '?').trim().charAt(0).toUpperCase();

  const handleLogout = () => {
    logout();
    navigate('/login');
  };

  return (
    <Menu
      triggerClassName="user-menu-trigger"
      ariaLabel={t('layout.userMenuAria')}
      testId="user-menu-button"
      panelTestId="user-menu-panel"
      align="end"
      trigger={
        <>
          <span className="user-menu-avatar" data-testid="user-menu-avatar" aria-hidden="true">
            {initials}
          </span>
          <ChevronDownIcon />
        </>
      }
    >
      <MenuItem icon={<UserIcon />} onClick={() => navigate('/profile')} testId="user-menu-profile">
        {t('layout.profile')}
      </MenuItem>
      <MenuItem icon={<VaultIcon />} onClick={() => navigate('/vault')} testId="user-menu-vault">
        {t('layout.vault')}
      </MenuItem>
      {isAdmin && (
        <MenuItem icon={<ShieldIcon />} onClick={() => navigate('/admin/users')} testId="user-menu-admin">
          {t('layout.admin')}
        </MenuItem>
      )}

      <MenuDivider />

      {/* Language switch — moved from the Profile page (WIKI4AI-85). Toggling
          keeps the menu open so the UI change is visible in place. */}
      <div className="menu-lang-row" data-testid="user-menu-language-row">
        <span className="menu-lang-label">{t('layout.language')}</span>
        <div className="lang-toggle" role="group" aria-label={t('layout.language')}>
          {SUPPORTED_LANGUAGES.map((code) => (
            <button
              key={code}
              type="button"
              className={`lang-option${language === code ? ' lang-option-active' : ''}`}
              aria-pressed={language === code}
              disabled={isUpdating}
              data-menu-item
              data-testid={`user-menu-lang-${code}`}
              onClick={() => void changeLanguage(code as AppLanguage)}
            >
              {code.toUpperCase()}
            </button>
          ))}
        </div>
      </div>

      <MenuDivider />

      <MenuItem danger icon={<LogoutIcon />} onClick={handleLogout} testId="user-menu-logout">
        {t('layout.logout')}
      </MenuItem>
    </Menu>
  );
}
