/**
 * WIKI4AI-97: Calendar page — month view with events.
 *
 * Month grid (Monday–Sunday) with prev/next/today navigation, event pills
 * colored by event type (private events carry a lock icon; all-day and
 * timed events are visually distinct), day click → create form for that day,
 * event click → detail modal with edit/delete. Data: GET /calendar/events
 * for the visible month (public + own private — no `mine` param).
 */
import React, { useCallback, useEffect, useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import EventFormModal from '../components/EventFormModal';
import {
  deleteEvent,
  fetchEvents,
  fetchEventTypes,
  type CalendarEvent,
  type CalendarEventType,
} from '../services/calendarApi';
import './CalendarPage.css';

// ── Date helpers (local time, no UTC drift) ─────────────────────────────────

function toISODate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function isSameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

/** Month label in the active UI language (e.g. "September 2026" / "september 2026"). */
function monthLabel(year: number, month: number, lang: string): string {
  return new Intl.DateTimeFormat(lang === 'sk' ? 'sk' : 'en', {
    month: 'long',
    year: 'numeric',
  }).format(new Date(year, month, 1));
}

/** Validate a hex color from the backend; fall back to the primary accent. */
function safeColor(color: string | null): string {
  return color && /^#[0-9a-fA-F]{6}$/.test(color) ? color : '#4f8cff';
}

/** Normalize backend "HH:mm:ss" times for display as "HH:mm". */
function formatTime(t: string | null): string {
  if (!t) return '';
  const m = t.match(/^(\d{2}:\d{2})/);
  return m ? m[1] : t;
}

// ── Inline SVG icons (no emoji — the container has no emoji font) ───────────

function ChevronLeftIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <polyline points="15 18 9 12 15 6" />
    </svg>
  );
}

function ChevronRightIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <polyline points="9 18 15 12 9 6" />
    </svg>
  );
}

function LockIcon() {
  return (
    <svg width="10" height="10" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
      <path d="M7 11V7a5 5 0 0 1 10 0v4" />
    </svg>
  );
}

// ── Form state: create for a day, or edit an existing event ────────────────

type FormState =
  | { mode: 'create'; date: string }
  | { mode: 'edit'; event: CalendarEvent };

const MAX_PILLS_PER_CELL = 3;

const CalendarPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const today = useMemo(() => new Date(), []);

  const [viewYear, setViewYear] = useState(today.getFullYear());
  const [viewMonth, setViewMonth] = useState(today.getMonth()); // 0-based

  const [events, setEvents] = useState<CalendarEvent[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  const [eventTypes, setEventTypes] = useState<CalendarEventType[]>([]);

  const [formState, setFormState] = useState<FormState | null>(null);
  const [detailEvent, setDetailEvent] = useState<CalendarEvent | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);
  const [deleting, setDeleting] = useState(false);

  // ── Data loading ──────────────────────────────────────────────────────────

  const loadEvents = useCallback(async () => {
    setLoading(true);
    setLoadError(null);
    try {
      const first = new Date(viewYear, viewMonth, 1);
      const last = new Date(viewYear, viewMonth + 1, 0);
      const list = await fetchEvents(toISODate(first), toISODate(last));
      setEvents(list);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : t('calendar.loadError'));
    } finally {
      setLoading(false);
    }
  }, [viewYear, viewMonth, t]);

  useEffect(() => {
    void loadEvents();
  }, [loadEvents]);

  useEffect(() => {
    fetchEventTypes()
      .then(setEventTypes)
      .catch(() => {
        // Non-fatal: the form will simply show an empty type list.
      });
  }, []);

  // ── Month grid (Monday-first) ─────────────────────────────────────────────

  const cells = useMemo(() => {
    const firstOfMonth = new Date(viewYear, viewMonth, 1);
    // getDay(): 0=Sun..6=Sat → Monday-based offset: Mon=0 … Sun=6
    const startOffset = (firstOfMonth.getDay() + 6) % 7;
    const daysInMonth = new Date(viewYear, viewMonth + 1, 0).getDate();
    const totalCells = Math.ceil((startOffset + daysInMonth) / 7) * 7;
    return Array.from({ length: totalCells }, (_, i) => {
      const date = new Date(viewYear, viewMonth, 1 - startOffset + i);
      return { date, inMonth: date.getMonth() === viewMonth };
    });
  }, [viewYear, viewMonth]);

  const eventsByDay = useMemo(() => {
    const map = new Map<string, CalendarEvent[]>();
    for (const event of events ?? []) {
      const list = map.get(event.eventDate) ?? [];
      list.push(event);
      map.set(event.eventDate, list);
    }
    return map;
  }, [events]);

  const monthHasEvents = useMemo(
    () => (events ?? []).some((e) => e.eventDate.slice(0, 7) === toISODate(new Date(viewYear, viewMonth, 1)).slice(0, 7)),
    [events, viewYear, viewMonth],
  );

  // ── Navigation ────────────────────────────────────────────────────────────

  const goPrev = () => {
    if (viewMonth === 0) {
      setViewMonth(11);
      setViewYear((y) => y - 1);
    } else {
      setViewMonth((m) => m - 1);
    }
  };

  const goNext = () => {
    if (viewMonth === 11) {
      setViewMonth(0);
      setViewYear((y) => y + 1);
    } else {
      setViewMonth((m) => m + 1);
    }
  };

  const goToday = () => {
    setViewYear(today.getFullYear());
    setViewMonth(today.getMonth());
  };

  // ── Interactions ──────────────────────────────────────────────────────────

  const openCreateForDay = (date: Date) => {
    setFormState({ mode: 'create', date: toISODate(date) });
  };

  const openDetail = (event: CalendarEvent) => {
    setDetailError(null);
    setDetailEvent(event);
  };

  const startEdit = () => {
    if (!detailEvent) return;
    setFormState({ mode: 'edit', event: detailEvent });
    setDetailEvent(null);
  };

  const handleDelete = async () => {
    if (!detailEvent) return;
    if (!window.confirm(t('calendar.detail.deleteConfirm', { title: detailEvent.title }))) return;
    setDeleting(true);
    setDetailError(null);
    try {
      await deleteEvent(detailEvent.id);
      setDetailEvent(null);
      void loadEvents();
    } catch (err) {
      // 403 (foreign public) / 404 (gone or foreign private) — surface, don't crash.
      setDetailError(err instanceof Error ? err.message : t('calendar.detail.deleteFailed'));
    } finally {
      setDeleting(false);
    }
  };

  const handleSaved = () => {
    setFormState(null);
    void loadEvents();
  };

  // ── Render ────────────────────────────────────────────────────────────────

  if (loading && events === null) {
    return (
      <div className="calendar-page">
        <div className="loading-state">
          <div className="spinner" />
          <p>{t('calendar.loading')}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="calendar-page">
      {/* Header: title + month navigation */}
      <header className="cal-header">
        <h1 data-testid="cal-title">{t('calendar.title')}</h1>
        <div className="cal-nav" data-testid="cal-nav">
          <button type="button" className="cal-nav-btn" onClick={goPrev} aria-label={t('calendar.prevMonth')} data-testid="cal-prev-month">
            <ChevronLeftIcon />
          </button>
          <span className="cal-month-label" data-testid="cal-month-label">
            {monthLabel(viewYear, viewMonth, i18n.language)}
          </span>
          <button type="button" className="cal-nav-btn" onClick={goNext} aria-label={t('calendar.nextMonth')} data-testid="cal-next-month">
            <ChevronRightIcon />
          </button>
          <button type="button" className="cal-today-btn" onClick={goToday} data-testid="cal-today-btn">
            {t('calendar.today')}
          </button>
        </div>
      </header>

      {loadError && (
        <div className="cal-error-box" role="alert" data-testid="cal-load-error">
          <span>{loadError}</span>
          <button type="button" className="btn-secondary btn-sm" onClick={() => void loadEvents()} data-testid="cal-retry-btn">
            {t('calendar.retry')}
          </button>
        </div>
      )}

      {!monthHasEvents && !loading && !loadError && (
        <p className="cal-no-events" data-testid="cal-no-events">
          {t('calendar.noEvents')}
        </p>
      )}

      {/* Weekday header (Monday first) */}
      <div className="cal-weekdays" aria-hidden="true">
        {(['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const).map((dow) => (
          <div key={dow} className="cal-weekday">
            {t(`calendar.weekday.${dow}`)}
          </div>
        ))}
      </div>

      {/* Month grid */}
      <div className="cal-grid" data-testid="cal-grid">
        {cells.map(({ date, inMonth }) => {
          const iso = toISODate(date);
          const dayEvents = eventsByDay.get(iso) ?? [];
          const isToday = isSameDay(date, today);
          return (
            <div
              key={iso}
              className={[
                'cal-cell',
                inMonth ? '' : 'cal-cell-other',
                isToday ? 'cal-cell-today' : '',
              ].join(' ')}
              data-testid={`cal-cell-${iso}`}
              onClick={() => openCreateForDay(date)}
              role="button"
              tabIndex={0}
              onKeyDown={(e) => {
                if (e.key === 'Enter' || e.key === ' ') {
                  e.preventDefault();
                  openCreateForDay(date);
                }
              }}
            >
              <span className={`cal-day-number${isToday ? ' cal-day-number-today' : ''}`}>{date.getDate()}</span>
              <div className="cal-cell-events">
                {dayEvents.slice(0, MAX_PILLS_PER_CELL).map((event) => {
                  const color = safeColor(event.eventColor);
                  const isPrivate = event.visibility === 'private';
                  return (
                    <button
                      key={event.id}
                      type="button"
                      className={`cal-pill ${event.startTime ? 'cal-pill-timed' : 'cal-pill-allday'}`}
                      style={{
                        background: event.startTime ? `${color}2e` : `${color}cc`,
                        borderLeftColor: color,
                      }}
                      title={event.startTime ? `${formatTime(event.startTime)} ${event.title}` : event.title}
                      data-testid={`cal-event-pill-${event.id}`}
                      onClick={(e) => {
                        e.stopPropagation();
                        openDetail(event);
                      }}
                    >
                      {isPrivate && (
                        <span className="cal-pill-lock" data-testid={`cal-event-lock-${event.id}`}>
                          <LockIcon />
                        </span>
                      )}
                      {event.startTime && <span className="cal-pill-time">{formatTime(event.startTime)}</span>}
                      <span className="cal-pill-title">{event.title}</span>
                    </button>
                  );
                })}
                {dayEvents.length > MAX_PILLS_PER_CELL && (
                  <span className="cal-more" data-testid={`cal-more-${iso}`}>
                    {t('calendar.moreEvents', { count: dayEvents.length - MAX_PILLS_PER_CELL })}
                  </span>
                )}
              </div>
            </div>
          );
        })}
      </div>

      {/* Detail modal (edit / delete) */}
      {detailEvent && (
        <div className="cal-modal-overlay" data-testid="event-detail-overlay">
          <div className="cal-modal cal-detail-modal" role="dialog" aria-modal="true" data-testid="event-detail">
            <h2>
              {detailEvent.visibility === 'private' && (
                <span className="cal-detail-lock">
                  <LockIcon />
                </span>
              )}
              {detailEvent.title}
            </h2>

            <dl className="cal-detail-rows">
              <div className="cal-detail-row">
                <dt>{t('calendar.detail.date')}</dt>
                <dd data-testid="cal-detail-date">{detailEvent.eventDate}</dd>
              </div>
              <div className="cal-detail-row">
                <dt>{t('calendar.detail.time')}</dt>
                <dd data-testid="cal-detail-time">
                  {detailEvent.startTime ? (
                    `${formatTime(detailEvent.startTime)} – ${formatTime(detailEvent.endTime) || formatTime(detailEvent.startTime)}`
                  ) : (
                    t('calendar.allDay')
                  )}
                </dd>
              </div>
              <div className="cal-detail-row">
                <dt>{t('calendar.detail.type')}</dt>
                <dd data-testid="cal-detail-type">
                  <span
                    className="cal-detail-color-dot"
                    style={{ background: safeColor(detailEvent.eventColor) }}
                    aria-hidden="true"
                  />
                  {detailEvent.eventType ?? '—'}
                </dd>
              </div>
              <div className="cal-detail-row">
                <dt>{t('calendar.detail.visibility')}</dt>
                <dd data-testid="cal-detail-visibility">
                  {detailEvent.visibility === 'private' ? t('calendar.form.private') : t('calendar.form.public')}
                </dd>
              </div>
              <div className="cal-detail-row">
                <dt>{t('calendar.detail.createdBy')}</dt>
                <dd data-testid="cal-detail-created-by">{detailEvent.createdBy}</dd>
              </div>
            </dl>

            {detailEvent.description && (
              <p className="cal-detail-description" data-testid="cal-detail-description">
                {detailEvent.description}
              </p>
            )}

            {detailError && (
              <p className="cal-form-error" role="alert" data-testid="cal-detail-error">
                {detailError}
              </p>
            )}

            <div className="cal-form-actions">
              <button type="button" className="btn-danger" onClick={() => void handleDelete()} disabled={deleting} data-testid="cal-detail-delete">
                {t('common.delete')}
              </button>
              <span className="cal-detail-spacer" />
              <button type="button" className="btn-secondary" onClick={() => setDetailEvent(null)} data-testid="cal-detail-close">
                {t('common.close')}
              </button>
              <button type="button" className="btn-primary" onClick={startEdit} data-testid="cal-detail-edit">
                {t('calendar.detail.edit')}
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Create / edit form modal */}
      {formState && (
        <EventFormModal
          mode={formState.mode}
          initialDate={formState.mode === 'create' ? formState.date : undefined}
          event={formState.mode === 'edit' ? formState.event : null}
          types={eventTypes}
          onTypesChanged={setEventTypes}
          onClose={() => setFormState(null)}
          onSaved={handleSaved}
        />
      )}
    </div>
  );
};

export default CalendarPage;
