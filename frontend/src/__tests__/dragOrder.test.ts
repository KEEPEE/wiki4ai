/**
 * WIKI4AI-187: tests for the pure drag&drop reorder logic.
 *
 * The anchor the UI sends must mean exactly what the optimistic move shows, so
 * every case here asserts both at once: the body of the reorder call AND the
 * resulting local order.
 */

import { describe, it, expect } from 'vitest'
import { computeReorderBody, moveWithinGroup, groupBounds } from '../utils/dragOrder'

type Row = { slug: string; pinned?: boolean }

const titles = (rows: Row[]) => rows.map((r) => r.slug)

/** Flat list, nothing pinned — the ordinary case. */
const flat: Row[] = [
  { slug: 'alpha' },
  { slug: 'bravo' },
  { slug: 'charlie' },
  { slug: 'delta' },
]

describe('dragOrder: groupBounds', () => {
  it('reports the contiguous block of each pin group', () => {
    const mixed: Row[] = [
      { slug: 'p1', pinned: true },
      { slug: 'p2', pinned: true },
      { slug: 'a1' },
      { slug: 'a2' },
    ]
    expect(groupBounds(mixed, true)).toEqual([0, 1])
    expect(groupBounds(mixed, false)).toEqual([2, 3])
  })

  it('returns an empty block for a group that does not exist', () => {
    expect(groupBounds(flat, true)).toEqual([0, -1])
  })
})

describe('dragOrder: computeReorderBody (unpinned list)', () => {
  it('moving down anchors after the row the item passed', () => {
    // alpha dropped onto delta -> alpha lands last, right after delta
    expect(computeReorderBody(flat, 0, 3)).toEqual({ afterSlug: 'delta' })
    expect(titles(moveWithinGroup(flat, 0, 3))).toEqual(['bravo', 'charlie', 'delta', 'alpha'])
  })

  it('moving up anchors after the previous row (taking the slot)', () => {
    // delta dropped onto bravo -> delta takes bravo's slot: [alpha, delta, bravo, charlie]
    expect(computeReorderBody(flat, 3, 1)).toEqual({ afterSlug: 'alpha' })
    expect(titles(moveWithinGroup(flat, 3, 1))).toEqual(['alpha', 'delta', 'bravo', 'charlie'])
  })

  it('one step down uses the target row itself as the anchor', () => {
    expect(computeReorderBody(flat, 0, 1)).toEqual({ afterSlug: 'bravo' })
    expect(titles(moveWithinGroup(flat, 0, 1))).toEqual(['bravo', 'alpha', 'charlie', 'delta'])
  })

  it('one step up anchors before the first row', () => {
    expect(computeReorderBody(flat, 1, 0)).toEqual({ beforeSlug: 'alpha' })
    expect(titles(moveWithinGroup(flat, 1, 0))).toEqual(['bravo', 'alpha', 'charlie', 'delta'])
  })

  it('dropping onto itself is a no-op (no API call)', () => {
    expect(computeReorderBody(flat, 2, 2)).toBeNull()
    expect(titles(moveWithinGroup(flat, 2, 2))).toEqual(titles(flat))
  })

  it('a drop past the end lands last', () => {
    expect(computeReorderBody(flat, 0, 99)).toEqual({ afterSlug: 'delta' })
    expect(computeReorderBody(flat, 2, -5)).toEqual({ beforeSlug: 'alpha' })
  })

  it('an item without a slug cannot be anchored', () => {
    const rows: ({ slug?: string })[] = [{}, { slug: 'bravo' }]
    expect(computeReorderBody(rows, 0, 1)).toBeNull()
  })
})

describe('dragOrder: pinned groups stay separate', () => {
  const mixed: Row[] = [
    { slug: 'pin-a', pinned: true },
    { slug: 'pin-b', pinned: true },
    { slug: 'free-a' },
    { slug: 'free-b' },
    { slug: 'free-c' },
  ]

  it('a pinned item dropped into the unpinned block is clamped to its own block', () => {
    // pin-a dragged onto free-c -> it can only move below pin-b
    expect(computeReorderBody(mixed, 0, 4)).toEqual({ afterSlug: 'pin-b' })
    expect(titles(moveWithinGroup(mixed, 0, 4))).toEqual(['pin-b', 'pin-a', 'free-a', 'free-b', 'free-c'])
  })

  it('an unpinned item dragged into the pinned block stays in the unpinned one', () => {
    // free-c dragged onto pin-a -> clamped to the top of the unpinned block
    expect(computeReorderBody(mixed, 4, 0)).toEqual({ beforeSlug: 'free-a' })
    expect(titles(moveWithinGroup(mixed, 4, 0))).toEqual(['pin-a', 'pin-b', 'free-c', 'free-a', 'free-b'])
  })

  it('pinned items reorder among themselves with a slug anchor', () => {
    expect(computeReorderBody(mixed, 1, 0)).toEqual({ beforeSlug: 'pin-a' })
    expect(titles(moveWithinGroup(mixed, 1, 0))).toEqual(['pin-b', 'pin-a', 'free-a', 'free-b', 'free-c'])
  })

  it('a single-item group can never move', () => {
    const onePinned: Row[] = [{ slug: 'only', pinned: true }, { slug: 'free' }]
    expect(computeReorderBody(onePinned, 0, 1)).toBeNull()
  })
})
