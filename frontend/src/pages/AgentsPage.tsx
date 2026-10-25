/**
 * WIKI4AI-163: the agents page (`/agents`).
 *
 * One card per actor from `GET /api/v1/actors`: `handle`, the system label (`kind`:
 * agent / human / service), the presence status, `lastSeenAt`, the role the actor has
 * in the selected project (`project_actors.role`, from
 * `GET /api/v1/projects/{projectId}/actors`) and the number of leases the reaper took
 * back from it.
 *
 * That last number is `ActorMetricsDTO.leaseExpiredCount` from
 * `GET /api/v1/metrics/actors`. The page does not count `lease_expired` events, does
 * not scan timelines and does not infer it from a missing heartbeat: it renders the
 * field the metrics endpoint computed, and renders nothing when that actor has no row
 * in the window (an actor with no events has no metrics row at all — the backend says
 * so explicitly).
 *
 * Tokens of an agent are `GET /api/v1/tokens` rows whose `actorId` is this actor, and
 * they are displayed as their display prefix (`w4a_k7x2…`) and nothing more:
 * `agentsApi.toTokenInfo()` copies the allow-list, so a listing can never carry a
 * secret into this component. Rotate and revoke are the two actions the story asks
 * for, plus the kill switch `DELETE /api/v1/actors/{id}/tokens`.
 *
 * SECURITY (WIKI4AI-168 / WIKI4AI-177): the plaintext of a rotated token is shown
 * exactly once, in the one-time panel below the list, with a copy button. It is held
 * in component state only — never in localStorage, never in the query cache — and the
 * unmount cleanup drops it. A reload cannot show it again, because the backend does
 * not have it any more.
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useActors } from '../hooks/useBoard';
import {
  useActorTokens,
  useProjectActors,
  useRevokeAllActorTokensMutation,
  useRevokeTokenMutation,
  useRotateTokenMutation,
} from '../hooks/useAgents';
import { useActorMetrics } from '../hooks/useMetrics';
import { roleOfActor, tokensOfActor, type TokenInfo } from '../services/agentsApi';
import { formatApiDateTime } from './coordinationModel';
import './AgentsPage.css';

// ── Inline SVG icons (no emoji — the container has no emoji font) ────────────

function AgentsIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="4" y="7" width="16" height="12" rx="2" />
      <path d="M12 3v4" />
      <circle cx="12" cy="2" r="1" />
      <circle cx="9" cy="13" r="1" />
      <circle cx="15" cy="13" r="1" />
      <path d="M9 17h6" />
    </svg>
  );
}

function KeyIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.778 7.778 5.5 5.5 0 0 1 7.777-7.777zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4" />
    </svg>
  );
}

/**
 * Display handle of a token: the `w4a_` scheme plus the 8-character prefix the API
 * sends, with an ellipsis that says out loud that the rest is not shown.
 * A token with no prefix (a legacy row) is labelled as such — never as an empty token.
 */
function tokenPrefix(token: TokenInfo): string {
  return token.prefix ? `w4a_${token.prefix}…` : 'legacy';
}

export default function AgentsPage() {
  const { t, i18n } = useTranslation();

  const [projectId, setProjectId] = useState<number | null>(null);

  const { actors, isLoading: actorsLoading, isError: actorsError } = useActors(null);
  const { tokens, isError: tokensError } = useActorTokens();
  const { projectActors, isError: rolesError } = useProjectActors(projectId);
  // leaseExpiredCount is a metrics field, not a board field.
  const { metrics, isError: metricsError } = useActorMetrics();

  const rotateToken = useRotateTokenMutation();
  const revokeToken = useRevokeTokenMutation();
  const revokeAll = useRevokeAllActorTokensMutation();

  // ── One-time plaintext (WIKI4AI-168) ───────────────────────────────────────
  const [oneTime, setOneTime] = useState<{ tokenId: number; name: string; token: string } | null>(
    null,
  );
  const [copied, setCopied] = useState(false);

  // WIKI4AI-175: the only timer this page owns is tracked and cleared on unmount.
  const timersRef = useRef<number[]>([]);
  const scheduleTimer = useCallback((fn: () => void, ms: number) => {
    const id = window.setTimeout(() => {
      timersRef.current = timersRef.current.filter((timerId) => timerId !== id);
      fn();
    }, ms);
    timersRef.current.push(id);
  }, []);

  // WIKI4AI-168: the plaintext leaves the page with the page.
  useEffect(() => {
    return () => {
      timersRef.current.forEach((timerId) => window.clearTimeout(timerId));
      timersRef.current = [];
      setOneTime(null);
    };
  }, []);

  const metricsByActor = useMemo(() => {
    const map = new Map<number, number>();
    for (const row of metrics?.actors ?? []) {
      // A metrics row whose `actorId` is null is "events with no attributed actor".
      // Its numbers belong to no agent, so they are attached to no agent card — and
      // the null is never turned into the id 0 (WIKI4AI-164).
      if (row.actorId === null) continue;
      map.set(row.actorId, row.leaseExpiredCount);
    }
    return map;
  }, [metrics]);

  const tokensByActor = useMemo(() => {
    const map = new Map<number, TokenInfo[]>();
    for (const actor of actors) map.set(actor.id, tokensOfActor(tokens, actor.id));
    return map;
  }, [actors, tokens]);

  const handleRotate = useCallback(
    async (token: TokenInfo) => {
      try {
        const created = await rotateToken.rotate(token.id);
        // The plaintext exists in this response only. State, not storage, not cache.
        setOneTime({
          tokenId: created.metadata.id,
          name: created.metadata.name || token.name,
          token: created.token,
        });
        setCopied(false);
      } catch {
        // The mutation's error is rendered below; a failed rotation shows no panel.
      }
    },
    [rotateToken],
  );

  const handleRevoke = useCallback(
    async (token: TokenInfo) => {
      try {
        await revokeToken.revoke(token.id);
      } catch {
        // Rendered as `agents-revoke-error`.
      }
    },
    [revokeToken],
  );

  const handleRevokeAll = useCallback(
    async (actorId: number) => {
      try {
        await revokeAll.revokeAll(actorId);
      } catch {
        // Rendered as `agents-revoke-all-error`.
      }
    },
    [revokeAll],
  );

  const copyOneTime = useCallback(() => {
    if (!oneTime) return;
    const done = () => {
      setCopied(true);
      scheduleTimer(() => setCopied(false), 2000);
    };
    if (navigator.clipboard?.writeText) {
      void navigator.clipboard.writeText(oneTime.token).then(done).catch(() => {
        // Clipboard denied — the value is still on screen to copy by hand.
      });
      return;
    }
    done();
  }, [oneTime, scheduleTimer]);

  const mutationError = rotateToken.error ?? revokeToken.error ?? revokeAll.error;
  const loadError = actorsError || tokensError || rolesError || metricsError;

  return (
    <div className="agents-page" data-testid="agents-page">
      <header className="agents-header">
        <div>
          <h1 className="agents-title">
            <span className="agents-title-icon">
              <AgentsIcon />
            </span>
            {t('agents.title')}
          </h1>
          <p className="agents-subtitle">{t('agents.subtitle')}</p>
        </div>
        <label className="agents-project">
          <span>{t('agents.project')}</span>
          <input
            type="number"
            min={1}
            value={projectId ?? ''}
            placeholder={t('agents.projectPlaceholder')}
            data-testid="agents-project-input"
            onChange={(event) => {
              const raw = event.target.value;
              const parsed = Number(raw);
              setProjectId(raw !== '' && Number.isFinite(parsed) ? parsed : null);
            }}
          />
        </label>
      </header>

      {loadError && (
        <p className="agents-error" data-testid="agents-load-error">
          {t('agents.loadError')}
        </p>
      )}
      {mutationError ? (
        <p className="agents-error" data-testid="agents-mutation-error">
          {t('agents.actionError')}
        </p>
      ) : null}

      {oneTime && (
        <section className="one-time-token" data-testid="rotated-token-display">
          <p className="one-time-token-warning" data-testid="rotated-token-warning">
            {t('agents.rotatedWarning', { name: oneTime.name })}
          </p>
          <code data-testid="rotated-token-value">{oneTime.token}</code>
          <button
            type="button"
            className="btn btn-small"
            data-testid="copy-rotated-token-btn"
            onClick={copyOneTime}
          >
            {copied ? t('agents.copied') : t('agents.copy')}
          </button>
        </section>
      )}

      {actorsLoading && actors.length === 0 ? (
        <p className="agents-loading" data-testid="agents-loading">
          {t('agents.loading')}
        </p>
      ) : actors.length === 0 ? (
        <p className="agents-empty" data-testid="agents-empty">
          {t('agents.empty')}
        </p>
      ) : (
        <ul className="agents-list" data-testid="agents-list">
          {actors.map((actor) => {
            const actorTokens = tokensByActor.get(actor.id) ?? [];
            const role = projectId === null ? null : roleOfActor(projectActors, actor.id);
            // `null` here means "the metrics window had no row for this actor", which
            // is not 0 and is not rendered as 0.
            const leaseExpired = metricsByActor.has(actor.id) ? metricsByActor.get(actor.id)! : null;
            const lastSeen = formatApiDateTime(actor.lastSeenAt, i18n.language);

            return (
              <li key={actor.id} className="agent-card" data-testid={`agent-card-${actor.id}`}>
                <div className="agent-card-head">
                  <span className="agent-handle" data-testid={`agent-handle-${actor.id}`}>
                    {actor.handle}
                  </span>
                  <span className="agent-kind" data-testid={`agent-kind-${actor.id}`}>
                    {actor.kind ?? t('agents.kindUnknown')}
                  </span>
                  <span className="agent-status" data-presence={actor.status} data-testid={`agent-status-${actor.id}`}>
                    {t(`board.presence.${actor.status}`, { defaultValue: actor.status })}
                  </span>
                  {actor.displayName && <span className="agent-display">{actor.displayName}</span>}
                </div>

                <dl className="agent-facts">
                  <div>
                    <dt>{t('agents.role')}</dt>
                    <dd data-testid={`agent-role-${actor.id}`}>
                      {role ?? t('agents.roleUnknown')}
                    </dd>
                  </div>
                  <div>
                    <dt>{t('agents.lastSeen')}</dt>
                    <dd data-testid={`agent-lastseen-${actor.id}`}>
                      {lastSeen ?? t('agents.neverSeen')}
                    </dd>
                  </div>
                  <div>
                    <dt>{t('agents.leaseExpired')}</dt>
                    <dd data-testid={`agent-leaseexpired-${actor.id}`}>
                      {leaseExpired === null ? t('agents.metricsMissing') : leaseExpired}
                    </dd>
                  </div>
                </dl>

                <div className="agent-tokens">
                  <h2 className="agent-tokens-title">
                    <span className="agent-tokens-icon">
                      <KeyIcon />
                    </span>
                    {t('agents.tokensTitle')}
                  </h2>
                  {actorTokens.length === 0 ? (
                    <p className="agent-tokens-empty" data-testid={`agent-tokens-empty-${actor.id}`}>
                      {t('agents.tokensEmpty')}
                    </p>
                  ) : (
                    <ul className="agent-token-list" data-testid={`agent-tokens-${actor.id}`}>
                      {actorTokens.map((token) => (
                        <li key={token.id} className="agent-token" data-testid={`agent-token-${token.id}`}>
                          <span className="agent-token-name">{token.name}</span>
                          <code
                            className="agent-token-prefix"
                            data-testid={`agent-token-${token.id}-prefix`}
                          >
                            {tokenPrefix(token)}
                          </code>
                          {token.revokedAt && (
                            <span className="agent-token-revoked" data-testid={`agent-token-${token.id}-revoked`}>
                              {t('agents.tokenRevoked')}
                            </span>
                          )}
                          <span className="agent-token-actions">
                            <button
                              type="button"
                              className="btn btn-small"
                              data-testid={`agent-token-${token.id}-rotate`}
                              disabled={token.revokedAt !== null}
                              onClick={() => void handleRotate(token)}
                            >
                              {t('agents.rotate')}
                            </button>
                            <button
                              type="button"
                              className="btn btn-small btn-danger"
                              data-testid={`agent-token-${token.id}-revoke`}
                              disabled={token.revokedAt !== null}
                              onClick={() => void handleRevoke(token)}
                            >
                              {t('agents.revoke')}
                            </button>
                          </span>
                        </li>
                      ))}
                    </ul>
                  )}

                  <button
                    type="button"
                    className="btn btn-small btn-danger agent-revoke-all"
                    data-testid={`agent-revoke-all-${actor.id}`}
                    onClick={() => void handleRevokeAll(actor.id)}
                  >
                    {t('agents.revokeAll')}
                  </button>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
