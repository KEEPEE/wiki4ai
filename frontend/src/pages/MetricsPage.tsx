/**
 * WIKI4AI-164: the metrics page (`/metrics`).
 *
 * Every number on this page is a field of `ActorMetricsResponseDTO` (WIKI4AI-147),
 * which `ActorMetricsService` derives from `work_item_events` alone. The page adds no
 * arithmetic of its own:
 *
 *   `summary.eventCount / createdCount / claimedCount / completedCount / blockedCount
 *    / leaseExpiredCount / leaseExtendedCount / cycleCount / averageCycleSeconds
 *    / totalStatusSeconds / backfilledEventCount / actorCount`
 *     → the summary cards, one card per field, values passed through.
 *   `actors[].statusSeconds` + `actors[].totalStatusSeconds`
 *     → the time-in-state bars. The bar width is `statusSeconds / totalStatusSeconds`
 *       as a percent — the only ratio the story allows, because both operands are the
 *       API's. No chart library: a bar is a div whose width is that percent.
 *   `actors[].averageCycleSeconds` (+ `actors[].cycleCount` next to it, so a mean over
 *     one sample is visibly a mean over one sample)
 *     → the cycle-time column.
 *   `actors[].leaseExpiredCount` → the lease column. The page never counts events.
 *   `from`, `to`, `scope`, `projectId`
 *     → the window header, because a metric without its window is not a metric.
 *
 * Durations are formatted (7500 s → "2h 05m"), which is rendering, not computing.
 * `backfilledEventCount` is shown as its own note: reconstructed history must be
 * discountable, not blended into observed history.
 */
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useActorMetrics } from '../hooks/useMetrics';
import {
  barStyle,
  formatDurationSeconds,
  statusSecondsShares,
} from './coordinationModel';
import './MetricsPage.css';

// ── Inline SVG icons (no emoji — the container has no emoji font) ────────────

function MetricsIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <line x1="4" y1="20" x2="20" y2="20" />
      <rect x="5" y="11" width="3" height="7" rx="1" />
      <rect x="10.5" y="7" width="3" height="11" rx="1" />
      <rect x="16" y="13" width="3" height="5" rx="1" />
    </svg>
  );
}

/**
 * A metrics row is labelled by the actor the API named for it.
 *
 * `actorId === null` is a REAL row: `ActorMetricsService` groups events by
 * `event.getActorId()`, and that column is nullable — a human acting through a legacy
 * JWT, or a reaper-written event, attributes its events to no actor. The label says
 * "no actor", because turning the null into `0` would announce an actor with id 0 that
 * the API never returned (WIKI4AI-164: the UI adds no numbers of its own).
 */
function actorRowKey(actorId: number | null): string {
  return actorId === null ? 'unattributed' : String(actorId);
}

/** One summary card: a label and the API's value for it. */
function SummaryCard({ label, value, testId }: { label: string; value: string; testId: string }) {
  return (
    <div className="metric-card" data-testid={testId}>
      <span className="metric-card-label">{label}</span>
      <span className="metric-card-value">{value}</span>
    </div>
  );
}

export default function MetricsPage() {
  const { t } = useTranslation();
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');

  const { metrics, isLoading, isError } = useActorMetrics({
    from: from || null,
    to: to || null,
  });

  const summary = metrics?.summary ?? null;
  const actors = metrics?.actors ?? [];

  return (
    <div className="metrics-page" data-testid="metrics-page">
      <header className="metrics-header">
        <div>
          <h1 className="metrics-title">
            <span className="metrics-title-icon">
              <MetricsIcon />
            </span>
            {t('metrics.title')}
          </h1>
          <p className="metrics-subtitle">{t('metrics.subtitle')}</p>
        </div>
        <div className="metrics-window-form">
          <label className="metrics-window-field">
            <span>{t('metrics.from')}</span>
            <input
              type="date"
              value={from}
              data-testid="metrics-from-input"
              onChange={(event) => setFrom(event.target.value)}
            />
          </label>
          <label className="metrics-window-field">
            <span>{t('metrics.to')}</span>
            <input
              type="date"
              value={to}
              data-testid="metrics-to-input"
              onChange={(event) => setTo(event.target.value)}
            />
          </label>
        </div>
      </header>

      {/* The window the API echoed back — a number is always shown with it. */}
      {metrics && (
        <p className="metrics-window" data-testid="metrics-window">
          {t('metrics.window', {
            from: metrics.from ?? t('metrics.windowOpen'),
            to: metrics.to ?? t('metrics.windowOpen'),
            scope: metrics.scope ?? '—',
          })}
          {metrics.projectId !== null && ` · ${t('metrics.projectScoped', { id: metrics.projectId })}`}
        </p>
      )}

      {isError && (
        <p className="metrics-error" data-testid="metrics-load-error">
          {t('metrics.loadError')}
        </p>
      )}

      {isLoading && !metrics ? (
        <p className="metrics-loading" data-testid="metrics-loading">
          {t('metrics.loading')}
        </p>
      ) : !metrics ? (
        <p className="metrics-empty" data-testid="metrics-empty">
          {t('metrics.empty')}
        </p>
      ) : (
        <>
          {summary && (
            <section className="metrics-summary" data-testid="metrics-summary">
              <SummaryCard
                label={t('metrics.summaryEvents')}
                value={String(summary.eventCount)}
                testId="metric-events"
              />
              <SummaryCard
                label={t('metrics.summaryCreated')}
                value={String(summary.createdCount)}
                testId="metric-created"
              />
              <SummaryCard
                label={t('metrics.summaryClaimed')}
                value={String(summary.claimedCount)}
                testId="metric-claimed"
              />
              <SummaryCard
                label={t('metrics.summaryCompleted')}
                value={String(summary.completedCount)}
                testId="metric-completed"
              />
              <SummaryCard
                label={t('metrics.summaryBlocked')}
                value={String(summary.blockedCount)}
                testId="metric-blocked"
              />
              <SummaryCard
                label={t('metrics.summaryLeaseExpired')}
                value={String(summary.leaseExpiredCount)}
                testId="metric-lease-expired"
              />
              <SummaryCard
                label={t('metrics.summaryLeaseExtended')}
                value={String(summary.leaseExtendedCount)}
                testId="metric-lease-extended"
              />
              <SummaryCard
                label={t('metrics.summaryCycleCount')}
                value={String(summary.cycleCount)}
                testId="metric-cycle-count"
              />
              <SummaryCard
                label={t('metrics.summaryAverageCycle')}
                value={formatDurationSeconds(summary.averageCycleSeconds, t) ?? '—'}
                testId="metric-average-cycle"
              />
              <SummaryCard
                label={t('metrics.summaryStatusTime')}
                value={formatDurationSeconds(summary.totalStatusSeconds, t) ?? '—'}
                testId="metric-status-time"
              />
              <SummaryCard
                label={t('metrics.summaryActors')}
                value={String(summary.actorCount)}
                testId="metric-actor-count"
              />
              <p className="metrics-backfilled" data-testid="metric-backfilled">
                {t('metrics.backfilled', { count: summary.backfilledEventCount })}
              </p>
            </section>
          )}

          {/* Instance-wide time in state: the summary's own map and its own total. */}
          {summary && Object.keys(summary.statusSeconds).length > 0 && (
            <section className="metrics-status-share" data-testid="metrics-summary-share">
              <h2 className="metrics-section-title">{t('metrics.statusShareTitle')}</h2>
              {statusSecondsShares(summary.statusSeconds, summary.totalStatusSeconds).map((share) => (
                <div
                  key={share.status}
                  className="metrics-share-row"
                  data-testid={`metrics-summary-share-${share.status}`}
                >
                  <span className="metrics-share-label">
                    {t(`board.status.${share.status}`, { defaultValue: share.status })}
                  </span>
                  <div className="metrics-share-bar">
                    <div className="metrics-share-bar-fill" style={barStyle(share.percent)} />
                  </div>
                  <span className="metrics-share-value">
                    {formatDurationSeconds(share.seconds, t) ?? '—'}
                    {share.percent !== null && ` · ${share.percent.toFixed(1)}%`}
                  </span>
                </div>
              ))}
            </section>
          )}

          {actors.length === 0 ? (
            <p className="metrics-empty" data-testid="metrics-no-actors">
              {t('metrics.noActors')}
            </p>
          ) : (
            <ul className="metrics-actors">
              {actors.map((row) => {
                const shares = statusSecondsShares(row.statusSeconds, row.totalStatusSeconds);
                const rowKey = actorRowKey(row.actorId);
                return (
                  <li key={rowKey} className="metrics-actor" data-testid={`metrics-actor-${rowKey}`}>
                    <div className="metrics-actor-head">
                      <span
                        className="metrics-actor-id"
                        data-testid={`metrics-actor-${rowKey}-id`}
                        title={row.actorId === null ? t('metrics.actorUnattributedHint') : undefined}
                      >
                        {row.actorId === null
                          ? t('metrics.actorUnattributed')
                          : t('metrics.actor', { id: row.actorId })}
                      </span>
                      <span
                        className="metrics-actor-lease"
                        data-testid={`metrics-actor-${rowKey}-lease-expired`}
                      >
                        {t('metrics.leaseExpired', { count: row.leaseExpiredCount })}
                      </span>
                      <span
                        className="metrics-actor-cycle"
                        data-testid={`metrics-actor-${rowKey}-cycle`}
                      >
                        {t('metrics.cycle', {
                          average: formatDurationSeconds(row.averageCycleSeconds, t) ?? '—',
                          count: row.cycleCount,
                        })}
                      </span>
                    </div>

                    <div className="metrics-actor-counts">
                      <span data-testid={`metrics-actor-${rowKey}-events`}>
                        {t('metrics.events', { count: row.eventCount })}
                      </span>
                      <span data-testid={`metrics-actor-${rowKey}-created`}>
                        {t('metrics.created', { count: row.createdCount })}
                      </span>
                      <span data-testid={`metrics-actor-${rowKey}-claimed`}>
                        {t('metrics.claimed', { count: row.claimedCount })}
                      </span>
                      <span data-testid={`metrics-actor-${rowKey}-completed`}>
                        {t('metrics.completed', { count: row.completedCount })}
                      </span>
                      <span data-testid={`metrics-actor-${rowKey}-blocked`}>
                        {t('metrics.blocked', { count: row.blockedCount })}
                      </span>
                      <span data-testid={`metrics-actor-${rowKey}-lease-extended`}>
                        {t('metrics.leaseExtended', { count: row.leaseExtendedCount })}
                      </span>
                      <span data-testid={`metrics-actor-${rowKey}-status-time`}>
                        {t('metrics.statusTime', {
                          value: formatDurationSeconds(row.totalStatusSeconds, t) ?? '—',
                        })}
                      </span>
                    </div>

                    {shares.length === 0 ? (
                      <p className="metrics-no-status">{t('metrics.noStatusSeconds')}</p>
                    ) : (
                      <div className="metrics-actor-share" data-testid={`metrics-actor-${rowKey}-share`}>
                        {shares.map((share) => (
                          <div
                            key={share.status}
                            className="metrics-share-row"
                            data-testid={`metrics-actor-${rowKey}-share-${share.status}`}
                          >
                            <span className="metrics-share-label">
                              {t(`board.status.${share.status}`, { defaultValue: share.status })}
                            </span>
                            <div className="metrics-share-bar">
                              <div className="metrics-share-bar-fill" style={barStyle(share.percent)} />
                            </div>
                            <span className="metrics-share-value">
                              {formatDurationSeconds(share.seconds, t) ?? '—'}
                              {share.percent !== null && ` · ${share.percent.toFixed(1)}%`}
                            </span>
                          </div>
                        ))}
                      </div>
                    )}
                  </li>
                );
              })}
            </ul>
          )}

          {/* The API's `actorCount` counts rows, and a row may name no actor. The
              number is left exactly as the API sent it; only its meaning is stated. */}
          {actors.some((row) => row.actorId === null) && (
            <p className="metrics-unattributed-note" data-testid="metrics-unattributed-note">
              {t('metrics.unattributedNote')}
            </p>
          )}

          <p className="metrics-footnote" data-testid="metrics-footnote">
            {t('metrics.footnote')}
          </p>
        </>
      )}
    </div>
  );
}
