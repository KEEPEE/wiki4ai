/**
 * WIKI4AI-186: optimistic pin/unpin for a list of projects or documents.
 *
 * The pin endpoints (`POST .../pin`, `POST .../unpin`) return the entity with
 * its new `pinned`/`sortOrder` and — unlike an edit — do NOT bump the
 * document `version` or move `updatedAt`. So the response of the pin call is
 * the authoritative state and is applied straight to the local view; no refetch
 * is needed to make the order settle, and a concurrent editor is not pushed
 * into a 409 by our pin.
 *
 * Behaviour:
 * - the click flips `pinned` immediately (optimistic) and the list is re-sorted
 *   pinned-first, mirroring the server order (see `utils/pinSort`);
 * - the response replaces the optimistic values with the real ones;
 * - on error (403 = missing MANAGE/UPDATE permission, 404, network) the item is
 *   rolled back to exactly the state it had before the click and the error is
 *   re-thrown so the caller can show a Toast — nothing else changes.
 *
 * The overrides are kept per slug instead of copying the whole list, so the
 * server list stays the single source of truth: a later refetch simply provides
 * the base rows again and the (already matching) override becomes a no-op.
 */

import { useCallback, useMemo, useState } from 'react';
import { pinnedFirst, type Pinable } from '../utils/pinSort';

/** Minimal pin API, satisfied by `projectApi` and `documentApi` wrappers. */
export interface PinApi<T> {
  pin: (slug: string) => Promise<T>;
  unpin: (slug: string) => Promise<T>;
}

/** Fields a pin call may change. `sortOrder` is opaque — never displayed. */
type PinPatch = { pinned?: boolean; sortOrder?: number };

/**
 * True when the failure is a permission problem (403). `apiClient` throws plain
 * Errors whose message starts with the HTTP status (`"403: Chýba MANAGE..."`).
 */
export function isForbiddenError(err: unknown): boolean {
  return err instanceof Error && /^403\b/.test(err.message);
}

export function usePinToggle<T extends Pinable & { slug?: string }>(
  items: T[],
  api: PinApi<T>,
  options?: {
    /**
     * Called after a pin/unpin succeeded. The optimistic order is only an
     * approximation of the server's (the list is ordered by its own sort key —
     * `created_desc` / `updated_desc` — with pinned floated to the top), so the
     * caller should refetch and let the server order win. Without a refetch the
     * UI is still consistent, just possibly one position off inside the pinned
     * group until the next load.
     */
    onSettled?: () => void;
  },
) {
  // slug -> last known pin state (optimistic, then replaced by the response).
  const [overrides, setOverrides] = useState<Record<string, PinPatch>>({});
  // Slugs with a pin request in flight, so the button can be disabled/spinner.
  const [pendingSlugs, setPendingSlugs] = useState<string[]>([]);
  const onSettled = options?.onSettled;

  const list = useMemo(
    () => pinnedFirst(
      items.map((item) => (item.slug && overrides[item.slug] ? { ...item, ...overrides[item.slug] } : item)),
    ),
    [items, overrides],
  );

  /** Flip the pin of one item; resolves with the server entity, rejects on error. */
  const togglePin = useCallback(
    async (item: T): Promise<T> => {
      const slug = item.slug;
      if (!slug) throw new Error('pin: item has no slug');

      const snapshot = overrides[slug];
      const nextPinned = !item.pinned;

      // 1. optimistic flip — the card/row jumps to the top (or back) at once.
      setOverrides((prev) => ({ ...prev, [slug]: { ...prev[slug], pinned: nextPinned } }));
      setPendingSlugs((prev) => [...prev, slug]);

      try {
        // 2. server truth replaces the guess (pinned + opaque sortOrder).
        const updated = nextPinned ? await api.pin(slug) : await api.unpin(slug);
        setOverrides((prev) => ({
          ...prev,
          [slug]: { pinned: updated.pinned, sortOrder: updated.sortOrder },
        }));
        // Let the caller refetch so the final order is exactly the server's.
        onSettled?.();
        return updated;
      } catch (err) {
        // 3. rollback to the exact pre-click state.
        setOverrides((prev) => {
          const next = { ...prev };
          if (snapshot) next[slug] = snapshot;
          else delete next[slug];
          return next;
        });
        throw err;
      } finally {
        setPendingSlugs((prev) => prev.filter((s) => s !== slug));
      }
    },
    [api, overrides, onSettled],
  );

  const isPending = useCallback(
    (slug?: string) => (slug ? pendingSlugs.includes(slug) : false),
    [pendingSlugs],
  );

  /**
   * Apply the known pin state to any other list of the same entities (e.g.
   * search results, which keep their relevance order and are therefore not
   * re-sorted pinned-first) so a pin never shows a stale icon there.
   */
  const withOverrides = useCallback(
    <U extends Pinable & { slug?: string }>(items: U[]): U[] =>
      items.map((item) => (item.slug && overrides[item.slug] ? { ...item, ...overrides[item.slug] } : item)),
    [overrides],
  );

  return { list, togglePin, isPending, withOverrides };
}
