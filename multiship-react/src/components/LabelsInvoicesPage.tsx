import { FiArrowLeft, FiFileText } from 'react-icons/fi'
import { useNavigate } from 'react-router-dom'
import OrderDocumentsTable from './OrderDocumentsTable'
import { bulkPaths } from '../routes/workspaceRoutes'

/** Labels & Invoices — every labelled order's label, commercial invoice and billing statement. Opened from Bulk Mailer. */
export default function LabelsInvoicesPage() {
  const navigate = useNavigate()
  return (
    <div className="space-y-3 pb-8">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-1 pt-1">
        <h2
          className="mr-auto flex items-center gap-2 text-[17px] font-semibold tracking-tight text-[#1f150c]"
          title="Every labelled order — download its label (PDF or ZPL), commercial invoice and billing statement."
        >
          <span className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-[#1f150c] text-[#f4eede] shadow-sm" aria-hidden="true">
            <FiFileText className="h-3.5 w-3.5" />
          </span>
          Labels &amp; Invoices
        </h2>
        <button
          type="button"
          onClick={() => navigate(bulkPaths.imports)}
          className="inline-flex items-center gap-1.5 rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
        >
          <FiArrowLeft className="h-3.5 w-3.5" />
          Bulk Mailer
        </button>
      </div>
      <OrderDocumentsTable />
    </div>
  )
}
