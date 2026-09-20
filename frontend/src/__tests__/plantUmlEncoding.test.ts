/**
 * Tests for the PlantUML kroki encoding (WIKI4AI-63).
 *
 * The encoding is: zlib deflate (pako.deflate) → base64url without padding.
 * Verified against live kroki.io on 2026-09-20: the fixed vector below returns
 * HTTP 200 image/svg+xml, while pako.deflateRaw (raw deflate) is rejected with
 * a syntax error — so the zlib-wrapped stream is what kroki expects.
 */

import { describe, it, expect } from 'vitest';
import { deflateSync } from 'node:zlib';
import { encodePlantUml, plantUmlSvgUrl, PLANTUML_API_BASE } from '../utils/plantUmlEncoding';

/** Independent reference implementation: node:zlib + Buffer base64url. */
function referenceEncode(code: string): string {
  return deflateSync(new TextEncoder().encode(code)).toString('base64url');
}

describe('encodePlantUml', () => {
  it('matches the independent node:zlib reference implementation', () => {
    const samples = [
      '@startuml\nBob -> Alice : Hi\n@enduml',
      '@startuml\nclass A {\n  +int x\n}\nclass B\nA <|-- B\n@enduml',
      'diagram s s s s', // length that produces base64 padding in the reference
      '',
    ];
    for (const sample of samples) {
      expect(encodePlantUml(sample)).toBe(referenceEncode(sample));
    }
  });

  it('produces a fixed, kroki-verified vector', () => {
    // Verified 2026-09-20: GET https://kroki.io/plantuml/svg/<this> → HTTP 200 SVG.
    expect(encodePlantUml('@startuml\nBob -> Alice : Hi\n@enduml')).toBe(
      'eJxzKC5JLCopzc3hcspPUtC1U3DMyUxOVbBS8MjkckjNSynNzQEA0CwLXQ',
    );
  });

  it('uses only the base64url alphabet and no padding', () => {
    const encoded = encodePlantUml('@startuml\nactor A\nA --> "Use case: with colon"\n@enduml');
    expect(encoded).toMatch(/^[A-Za-z0-9_-]+$/);
    expect(encoded).not.toContain('=');
  });

  it('is deterministic for the same input', () => {
    const code = '@startuml\ncomponent A\nA --> B\n@enduml';
    expect(encodePlantUml(code)).toBe(encodePlantUml(code));
  });

  it('produces different output for different inputs', () => {
    expect(encodePlantUml('@startuml\nA\n@enduml')).not.toBe(
      encodePlantUml('@startuml\nB\n@enduml'),
    );
  });
});

describe('plantUmlSvgUrl', () => {
  it('builds the same-origin kroki SVG URL (nginx proxy path)', () => {
    const url = plantUmlSvgUrl('@startuml\nBob -> Alice : Hi\n@enduml');
    expect(url).toBe(
      `${PLANTUML_API_BASE}/svg/eJxzKC5JLCopzc3hcspPUtC1U3DMyUxOVbBS8MjkckjNSynNzQEA0CwLXQ`,
    );
    expect(url.startsWith('/plantuml/svg/')).toBe(true);
  });
});
