import { act, render, screen, cleanup } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ToastContainer from '../components/Toast'
import type { ToastItem } from '../components/Toast'

/**
 * WIKI4AI-195 (DIZ-4 item 7) — tri veci, ktoré boli na toastoch zlé:
 *  1. `role="alert"` + `aria-live="polite"` sa navzájom popierali
 *     (`alert` implikuje assertive).
 *  2. error toast zmizal po 5 s → WCAG 2.2.1, správu nebolo vrátiť.
 *  3. uzatváracie `×` malo `opacity: .6` → 2.59:1 (1.4.11).
 */
function item(type: ToastItem['type']): ToastItem[] {
  return [{ id: 1, message: `správa ${type}`, type }]
}

describe('Toast (WIKI4AI-195, DIZ-4 item 7)', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => {
    vi.useRealTimers()
    cleanup()
  })

  it('error je role="alert", success a info sú role="status"', () => {
    const { unmount } = render(
      <ToastContainer toasts={item('error')} onDismiss={() => {}} />,
    )
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expect(screen.queryByRole('status')).not.toBeInTheDocument()
    unmount()

    render(<ToastContainer toasts={item('success')} onDismiss={() => {}} />)
    expect(screen.getByRole('status')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('žiadny toast nemá explicitný aria-live — zdrojom pravdy je role', () => {
    for (const type of ['error', 'success', 'info'] as const) {
      const { unmount } = render(
        <ToastContainer toasts={item(type)} onDismiss={() => {}} />,
      )
      const el = screen.getByTestId(`toast-${type}`)
      expect(el, `aria-live na ${type}` ).not.toHaveAttribute('aria-live')
      unmount()
    }
  })

  it('error sa po 5 s NIZNE nezmizne (2.2.1), success áno', () => {
    const onDismiss = vi.fn()
    const { rerender } = render(
      <ToastContainer toasts={item('error')} onDismiss={onDismiss} />,
    )
    act(() => {
      vi.advanceTimersByTime(10_000)
    })
    expect(onDismiss).not.toHaveBeenCalled()

    rerender(<ToastContainer toasts={[]} onDismiss={onDismiss} />)
    cleanup()

    const onDismissSuccess = vi.fn()
    render(<ToastContainer toasts={item('success')} onDismiss={onDismissSuccess} />)
    act(() => {
      vi.advanceTimersByTime(5_000 + 400) // 5 s auto + 300 ms slide-out
    })
    expect(onDismissSuccess).toHaveBeenCalledWith(1)
  })

  it('error sa dá zavrieť tlačidlom s prístupným názvom', () => {
    const onDismiss = vi.fn()
    render(<ToastContainer toasts={item('error')} onDismiss={onDismiss} />)
    const btn = screen.getByRole('button', { name: /zavrie|dismiss|close/i })
    act(() => {
      btn.click()
    })
    act(() => {
      vi.advanceTimersByTime(400)
    })
    expect(onDismiss).toHaveBeenCalledWith(1)
  })

  it('accent lišta nemá hex farbu (DIZ-3 item 7)', () => {
    render(<ToastContainer toasts={item('success')} onDismiss={() => {}} />)
    const bar = document.querySelector('.toast-accent-bar') as HTMLElement
    expect(bar.getAttribute('style')).not.toMatch(/#[0-9a-f]{3,6}/i)
    expect(bar.getAttribute('style')).toContain('var(--color-success-text)')
  })
})
