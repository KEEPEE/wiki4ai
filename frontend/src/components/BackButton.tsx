import { useNavigate } from 'react-router-dom';

interface BackButtonProps {
  /** The route to navigate back to. Falls back to router's `go(-1)` if not provided. */
  to?: string;
  /** Custom label text. Defaults to "Späť". */
  label?: string;
}

/**
 * Glassmorphism Back Button component.
 * Displays a pill-shaped button with neon hover effects, inspired by Nexaverse design.
 * Used in Document Viewer, Document Editor, and Project Settings pages.
 */
export default function BackButton({ to, label = 'Späť' }: BackButtonProps) {
  const navigate = useNavigate();

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
      aria-label={`Navigate back${to ? ` to ${to}` : ''}`}
    >
      <span className="back-button-icon" aria-hidden="true">←</span>
      <span className="back-button-text">{label}</span>
    </button>
  );
}
