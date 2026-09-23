/**
 * WIKI4AI-73: translation key parity check.
 * Every key in en.json must exist in sk.json and vice versa — a missing
 * counterpart means a user sees the raw key (or fallback) in one locale,
 * which is exactly the mixed-language UI this story eliminates.
 */
import { describe, it, expect } from 'vitest';
import en from '../locales/en.json';
import sk from '../locales/sk.json';

type Dict = Record<string, unknown>;

/** Recursively collect all leaf keys of a nested translation object. */
function collectKeys(obj: Dict, prefix = ''): string[] {
  const keys: string[] = [];
  for (const [key, value] of Object.entries(obj)) {
    const path = prefix ? `${prefix}.${key}` : key;
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
      keys.push(...collectKeys(value as Dict, path));
    } else {
      keys.push(path);
    }
  }
  return keys;
}

describe('i18n locale parity', () => {
  const enKeys = collectKeys(en as Dict).sort();
  const skKeys = collectKeys(sk as Dict).sort();

  it('catalogs are non-empty', () => {
    expect(enKeys.length).toBeGreaterThan(0);
    expect(skKeys.length).toBeGreaterThan(0);
  });

  it('every en key has an sk counterpart', () => {
    const skSet = new Set(skKeys);
    const missingInSk = enKeys.filter((k) => !skSet.has(k));
    expect(missingInSk).toEqual([]);
  });

  it('every sk key has an en counterpart', () => {
    const enSet = new Set(enKeys);
    const missingInEn = skKeys.filter((k) => !enSet.has(k));
    expect(missingInEn).toEqual([]);
  });

  it('no translation value is empty (would render a blank UI element)', () => {
    const empty: string[] = [];
    const walk = (obj: Dict, prefix = '') => {
      for (const [key, value] of Object.entries(obj)) {
        const path = prefix ? `${prefix}.${key}` : key;
        if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
          walk(value as Dict, path);
        } else if (typeof value === 'string' && value.trim() === '') {
          empty.push(path);
        }
      }
    };
    walk(en as Dict);
    walk(sk as Dict);
    expect(empty).toEqual([]);
  });
});
