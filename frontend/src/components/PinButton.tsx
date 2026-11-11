/**
 * WIKI4AI-186: shared pin/unpin toggle for projects and documents.
 *
 * One icon button, used on the project cards (Dashboard), the document rows
 * (ProjectDetail) and both detail headers (ProjectDetail / DocumentViewer) so
 * the control behaves identically everywhere:
 * - outline pin = "click to pin", solid filled pin + accent = pinned (the state
 *   is carried by shape as well as colour, so it survives a colour-blind or
 *   greyscale view);
 * - `aria-pressed` + a descriptive `aria-label`/`title` for screen readers and
 *   hover, and a native <button> so Enter/Space work without extra handlers;
 * - the click handler is the caller's (async, optimistic) — while it is in
 *   flight the button is disabled so a double click cannot send two pins.
 */

import React from 'react';
import { useTranslation } from 'react-i18next';
import { PinIcon } from './icons';
import './PinButton.css';

export interface PinButtonProps {
  pinned: boolean;
  /** Called on click; may be async. Errors are handled by the caller (Toast). */
  onToggle: () => void;
  /** Name of the pinned entity, used for the accessible label. */
  name: string;
  /** Which entity this pins — selects the aria-label wording. */
  kind?: 'project' | 'document';
  /** Exact data-testid, e.g. `pin-project-<slug>` / `pin-doc-<slug>`. */
  'data-testid': string;
  disabled?: boolean;
  /** Extra class for context-specific placement/sizing. */
  className?: string;
  /** Override the accessible label entirely (used where wording must differ). */
  ariaLabel?: string;
}

const PinButton: React.FC<PinButtonProps> = ({
  pinned,
  onToggle,
  name,
  kind = 'project',
  disabled,
  className,
  ariaLabel,
  ...testProps
}) => {
  const { t } = useTranslation();

  const label =
    ariaLabel ??
    t(pinned ? 'pin.unpinAria' : 'pin.pinAria', {
      type: t(kind === 'project' ? 'pin.typeProject' : 'pin.typeDocument'),
      name,
    });

  return (
    <button
      type="button"
      className={`pin-button${pinned ? ' pin-button-active' : ''}${className ? ` ${className}` : ''}`}
      aria-label={label}
      aria-pressed={pinned}
      title={label}
      onClick={(e) => {
        // Cards and document rows navigate on click — pinning must not navigate.
        e.stopPropagation();
        onToggle();
      }}
      disabled={disabled}
      data-testid={testProps['data-testid']}
    >
      <PinIcon size={15} filled={pinned} />
    </button>
  );
};

export default PinButton;
