/**
 * WIKI4AI-85: single top-nav user menu.
 *
 * Replaces the old debug-styled auth cluster (Vault / Admin / username / Logout
 * badges with hardcoded inline backgrounds) with ONE initials-avatar + chevron
 * trigger opening a shared <Menu> dropdown:
 *   Profile · Calendar · Vault · Board · New events (WIKI4AI-165, only when the API
 *   reported something new) · Agents · Resources · Metrics · Admin (role-gated, ADMIN
 *   only) · Language (EN/SK switch, moved here from the Profile page) · Logout.
 *
 * All colors come from theme variables (see Menu.css); no inline colors.
 *
 * WIKI4AI-165: the unseen-events badge sits ON the trigger, so "something arrived" is
 * visible without opening the menu, and the menu carries the action that clears it.
 * The number itself is never produced here — see `UnseenEventsMarker.tsx` and
 * `src/utils/unseenEvents.ts` for where it comes from and what it does not cover.
 */
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../contexts/AuthContext';
import { useUserLanguage } from '../hooks/useUserLanguage';
import { SUPPORTED_LANGUAGES, type AppLanguage } from '../i18n';
import { Menu, MenuItem, MenuDivider } from './Menu';
import {
  UnseenEventsBadge,
  UnseenEventsMenuItem,
  UnseenEventsProvider,
} from './UnseenEventsMarker';

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

function CalendarIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="4" width="18" height="18" rx="2" ry="2" />
      <line x1="16" y1="2" x2="16" y2="6" />
      <line x1="8" y1="2" x2="8" y2="6" />
      <line x1="3" y1="10" x2="21" y2="10" />
    </svg>
  );
}

/** WIKI4AI-161: coordination board (three columns). */
function BoardIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="4" width="5" height="16" rx="1" />
      <rect x="10" y="4" width="5" height="11" rx="1" />
      <rect x="17" y="4" width="4" height="8" rx="1" />
    </svg>
  );
}

/** WIKI4AI-163: agents (a headless unit). */
function AgentsIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="4" y="7" width="16" height="12" rx="2" />
      <path d="M12 3v4" />
      <circle cx="12" cy="2" r="1" />
      <circle cx="9" cy="13" r="1" />
      <circle cx="15" cy="13" r="1" />
      <path d="M9 17h6" />
    </svg>
  );
}

/** WIKI4AI-164: resources (two capacity racks). */
function ResourceIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="4" width="18" height="6" rx="1.5" />
      <rect x="3" y="14" width="18" height="6" rx="1.5" />
      <line x1="7" y1="7" x2="7.01" y2="7" />
      <line x1="7" y1="17" x2="7.01" y2="17" />
    </svg>
  );
}

/** WIKI4AI-164: metrics (three bars). */
function MetricsIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <line x1="4" y1="20" x2="20" y2="20" />
      <rect x="5" y="11" width="3" height="7" rx="1" />
      <rect x="10.5" y="7" width="3" height="11" rx="1" />
      <rect x="16" y="13" width="3" height="5" rx="1" />
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

interface InnerProps {
  isAdmin: boolean;
  initials: string;
}

/**
 * The menu itself, split out of `UserMenu` because the marker's count comes from a
 * context: a component cannot read a context provider it renders itself, so
 * `UserMenu` owns the provider and this component consumes it.
 */
function UserMenuInner({ isAdmin, initials }: InnerProps) {
  const { logout } = useAuth();
  const navigate = useNavigate();
  const { t } = useTranslation();
  const { language, isUpdating, change: changeLanguage } = useUserLanguage();

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
          {/* WIKI4AI-165: visible without opening the menu, gone once looked at. */}
          <UnseenEventsBadge />
          <ChevronDownIcon />
        </>
      }
    >
      <MenuItem icon={<UserIcon />} onClick={() => navigate('/profile')} testId="user-menu-profile">
        {t('layout.profile')}
      </MenuItem>
      {/* WIKI4AI-97: calendar (month view + events) */}
      <MenuItem icon={<CalendarIcon />} onClick={() => navigate('/calendar')} testId="user-menu-calendar">
        {t('layout.calendar')}
      </MenuItem>
      <MenuItem icon={<VaultIcon />} onClick={() => navigate('/vault')} testId="user-menu-vault">
        {t('layout.vault')}
      </MenuItem>
      {/* WIKI4AI-161: coordination board (status columns, lease countdown, capacity) */}
      <MenuItem icon={<BoardIcon />} onClick={() => navigate('/board')} testId="user-menu-board">
        {t('layout.board')}
      </MenuItem>
      {/* WIKI4AI-165: rendered only when the API reported something new. Opening it
          marks the content as seen, which is what makes the badge disappear. */}
      <UnseenEventsMenuItem />
      {/* WIKI4AI-163: agents, their project role and their tokens */}
      <MenuItem icon={<AgentsIcon />} onClick={() => navigate('/agents')} testId="user-menu-agents">
        {t('layout.agents')}
      </MenuItem>
      {/* WIKI4AI-164: capacity, live reservations and their TTL */}
      <MenuItem icon={<ResourceIcon />} onClick={() => navigate('/resources')} testId="user-menu-resources">
        {t('layout.resources')}
      </MenuItem>
      {/* WIKI4AI-164: time in state, cycle time, lease expiries */}
      <MenuItem icon={<MetricsIcon />} onClick={() => navigate('/metrics')} testId="user-menu-metrics">
        {t('layout.metrics')}
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

export default function UserMenu() {
  const { user } = useAuth();

  if (!user) return null;

  const isAdmin = (user as { role?: string }).role === 'ADMIN';
  const initials = (user.username || '?').trim().charAt(0).toUpperCase();

  return (
    <UnseenEventsProvider>
      <UserMenuInner isAdmin={isAdmin} initials={initials} />
    </UnseenEventsProvider>
  );
}
