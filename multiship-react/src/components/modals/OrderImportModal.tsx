import { useState } from 'react'
import {
  FiAlertCircle,
  FiArrowRight,
  FiCheck,
  FiCheckCircle,
  FiDownload,
  FiFile,
  FiFileText,
  FiLoader,
  FiUploadCloud,
  FiX,
} from 'react-icons/fi'
import {
  orderImportService,
  type OrderImportPreview,
} from '../../api/orderImportService'
import { notify } from '../../utils/notify'

/**
 * Sprint 40 — CSV / XLSX order import modal. Three steps:
 *   1. Upload  — drag-and-drop / pick a file, download the template.
 *   2. Review  — preview table with per-row status + validation actions.
 *   3. Done    — commit summary (generated / failed labels).
 * Espresso/cream palette to match the rest of the workspace.
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
  const [downloadingXlsx, setDownloadingXlsx] = useState(false)
  const [validating, setValidating] = useState<'data' | 'addresses' | null>(null)

  const step: 1 | 2 | 3 = committedSummary ? 3 : preview ? 2 : 1

  const downloadXlsx = async () => {
    if (downloadingXlsx) return
    setDownloadingXlsx(true)
    try {
      await orderImportService.downloadXlsxTemplate(null)
    } catch (e) {
      notify.apiError(e, 'Template download failed.')
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
      // Save the imported data to Data History — does NOT generate labels.
      const response = await orderImportService.save(preview.rows, file?.name)
      if (response.status === 'success' && response.data) {
        setCommittedSummary(response.data)
        notify.success(response.message ?? 'Saved.')
      } else {
        notify.error(response.message ?? 'Save failed.')
      }
    } catch (e) {
      notify.apiError(e, 'Save failed.')
    } finally {
      setCommitting(false)
    }
  }

  const runReValidate = async () => {
    if (!preview) return
    setValidating('data')
    try {
      const response = await orderImportService.validate(preview.rows)
      if (response.status === 'success' && response.data) {
        setPreview(response.data)
        notify.success(response.message ?? 'Rows re-validated.')
      } else {
        notify.error(response.message ?? 'Validation failed.')
      }
    } catch (e) {
      notify.apiError(e, 'Validation failed.')
    } finally {
      setValidating(null)
    }
  }

  const runValidateAddresses = async () => {
    if (!preview) return
    setValidating('addresses')
    try {
      const response = await orderImportService.validateAddresses(preview.rows)
      if (response.status === 'success' && response.data) {
        setPreview(response.data)
        notify.success(response.message ?? 'Addresses checked.')
      } else {
        notify.error(response.message ?? 'Address validation failed.')
      }
    } catch (e) {
      notify.apiError(e, 'Address validation failed.')
    } finally {
      setValidating(null)
    }
  }

  const startOver = () => {
    setFile(null)
    setPreview(null)
    setCommittedSummary(null)
    setError(null)
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Import orders"
      className="fixed inset-0 z-50 flex items-center justify-center bg-[#1f150c]/45 p-4 backdrop-blur-[1px]"
      onClick={onClose}
    >
      <div
        className="flex h-[min(780px,92vh)] w-full max-w-[920px] flex-col overflow-hidden rounded-2xl border border-[#e3d9c4] bg-[#fffdf8] shadow-[0_30px_80px_rgba(31,21,12,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        {/* ── Header ── */}
        <div className="flex items-start justify-between gap-4 border-b border-[#eee6d6] bg-white px-6 py-4">
          <div className="flex items-start gap-3">
            <span className="mt-0.5 inline-flex h-9 w-9 items-center justify-center rounded-xl bg-[#1f150c] text-[#f4eede]">
              <FiUploadCloud className="h-4 w-4" />
            </span>
            <div>
              <p className="text-[10px] font-bold uppercase tracking-[0.18em] text-[#b6a684]">Bulk import</p>
              <h3 className="text-[16px] font-semibold text-[#1f150c]">Import orders from CSV / Excel</h3>
              <p className="mt-0.5 text-[11.5px] text-[#8a7959]">
                One order per row — upload, review the preview, then commit.
              </p>
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-[#e3d9c4] bg-white text-[#8a7959] transition hover:bg-[#faf7f0] hover:text-[#412d15]"
          >
            <FiX className="h-4 w-4" />
          </button>
        </div>

        {/* ── Stepper ── */}
        <div className="flex items-center gap-2 border-b border-[#eee6d6] bg-[#faf7f0] px-6 py-3">
          <Stepper current={step} />
        </div>

        {/* ── Body ── */}
        <div className="flex-1 space-y-4 overflow-y-auto px-6 py-5">
          {step === 1 ? (
            <UploadStep
              file={file}
              onFileChange={setFile}
              onSubmit={() => void submitPreview()}
              uploading={uploading}
              downloadingXlsx={downloadingXlsx}
              onDownloadXlsx={() => void downloadXlsx()}
              csvHref={orderImportService.templateUrl()}
            />
          ) : null}
          {step === 2 && preview ? <PreviewStep preview={preview} /> : null}
          {step === 3 && committedSummary ? <CommittedStep summary={committedSummary} /> : null}

          {error ? (
            <div className="flex items-start gap-2 rounded-xl border border-rose-200 bg-rose-50 px-3.5 py-2.5 text-[12px] text-rose-800">
              <FiAlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
              <span>{error}</span>
            </div>
          ) : null}
        </div>

        {/* ── Footer ── */}
        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-[#eee6d6] bg-white px-6 py-3.5">
          <div className="text-[11px] text-[#b6a684]">
            {step === 2 && preview ? `${preview.totalRows} rows loaded` : step === 3 ? 'Import complete' : 'Step 1 of 3'}
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {step === 3 ? (
              <button type="button" onClick={startOver} className={GHOST_BTN}>
                Import another file
              </button>
            ) : null}

            <button type="button" onClick={onClose} className={GHOST_BTN}>
              {step === 3 ? 'Done' : 'Cancel'}
            </button>

            {step === 2 && preview ? (
              <>
                <button
                  type="button"
                  onClick={() => void runReValidate()}
                  disabled={validating != null || committing}
                  className={GHOST_BTN}
                  title="Re-run required-field + name-code + international-item checks on the current row state."
                >
                  {validating === 'data' ? <FiLoader className="h-3.5 w-3.5 animate-spin" /> : <FiCheckCircle className="h-3.5 w-3.5" />}
                  {validating === 'data' ? 'Validating…' : 'Re-validate'}
                </button>
                <button
                  type="button"
                  onClick={() => void runValidateAddresses()}
                  disabled={validating != null || committing}
                  className={GHOST_BTN}
                  title="Address-check every row against its picked carrier's own validation API."
                >
                  {validating === 'addresses' ? <FiLoader className="h-3.5 w-3.5 animate-spin" /> : <FiCheckCircle className="h-3.5 w-3.5" />}
                  {validating === 'addresses' ? 'Checking…' : 'Validate addresses'}
                </button>
                <button
                  type="button"
                  onClick={() => void submitCommit()}
                  disabled={committing || preview.validRows === 0 || validating != null}
                  className={PRIMARY_BTN}
                >
                  {committing ? <FiLoader className="h-3.5 w-3.5 animate-spin" /> : <FiCheckCircle className="h-3.5 w-3.5" />}
                  {committing ? 'Saving…' : `Save ${preview.validRows} row(s)`}
                </button>
              </>
            ) : null}
          </div>
        </div>
      </div>
    </div>
  )
}

const GHOST_BTN =
  'inline-flex items-center gap-1.5 rounded-lg border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40'
const PRIMARY_BTN =
  'inline-flex items-center gap-1.5 rounded-lg bg-[#1f150c] px-3.5 py-1.5 text-[12px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4] disabled:text-white disabled:shadow-none'

function Stepper({ current }: { current: 1 | 2 | 3 }) {
  const steps = [
    { n: 1, label: 'Upload file' },
    { n: 2, label: 'Review & validate' },
    { n: 3, label: 'Save' },
  ]
  return (
    <ol className="flex w-full items-center gap-1">
      {steps.map((s, i) => {
        const done = current > s.n
        const active = current === s.n
        return (
          <li key={s.n} className="flex flex-1 items-center gap-2">
            <span
              className={`inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-full text-[11px] font-bold transition ${
                done
                  ? 'bg-emerald-600 text-white'
                  : active
                    ? 'bg-[#1f150c] text-[#f4eede]'
                    : 'bg-[#efe7d4] text-[#b6a684]'
              }`}
            >
              {done ? <FiCheck className="h-3.5 w-3.5" /> : s.n}
            </span>
            <span
              className={`whitespace-nowrap text-[11.5px] font-semibold ${
                active ? 'text-[#1f150c]' : done ? 'text-[#5a4526]' : 'text-[#b6a684]'
              }`}
            >
              {s.label}
            </span>
            {i < steps.length - 1 ? (
              <span className={`mx-1 h-px flex-1 ${current > s.n ? 'bg-emerald-300' : 'bg-[#e3d9c4]'}`} />
            ) : null}
          </li>
        )
      })}
    </ol>
  )
}

function UploadStep({
  file,
  onFileChange,
  onSubmit,
  uploading,
  downloadingXlsx,
  onDownloadXlsx,
  csvHref,
}: {
  file: File | null
  onFileChange: (f: File | null) => void
  onSubmit: () => void
  uploading: boolean
  downloadingXlsx: boolean
  onDownloadXlsx: () => void
  csvHref: string
}) {
  const [dragging, setDragging] = useState(false)
  const prettySize = (bytes: number) =>
    bytes < 1024 ? `${bytes} B` : bytes < 1_048_576 ? `${(bytes / 1024).toFixed(1)} KB` : `${(bytes / 1_048_576).toFixed(1)} MB`

  return (
    <div className="space-y-4">
      {/* Dropzone */}
      <label
        onDragOver={(e) => {
          e.preventDefault()
          setDragging(true)
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={(e) => {
          e.preventDefault()
          setDragging(false)
          const f = e.dataTransfer.files?.[0]
          if (f) onFileChange(f)
        }}
        className={`flex cursor-pointer flex-col items-center justify-center rounded-2xl border-2 border-dashed px-6 py-10 text-center transition ${
          dragging ? 'border-[#412d15] bg-[#f4eede]' : 'border-[#cdbf9f] bg-[#faf7f0] hover:border-[#412d15] hover:bg-[#f4eede]/60'
        }`}
      >
        <span className="inline-flex h-12 w-12 items-center justify-center rounded-2xl bg-white text-[#412d15] shadow-sm ring-1 ring-[#e3d9c4]">
          <FiUploadCloud className="h-5 w-5" />
        </span>
        <p className="mt-3 text-[13px] font-semibold text-[#1f150c]">
          Drag &amp; drop your file here, or <span className="text-[#412d15] underline underline-offset-2">browse</span>
        </p>
        <p className="mt-1 text-[11px] text-[#8a7959]">CSV or Excel (.csv, .xlsx) · one order per row</p>
        <input
          type="file"
          accept=".csv,.xlsx,.txt"
          onChange={(e) => onFileChange(e.target.files?.[0] ?? null)}
          className="sr-only"
        />
      </label>

      {/* Selected file chip */}
      {file ? (
        <div className="flex items-center justify-between gap-3 rounded-xl border border-[#e3d9c4] bg-white px-3.5 py-2.5">
          <div className="flex min-w-0 items-center gap-2.5">
            <span className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-[#f4eede] text-[#412d15]">
              <FiFile className="h-4 w-4" />
            </span>
            <div className="min-w-0">
              <p className="truncate text-[12.5px] font-semibold text-[#1f150c]">{file.name}</p>
              <p className="text-[10.5px] text-[#8a7959]">{prettySize(file.size)}</p>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => onFileChange(null)}
              className="rounded-lg border border-[#e3d9c4] bg-white p-1.5 text-[#8a7959] transition hover:bg-[#faf7f0] hover:text-rose-600"
              aria-label="Remove file"
            >
              <FiX className="h-3.5 w-3.5" />
            </button>
            <button type="button" onClick={onSubmit} disabled={uploading} className={PRIMARY_BTN}>
              {uploading ? <FiLoader className="h-3.5 w-3.5 animate-spin" /> : <FiArrowRight className="h-3.5 w-3.5" />}
              {uploading ? 'Reading…' : 'Preview rows'}
            </button>
          </div>
        </div>
      ) : null}

      {/* Template + required columns helper */}
      <div className="grid gap-3 sm:grid-cols-2">
        <div className="rounded-xl border border-[#e3d9c4] bg-white p-4">
          <p className="flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-[0.12em] text-[#8a7959]">
            <FiDownload className="h-3.5 w-3.5" /> Templates
          </p>
          <p className="mt-1.5 text-[11.5px] text-[#8a7959]">
            The Excel template has cascading dropdowns to pick each row's client, carrier &amp; account.
          </p>
          <div className="mt-3 flex flex-wrap items-center gap-2">
            <button type="button" onClick={onDownloadXlsx} disabled={downloadingXlsx} className={GHOST_BTN}>
              {downloadingXlsx ? <FiLoader className="h-3.5 w-3.5 animate-spin" /> : <FiDownload className="h-3.5 w-3.5" />}
              Excel template
            </button>
            <a
              href={csvHref}
              className="inline-flex items-center gap-1.5 text-[11.5px] font-semibold text-[#8a7959] transition hover:text-[#1f150c]"
              title="Flat CSV template — no validation, no dropdowns."
            >
              <FiDownload className="h-3 w-3" /> Plain CSV
            </a>
          </div>
        </div>

        <div className="rounded-xl border border-[#e3d9c4] bg-white p-4">
          <p className="flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-[0.12em] text-[#8a7959]">
            <FiFileText className="h-3.5 w-3.5" /> Required columns
          </p>
          <div className="mt-2 flex flex-wrap gap-1.5">
            {['recipientName', 'addressLine1', 'city', 'postalCode', 'countryCode', 'weight'].map((c) => (
              <span
                key={c}
                className="rounded-md bg-[#faf7f0] px-1.5 py-0.5 font-mono text-[10.5px] font-semibold text-[#5a4526] ring-1 ring-[#eee6d6]"
              >
                {c}
              </span>
            ))}
          </div>
        </div>
      </div>
    </div>
  )
}

function StatPill({ tone, label }: { tone: 'total' | 'valid' | 'error' | 'warn'; label: string }) {
  const tones: Record<string, string> = {
    total: 'bg-[#efe7d4] text-[#5a4526]',
    valid: 'bg-emerald-100 text-emerald-800',
    error: 'bg-rose-100 text-rose-800',
    warn: 'bg-amber-100 text-amber-800',
  }
  return <span className={`rounded-full px-2.5 py-0.5 text-[11.5px] font-semibold ${tones[tone]}`}>{label}</span>
}

function PreviewStep({ preview }: { preview: OrderImportPreview }) {
  const warned = preview.rows.filter((r) => (r.warnings?.length ?? 0) > 0).length
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <StatPill tone="total" label={`${preview.totalRows} total`} />
        <StatPill tone="valid" label={`✓ ${preview.validRows} valid`} />
        {preview.invalidRows > 0 ? <StatPill tone="error" label={`✗ ${preview.invalidRows} with errors`} /> : null}
        {warned > 0 ? <StatPill tone="warn" label={`⚠ ${warned} with warnings`} /> : null}
      </div>

      <div className="overflow-auto rounded-xl border border-[#e3d9c4]">
        <table className="w-full min-w-[960px] text-left text-[11px] text-[#3f3527]">
          <thead className="bg-[#faf7f0] text-[9.5px] uppercase tracking-[0.14em] text-[#8a7959]">
            <tr>
              <th className="p-2.5">#</th>
              <th className="p-2.5">Order ref</th>
              <th className="p-2.5">Recipient</th>
              <th className="p-2.5">Address</th>
              <th className="p-2.5">City / Postal</th>
              <th className="p-2.5">Carrier</th>
              <th className="p-2.5 text-right">Weight</th>
              <th className="p-2.5">Item / Customs</th>
              <th className="p-2.5">Status</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-[#eee6d6]">
            {preview.rows.map((r) => {
              const ok = r.errors.length === 0
              const hasItem = !!(
                r.itemDescription || r.itemSku || r.hsCode
                || r.countryOfOrigin || r.itemQuantity != null || r.itemUnitValue != null
              )
              return (
                <tr key={r.rowNumber} className="transition hover:bg-[#faf7f0]/60">
                  <td className="p-2.5 font-mono text-[10.5px] text-[#8a7959]">{r.rowNumber}</td>
                  <td className="p-2.5 font-mono text-[10.5px] text-[#5a4526]">{r.orderRef ?? '—'}</td>
                  <td className="p-2.5">
                    <p className="font-semibold text-[#1f150c]">{r.recipientName ?? '—'}</p>
                    {r.recipientCompany ? <p className="text-[10px] text-[#8a7959]">{r.recipientCompany}</p> : null}
                  </td>
                  <td className="p-2.5">
                    <p className="max-w-[200px] truncate">{r.addressLine1 ?? '—'}</p>
                  </td>
                  <td className="p-2.5">
                    {r.city ?? '—'} · {r.postalCode ?? '—'} {r.countryCode ?? ''}
                  </td>
                  <td className="p-2.5">
                    {r.carrierCode ?? '—'}
                    {r.accountNumber ? ` · ${r.accountNumber}` : ''}
                    {r.serviceType ? ` · ${r.serviceType}` : ''}
                  </td>
                  <td className="p-2.5 text-right">
                    {r.weight ?? '—'} {r.weightUnit ?? ''}
                  </td>
                  <td className="p-2.5">
                    {hasItem ? (
                      <div className="space-y-0.5">
                        {r.itemDescription ? (
                          <p className="max-w-[220px] truncate font-semibold text-[#1f150c]">{r.itemDescription}</p>
                        ) : null}
                        <p className="text-[9.5px] text-[#8a7959]">
                          {r.itemSku ? <span className="font-mono">{r.itemSku}</span> : null}
                          {r.itemQuantity != null ? <span>{r.itemSku ? ' · ' : ''}qty {r.itemQuantity}</span> : null}
                          {r.itemUnitValue != null ? (
                            <span>{(r.itemSku || r.itemQuantity != null) ? ' · ' : ''}@{r.itemUnitValue}</span>
                          ) : null}
                        </p>
                        {(r.hsCode || r.countryOfOrigin) ? (
                          <p className="text-[9.5px] text-[#8a7959]">
                            {r.hsCode ? <span className="font-mono">HS {r.hsCode}</span> : null}
                            {r.hsCode && r.countryOfOrigin ? ' · ' : ''}
                            {r.countryOfOrigin ? <span>from {r.countryOfOrigin}</span> : null}
                          </p>
                        ) : null}
                      </div>
                    ) : (
                      <span className="text-[#cdbf9f]">—</span>
                    )}
                  </td>
                  <td className="p-2.5">
                    {ok ? (
                      <span className="inline-flex items-center gap-1 rounded-full bg-emerald-100 px-1.5 py-0.5 text-[9.5px] font-semibold text-emerald-800">
                        <FiCheckCircle className="h-2.5 w-2.5" /> Ready
                      </span>
                    ) : (
                      <span className="inline-flex items-center gap-1 rounded-full bg-rose-100 px-1.5 py-0.5 text-[9.5px] font-semibold text-rose-800">
                        <FiAlertCircle className="h-2.5 w-2.5" /> {r.errors.join(', ')}
                      </span>
                    )}
                    {r.warnings && r.warnings.length > 0 ? (
                      <p className="mt-1 text-[9.5px] font-semibold text-amber-700">⚠ {r.warnings.join('; ')}</p>
                    ) : null}
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}

function CommittedStep({ summary }: { summary: OrderImportPreview }) {
  const saved = summary.rows.filter((r) => (r.errors?.length ?? 0) === 0)
  return (
    <div className="space-y-3">
      <div className="rounded-2xl border border-emerald-200 bg-emerald-50/60 p-6 text-center">
        <span className="mx-auto inline-flex h-12 w-12 items-center justify-center rounded-2xl bg-emerald-600 text-white">
          <FiCheck className="h-6 w-6" />
        </span>
        <p className="mt-3 text-[14px] font-semibold text-[#1f150c]">{summary.validRows} order(s) saved</p>
        <p className="mt-1 text-[11.5px] text-[#5a4526]">
          Saved to <span className="font-semibold">Data history</span> — no labels were generated.
          {summary.invalidRows > 0 ? ` ${summary.invalidRows} row(s) with errors were skipped.` : ''}
        </p>
        {summary.batchId != null ? (
          <p className="mt-3 inline-flex items-center gap-1.5 rounded-full bg-emerald-100 px-2.5 py-1 text-[10.5px] font-bold uppercase tracking-[0.1em] text-emerald-800">
            Import #{summary.batchId}
          </p>
        ) : null}
      </div>

      {saved.length > 0 ? (
        <div className="overflow-hidden rounded-xl border border-[#e3d9c4]">
          <p className="bg-[#faf7f0] px-3 py-2 text-[10.5px] font-bold uppercase tracking-[0.14em] text-[#8a7959]">
            Saved rows
          </p>
          <table className="w-full text-left text-[11.5px] text-[#3f3527]">
            <thead className="border-t border-[#eee6d6] bg-[#faf7f0]/60 text-[9.5px] uppercase tracking-[0.14em] text-[#8a7959]">
              <tr>
                <th className="p-2.5">#</th>
                <th className="p-2.5">Recipient</th>
                <th className="p-2.5">Destination</th>
                <th className="p-2.5">Carrier</th>
                <th className="p-2.5 text-right">Weight</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[#eee6d6]">
              {saved.map((r) => (
                <tr key={r.rowNumber}>
                  <td className="p-2.5 font-mono text-[10.5px] text-[#8a7959]">{r.rowNumber}</td>
                  <td className="p-2.5 font-semibold text-[#1f150c]">{r.recipientName ?? '—'}</td>
                  <td className="p-2.5">
                    {r.city ?? '—'} {r.countryCode ? `· ${r.countryCode}` : ''}
                  </td>
                  <td className="p-2.5">{r.carrierCode ?? '—'}</td>
                  <td className="p-2.5 text-right">{r.weight ?? '—'} {r.weightUnit ?? ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </div>
  )
}
