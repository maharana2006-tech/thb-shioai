import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { FiArrowLeft, FiArrowRight, FiCheckCircle } from 'react-icons/fi'
import OrderImportModal from './modals/OrderImportModal'
import { bulkBatchPath, bulkPaths } from '../routes/workspaceRoutes'

/**
 * Bulk Mailer → Import CSV / Excel: the importer on its own page (Upload →
 * check and fix rows → save). Saving puts the valid orders into a batch in
 * Import history; the operator can keep fixing the rest here, then go back
 * to Import history where the new batch is highlighted.
 */
export default function BulkImportPage() {
  const navigate = useNavigate()
  const [savedBatchId, setSavedBatchId] = useState<number | null>(null)
  const toHistory = (batchId: number | null) =>
    navigate(batchId ? bulkBatchPath(batchId) : bulkPaths.imports)

  return (
    <div className="space-y-4 pb-24">
      <div className="flex items-center justify-between gap-3 px-1 pt-1">
        <p className="text-[12.5px] text-slate-500">Upload a file, fix the rows that need it, and save — saved orders land in Import history, where you generate their labels.</p>
        {
          <button
            type="button"
            onClick={() => toHistory(null)}
            className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[13.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
          >
            <FiArrowLeft className="h-3.5 w-3.5" />
            Back to import history
          </button>
        }
      </div>

      {savedBatchId !== null ? (
        <div
          role="status"
          className="flex flex-wrap items-center justify-between gap-2 rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-3 text-[13px] text-emerald-800"
        >
          <span className="inline-flex items-center gap-2 font-semibold">
            <FiCheckCircle className="h-4 w-4" />
            Saved to batch #{savedBatchId} in Import history.
          </span>
          <button
            type="button"
            onClick={() => toHistory(savedBatchId)}
            className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[12.5px] font-semibold text-[#f4eede] hover:bg-[#412d15]"
          >
            Open batch #{savedBatchId}
            <FiArrowRight className="h-3.5 w-3.5" />
          </button>
        </div>
      ) : null}

      <OrderImportModal inline onImported={(batchId) => setSavedBatchId(batchId)} />
    </div>
  )
}
