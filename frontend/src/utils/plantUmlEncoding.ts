/**
 * PlantUML diagram source encoding for the kroki API (WIKI4AI-63).
 *
 * Kroki accepts `GET /plantuml/svg/<encoded>` where `<encoded>` is the diagram
 * text compressed with zlib deflate and encoded as base64url (no padding).
 *
 * NOTE (verified empirically against live kroki.io, 2026-09-20): kroki expects
 * the zlib-wrapped deflate stream (`pako.deflate`), NOT raw deflate
 * (`pako.deflateRaw` — that one is rejected with a syntax error).
 */

// pako 3.x uses named exports (no default export).
import { deflate } from 'pako';

/** Base URL path of the kroki API as exposed by nginx (same origin). */
export const PLANTUML_API_BASE = '/plantuml';

/**
 * Encode PlantUML source text for the kroki API.
 * zlib deflate → base64url (alphabet A–Z a–z 0–9 - _, no '=' padding).
 */
export function encodePlantUml(code: string): string {
  const deflated = deflate(new TextEncoder().encode(code));
  let binary = '';
  for (let i = 0; i < deflated.length; i++) {
    binary += String.fromCharCode(deflated[i]);
  }
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

/** Build the full kroki SVG URL (same-origin, proxied by nginx) for a PlantUML source text. */
export function plantUmlSvgUrl(code: string): string {
  return `${PLANTUML_API_BASE}/svg/${encodePlantUml(code)}`;
}
