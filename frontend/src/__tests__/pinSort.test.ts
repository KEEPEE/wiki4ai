/**
 * WIKI4AI-186: pinned-first ordering helper.
 *
 * The server orders its lists by its own key (created_desc / updated_desc) with
 * `pinned DESC` in front, so the client may only float pinned entries — it must
 * not invent an order. These tests hold that line, and cover the opaque
 * (possibly negative) `sortOrder` used by the manual comparator.
 */

import { describe, it, expect } from 'vitest'
import { comparePinned, pinnedFirst, type Pinable } from '../utils/pinSort'

describe('pinnedFirst (WIKI4AI-186)', () => {
  it('floats pinned entries to the front', () => {
    const items = [
      { slug: 'a', pinned: false },
      { slug: 'b', pinned: true },
      { slug: 'c', pinned: false },
    ]
    expect(pinnedFirst(items).map((i) => i.slug)).toEqual(['b', 'a', 'c'])
  })

  it('keeps the server order inside both groups (no re-sorting by sortOrder)', () => {
    // A list as delivered by GET /projects (created_desc, pinned first).
    const serverOrder = [
      { slug: 'pinned-newest', pinned: true, sortOrder: -4 },
      { slug: 'pinned-oldest', pinned: true, sortOrder: -1 },
      { slug: 'fresh', pinned: false, sortOrder: -3 },
      { slug: 'middle', pinned: false, sortOrder: 0 },
      { slug: 'oldest', pinned: false, sortOrder: 7 },
    ]
    expect(pinnedFirst(serverOrder).map((i) => i.slug)).toEqual([
      'pinned-newest',
      'pinned-oldest',
      'fresh',
      'middle',
      'oldest',
    ])
  })

  it('is a no-op on an already correct server list', () => {
    const serverOrder = [
      { slug: 'pinned', pinned: true, sortOrder: -1 },
      { slug: 'one', pinned: false, sortOrder: 0 },
      { slug: 'two', pinned: false, sortOrder: 1 },
    ]
    expect(pinnedFirst(serverOrder).map((i) => i.slug)).toEqual(['pinned', 'one', 'two'])
  })

  it('does not mutate the input array', () => {
    const items = [
      { slug: 'a', pinned: false, sortOrder: 0 },
      { slug: 'b', pinned: true, sortOrder: -1 },
    ]
    const sorted = pinnedFirst(items)
    expect(items.map((i) => i.slug)).toEqual(['a', 'b'])
    expect(sorted).not.toBe(items)
  })

  it('handles items without any pin metadata', () => {
    const items: (Pinable & { slug: string })[] = [{ slug: 'x' }, { slug: 'y' }]
    expect(pinnedFirst(items).map((i) => i.slug)).toEqual(['x', 'y'])
  })
})

describe('comparePinned — manual order (used by WIKI4AI-187)', () => {
  it('orders pinned first, then by the opaque sortOrder including negatives', () => {
    const items = [
      { slug: 'mid', pinned: false, sortOrder: 0 },
      { slug: 'top', pinned: true, sortOrder: -3 },
      { slug: 'pinned-later', pinned: true, sortOrder: -1 },
      { slug: 'last', pinned: false, sortOrder: 7 },
    ]
    expect([...items].sort(comparePinned).map((i) => i.slug)).toEqual([
      'top',
      'pinned-later',
      'mid',
      'last',
    ])
  })

  it('treats a missing sortOrder as the neutral 0', () => {
    expect(comparePinned({}, { sortOrder: 5 })).toBeLessThan(0)
    expect(comparePinned({ pinned: true }, { pinned: false })).toBeLessThan(0)
    expect(comparePinned({ pinned: false }, { pinned: true })).toBeGreaterThan(0)
    expect(comparePinned({ sortOrder: 1 }, { sortOrder: 1 })).toBe(0)
  })
})
