import { useNavigate } from 'react-router-dom';
import { useTranslation } from 'react-i18next';

interface BackButtonProps {
  /** The route to navigate back to. Falls back to router's `go(-1)` if not provided. */
  to?: string;
  /** Custom label text. Defaults to the localized "Back". */
  label?: string;
}

/**
 * Glassmorphism Back Button component.
 * Displays a pill-shaped button with neon hover effects, inspired by Nexaverse design.
 * Used in Document Viewer, Document Editor, and Project Settings pages.
 */
export default function BackButton({ to, label }: BackButtonProps) {
  const navigate = useNavigate();
  const { t } = useTranslation();

  const handleClick = () => {
    if (to) {
      navigate(to);
    } else {
      navigate(-1);
    }
  };

  return (
    <button
      onClick={handleClick}
      className="back-button"
      aria-label={t('common.back')}
    >
      <span className="back-button-icon" aria-hidden="true">←</span>
      <span className="back-button-text">{label ?? t('common.back')}</span>
    </button>
  );
}
