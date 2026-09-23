/**
 * F5-A — soft-delete + restore + empty-Trash actions for the imports
 * view of DataHistoryPage, extracted so the batch-mutation side effects
 * (busy spinner id, empty-Trash two-step confirm state, view-Trash
 * toggle) live in one place instead of scattered across the monolithic
 * component.
 *
 * <p>The hook takes the batch list and its setter from the parent so
 * the Trash-view toggle can reload independently from the main
 * history-list hook without duplicating the batches state.
 */
import { useState } from 'react'
import { notify } from '../utils/notify'
import { ApiError } from '../api/apiClient'
import {
  orderImportService,
  type ImportBatchSummary,
} from '../api/orderImportService'

export interface UseTrashActionsOptions {
  setBatches: React.Dispatch<React.SetStateAction<ImportBatchSummary[]>>
  /** Called after a delete / restore instead of dropping the batch from the list —
   *  e.g. a single-batch page re-reads the batch to show its new state. */
  onMoved?: (id: number) => void
}

export interface UseTrashActionsResult {
  trashBusyId: number | null
  confirmEmpty: boolean
  setConfirmEmpty: React.Dispatch<React.SetStateAction<boolean>>
  emptying: boolean
  handleEmptyTrash: () => Promise<void>
  handleDelete: (id: number, fileName?: string | null) => Promise<void>
  handleRestore: (
    id: number,
    fileName?: string | null,
    allowDuplicate?: boolean,
  ) => Promise<void>
}

export function useTrashActions({
  setBatches,
  onMoved,
}: UseTrashActionsOptions): UseTrashActionsResult {
  const [trashBusyId, setTrashBusyId] = useState<number | null>(null)
  const [confirmEmpty, setConfirmEmpty] = useState(false)
  const [emptying, setEmptying] = useState(false)

  /** PERMANENTLY delete every batch currently in Trash. */
  const handleEmptyTrash = async () => {
    setEmptying(true)
    try {
      const res = await orderImportService.emptyTrash()
      setBatches([])
      notify.success(res.message ?? 'Trash emptied.')
    } catch (e) {
      notify.apiError(e, 'Could not empty Trash.')
    } finally {
      setEmptying(false)
      setConfirmEmpty(false)
    }
  }

  /** Move a batch to Trash (soft delete). Recoverable from the Trash view. */
  const handleDelete = async (id: number, fileName?: string | null) => {
    setTrashBusyId(id)
    try {
      await orderImportService.deleteBatch(id)
      if (onMoved) onMoved(id)
      else setBatches((list) => list.filter((b) => b.id !== id))
      notify.success(
        `"${fileName || `Import #${id}`}" moved to Trash · restore it from Trash anytime.`,
      )
    } catch (e) {
      // "Still has live labels" is the rule working, not something going wrong.
      if (e instanceof ApiError && e.status === 409) {
        notify.info({ title: "Can't delete this import yet", body: e.message })
        return
      }
      notify.apiError(e, 'Could not delete import.')
    } finally {
      setTrashBusyId(null)
    }
  }

  /** Restore a batch from Trash back to the live Data History list. */
  const handleRestore = async (
    id: number,
    fileName?: string | null,
    allowDuplicate = false,
  ) => {
    setTrashBusyId(id)
    try {
      await orderImportService.restoreBatch(id, allowDuplicate)
      if (onMoved) onMoved(id)
      else setBatches((list) => list.filter((b) => b.id !== id))
      notify.success(`"${fileName || `Import #${id}`}" restored.`)
    } catch (e) {
      // Some orders are also in live imports — ask, like "Save anyway" does.
      if (
        !allowDuplicate &&
        e instanceof ApiError &&
        e.status === 409 &&
        e.errorCode === 'IMPORT_DUPLICATE_ORDERS'
      ) {
        setTrashBusyId(null)
        const ok = await notify.confirm(`${e.message}\n\nRestore anyway?`, {
          title: 'Orders already in Import history',
          confirmLabel: 'Restore anyway',
          cancelLabel: 'Cancel',
          danger: true,
        })
        if (ok) await handleRestore(id, fileName, true)
        return
      }
      notify.apiError(e, 'Could not restore import.')
    } finally {
      setTrashBusyId(null)
    }
  }

  return {
    trashBusyId,
    confirmEmpty,
    setConfirmEmpty,
    emptying,
    handleEmptyTrash,
    handleDelete,
    handleRestore,
  }
}
