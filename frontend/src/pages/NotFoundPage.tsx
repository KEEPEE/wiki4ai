/**
 * WIKI4AI-108: the page a user gets when the router matched nothing.
 *
 * Before this page existed, an unmatched URL (`/admin`, a typo, a stale bookmark)
 * rendered literally nothing: React Router logged "No routes matched location" and
 * React committed an empty tree. No exception was thrown, so the ErrorBoundary
 * (WIKI4AI-27/38) deliberately never ran — there was no error to catch. The user
 * saw a black screen with no explanation and no way out.
 *
 * What this page is allowed to say, and why:
 *  - the requested path — that is `useLocation()`, the address the browser was
 *    actually given. It is observed, not inferred.
 *  - "no page matched" — that is a fact about the route table, which is what just
 *    ran.
 * What it must NOT say: anything about content it did not load. It fetches nothing,
 * so it claims nothing: no document title, no project name, no "last visited", no
 * status code the server never sent. An unmatched SPA route is served by
 * `index.html` with HTTP 200 — the app has no authority to print a status code it
 * did not receive, so none is printed here.
 *
 * It is a standalone route (no `Layout`, no `ProtectedRoute`), placed exactly like
 * `/login`, `/register` and `/setup`: an anonymous visitor who mistyped an address
 * gets an explanation and a way home, not a silent bounce to the login form.
 */
import { useLayoutEffect } from 'react';
import { Link, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import AmbientBackground from '../components/AmbientBackground';
import { signalContentReady } from '../utils/appReady';
import './NotFound.css';

export default function NotFoundPage() {
  const { t } = useTranslation();
  // The address the browser asked for. Read from the router, never reconstructed.
  const { pathname } = useLocation();

  // WIKI4AI-82: standalone route (no Layout) — hand the global splash over to this
  // page's own shell so two spinners are never visible at once.
  useLayoutEffect(() => {
    signalContentReady();
  }, []);

  return (
    <>
      <AmbientBackground />

      <div className="not-found-page">
        <div className="not-found-card" data-testid="not-found-card">
          <h1 className="not-found-title" data-testid="not-found-title">
            {t('notFound.title')}
          </h1>

          <p className="not-found-body" data-testid="not-found-body">
            {t('notFound.body')}
          </p>

          {/* Only the address the router was asked for — nothing else is claimed. */}
          <p className="not-found-path" data-testid="not-found-path">
            {t('notFound.requestedPathLabel')}{' '}
            <code data-testid="not-found-path-value">{pathname}</code>
          </p>

          <div className="not-found-actions">
            <Link to="/" className="not-found-home" data-testid="not-found-home-link">
              {t('notFound.backHome')}
            </Link>
          </div>
        </div>
      </div>
    </>
  );
}
