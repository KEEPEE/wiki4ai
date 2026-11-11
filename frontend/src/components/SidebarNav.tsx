import { NavLink, Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../contexts/AuthContext';
import WikiLogo from './WikiLogo';
import ThemeSwitch from './ThemeSwitch';
import './WikiLogo.css';
import './SidebarNav.css';

/* ── Inline SVG icons (no emoji — the container has no emoji font) ─────── */

const stroke = {
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 2,
  strokeLinecap: 'round',
  strokeLinejoin: 'round',
} as const;

function Svg({ children }: { children: React.ReactNode }) {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true" {...stroke}>
      {children}
    </svg>
  );
}

const DashboardIcon = () => (
  <Svg>
    <rect x="3" y="3" width="7" height="9" rx="1.5" />
    <rect x="14" y="3" width="7" height="5" rx="1.5" />
    <rect x="14" y="12" width="7" height="9" rx="1.5" />
    <rect x="3" y="16" width="7" height="5" rx="1.5" />
  </Svg>
);

const SearchIcon = () => (
  <Svg>
    <circle cx="11" cy="11" r="7" />
    <path d="M21 21l-4.3-4.3" />
  </Svg>
);

const CalendarIcon = () => (
  <Svg>
    <rect x="3" y="4" width="18" height="18" rx="2" />
    <path d="M16 2v4M8 2v4M3 10h18" />
  </Svg>
);

const BoardIcon = () => (
  <Svg>
    <rect x="3" y="4" width="5" height="16" rx="1" />
    <rect x="10" y="4" width="5" height="11" rx="1" />
    <rect x="17" y="4" width="4" height="8" rx="1" />
  </Svg>
);

const VaultIcon = () => (
  <Svg>
    <path d="M20 7h-3a2 2 0 0 0-2-2H9a2 2 0 0 0-2 2H4a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2V9a2 2 0 0 0-2-2z" />
    <rect x="10" y="11" width="4" height="4" rx="1" />
  </Svg>
);

const AgentsIcon = () => (
  <Svg>
    <rect x="4" y="7" width="16" height="12" rx="2" />
    <path d="M12 3v4" />
    <circle cx="12" cy="2" r="1" />
    <circle cx="9" cy="13" r="1" />
    <circle cx="15" cy="13" r="1" />
    <path d="M9 17h6" />
  </Svg>
);

const ResourcesIcon = () => (
  <Svg>
    <rect x="3" y="4" width="18" height="6" rx="1.5" />
    <rect x="3" y="14" width="18" height="6" rx="1.5" />
    <path d="M7 7h.01M7 17h.01" />
  </Svg>
);

const MetricsIcon = () => (
  <Svg>
    <path d="M4 20h16" />
    <rect x="5" y="11" width="3" height="7" rx="1" />
    <rect x="10.5" y="7" width="3" height="11" rx="1" />
    <rect x="16" y="13" width="3" height="5" rx="1" />
  </Svg>
);

const ProfileIcon = () => (
  <Svg>
    <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
    <circle cx="12" cy="7" r="4" />
  </Svg>
);

const AdminIcon = () => (
  <Svg>
    <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
  </Svg>
);

interface NavDef {
  to: string;
  /** i18n kľúč popisku — existujúce `layout.*` kľúče sa preberajú z UserMenu. */
  labelKey: string;
  icon: () => React.ReactElement;
  testId: string;
  /** Presná zhoda bez potomkov (index route). */
  end?: boolean;
  /** Položka len pre ADMIN (rovnaké pravidlo ako UserMenu). */
  adminOnly?: boolean;
}

interface NavSection {
  titleKey: string;
  items: NavDef[];
}

/**
 * Sekcie sidebaru. Popisky sa PREBÍRAJÚ z existujúcich `layout.*` kľúčov
 * (UserMenu ich používa v dropdown menu), preto i18nParity nepridáva duplicitné
 * preklady — nové sú len `layout.nav.*` (sekcie, Dashboard, Search).
 */
const NAV_SECTIONS: NavSection[] = [
  {
    titleKey: 'layout.nav.sectionWiki',
    items: [
      { to: '/', labelKey: 'layout.nav.dashboard', icon: DashboardIcon, testId: 'sidebar-dashboard', end: true },
      { to: '/search', labelKey: 'layout.nav.search', icon: SearchIcon, testId: 'sidebar-search' },
      { to: '/calendar', labelKey: 'layout.calendar', icon: CalendarIcon, testId: 'sidebar-calendar' },
      { to: '/board', labelKey: 'layout.board', icon: BoardIcon, testId: 'sidebar-board' },
      { to: '/vault', labelKey: 'layout.vault', icon: VaultIcon, testId: 'sidebar-vault' },
    ],
  },
  {
    titleKey: 'layout.nav.sectionCoordination',
    items: [
      { to: '/agents', labelKey: 'layout.agents', icon: AgentsIcon, testId: 'sidebar-agents' },
      { to: '/resources', labelKey: 'layout.resources', icon: ResourcesIcon, testId: 'sidebar-resources' },
      { to: '/metrics', labelKey: 'layout.metrics', icon: MetricsIcon, testId: 'sidebar-metrics' },
    ],
  },
  {
    titleKey: 'layout.nav.sectionAccount',
    items: [
      { to: '/profile', labelKey: 'layout.profile', icon: ProfileIcon, testId: 'sidebar-profile' },
      { to: '/admin/users', labelKey: 'layout.admin', icon: AdminIcon, testId: 'sidebar-admin-users', adminOnly: true },
    ],
  },
];

interface SidebarNavProps {
  /** Otvorený off-canvas sidebar (mobil < 900 px). */
  open: boolean;
  /**
   * WIKI4AI-195 (DIZ-4 item 8, WCAG 2.4.3): off-canvas sidebar je síce
   * vizuálne aj geometricky mimo obrazovky, ale jeho odkazy ostávajú v
   * tab ráde. inert ich z neho reálne vyberie — CSS na to selektor
   * nemá, preto to posielame z useMediaQuery v Layout.tsx.
   */
  inert?: boolean;
  onNavigate: () => void;
}

/**
 * WIKI4AI-185: glass-admin sidebar (tm-607 LAYOUT + SIDEBAR).
 *
 * 280→264 px fixed, `background: var(--glass)` + `backdrop-filter: blur(20px)`
 * + 1 px pravý okraj. Blur je vidieť len cez `.background` + orb-y
 * (viz AmbientBackground) — bez nich by bol sidebar plochý.
 *
 * Pôvodné testy počítali s `data-testid="nav-admin-users"` / `nav-vault` /
 * `nav-profile` v zmysle „tieto ovládacie prvky v hlavnej navigácii SU“ —
 * sidebar im vráti rovnaké id, takže testy na UserMenu ostávajú platné
 * (dropdown menu má vlastné `user-menu-*` id).
 */
export default function SidebarNav({ open, inert, onNavigate }: SidebarNavProps) {
  const { t } = useTranslation();
  const { user } = useAuth();
  const location = useLocation();

  const isAdmin = (user as { role?: string } | null)?.role === 'ADMIN';
  const initials = (user?.username || '?').trim().charAt(0).toUpperCase();

  return (
    <aside
      className={`sidebar${open ? ' sidebar-open' : ''}`}
      aria-label={t('layout.nav.sectionWiki')}
      inert={inert}
    >
      <div className="sidebar-header">
        {/* DIZ-4 item 11: odkaz obsahuje len SVG logo, teda NEMÁ prístupný
            názov — čítačka z neho vyhlási prázdne link. aria-label je
            jediný spôsob, ako pomenovať odkaz bez textového childa. */}
        <Link
          to="/"
          className="logo"
          data-testid="nav-logo"
          aria-label={t('layout.nav.homeAria')}
          onClick={onNavigate}
        >
          <WikiLogo size={40} />
        </Link>
        <Link to="/" className="logo-text" onClick={onNavigate}>
          Wiki<span className="logo-text-accent">4AI</span>
        </Link>
      </div>

      <nav className="nav-menu">
        {NAV_SECTIONS.map((section) => {
          const items = section.items.filter((item) => !item.adminOnly || isAdmin);
          if (items.length === 0) return null;
          return (
            <div className="nav-section" key={section.titleKey}>
              <p className="nav-section-title">{t(section.titleKey)}</p>
              {items.map((item) => {
                const Icon = item.icon;
                return (
                  <div className="nav-item" key={item.to}>
                    <NavLink
                      to={item.to}
                      end={item.end}
                      onClick={onNavigate}
                      data-testid={item.testId}
                      className={({ isActive }) =>
                        `nav-link${isActive ? ' nav-link-active' : ''}`
                      }
                      /* `aria-current="page"` tu nie je nastavené —
                       * `NavLink` ho dáva SÁM (výchozí hodnota
                       * `ariaCurrentProp = 'page'`, aplikuje sa len pri
                       * `isActive`; overené v
                       * node_modules/react-router/dist/development/
                       * chunk-4N6VE7H7.mjs:10507,10543). Je to bežný
                       * reťazcový prop, NIE render prop — funkcia by sa
                       * vypísala do atribútu ako garbage. */
                    >
                      <Icon />
                      <span>{t(item.labelKey)}</span>
                    </NavLink>
                  </div>
                );
              })}
            </div>
          );
        })}
      </nav>

      <div className="sidebar-footer">
        <Link
          to="/profile"
          className={`user-profile${location.pathname === '/profile' ? ' user-profile-active' : ''}`}
          onClick={onNavigate}
          data-testid="sidebar-user-profile"
          /* DIZ-4 item 11 (WIKI4AI-195): na rozdiel od `NavLink` nižšie
           * je toto `<Link>`, ktorý `aria-current` nedáva sám — aktívny
           * profil sa doteraz rozoznával len triedou `user-profile-active`,
           * teda vizuálne. Asistívna technológia o ňom nevedela nič. */
          aria-current={location.pathname === '/profile' ? 'page' : undefined}
        >
          <span className="user-avatar" aria-hidden="true">
            {initials}
          </span>
          <span className="user-meta">
            <span className="user-name">{user?.username ?? '—'}</span>
            <span className="user-role">{isAdmin ? 'ADMIN' : 'USER'}</span>
          </span>
        </Link>
        <ThemeSwitch />
      </div>
    </aside>
  );
}
