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
 */
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
} from 'react';
import './Menu.css';

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
  const rootRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const panelRef = useRef<HTMLDivElement>(null);
  const panelId = useId();

  const close = useCallback(() => setOpen(false), []);

  // Click outside → close, and Escape → close (document-level so it works
  // regardless of which element currently holds focus).
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (e: MouseEvent | TouchEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
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
      {open && (
        <div
          ref={panelRef}
          id={panelId}
          role="menu"
          className="menu-panel"
          data-testid={panelTestId}
          onKeyDown={onPanelKeyDown}
        >
          <MenuContext.Provider value={{ close }}>
            {children}
          </MenuContext.Provider>
        </div>
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
