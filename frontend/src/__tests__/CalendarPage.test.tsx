/**
 * WIKI4AI-97: Calendar page component tests.
 *
 * Covers: month grid rendering with event pills (all-day vs timed, private
 * lock icon), prev/next/today navigation (refetches the visible range),
 * day click → create form pre-filled with that date, and event pill click →
 * detail modal with edit entry point.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import CalendarPage from '../pages/CalendarPage'
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
          makeEvent({
            id: 12,
            title: 'Deploy window',
            eventDate: toISO(new Date()),
            startTime: '14:00',
            endTime: '15:00',
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
