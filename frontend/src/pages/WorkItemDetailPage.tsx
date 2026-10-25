/**
 * WIKI4AI-162: work item detail (`/board/:id`).
 *
 * Four blocks, all derived from the API:
 *
 *   timeline      GET /work-items/{id}/events, chronological. `lease_expired` is a
 *                 first-class entry with its own badge and colour — the event that
 *                 says "the agent stopped paying for this lease" is the one a human
 *                 has to see, so it is not styled like a status change.
 *   comments      grouped by `kind` with a badge per kind
 *                 (status / blocker / result / handoff / note / decision).
 *   handoff card  from the payload of a handoff comment: `to_handle` (who) and
 *                 `context` (what/why). A key the comment does not carry is shown as
 *                 missing, never as an empty value.
 *   reservations  GET /resources/{key}/reservations filtered on the row's own
 *                 `workItemId` — live vs released comes from `releasedAt`.
 *
 * "Waiting in state X for N min" is measured between the last `status_changed` event
 * and the newest timestamp the API reported — not against the wall clock, and not
 * against `updatedAt` alone. No status change in the timeline means no waiting card.
 *
 * SECURITY (WIKI4AI-177): the lease is rendered as `leaseExpiresAt` only. The claim
 * secret is dropped in boardApi and is not a field of the DTO this page receives.
 */
import { useMemo } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  COMMENT_KINDS,
  type ReservationDTO,
  type WorkItemCommentDTO,
  type WorkItemEventDTO,
} from '../services/boardApi';
import {
  useActors,
  useItemReservations,
  useResources,
  useWorkItem,
  useWorkItemComments,
  useWorkItemEvents,
} from '../hooks/useBoard';
import {
  durationParts,
  formatPayloadValue,
  groupCommentsByKind,
  handoffFields,
  isLeaseExpired,
  leaseRemainingMs,
  parseApiTime,
  waitingInState,
} from './boardModel';
import './WorkItemDetailPage.css';

// ── Formatting helpers (data formatting, not translation) ────────────────────

function formatDateTime(iso: string | null, lang: string): string {
  const ms = parseApiTime(iso);
  if (ms === null) return '';
  return new Intl.DateTimeFormat(lang === 'sk' ? 'sk' : 'en', {
    day: 'numeric',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).format(new Date(ms));
}

function leaseText(
  remainingMs: number,
  t: (key: string, options?: Record<string, unknown>) => string,
): string {
  const { hours, minutes, seconds } = durationParts(remainingMs);
  if (hours > 0) return t('workItem.leaseRemainingHours', { hours, minutes });
  if (minutes > 0) return t('workItem.leaseRemainingMinutes', { minutes, seconds });
  return t('workItem.leaseRemainingSeconds', { seconds });
}

/**
 * An event payload is rendered only if it does not name a lease secret. The backend
 * states the timeline payload never carries one; this is the belt to that braces:
 * a payload that mentions `claimToken` is hidden instead of printed.
 */
function renderablePayload(payload: string | null): string | null {
  if (!payload) return null;
  if (/claim[_-]?token/i.test(payload)) return null;
  return payload;
}

// ── Timeline ─────────────────────────────────────────────────────────────────

function TimelineEntry({ event, lang }: { event: WorkItemEventDTO; lang: string }) {
  const { t } = useTranslation();
  const payload = renderablePayload(event.payload);
  const expired = event.event === 'lease_expired';

  return (
    <li
      className={`wi-event${expired ? ' wi-event-lease-expired' : ''}`}
      data-testid={`wi-event-${event.id}`}
      data-event={event.event}
    >
      <span className="wi-event-time">{formatDateTime(event.createdAt, lang)}</span>
      <span className={`wi-event-badge wi-event-${event.event}`}>
        {t(`workItem.event.${event.event}`, { defaultValue: event.event })}
      </span>
      {event.fromStatus && event.toStatus && (
        <span className="wi-event-status" data-testid={`wi-event-status-${event.id}`}>
          {t(`workItem.status.${event.fromStatus}`, { defaultValue: event.fromStatus })}
          <span className="wi-event-arrow" aria-hidden="true">→</span>
          {t(`workItem.status.${event.toStatus}`, { defaultValue: event.toStatus })}
        </span>
      )}
      {expired && (
        <span className="wi-event-alert" data-testid={`wi-event-alert-${event.id}`}>
          {t('workItem.leaseExpiredNote')}
        </span>
      )}
      {payload && (
        <details className="wi-event-payload">
          <summary>{t('workItem.eventPayload')}</summary>
          <code>{payload}</code>
        </details>
      )}
    </li>
  );
}

// ── Comments ─────────────────────────────────────────────────────────────────

function CommentItem({ comment, lang }: { comment: WorkItemCommentDTO; lang: string }) {
  const { t } = useTranslation();
  const payload = comment.payload ?? null;
  const entries = payload ? Object.entries(payload) : [];

  return (
    <li className="wi-comment" data-testid={`wi-comment-${comment.id}`} data-kind={comment.kind}>
      <div className="wi-comment-head">
        <span className={`board-kind-badge board-kind-${comment.kind}`}>
          {t(`workItem.kind.${comment.kind}`, { defaultValue: comment.kind })}
        </span>
        <span className="wi-comment-time">{formatDateTime(comment.createdAt, lang)}</span>
      </div>
      <p className="wi-comment-body">{comment.body}</p>
      {entries.length > 0 && (
        <ul className="wi-comment-payload">
          {entries.map(([key, value]) => (
            <li key={key} className="wi-comment-payload-row">
              <span className="wi-comment-payload-key">{key}</span>
              <span className="wi-comment-payload-value">{formatPayloadValue(value)}</span>
            </li>
          ))}
        </ul>
      )}
    </li>
  );
}

/**
 * The handoff card. `to_handle` and `context` are the two fields the comment
 * contract guarantees (WIKI4AI-149); when one is absent the card says so instead of
 * rendering a blank, because a handoff with no target is precisely the failure this
 * page exists to make visible.
 */
function HandoffCard({ comment, lang }: { comment: WorkItemCommentDTO; lang: string }) {
  const { t } = useTranslation();
  const fields = handoffFields(comment);

  return (
    <article className="wi-handoff" data-testid={`wi-handoff-${comment.id}`}>
      <header className="wi-handoff-head">
        <span className="board-kind-badge board-kind-handoff">{t('workItem.kind.handoff')}</span>
        <span className="wi-handoff-time">{formatDateTime(comment.createdAt, lang)}</span>
      </header>

      <dl className="wi-handoff-grid">
        <div className="wi-handoff-row" data-testid={`wi-handoff-to-${comment.id}`}>
          <dt>{t('workItem.handoffTo')}</dt>
          <dd className={fields.toHandle ? '' : 'wi-handoff-missing'}>
            {fields.toHandle ?? t('workItem.handoffMissingTo')}
          </dd>
        </div>
        <div className="wi-handoff-row" data-testid={`wi-handoff-context-${comment.id}`}>
          <dt>{t('workItem.handoffContext')}</dt>
          <dd className={fields.context ? '' : 'wi-handoff-missing'}>
            {fields.context ?? t('workItem.handoffMissingContext')}
          </dd>
        </div>
        {fields.extra.map(([key, value]) => (
          <div key={key} className="wi-handoff-row">
            <dt>{key}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>

      {fields.missing.length > 0 && (
        <p className="wi-handoff-warning" data-testid={`wi-handoff-warning-${comment.id}`}>
          {t('workItem.handoffIncomplete', { fields: fields.missing.join(', ') })}
        </p>
      )}
    </article>
  );
}

// ── Reservations ─────────────────────────────────────────────────────────────

function ReservationRow({ reservation, lang }: { reservation: ReservationDTO; lang: string }) {
  const { t } = useTranslation();
  const live = reservation.releasedAt === null || reservation.releasedAt === undefined;

  return (
    <li className="wi-reservation" data-testid={`wi-reservation-${reservation.id}`}>
      <span className="wi-reservation-key">{reservation.resourceKey}</span>
      <span className="wi-reservation-slots">
        {t('workItem.reservationSlots', { slots: reservation.slots ?? 0 })}
      </span>
      <span className={`wi-reservation-state ${live ? 'wi-reservation-live' : 'wi-reservation-released'}`}>
        {live
          ? t('workItem.reservationLive', {
              expiresAt: formatDateTime(reservation.expiresAt, lang),
            })
          : t('workItem.reservationReleased', {
              releasedAt: formatDateTime(reservation.releasedAt ?? null, lang),
            })}
      </span>
      {reservation.reason && <span className="wi-reservation-reason">{reservation.reason}</span>}
    </li>
  );
}

// ── Page ─────────────────────────────────────────────────────────────────────

export default function WorkItemDetailPage() {
  const { t, i18n } = useTranslation();
  const params = useParams();
  const workItemId = Number(params.id);
  const validId = Number.isFinite(workItemId) && workItemId > 0;

  const { item, fetchedAt, isLoading, isError, error } = useWorkItem(workItemId, {
    enabled: validId,
  });
  const { events } = useWorkItemEvents(workItemId, { enabled: validId });
  const { comments } = useWorkItemComments(workItemId, { enabled: validId });
  const { resources } = useResources();
  const { reservations } = useItemReservations(workItemId, resources, { enabled: validId });
  const { actors } = useActors();

  const actorIndex = useMemo(() => new Map(actors.map((a) => [a.id, a])), [actors]);
  const commentsByKind = useMemo(
    () => groupCommentsByKind(comments, COMMENT_KINDS),
    [comments],
  );
  const handoffs = useMemo(
    () => comments.filter((c) => c.kind === 'handoff'),
    [comments],
  );
  const waiting = useMemo(
    () => (item ? waitingInState(item, events) : null),
    [item, events],
  );

  if (!validId) {
    return (
      <div className="wi-detail-page">
        <p className="wi-not-found" data-testid="wi-not-found">
          {t('workItem.invalidId')}
        </p>
        <Link to="/board" className="wi-back" data-testid="wi-back">
          {t('workItem.back')}
        </Link>
      </div>
    );
  }

  if (isLoading && !item) {
    return (
      <div className="wi-detail-page">
        <p className="wi-loading" data-testid="wi-loading">
          {t('workItem.loading')}
        </p>
      </div>
    );
  }

  if (!item) {
    return (
      <div className="wi-detail-page">
        <p className="wi-not-found" data-testid="wi-not-found">
          {isError
            ? t('workItem.loadError', { message: error instanceof Error ? error.message : '' })
            : t('workItem.notFound')}
        </p>
        <Link to="/board" className="wi-back" data-testid="wi-back">
          {t('workItem.back')}
        </Link>
      </div>
    );
  }

  const remaining = fetchedAt === null ? null : leaseRemainingMs(item.leaseExpiresAt, fetchedAt);
  const expired = isLeaseExpired(remaining);
  const assignee = item.assigneeActorId === null ? null : actorIndex.get(item.assigneeActorId) ?? null;

  return (
    <div className="wi-detail-page">
      <header className="wi-header">
        <Link to="/board" className="wi-back" data-testid="wi-back">
          {t('workItem.back')}
        </Link>
        <h1 className="wi-title" data-testid="wi-title">
          {item.title}
        </h1>
        <div className="wi-meta">
          <span className={`wi-status wi-status-${item.status}`} data-testid="wi-status">
            {t(`workItem.status.${item.status}`, { defaultValue: item.status })}
          </span>
          {item.priority && (
            <span className="wi-priority" data-testid="wi-priority">
              {t('workItem.priority', { priority: item.priority })}
            </span>
          )}
          {item.externalRef && (
            <span className="wi-external-ref" data-testid="wi-external-ref">
              {item.externalRef}
            </span>
          )}
          {item.labels.length > 0 && (
            <ul className="wi-labels" data-testid="wi-labels">
              {item.labels.map((label) => (
                <li key={label} className="board-label-chip">
                  {label}
                </li>
              ))}
            </ul>
          )}
        </div>
      </header>

      {item.body && <p className="wi-body">{item.body}</p>}

      <section className="wi-facts" data-testid="wi-facts">
        <div className="wi-fact" data-testid="wi-assignee">
          <span className="wi-fact-label">{t('workItem.assigneeLabel')}</span>
          {assignee ? (
            <span className="wi-fact-value" data-presence={assignee.status}>
              <span className={`presence-dot presence-${assignee.status}`} aria-hidden="true" />
              {assignee.handle}
            </span>
          ) : (
            <span className="wi-fact-value wi-fact-muted">
              {item.assigneeActorId === null
                ? t('workItem.unassigned')
                : t('workItem.unknownActor', { actorId: item.assigneeActorId })}
            </span>
          )}
        </div>

        <div className="wi-fact" data-testid="wi-lease">
          <span className="wi-fact-label">{t('workItem.leaseLabel')}</span>
          {remaining === null ? (
            <span className="wi-fact-value wi-fact-muted">{t('workItem.leaseNone')}</span>
          ) : (
            <span className={`wi-fact-value${expired ? ' wi-lease-expired' : ''}`}>
              {expired ? t('workItem.leaseExpired') : leaseText(remaining, t)}
              <span className="wi-lease-until">
                {t('workItem.leaseUntil', { until: formatDateTime(item.leaseExpiresAt, i18n.language) })}
              </span>
            </span>
          )}
        </div>

        {waiting && (
          <div className="wi-fact" data-testid="wi-waiting">
            <span className="wi-fact-label">{t('workItem.waitingLabel')}</span>
            <span className="wi-fact-value">
              {t('workItem.waitingInState', { status: waiting.status, minutes: waiting.minutes })}
              <span className="wi-waiting-since">
                {t('workItem.waitingSince', { since: formatDateTime(waiting.sinceAt, i18n.language) })}
              </span>
            </span>
          </div>
        )}
      </section>

      <section className="wi-section" data-testid="wi-timeline">
        <h2 className="wi-section-title">{t('workItem.timelineTitle')}</h2>
        {events.length === 0 ? (
          <p className="wi-empty" data-testid="wi-timeline-empty">
            {t('workItem.timelineEmpty')}
          </p>
        ) : (
          <ol className="wi-timeline-list">
            {events.map((event) => (
              <TimelineEntry key={event.id} event={event} lang={i18n.language} />
            ))}
          </ol>
        )}
      </section>

      {handoffs.length > 0 && (
        <section className="wi-section" data-testid="wi-handoffs">
          <h2 className="wi-section-title">{t('workItem.handoffTitle')}</h2>
          {handoffs.map((comment) => (
            <HandoffCard key={comment.id} comment={comment} lang={i18n.language} />
          ))}
        </section>
      )}

      <section className="wi-section" data-testid="wi-comments">
        <h2 className="wi-section-title">{t('workItem.commentsTitle')}</h2>
        {comments.length === 0 ? (
          <p className="wi-empty" data-testid="wi-comments-empty">
            {t('workItem.commentsEmpty')}
          </p>
        ) : (
          <div className="wi-comment-groups">
            {[...commentsByKind.entries()].map(([kind, list]) => (
              <section key={kind} className="wi-comment-group" data-testid={`wi-comment-group-${kind}`}>
                <h3 className="wi-comment-group-title">
                  <span className={`board-kind-badge board-kind-${kind}`}>
                    {t(`workItem.kind.${kind}`, { defaultValue: kind })}
                  </span>
                  <span className="wi-comment-group-count">{list.length}</span>
                </h3>
                <ul className="wi-comment-list">
                  {list.map((comment) => (
                    <CommentItem key={comment.id} comment={comment} lang={i18n.language} />
                  ))}
                </ul>
              </section>
            ))}
          </div>
        )}
      </section>

      <section className="wi-section" data-testid="wi-reservations">
        <h2 className="wi-section-title">{t('workItem.reservationsTitle')}</h2>
        {reservations.length === 0 ? (
          <p className="wi-empty" data-testid="wi-reservations-empty">
            {t('workItem.reservationsEmpty')}
          </p>
        ) : (
          <ul className="wi-reservation-list">
            {reservations.map((reservation) => (
              <ReservationRow key={reservation.id} reservation={reservation} lang={i18n.language} />
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}
