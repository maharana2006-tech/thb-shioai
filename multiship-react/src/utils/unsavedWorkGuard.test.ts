import { afterEach, describe, expect, it, vi } from 'vitest'
import { canLeaveUnsavedWork, registerUnsavedWorkGuard } from './unsavedWorkGuard'

afterEach(() => {
  registerUnsavedWorkGuard(null)
})

describe('unsavedWorkGuard', () => {
  it('no guard → safe to leave', async () => {
    expect(await canLeaveUnsavedWork()).toBe(true)
  })

  it('guard says Stay → canLeave is false', async () => {
    registerUnsavedWorkGuard(() => Promise.resolve(false))
    expect(await canLeaveUnsavedWork()).toBe(false)
  })

  it('guard says Leave → canLeave is true', async () => {
    registerUnsavedWorkGuard(() => Promise.resolve(true))
    expect(await canLeaveUnsavedWork()).toBe(true)
  })

  it('a thrown guard mustn\'t trap the user', async () => {
    registerUnsavedWorkGuard(() => Promise.reject(new Error('boom')))
    expect(await canLeaveUnsavedWork()).toBe(true)
  })

  it('unregister clears the guard', async () => {
    const guard = vi.fn().mockResolvedValue(false)
    registerUnsavedWorkGuard(guard)
    expect(await canLeaveUnsavedWork()).toBe(false)
    registerUnsavedWorkGuard(null)
    expect(await canLeaveUnsavedWork()).toBe(true)
    expect(guard).toHaveBeenCalledTimes(1)
  })
})
