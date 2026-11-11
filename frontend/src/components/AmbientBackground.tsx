import './AmbientBackground.css';

/**
 * AmbientBackground — glass pozadie (`.background` + 3 `.orb` + mriežka).
 *
 * WIKI4AI-185: `.background` (gradient + farebné mlhy) je SÚČASŤOU glass
 * receptu z tm-607 Glass Admin — `backdrop-filter` sidebaru, topbaru a kariet
 * je vidieť len cez priesvitné pozadie. Bez tohto divu by sklo vyzeralo plocho.
 *
 * Vzhľad riadia tokeny v `src/styles/globals.css` (3 bloky tém), nie hex farby.
 */
export default function AmbientBackground() {
  return (
    <>
      {/* Gradient + mlhy — podklad, cez ktorý sa ukazuje backdrop-filter */}
      <div className="background" data-testid="app-background" />

      {/* Ambient Orbs */}
      <div className="ambient-orb ambient-orb--1" />
      <div className="ambient-orb ambient-orb--2" />
      <div className="ambient-orb ambient-orb--3" />

      {/* Grid Overlay — viditeľná len v nexaverse (--grid-opacity) */}
      <div className="ambient-grid" />

      {/* WIKI4AI-208: vineta — stiahne okraje do --bg-0 a dodá hĺbku.
          Bez nej sa mlha rozleje do celej plochy a sklo stratí referenciu. */}
      <div className="ambient-vignette" aria-hidden="true" />
    </>
  );
}
