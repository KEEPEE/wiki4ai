/**
 * WIKI4AI-100: shared Public/Private visibility segmented control.
 *
 * Same visual pattern as the calendar event form (WIKI4AI-97): Globe icon for
 * public, Lock icon for private. Used by the create-project modal, project
 * settings, the document create form and the document viewer header so the
 * toggle stays consistent across the app.
 */

import React from 'react';
import { useTranslation } from 'react-i18next';
import { GlobeIcon, LockIcon } from './icons';
import type { Visibility } from '../types/project';
import './VisibilityToggle.css';

export interface VisibilityToggleProps {
  value: Visibility;
  onChange: (value: Visibility) => void;
  /** Accessible name of the group; defaults to t('common.visibility'). */
  label?: string;
  /** Prefix for data-testid attributes, e.g. 'create-project' → 'create-project-visibility-public'. */
  testIdPrefix: string;
  disabled?: boolean;
}

const VisibilityToggle: React.FC<VisibilityToggleProps> = ({ value, onChange, label, testIdPrefix, disabled }) => {
  const { t } = useTranslation();

  return (
    <div className="visibility-toggle" role="group" aria-label={label ?? t('common.visibility')}>
      <button
        type="button"
        className={`visibility-option${value === 'public' ? ' visibility-option-active' : ''}`}
        aria-pressed={value === 'public'}
        onClick={() => onChange('public')}
        disabled={disabled}
        data-testid={`${testIdPrefix}-visibility-public`}
      >
        <GlobeIcon size={14} />
        {t('common.public')}
      </button>
      <button
        type="button"
        className={`visibility-option${value === 'private' ? ' visibility-option-active' : ''}`}
        aria-pressed={value === 'private'}
        onClick={() => onChange('private')}
        disabled={disabled}
        data-testid={`${testIdPrefix}-visibility-private`}
      >
        <LockIcon size={14} />
        {t('common.private')}
      </button>
    </div>
  );
};

export default VisibilityToggle;
