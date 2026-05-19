/**
 * Tests for generateSlug utility function.
 * Ensures frontend slug generation matches backend's Document.generateSlug() logic.
 */

import { describe, it, expect } from 'vitest'
import { generateSlug } from '../utils/slugify'

describe('generateSlug', () => {
  it('should convert simple title to lowercase with dashes', () => {
    expect(generateSlug('My Document')).toBe('my-document')
    expect(generateSlug('Hello World')).toBe('hello-world')
    expect(generateSlug('test doc')).toBe('test-doc')
  })

  it('should handle empty and null titles', () => {
    expect(generateSlug('')).toBe('')
    expect(generateSlug(null as any)).toBe('')
    expect(generateSlug(undefined as any)).toBe('')
  })

  it('should strip non-ASCII characters including em dash (—)', () => {
    // This is the key bug fix — em dashes and other non-ASCII chars must be stripped
    expect(generateSlug('Index — Wiki4AI Overview')).toBe('index-wiki4ai-overview')
    expect(generateSlug('Title with … ellipsis')).toBe('title-with-ellipsis')
    expect(generateSlug('Special © characters ® here')).toBe('special-characters-here')
  })

  it('should transliterate Slovak/Czech diacritics', () => {
    expect(generateSlug('Úvod do Wiki4AI')).toBe('uvod-do-wiki4ai')
    expect(generateSlug('Čeština slová')).toBe('cestina-slova')
    expect(generateSlug('Ľubovňa')).toBe('lubovna')
    expect(generateSlug('Žilina')).toBe('zilina')
  })

  it('should normalize multiple spaces and dashes', () => {
    expect(generateSlug('  Multiple   Spaces  ')).toBe('multiple-spaces')
    expect(generateSlug('---already---dashed---')).toBe('already-dashed')
    expect(generateSlug('--leading-trailing--')).toBe('leading-trailing')
  })

  it('should handle mixed content with diacritics and special chars', () => {
    expect(generateSlug('Príklad — Test Článok')).toBe('priklad-test-clanok')
    expect(generateSlug('Úžitočné informácie o systéme')).toBe('uzitocne-informacie-o-systeme')
  })

  it('should preserve alphanumeric characters and existing dashes', () => {
    expect(generateSlug('api-v2-test')).toBe('api-v2-test')
    // Dots are stripped (not converted to dashes) - matches backend behavior
    expect(generateSlug('My-Document.md')).toBe('my-documentmd')
  })
})
