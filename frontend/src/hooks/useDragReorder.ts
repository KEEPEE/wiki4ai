/**
 * WIKI4AI-187: HTML5 drag & drop over a server-ordered list, without a library.
 *
 * The list on screen is the server's order (the page asked for `?sort=manual`),
 * so a drop only has to say "put me next to that neighbour" — see
 * `utils/dragOrder` for how the drop index becomes a `beforeSlug`/`afterSlug`
 * body. Everything else here is plumbing: which row is being dragged, which row
 * is under the cursor (for the drop line), the optimistic order while the call
 * is in flight, and the rollback when it fails.
 *
 * ## Why `enabled` exists
 * A drop writes the MANUAL order of the whole list. While the user is looking at
 * `?sort=name_asc` or at a filtered subset, that order is not what they see, so
 * moving a row would silently reorder a list they are not looking at. The caller
 * therefore passes `enabled: false` for any other sort or an active filter, and
 * the hook refuses to start a drag — the page explains why next to the handle.
 *
 * ## Optimistic update and rollback
 * The new order is shown immediately (`moveWithinGroup`) and kept until the
 * server round-trip is finished: `reorder` resolves, then `onReordered` (a cache
 * invalidation, awaited) brings back the authoritative order, and only then is
 * the optimistic list dropped. That ordering is what makes the list settle
 * without flashing back to the old order for one frame. On an error (403 =
 * missing MANAGE/UPDATE, 404 = anchor vanished, network) the optimistic list is
 * discarded, which restores exactly the server list the drag started from.
 */

import { useCallback, useMemo, useState } from 'react';
import {
  computeReorderBody,
  moveWithinGroup,
  type Draggable,
  type ReorderBody,
} from '../utils/dragOrder';

export interface UseDragReorderOptions<T extends Draggable> {
  /** The list as rendered (server order + any pin overrides). */
  list: T[];
  /** False while the view is not the manual, unfiltered order. */
  enabled: boolean;
  /** Send the reorder request for `item` (slug + body). */
  reorder: (item: T, body: ReorderBody) => Promise<unknown>;
  /** Awaited after success — normally a query invalidation. */
  onReordered?: () => Promise<unknown> | void;
  /** Called with the failure so the page can show a Toast. */
  onError: (err: unknown, item: T) => void;
}

export function useDragReorder<T extends Draggable>({
  list,
  enabled,
  reorder,
  onReordered,
  onError,
}: UseDragReorderOptions<T>) {
  // Index of the row being dragged, and the row currently under the cursor.
  const [dragIndex, setDragIndex] = useState<number | null>(null);
  const [overIndex, setOverIndex] = useState<number | null>(null);
  // The order shown while the request is in flight; null = render `list`.
  const [optimistic, setOptimistic] = useState<T[] | null>(null);
  const [isReordering, setIsReordering] = useState(false);

  const displayList = optimistic ?? list;

  // A drag may not start at all when the view is not the manual order.
  const startDrag = useCallback(
    (index: number, event?: { dataTransfer?: DataTransfer | null }) => {
      if (!enabled) return false;
      setDragIndex(index);
      if (event?.dataTransfer) {
        // Firefox refuses to start a drag with an empty data transfer.
        const slug = list[index]?.slug ?? '';
        try {
          event.dataTransfer.setData('text/plain', slug);
          event.dataTransfer.effectAllowed = 'move';
        } catch {
          /* jsdom / older browsers — the drag still works without it */
        }
      }
      return true;
    },
    [enabled, list],
  );

  const dragOver = useCallback(
    (index: number, event?: { preventDefault?: () => void }) => {
      if (!enabled || dragIndex === null) return;
      event?.preventDefault?.(); // required, or the browser fires no drop
      if (index !== overIndex) setOverIndex(index);
    },
    [enabled, dragIndex, overIndex],
  );

  const reset = useCallback(() => {
    setDragIndex(null);
    setOverIndex(null);
  }, []);

  /** Escape / dropping outside a row: forget the drag, nothing was sent. */
  const cancelDrag = useCallback(() => {
    setDragIndex(null);
    setOverIndex(null);
  }, []);

  const drop = useCallback(
    async (index: number): Promise<void> => {
      if (!enabled || dragIndex === null) {
        reset();
        return;
      }
      const from = dragIndex;
      const item = list[from];
      const body = computeReorderBody(list, from, index);
      reset();
      // Same row, a drop inside the same slot, or an item with no slug: the
      // order did not change, so no request is made at all.
      if (!item || !body) return;

      setOptimistic(moveWithinGroup(list, from, index));
      setIsReordering(true);
      try {
        await reorder(item, body);
        // Let the server order land in the cache before dropping our guess,
        // otherwise the list would flash the old order for one frame.
        await onReordered?.();
        setOptimistic(null);
      } catch (err) {
        // Rollback: the optimistic order is discarded and the untouched server
        // list is rendered again.
        setOptimistic(null);
        onError(err, item);
      } finally {
        setIsReordering(false);
      }
    },
    [dragIndex, enabled, list, onReordered, onError, reorder, reset],
  );

  /**
   * Which edge the drop line is drawn on: the dragged item takes the hovered
   * row's slot, so moving up means "above it" and moving down "below it".
   */
  const dropEdge = useMemo<'above' | 'below' | null>(() => {
    if (dragIndex === null || overIndex === null) return null;
    return overIndex < dragIndex ? 'above' : 'below';
  }, [dragIndex, overIndex]);

  return {
    displayList,
    dragIndex,
    overIndex,
    dropEdge,
    isReordering,
    startDrag,
    dragOver,
    drop,
    cancelDrag,
    /** True while this very row is the one being dragged (for styling). */
    isDragging: (index: number) => dragIndex === index,
    isOver: (index: number) => overIndex === index && dragIndex !== null && dragIndex !== index,
  };
}
