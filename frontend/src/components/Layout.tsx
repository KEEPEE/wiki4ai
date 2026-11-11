import { useEffect, useLayoutEffect, useState } from 'react';
import { Outlet, Link, useLocation, useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../contexts/AuthContext';
import AmbientBackground from './AmbientBackground';
import SidebarNav from './SidebarNav';
import ThemeSwitch from './ThemeSwitch';
import UserMenu from './UserMenu';
import { signalContentReady } from '../utils/appReady';
import { useMediaQuery } from '../hooks/useMediaQuery';
import './GlobalSearchBar.css';
import './Layout.css';

interface LayoutProps {
  children?: React.ReactNode;
}

/**
 * WIKI4AI-185: shell prenesený na template tm-607 Glass Admin.
 *
 * Bolo: sticky top-nav (logo + search + user menu) bez navigácie.
 * Je: glass-admin sidebar (`.sidebar` s navigáciou) + `main > header` topbar
 * (hľadanie, prepínač tém, používateľ) + obsah vedľa sidebaru.
 *
 * Dve veci sa MUSIA zachovať, inak sa rozpadnú iné stránky:
 *  1. `<main>` ostáva priamy rodič `<header>` — CSS stránky viažu výšku na
 *     `main > header` (meranie --w4a-nav-height, DocumentEditor.css,
 *     GraphView.css).
 *  2. `.background` + 3 `.orb` (AmbientBackground) — bez priesvitného pozadia
 *     by `backdrop-filter` sidebaru a kariet nebol vidieť (glass recept).
 */
export default function Layout({ children }: LayoutProps) {
  const { user, isAuthenticated } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const { t } = useTranslation();

  // WIKI4AI-82: signal that the app shell has rendered so the global splash
  // hands off to page-level loaders (single visible spinner). useLayoutEffect
  // fires before paint, so the splash fade and the page loader never overlap.
  useLayoutEffect(() => {
    signalContentReady();
  }, []);

  // WIKI4AI-82: expose the sticky top-nav height as --w4a-nav-height for
  // viewport-bounded pages (DocumentEditor, GraphViewPage). WIKI4AI-185: header
  // je stále priamy child <main>; výška sa meria rovnako, len pribudol padding
  // navyše, takže calc(100vh - var(--w4a-nav-height)) stále sedí od vršku
  // viewportu (header začína v y=0, sticky top: 0).
  useLayoutEffect(() => {
    const nav = document.querySelector<HTMLElement>('main > header');
    if (!nav) return;
    const update = () => {
      document.documentElement.style.setProperty('--w4a-nav-height', `${nav.offsetHeight}px`);
    };
    update();
    const observer = new ResizeObserver(update);
    observer.observe(nav);
    window.addEventListener('resize', update);
    return () => {
      observer.disconnect();
      window.removeEventListener('resize', update);
    };
  }, [isAuthenticated]);

  // Mobilný off-canvas sidebar: zavrie sa po akejkoľvek navigácii.
  const [navOpen, setNavOpen] = useState(false);
  useEffect(() => {
    setNavOpen(false);
  }, [location.pathname]);

  /* WIKI4AI-208: tichá zóna. Wiki4AI je primárne ČÍTAČKA dokumentov —
     mlha a orby sú výzdoba pre navigáciu, ale pod dlhým textom, kódom a
     tenkými čiarami diagramu prekážajú. `data-mood` na `<html>` je druhá
     os voľby (nezávisí od `data-theme`) a `tokens.css` na ňu nadväzuje
     blokom `html[data-mood='quiet']`, ktorý stlmí mlhu/orby a zdvihne
     vinetu. Kontrast tým LEN rastie (podklad tmavne), takže rozpočet
     v `contrastBudget.test.ts` ostáva platný — ten meria identitu,
     nie náladu.
     Ticho platí na čítačke, editore a hľadaní. Zoznam projektov,
     dashboard a kalendár tiché NIE SU — tam mlha pomáha orientácii. */
  useEffect(() => {
    const quiet =
      /^\/search$/.test(location.pathname) ||
      /^\/projects\/[^/]+\/documents\/[^/]+(\/(view|edit))?$/.test(location.pathname);
    const root = document.documentElement;
    if (quiet) root.setAttribute('data-mood', 'quiet');
    else root.removeAttribute('data-mood');
    /* Úklid je nutný: Layout sa odmontáva pri odhlásení a atribút by
       zostal na `<html>` aj na prihlasovacej obrazovke. */
    return () => root.removeAttribute('data-mood');
  }, [location.pathname]);

  /* WIKI4AI-195 (DIZ-4 item 8, WCAG 2.4.3 Focus Order + 2.1.2):
   * pod 900 px je sidebar posunutý mimo obrazovku CSS-om, ale jeho
   * odkazy SU v tab ráde — klávesnica odtabuje 20 odkadmi do prázdna
   * pred obsahom. inert z neho urobí nedostupný podstrom; CSS na to
   * selektor nemá, preto to musí ísť z matchMedia. */
  const isNarrow = useMediaQuery('(max-width: 900px)');

  /* WIKI4AI-195 (DIZ-4 item 8): Escape mobilný sidebar nezatvoril vôbec.
   * Poslucháč je naviazaný na navOpen, takže mimo otvoreného stavu
   * nikde visieť nemôže. */
  useEffect(() => {
    if (!navOpen) return;
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setNavOpen(false);
    };
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [navOpen]);

  // Global (cross-project) search — WIKI4AI-61. Submitting navigates to /search?q=...
  const [globalQuery, setGlobalQuery] = useState('');

  const handleGlobalSearchSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const q = globalQuery.trim();
    if (q.length >= 2) {
      navigate(`/search?q=${encodeURIComponent(q)}`);
    }
  };

  return (
    <div className="w4a-shell">
      {/* WIKI4AI-195 (DIZ-4 item 4, WCAG 2.4.1 Bypass Blocks): skip link
          MUSÍ byť prvý child dokumentu, inak sa k nemu klávesnica nedostane
          skôr než k navigácii, ktorú má obísť. Je vizuálne skrytý, kým
          nedostane focus — :focus-visible ho vykreslí. */}
      <a href="#main-content" className="skip-link" data-testid="skip-link">
        {t('layout.skipToContent')}
      </a>

      {/* Ambient Background — .background + 3 orb-y, fixed behind all content.
          Nevyhnutné pre glass efekt (backdrop-filter potrebuje čo najvidieť). */}
      <AmbientBackground />

      {/* Sidebar (glass-admin) — off-canvas pod 900 px, preto backdrop */}
      {isAuthenticated && (
        <SidebarNav
          open={navOpen}
          inert={isNarrow && !navOpen}
          onNavigate={() => setNavOpen(false)}
        />
      )}
      <div
        className={`sidebar-backdrop${navOpen ? ' sidebar-backdrop-show' : ''}`}
        onClick={() => setNavOpen(false)}
        aria-hidden="true"
      />

      {/* Main Content Area */}
      {/* tabIndex=-1 je nevyhnutné: odkaz #main-content presunie focus
       * na element, ale element bez tabindexu focus nedostane, takže by skip
       * link presunul len scroll, nie focus — a to 2.4.1 nerieši. */}
      <main className="main-content" id="main-content" tabIndex={-1}>
        {/* Topbar — glass header; MUSÍ zostať priamym childom <main> */}
        <header className="navbar">
          {/* Top-edge highlight pseudo-element */}
          <div className="navbar-topline" aria-hidden="true" />

          <div className="navbar-left">
            <button
              type="button"
              className="menu-toggle"
              aria-label={t(navOpen ? 'layout.nav.menuClose' : 'layout.nav.menuToggle')}
              aria-expanded={navOpen}
              data-testid="nav-menu-toggle"
              onClick={() => setNavOpen((open) => !open)}
            >
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
                <path d="M3 6h18M3 12h18M3 18h18" />
              </svg>
            </button>
            {/* Bez sidebaru (anonymous) aspoň wordmark, aby shell nebol prázdny */}
            {!isAuthenticated && (
              <Link to="/" className="navbar-brand" data-testid="nav-logo">
                Wiki4AI
              </Link>
            )}
          </div>

          {/* Global (cross-project) search bar — WIKI4AI-61. Login-only endpoint,
              so the bar is only shown for authenticated users. */}
          {isAuthenticated && (
            <form
              className="global-search-bar"
              onSubmit={handleGlobalSearchSubmit}
              role="search"
              data-testid="global-search-form"
            >
              {/* Vyhľadávanie má vlastný aria-label na <input>; ikona je
                 dekorácia a bez aria-hidden by ju čítačka hlásila ako
                 nepomenovaný grafický prvok. */}
              <svg className="global-search-icon" aria-hidden="true" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z" />
              </svg>
              <input
                type="text"
                className="global-search-input"
                placeholder={t('layout.searchPlaceholder')}
                value={globalQuery}
                onChange={(e) => setGlobalQuery(e.target.value)}
                aria-label={t('layout.searchAriaLabel')}
                data-testid="global-search-input"
              />
            </form>
          )}

          {/* Auth section — WIKI4AI-85: single user menu (avatar + chevron)
              for authenticated users; login/register links when anonymous.
              WIKI4AI-185: prepínač tém je aj v sidebari, tu zostáva pre
              anonymous a pre mobil (kde je sidebar skrytý). */}
          <div className="navbar-right" data-testid="auth-section">
            {isAuthenticated && user ? (
              <>
                <span className="navbar-theme-mobile" data-testid="theme-switch-top">
                  <ThemeSwitch testIdPrefix="theme-top" />
                </span>
                <UserMenu />
              </>
            ) : (
              <nav className="nav-anonymous">
                <Link to="/login" className="nav-auth-link nav-auth-login" data-testid="nav-login">
                  {t('layout.login')}
                </Link>
                <Link to="/register" className="nav-auth-link nav-auth-register" data-testid="nav-register">
                  {t('layout.register')}
                </Link>
              </nav>
            )}
          </div>
        </header>

        {/* Page content — flex column so child pages can use flex: 1 to fill height */}
        <div className="page-content">
          {children || <Outlet />}
        </div>
      </main>
    </div>
  );
}
