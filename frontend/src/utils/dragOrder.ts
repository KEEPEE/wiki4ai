/**
 * WIKI4AI-187: the pure part of drag & drop — turning "item X was dropped onto
 * item Y" into the body of a reorder call, and into the optimistic local move.
 *
 * The backend (WIKI4AI-184) accepts exactly one of `beforeSlug` / `afterSlug` /
 * `position` and resolves it against the CURRENT manual order of the whole list:
 * `afterSlug = X` means "immediately after X", `beforeSlug = X` "immediately
 * before X". A drag knows its neighbours, so the UI sends a slug anchor rather
 * than an index — an index would be wrong the moment somebody else reordered
 * between the drag start and the drop.
 *
 * ## Dropping ON a row means taking its slot
 * The row under the cursor is the slot the user aimed at, so the moved item ends
 * up at that row's index in the final list. In the list *without* the moved item
 * that is the same index, whether the item travelled up or down — which is why
 * `insertAt` below is a plain clamp of the drop index, with no off-by-one.
 *
 * ## Pinned items move inside their own group only
 * The manual order the endpoint renumbers is itself pinned-first
 * (`ListSort.manualProjectOrder()` = `pinned DESC, sort_order`), and the UI shows
 * the same thing. Dropping an unpinned card onto the pinned block would be
 * accepted by the server and rendered straight back at the same place — a silent
 * no-op that looks like a bug. So a drop index outside the dragged item's group
 * is clamped to that group's bounds, which makes the limit visible instead of
 * surprising.
 */

import type { Pinable } from './pinSort';

/**
 * Body of `POST .../reorder` — exactly one key, never both.
 *
 * The drag&drop UI always sends an anchor (`beforeSlug`/`afterSlug`) because it
 * knows the neighbour under the cursor; `position` is the endpoint's third
 * spelling — an absolute index, clamped to the list — kept here so the API
 * layer can express every request the backend accepts.
 */
export interface ReorderBody {
  beforeSlug?: string;
  afterSlug?: string;
  position?: number;
}

/** Items the reorder logic needs: a slug to anchor on and a pin flag to group by. */
export type Draggable = Pinable & { slug?: string };

/**
 * First/last index of the run of items sharing the `pinned` flag. The list is
 * pinned-first, so each group is one contiguous block; an absent group is `[0,-1]`.
 */
export function groupBounds<T extends Draggable>(list: T[], pinned: boolean): [number, number] {
  let start = -1;
  let end = -1;
  for (let i = 0; i < list.length; i++) {
    if (!!list[i].pinned === pinned) {
      if (start === -1) start = i;
      end = i;
    }
  }
  return start === -1 ? [0, -1] : [start, end];
}

/** The drop index clamped into the block the dragged item may move within. */
const clampToGroup = <T extends Draggable>(list: T[], dragIndex: number, dropIndex: number): number => {
  const [start, end] = groupBounds(list, !!list[dragIndex]?.pinned);
  return Math.max(start, Math.min(dropIndex, end));
};

/**
 * The reorder body for dragging `dragIndex` onto `dropIndex` (indices of the list
 * as rendered; `dropIndex` = the row currently under the cursor).
 *
 * @returns `null` when nothing changes (same row, or an item with no slug to
 *          anchor on) — the caller then skips the API call entirely.
 */
export function computeReorderBody<T extends Draggable>(
  list: T[],
  dragIndex: number,
  dropIndex: number,
): ReorderBody | null {
  if (dragIndex < 0 || dragIndex >= list.length) return null;
  const moved = list[dragIndex];
  if (!moved.slug) return null;

  const target = clampToGroup(list, dragIndex, dropIndex);
  if (target === dragIndex) return null;

  const [start] = groupBounds(list, !!moved.pinned);
  // The group as the server sees it once the moved item is lifted out of it.
  const groupWithout: T[] = [];
  for (let i = start; i < list.length; i++) {
    if (!!list[i].pinned !== !!moved.pinned) continue;
    if (i !== dragIndex) groupWithout.push(list[i]);
  }

  // Index inside that group-without-item; the group is contiguous, so subtracting
  // its start turns a list index into a group index.
  const insertAt = target - start;
  const anchor = insertAt === 0 ? groupWithout[0] : groupWithout[insertAt - 1];
  if (!anchor?.slug) return null;

  return insertAt === 0 ? { beforeSlug: anchor.slug } : { afterSlug: anchor.slug };
}

/**
 * The optimistic view of the same move: the list as it looks while the reorder
 * call is in flight. Mirrors `computeReorderBody` exactly (same clamp, same
 * target index) so the UI and the server cannot disagree on success.
 */
export function moveWithinGroup<T extends Draggable>(
  list: T[],
  dragIndex: number,
  dropIndex: number,
): T[] {
  if (dragIndex < 0 || dragIndex >= list.length) return list;
  const target = clampToGroup(list, dragIndex, dropIndex);
  if (target === dragIndex) return list;

  const next = list.slice();
  const [moved] = next.splice(dragIndex, 1);
  next.splice(target, 0, moved);
  return next;
}
