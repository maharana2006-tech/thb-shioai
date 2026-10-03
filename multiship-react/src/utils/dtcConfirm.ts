import type { DtcBatchStats } from '../api/dtcService'
import { notify } from './notify'

/**
 * Ask before Automatic label buys a whole batch — one click otherwise pays the
 * carrier for every pending line. Pending lines get a first label; failed lines
 * are retried on their existing order. Lines already labelled are skipped, so a
 * batch with nothing left says so instead of asking.
 *
 * @return true when the operator confirmed and the run should be queued
 */
export async function confirmBatchGenerate(
  b: Pick<DtcBatchStats, 'batchId' | 'tenantId' | 'pendingCount' | 'failedCount'>,
): Promise<boolean> {
  const fresh = b.pendingCount ?? 0
  const retries = b.failedCount ?? 0
  const n = fresh + retries
  if (n === 0) {
    notify.info(`Batch ${b.batchId} has nothing left to label — every line already has one.`)
    return false
  }
  const parts = [
    fresh ? `${fresh} new` : null,
    retries ? `${retries} retried after a failure` : null,
  ].filter(Boolean).join(', ')
  return notify.confirm(
    `Labels will be bought for ${n} line${n === 1 ? '' : 's'} of batch ${b.batchId} (${b.tenantId}) — ${parts}. `
      + 'The carrier bills each label; lines that already have one are skipped.',
    {
      title: `Generate ${n} label${n === 1 ? '' : 's'}?`,
      confirmLabel: `Generate ${n} label${n === 1 ? '' : 's'}`,
      cancelLabel: 'Not now',
    },
  )
}
