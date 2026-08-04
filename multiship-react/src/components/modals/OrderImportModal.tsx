import { useState } from 'react'
import {
  FiAlertCircle,
  FiCheckCircle,
  FiDownload,
  FiFileText,
  FiLoader,
  FiUpload,
  FiX,
} from 'react-icons/fi'
import {
  orderImportService,
  type OrderImportPreview,
  type OrderImportRow,
} from '../../api/orderImportService'
import { notify } from '../../utils/notify'

/**
 * Sprint 40 — CSV / XLSX order import modal. Three steps:
 *   1. File picker + template download.
 *   2. Preview table with per-row status.
 *   3. Commit summary.
 */
export interface OrderImportModalProps {
  onClose: () => void
}

export default function OrderImportModal({ onClose }: OrderImportModalProps) {
  const [file, setFile] = useState<File | null>(null)
  const [preview, setPreview] = useState<OrderImportPreview | null>(null)
  const [committedSummary, setCommittedSummary] = useState<OrderImportPreview | null>(null)
  const [uploading, setUploading] = useState(false)
  const [committing, setCommitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  /** Sprint 48 (revised) — the template is universal now. One workbook
   *  covers every client, and each row picks its own clientCode / carrier /
   *  account inside the workbook via cascading dropdowns. The per-account
   *  picker that was here previously is redundant. */
  const [downloadingXlsx, setDownloadingXlsx] = useState(false)

  const downloadXlsx = async () => {
    if (downloadingXlsx) return
    setDownloadingXlsx(true)
    try {
      await orderImportService.downloadXlsxTemplate(null)
    } catch (e) {
      notify.error(e instanceof Error ? e.message : 'Template download failed.')
    } finally {
      setDownloadingXlsx(false)
    }
  }

  const submitPreview = async () => {
    if (!file) return
    setUploading(true)
    setError(null)
    try {
      const response = await orderImportService.preview(file)
      if (response.status === 'success' && response.data) {
        setPreview(response.data)
      } else {
        setError(response.message ?? 'Preview failed.')
        notify.error(response.message ?? 'Preview failed.')
      }
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Upload failed.'
      setError(msg)
      notify.error(msg)
    } finally {
      setUploading(false)
    }
  }

  const submitCommit = async () => {
    if (!preview) return
    setCommitting(true)
    try {
      const response = await orderImportService.commit(preview.rows)
      if (response.status === 'success' && response.data) {
        setCommittedSummary(response.data)
        notify.success(response.message ?? 'Committed.')
      } else {
        notify.error(response.message ?? 'Commit failed.')
      }
    } catch (e) {
      notify.error(e instanceof Error ? e.message : 'Commit failed.')
    } finally {
      setCommitting(false)
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Import orders"
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4"
      onClick={onClose}
    >
      <div
        className="flex h-[min(760px,92vh)] w-full max-w-[900px] flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <p className="inline-flex items-center gap-1 text-[10.5px] font-bold uppercase tracking-[0.16em] text-slate-500">
              <FiFileText className="h-3 w-3" /> Import
            </p>
            <h3 className="mt-1 text-[15px] font-semibold text-slate-950">
              Import orders from CSV / Excel
            </h3>
            <p className="mt-1 text-[11.5px] text-slate-500">
              Upload a .csv or .xlsx with one order per row. Review the preview before committing.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 transition hover:bg-slate-50"
          >
            <FiX className="h-3.5 w-3.5" />
          </button>
        </div>

        <div className="flex-1 space-y-4 overflow-y-auto px-5 py-4">
          {!preview && !committedSummary ? (
            <UploadStep
              file={file}
              onFileChange={setFile}
              onSubmit={() => void submitPreview()}
              uploading={uploading}
            />
          ) : null}
          {preview && !committedSummary ? (
            <PreviewStep preview={preview} />
          ) : null}
          {committedSummary ? (
            <CommittedStep summary={committedSummary} />
          ) : null}

          {error ? (
            <div className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-[12px] text-rose-800">
              <FiAlertCircle className="mr-1.5 inline h-3.5 w-3.5" />
              {error}
            </div>
          ) : null}
        </div>

        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-slate-100 px-5 py-3">
          {/* Sprint 48 (revised) — universal template.
                * One workbook covers every client. Each row picks its own
                  clientCode + carrier + account via cascading dropdowns
                  in the workbook (no per-download scoping).
                * XLSX button hits the auth-gated /template.xlsx endpoint
                  via fetch + Blob download so the Bearer token is attached.
                * CSV link stays for operators who want the flat public
                  template (no validation, no dropdowns). */}
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => void downloadXlsx()}
              disabled={downloadingXlsx}
              className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-2.5 py-1.5 text-[11.5px] font-semibold text-slate-700 transition hover:bg-slate-50 disabled:opacity-50"
              title="Download the universal .xlsx template — cascading dropdowns pick per-row Client / Carrier / Account inside the workbook."
            >
              {downloadingXlsx ? <FiLoader className="h-3 w-3 animate-spin" /> : <FiDownload className="h-3 w-3" />}
              Download XLSX template
            </button>
            <a
              href={orderImportService.templateUrl()}
              className="inline-flex items-center gap-1.5 text-[11px] font-semibold text-slate-500 hover:text-slate-950"
              title="Flat CSV template — no validation, no dropdowns."
            >
              <FiDownload className="h-3 w-3" />
              CSV
            </a>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              className="inline-flex items-center rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-700 hover:bg-slate-50"
            >
              Close
            </button>
            {preview && !committedSummary ? (
              <button
                type="button"
                onClick={() => void submitCommit()}
                disabled={committing || preview.validRows === 0}
                className="inline-flex items-center gap-1.5 rounded-lg bg-slate-950 px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-slate-800 disabled:opacity-40"
              >
                <FiCheckCircle className="h-3 w-3" />
                {committing ? 'Committing…' : `Commit ${preview.validRows} valid row(s)`}
              </button>
            ) : null}
          </div>
        </div>
      </div>
    </div>
  )
}

function UploadStep({
  file,
  onFileChange,
  onSubmit,
  uploading,
}: {
  file: File | null
  onFileChange: (f: File | null) => void
  onSubmit: () => void
  uploading: boolean
}) {
  return (
    <div className="rounded-2xl border border-dashed border-slate-300 bg-slate-50/60 p-5 text-center">
      <FiUpload className="mx-auto h-6 w-6 text-slate-400" />
      <p className="mt-2 text-[12.5px] font-semibold text-slate-950">
        Pick a .csv or .xlsx file
      </p>
      <p className="mt-1 text-[11px] text-slate-500">
        Required columns: recipientName, addressLine1, city, postalCode, countryCode, weight.
      </p>
      <input
        type="file"
        accept=".csv,.xlsx,.txt"
        onChange={(e) => onFileChange(e.target.files?.[0] ?? null)}
        className="mx-auto mt-3 block text-[11px] text-slate-700"
      />
      {file ? (
        <p className="mt-2 font-mono text-[11px] text-slate-600">{file.name}</p>
      ) : null}
      <button
        type="button"
        onClick={onSubmit}
        disabled={!file || uploading}
        className="mt-3 inline-flex items-center gap-1.5 rounded-lg bg-slate-950 px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-slate-800 disabled:opacity-40"
      >
        <FiUpload className="h-3 w-3" />
        {uploading ? 'Uploading…' : 'Preview'}
      </button>
    </div>
  )
}

function PreviewStep({ preview }: { preview: OrderImportPreview }) {
  return (
    <>
      <div className="flex items-center gap-3 text-[11.5px]">
        <span className="rounded-full bg-slate-100 px-2 py-0.5 font-semibold text-slate-700">
          {preview.totalRows} total
        </span>
        <span className="rounded-full bg-emerald-100 px-2 py-0.5 font-semibold text-emerald-800">
          ✓ {preview.validRows} valid
        </span>
        {preview.invalidRows > 0 ? (
          <span className="rounded-full bg-rose-100 px-2 py-0.5 font-semibold text-rose-800">
            ✗ {preview.invalidRows} with errors
          </span>
        ) : null}
        {/* Sprint 48 — warnings pill (e.g. row account diverges from
             the template's default). Non-blocking; commit still allowed. */}
        {(() => {
          const warned = preview.rows.filter((r) => (r.warnings?.length ?? 0) > 0).length
          if (warned === 0) return null
          return (
            <span className="rounded-full bg-amber-100 px-2 py-0.5 font-semibold text-amber-800">
              ⚠ {warned} with warnings
            </span>
          )
        })()}
      </div>

      <div className="overflow-auto rounded-xl border border-slate-200">
        <table className="w-full min-w-[960px] text-left text-[11px] text-slate-700">
          <thead className="bg-slate-50 text-[9.5px] uppercase tracking-[0.14em] text-slate-500">
            <tr>
              <th className="p-2">#</th>
              {/* Sprint 48 — orderRef ties multi-row shipments together. */}
              <th className="p-2">Order ref</th>
              <th className="p-2">Recipient</th>
              <th className="p-2">Address</th>
              <th className="p-2">City / Postal</th>
              <th className="p-2">Carrier</th>
              <th className="p-2 text-right">Weight</th>
              {/* Sprint 48 — item / customs data on this row. Renders empty
                   for pure shipment-only rows and pure item-only rows show
                   only these fields. */}
              <th className="p-2">Item / Customs</th>
              <th className="p-2">Status</th>
            </tr>
          </thead>
          <tbody>
            {preview.rows.map((r) => {
              const ok = r.errors.length === 0
              // Item / customs — surface the fields the operator most needs
              // to eyeball (description + HS + country + qty). Displayed
              // compactly in the shared "Item / Customs" cell; empty for
              // pure shipment rows.
              const hasItem = !!(
                r.itemDescription || r.itemSku || r.hsCode
                || r.countryOfOrigin || r.itemQuantity != null || r.itemUnitValue != null
              )
              return (
                <tr key={r.rowNumber} className="border-t border-slate-100">
                  <td className="p-2 font-mono text-[10.5px]">{r.rowNumber}</td>
                  <td className="p-2 font-mono text-[10.5px] text-slate-600">
                    {r.orderRef ?? '—'}
                  </td>
                  <td className="p-2">
                    <p className="font-semibold text-slate-950">{r.recipientName ?? '—'}</p>
                    {r.recipientCompany ? (
                      <p className="text-[10px] text-slate-500">{r.recipientCompany}</p>
                    ) : null}
                  </td>
                  <td className="p-2">
                    <p className="max-w-[200px] truncate">{r.addressLine1 ?? '—'}</p>
                  </td>
                  <td className="p-2">
                    {r.city ?? '—'} · {r.postalCode ?? '—'} {r.countryCode ?? ''}
                  </td>
                  <td className="p-2">
                    {r.carrierCode ?? '—'}
                    {r.accountNumber ? ` · ${r.accountNumber}` : ''}
                    {r.serviceType ? ` · ${r.serviceType}` : ''}
                  </td>
                  <td className="p-2 text-right">
                    {r.weight ?? '—'} {r.weightUnit ?? ''}
                  </td>
                  <td className="p-2">
                    {hasItem ? (
                      <div className="space-y-0.5">
                        {r.itemDescription ? (
                          <p className="max-w-[220px] truncate font-semibold text-slate-950">
                            {r.itemDescription}
                          </p>
                        ) : null}
                        <p className="text-[9.5px] text-slate-500">
                          {r.itemSku ? <span className="font-mono">{r.itemSku}</span> : null}
                          {r.itemQuantity != null ? (
                            <span>
                              {r.itemSku ? ' · ' : ''}qty {r.itemQuantity}
                            </span>
                          ) : null}
                          {r.itemUnitValue != null ? (
                            <span>
                              {(r.itemSku || r.itemQuantity != null) ? ' · ' : ''}
                              @{r.itemUnitValue}
                            </span>
                          ) : null}
                        </p>
                        {(r.hsCode || r.countryOfOrigin) ? (
                          <p className="text-[9.5px] text-slate-500">
                            {r.hsCode ? <span className="font-mono">HS {r.hsCode}</span> : null}
                            {r.hsCode && r.countryOfOrigin ? ' · ' : ''}
                            {r.countryOfOrigin ? <span>from {r.countryOfOrigin}</span> : null}
                          </p>
                        ) : null}
                      </div>
                    ) : (
                      <span className="text-slate-300">—</span>
                    )}
                  </td>
                  <td className="p-2">
                    {ok ? (
                      <span className="inline-flex items-center gap-1 rounded-full bg-emerald-100 px-1.5 py-0.5 text-[9.5px] font-semibold text-emerald-800">
                        <FiCheckCircle className="h-2.5 w-2.5" />
                        Ready
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 rounded-full bg-rose-100 px-1.5 py-0.5 text-[9.5px] font-semibold text-rose-800">
                        <FiAlertCircle className="h-2.5 w-2.5" />
                        {r.errors.join(', ')}
                      </span>
                    )}
                    {/* Non-blocking warnings sit under the status pill so
                         operators see divergence hints without them being
                         mistaken for errors. */}
                    {r.warnings && r.warnings.length > 0 ? (
                      <p className="mt-1 text-[9.5px] font-semibold text-amber-700">
                        ⚠ {r.warnings.join('; ')}
                      </p>
                    ) : null}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </>
  )
}

function CommittedStep({ summary }: { summary: OrderImportPreview }) {
  const generated = summary.rows.filter((r) => r.generatedStatus === 'GENERATED')
  const failed = summary.rows.filter((r) => r.generatedStatus === 'FAILED')
  return (
    <div className="space-y-3">
      <div className="rounded-2xl border border-emerald-200 bg-emerald-50/60 p-5 text-center">
        <FiCheckCircle className="mx-auto h-6 w-6 text-emerald-600" />
        <p className="mt-2 text-[13px] font-semibold text-slate-950">
          {generated.length} label(s) generated
        </p>
        {failed.length > 0 || summary.invalidRows > 0 ? (
          <p className="mt-1 text-[11.5px] text-rose-700">
            {failed.length} label(s) failed at the carrier
            {summary.invalidRows > 0
              ? ` · ${summary.invalidRows} row(s) skipped (validation)`
              : ''}
          </p>
        ) : (
          <p className="mt-1 text-[11.5px] text-slate-600">
            All rows accepted.
          </p>
        )}
      </div>

      {generated.length > 0 ? (
        <div className="rounded-xl border border-slate-200">
          <p className="px-3 py-2 text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500">
            Generated labels
          </p>
          <table className="w-full text-left text-[11.5px] text-slate-700">
            <thead className="border-t border-slate-100 bg-slate-50 text-[9.5px] uppercase tracking-[0.14em] text-slate-500">
              <tr>
                <th className="p-2">#</th>
                <th className="p-2">Order</th>
                <th className="p-2">Recipient</th>
                <th className="p-2">Tracking</th>
              </tr>
            </thead>
            <tbody>
              {generated.map((r) => (
                <tr key={r.rowNumber} className="border-t border-slate-100">
                  <td className="p-2 font-mono text-[10.5px]">{r.rowNumber}</td>
                  <td className="p-2 font-semibold">{r.generatedOrderNo ?? '—'}</td>
                  <td className="p-2">{r.recipientName ?? '—'}</td>
                  <td className="p-2 font-mono text-[10.5px]">{r.generatedTrackingNumber ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {failed.length > 0 ? (
        <div className="rounded-xl border border-rose-200 bg-rose-50/60">
          <p className="px-3 py-2 text-[10.5px] font-bold uppercase tracking-[0.14em] text-rose-800">
            Failed at the carrier
          </p>
          <table className="w-full text-left text-[11.5px] text-slate-700">
            <thead className="border-t border-rose-100 bg-rose-50 text-[9.5px] uppercase tracking-[0.14em] text-rose-700">
              <tr>
                <th className="p-2">#</th>
                <th className="p-2">Recipient</th>
                <th className="p-2">Reason</th>
              </tr>
            </thead>
            <tbody>
              {failed.map((r) => (
                <tr key={r.rowNumber} className="border-t border-rose-100">
                  <td className="p-2 font-mono text-[10.5px]">{r.rowNumber}</td>
                  <td className="p-2">{r.recipientName ?? '—'}</td>
                  <td className="p-2 text-rose-800">{r.generatedMessage ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </div>
  )
}
