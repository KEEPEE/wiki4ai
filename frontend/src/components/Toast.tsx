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

const accentGradients: Record<ToastType, string> = {
  success: 'linear-gradient(180deg, #065f46 0%, #16a34a 100%)',
  error:   'linear-gradient(180deg, #dc2626 0%, #ef4444 100%)',
  info:    'linear-gradient(180deg, var(--primary) 0%, var(--accent) 100%)',
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

  useEffect(() => {
    // Auto-dismiss after 5 seconds (configurable)
    scheduleTimer(startSlideOut, 5000);

    return () => {
      timersRef.current.forEach((timerId) => window.clearTimeout(timerId));
      timersRef.current = [];
    };
  }, [item.id, onDismiss, scheduleTimer, startSlideOut]);

  const handleDismiss = () => startSlideOut();

  return (
    <div
      className={`toast toast-${item.type} ${isExiting ? 'toast-exit' : ''}`}
      role="alert"
      aria-live="polite"
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
