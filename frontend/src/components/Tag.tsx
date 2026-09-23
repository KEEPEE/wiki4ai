/**
 * Tag / Label Badge component — glassmorphism s neon gradient border efektmi.
 *
 * Štýl:
 * - Background: rgba(255,255,255,0.03) (var(--glass-bg))
 * - Border: 1px solid rgba(0,240,255,0.2) — cyan pri 20% opacity
 * - border-radius: 50px (pill shape)
 * - Padding: 4px 12px
 * - Text: Outfit font, weight 400, color rgba(255,255,255,0.6), font-size 12px
 * - Hover: border-color var(--primary), box-shadow neon glow, color var(--primary)
 */

import React from 'react';
import { useTranslation } from 'react-i18next';
import './Tag.css';

interface TagProps {
  label: string;
  variant?: 'default' | 'admin' | 'user';
  onClick?: () => void;
}

/**
 * Render a styled tag/label badge with glassmorphism and neon effects.
 */
const Tag: React.FC<TagProps> = ({ label, variant = 'default', onClick }) => {
  const { t } = useTranslation();
  const className = `tag ${variant === 'admin' ? 'tag-admin' : variant === 'user' ? 'tag-user' : ''}`;

  return (
    <span className={className} role="img" aria-label={t('tag.label', { label })} onClick={onClick}>
      {label}
    </span>
  );
};

export default Tag;
