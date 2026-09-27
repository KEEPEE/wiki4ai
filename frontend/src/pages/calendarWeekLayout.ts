/**
 * WIKI4AI-110: pure layout helpers for the calendar week view (hourly grid).
 *
 * Kept free of React so the overlap-column algorithm is unit-testable in
 * isolation. All date math is local-time based (no UTC drift), matching the
 * rest of the calendar page.
 */

export const HOURS_PER_DAY = 24;
export const MINUTES_PER_HOUR = 60;
export const DAY_MINUTES = HOURS_PER_DAY * MINUTES_PER_HOUR; // 1440

/** A timed event shorter than this is still given a visible block height. */
export const MIN_EVENT_MINUTES = 30;

/** Monday of the week containing `d` (local midnight). */
export function startOfWeek(d: Date): Date {
  const out = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  const offset = (out.getDay() + 6) % 7; // getDay(): Sun=0..Sat=6 → Mon=0..Sun=6
  out.setDate(out.getDate() - offset);
  return out;
}

/** The seven days (Mon–Sun) of the week containing `d`, at local midnight. */
export function weekDays(d: Date): Date[] {
  const start = startOfWeek(d);
  return Array.from({ length: 7 }, (_, i) => new Date(start.getFullYear(), start.getMonth(), start.getDate() + i));
}

/** Parse "HH:mm" or backend "HH:mm:ss" into minutes from midnight; null when absent/unparseable. */
export function timeToMinutes(t: string | null | undefined): number | null {
  if (!t) return null;
  const m = t.match(/^(\d{1,2}):(\d{2})/);
  if (!m) return null;
  const h = Number(m[1]);
  const min = Number(m[2]);
  if (h > 23 || min > 59) return null;
  return h * MINUTES_PER_HOUR + min;
}

export interface TimedSpan {
  startMin: number;
  /** Exclusive end, clamped to [startMin + MIN_EVENT_MINUTES, DAY_MINUTES]. */
  endMin: number;
}

/**
 * Time span of an event for grid placement. All-day events (no startTime)
 * return null — they live in the top strip, not the hourly grid. Missing or
 * degenerate end times default to a one-hour block (clamped to midnight).
 */
export function eventSpan(startTime: string | null | undefined, endTime: string | null | undefined): TimedSpan | null {
  const start = timeToMinutes(startTime);
  if (start === null) return null;
  let end = timeToMinutes(endTime);
  if (end === null || end <= start) end = start + MIN_EVENT_MINUTES * 2; // default: one hour
  return { startMin: start, endMin: Math.min(Math.max(end, start + MIN_EVENT_MINUTES), DAY_MINUTES) };
}

export interface PlacedEvent {
  /** Index of the event in the input array (stable reference back to it). */
  index: number;
  span: TimedSpan;
  /** 0-based column within the overlap cluster. */
  col: number;
  /** Number of columns in the overlap cluster (width = 100 / cols %). */
  cols: number;
}

/**
 * Assign side-by-side columns to overlapping timed events of one day.
 *
 * Events are sorted by start time and grouped into maximal clusters of
 * transitively overlapping intervals. Inside a cluster, each event takes the
 * first column whose previous event ends before it starts (meeting-room
 * style greedy assignment); `cols` is the number of columns in the cluster,
 * so a two-way overlap yields two half-width columns and non-overlapping
 * events keep full width (cols = 1).
 */
export function layoutDayEvents(spans: Array<TimedSpan | null>): PlacedEvent[] {
  const timed = spans
    .map((span, index) => (span ? { span, index } : null))
    .filter((x): x is { span: TimedSpan; index: number } => x !== null);

  const order = [...timed].sort(
    (a, b) => a.span.startMin - b.span.startMin || b.span.endMin - a.span.endMin,
  );

  // Cluster into groups of transitively overlapping intervals.
  const clusters: Array<Array<{ span: TimedSpan; index: number }>> = [];
  let current: Array<{ span: TimedSpan; index: number }> = [];
  let clusterEnd = -1;
  for (const item of order) {
    if (current.length > 0 && item.span.startMin >= clusterEnd) {
      clusters.push(current);
      current = [];
      clusterEnd = -1;
    }
    current.push(item);
    clusterEnd = Math.max(clusterEnd, item.span.endMin);
  }
  if (current.length > 0) clusters.push(current);

  const placed: PlacedEvent[] = [];
  for (const cluster of clusters) {
    const colEnds: number[] = []; // last end minute per column
    const colOf = new Map<number, number>();
    for (const item of cluster) {
      let col = colEnds.findIndex((end) => end <= item.span.startMin);
      if (col === -1) {
        col = colEnds.length;
        colEnds.push(item.span.endMin);
      } else {
        colEnds[col] = item.span.endMin;
      }
      colOf.set(item.index, col);
    }
    for (const item of cluster) {
      placed.push({ index: item.index, span: item.span, col: colOf.get(item.index) as number, cols: colEnds.length });
    }
  }
  return placed;
}
