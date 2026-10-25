/**
 * WIKI4AI-164: the capacity page (`/resources`).
 *
 * For every resource from `GET /api/v1/resources`:
 *   capacity vs live slots — `activeSlots / capacity`, drawn as a bar whose width IS
 *   that ratio (no chart library, no computed aggregate: `availableSlots` is even sent
 *   by the API, so it is read rather than subtracted),
 *   its ledger from `GET /api/v1/resources/{key}/reservations`, live vs released by
 *   the row's own `releasedAt`,
 *   a TTL countdown per reservation, measured as `expiresAt − fetchedAt` and advanced
 *   by `refetchInterval: 10_000` — no `setInterval`, no component timer (WIKI4AI-175).
 *
 * Manual release is `POST /api/v1/reservations/{id}/release`, which the backend
 * documents as idempotent: a second call is 204 again and decrements `active_slots`
 * exactly once. The page therefore disables the button the moment it has seen that
 * 204 and labels the row "release accepted" until the ledger refetch confirms it with
 * `releasedAt`. A second click is a no-op, never a red error.
 */
import { useTranslation } from 'react-i18next';
import { useCapacityLedger, useReleaseReservationMutation } from '../hooks/useResources';
import {
  barStyle,
  formatApiDateTime,
  formatDurationSeconds,
  isReservationExpired,
  reservationRemainingMs,
  sharePercent,
} from './coordinationModel';
import './ResourcesPage.css';

// ── Inline SVG icons (no emoji — the container has no emoji font) ────────────

function ResourceIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="4" width="18" height="6" rx="1.5" />
      <rect x="3" y="14" width="18" height="6" rx="1.5" />
      <line x1="7" y1="7" x2="7.01" y2="7" />
      <line x1="7" y1="17" x2="7.01" y2="17" />
    </svg>
  );
}

export default function ResourcesPage() {
  const { t, i18n } = useTranslation();
  const { ledger, isLoading, isError } = useCapacityLedger();
  const release = useReleaseReservationMutation();

  const releaseError = release.error;

  return (
    <div className="resources-page" data-testid="resources-page">
      <header className="resources-header">
        <div>
          <h1 className="resources-title">
            <span className="resources-title-icon">
              <ResourceIcon />
            </span>
            {t('resources.title')}
          </h1>
          <p className="resources-subtitle">{t('resources.subtitle')}</p>
        </div>
      </header>

      {isError && (
        <p className="resources-error" data-testid="resources-load-error">
          {t('resources.loadError')}
        </p>
      )}
      {releaseError ? (
        <p className="resources-error" data-testid="resources-release-error">
          {t('resources.releaseError')}
        </p>
      ) : null}

      {isLoading && !ledger ? (
        <p className="resources-loading" data-testid="resources-loading">
          {t('resources.loading')}
        </p>
      ) : !ledger || ledger.resources.length === 0 ? (
        <p className="resources-empty" data-testid="resources-empty">
          {t('resources.empty')}
        </p>
      ) : (
        <ul className="resources-list">
          {ledger.resources.map((resource) => {
            const rows = ledger.reservations[resource.key] ?? [];
            // The bar is the API's own ratio. `capacity` null or 0 → no bar at all.
            const percent = sharePercent(resource.activeSlots, resource.capacity);

            return (
              <li key={resource.key} className="resource-card" data-testid={`resource-card-${resource.key}`}>
                <div className="resource-card-head">
                  <span className="resource-name">{resource.name ?? resource.key}</span>
                  <span className="resource-key">{resource.key}</span>
                  {resource.kind && <span className="resource-kind">{resource.kind}</span>}
                  {resource.enabled === false && (
                    <span className="resource-disabled" data-testid={`resource-disabled-${resource.key}`}>
                      {t('resources.disabled')}
                    </span>
                  )}
                </div>

                <div className="resource-capacity">
                  <span
                    className="resource-capacity-text"
                    data-testid={`resource-slots-${resource.key}`}
                  >
                    {t('resources.slots', {
                      active: resource.activeSlots ?? '—',
                      capacity: resource.capacity ?? '—',
                      available: resource.availableSlots ?? '—',
                    })}
                  </span>
                  <div
                    className="resource-bar"
                    data-testid={`resource-bar-${resource.key}`}
                    data-derivable={percent !== null}
                  >
                    <div className="resource-bar-fill" style={barStyle(percent)} />
                  </div>
                </div>

                {rows.length === 0 ? (
                  <p className="resource-empty-ledger">{t('resources.noReservations')}</p>
                ) : (
                  <table className="resource-table">
                    <thead>
                      <tr>
                        <th>{t('resources.colReservation')}</th>
                        <th>{t('resources.colActor')}</th>
                        <th>{t('resources.colItem')}</th>
                        <th>{t('resources.colSlots')}</th>
                        <th>{t('resources.colTtl')}</th>
                        <th>{t('resources.colAction')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {rows.map((reservation) => {
                        const releasedByApi = reservation.releasedAt !== null && reservation.releasedAt !== undefined;
                        // A 204 this browser already received. Not a guess about state:
                        // the ledger refetch is what turns it into `releasedAt`.
                        const accepted = release.releasedIds.has(reservation.id);
                        const remaining = releasedByApi
                          ? null
                          : reservationRemainingMs(reservation.expiresAt, ledger.fetchedAt);
                        const ttl = formatDurationSeconds(
                          remaining === null ? null : remaining / 1000,
                          t,
                        );

                        return (
                          <tr
                            key={reservation.id}
                            className={`reservation-row${releasedByApi ? ' reservation-released' : ''}`}
                            data-testid={`reservation-row-${reservation.id}`}
                            data-released={releasedByApi}
                          >
                            <td>#{reservation.id}</td>
                            <td>{reservation.actorId === null ? '—' : `#${reservation.actorId}`}</td>
                            <td>
                              {reservation.workItemId === null
                                ? '—'
                                : t('board.workItemRef', { id: reservation.workItemId })}
                            </td>
                            <td>{reservation.slots ?? '—'}</td>
                            <td data-testid={`reservation-ttl-${reservation.id}`}>
                              {releasedByApi
                                ? t('resources.released')
                                : remaining === null
                                  ? t('resources.noDeadline')
                                  : isReservationExpired(remaining)
                                    ? t('resources.ttlExpired')
                                    : ttl}
                            </td>
                            <td>
                              {releasedByApi ? (
                                <span
                                  className="reservation-released-label"
                                  data-testid={`reservation-released-${reservation.id}`}
                                >
                                  {t('resources.released')}
                                </span>
                              ) : (
                                <button
                                  type="button"
                                  className="btn btn-small"
                                  data-testid={`reservation-release-${reservation.id}`}
                                  disabled={accepted || release.isPending}
                                  onClick={() => void release.release(reservation.id).catch(() => undefined)}
                                >
                                  {accepted ? t('resources.releaseAccepted') : t('resources.release')}
                                </button>
                              )}
                            </td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                )}

                <p className="resource-created">
                  {formatApiDateTime(resource.createdAt, i18n.language) ?? ''}
                </p>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
