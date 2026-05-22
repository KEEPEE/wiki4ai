/**
 * WikiLogo — Hexagon SVG Logo s Neon Glow & Gradient
 *
 * Inšpirované TemplateMo 603 - Nexaverse:
 *   - Hexagonálny tvar s vnútorným menším hexagónom a centrálnym kruhom
 *   - Gradient stroke: linear-gradient(#00f0ff → #ff00d4) pre vonkajší polygon
 *   - Inner polygon: 1.5px stroke pri 60% opacity
 *   - Vonkajší polygon: 2px stroke
 *   - Neon glow efekt: drop-shadow(0 0 20px var(--glow-cyan))
 *   - Hover: zosilnenie glow
 */

interface WikiLogoProps {
  size?: number;
}

export default function WikiLogo({ size = 90 }: WikiLogoProps) {
  const s = size; // SVG viewBox size
  const cx = s / 2; // center x
  const cy = s / 2; // center y
  const outerR = s * 0.42; // outer hexagon radius
  const innerR = s * 0.25; // inner hexagon radius

  // Helper: generate hexagon points from center and radius
  function hexPoints(centerX: number, centerY: number, radius: number): string {
    const points: [number, number][] = [];
    for (let i = 0; i < 6; i++) {
      const angle = (Math.PI / 180) * (60 * i - 30); // start at -30° for flat top
      const x = centerX + radius * Math.cos(angle);
      const y = centerY + radius * Math.sin(angle);
      points.push([x, y]);
    }
    return points.map(([x, y]) => `${x.toFixed(2)},${y.toFixed(2)}`).join(' ');
  }

  return (
    <div
      className="wiki-logo"
      style={{ width: s, height: s }}
    >
      <svg
        viewBox={`0 0 ${s} ${s}`}
        xmlns="http://www.w3.org/2000/svg"
        className="wiki-logo-svg"
      >
        {/* SVG Gradient Definition */}
        <defs>
          <linearGradient id="wikiLogoGrad" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="#00f0ff" />
            <stop offset="50%" stopColor="#ffffff" />
            <stop offset="100%" stopColor="#ff00d4" />
          </linearGradient>

          {/* Neon glow filter */}
          <filter id="wikiLogoGlow" x="-20%" y="-20%" width="140%" height="140%">
            <feDropShadow
              dx="0"
              dy="0"
              stdDeviation="6"
              floodColor="#00f0ff"
              floodOpacity="0.5"
            />
          </filter>

          {/* Stronger glow for hover */}
          <filter id="wikiLogoGlowHover" x="-30%" y="-30%" width="160%" height="160%">
            <feDropShadow
              dx="0"
              dy="0"
              stdDeviation="10"
              floodColor="#00f0ff"
              floodOpacity="0.7"
            />
          </filter>
        </defs>

        {/* Outer hexagon — gradient stroke */}
        <polygon
          points={hexPoints(cx, cy, outerR)}
          fill="none"
          stroke="url(#wikiLogoGrad)"
          strokeWidth="2"
          filter="url(#wikiLogoGlow)"
          className="wiki-logo-outer"
        />

        {/* Inner hexagon — 1.5px stroke at 60% opacity */}
        <polygon
          points={hexPoints(cx, cy, innerR)}
          fill="none"
          stroke="#00f0ff"
          strokeWidth="1.5"
          opacity="0.6"
          className="wiki-logo-inner"
        />

        {/* Center circle */}
        <circle
          cx={cx}
          cy={cy}
          r={s * 0.08}
          fill="#00f0ff"
          filter="url(#wikiLogoGlow)"
          className="wiki-logo-center"
        />

        {/* Center dot */}
        <circle
          cx={cx}
          cy={cy}
          r={s * 0.03}
          fill="#ffffff"
        />
      </svg>
    </div>
  );
}
