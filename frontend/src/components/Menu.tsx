/**
 * WIKI4AI-85/86: shared accessible dropdown menu.
 *
 * One component powers both the top-nav user menu (Layout) and the per-row
 * kebab menu on the admin users table, so hover/focus states, keyboard
 * handling (ArrowUp/Down/Home/End/Esc/Tab), Esc-to-close, click-outside-to-close
 * and theming stay consistent everywhere.
 *
 * ARIA: follows the WAI-ARIA "menu button" pattern — the trigger is a real
 * <button aria-haspopup="menu">, the panel is role="menu", plain actions are
 * role="menuitem" and radio choices are role="menuitemradio" with
 * aria-checked. All items are focusable <button>s so keyboard users can reach
 * them without extra tab stops.
 *
 * WIKI4AI-91: the panel is rendered through a portal into document.body with
 * position: fixed coordinates derived from the trigger's getBoundingClientRect,
 * so overflow ancestors (e.g. .table-wrapper on /admin/users) can no longer
 * clip it. The panel drops down by default, flips up when there is not enough
 * room below the viewport bottom, and is clamped inside the viewport
 * horizontally (and vertically as a safety net). While open it repositions on
 * window resize and on ANY scroll — a capture-phase document listener catches
 * nested scrollers such as .table-wrapper, so the panel never floats away from
 * its trigger. All listeners are passive.
 */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useLayoutEffect,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
} from 'react';
import { createPortal } from 'react-dom';
import './Menu.css';

// ── WIKI4AI-91: portal placement constants ─────────────────────────────────
const PANEL_GAP = 8; // px between the trigger edge and the panel
const VIEWPORT_MARGIN = 8; // minimum distance of the panel from viewport edges

interface PanelPosition {
  top: number;
  left: number;
}

// ── Context ────────────────────────────────────────────────────────────────

interface MenuContextValue {
  /** Close the panel (used by items that navigate away). */
  close: () => void;
}

const MenuContext = createContext<MenuContextValue | null>(null);

function useMenuContext(): MenuContextValue {
  const ctx = useContext(MenuContext);
  if (!ctx) throw new Error('MenuItem/MenuDivider must be used inside <Menu>');
  return ctx;
}

// ── Menu (trigger + panel) ─────────────────────────────────────────────────

export interface MenuProps {
  /** Content rendered inside the trigger button. */
  trigger: ReactNode;
  /** Extra class for the trigger button (e.g. avatar vs kebab styling). */
  triggerClassName?: string;
  /** Accessible name for the trigger button. */
  ariaLabel: string;
  /** data-testid for the trigger button. */
  testId?: string;
  /** data-testid for the panel (rendered only while open). */
  panelTestId?: string;
  /** Align the panel to the start or end edge of the trigger. Default 'end'. */
  align?: 'start' | 'end';
  children: ReactNode;
}

export function Menu({
  trigger,
  triggerClassName,
  ariaLabel,
  testId,
  panelTestId,
  align = 'end',
  children,
}: MenuProps) {
  const [open, setOpen] = useState(false);
  // WIKI4AI-91: fixed viewport coordinates for the portal panel. Null until the
  // first measurement pass (the panel is then rendered off-screen and measured
  // in a useLayoutEffect before paint, so there is no visible flicker).
  const [panelPos, setPanelPos] = useState<PanelPosition | null>(null);
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const panelId = useId();

  const close = useCallback(() => setOpen(false), []);

  // WIKI4AI-91: compute the panel's fixed position from the trigger's current
  // viewport rect. Drops down by default; flips up when there is not enough
  // room below the viewport bottom (and there IS room above); clamped to stay
  // inside the viewport horizontally, with a vertical safety clamp for the
  // case where the trigger itself has been scrolled out of view.
  const updatePanelPosition = useCallback(() => {
    const trigger = triggerRef.current;
    const panel = panelRef.current;
    if (!trigger || !panel) return;
    const t = trigger.getBoundingClientRect();
    const p = panel.getBoundingClientRect(); // only width/height matter here
    let top = t.bottom + PANEL_GAP;
    const fitsBelow = top + p.height <= window.innerHeight - VIEWPORT_MARGIN;
    const spaceAbove = t.top - p.height - PANEL_GAP;
    if (!fitsBelow && spaceAbove >= VIEWPORT_MARGIN) {
      top = t.top - p.height - PANEL_GAP; // flip up near the viewport bottom
    }
    top = Math.min(
      Math.max(top, VIEWPORT_MARGIN),
      Math.max(window.innerHeight - p.height - VIEWPORT_MARGIN, VIEWPORT_MARGIN),
    );
    let left = align === 'end' ? t.right - p.width : t.left;
    left = Math.min(
      Math.max(left, VIEWPORT_MARGIN),
      Math.max(window.innerWidth - p.width - VIEWPORT_MARGIN, VIEWPORT_MARGIN),
    );
    setPanelPos({ top: Math.round(top), left: Math.round(left) });
  }, [align]);

  // First measurement pass on open (layout effect → before paint, no flicker).
  useLayoutEffect(() => {
    if (!open) return;
    updatePanelPosition();
  }, [open, updatePanelPosition]);

  // WIKI4AI-91: keep the panel pinned to the trigger while it is open.
  // Reposition (rather than close) on window resize and on any scroll: the
  // capture-phase document listener also fires for nested scrollers such as
  // .table-wrapper, whose overflow would otherwise leave a stale panel behind.
  useEffect(() => {
    if (!open) return;
    const reposition = () => updatePanelPosition();
    window.addEventListener('resize', reposition, { passive: true });
    window.addEventListener('scroll', reposition, { passive: true });
    document.addEventListener('scroll', reposition, { passive: true, capture: true });
    return () => {
      window.removeEventListener('resize', reposition);
      window.removeEventListener('scroll', reposition);
      document.removeEventListener('scroll', reposition, true);
    };
  }, [open, updatePanelPosition]);

  // Click outside → close, and Escape → close (document-level so it works
  // regardless of which element currently holds focus). WIKI4AI-91: the panel
  // lives in a portal on document.body, so "inside the menu" now means inside
  // the trigger root OR inside the portal panel.
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: MouseEvent | TouchEvent) => {
      const target = e.target as Node;
      if (rootRef.current?.contains(target)) return;
      if (panelRef.current?.contains(target)) return;
      setOpen(false);
    };
    const onKeyDown = (e: globalThis.KeyboardEvent) => {
      if (e.key === 'Escape') {
        setOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener('mousedown', onPointerDown);
    document.addEventListener('touchstart', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onPointerDown);
      document.removeEventListener('touchstart', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  // On open, move focus to the first enabled item so arrow keys work
  // immediately (both mouse-open and keyboard-open).
  useEffect(() => {
    if (!open || !panelRef.current) return;
    const raf = requestAnimationFrame(() => {
      const first = panelRef.current?.querySelector<HTMLElement>(
        '[data-menu-item]:not(:disabled)',
      );
      first?.focus();
    });
    return () => cancelAnimationFrame(raf);
  }, [open]);

  // Arrow/Home/End navigation. (Escape is handled at document level above.)
  const onPanelKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Tab') {
      // Let the browser move focus out of the menu; close as a side effect.
      setOpen(false);
      return;
    }
    if (e.key !== 'ArrowDown' && e.key !== 'ArrowUp' && e.key !== 'Home' && e.key !== 'End') {
      return;
    }
    const items = Array.from(
      panelRef.current?.querySelectorAll<HTMLElement>('[data-menu-item]:not(:disabled)') ?? [],
    );
    if (items.length === 0) return;
    const idx = items.indexOf(document.activeElement as HTMLElement);
    e.preventDefault();
    let next: number;
    if (e.key === 'Home') next = 0;
    else if (e.key === 'End') next = items.length - 1;
    else if (e.key === 'ArrowDown') next = idx < 0 ? 0 : (idx + 1) % items.length;
    else next = idx <= 0 ? items.length - 1 : idx - 1;
    items[next]?.focus();
  };

  return (
    <div className={`menu-root menu-align-${align}`} ref={rootRef}>
      <button
        type="button"
        ref={triggerRef}
        className={`menu-trigger ${triggerClassName ?? ''}`.trim()}
        aria-label={ariaLabel}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        data-testid={testId}
        onClick={() => setOpen((v) => !v)}
      >
        {trigger}
      </button>
      {open &&
        createPortal(
          <div
            ref={panelRef}
            id={panelId}
            role="menu"
            className="menu-panel"
            data-testid={panelTestId}
            // WIKI4AI-91: position fixed; top/left come from the trigger's
            // getBoundingClientRect (see updatePanelPosition). Until the first
            // measurement pass the panel sits off-screen — useLayoutEffect
            // corrects it before paint.
            style={{
              top: panelPos?.top ?? -9999,
              left: panelPos?.left ?? -9999,
            }}
            onKeyDown={onPanelKeyDown}
          >
            <MenuContext.Provider value={{ close }}>
              {children}
            </MenuContext.Provider>
          </div>,
          document.body,
        )}
    </div>
  );
}

// ── Menu items ─────────────────────────────────────────────────────────────

export interface MenuItemProps {
  children: ReactNode;
  /** Optional leading icon (SVG). */
  icon?: ReactNode;
  /** Style as a destructive action. */
  danger?: boolean;
  disabled?: boolean;
  /** Native tooltip. */
  title?: string;
  onClick?: () => void;
  testId?: string;
}

/** Plain menu action (role="menuitem"). Closes the panel on click unless the handler stops it. */
export function MenuItem({ children, icon, danger, disabled, title, onClick, testId }: MenuItemProps) {
  const { close } = useMenuContext();
  return (
    <button
      type="button"
      role="menuitem"
      data-menu-item
      className={`menu-item${danger ? ' menu-item-danger' : ''}`}
      disabled={disabled}
      title={title}
      data-testid={testId}
      onClick={() => {
        close();
        onClick?.();
      }}
    >
      {icon && <span className="menu-item-icon" aria-hidden="true">{icon}</span>}
      <span className="menu-item-label">{children}</span>
    </button>
  );
}

export interface MenuRadioItemProps {
  children: ReactNode;
  /** Whether this option is the current/selected one (radio semantics). */
  selected?: boolean;
  disabled?: boolean;
  onClick?: () => void;
  testId?: string;
}

/** Radio-style menu choice (role="menuitemradio"); the selected one is highlighted. */
export function MenuRadioItem({ children, selected, disabled, onClick, testId }: MenuRadioItemProps) {
  const { close } = useMenuContext();
  return (
    <button
      type="button"
      role="menuitemradio"
      aria-checked={!!selected}
      data-menu-item
      className={`menu-item menu-item-radio${selected ? ' menu-item-selected' : ''}`}
      disabled={disabled}
      data-testid={testId}
      onClick={() => {
        close();
        onClick?.();
      }}
    >
      <span className="menu-item-icon" aria-hidden="true">
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round">
          {selected ? (
            <path d="M20 6L9 17l-5-5" />
          ) : (
            <circle cx="12" cy="12" r="9" opacity="0.4" />
          )}
        </svg>
      </span>
      <span className="menu-item-label">{children}</span>
    </button>
  );
}

/** Non-focusable group label inside the panel (e.g. "Role"). */
export function MenuSectionLabel({ children, testId }: { children: ReactNode; testId?: string }) {
  return (
    <div className="menu-section-label" aria-hidden="true" data-testid={testId}>
      {children}
    </div>
  );
}

/** Visual separator. */
export function MenuDivider() {
  return <hr className="menu-divider" role="separator" />;
}
