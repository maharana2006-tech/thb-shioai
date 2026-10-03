import { beforeEach, describe, expect, it, vi } from 'vitest'

const confirm = vi.fn()
const info = vi.fn()
vi.mock('./notify', () => ({ notify: { confirm: (...a: unknown[]) => confirm(...a), info: (...a: unknown[]) => info(...a) } }))

import { confirmBatchGenerate } from './dtcConfirm'

const batch = (pendingCount: number, failedCount: number) => ({ batchId: 245, tenantId: 'ARHDEV', pendingCount, failedCount })

describe('confirmBatchGenerate', () => {
  beforeEach(() => { confirm.mockReset(); info.mockReset() })

  it('asks before buying, counting new lines and retries', async () => {
    confirm.mockResolvedValue(true)
    await expect(confirmBatchGenerate(batch(3, 2))).resolves.toBe(true)
    const [message, opts] = confirm.mock.calls[0]
    expect(message).toContain('Labels will be bought for 5 lines of batch 245 (ARHDEV) — 3 new, 2 retried after a failure')
    expect(opts).toMatchObject({ title: 'Generate 5 labels?', confirmLabel: 'Generate 5 labels', cancelLabel: 'Not now' })
  })

  it('does not queue the run when the operator says Not now', async () => {
    confirm.mockResolvedValue(false)
    await expect(confirmBatchGenerate(batch(1, 0))).resolves.toBe(false)
    expect(confirm.mock.calls[0][1]).toMatchObject({ title: 'Generate 1 label?' })
  })

  it('says so instead of asking when every line already has a label', async () => {
    await expect(confirmBatchGenerate(batch(0, 0))).resolves.toBe(false)
    expect(confirm).not.toHaveBeenCalled()
    expect(info).toHaveBeenCalledWith(expect.stringContaining('nothing left to label'))
  })
})
