import { FiHome, FiX } from 'react-icons/fi'
import type { Warehouse } from '../../api/warehouseService'
import AttachClientsStep from './AttachClientsStep'

/**
 * Standalone "attach clients to warehouse" modal — used from the warehouse
 * list row menu. The post-create step inside WarehouseEditorModal renders the
 * same body inline; both share AttachClientsStep.
 */
export default function AttachClientsModal({
  warehouse,
  onClose,
}: {
  warehouse: Warehouse
  onClose: () => void
}) {
  return (
    <div
      className="fixed inset-0 z-[55] flex items-center justify-center bg-slate-950/50 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-labelledby="attach-clients-title"
    >
      <div className="w-full max-w-2xl overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]">
        <header className="flex items-center justify-between border-b border-slate-100 px-5 py-4">
          <div className="flex items-center gap-2.5">
            <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-[#412d15]/10 text-[#412d15]">
              <FiHome className="h-4 w-4" />
            </span>
            <div>
              <h2 id="attach-clients-title" className="text-[15px] font-semibold text-slate-950">
                Attach {warehouse.code} to clients
              </h2>
              <p className="mt-0.5 text-[11.5px] text-slate-500">
                Pick the clients that should see this warehouse. Existing attachments are unaffected.
              </p>
            </div>
          </div>
          <button
            type="button"
            aria-label="Close"
            onClick={onClose}
            className="rounded-lg border border-transparent p-1.5 text-slate-400 transition hover:border-slate-200 hover:text-slate-600"
          >
            <FiX className="h-4 w-4" />
          </button>
        </header>
        <AttachClientsStep
          warehouse={warehouse}
          onDone={onClose}
          skipLabel="Cancel"
          confirmLabel="Attach"
        />
      </div>
    </div>
  )
}
