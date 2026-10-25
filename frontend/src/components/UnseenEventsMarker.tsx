/**
 * WIKI4AI-165: the "what arrived" marker in the navigation.
 *
 * The badge is rendered on the user-menu trigger, so it is visible without opening
 * anything, and the menu carries the matching action. It disappears when the content
 * behind it has been looked at:
 *
 *   actor session   (`whoami.actorId !== null`)
 *     badge = `UnseenEventsDTO.count` from `GET /api/v1/actors/{id}/inbox/unseen` —
 *     the server's own COUNT. Opening the marker POSTs `mark-seen` with no body and
 *     the re-read answers 0.
 *
 *   legacy JWT session (`whoami.actorId === null`, the normal human session on dev)
 *     there is no inbox to ask, so the badge is the derivation documented in
 *     `src/utils/unseenEvents.ts`: the `WorkItemEventDTO.id`s this browser was actually
 *     sent that are above the maximum it has already been shown. Opening the marker
 *     records that maximum, the derivation yields nothing and the badge disappears.
 *     It measures "new events you actually looked at", NOT "all new events" — see the
 *     LIMITATION block in that file. It is a comparison of server-assigned event ids,
 *     not a UI-invented number and not a timestamp comparison.
 *
 * When neither read produced an answer the marker renders nothing at all: an unknown
 * count is never displayed as 0.
 *
 * Why this component owns its `QueryClient`: `UserMenu` is rendered by `Layout`, and
 * `Layout` is unit-tested without a provider (see Layout.test.tsx). A marker that
 * required an ambient provider would break that suite. A nested provider is legal in
 * react-query and shadows the app-wide one, so the marker works in both places.
 */
import { createContext, useContext, useRef, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useUnseenEvents, type UnseenEventsState } from '../hooks/useUnseenEvents';
import { MenuItem } from './Menu';
import './UnseenEventsMarker.css';

const UnseenEventsContext = createContext<UnseenEventsState | null>(null);

export function useUnseenEventsState(): UnseenEventsState {
  const ctx = useContext(UnseenEventsContext);
  if (!ctx) throw new Error('useUnseenEventsState must be used inside <UnseenEventsProvider>');
  return ctx;
}

export function UnseenEventsProvider({ children }: { children: ReactNode }) {
  return (
    <UnseenEventsClientProvider>
      <UnseenEventsStateProvider>{children}</UnseenEventsStateProvider>
    </UnseenEventsClientProvider>
  );
}

function UnseenEventsClientProvider({ children }: { children: ReactNode }) {
  const clientRef = useRef<QueryClient | null>(null);
  if (clientRef.current === null) {
    clientRef.current = new QueryClient({
      defaultOptions: {
        queries: {
          // A nav badge must not spend retries on a 403 inbox or a 404 project.
          retry: 0,
          gcTime: 0,
        },
      },
    });
  }
  return <QueryClientProvider client={clientRef.current}>{children}</QueryClientProvider>;
}

function UnseenEventsStateProvider({ children }: { children: ReactNode }) {
  const state = useUnseenEvents();
  return <UnseenEventsContext.Provider value={state}>{children}</UnseenEventsContext.Provider>;
}

/** True when the marker has an answer worth showing. */
export function isMarkerVisible(state: UnseenEventsState): boolean {
  return state.known && state.count > 0;
}

// ── Badge on the trigger ─────────────────────────────────────────────────────

export function UnseenEventsBadge() {
  const { t } = useTranslation();
  const state = useUnseenEventsState();
  if (!isMarkerVisible(state)) return null;

  return (
    <span
      className="unseen-events-badge"
      data-testid="unseen-events-badge"
      data-source={state.source}
      title={t('layout.newEvents', { count: state.count })}
    >
      {state.count}
    </span>
  );
}

// ── Action inside the menu ───────────────────────────────────────────────────

export function UnseenEventsMenuItem() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const state = useUnseenEventsState();
  if (!isMarkerVisible(state)) return null;

  const open = () => {
    // "Opened" is what makes the count drop: for an actor the server is told, for a
    // browser session the high-water mark moves to the newest id it was shown.
    void state.markSeen().catch(() => {
      // A failed mark-seen leaves the badge as it was; the marker never lies.
    });
    navigate('/board');
  };

  return (
    <MenuItem
      icon={<SparkIcon />}
      onClick={open}
      testId="user-menu-new-events"
      title={t('layout.newEventsHint')}
    >
      {t('layout.newEvents', { count: state.count })}
    </MenuItem>
  );
}

function SparkIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12 2v4M12 18v4M4.9 4.9l2.8 2.8M16.3 16.3l2.8 2.8M2 12h4M18 12h4M4.9 19.1l2.8-2.8M16.3 7.7l2.8-2.8" />
    </svg>
  );
}
