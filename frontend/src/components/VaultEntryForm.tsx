import React, { useState, useEffect } from 'react';
import type { VaultEntry } from '../types/vault';
import './VaultEntryForm.css';

export interface VaultEntryFormData {
  title: string;
  url: string;
  groupPath: string;
  username: string;
  password: string;
  notes: string;
}

export interface VaultEntryFormProps {
  entry?: VaultEntry | null;
  existingGroups?: string[];
  onSubmit: (data: VaultEntryFormData) => Promise<void>;
  onCancel: () => void;
  isSubmitting?: boolean;
  error?: string | null;
}

const DEFAULT_FORM_DATA: VaultEntryFormData = {
  title: '',
  url: '',
  groupPath: '',
  username: '',
  password: '',
  notes: '',
};

function generatePassword(length = 16): string {
  const charset = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!@#$%^&*';
  let result = '';
  for (let i = 0; i < length; i++) {
    result += charset.charAt(Math.floor(Math.random() * charset.length));
  }
  return result;
}

const VaultEntryForm: React.FC<VaultEntryFormProps> = ({
  entry,
  existingGroups = [],
  onSubmit,
  onCancel,
  isSubmitting = false,
  error,
}) => {
  const [formData, setFormData] = useState<VaultEntryFormData>(DEFAULT_FORM_DATA);
  const [showPassword, setShowPassword] = useState(false);
  const [fieldErrors, setFieldErrors] = useState<Partial<Record<keyof VaultEntryFormData, string>>>({});
  const [showGroupSuggestions, setShowGroupSuggestions] = useState(false);

  useEffect(() => {
    if (entry) {
      setFormData({
        title: entry.title || '',
        url: entry.url || '',
        groupPath: entry.groupPath || '',
        username: entry.data.username || '',
        password: entry.data.password || '',
        notes: entry.data.notes || '',
      });
    } else {
      setFormData(DEFAULT_FORM_DATA);
    }
  }, [entry]);

  const validate = (): boolean => {
    const errors: Partial<Record<keyof VaultEntryFormData, string>> = {};

    if (!formData.title.trim()) {
      errors.title = 'Title is required';
    }

    if (!formData.password) {
      errors.password = 'Password is required';
    }

    if (formData.url && !isValidUrl(formData.url)) {
      errors.url = 'Please enter a valid URL';
    }

    setFieldErrors(errors);
    return Object.keys(errors).length === 0;
  };

  const isValidUrl = (value: string): boolean => {
    try {
      new URL(value);
      return true;
    } catch {
      return false;
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!validate()) return;
    await onSubmit(formData);
  };

  const handleChange = (field: keyof VaultEntryFormData, value: string) => {
    setFormData((prev) => ({ ...prev, [field]: value }));
    if (fieldErrors[field]) {
      setFieldErrors((prev) => {
        const next = { ...prev };
        delete next[field];
        return next;
      });
    }
  };

  const handleGeneratePassword = () => {
    const newPassword = generatePassword();
    handleChange('password', newPassword);
    setShowPassword(true);
  };

  const filteredGroups = existingGroups.filter((g) =>
    g.toLowerCase().includes(formData.groupPath.toLowerCase()),
  );

  const isEditMode = !!entry;

  return (
    <form onSubmit={handleSubmit} className="vault-entry-form">
      <h3>{isEditMode ? 'Edit Entry' : 'Add New Entry'}</h3>

      <div className="form-field">
        <label htmlFor="vault-form-title">Title</label>
        <input
          id="vault-form-title"
          type="text"
          placeholder="Title (e.g., GitHub)"
          value={formData.title}
          onChange={(e) => handleChange('title', e.target.value)}
          autoFocus
          data-testid="vault-form-title-input"
        />
        {fieldErrors.title && <span className="field-error">{fieldErrors.title}</span>}
      </div>

      <div className="form-field">
        <label htmlFor="vault-form-url">URL</label>
        <input
          id="vault-form-url"
          type="text"
          placeholder="URL (optional)"
          value={formData.url}
          onChange={(e) => handleChange('url', e.target.value)}
          data-testid="vault-form-url-input"
        />
        {fieldErrors.url && <span className="field-error">{fieldErrors.url}</span>}
      </div>

      <div className="form-field">
        <label htmlFor="vault-form-group-path">Group Path</label>
        <div className="group-path-wrapper">
          <input
            id="vault-form-group-path"
            type="text"
            placeholder="Group path (e.g., /Work, /Personal)"
            value={formData.groupPath}
            onChange={(e) => handleChange('groupPath', e.target.value)}
            onFocus={() => setShowGroupSuggestions(true)}
            onBlur={() => setTimeout(() => setShowGroupSuggestions(false), 150)}
            data-testid="vault-form-group-path-input"
          />
          {showGroupSuggestions && filteredGroups.length > 0 && (
            <ul className="group-suggestions">
              {filteredGroups.map((g) => (
                <li
                  key={g}
                  onClick={() => {
                    handleChange('groupPath', g);
                    setShowGroupSuggestions(false);
                  }}
                  data-testid={`vault-form-group-suggestion-${g.replace(/\//g, '-')}`}
                >
                  {g || '/'}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      <div className="form-field">
        <label htmlFor="vault-form-username">Username</label>
        <input
          id="vault-form-username"
          type="text"
          placeholder="Username (optional)"
          value={formData.username}
          onChange={(e) => handleChange('username', e.target.value)}
          data-testid="vault-form-username-input"
        />
      </div>

      <div className="form-field">
        <label htmlFor="vault-form-password">Password</label>
        <div className="password-wrapper">
          <input
            id="vault-form-password"
            type={showPassword ? 'text' : 'password'}
            placeholder="Password"
            value={formData.password}
            onChange={(e) => handleChange('password', e.target.value)}
            data-testid="vault-form-password-input"
          />
          <button
            type="button"
            className="toggle-password-btn"
            onClick={() => setShowPassword((prev) => !prev)}
            aria-label={showPassword ? 'Hide password' : 'Show password'}
            data-testid="vault-form-toggle-password-button"
          >
            {showPassword ? '🙈' : '👁️'}
          </button>
        </div>
        <div className="password-actions">
          <button
            type="button"
            className="generate-password-btn"
            onClick={handleGeneratePassword}
            data-testid="vault-form-generate-password-button"
          >
            Generate password
          </button>
        </div>
        {fieldErrors.password && <span className="field-error">{fieldErrors.password}</span>}
      </div>

      <div className="form-field">
        <label htmlFor="vault-form-notes">Notes</label>
        <textarea
          id="vault-form-notes"
          placeholder="Notes (optional)"
          value={formData.notes}
          onChange={(e) => handleChange('notes', e.target.value)}
          rows={3}
          data-testid="vault-form-notes-input"
        />
      </div>

      {error && <p className="form-error">{error}</p>}

      <div className="form-actions">
        <button type="submit" className="btn-primary" disabled={isSubmitting}>
          {isEditMode ? 'Save Changes' : 'Add Entry'}
        </button>
        <button type="button" onClick={onCancel} className="btn-secondary">
          Cancel
        </button>
      </div>
    </form>
  );
};

export default VaultEntryForm;
