/**
 * WIKI4AI-187: the persisted sort choice of a list.
 *
 * The choice is a *user preference*, not application state: it is sent to the
 * backend as `?sort=` on every load and kept in localStorage, so a reload (or a
 * second tab) opens the list in the same order. It is deliberately not synced
 * across tabs — two tabs showing different sorts is a feature when comparing
 * two orderings, and a storage listener would silently reorder a list the user
 * is about to drag.
 */

import { useCallback, useState } from 'react';
import {
  readStoredSort,
  writeStoredSort,
  type ListSortKey,
} from '../utils/listSort';

export function useSortPreference(storageKey: string, fallback: ListSortKey) {
  // Read once during the first render — the initial request must already carry
  // the stored sort, otherwise the list would load in the default order and
  // re-render a moment later.
  const [sort, setSort] = useState<ListSortKey>(() => readStoredSort(storageKey, fallback));

  const changeSort = useCallback(
    (next: ListSortKey) => {
      setSort(next);
      writeStoredSort(storageKey, next);
    },
    [storageKey],
  );

  return { sort, setSort: changeSort };
}
