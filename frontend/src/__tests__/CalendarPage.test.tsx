/**
 * WIKI4AI-97: Calendar page component tests.
 *
 * Covers: month grid rendering with event pills (all-day vs timed, private
 * lock icon), prev/next/today navigation (refetches the visible range),
 * day click → create form pre-filled with that date, and event pill click →
 * detail modal with edit entry point.
 *
 * WIKI4AI-110: week view — Mesiac/Týždeň toggle (state persisted in the URL),
 * hourly grid rendering, timed event blocks positioned by time, side-by-side
 * overlap layout, all-day strip, hour-slot click → create form pre-filled with
 * date + time, week navigation, and edit from the week view.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import CalendarPage from '../pages/CalendarPage'
import { startOfWeek } from '../pages/calendarWeekLayout'
import * as calendarApi from '../services/calendarApi'

vi.mock('../services/calendarApi', () => ({
  fetchEvents: vi.fn(),
  fetchEventTypes: vi.fn(),
  createEvent: vi.fn(),
  updateEvent: vi.fn(),
  deleteEvent: vi.fn(),
  createEventType: vi.fn(),
}))

const mockFetchEvents = vi.mocked(calendarApi.fetchEvents)
const mockFetchEventTypes = vi.mocked(calendarApi.fetchEventTypes)

// ── Helpers ──────────────────────────────────────────────────────────────────

function toISO(d: Date): string {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function monthRange(year: number, month: number): { from: string; to: string } {
  return {
    from: toISO(new Date(year, month, 1)),
    to: toISO(new Date(year, month + 1, 0)),
  }
}

function makeEvent(overrides: Partial<calendarApi.CalendarEvent> & { id: number }): calendarApi.CalendarEvent {
  return {
    title: 'Event',
    description: null,
    eventTypeId: 1,
    eventType: 'Agent task',
    eventColor: '#4f8cff',
    eventDate: toISO(new Date()),
    startTime: null,
    endTime: null,
    visibility: 'public',
    createdBy: 'alice',
    createdAt: '2026-09-26T00:00:00Z',
    updatedAt: '2026-09-26T00:00:00Z',
    ...overrides,
  }
}

const TYPES: calendarApi.CalendarEventType[] = [
  { id: 1, name: 'Agent task', color: '#4f8cff' },
  { id: 2, name: 'Pripomienka', color: '#f59e0b' },
]

function renderCalendar() {
  return render(
    <MemoryRouter initialEntries={['/calendar']}>
      <CalendarPage />
    </MemoryRouter>,
  )
}

// ── Tests ────────────────────────────────────────────────────────────────────

describe('CalendarPage (WIKI4AI-97)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    const now = new Date()
    mockFetchEventTypes.mockResolvedValue(TYPES)
    mockFetchEvents.mockImplementation(async (from: string, to: string) => {
      // Only the current month carries events in these tests.
      const current = toISO(new Date()).slice(0, 7)
      if (current === from.slice(0, 7) && current === to.slice(0, 7)) {
        return [
          makeEvent({ id: 11, title: 'Team sync', eventDate: toISO(new Date()) }),
          // Backend returns times as "HH:mm:ss" — the UI must normalize to "HH:mm".
          makeEvent({
            id: 12,
            title: 'Deploy window',
            eventDate: toISO(new Date()),
            startTime: '14:00:00',
            endTime: '15:00:00',
            eventTypeId: 2,
            eventType: 'Pripomienka',
            eventColor: '#f59e0b',
          }),
          makeEvent({
            id: 13,
            title: 'Personal note',
            eventDate: toISO(new Date()),
            startTime: '09:30',
            visibility: 'private',
          }),
        ]
      }
      return []
    })
    void now
  })

  it('renders the month grid with weekday headers (Monday first)', async () => {
    renderCalendar()
    await screen.findByTestId('cal-event-pill-11')

    // All seven weekday headers are present, in Monday-first order.
    const expected = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']
    for (const label of expected) {
      expect(screen.getByText(label, { exact: true })).toBeInTheDocument()
    }
    // The grid renders a full set of day cells (5 or 6 weeks × 7 days);
    // each cell is role="button" (nested event pills are buttons too).
    const grid = screen.getByTestId('cal-grid')
    expect(within(grid).getAllByRole('button').length).toBeGreaterThanOrEqual(35)
  })

  it('renders event pills: all-day, timed (with time prefix) and private (lock icon)', async () => {
    renderCalendar()

    const allDayPill = await screen.findByTestId('cal-event-pill-11')
    expect(allDayPill).toHaveClass('cal-pill-allday')
    expect(within(allDayPill).getByText('Team sync')).toBeInTheDocument()
    // All-day pill has no time prefix.
    expect(within(allDayPill).queryByTestId('cal-event-lock-11')).not.toBeInTheDocument()

    const timedPill = await screen.findByTestId('cal-event-pill-12')
    expect(timedPill).toHaveClass('cal-pill-timed')
    expect(within(timedPill).getByText('14:00')).toBeInTheDocument()
    expect(within(timedPill).getByText('Deploy window')).toBeInTheDocument()

    const privatePill = await screen.findByTestId('cal-event-pill-13')
    expect(privatePill).toHaveClass('cal-pill-timed')
    // Private events carry the lock icon.
    expect(screen.getByTestId('cal-event-lock-13')).toBeInTheDocument()
  })

  it('fetches the visible month range on load', async () => {
    const now = new Date()
    renderCalendar()
    await screen.findByTestId('cal-event-pill-11')

    expect(mockFetchEvents).toHaveBeenCalledWith(
      monthRange(now.getFullYear(), now.getMonth()).from,
      monthRange(now.getFullYear(), now.getMonth()).to,
    )
  })

  it('navigates to next/previous month and refetches the range', async () => {
    const user = userEvent.setup()
    const now = new Date()
    renderCalendar()
    await screen.findByTestId('cal-event-pill-11')
    vi.clearAllMocks()

    // Next month.
    await user.click(screen.getByTestId('cal-next-month'))
    await waitFor(() => {
      expect(mockFetchEvents).toHaveBeenCalledWith(
        monthRange(now.getFullYear(), (now.getMonth() + 1) % 12).from,
        monthRange(now.getFullYear(), (now.getMonth() + 1) % 12).to,
      )
    })

    // Back to the current month.
    await user.click(screen.getByTestId('cal-prev-month'))
    await waitFor(() => {
      expect(mockFetchEvents).toHaveBeenCalledWith(
        monthRange(now.getFullYear(), now.getMonth()).from,
        monthRange(now.getFullYear(), now.getMonth()).to,
      )
    })
  })

  it('shows the current month label and jumps back with Today', async () => {
    const user = userEvent.setup()
    const now = new Date()
    renderCalendar()
    await screen.findByTestId('cal-event-pill-11')

    const expectedLabel = new Intl.DateTimeFormat('en', { month: 'long', year: 'numeric' }).format(
      new Date(now.getFullYear(), now.getMonth(), 1),
    )
    expect(screen.getByTestId('cal-month-label').textContent).toBe(expectedLabel)

    // Leave the current month, then press Today.
    await user.click(screen.getByTestId('cal-next-month'))
    vi.clearAllMocks()
    await user.click(screen.getByTestId('cal-today-btn'))
    await waitFor(() => {
      expect(mockFetchEvents).toHaveBeenCalledWith(
        monthRange(now.getFullYear(), now.getMonth()).from,
        monthRange(now.getFullYear(), now.getMonth()).to,
      )
    })
  })

  it('opens the create form pre-filled with the clicked day', async () => {
    const user = userEvent.setup()
    renderCalendar()
    await screen.findByTestId('cal-event-pill-11')

    const todayISO = toISO(new Date())
    await user.click(screen.getByTestId(`cal-cell-${todayISO}`))

    expect(await screen.findByTestId('event-form-modal')).toBeInTheDocument()
    expect(screen.getByTestId('event-form-date')).toHaveValue(todayISO)
    // Create mode defaults: all-day checked, public visibility.
    expect(screen.getByTestId('event-form-allday')).toBeChecked()
    expect(screen.getByTestId('event-form-visibility-public')).toHaveAttribute('aria-pressed', 'true')
    // The type select lists the fetched types.
    const select = screen.getByTestId('event-form-type') as HTMLSelectElement
    expect(Array.from(select.options).map((o) => o.textContent)).toContain('Agent task')
  })

  it('opens the detail modal with event info and edit entry point when a pill is clicked', async () => {
    const user = userEvent.setup()
    renderCalendar()
    await screen.findByTestId('cal-event-pill-12')

    await user.click(screen.getByTestId('cal-event-pill-12'))

    const detail = await screen.findByTestId('event-detail')
    expect(within(detail).getByText('Deploy window')).toBeInTheDocument()
    expect(within(detail).getByTestId('cal-detail-time').textContent).toBe('14:00 – 15:00')
    expect(within(detail).getByTestId('cal-detail-created-by').textContent).toBe('alice')

    // Edit opens the form modal in edit mode.
    await user.click(screen.getByTestId('cal-detail-edit'))
    const form = await screen.findByTestId('event-form-modal')
    expect(within(form).getByTestId('event-form-title')).toHaveValue('Deploy window')
    expect(within(form).getByTestId('event-form-allday')).not.toBeChecked()
  })

  it('shows an empty-month hint when the visible month has no events', async () => {
    const user = userEvent.setup()
    renderCalendar()
    await screen.findByTestId('cal-event-pill-11')

    // Move to a month without events (mock returns [] for non-current months).
    await user.click(screen.getByTestId('cal-next-month'))
    expect(await screen.findByTestId('cal-no-events')).toBeInTheDocument()
  })
})

// ── WIKI4AI-110: week view ───────────────────────────────────────────────────

describe('CalendarPage week view (WIKI4AI-110)', () => {
  const HOUR_HEIGHT_PX = 48 // must match CalendarPage.tsx

  function toISO(d: Date): string {
    const y = d.getFullYear()
    const m = String(d.getMonth() + 1).padStart(2, '0')
    const day = String(d.getDate()).padStart(2, '0')
    return `${y}-${m}-${day}`
  }

  /** Monday–Sunday ISO range of the week containing `anchor`. */
  function weekRange(anchor: Date): { from: string; to: string } {
    const start = startOfWeek(anchor)
    const end = new Date(start.getFullYear(), start.getMonth(), start.getDate() + 6)
    return { from: toISO(start), to: toISO(end) }
  }

  function makeEvent(overrides: Partial<calendarApi.CalendarEvent> & { id: number }): calendarApi.CalendarEvent {
    return {
      title: 'Event',
      description: null,
      eventTypeId: 1,
      eventType: 'Agent task',
      eventColor: '#4f8cff',
      eventDate: toISO(new Date()),
      startTime: null,
      endTime: null,
      visibility: 'public',
      createdBy: 'alice',
      createdAt: '2026-09-26T00:00:00Z',
      updatedAt: '2026-09-26T00:00:00Z',
      ...overrides,
    }
  }

  const TYPES: calendarApi.CalendarEventType[] = [{ id: 1, name: 'Agent task', color: '#4f8cff' }]

  function renderCalendar(entry = '/calendar') {
    return render(
      <MemoryRouter initialEntries={[entry]}>
        <CalendarPage />
      </MemoryRouter>,
    )
  }

  /** Render and switch to the week view (waits for the grid). */
  async function enterWeekView(user: ReturnType<typeof userEvent.setup>) {
    renderCalendar()
    await screen.findByTestId('cal-grid')
    await user.click(screen.getByTestId('cal-view-toggle-week'))
    return screen.findByTestId('cal-week-grid')
  }

  beforeEach(() => {
    vi.clearAllMocks()
    mockFetchEventTypes.mockResolvedValue(TYPES)
    // Week tests drive their own fetch mocks; default to an empty week.
    mockFetchEvents.mockResolvedValue([])
  })

  it('toggles to the week view and renders the hourly grid with day columns', async () => {
    const user = userEvent.setup()
    await enterWeekView(user)

    // Seven day columns (Mon–Sun of the current week).
    const range = weekRange(new Date())
    for (let i = 0; i < 7; i++) {
      const d = new Date(
        Number(range.from.slice(0, 4)),
        Number(range.from.slice(5, 7)) - 1,
        Number(range.from.slice(8, 10)),
      )
      d.setDate(d.getDate() + i)
      expect(screen.getByTestId(`cal-week-day-col-${toISO(d)}`)).toBeInTheDocument()
    }
    // Hour axis labels are present (00:00 … 23:00).
    expect(screen.getByText('08:00')).toBeInTheDocument()
    expect(screen.getByText('23:00')).toBeInTheDocument()
    // The week toggle is pressed; the month grid is gone.
    expect(screen.getByTestId('cal-view-toggle-week')).toHaveAttribute('aria-pressed', 'true')
    expect(screen.queryByTestId('cal-grid')).not.toBeInTheDocument()
  })

  it('starts in the week view when the URL carries ?view=week', async () => {
    renderCalendar('/calendar?view=week')
    await screen.findByTestId('cal-week-grid')
    expect(screen.getByTestId('cal-view-toggle-week')).toHaveAttribute('aria-pressed', 'true')
  })

  it('scrolls the time grid to the 08:00 line on entry (first label unclipped, WIKI4AI-115)', async () => {
    const user = userEvent.setup()
    await enterWeekView(user)

    // The scroll container starts with the INITIAL_SCROLL_HOUR (08:00) hour line
    // near the top of the visible area — combined with the .cal-week-grid
    // padding-top clearance this keeps the first on-line label fully readable.
    const scroller = document.querySelector('.cal-week-time-scroll') as HTMLElement
    expect(scroller).not.toBeNull()
    expect(scroller.scrollTop).toBe(8 * HOUR_HEIGHT_PX)
  })

  it('shows the week range in the header (e.g. "27. Sep – 3. Oct" style)', async () => {
    const user = userEvent.setup()
    await enterWeekView(user)

    const label = screen.getByTestId('cal-month-label').textContent ?? ''
    // The label contains both the Monday and Sunday day numbers of the week.
    const range = weekRange(new Date())
    expect(label).toContain(String(Number(range.from.slice(8, 10))))
    expect(label).toContain(String(Number(range.to.slice(8, 10))))
  })

  it('clicking an hour slot opens the create form pre-filled with date and time', async () => {
    const user = userEvent.setup()
    await enterWeekView(user)

    const todayISO = toISO(new Date())
    await user.click(screen.getByTestId(`cal-week-hour-cell-${todayISO}-10`))

    const form = await screen.findByTestId('event-form-modal')
    // Timed by default (all-day unchecked) with the slot's hour pre-filled.
    expect(within(form).getByTestId('event-form-allday')).not.toBeChecked()
    expect(within(form).getByTestId('event-form-date')).toHaveValue(todayISO)
    expect(within(form).getByTestId('event-form-start-time')).toHaveValue('10:00')
    expect(within(form).getByTestId('event-form-end-time')).toHaveValue('11:00')
  })

  it('renders a timed event as a block in the correct day column at its time position', async () => {
    const user = userEvent.setup()
    const todayISO = toISO(new Date())
    mockFetchEvents.mockResolvedValue([
      makeEvent({ id: 21, title: 'Standup', eventDate: todayISO, startTime: '10:00:00', endTime: '11:00:00' }),
    ])
    await enterWeekView(user)

    const block = await screen.findByTestId('cal-event-block-21')
    // Inside the column of its day.
    expect(screen.getByTestId(`cal-week-day-col-${todayISO}`)).toContainElement(block)
    // 10:00 → top = 10 × hour height; one hour long → height = hour height.
    expect(block.style.top).toBe(`${10 * HOUR_HEIGHT_PX}px`)
    expect(block.style.height).toBe(`${HOUR_HEIGHT_PX}px`)
    // Full width (no overlap) with the normalized time label.
    expect(within(block).getByText('10:00–11:00')).toBeInTheDocument()
  })

  it('places overlapping events side by side in the same day column', async () => {
    const user = userEvent.setup()
    const todayISO = toISO(new Date())
    mockFetchEvents.mockResolvedValue([
      makeEvent({ id: 31, title: 'Design review', eventDate: todayISO, startTime: '10:00', endTime: '12:00' }),
      makeEvent({ id: 32, title: 'Pairing', eventDate: todayISO, startTime: '11:00', endTime: '13:00' }),
    ])
    await enterWeekView(user)

    const a = await screen.findByTestId('cal-event-block-31')
    const b = screen.getByTestId('cal-event-block-32')
    // Both in the same day column, half width each, different horizontal slots.
    expect(screen.getByTestId(`cal-week-day-col-${todayISO}`)).toContainElement(a)
    expect(screen.getByTestId(`cal-week-day-col-${todayISO}`)).toContainElement(b)
    expect(a.style.width).toBe('calc(50% - 4px)')
    expect(b.style.width).toBe('calc(50% - 4px)')
    expect(a.style.left).not.toBe(b.style.left)
    // Vertical positions follow their start times (10:00 → 480px, 11:00 → 528px).
    expect(a.style.top).toBe(`${10 * HOUR_HEIGHT_PX}px`)
    expect(b.style.top).toBe(`${11 * HOUR_HEIGHT_PX}px`)
  })

  it('shows all-day events in the top strip, not in the hourly grid', async () => {
    const user = userEvent.setup()
    const todayISO = toISO(new Date())
    mockFetchEvents.mockResolvedValue([
      makeEvent({ id: 41, title: 'Conference', eventDate: todayISO }),
    ])
    await enterWeekView(user)

    const pill = await screen.findByTestId('cal-week-allday-pill-41')
    expect(screen.getByTestId('cal-week-allday-strip')).toContainElement(pill)
    // No timed block for the all-day event.
    expect(screen.queryByTestId('cal-event-block-41')).not.toBeInTheDocument()
  })

  it('navigates by week (prev/next/today) and refetches the visible range', async () => {
    const user = userEvent.setup()
    await enterWeekView(user)
    vi.clearAllMocks()

    const current = weekRange(new Date())
    const nextStart = new Date(
      Number(current.from.slice(0, 4)),
      Number(current.from.slice(5, 7)) - 1,
      Number(current.from.slice(8, 10)) + 7,
    )
    await user.click(screen.getByTestId('cal-next-month'))
    await waitFor(() => {
      expect(mockFetchEvents).toHaveBeenCalledWith(weekRange(nextStart).from, weekRange(nextStart).to)
    })

    // Today returns to the current week.
    vi.clearAllMocks()
    await user.click(screen.getByTestId('cal-today-btn'))
    await waitFor(() => {
      expect(mockFetchEvents).toHaveBeenCalledWith(current.from, current.to)
    })
  })

  it('opens the detail modal (and edit form) when a week-view event block is clicked', async () => {
    const user = userEvent.setup()
    const todayISO = toISO(new Date())
    mockFetchEvents.mockResolvedValue([
      makeEvent({ id: 51, title: 'Deploy window', eventDate: todayISO, startTime: '10:00:00', endTime: '11:00:00' }),
    ])
    await enterWeekView(user)

    await user.click(await screen.findByTestId('cal-event-block-51'))
    const detail = await screen.findByTestId('event-detail')
    expect(within(detail).getByTestId('cal-detail-time').textContent).toBe('10:00 – 11:00')

    // Edit opens the form pre-filled with the event's times.
    await user.click(screen.getByTestId('cal-detail-edit'))
    const form = await screen.findByTestId('event-form-modal')
    expect(within(form).getByTestId('event-form-allday')).not.toBeChecked()
    expect(within(form).getByTestId('event-form-start-time')).toHaveValue('10:00')
    expect(within(form).getByTestId('event-form-end-time')).toHaveValue('11:00')
  })

  it('switching back to the month view restores the month grid', async () => {
    const user = userEvent.setup()
    await enterWeekView(user)

    await user.click(screen.getByTestId('cal-view-toggle-month'))
    expect(await screen.findByTestId('cal-grid')).toBeInTheDocument()
    expect(screen.queryByTestId('cal-week-grid')).not.toBeInTheDocument()
    expect(screen.getByTestId('cal-view-toggle-month')).toHaveAttribute('aria-pressed', 'true')
  })
})
