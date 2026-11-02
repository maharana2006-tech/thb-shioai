import { afterEach, describe, expect, it, vi } from 'vitest'
import { act, cleanup, renderHook } from '@testing-library/react'
import { useUnsavedWorkGuard } from './useUnsavedWorkGuard'
import { canLeaveUnsavedWork } from '../utils/unsavedWorkGuard'

const confirmMock = vi.fn()
vi.mock('../utils/notify', () => ({ notify: { confirm: (...a: unknown[]) => confirmMock(...a) } }))

afterEach(() => { cleanup(); vi.restoreAllMocks(); confirmMock.mockReset() })

const pop = () => act(async () => { window.dispatchEvent(new PopStateEvent('popstate')); await Promise.resolve() })

describe('useUnsavedWorkGuard', () => {
  it('clean page: nothing registered, Back untouched', async () => {
    const back = vi.spyOn(window.history, 'back').mockImplementation(() => {})
    renderHook(() => useUnsavedWorkGuard(false, 'msg', 'Leave?'))
    expect(window.history.state?.unsavedWorkSentinel).toBeUndefined()
    expect(await canLeaveUnsavedWork()).toBe(true)
    await pop()
    expect(back).not.toHaveBeenCalled()
  })

  it('dirty page: in-app navigation asks, Back asks and Stay keeps the page', async () => {
    const back = vi.spyOn(window.history, 'back').mockImplementation(() => {})
    confirmMock.mockResolvedValue(false)
    const { unmount } = renderHook(() => useUnsavedWorkGuard(true, 'Lose edits?', 'Leave client editor?'))
    expect(window.history.state?.unsavedWorkSentinel).toBe(true)

    expect(await canLeaveUnsavedWork()).toBe(false)
    expect(confirmMock).toHaveBeenLastCalledWith('Lose edits?', expect.objectContaining({ title: 'Leave client editor?' }))

    await pop()
    expect(back).not.toHaveBeenCalled()
    expect(window.history.state?.unsavedWorkSentinel).toBe(true)

    confirmMock.mockResolvedValue(true)
    await pop()
    expect(back).toHaveBeenCalledTimes(1)
    unmount()
  })

  it('page becomes clean again (saved): guard and sentinel are dropped', async () => {
    const back = vi.spyOn(window.history, 'back').mockImplementation(() => {})
    const { rerender } = renderHook(({ dirty }) => useUnsavedWorkGuard(dirty, 'm', 't'), { initialProps: { dirty: true } })
    rerender({ dirty: false })
    expect(await canLeaveUnsavedWork()).toBe(true)
    expect(back).toHaveBeenCalledTimes(1)
  })
})
