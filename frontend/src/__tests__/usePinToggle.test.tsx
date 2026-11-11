/**
 * WIKI4AI-186: the optimistic pin/unpin helper shared by the project and the
 * document surfaces. Tests the contract the pages rely on: immediate reorder,
 * server truth after the response, exact rollback on failure (403 included).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { usePinToggle, isForbiddenError } from '../hooks/usePinToggle'

interface Item {
  slug: string
  pinned?: boolean
  sortOrder?: number
}

const baseItems: Item[] = [
  { slug: 'alpha', pinned: false, sortOrder: 0 },
  { slug: 'beta', pinned: false, sortOrder: 1 },
]

function makeApi() {
  return { pin: vi.fn(), unpin: vi.fn() }
}

describe('usePinToggle (WIKI4AI-186)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('flips pinned and re-sorts before the request resolves (optimistic)', async () => {
    const api = makeApi()
    let resolvePin: (value: Item) => void = () => {}
    api.pin.mockImplementation(() => new Promise<Item>((res) => { resolvePin = res }))

    const { result } = renderHook(() => usePinToggle(baseItems, api))
    expect(result.current.list.map((i) => i.slug)).toEqual(['alpha', 'beta'])

    let done: Promise<Item> | undefined
    act(() => {
      done = result.current.togglePin(baseItems[1])
    })

    // Still pending — the UI already shows beta pinned and first.
    expect(result.current.list.map((i) => i.slug)).toEqual(['beta', 'alpha'])
    expect(result.current.list[0].pinned).toBe(true)
    expect(result.current.isPending('beta')).toBe(true)

    await act(async () => {
      resolvePin({ ...baseItems[1], pinned: true, sortOrder: -1 })
      await done
    })

    expect(api.pin).toHaveBeenCalledWith('beta')
    expect(result.current.list[0]).toMatchObject({ slug: 'beta', pinned: true, sortOrder: -1 })
    expect(result.current.isPending('beta')).toBe(false)
  })

  it('applies the server response instead of the optimistic guess', async () => {
    const api = makeApi()
    // The guess was "pinned", the server also renumbered sortOrder to -7.
    api.pin.mockResolvedValue({ ...baseItems[0], pinned: true, sortOrder: -7 })

    const { result } = renderHook(() => usePinToggle(baseItems, api))
    await act(async () => {
      await result.current.togglePin(baseItems[0])
    })

    expect(result.current.list[0]).toMatchObject({ slug: 'alpha', pinned: true, sortOrder: -7 })
    expect(result.current.list[1].slug).toBe('beta')
  })

  it('rolls back to the pre-click state when the request fails with 403', async () => {
    const api = makeApi()
    api.pin.mockRejectedValue(new Error('403: Chýba MANAGE oprávnenie'))

    const { result } = renderHook(() => usePinToggle(baseItems, api))
    await act(async () => {
      await expect(result.current.togglePin(baseItems[1])).rejects.toThrow('403')
    })

    expect(result.current.list.map((i) => i.slug)).toEqual(['alpha', 'beta'])
    expect(result.current.list[1].pinned).toBe(false)
    expect(result.current.isPending('beta')).toBe(false)
  })

  it('rolls back to the last known state, not to the stale base list', async () => {
    const api = makeApi()
    api.pin.mockResolvedValue({ ...baseItems[1], pinned: true, sortOrder: -1 })
    api.unpin.mockRejectedValue(new Error('403: Chýba MANAGE oprávnenie'))

    const { result } = renderHook(() => usePinToggle(baseItems, api))

    await act(async () => {
      await result.current.togglePin(baseItems[1])
    })
    expect(result.current.list[0].slug).toBe('beta')

    // Second click fails: the successful pin from the first click must survive.
    await act(async () => {
      await expect(result.current.togglePin(result.current.list[0])).rejects.toThrow('403')
    })

    expect(result.current.list[0]).toMatchObject({ slug: 'beta', pinned: true, sortOrder: -1 })
  })

  it('withOverrides carries the pin state into lists that keep their own order', async () => {
    const api = makeApi()
    api.pin.mockResolvedValue({ ...baseItems[1], pinned: true, sortOrder: -1 })

    const { result } = renderHook(() => usePinToggle(baseItems, api))
    await act(async () => {
      await result.current.togglePin(baseItems[1])
    })

    const searchResults = [
      { slug: 'alpha', pinned: false, sortOrder: 0 },
      { slug: 'beta', pinned: false, sortOrder: 1 },
    ]
    const patched = result.current.withOverrides(searchResults)
    // Relevance order is kept, only the pin state is carried over.
    expect(patched.map((i) => i.slug)).toEqual(['alpha', 'beta'])
    expect(patched[1].pinned).toBe(true)
  })

  it('lets the caller refetch after a success so the server order wins', async () => {
    const api = makeApi()
    const onSettled = vi.fn()
    api.pin.mockResolvedValue({ ...baseItems[1], pinned: true, sortOrder: -1 })

    const { result } = renderHook(() => usePinToggle(baseItems, api, { onSettled }))
    await act(async () => {
      await result.current.togglePin(baseItems[1])
    })
    expect(onSettled).toHaveBeenCalledTimes(1)

    // A failed pin must not trigger a refetch — nothing changed on the server.
    const onSettledAfterFailure = vi.fn()
    api.pin.mockRejectedValueOnce(new Error('403: Chýba MANAGE oprávnenie'))
    const second = renderHook(() => usePinToggle(baseItems, api, { onSettled: onSettledAfterFailure }))
    await act(async () => {
      await expect(second.result.current.togglePin(baseItems[0])).rejects.toThrow('403')
    })
    expect(onSettledAfterFailure).not.toHaveBeenCalled()
  })

  it('isForbiddenError matches the apiClient 403 message only', () => {
    expect(isForbiddenError(new Error('403: Chýba UPDATE oprávnenie'))).toBe(true)
    expect(isForbiddenError(new Error('404: Dokument nebol nájdený'))).toBe(false)
    expect(isForbiddenError(new Error('Network error'))).toBe(false)
    expect(isForbiddenError('boom')).toBe(false)
  })
})
