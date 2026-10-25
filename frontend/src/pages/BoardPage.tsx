/**
 * WIKI4AI-161: the coordination board (`/board`).
 *
 * Eight columns — backlog, ready, claimed, running, review, done and the two
 * terminal states blocked / cancelled, which are columns of their own so a blocked
 * item is never invisible. A card carries the title, the assignee with a presence
 * dot taken from `ActorDTO.status`, the lease countdown computed from
 * `leaseExpiresAt`, and the labels.
 *
 * Right sidebar:
 *   - "What is waiting for you": blocker comments the backend flags `needs_human`
 *     (GET /work-items/{id}/comments?kind=blocker&needs_human=true),
 *   - resource capacity: `activeSlots` vs `capacity` from GET /resources,
 *   - live agents from GET /actors.
 *
 * Live without a live connection: react-query `refetchInterval: 10_000`. There is no
 * WebSocket, no SSE and — the WIKI4AI-175 rule — no timer owned by this component at
 * all: the countdown is `leaseExpiresAt − fetchedAt`, recomputed when the data is
 * refetched, so nothing can outlive the unmount.
 *
 * SECURITY (WIKI4AI-177): `claimToken` is not a field of `WorkItemDTO` in this app —
 * boardApi drops it at the boundary. Nothing here renders a lease secret: the lease
 * is shown as its deadline.
 */
import { useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  WORK_ITEM_STATUSES,
  type WorkItemCommentDTO,
  type WorkItemDTO,
  type WorkItemFilters,
} from '../services/boardApi';
import {
  useActors,
  useResources,
  useWaitingForMe,
  useWorkItems,
} from '../hooks/useBoard';
import {
  boardColumns,
  durationParts,
  groupItemsByStatus,
  isLeaseExpired,
  leaseRemainingMs,
  parseApiTime,
} from './boardModel';
import './BoardPage.css';

// ── Inline SVG icons (no emoji — the container has no emoji font) ───────────

function BoardIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="4" width="5" height="16" rx="1" />
      <rect x="10" y="4" width="5" height="11" rx="1" />
      <rect x="17" y="4" width="4" height="8" rx="1" />
    </svg>
  );
}

function ClockIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="9" />
      <polyline points="12 7 12 12 15 14" />
    </svg>
  );
}

// ── Formatting helpers (data formatting, not translation) ────────────────────

/** Locale-aware rendering of a backend timestamp. */
function formatDateTime(iso: string | null, lang: string): string | null {
  const ms = parseApiTime(iso);
  if (ms === null) return null;
  return new Intl.DateTimeFormat(lang === 'sk' ? 'sk' : 'en', {
    day: 'numeric',
    month: 'short',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(ms));
}

/**
 * Lease countdown text. The value is `deadline − fetchedAt`, i.e. a pure function of
 * the payload and of the moment that payload was fetched.
 */
function leaseText(
  remainingMs: number,
  t: (key: string, options?: Record<string, unknown>) => string,
): string {
  const { hours, minutes, seconds } = durationParts(remainingMs);
  if (hours > 0) return t('board.leaseRemainingHours', { hours, minutes });
  if (minutes > 0) return t('board.leaseRemainingMinutes', { minutes, seconds });
  return t('board.leaseRemainingSeconds', { seconds });
}

// ── Card ─────────────────────────────────────────────────────────────────────

interface CardProps {
  item: WorkItemDTO;
  /** The actor row the API sent for this item's assignee, or null when there is none. */
  assignee: { handle: string; status: string } | null;
  /** Reference point of the countdown: when this board data was fetched. */
  referenceMs: number | null;
}

function WorkItemCard({ item, assignee, referenceMs }: CardProps) {
  const { t } = useTranslation();

  const remaining =
    referenceMs === null ? null : leaseRemainingMs(item.leaseExpiresAt, referenceMs);
  const expired = isLeaseExpired(remaining);
  const hasLease = remaining !== null;

  return (
    <article
      className={`board-card${expired ? ' board-card-lease-expired' : ''}`}
      data-testid={`board-card-${item.id}`}
      data-status={item.status}
    >
      <Link
        to={`/board/${item.id}`}
        className="board-card-title"
        data-testid={`board-card-title-${item.id}`}
      >
        {item.title}
      </Link>

      <div className="board-card-meta">
        {item.assigneeActorId === null ? (
          <span className="board-card-assignee board-card-unassigned" data-testid={`board-card-assignee-${item.id}`}>
            {t('board.unassigned')}
          </span>
        ) : assignee ? (
          <span
            className="board-card-assignee"
            data-testid={`board-card-assignee-${item.id}`}
            data-presence={assignee.status}
          >
            <span className={`presence-dot presence-${assignee.status}`} aria-hidden="true" />
            {assignee.handle}
          </span>
        ) : (
          <span className="board-card-assignee board-card-unknown-actor" data-testid={`board-card-assignee-${item.id}`}>
            {t('board.unknownActor', { actorId: item.assigneeActorId })}
          </span>
        )}

        {hasLease && (
          <span
            className={`board-card-lease${expired ? ' board-card-lease-expired' : ''}`}
            data-testid={`board-card-lease-${item.id}`}
          >
            <ClockIcon />
            {expired ? t('board.leaseExpired') : leaseText(remaining as number, t)}
          </span>
        )}
      </div>

      {item.labels.length > 0 && (
        <ul className="board-card-labels" data-testid={`board-card-labels-${item.id}`}>
          {item.labels.map((label) => (
            <li key={label} className="board-label-chip">
              {label}
            </li>
          ))}
        </ul>
      )}
    </article>
  );
}

// ── Sidebar blocks ───────────────────────────────────────────────────────────

function WaitingCard({ comments, itemTitles }: {
  comments: WorkItemCommentDTO[];
  itemTitles: Map<number, string>;
}) {
  const { t } = useTranslation();

  if (comments.length === 0) {
    return (
      <p className="board-side-empty" data-testid="board-waiting-empty">
        {t('board.waitingEmpty')}
      </p>
    );
  }

  return (
    <ul className="board-waiting-list" data-testid="board-waiting-list">
      {comments.map((comment) => (
        <li key={comment.id} className="board-waiting-item" data-testid={`board-waiting-${comment.id}`}>
          <div className="board-waiting-head">
            <span className="board-kind-badge board-kind-blocker">{t('board.kind.blocker')}</span>
            {comment.workItemId && (
              <Link to={`/board/${comment.workItemId}`} className="board-waiting-link">
                {itemTitles.get(comment.workItemId) ?? t('board.workItemRef', { id: comment.workItemId })}
              </Link>
            )}
          </div>
          <p className="board-waiting-body">{comment.body}</p>
        </li>
      ))}
    </ul>
  );
}

// ── Page ─────────────────────────────────────────────────────────────────────

export default function BoardPage() {
  const { t, i18n } = useTranslation();

  // Filters are API parameters, not client-side predicates: the backend ANDs them,
  // matches `label` as a whole token and answers 400 for an unknown status.
  const [filters, setFilters] = useState<WorkItemFilters>({});

  const board = useWorkItems(filters);
  const { actors } = useActors();
  const { resources } = useResources();
  const waiting = useWaitingForMe(board.items.map((item) => item.id));

  const columns = useMemo(() => boardColumns(board.items), [board.items]);
  const grouped = useMemo(() => groupItemsByStatus(board.items), [board.items]);

  const actorIndex = useMemo(
    () => new Map(actors.map((actor) => [actor.id, actor])),
    [actors],
  );
  const itemTitles = useMemo(
    () => new Map(board.items.map((item) => [item.id, item.title])),
    [board.items],
  );

  const referenceMs = board.fetchedAt;
  const activeAgents = actors.filter((actor) => actor.status === 'active');
  const updatedLabel = referenceMs === null
    ? null
    : formatDateTime(new Date(referenceMs).toISOString(), i18n.language);

  return (
    <div className="board-page">
      <header className="board-header">
        <div className="board-title-block">
          <h1 className="board-title">
            <span className="board-title-icon" aria-hidden="true"><BoardIcon /></span>
            {t('board.title')}
          </h1>
          <p className="board-subtitle">{t('board.subtitle')}</p>
        </div>

        <div className="board-filters" data-testid="board-filters">
          <label className="board-filter">
            <span className="board-filter-label">{t('board.filterStatus')}</span>
            <select
              className="board-filter-select"
              data-testid="board-filter-status"
              value={filters.status ?? ''}
              onChange={(e) => setFilters((f) => ({ ...f, status: e.target.value || null }))}
            >
              <option value="">{t('board.filterStatusAll')}</option>
              {WORK_ITEM_STATUSES.map((status) => (
                <option key={status} value={status}>
                  {t(`board.status.${status}`)}
                </option>
              ))}
            </select>
          </label>

          <label className="board-filter">
            <span className="board-filter-label">{t('board.filterLabel')}</span>
            <input
              type="text"
              className="board-filter-input"
              data-testid="board-filter-label"
              value={filters.label ?? ''}
              placeholder={t('board.filterLabelPlaceholder')}
              onChange={(e) => setFilters((f) => ({ ...f, label: e.target.value || null }))}
            />
          </label>

          <label className="board-filter">
            <span className="board-filter-label">{t('board.filterProject')}</span>
            <input
              type="number"
              className="board-filter-input"
              data-testid="board-filter-project"
              value={filters.projectId ?? ''}
              onChange={(e) =>
                setFilters((f) => ({
                  ...f,
                  projectId: e.target.value === '' ? null : Number(e.target.value),
                }))
              }
            />
          </label>
        </div>
      </header>

      {updatedLabel && (
        <p className="board-updated" data-testid="board-updated">
          {t('board.updatedAt', { time: updatedLabel })}
        </p>
      )}

      <div className="board-layout">
        <main className="board-columns" data-testid="board-columns">
          {board.isError && (
            <p className="board-error" data-testid="board-error">
              {t('board.loadError')}
            </p>
          )}

          {board.isLoading && board.items.length === 0 && (
            <p className="board-loading" data-testid="board-loading">
              {t('board.loading')}
            </p>
          )}

          {columns.map((status) => {
            const items = grouped.get(status) ?? [];
            return (
              <section
                key={status}
                className={`board-column board-column-${status}`}
                data-testid={`board-column-${status}`}
                aria-label={t(`board.status.${status}`)}
              >
                <header className="board-column-head">
                  <h2 className="board-column-title">{t(`board.status.${status}`)}</h2>
                  <span className="board-column-count" data-testid={`board-column-count-${status}`}>
                    {items.length}
                  </span>
                </header>

                <div className="board-column-body">
                  {items.length === 0 ? (
                    <p className="board-column-empty">{t('board.emptyColumn')}</p>
                  ) : (
                    items.map((item) => (
                      <WorkItemCard
                        key={item.id}
                        item={item}
                        assignee={
                          item.assigneeActorId === null
                            ? null
                            : actorIndex.get(item.assigneeActorId) ?? null
                        }
                        referenceMs={referenceMs}
                      />
                    ))
                  )}
                </div>
              </section>
            );
          })}
        </main>

        <aside className="board-sidebar" data-testid="board-sidebar">
          <section className="board-side-block" data-testid="board-waiting">
            <h2 className="board-side-title">{t('board.waitingTitle')}</h2>
            <WaitingCard comments={waiting.comments} itemTitles={itemTitles} />
          </section>

          <section className="board-side-block" data-testid="board-capacity">
            <h2 className="board-side-title">{t('board.capacityTitle')}</h2>
            {resources.length === 0 ? (
              <p className="board-side-empty" data-testid="board-capacity-empty">
                {t('board.capacityEmpty')}
              </p>
            ) : (
              <ul className="board-capacity-list" data-testid="board-capacity-list">
                {resources.map((resource) => (
                  <li key={resource.key} className="board-capacity-item" data-testid={`board-capacity-${resource.key}`}>
                    <span className="board-capacity-name">
                      {resource.name ?? resource.key}
                      {resource.enabled === false && (
                        <span className="board-capacity-disabled">{t('board.capacityDisabled')}</span>
                      )}
                    </span>
                    <span className="board-capacity-slots">
                      {t('board.capacitySlots', {
                        active: resource.activeSlots ?? 0,
                        capacity: resource.capacity ?? 0,
                      })}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className="board-side-block" data-testid="board-agents">
            <h2 className="board-side-title">
              {t('board.agentsTitle')}
              <span className="board-side-count" data-testid="board-agents-active-count">
                {activeAgents.length}
              </span>
            </h2>
            {actors.length === 0 ? (
              <p className="board-side-empty" data-testid="board-agents-empty">
                {t('board.agentsEmpty')}
              </p>
            ) : (
              <ul className="board-agents-list" data-testid="board-agents-list">
                {actors.map((actor) => (
                  <li key={actor.id} className="board-agent" data-testid={`board-agent-${actor.id}`} data-presence={actor.status}>
                    <span className={`presence-dot presence-${actor.status}`} aria-hidden="true" />
                    <span className="board-agent-handle">{actor.handle}</span>
                    <span className={`board-agent-status board-agent-status-${actor.status}`}>
                      {t(`board.presence.${actor.status}`, { defaultValue: actor.status })}
                    </span>
                  </li>
                ))}
              </ul>
            )}
          </section>
        </aside>
      </div>
    </div>
  );
}
