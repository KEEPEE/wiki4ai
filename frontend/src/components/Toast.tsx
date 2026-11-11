/**
 * Toast notification component — Nexaverse glassmorphism with neon accents.
 *
 * Features:
 * - Glassmorphism panel (backdrop-filter blur, translucent bg)
 * - Neon accent bars per type (success green / error red / info cyan→purple)
 * - Slide-in animation (30px + fade 0→1, 0.4s ease)
 * - Slide-out animation (fade to 0, 0.3s ease)
 * - Auto-dismiss after configurable duration (default 5s)
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import './Toast.css';

/** Toast type enum */
export type ToastType = 'success' | 'error' | 'info';

/** Single toast item shape */
export interface ToastItem {
  id: number;
  message: string;
  type: ToastType;
}

/* ── Accent bar gradient per type ─────────────────────────────── */
const AUTO_DISMISS_MS = 5000;


/* WIKI4AI-194 (DIZ-3 item 7): tri hex gradienty mimo tokens.css
   (#065f46/#16a34a, #dc2626/#ef4444). Accent lista je 4px INDIKATOR,
   preto top vrstva pouziva --color-*-text — sýtu farbu, ktorá v každej
   identite spĺňa 3:1 (1.4.11) na sklenom paneli. --accent pri info tipe
   bol fialový slot, ktorý v glass-light nie je info farba. */
const accentGradients: Record<ToastType, string> = {
  success: 'linear-gradient(180deg, var(--color-success-text) 0%, var(--color-success-border) 100%)',
  error:   'linear-gradient(180deg, var(--color-error-text) 0%, var(--color-error-border) 100%)',
  info:    'linear-gradient(180deg, var(--color-info-text) 0%, var(--color-info-border) 100%)',
};

/* ── ToastItem component ─────────────────────────────────────── */

interface ToastItemProps {
  item: ToastItem;
  onDismiss: (id: number) => void;
}

const ToastItemComponent: React.FC<ToastItemProps> = ({ item, onDismiss }) => {
  const { t } = useTranslation();
  const [isExiting, setIsExiting] = useState(false);
  // WIKI4AI-179: every timer this item schedules is tracked — the 5 s
  // auto-dismiss AND the 300 ms slide-out timer. The slide-out timer used to be
  // fire-and-forget in two places, so `onDismiss(item.id)` could reach the
  // parent after this item — or the parent that owns the toast list — was gone.
  const timersRef = useRef<number[]>([]);

  const scheduleTimer = useCallback((fn: () => void, ms: number) => {
    const id = window.setTimeout(() => {
      timersRef.current = timersRef.current.filter((timerId) => timerId !== id);
      fn();
    }, ms);
    timersRef.current.push(id);
  }, []);

  const startSlideOut = useCallback(() => {
    setIsExiting(true);
    scheduleTimer(() => onDismiss(item.id), 300); // wait for slide-out animation
  }, [item.id, onDismiss, scheduleTimer]);

  /* WIKI4AI-195 (DIZ-4 item 7, WCAG 2.2.1 Timing Adjustable): chybovy
   * toast zmizal po 5 s. Človek, ktorému čítačka hlásku stíha neskoro,
   * alebo klávesnica, ktorá sa k toastu dostane až po odtabovaní panela —
   * tí by správu NIČIL a nemohli by ju vrátiť. Error sa preto
   * nautodimissuje vôbec; mizne len na požiadanie. (Success a info sú
   * potvrdenia — ich strata nič neblokuje.) */
  useEffect(() => {
    if (item.type === 'error') {
      return () => {
        timersRef.current.forEach((timerId) => window.clearTimeout(timerId));
        timersRef.current = [];
      };
    }

    scheduleTimer(startSlideOut, AUTO_DISMISS_MS);

    return () => {
      timersRef.current.forEach((timerId) => window.clearTimeout(timerId));
      timersRef.current = [];
    };
  }, [item.id, item.type, onDismiss, scheduleTimer, startSlideOut]);

  const handleDismiss = () => startSlideOut();

  return (
    <div
      className={`toast toast-${item.type} ${isExiting ? 'toast-exit' : ''}`}
      /* WIKI4AI-195 (DIZ-4 item 7): role="alert" IMPLIKUJE
       * aria-live="assertive", takže explicitné aria-live="polite" ho
       * priamo rozporovalo a implementácie sa nezhodujú, ktorá vyhráva.
       * Delene podľa typu: error = alert (preruší), success/info = status
       * (nepreruší). aria-live sa nedáva vôbec — zdrojom pravdy je role. */
      role={item.type === 'error' ? 'alert' : 'status'}
      data-testid={`toast-${item.type}`}
    >
      {/* Neon accent bar */}
      <span
        className="toast-accent-bar"
        style={{ background: accentGradients[item.type] }}
      />

      {/* Message text */}
      <span className="toast-message">{item.message}</span>

      {/* Dismiss button */}
      <button
        type="button"
        className="toast-dismiss"
        onClick={handleDismiss}
        aria-label={t('toast.dismissAria')}
      >
        ×
      </button>
    </div>
  );
};

/* ── ToastContainer component (public API) ───────────────────── */

interface ToastContainerProps {
  toasts: ToastItem[];
  onDismiss: (id: number) => void;
}

export const ToastContainer: React.FC<ToastContainerProps> = ({ toasts, onDismiss }) => {
  if (toasts.length === 0) return null;

  return (
    <div className="toast-container" data-testid="toast-container">
      {toasts.map((toast) => (
        <ToastItemComponent key={toast.id} item={toast} onDismiss={onDismiss} />
      ))}
    </div>
  );
};

export default ToastContainer;
