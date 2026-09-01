import { useEffect, useRef, useState } from 'react'
import { useFocusTrap } from '../../hooks/useFocusTrap'
import {
  FiAlertCircle,
  FiArrowRight,
  FiCheck,
  FiCheckCircle,
  FiDownload,
  FiFile,
  FiHome,
  FiLoader,
  FiUploadCloud,
  FiX,
  FiZap,
} from 'react-icons/fi'
import {
  orderImportService,
  type ImportBatchDetail,
  type OrderImportPreview,
  type OrderImportRow,
} from '../../api/orderImportService'
import { notify } from '../../utils/notify'
import { ApiError } from '../../api/apiClient'

/** Clean, user-facing text + a friendly title for an upload/save failure —
 *  duplicate-file (409) gets its own heading instead of "Something went wrong". */
function importErrorDisplay(e: unknown, fallback: string): { title: string; body: string } {
  if (e instanceof ApiError) {
    const body = (e.payload?.message as string | undefined) || e.message || fallback
    if (e.status === 409) return { title: 'File already imported', body }
    return { title: 'Import failed', body }
  }
  return { title: 'Import failed', body: e instanceof Error ? e.message : fallback }
}

/**
 * CSV / XLSX order import. Restructured to one linear flow with one primary
 * action per state — no draft-vs-save fork:
 *   1. Upload   — pick a file; on parse the batch is AUTO-SAVED as a draft
 *                 (closing the tab loses nothing).
 *   2. Review   — fix rows in place; every cell edit persists to the draft
 *                 and re-validates server-side. One button: Generate.
 *   3. Result   — generation outcome with what remains in the draft.
 * Espresso/cream palette to match the rest of the workspace.
 */
export interface OrderImportModalProps {
  /** Dismiss handler. Optional in inline mode (no overlay to close). */
  onClose?: () => void
  /** Render the flow inline on a page (no modal overlay / focus trap) instead
   *  of as a popup. Used by the Order Intake page's "Import" tab. */
  inline?: boolean
  /** Called after the batch is saved / generated so the host can refresh the history view. */
  onImported?: () => void
}

export default function OrderImportModal({ onClose, inline = false, onImported }: OrderImportModalProps) {
  // Sprint 51 T6b — focus trap (modal only).
  const dialogRef = useRef<HTMLDivElement>(null)
  useFocusTrap(!inline, dialogRef)
  const [file, setFile] = useState<File | null>(null)
  /** The auto-saved draft under review (rows + counts). Non-null = step 2. */
  const [preview, setPreview] = useState<OrderImportPreview | null>(null)
  /** The draft's Import-history id — minted by the auto-save at upload. */
  const [batchId, setBatchId] = useState<number | null>(null)
  /** Generation outcome. Non-null = step 3. */
  const [result, setResult] = useState<ImportBatchDetail | null>(null)
  const [uploading, setUploading] = useState(false)
  const [generating, setGenerating] = useState(false)
  const [genProgress, setGenProgress] = useState<{ done: number; total: number } | null>(null)
  /** Bill-to for the whole batch; PLATFORM needs an explicit confirm click. */
  const [billing, setBilling] = useState<'AUTO' | 'PLATFORM'>('AUTO')
  const [confirmPlatform, setConfirmPlatform] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [downloadingXlsx, setDownloadingXlsx] = useState(false)
  /** True while a cell edit is being persisted + re-validated server-side. */
  const [savingCell, setSavingCell] = useState(false)
  /** Serialize cell PUTs — a second edit waits for the first (the backend
   *  re-validates the whole batch per PUT, so order matters). */
  const putChain = useRef<Promise<void>>(Promise.resolve())

  const step: 1 | 2 | 3 = result ? 3 : preview ? 2 : 1

  /** Map a saved-batch detail onto the review state's preview shape. */
  const applyBatch = (d: ImportBatchDetail) => {
    const rows = d.rows ?? []
    const invalid = rows.filter((r) => (r.errors?.length ?? 0) > 0).length
    setPreview({ totalRows: rows.length, validRows: rows.length - invalid, invalidRows: invalid, rows, batchId: d.id })
  }

  /**
   * Inline-edit hook for the review table. Applies the patch locally (typing
   * feels instant), then persists the row to the SAVED DRAFT — the backend
   * re-validates the whole batch and returns fresh rows + counts, so errors
   * clear/appear with server truth and nothing is lost on close.
   */
  const updateRow = (rowNumber: number, patch: Partial<OrderImportRow>) => {
    if (!preview || batchId == null) return
    const target = preview.rows.find((r) => r.rowNumber === rowNumber)
    if (!target) return
    const patched = { ...target, ...patch }
    // Optimistic local apply.
    setPreview((p) => p ? { ...p, rows: p.rows.map((r) => (r.rowNumber === rowNumber ? patched : r)) } : p)
    setSavingCell(true)
    putChain.current = putChain.current
      .then(() => orderImportService.updateRow(batchId, rowNumber, patched))
      .then((res) => {
        if (res.status?.toLowerCase() === 'success' && res.data) applyBatch(res.data)
      })
      .catch((e) => {
        notify.apiError(e, 'Could not save the edit — it will retry on your next change.')
      })
      .finally(() => setSavingCell(false))
  }

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

  /**
   * Upload = parse + validate + AUTO-SAVE as a draft in one motion. The old
   * flow made "save" a separate decision ("Save as draft" vs "Fix N rows to
   * save") over data the backend had effectively already accepted — now the
   * draft simply exists from the moment the file parses, and the only real
   * decision left (generate now or fix first) belongs to step 2.
   */
  const submitPreview = async () => {
    if (!file) return
    setUploading(true)
    setError(null)
    try {
      const previewRes = await orderImportService.preview(file)
      if (previewRes.status !== 'success' || !previewRes.data) {
        setError(previewRes.message ?? 'Preview failed.')
        notify.error(previewRes.message ?? 'Preview failed.')
        return
      }
      const saveRes = await orderImportService.save(previewRes.data.rows, file.name, true)
      if (saveRes.status === 'success' && saveRes.data) {
        setPreview(saveRes.data)
        setBatchId(saveRes.data.batchId ?? null)
        onImported?.()
      } else {
        setError(saveRes.message ?? 'Import failed.')
        notify.error(saveRes.message ?? 'Import failed.')
      }
    } catch (e) {
      const { title, body } = importErrorDisplay(e, 'Upload failed.')
      setError(body)
      notify.error({ title, body })
    } finally {
      setUploading(false)
    }
  }

  /** Generate labels for every ready row; error rows stay behind in the draft. */
  const generate = async () => {
    if (batchId == null) return
    setGenerating(true)
    setGenProgress(null)
    setConfirmPlatform(false)
    // Live X-of-N progress, polled alongside the generate POST.
    let polling = true
    const poll = async () => {
      while (polling) {
        try {
          const pr = await orderImportService.generationProgress(batchId)
          const d = pr.data
          if (polling && d?.running && d.total > 0) setGenProgress({ done: d.done, total: d.total })
        } catch { /* transient — the POST result is authoritative */ }
        await new Promise((r) => setTimeout(r, 400))
      }
    }
    void poll()
    try {
      const res = await orderImportService.generateLabels(batchId, {
        usePlatformAccount: billing === 'PLATFORM',
      })
      if (res.data) {
        setResult(res.data)
        const gen = (res.data.rows ?? []).filter((r) => (r.generatedStatus ?? '').toUpperCase() === 'GENERATED').length
        if (gen > 0) notify.success(res.message ?? `${gen} label(s) generated.`)
        else notify.error(res.message ?? 'No labels were generated.')
        onImported?.()
      } else {
        notify.error(res.message ?? 'Label generation failed.')
      }
    } catch (e) {
      const { title, body } = importErrorDisplay(e, 'Label generation failed.')
      notify.error({ title, body })
    } finally {
      polling = false
      setGenerating(false)
      setGenProgress(null)
    }
  }

  const startOver = () => {
    setFile(null)
    setPreview(null)
    setBatchId(null)
    setResult(null)
    setError(null)
    setBilling('AUTO')
    setConfirmPlatform(false)
  }

  const panel = (
      <div
        ref={inline ? undefined : dialogRef}
        className={
          inline
            ? 'flex w-full flex-col overflow-hidden rounded-2xl border border-[#e3d9c4] bg-[#fffdf8] shadow-sm'
            : 'flex h-[min(780px,92vh)] w-full max-w-[920px] flex-col overflow-hidden rounded-2xl border border-[#e3d9c4] bg-[#fffdf8] shadow-[0_30px_80px_rgba(31,21,12,0.35)]'
        }
        onClick={inline ? undefined : (e) => e.stopPropagation()}
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
                One order per row — upload (saved automatically), fix what needs fixing, generate.
              </p>
            </div>
          </div>
          {onClose ? (
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="inline-flex h-8 w-8 shrink-0 items-center justify-center rounded-lg border border-[#e3d9c4] bg-white text-[#8a7959] transition hover:bg-[#faf7f0] hover:text-[#412d15]"
            >
              <FiX className="h-4 w-4" />
            </button>
          ) : null}
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
          {step === 2 && preview ? (
            <PreviewStep preview={preview} onEdit={updateRow} savingCell={savingCell} batchId={batchId} />
          ) : null}
          {step === 3 && result ? <ResultStep result={result} /> : null}

          {error ? (
            <div className="flex items-start gap-2 rounded-xl border border-rose-200 bg-rose-50 px-3.5 py-2.5 text-[12px] text-rose-800">
              <FiAlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
              <span>{error}</span>
            </div>
          ) : null}
        </div>

        {/* ── Footer — one primary action per state ── */}
        <div className="flex flex-wrap items-center justify-between gap-2 border-t border-[#eee6d6] bg-white px-6 py-3.5">
          <div className="text-[11px] text-[#b6a684]">
            {step === 2 && preview ? (
              <span>
                {batchId != null ? (
                  <span className="font-semibold text-[#8a7959]">Draft #{batchId} saved automatically</span>
                ) : null}
                {preview.invalidRows > 0
                  ? ` · ${preview.invalidRows} row(s) need fixes — they stay in the draft until fixed`
                  : ' · safe to close and finish later'}
              </span>
            ) : step === 3 ? (
              'Generation finished'
            ) : (
              'Step 1 of 3'
            )}
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {step === 3 ? (
              <button type="button" onClick={startOver} className={GHOST_BTN}>
                Import another file
              </button>
            ) : null}

            {onClose ? (
              <button type="button" onClick={onClose} className={GHOST_BTN}>
                {step === 3 ? 'Done' : step === 2 ? 'Close — draft is saved' : 'Cancel'}
              </button>
            ) : null}

            {step === 2 && preview ? (
              generating ? (
                /* Live X-of-N progress while the labels are bought. */
                <div
                  className="flex min-w-[190px] flex-col gap-1 rounded-xl bg-[#1f150c] px-3.5 py-1.5 text-[#f4eede]"
                  role="progressbar"
                  aria-valuemin={0}
                  aria-valuemax={genProgress?.total || undefined}
                  aria-valuenow={genProgress ? Math.min(genProgress.done, genProgress.total) : undefined}
                >
                  <div className="flex items-center justify-between text-[11px] font-semibold">
                    <span className="inline-flex items-center gap-1.5">
                      <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
                      Generating…
                    </span>
                    {genProgress ? (
                      <span className="tabular-nums">{Math.min(genProgress.done, genProgress.total)}/{genProgress.total}</span>
                    ) : null}
                  </div>
                  <div className="h-1.5 w-full overflow-hidden rounded-full bg-[#f4eede]/20">
                    {genProgress && genProgress.total > 0 ? (
                      <div
                        className="h-full rounded-full bg-[#f4eede] transition-[width] duration-300 ease-out"
                        style={{ width: `${Math.round((Math.min(genProgress.done, genProgress.total) / genProgress.total) * 100)}%` }}
                      />
                    ) : (
                      <div className="h-full w-1/3 animate-pulse rounded-full bg-[#f4eede]/70" />
                    )}
                  </div>
                </div>
              ) : (
                <>
                  {/* Bill-to for the whole batch. Platform = house account,
                      rebilled with markup — needs an explicit confirm. */}
                  <span
                    title="Which carrier account this batch bills to. Platform bills the house account and rebills the client with markup."
                    className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[11px] font-semibold text-[#5a4526]"
                  >
                    <FiHome className="h-3.5 w-3.5 shrink-0" />
                    <span className="hidden sm:inline text-[9.5px] uppercase tracking-[0.08em] text-[#b6a684]">Bills to</span>
                    <select
                      value={billing}
                      onChange={(e) => {
                        setBilling(e.target.value as 'AUTO' | 'PLATFORM')
                        setConfirmPlatform(false)
                      }}
                      className="cursor-pointer border-0 bg-transparent pr-1 text-[11px] font-semibold text-inherit focus:outline-none"
                    >
                      <option value="AUTO">Client account</option>
                      <option value="PLATFORM">Platform account</option>
                    </select>
                  </span>
                  {billing === 'PLATFORM' && confirmPlatform ? (
                    <>
                      <button type="button" onClick={() => void generate()} className={PRIMARY_BTN}>
                        <FiHome className="h-3.5 w-3.5" />
                        Confirm — bill to platform
                      </button>
                      <button type="button" onClick={() => setConfirmPlatform(false)} className={GHOST_BTN}>
                        Cancel
                      </button>
                    </>
                  ) : (
                    <button
                      type="button"
                      onClick={() => (billing === 'PLATFORM' ? setConfirmPlatform(true) : void generate())}
                      disabled={preview.validRows === 0 || savingCell}
                      className={PRIMARY_BTN}
                      title={
                        savingCell
                          ? 'Saving your edit…'
                          : preview.validRows === 0
                            ? 'Fix at least one row to generate.'
                            : preview.invalidRows > 0
                              ? `Generates the ${preview.validRows} ready row(s); the ${preview.invalidRows} with errors stay in the draft to fix later.`
                              : undefined
                      }
                    >
                      <FiZap className="h-3.5 w-3.5" />
                      {preview.validRows === 0
                        ? 'Fix rows to generate'
                        : preview.invalidRows > 0
                          ? `Generate ${preview.validRows} ready label(s)`
                          : `Generate all ${preview.validRows} label(s)`}
                    </button>
                  )}
                </>
              )
            ) : null}
          </div>
        </div>
      </div>
  )

  if (inline) return panel

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Import orders"
      className="fixed inset-0 z-50 flex items-center justify-center bg-[#1f150c]/45 p-4 backdrop-blur-[1px]"
      onClick={onClose}
    >
      {panel}
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
    { n: 2, label: 'Review & fix' },
    { n: 3, label: 'Generate labels' },
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
          onChange={(e) => {
            onFileChange(e.target.files?.[0] ?? null)
            // Reset so re-selecting the SAME file fires onChange again
            // (a file <input> won't emit change when the value is unchanged).
            e.target.value = ''
          }}
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
              {uploading ? 'Validating…' : 'Upload & validate'}
            </button>
          </div>
        </div>
      ) : null}

      {/* Template helper */}
      <div>
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

/**
 * Every backend validation message starts with the column it belongs to
 * ("postalCode 'ABC12' doesn't match…", "clientCode ZZZZ is not
 * registered"). This maps a message to that field key so the offending
 * CELL can be painted red; messages with no field prefix (the
 * international-customs group rule) stay row-level in the Status column.
 */
const FIELD_KEYS = [
  'orderRef', 'clientCode', 'billTo', 'warehouseCode',
  'recipientName', 'recipientCompany', 'recipientPhone', 'recipientEmail',
  'addressLine1', 'addressLine2', 'city', 'state', 'postalCode', 'countryCode',
  'carrierCode', 'accountNumber', 'serviceType', 'packageType',
  'weight', 'weightUnit', 'currency', 'reference',
  'itemDescription', 'itemSku', 'itemQuantity', 'itemUnitValue',
  'hsCode', 'countryOfOrigin',
] as const

/** `extraKeys` carries the row's tenant custom fields, whose messages are
 *  keyed by fieldKey exactly like the built-in columns. */
function errorField(message: string, extraKeys: string[] = []): string | null {
  const first = message.split(/[\s']/, 1)[0]
  if ((FIELD_KEYS as readonly string[]).includes(first)) return first
  return extraKeys.includes(first) ? first : null
}

/** Split a row's errors into per-field buckets + row-level leftovers. */
function bucketErrors(
  errors: string[],
  extraKeys: string[] = [],
): { byField: Record<string, string[]>; rowLevel: string[] } {
  const byField: Record<string, string[]> = {}
  const rowLevel: string[] = []
  for (const msg of errors) {
    const f = errorField(msg, extraKeys)
    if (f) (byField[f] ??= []).push(msg)
    else rowLevel.push(msg)
  }
  return { byField, rowLevel }
}

/**
 * A read-only table cell that becomes an input on click. Shows the value as
 * plain text (red when its field failed validation); clicking it opens an
 * inline editor that commits on blur or Enter and cancels on Escape. The
 * commit fires once on exit — not per keystroke — so a row only re-validates
 * after the operator finishes the cell.
 */
function EditCell({
  value,
  onCommit,
  bad = false,
  mono = false,
  errors,
}: {
  value: string
  onCommit: (v: string) => void
  bad?: boolean
  mono?: boolean
  /** When present, shown as the cell's hover tooltip so the error text isn't
   *  printed inline — the cell just turns red and explains itself on hover. */
  errors?: string[]
}) {
  const [editing, setEditing] = useState(false)
  const [draft, setDraft] = useState(value)
  const inputRef = useRef<HTMLInputElement>(null)
  useEffect(() => {
    if (editing) {
      inputRef.current?.focus()
      inputRef.current?.select()
    }
  }, [editing])

  const begin = () => {
    setDraft(value)
    setEditing(true)
  }
  const commit = () => {
    setEditing(false)
    if (draft !== value) onCommit(draft)
  }
  const cancel = () => {
    setEditing(false)
    setDraft(value)
  }

  if (editing) {
    return (
      <input
        ref={inputRef}
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') { e.preventDefault(); commit() }
          else if (e.key === 'Escape') { e.preventDefault(); cancel() }
        }}
        className={`w-full rounded-[5px] border border-[#412d15] bg-white px-1.5 py-0.5 text-[11px] text-[#1f150c] outline-none ring-1 ring-[#412d15] ${mono ? 'font-mono' : ''}`}
      />
    )
  }
  const tooltip = bad && errors && errors.length > 0 ? errors.join('\n') : value || undefined
  return (
    <button
      type="button"
      onClick={begin}
      title={tooltip}
      className={`block w-full cursor-text truncate rounded-[5px] px-1.5 py-0.5 text-left text-[11px] transition ${mono ? 'font-mono' : ''} ${
        bad
          ? 'bg-rose-50 text-rose-800 ring-1 ring-inset ring-rose-300 hover:ring-rose-400'
          : 'text-[#3f3527] hover:bg-[#efe7d4]'
      }`}
    >
      {value || <span className="text-[#cdbf9f]">—</span>}
    </button>
  )
}

/** Column model for the review grid. `numeric`/`upper` shape how an edit is
 *  written back; `w` is the input min-width so codes stay tight and names get
 *  room. Order mirrors the CSV/template. */
type PreviewColumn = {
  key: string
  mono?: boolean
  upper?: boolean
  numeric?: boolean
  w?: string
}
const PREVIEW_COLUMNS: PreviewColumn[] = [
  { key: 'orderRef', mono: true, w: 'w-24' },
  { key: 'clientCode', mono: true, upper: true, w: 'w-24' },
  { key: 'billTo', mono: true, upper: true, w: 'w-24' },
  { key: 'warehouseCode', mono: true, upper: true, w: 'w-24' },
  { key: 'recipientName', w: 'w-40' },
  { key: 'recipientCompany', w: 'w-40' },
  { key: 'recipientPhone', w: 'w-28' },
  { key: 'recipientEmail', w: 'w-44' },
  { key: 'addressLine1', w: 'w-48' },
  { key: 'addressLine2', w: 'w-40' },
  { key: 'city', w: 'w-32' },
  { key: 'state', mono: true, upper: true, w: 'w-16' },
  { key: 'postalCode', mono: true, w: 'w-24' },
  { key: 'countryCode', mono: true, upper: true, w: 'w-16' },
  { key: 'carrierCode', mono: true, upper: true, w: 'w-24' },
  { key: 'accountNumber', mono: true, w: 'w-32' },
  { key: 'serviceType', mono: true, w: 'w-28' },
  { key: 'packageType', mono: true, w: 'w-24' },
  { key: 'weight', numeric: true, w: 'w-16' },
  { key: 'weightUnit', mono: true, upper: true, w: 'w-16' },
  { key: 'currency', mono: true, upper: true, w: 'w-16' },
  { key: 'reference', mono: true, w: 'w-28' },
  { key: 'itemDescription', w: 'w-48' },
  { key: 'itemSku', mono: true, w: 'w-24' },
  { key: 'itemQuantity', numeric: true, w: 'w-16' },
  { key: 'itemUnitValue', numeric: true, w: 'w-20' },
  { key: 'hsCode', mono: true, w: 'w-24' },
  { key: 'countryOfOrigin', mono: true, upper: true, w: 'w-16' },
]

/** Column lookup so the detail view can render the same editable cell per field. */
const COL_BY_KEY: Record<string, PreviewColumn> = Object.fromEntries(
  PREVIEW_COLUMNS.map((c) => [c.key, c]),
)

/** Detail-pane box widths, sized to each field. Fields not listed fall back to
 *  the grid's compact `col.w` (so state/zip/qty stay small). The detail pane has
 *  more room than the grid, so free-text fields get wider than the grid cap. */
const DETAIL_FIELD_W: Record<string, string> = {
  recipientName: 'w-64',
  recipientCompany: 'w-64',
  recipientEmail: 'w-72',
  addressLine1: 'w-72',
  addressLine2: 'w-64',
  city: 'w-48',
  itemDescription: 'w-72',
  recipientPhone: 'w-40',
  accountNumber: 'w-40',
  serviceType: 'w-40',
  reference: 'w-40',
  itemSku: 'w-32',
  hsCode: 'w-28',
}

/** Field groups for the detail view. Every editable column must appear in a
 *  group, otherwise its value AND its validation error have nowhere to render. */
const CARD_GROUPS: { title: string; keys: string[] }[] = [
  { title: 'Order', keys: ['orderRef', 'clientCode', 'billTo', 'warehouseCode'] },
  { title: 'Recipient', keys: ['recipientName', 'recipientCompany', 'recipientPhone', 'recipientEmail'] },
  { title: 'Ship to', keys: ['addressLine1', 'addressLine2', 'city', 'state', 'postalCode', 'countryCode'] },
  { title: 'Shipment', keys: ['carrierCode', 'accountNumber', 'serviceType', 'packageType', 'weight', 'weightUnit', 'currency', 'reference'] },
  { title: 'Customs', keys: ['itemDescription', 'itemSku', 'itemQuantity', 'itemUnitValue', 'hsCode', 'countryOfOrigin'] },
]

function PreviewStep({
  preview,
  onEdit,
  savingCell,
  batchId,
}: {
  preview: OrderImportPreview
  onEdit: (rowNumber: number, patch: Partial<OrderImportRow>) => void
  savingCell: boolean
  batchId: number | null
}) {
  const warned = preview.rows.filter((r) => (r.warnings?.length ?? 0) > 0).length
  // Union of tenant custom-field keys across the batch → stable extra columns.
  const customCols = Array.from(
    new Set(preview.rows.flatMap((r) => Object.keys(r.customFields ?? {}))),
  )
  // Two ways to review: the dense spreadsheet grid, or a master–detail (a row
  // list on the left, the selected order's editable fields on the right).
  const [view, setView] = useState<'table' | 'detail'>('table')
  const [selectedRowNo, setSelectedRowNo] = useState<number | null>(null)

  const cellFor = (r: OrderImportRow, col: PreviewColumn, errs?: string[]) => {
    const raw = (r as unknown as Record<string, unknown>)[col.key]
    const value = raw == null ? '' : String(raw)
    const commit = (v: string) => {
      let next: unknown = v
      if (col.numeric) next = v === '' ? null : Number(v)
      else if (col.upper) next = v.toUpperCase()
      onEdit(r.rowNumber, { [col.key]: next } as Partial<OrderImportRow>)
    }
    return <EditCell value={value} onCommit={commit} bad={(errs?.length ?? 0) > 0} mono={col.mono} errors={errs} />
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <StatPill tone="total" label={`${preview.totalRows} total`} />
        <StatPill tone="valid" label={`✓ ${preview.validRows} valid`} />
        {preview.invalidRows > 0 ? <StatPill tone="error" label={`✗ ${preview.invalidRows} with errors`} /> : null}
        {warned > 0 ? <StatPill tone="warn" label={`⚠ ${warned} with warnings`} /> : null}
        {/* View toggle: dense spreadsheet grid vs one card per order */}
        <div className="inline-flex overflow-hidden rounded-lg border border-[#e3d9c4]">
          {(['table', 'detail'] as const).map((v) => (
            <button
              key={v}
              type="button"
              onClick={() => setView(v)}
              className={`px-2.5 py-1 text-[10.5px] font-semibold capitalize transition ${
                view === v ? 'bg-[#1f150c] text-[#f4eede]' : 'bg-white text-[#5a4526] hover:bg-[#faf7f0]'
              }`}
            >
              {v === 'table' ? 'Table' : 'Detail'}
            </button>
          ))}
        </div>
        <span className="ml-auto inline-flex items-center gap-1.5 text-[10.5px] text-[#b6a684]">
          {savingCell ? (
            <>
              <FiLoader className="h-3 w-3 animate-spin text-[#5a4526]" />
              <span className="font-semibold text-[#5a4526]">Saving to draft{batchId != null ? ` #${batchId}` : ''}…</span>
            </>
          ) : (
            'Click any cell to edit — every change saves to the draft and re-validates. Scroll right for more columns.'
          )}
        </span>
      </div>

      {/* TABLE view — dense spreadsheet grid: one row per order, one column per
          CSV field. Cells edit in place; a failing cell goes red. */}
      {view === 'table' ? (
      <div className="overflow-x-auto rounded-xl border border-[#e3d9c4]">
        <table className="w-full border-collapse text-[11px]">
          <thead>
            <tr className="bg-[#faf7f0] text-[8.5px] uppercase tracking-[0.08em] text-[#8a7959]">
              <th className="sticky left-0 z-20 border-b border-r border-[#e3d9c4] bg-[#faf7f0] px-2 py-1.5 text-left font-bold">Row</th>
              {PREVIEW_COLUMNS.map((c) => (
                <th key={c.key} className="whitespace-nowrap border-b border-[#e3d9c4] px-2 py-1.5 text-left font-bold">
                  {c.key}
                </th>
              ))}
              {customCols.map((k) => (
                <th key={k} className="whitespace-nowrap border-b border-[#e3d9c4] px-2 py-1.5 text-left font-bold">{k}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {preview.rows.map((r) => {
              const customKeys = Object.keys(r.customFields ?? {})
              const { byField } = bucketErrors(r.errors ?? [], customKeys)
              const ok = (r.errors?.length ?? 0) === 0
              const warnCount = r.warnings?.length ?? 0
              // Full-row summary tooltip: every error + warning on the row —
              // including the group-level customs rules that belong to no single
              // field — so hovering the status chip explains the whole row.
              const statusTitle = [
                ...(r.errors ?? []).map((m) => '✗ ' + m),
                ...(r.warnings ?? []).map((w) => '⚠ ' + w),
              ].join('\n') || undefined
              return (
                <tr key={r.rowNumber} className={ok ? 'bg-white' : 'bg-rose-50/40'}>
                  {/* Single frozen column: row number + status, so nothing can
                      bleed through a gap between two separate sticky columns. */}
                  <td className={`sticky left-0 z-10 whitespace-nowrap border-b border-r border-[#e3d9c4] px-2 py-1 ${ok ? 'bg-white' : 'bg-rose-50'}`}>
                    <div className="flex items-center gap-1.5">
                      <span className="font-mono text-[10px] font-bold text-[#8a7959]">{r.rowNumber}</span>
                      {ok ? (
                        <span
                          title={warnCount > 0 ? statusTitle : 'Ready to generate'}
                          className="inline-flex items-center gap-1 rounded-full bg-emerald-100 px-1.5 py-0.5 text-[9px] font-semibold text-emerald-800"
                        >
                          <FiCheckCircle className="h-2.5 w-2.5" /> Ready
                        </span>
                      ) : (
                        <span
                          title={statusTitle}
                          className="inline-flex cursor-help items-center gap-1 rounded-full bg-rose-100 px-1.5 py-0.5 text-[9px] font-semibold text-rose-800"
                        >
                          <FiAlertCircle className="h-2.5 w-2.5" /> {r.errors.length} error{r.errors.length === 1 ? '' : 's'}
                        </span>
                      )}
                      {warnCount > 0 ? (
                        <span
                          title={statusTitle}
                          className="inline-flex cursor-help items-center rounded-full bg-amber-100 px-1.5 py-0.5 text-[9px] font-semibold text-amber-800"
                        >
                          ⚠ {warnCount}
                        </span>
                      ) : null}
                    </div>
                  </td>
                  {PREVIEW_COLUMNS.map((c) => (
                    <td key={c.key} className="border-b border-[#f2ecdf] px-1 py-1 align-top">
                      <div className={c.w}>{cellFor(r, c, byField[c.key])}</div>
                    </td>
                  ))}
                  {customCols.map((k) => (
                    <td key={k} className="border-b border-[#f2ecdf] px-1 py-1 align-top">
                      <div className="w-32">
                        <EditCell
                          value={r.customFields?.[k] ?? ''}
                          bad={(byField[k]?.length ?? 0) > 0}
                          errors={byField[k]}
                          onCommit={(v) => onEdit(r.rowNumber, { customFields: { ...(r.customFields ?? {}), [k]: v } })}
                        />
                      </div>
                    </td>
                  ))}
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      ) : (
      /* MASTER–DETAIL: pick a row on the left, edit its fields on the right. */
      (() => {
        const selected =
          preview.rows.find((r) => r.rowNumber === selectedRowNo) ??
          preview.rows.find((r) => (r.errors?.length ?? 0) > 0) ??
          preview.rows[0]
        const customKeys = selected ? Object.keys(selected.customFields ?? {}) : []
        const { byField, rowLevel } = selected
          ? bucketErrors(selected.errors ?? [], customKeys)
          : { byField: {} as Record<string, string[]>, rowLevel: [] as string[] }

        return (
          <div className="flex h-[440px] overflow-hidden rounded-xl border border-[#e3d9c4]">
            {/* Left — row list */}
            <div className="w-56 shrink-0 overflow-y-auto border-r border-[#eee6d6] bg-[#faf7f0]">
              {preview.rows.map((r) => {
                const rOk = (r.errors?.length ?? 0) === 0
                const active = selected?.rowNumber === r.rowNumber
                return (
                  <button
                    key={r.rowNumber}
                    type="button"
                    onClick={() => setSelectedRowNo(r.rowNumber)}
                    className={`flex w-full items-center gap-2 border-b border-[#f2ecdf] px-2.5 py-1.5 text-left text-[11px] transition ${
                      active ? 'bg-white shadow-[inset_2px_0_0_#1f150c]' : 'hover:bg-white/60'
                    }`}
                  >
                    <span className={`h-1.5 w-1.5 shrink-0 rounded-full ${rOk ? 'bg-emerald-500' : 'bg-rose-500'}`} />
                    <span className="font-mono text-[9.5px] font-bold text-[#8a7959]">R{r.rowNumber}</span>
                    <span className="min-w-0 flex-1 truncate">
                      <span className="font-semibold text-[#1f150c]">{r.orderRef || r.recipientName || '—'}</span>
                      {r.clientCode ? <span className="ml-1 font-mono text-[9.5px] text-[#8a7959]">{r.clientCode}</span> : null}
                    </span>
                    {!rOk ? (
                      <span className="shrink-0 rounded-full bg-rose-100 px-1.5 text-[9px] font-bold text-rose-700">
                        {r.errors!.length}
                      </span>
                    ) : null}
                  </button>
                )
              })}
            </div>

            {/* Right — detail editor for the selected row */}
            <div className="min-w-0 flex-1 overflow-y-auto p-4">
              {!selected ? (
                <p className="py-10 text-center text-[12px] text-[#8a7959]">Select a row on the left.</p>
              ) : (
                <>
                  <div className="flex flex-wrap items-center gap-2 border-b border-dashed border-[#eee6d6] pb-2.5">
                    <span className="font-mono text-[11px] font-bold text-[#8a7959]">Row {selected.rowNumber}</span>
                    {selected.orderRef ? <span className="font-mono text-[13px] font-semibold text-[#1f150c]">{selected.orderRef}</span> : null}
                    {selected.clientCode ? (
                      <span className="rounded bg-[#faf7f0] px-1.5 py-0.5 font-mono text-[10px] font-semibold text-[#5a4526]">{selected.clientCode}</span>
                    ) : null}
                    <span className="ml-auto">
                      {(selected.errors?.length ?? 0) === 0 ? (
                        <span className="inline-flex items-center gap-1 rounded-full bg-emerald-100 px-2 py-0.5 text-[10px] font-semibold text-emerald-800">
                          <FiCheckCircle className="h-2.5 w-2.5" /> Ready
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1 rounded-full bg-rose-100 px-2 py-0.5 text-[10px] font-semibold text-rose-800">
                          <FiAlertCircle className="h-2.5 w-2.5" /> {selected.errors!.length} error{selected.errors!.length === 1 ? '' : 's'}
                        </span>
                      )}
                    </span>
                  </div>

                  {/* Row-level (non-field) errors + warnings */}
                  {rowLevel.length > 0 || (selected.warnings?.length ?? 0) > 0 ? (
                    <div className="mt-2 space-y-0.5">
                      {rowLevel.map((m, i) => (
                        <p key={`e${i}`} className="text-[10.5px] text-rose-700">✗ {m}</p>
                      ))}
                      {(selected.warnings ?? []).map((m, i) => (
                        <p key={`w${i}`} className="text-[10.5px] text-amber-700">⚠ {m}</p>
                      ))}
                    </div>
                  ) : null}

                  {/* Grouped, editable fields — label left, value right, error below */}
                  <div className="mt-3 space-y-3">
                    {CARD_GROUPS.map((g) => (
                      <div key={g.title}>
                        <p className="mb-1 text-[9px] font-bold uppercase tracking-[0.12em] text-[#b6a684]">{g.title}</p>
                        <div className="space-y-1">
                          {g.keys.map((k) => {
                            const col = COL_BY_KEY[k]
                            if (!col) return null
                            const fe = byField[k]
                            return (
                              <div key={k} className="grid grid-cols-[120px_minmax(0,1fr)] items-start gap-2">
                                <span className="pt-1 text-[9.5px] uppercase tracking-[0.04em] text-[#a1906d]">{k}</span>
                                <div className="min-w-0">
                                  {/* Box sized to the field: state/zip narrow, address/email/description wide. */}
                                  <div className={`max-w-full ${DETAIL_FIELD_W[k] ?? col.w ?? 'w-40'}`}>{cellFor(selected, col, fe)}</div>
                                  {fe?.length ? <p className="mt-0.5 text-[9.5px] leading-snug text-rose-600">✗ {fe.join('; ')}</p> : null}
                                </div>
                              </div>
                            )
                          })}
                        </div>
                      </div>
                    ))}
                    {customKeys.length > 0 ? (
                      <div>
                        <p className="mb-1 text-[9px] font-bold uppercase tracking-[0.12em] text-[#b6a684]">Custom fields</p>
                        <div className="space-y-1">
                          {customKeys.map((k) => {
                            const fe = byField[k]
                            return (
                              <div key={k} className="grid grid-cols-[120px_minmax(0,1fr)] items-start gap-2">
                                <span className="pt-1 text-[9.5px] uppercase tracking-[0.04em] text-[#a1906d]">{k}</span>
                                <div className="min-w-0">
                                  <div className="w-48 max-w-full">
                                    <EditCell
                                      value={selected.customFields?.[k] ?? ''}
                                      bad={(fe?.length ?? 0) > 0}
                                      errors={fe}
                                      onCommit={(v) => onEdit(selected.rowNumber, { customFields: { ...(selected.customFields ?? {}), [k]: v } })}
                                    />
                                  </div>
                                  {fe?.length ? <p className="mt-0.5 text-[9.5px] leading-snug text-rose-600">✗ {fe.join('; ')}</p> : null}
                                </div>
                              </div>
                            )
                          })}
                        </div>
                      </div>
                    ) : null}
                  </div>
                </>
              )}
            </div>
          </div>
        )
      })()
      )}
    </div>
  )
}

/**
 * Step 3 — generation outcome. Three buckets from the batch's rows:
 * generated (tracking numbers), failed at the carrier (humanized reason), and
 * rows still held in the draft by validation errors. The user never has to
 * discover Import history on their own — but the batch lives there for later.
 */
function ResultStep({ result }: { result: ImportBatchDetail }) {
  const rows = result.rows ?? []
  const generated = rows.filter((r) => (r.generatedStatus ?? '').toUpperCase() === 'GENERATED')
  const failed = rows.filter((r) => (r.generatedStatus ?? '').toUpperCase() === 'FAILED')
  const held = rows.filter(
    (r) => (r.errors?.length ?? 0) > 0 && (r.generatedStatus ?? '').toUpperCase() !== 'GENERATED',
  )
  const allDone = generated.length === rows.length && rows.length > 0
  return (
    <div className="space-y-3">
      <div
        className={`rounded-2xl border p-6 text-center ${
          allDone ? 'border-emerald-200 bg-emerald-50/60' : 'border-[#e3d9c4] bg-[#faf7f0]'
        }`}
      >
        <span
          className={`mx-auto inline-flex h-12 w-12 items-center justify-center rounded-2xl text-white ${
            allDone ? 'bg-emerald-600' : generated.length > 0 ? 'bg-[#412d15]' : 'bg-rose-600'
          }`}
        >
          {generated.length > 0 ? <FiCheck className="h-6 w-6" /> : <FiAlertCircle className="h-6 w-6" />}
        </span>
        <p className="mt-3 text-[14px] font-semibold text-[#1f150c]">
          {generated.length} of {rows.length} label(s) generated
        </p>
        <p className="mt-1 text-[11.5px] text-[#5a4526]">
          {failed.length > 0 ? `${failed.length} rejected by the carrier — retry from Import history. ` : ''}
          {held.length > 0 ? `${held.length} still need fixes — the draft keeps them. ` : ''}
          {allDone ? 'Find the orders in All Orders (grouped by this label batch).' : ''}
        </p>
        <p className="mt-3 inline-flex items-center gap-1.5 rounded-full bg-[#efe7d4] px-2.5 py-1 text-[10.5px] font-bold uppercase tracking-[0.1em] text-[#5a4526]">
          Import #{result.id}{result.labelBatchId != null ? ` · label batch ${result.labelBatchId}` : ''}
        </p>
      </div>

      {generated.length > 0 ? (
        <div className="overflow-hidden rounded-xl border border-emerald-200">
          <p className="bg-emerald-50 px-3 py-2 text-[10.5px] font-bold uppercase tracking-[0.14em] text-emerald-800">
            Generated ({generated.length})
          </p>
          <table className="w-full text-left text-[11.5px] text-[#3f3527]">
            <thead className="border-t border-emerald-100 bg-emerald-50/60 text-[9.5px] uppercase tracking-[0.14em] text-emerald-700">
              <tr>
                <th className="p-2.5">#</th>
                <th className="p-2.5">Recipient</th>
                <th className="p-2.5">Destination</th>
                <th className="p-2.5">Order</th>
                <th className="p-2.5">Tracking</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-emerald-100">
              {generated.map((r) => (
                <tr key={r.rowNumber}>
                  <td className="p-2.5 font-mono text-[10.5px] text-emerald-700">{r.rowNumber}</td>
                  <td className="p-2.5 font-semibold text-[#1f150c]">{r.recipientName ?? '—'}</td>
                  <td className="p-2.5">{r.city ?? '—'} {r.countryCode ? `· ${r.countryCode}` : ''}</td>
                  <td className="p-2.5 font-mono text-[10.5px]">{r.generatedOrderNo ?? '—'}</td>
                  <td className="p-2.5 font-mono text-[10.5px]">{r.generatedTrackingNumber ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {failed.length > 0 ? (
        <div className="overflow-hidden rounded-xl border border-rose-300">
          <p className="bg-rose-50 px-3 py-2 text-[10.5px] font-bold uppercase tracking-[0.14em] text-rose-800">
            Rejected by the carrier ({failed.length}) — retry from Import history
          </p>
          <table className="w-full text-left text-[11.5px] text-[#3f3527]">
            <tbody className="divide-y divide-rose-100">
              {failed.map((r) => (
                <tr key={r.rowNumber}>
                  <td className="w-10 p-2.5 font-mono text-[10.5px] text-rose-700">{r.rowNumber}</td>
                  <td className="p-2.5 font-semibold text-[#1f150c]">{r.recipientName ?? '—'}</td>
                  <td className="p-2.5 text-rose-700">{r.generatedMessage ?? 'The carrier rejected this shipment.'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}

      {held.length > 0 ? (
        <div className="overflow-hidden rounded-xl border border-amber-300">
          <p className="bg-amber-50 px-3 py-2 text-[10.5px] font-bold uppercase tracking-[0.14em] text-amber-800">
            Still in the draft — fix from Import history ({held.length})
          </p>
          <table className="w-full text-left text-[11.5px] text-[#3f3527]">
            <tbody className="divide-y divide-amber-100">
              {held.map((r) => (
                <tr key={r.rowNumber}>
                  <td className="w-10 p-2.5 font-mono text-[10.5px] text-amber-700">{r.rowNumber}</td>
                  <td className="p-2.5 font-semibold text-[#1f150c]">{r.recipientName ?? '—'}</td>
                  <td className="p-2.5 text-amber-700">
                    {(r.errors ?? []).slice(0, 2).join('; ')}{(r.errors?.length ?? 0) > 2 ? ` (+${r.errors!.length - 2} more)` : ''}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : null}
    </div>
  )
}
