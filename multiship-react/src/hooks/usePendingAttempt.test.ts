import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

vi.mock('../api/orderService', () => ({ orderService: { getAttempt: vi.fn() } }))
vi.mock('../utils/notify', () => ({ notify: { success: vi.fn(), error: vi.fn(), info: vi.fn() } }))

import { orderService } from '../api/orderService'
import { notify } from '../utils/notify'
import { ApiError } from '../api/apiClient'
import { forgetPendingAttempt, readPendingAttempt, recoverPendingAttempt, rememberPendingAttempt, settlePendingAttempt } from './usePendingAttempt'

const getAttempt = vi.mocked(orderService.getAttempt)
const navigate = vi.fn()

describe('pending attempt recovery', () => {
  beforeEach(() => { sessionStorage.clear(); vi.clearAllMocks() })
  afterEach(() => forgetPendingAttempt())

  it('remembers one key and forgets it after it settles or expires', () => {
    rememberPendingAttempt('k1', null)
    expect(readPendingAttempt()?.key).toBe('k1')
    expect(readPendingAttempt(Date.now() + 16 * 60 * 1000)).toBeNull()
    rememberPendingAttempt('k2', 900001)
    forgetPendingAttempt()
    expect(readPendingAttempt()).toBeNull()
  })

  it('a server answer settles the attempt; a network failure or abort keeps it for recovery', () => {
    rememberPendingAttempt('k1', null)
    settlePendingAttempt(new Error('Network Error'))
    expect(readPendingAttempt()?.key).toBe('k1')
    settlePendingAttempt(new ApiError('Unprocessable', 422, { errors: { field: 'sender' } }))
    expect(readPendingAttempt()).toBeNull()
  })

  it('does nothing when no attempt is pending', async () => {
    await recoverPendingAttempt(navigate)
    expect(getAttempt).not.toHaveBeenCalled()
  })

  it('DONE opens the label and clears the key', async () => {
    rememberPendingAttempt('k1', null)
    getAttempt.mockResolvedValue({ data: { state: 'DONE', orderNo: 900065 } } as never)
    await recoverPendingAttempt(navigate)
    expect(getAttempt).toHaveBeenCalledWith('k1')
    expect(navigate).toHaveBeenCalledWith('/label/900065')
    expect(notify.success).toHaveBeenCalled()
    expect(readPendingAttempt()).toBeNull()
  })

  it('PURCHASING warns and keeps the key for the next reload', async () => {
    rememberPendingAttempt('k1', null)
    getAttempt.mockResolvedValue({ data: { state: 'PURCHASING', orderNo: null } } as never)
    await recoverPendingAttempt(navigate)
    expect(notify.info).toHaveBeenCalled()
    expect(navigate).not.toHaveBeenCalled()
    expect(readPendingAttempt()?.key).toBe('k1')
  })

  it('FAILED / PARTIAL surface the server message and clear the key', async () => {
    rememberPendingAttempt('k1', null)
    getAttempt.mockResolvedValue({ data: { state: 'PARTIAL', orderNo: 900066, errorMessage: 'Box 2 rejected' } } as never)
    await recoverPendingAttempt(navigate)
    expect(notify.error).toHaveBeenCalledWith(expect.objectContaining({ body: 'Box 2 rejected' }))
    expect(readPendingAttempt()).toBeNull()
  })

  it('a double mount (StrictMode) looks the attempt up once', async () => {
    rememberPendingAttempt('k1', null)
    getAttempt.mockResolvedValue({ data: { state: 'DONE', orderNo: 900065 } } as never)
    await Promise.all([recoverPendingAttempt(navigate), recoverPendingAttempt(navigate)])
    expect(getAttempt).toHaveBeenCalledTimes(1)
    expect(notify.success).toHaveBeenCalledTimes(1)
  })

  it('an unknown key (404) is dropped silently', async () => {
    rememberPendingAttempt('k1', null)
    getAttempt.mockRejectedValue(new Error('404'))
    await recoverPendingAttempt(navigate)
    expect(notify.error).not.toHaveBeenCalled()
    expect(readPendingAttempt()).toBeNull()
  })
})
