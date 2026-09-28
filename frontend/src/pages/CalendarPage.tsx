/**
 * WIKI4AI-97: Calendar page — month view with events.
 * WIKI4AI-110: week view (hourly grid) + Mesiac/Týždeň toggle.
 *
 * Month grid (Monday–Sunday) with prev/next/today navigation, event pills
 * colored by event type (private events carry a lock icon; all-day and
 * timed events are visually distinct), day click → create form for that day,
 * event click → detail modal with edit/delete.
 *
 * Week view: 7 day columns × hourly rows (00–23). Timed events are absolutely
 * positioned blocks placed by their exact time; overlapping events share the
 * column side by side (calendarWeekLayout). All-day events live in a strip
 * above the grid. Clicking an empty hour slot opens the create form with the
 * slot's date + time pre-filled. The toggle state persists across re-renders
 * and is reflected in the URL (?view=week).
 *
 * Data: GET /calendar/events for the visible range (public + own private —
 * no `mine` param).
 */
import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useSearchParams } from 'react-router-dom';
import EventFormModal from '../components/EventFormModal';
import {
  deleteEvent,
  fetchEvents,
  fetchEventTypes,
  type CalendarEvent,
  type CalendarEventType,
} from '../services/calendarApi';
import {
  HOURS_PER_DAY,
  eventSpan,
  layoutDayEvents,
  startOfWeek,
  weekDays,
  type PlacedEvent,
} from './calendarWeekLayout';
import './CalendarPage.css';

// ── Date helpers (local time, no UTC drift) ─────────────────────────────────

function toISODate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

function addDays(d: Date, days: number): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + days);
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

/** Week range label, e.g. "27. sep – 3. okt 2026" (SK) / "Sep 27 – Oct 3 2026" (EN). */
function weekRangeLabel(weekStart: Date, lang: string): string {
  const locale = lang === 'sk' ? 'sk' : 'en';
  const end = addDays(weekStart, 6);
  const dayMonth = (d: Date) => new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short' }).format(d);
  const monthShort = (d: Date) => new Intl.DateTimeFormat(locale, { month: 'short' }).format(d);
  if (end.getMonth() === weekStart.getMonth()) {
    return `${weekStart.getDate()} – ${end.getDate()} ${monthShort(end)} ${end.getFullYear()}`;
  }
  const year = end.getFullYear() !== weekStart.getFullYear() ? ` ${end.getFullYear()}` : '';
  return `${dayMonth(weekStart)} – ${dayMonth(end)}${year}`;
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

// ── Week view geometry ───────────────────────────────────────────────────────

/** Height of one hour row in the weekly time grid (px). */
const HOUR_HEIGHT_PX = 48;
/** Minimum rendered height of an event block (px) — short events stay clickable. */
const MIN_BLOCK_HEIGHT_PX = 20;
/**
 * Default scroll position when entering the week view: the 08:00 hour line sits
 * near the top of the visible grid. WIKI4AI-115: previously this was
 * (INITIAL_SCROLL_HOUR - 1) × HOUR_HEIGHT_PX, which put the 07:00 row exactly at
 * the scroll area's top edge and half-clipped its on-line time label ("U:00").
 * .cal-week-grid now carries a 12px padding-top clearance (see CalendarPage.css),
 * so scrolling to INITIAL_SCROLL_HOUR × HOUR_HEIGHT_PX leaves the first visible
 * label (08:00) fully readable — and "00:00" stays unclipped at scrollTop = 0.
 */
const INITIAL_SCROLL_HOUR = 8;

const WEEKDAY_KEYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const;

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

// ── Form state: create for a day (optionally with a slot time), or edit ─────

type ViewMode = 'month' | 'week';

type FormState =
  | { mode: 'create'; date: string; startTime?: string; endTime?: string }
  | { mode: 'edit'; event: CalendarEvent };

const MAX_PILLS_PER_CELL = 3;

const CalendarPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const today = useMemo(() => new Date(), []);

  // ── View mode (Mesiac | Týždeň) — persisted in the URL (?view=week) ───────

  const [searchParams, setSearchParams] = useSearchParams();
  const [viewMode, setViewModeState] = useState<ViewMode>(() =>
    searchParams.get('view') === 'week' ? 'week' : 'month',
  );

  const setViewMode = useCallback(
    (mode: ViewMode) => {
      setViewModeState(mode);
      const next = new URLSearchParams(searchParams);
      if (mode === 'week') next.set('view', 'week');
      else next.delete('view');
      setSearchParams(next, { replace: true });
    },
    [searchParams, setSearchParams],
  );

  // ── Visible range: month anchor or week anchor (Monday) ───────────────────

  const [viewYear, setViewYear] = useState(today.getFullYear());
  const [viewMonth, setViewMonth] = useState(today.getMonth()); // 0-based
  const [weekAnchor, setWeekAnchor] = useState<Date>(() => startOfWeek(today));

  const days = useMemo(() => weekDays(weekAnchor), [weekAnchor]);

  // ── Data ──────────────────────────────────────────────────────────────────

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
      let from: Date;
      let last: Date;
      if (viewMode === 'month') {
        from = new Date(viewYear, viewMonth, 1);
        last = new Date(viewYear, viewMonth + 1, 0);
      } else {
        from = days[0];
        last = days[6];
      }
      const list = await fetchEvents(toISODate(from), toISODate(last));
      setEvents(list);
    } catch (err) {
      setLoadError(err instanceof Error ? err.message : t('calendar.loadError'));
    } finally {
      setLoading(false);
    }
  }, [viewMode, viewYear, viewMonth, days, t]);

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

  // ── Week grid: events grouped per day + overlap layout ────────────────────

  const eventsByDay = useMemo(() => {
    const map = new Map<string, CalendarEvent[]>();
    for (const event of events ?? []) {
      const list = map.get(event.eventDate) ?? [];
      list.push(event);
      map.set(event.eventDate, list);
    }
    return map;
  }, [events]);

  /** Timed events per visible day with their overlap columns (stable input order). */
  const placedByDay = useMemo(() => {
    const map = new Map<string, PlacedEvent[]>();
    for (const date of days) {
      const iso = toISODate(date);
      const dayEvents = eventsByDay.get(iso) ?? [];
      map.set(iso, layoutDayEvents(dayEvents.map((e) => eventSpan(e.startTime, e.endTime))));
    }
    return map;
  }, [days, eventsByDay]);

  const rangeHasEvents = useMemo(() => {
    const list = events ?? [];
    if (list.length === 0) return false;
    if (viewMode === 'month') {
      const prefix = toISODate(new Date(viewYear, viewMonth, 1)).slice(0, 7);
      return list.some((e) => e.eventDate.slice(0, 7) === prefix);
    }
    const fromISO = toISODate(days[0]);
    const toISO = toISODate(days[6]);
    return list.some((e) => e.eventDate >= fromISO && e.eventDate <= toISO);
  }, [events, viewMode, viewYear, viewMonth, days]);

  // ── Week grid scroll: default position ≈ 08:00 when entering week view ────

  const weekTimeScrollRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (viewMode !== 'week') return;
    const el = weekTimeScrollRef.current;
    // The .cal-week-grid padding-top clearance keeps the on-line label of the
    // INITIAL_SCROLL_HOUR row fully visible at this position (see above).
    if (el) el.scrollTop = INITIAL_SCROLL_HOUR * HOUR_HEIGHT_PX;
  }, [viewMode]);

  // ── Navigation (month steps / week steps depending on the active view) ────

  const goPrev = () => {
    if (viewMode === 'week') {
      setWeekAnchor((a) => addDays(a, -7));
      return;
    }
    if (viewMonth === 0) {
      setViewMonth(11);
      setViewYear((y) => y - 1);
    } else {
      setViewMonth((m) => m - 1);
    }
  };

  const goNext = () => {
    if (viewMode === 'week') {
      setWeekAnchor((a) => addDays(a, 7));
      return;
    }
    if (viewMonth === 11) {
      setViewMonth(0);
      setViewYear((y) => y + 1);
    } else {
      setViewMonth((m) => m + 1);
    }
  };

  const goToday = () => {
    if (viewMode === 'week') {
      setWeekAnchor(startOfWeek(today));
      return;
    }
    setViewYear(today.getFullYear());
    setViewMonth(today.getMonth());
  };

  // ── Interactions ──────────────────────────────────────────────────────────

  const openCreateForDay = (date: Date) => {
    setFormState({ mode: 'create', date: toISODate(date) });
  };

  /** Week view: click on an empty hour slot → timed event for that hour. */
  const openCreateForSlot = (date: Date, hour: number) => {
    const hh = String(hour).padStart(2, '0');
    const startTime = `${hh}:00`;
    const endTime = hour < HOURS_PER_DAY - 1 ? `${String(hour + 1).padStart(2, '0')}:00` : '';
    setFormState({ mode: 'create', date: toISODate(date), startTime, endTime });
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

  // ── Render helpers (shared by both views) ─────────────────────────────────

  const renderEventPill = (event: CalendarEvent, testIdPrefix: string) => {
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
        data-testid={`${testIdPrefix}-${event.id}`}
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

  const rangeLabel = viewMode === 'month' ? monthLabel(viewYear, viewMonth, i18n.language) : weekRangeLabel(days[0], i18n.language);

  return (
    <div className="calendar-page">
      {/* Header: title + view toggle + range navigation */}
      <header className="cal-header">
        <h1 data-testid="cal-title">{t('calendar.title')}</h1>
        <div className="cal-nav" data-testid="cal-nav">
          <div className="cal-view-toggle" role="group" aria-label={t('calendar.view.label')}>
            <button
              type="button"
              className={`cal-view-option${viewMode === 'month' ? ' cal-view-option-active' : ''}`}
              aria-pressed={viewMode === 'month'}
              onClick={() => setViewMode('month')}
              data-testid="cal-view-toggle-month"
            >
              {t('calendar.view.month')}
            </button>
            <button
              type="button"
              className={`cal-view-option${viewMode === 'week' ? ' cal-view-option-active' : ''}`}
              aria-pressed={viewMode === 'week'}
              onClick={() => setViewMode('week')}
              data-testid="cal-view-toggle-week"
            >
              {t('calendar.view.week')}
            </button>
          </div>
          <button type="button" className="cal-nav-btn" onClick={goPrev} aria-label={viewMode === 'week' ? t('calendar.prevWeek') : t('calendar.prevMonth')} data-testid="cal-prev-month">
            <ChevronLeftIcon />
          </button>
          <span className="cal-month-label" data-testid="cal-month-label">
            {rangeLabel}
          </span>
          <button type="button" className="cal-nav-btn" onClick={goNext} aria-label={viewMode === 'week' ? t('calendar.nextWeek') : t('calendar.nextMonth')} data-testid="cal-next-month">
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

      {!rangeHasEvents && !loading && !loadError && (
        <p className="cal-no-events" data-testid="cal-no-events">
          {viewMode === 'week' ? t('calendar.noEventsWeek') : t('calendar.noEvents')}
        </p>
      )}

      {viewMode === 'month' ? (
        <>
          {/* Weekday header (Monday first) */}
          <div className="cal-weekdays" aria-hidden="true">
            {WEEKDAY_KEYS.map((dow) => (
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
                    {dayEvents.slice(0, MAX_PILLS_PER_CELL).map((event) => renderEventPill(event, 'cal-event-pill'))}
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
        </>
      ) : (
        /* ── Week view ─────────────────────────────────────────────────────── */
        <div className="cal-week" data-testid="cal-week">
          {/* Horizontal scroll wrapper: on narrow screens the 7 day columns
              keep their min width and the grid scrolls sideways instead of
              collapsing (WIKI4AI-109 lesson). */}
          <div className="cal-week-scroll">
            <div className="cal-week-inner">
              {/* Day header row */}
              <div className="cal-week-head" aria-hidden="true">
                <div className="cal-week-gutter-spacer" />
                {days.map((date, i) => {
                  const iso = toISODate(date);
                  const isToday = isSameDay(date, today);
                  return (
                    <div key={iso} className={`cal-week-dayhead${isToday ? ' cal-week-dayhead-today' : ''}`}>
                      <span className="cal-week-dayhead-dow">{t(`calendar.weekday.${WEEKDAY_KEYS[i]}`)}</span>
                      <span className="cal-week-dayhead-num">{date.getDate()}</span>
                    </div>
                  );
                })}
              </div>

              {/* All-day strip (events without a time) */}
              <div className="cal-week-allday" data-testid="cal-week-allday-strip">
                <div className="cal-week-gutter-spacer">
                  <span className="cal-week-allday-label">{t('calendar.allDay')}</span>
                </div>
                {days.map((date) => {
                  const iso = toISODate(date);
                  const alldayEvents = (eventsByDay.get(iso) ?? []).filter((e) => !e.startTime);
                  return (
                    <div
                      key={iso}
                      className="cal-week-allday-cell"
                      onClick={() => openCreateForDay(date)}
                      role="button"
                      tabIndex={0}
                      onKeyDown={(e) => {
                        if (e.key === 'Enter' || e.key === ' ') {
                          e.preventDefault();
                          openCreateForDay(date);
                        }
                      }}
                      data-testid={`cal-week-allday-cell-${iso}`}
                    >
                      {alldayEvents.map((event) => renderEventPill(event, 'cal-week-allday-pill'))}
                    </div>
                  );
                })}
              </div>

              {/* Hourly time grid (vertical scroll; default position ≈ 08:00) */}
              <div className="cal-week-time-scroll" ref={weekTimeScrollRef}>
                <div className="cal-week-grid" data-testid="cal-week-grid">
                  {/* Time axis (left gutter) */}
                  <div className="cal-time-gutter" aria-hidden="true">
                    {Array.from({ length: HOURS_PER_DAY }, (_, h) => (
                      <div key={h} className="cal-hour-label" style={{ height: HOUR_HEIGHT_PX }}>
                        <span>{String(h).padStart(2, '0')}:00</span>
                      </div>
                    ))}
                  </div>

                  {/* One column per day */}
                  {days.map((date) => {
                    const iso = toISODate(date);
                    const isToday = isSameDay(date, today);
                    const dayEvents = eventsByDay.get(iso) ?? [];
                    const placed = placedByDay.get(iso) ?? [];
                    return (
                      <div key={iso} className={`cal-day-col${isToday ? ' cal-day-col-today' : ''}`} data-testid={`cal-week-day-col-${iso}`}>
                        {Array.from({ length: HOURS_PER_DAY }, (_, h) => (
                          <div
                            key={h}
                            className="cal-hour-cell"
                            style={{ height: HOUR_HEIGHT_PX }}
                            onClick={() => openCreateForSlot(date, h)}
                            role="button"
                            tabIndex={0}
                            aria-label={`${iso} ${String(h).padStart(2, '0')}:00`}
                            onKeyDown={(e) => {
                              if (e.key === 'Enter' || e.key === ' ') {
                                e.preventDefault();
                                openCreateForSlot(date, h);
                              }
                            }}
                            data-testid={`cal-week-hour-cell-${iso}-${h}`}
                          />
                        ))}
                        {/* Timed events — absolutely positioned by time */}
                        {placed.map((pe) => {
                          const event = dayEvents[pe.index];
                          if (!event) return null;
                          const color = safeColor(event.eventColor);
                          const isPrivate = event.visibility === 'private';
                          const topPx = (pe.span.startMin / 60) * HOUR_HEIGHT_PX;
                          const heightPx = Math.max(
                            ((pe.span.endMin - pe.span.startMin) / 60) * HOUR_HEIGHT_PX,
                            MIN_BLOCK_HEIGHT_PX,
                          );
                          const widthPct = 100 / pe.cols;
                          return (
                            <button
                              key={event.id}
                              type="button"
                              className="cal-event-block"
                              style={{
                                top: topPx,
                                height: heightPx,
                                left: `calc(${pe.col * widthPct}% + 2px)`,
                                width: `calc(${widthPct}% - 4px)`,
                                background: `${color}2e`,
                                borderLeftColor: color,
                              }}
                              title={`${formatTime(event.startTime)} – ${formatTime(event.endTime) || formatTime(event.startTime)} ${event.title}`}
                              data-testid={`cal-event-block-${event.id}`}
                              onClick={(e) => {
                                e.stopPropagation();
                                openDetail(event);
                              }}
                            >
                              <span className="cal-event-block-time">
                                {isPrivate && (
                                  <span className="cal-pill-lock" data-testid={`cal-event-lock-${event.id}`}>
                                    <LockIcon />
                                  </span>
                                )}
                                {formatTime(event.startTime)}–{formatTime(event.endTime) || formatTime(event.startTime)}
                              </span>
                              <span className="cal-event-block-title">{event.title}</span>
                            </button>
                          );
                        })}
                      </div>
                    );
                  })}
                </div>
              </div>
            </div>
          </div>
        </div>
      )}

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
          initialStartTime={formState.mode === 'create' ? formState.startTime : null}
          initialEndTime={formState.mode === 'create' ? formState.endTime : null}
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
