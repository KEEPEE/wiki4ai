/**
 * WIKI4AI-187: the sort menu shown above the project and the document list.
 *
 * A native `<select>` rather than a custom dropdown: it is keyboard accessible,
 * themeable with the glass tokens, and needs no outside-click handling. The
 * value is not applied here — the caller sends it to the backend as `?sort=`
 * (the server owns the ordering) and persists it in localStorage.
 */

import React from 'react';
import { useTranslation } from 'react-i18next';
import { SORT_OPTIONS, SORT_LABEL_KEY, type ListSortKey } from '../utils/listSort';
import './ListToolbar.css';

export interface SortSelectProps {
  value: ListSortKey;
  onChange: (next: ListSortKey) => void;
  /** i18n key of the label ("Zoradiť projekty" / "Zoradiť dokumenty"). */
  labelKey: string;
  /** Stable hook for tests/E2E; defaults to `sort-select`. */
  testId?: string;
  disabled?: boolean;
}

const SortSelect: React.FC<SortSelectProps> = ({ value, onChange, labelKey, testId = 'sort-select', disabled }) => {
  const { t } = useTranslation();

  return (
    <label className="list-toolbar-field" data-testid={`${testId}-label`}>
      <span className="list-toolbar-label">{t(labelKey)}</span>
      <select
        className="list-toolbar-select"
        data-testid={testId}
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value as ListSortKey)}
      >
        {SORT_OPTIONS.map((option) => (
          <option key={option} value={option}>
            {t(SORT_LABEL_KEY[option])}
          </option>
        ))}
      </select>
    </label>
  );
};

export default SortSelect;
