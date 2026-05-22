import './AmbientBackground.css';

/**
 * AmbientBackground — tmavé pozadie s ambient orb animáciami a grid overlay.
 * Inšpirované TemplateMo 603 - Nexaverse (cyberpunk atmosféra).
 */
export default function AmbientBackground() {
  return (
    <>
      {/* Ambient Orbs */}
      <div className="ambient-orb ambient-orb--1" />
      <div className="ambient-orb ambient-orb--2" />
      <div className="ambient-orb ambient-orb--3" />

      {/* Grid Overlay */}
      <div className="ambient-grid" />
    </>
  );
}
