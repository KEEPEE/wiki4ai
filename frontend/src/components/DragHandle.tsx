/**
 * WIKI4AI-187: the drag handle of a project card / document row.
 *
 * The handle is the drag SOURCE (`draggable`), the card/row is the drop target.
 * That split is deliberate: the card itself is a link (clicking it navigates),
 * so making the whole card draggable would turn every click-and-move into an
 * accidental reorder.
 *
 * When the list is not in the manual order the handle is locked: it stays
 * visible (so the affordance is discoverable), carries the reason as its
 * tooltip, and a click on it switches the list to the manual order — which
 * unlocks the drag. That is the "clear state message + one click to fix it"
 * the story asks for, instead of a handle that silently does nothing.
 */

import React from 'react';
import { GripIcon } from './icons';
import './ListToolbar.css';

export interface DragHandleProps {
  /** e.g. `drag-handle-project-<slug>` / `drag-handle-doc-<slug>`. */
  testId: string;
  /** False when the current sort/filter combination forbids reordering. */
  enabled: boolean;
  /** Tooltip: the drag hint when enabled, the reason when locked. */
  title: string;
  ariaLabel: string;
  onDragStart: (event: React.DragEvent<HTMLSpanElement>) => void;
  onDragEnd: () => void;
  /** Click / Enter on a locked handle (switch to the manual order). */
  onActivate?: () => void;
  /** The card/row to use as the drag image instead of the tiny handle. */
  getDragImage?: () => HTMLElement | null;
}

const DragHandle: React.FC<DragHandleProps> = ({
  testId,
  enabled,
  title,
  ariaLabel,
  onDragStart,
  onDragEnd,
  onActivate,
  getDragImage,
}) => {
  const handleDragStart = (event: React.DragEvent<HTMLSpanElement>) => {
    // Drag the whole card/row, not the 14 px grip the cursor happens to sit on.
    // The row marks itself with `[data-dnd-row]`, so no ref plumbing is needed.
    const image = getDragImage?.()
      ?? (event.currentTarget as HTMLElement).closest('[data-dnd-row]') as HTMLElement | null;
    if (image && event.dataTransfer?.setDragImage) {
      try {
        event.dataTransfer.setDragImage(image, 16, 16);
      } catch {
        /* jsdom has no layout — the drag still proceeds */
      }
    }
    onDragStart(event);
  };

  return (
    <span
      className={`drag-handle${enabled ? '' : ' drag-handle-locked'}`}
      data-testid={testId}
      data-dnd-enabled={enabled ? 'true' : 'false'}
      title={title}
      aria-label={ariaLabel}
      role="button"
      tabIndex={0}
      draggable={enabled}
      aria-disabled={!enabled}
      onDragStart={handleDragStart}
      onDragEnd={onDragEnd}
      onClick={(event) => {
        event.stopPropagation(); // never navigate the card while grabbing
        if (!enabled) onActivate?.();
      }}
      onKeyDown={(event) => {
        if (enabled) return;
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          event.stopPropagation();
          onActivate?.();
        }
      }}
    >
      <GripIcon size={14} />
    </span>
  );
};

export default DragHandle;
