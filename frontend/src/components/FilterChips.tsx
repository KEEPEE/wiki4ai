/**
 * WIKI4AI-187: chips for the filters that are currently active.
 *
 * A server-side filter changes what the list means (and switches drag & drop
 * off), so it must never be invisible: every active filter gets a chip with its
 * own × and there is one "clear all" that returns the list to the plain manual
 * order the user can drag in again.
 */

import React from 'react';
import { useTranslation } from 'react-i18next';
import './ListToolbar.css';

export interface ActiveFilterChip {
  /** Stable id used for the testid, e.g. `q` or `visibility`. */
  id: string;
  /** Human-readable description of the filter value. */
  label: string;
  onClear: () => void;
}

export interface FilterChipsProps {
  filters: ActiveFilterChip[];
  onClearAll: () => void;
}

const FilterChips: React.FC<FilterChipsProps> = ({ filters, onClearAll }) => {
  const { t } = useTranslation();
  if (filters.length === 0) return null;

  return (
    <div className="filter-chips" data-testid="filter-chips">
      <span className="filter-chips-label">{t('listSort.activeFilters')}</span>
      {filters.map((chip) => (
        <span key={chip.id} className="filter-chip" data-testid={`filter-chip-${chip.id}`}>
          {chip.label}
          <button
            type="button"
            className="filter-chip-clear"
            onClick={chip.onClear}
            aria-label={t('listSort.clearFilterAria', { filter: chip.label })}
            data-testid={`filter-chip-clear-${chip.id}`}
          >
            &times;
          </button>
        </span>
      ))}
      <button
        type="button"
        className="filter-clear-all"
        onClick={onClearAll}
        data-testid="filter-clear-all"
      >
        {t('listSort.clearAll')}
      </button>
    </div>
  );
};

export default FilterChips;
