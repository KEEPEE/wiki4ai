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

import React, { useEffect, useRef, useState } from 'react';
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

const accentBorders: Record<ToastType, string> = {
  success: 'rgba(22, 163, 74, 0.3)',
  error:   'rgba(220, 38, 38, 0.3)',
  info:    'var(--primary)',
};

const accentGlowColors: Record<ToastType, string> = {
  success: 'rgba(16, 185, 129, 0.3)',
  error:   'rgba(239, 68, 68, 0.3)',
  info:    'var(--glow-cyan)',
};

/* ── ToastItem component ─────────────────────────────────────── */

interface ToastItemProps {
  item: ToastItem;
  onDismiss: (id: number) => void;
}

const ToastItemComponent: React.FC<ToastItemProps> = ({ item, onDismiss }) => {
  const [isExiting, setIsExiting] = useState(false);
  const timerRef = useRef<number | null>(null);

  useEffect(() => {
    // Auto-dismiss after 5 seconds (configurable)
    timerRef.current = window.setTimeout(() => {
      setIsExiting(true);
      setTimeout(() => onDismiss(item.id), 300); // wait for slide-out animation
    }, 5000);

    return () => {
      if (timerRef.current) clearTimeout(timerRef.current);
    };
  }, [item.id, onDismiss]);

  const handleDismiss = () => {
    setIsExiting(true);
    setTimeout(() => onDismiss(item.id), 300);
  };

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
        aria-label="Dismiss notification"
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
