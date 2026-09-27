/**
 * WIKI4AI-97: Calendar event create/edit modal.
 *
 * Fields: title, description, type (shared .form-select with an inline
 * "add new type" sub-layer), visibility segmented control (Public/Private —
 * lock icon on private), date input and an "all day" checkbox that hides the
 * start/end time inputs when checked.
 *
 * Create → POST /calendar/events; edit → PUT /calendar/events/{id}. The PUT is
 * PATCH-like (null = no change), so reverting a timed event back to all-day
 * sends the explicit `clearTime: true` signal (WIKI4AI-97 backend fix).
 */
import React, { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  createEvent,
  createEventType,
  updateEvent,
  type CalendarEvent,
  type CalendarEventType,
} from '../services/calendarApi';
import './EventFormModal.css';

// ── Inline SVG icons (no emoji — the container has no emoji font) ───────────

function LockIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
      <path d="M7 11V7a5 5 0 0 1 10 0v4" />
    </svg>
  );
}

function GlobeIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="10" />
      <line x1="2" y1="12" x2="22" y2="12" />
      <path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1-4-10 15.3 15.3 0 0 1 4-10z" />
    </svg>
  );
}

export interface EventFormModalProps {
  mode: 'create' | 'edit';
  /** Pre-filled date (YYYY-MM-DD) for create mode. */
  initialDate?: string;
  /**
   * WIKI4AI-110: pre-filled start time (HH:mm) for create mode — set when the
   * form was opened by clicking an hour slot in the week view. Implies a
   * timed event (the "all day" checkbox starts unchecked).
   */
  initialStartTime?: string | null;
  /** WIKI4AI-110: pre-filled end time (HH:mm) for create mode. */
  initialEndTime?: string | null;
  /** Existing event, required in edit mode. */
  event?: CalendarEvent | null;
  /** Current type list (for the select). */
  types: CalendarEventType[];
  /** Called after a new type is created so the parent can refresh its list. */
  onTypesChanged: (types: CalendarEventType[]) => void;
  onClose: () => void;
  /** Called with the saved event after a successful create/update. */
  onSaved: (event: CalendarEvent) => void;
}

export default function EventFormModal({
  mode,
  initialDate,
  initialStartTime,
  initialEndTime,
  event,
  types,
  onTypesChanged,
  onClose,
  onSaved,
}: EventFormModalProps) {
  const { t } = useTranslation();

  const isEdit = mode === 'edit' && !!event;
  const [title, setTitle] = useState(event?.title ?? '');
  const [description, setDescription] = useState(event?.description ?? '');
  const [typeId, setTypeId] = useState<string>(event ? String(event.eventTypeId) : '');
  const [visibility, setVisibility] = useState<'public' | 'private'>(event?.visibility ?? 'public');
  const [date, setDate] = useState(event?.eventDate ?? initialDate ?? '');
  // WIKI4AI-110: opening the form from a week-view hour slot means a timed
  // event — the "all day" checkbox starts unchecked and the slot's time is
  // pre-filled. Plain create (month view) keeps the all-day default.
  const [allDay, setAllDay] = useState(() => {
    if (event) return event.startTime === null;
    return !initialStartTime;
  });
  // The backend returns times as "HH:mm:ss"; <input type="time"> needs "HH:mm".
  const [startTime, setStartTime] = useState(
    event?.startTime ? event.startTime.slice(0, 5) : (initialStartTime ?? '09:00'),
  );
  const [endTime, setEndTime] = useState(event?.endTime ? event.endTime.slice(0, 5) : (initialEndTime ?? ''));

  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  // Inline "add new type" sub-layer state.
  const [showNewType, setShowNewType] = useState(false);
  const [newTypeName, setNewTypeName] = useState('');
  const [newTypeColor, setNewTypeColor] = useState('#4f8cff');
  const [creatingType, setCreatingType] = useState(false);

  // The "add new type" option is a sentinel value in the select.
  const ADD_NEW_TYPE = '__add_new__';

  const handleSelectChange = (value: string) => {
    if (value === ADD_NEW_TYPE) {
      setShowNewType(true);
      setNewTypeName('');
      setError(null);
      return;
    }
    setTypeId(value);
  };

  const handleCreateType = async () => {
    const name = newTypeName.trim();
    if (!name) {
      setError(t('calendar.form.typeNameRequired'));
      return;
    }
    setCreatingType(true);
    setError(null);
    try {
      const created = await createEventType(name, newTypeColor || undefined);
      onTypesChanged([...types, created]);
      setTypeId(String(created.id));
      setShowNewType(false);
    } catch (err) {
      setError(err instanceof Error ? err.message : t('calendar.form.typeCreateFailed'));
    } finally {
      setCreatingType(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setError(null);

    if (!title.trim()) {
      setError(t('calendar.form.titleRequired'));
      return;
    }
    if (!typeId) {
      setError(t('calendar.form.typeRequired'));
      return;
    }
    if (!date) {
      setError(t('calendar.form.dateRequired'));
      return;
    }
    if (!allDay && !startTime) {
      setError(t('calendar.form.startTimeRequired'));
      return;
    }

    setSaving(true);
    try {
      if (isEdit && event) {
        const input: Parameters<typeof updateEvent>[1] = {
          title: title.trim(),
          description: description.trim() || undefined,
          eventType: Number(typeId),
          eventDate: date,
          visibility,
        };
        if (allDay) {
          // Reverting a timed event to all-day needs the explicit clear signal —
          // null times on a PATCH-like PUT mean "no change".
          if (event.startTime !== null || event.endTime !== null) {
            input.clearTime = true;
          }
        } else {
          input.startTime = startTime;
          if (endTime) input.endTime = endTime;
        }
        const saved = await updateEvent(event.id, input);
        onSaved(saved);
      } else {
        const saved = await createEvent({
          title: title.trim(),
          description: description.trim() || undefined,
          eventType: Number(typeId),
          eventDate: date,
          startTime: allDay ? null : startTime,
          endTime: allDay ? null : endTime || null,
          visibility,
        });
        onSaved(saved);
      }
    } catch (err) {
      setError(err instanceof Error ? err.message : t('calendar.form.saveFailed'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="cal-modal-overlay" data-testid="event-form-overlay">
      <div className="cal-modal event-form-modal" role="dialog" aria-modal="true" data-testid="event-form-modal">
        <h2>{isEdit ? t('calendar.form.editTitle') : t('calendar.form.createTitle')}</h2>

        <form onSubmit={handleSubmit} noValidate>
          {/* Title */}
          <div className="cal-form-group">
            <label htmlFor="cal-event-title">{t('calendar.form.title')}</label>
            <input
              id="cal-event-title"
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder={t('calendar.form.titlePlaceholder')}
              data-testid="event-form-title"
              autoFocus
            />
          </div>

          {/* Description */}
          <div className="cal-form-group">
            <label htmlFor="cal-event-description">{t('calendar.form.description')}</label>
            <textarea
              id="cal-event-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder={t('calendar.form.descriptionPlaceholder')}
              rows={3}
              data-testid="event-form-description"
            />
          </div>

          {/* Type + inline "add new type" */}
          <div className="cal-form-group">
            <label htmlFor="cal-event-type">{t('calendar.form.type')}</label>
            {showNewType ? (
              <div className="new-type-layer" data-testid="event-form-new-type-layer">
                <div className="new-type-row">
                  <input
                    type="text"
                    value={newTypeName}
                    onChange={(e) => setNewTypeName(e.target.value)}
                    placeholder={t('calendar.form.newTypeNamePlaceholder')}
                    aria-label={t('calendar.form.newTypeName')}
                    data-testid="event-form-new-type-name"
                  />
                  <input
                    type="color"
                    value={newTypeColor}
                    onChange={(e) => setNewTypeColor(e.target.value)}
                    aria-label={t('calendar.form.newTypeColor')}
                    data-testid="event-form-new-type-color"
                  />
                </div>
                <div className="new-type-actions">
                  <button
                    type="button"
                    className="btn-secondary btn-sm"
                    onClick={() => setShowNewType(false)}
                    disabled={creatingType}
                    data-testid="event-form-new-type-cancel"
                  >
                    {t('common.cancel')}
                  </button>
                  <button
                    type="button"
                    className="btn-primary btn-sm"
                    onClick={() => void handleCreateType()}
                    disabled={creatingType || !newTypeName.trim()}
                    data-testid="event-form-new-type-create"
                  >
                    {creatingType ? t('calendar.form.creating') : t('calendar.form.createType')}
                  </button>
                </div>
              </div>
            ) : (
              <select
                id="cal-event-type"
                className="form-select"
                value={typeId}
                onChange={(e) => handleSelectChange(e.target.value)}
                data-testid="event-form-type"
              >
                <option value="" disabled>
                  {t('calendar.form.typePlaceholder')}
                </option>
                {types.map((type) => (
                  <option key={type.id} value={String(type.id)}>
                    {type.name}
                  </option>
                ))}
                <option value={ADD_NEW_TYPE}>{t('calendar.form.addNewType')}</option>
              </select>
            )}
          </div>

          {/* Visibility segmented control */}
          <div className="cal-form-group">
            <span className="cal-form-label">{t('calendar.form.visibility')}</span>
            <div className="visibility-toggle" role="group" aria-label={t('calendar.form.visibility')}>
              <button
                type="button"
                className={`visibility-option${visibility === 'public' ? ' visibility-option-active' : ''}`}
                aria-pressed={visibility === 'public'}
                onClick={() => setVisibility('public')}
                data-testid="event-form-visibility-public"
              >
                <GlobeIcon />
                {t('calendar.form.public')}
              </button>
              <button
                type="button"
                className={`visibility-option${visibility === 'private' ? ' visibility-option-active' : ''}`}
                aria-pressed={visibility === 'private'}
                onClick={() => setVisibility('private')}
                data-testid="event-form-visibility-private"
              >
                <LockIcon />
                {t('calendar.form.private')}
              </button>
            </div>
          </div>

          {/* Date */}
          <div className="cal-form-group">
            <label htmlFor="cal-event-date">{t('calendar.form.date')}</label>
            <input
              id="cal-event-date"
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              data-testid="event-form-date"
            />
          </div>

          {/* All-day checkbox */}
          <label className="cal-all-day-check">
            <input
              type="checkbox"
              checked={allDay}
              onChange={(e) => setAllDay(e.target.checked)}
              data-testid="event-form-allday"
            />
            <span>{t('calendar.form.allDay')}</span>
          </label>

          {/* Time inputs (hidden when all-day) */}
          {!allDay && (
            <div className="cal-time-row">
              <div className="cal-form-group cal-form-group-time">
                <label htmlFor="cal-event-start">{t('calendar.form.startTime')}</label>
                <input
                  id="cal-event-start"
                  type="time"
                  value={startTime}
                  onChange={(e) => setStartTime(e.target.value)}
                  data-testid="event-form-start-time"
                />
              </div>
              <div className="cal-form-group cal-form-group-time">
                <label htmlFor="cal-event-end">{t('calendar.form.endTime')}</label>
                <input
                  id="cal-event-end"
                  type="time"
                  value={endTime}
                  onChange={(e) => setEndTime(e.target.value)}
                  data-testid="event-form-end-time"
                />
              </div>
            </div>
          )}

          {error && (
            <p className="cal-form-error" role="alert" data-testid="event-form-error">
              {error}
            </p>
          )}

          <div className="cal-form-actions">
            <button type="button" className="btn-secondary" onClick={onClose} disabled={saving} data-testid="event-form-cancel">
              {t('common.cancel')}
            </button>
            <button type="submit" className="btn-primary" disabled={saving} data-testid="event-form-submit">
              {saving ? t('calendar.form.creating') : t('common.save')}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
