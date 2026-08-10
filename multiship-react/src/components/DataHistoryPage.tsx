import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  FiArrowLeft,
  FiChevronDown,
  FiChevronRight,
  FiDatabase,
  FiFileText,
  FiRefreshCw,
  FiZap,
} from 'react-icons/fi'
import PageSectionHeader from './workspace/PageSectionHeader'
import { notify } from '../utils/notify'
import {
  orderImportService,
  type ImportBatchSummary,
  type OrderImportRow,
} from '../api/orderImportService'

/**
 * Data History — every saved CSV/XLSX import. "Commit" in the import modal
 * saves the parsed rows here (no labels generated); this page lists those
 * saved imports and lets you expand one to see its rows.
 */
export default function DataHistoryPage() {
  const navigate = useNavigate()
  const [batches, setBatches] = useState<ImportBatchSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [openId, setOpenId] = useState<number | null>(null)
  const [rowsById, setRowsById] = useState<Record<number, OrderImportRow[] | 'loading'>>({})
  const [generatingId, setGeneratingId] = useState<number | null>(null)
  const [genRowKey, setGenRowKey] = useState<string | null>(null)

  const load = async () => {
    setLoading(true)
    try {
      const res = await orderImportService.listHistory()
      setBatches(res.data ?? [])
    } catch (e) {
      notify.error(e instanceof Error ? e.message : 'Could not load import history.')
      setBatches([])
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
  }, [])

  const toggle = async (id: number) => {
    if (openId === id) {
      setOpenId(null)
      return
    }
    setOpenId(id)
    if (!rowsById[id]) {
      setRowsById((m) => ({ ...m, [id]: 'loading' }))
      try {
        const res = await orderImportService.getHistory(id)
        setRowsById((m) => ({ ...m, [id]: res.data?.rows ?? [] }))
      } catch (e) {
        notify.error(e instanceof Error ? e.message : 'Could not load import rows.')
        setRowsById((m) => ({ ...m, [id]: [] }))
      }
    }
  }

  /** Kick off label generation for a batch. Optimistically flips the row to
   *  "In progress" while the carrier calls run, then reflects the result. */
  const generate = async (id: number) => {
    setGeneratingId(id)
    setBatches((list) => list.map((b) => (b.id === id ? { ...b, status: 'IN_PROGRESS' } : b)))
    try {
      const res = await orderImportService.generateLabels(id)
      const updated = res.data
      if (updated) {
        setBatches((list) =>
          list.map((b) =>
            b.id === id
              ? { ...b, status: updated.status, savedRows: updated.savedRows, invalidRows: updated.invalidRows }
              : b,
          ),
        )
        // Refresh the expanded rows so tracking numbers show.
        if (updated.rows) setRowsById((m) => ({ ...m, [id]: updated.rows }))
        notify.success(res.message ?? 'Label generation finished.')
      } else {
        await load()
      }
    } catch (e) {
      notify.error(e instanceof Error ? e.message : 'Label generation failed.')
      await load()
    } finally {
      setGeneratingId(null)
    }
  }

  /** Generate a label for a single row inside a batch. */
  const generateRow = async (batchId: number, rowNumber: number) => {
    const key = `${batchId}-${rowNumber}`
    setGenRowKey(key)
    try {
      const res = await orderImportService.generateRowLabel(batchId, rowNumber)
      const updated = res.data
      if (updated) {
        if (updated.rows) setRowsById((m) => ({ ...m, [batchId]: updated.rows }))
        setBatches((list) =>
          list.map((b) =>
            b.id === batchId
              ? { ...b, status: updated.status, savedRows: updated.savedRows, invalidRows: updated.invalidRows }
              : b,
          ),
        )
        notify.success(res.message ?? 'Label generated.')
      }
    } catch (e) {
      notify.error(e instanceof Error ? e.message : 'Label generation failed.')
    } finally {
      setGenRowKey(null)
    }
  }

  const fmtDate = (v?: string | null) => {
    if (!v) return '—'
    const d = new Date(v)
    return Number.isNaN(d.getTime())
      ? v
      : d.toLocaleString('en-US', { month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit' })
  }

  /** Map an import status to a friendly label + pill classes. */
  const statusMeta = (status?: string | null): { label: string; cls: string } => {
    switch ((status || '').toUpperCase()) {
      case 'COMPLETE':
        return { label: 'Complete', cls: 'bg-emerald-50 text-emerald-700 ring-emerald-200' }
      case 'PARTIAL_COMPLETE':
        return { label: 'Partial complete', cls: 'bg-amber-50 text-amber-700 ring-amber-200' }
      case 'IN_PROGRESS':
        return { label: 'In progress', cls: 'bg-sky-50 text-sky-700 ring-sky-200' }
      case 'INITIATE':
        return { label: 'Initiated', cls: 'bg-slate-100 text-slate-600 ring-slate-200' }
      default:
        return { label: status || '—', cls: 'bg-slate-100 text-slate-500 ring-slate-200' }
    }
  }

  return (
    <div className="space-y-4 pb-24">
      <PageSectionHeader
        eyebrow="Operations"
        title="Data history"
        description="Every saved CSV / Excel import. Rows are stored here — no labels are generated on save."
        actions={
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              onClick={() => void load()}
              className="inline-flex items-center gap-1.5 rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[12.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
            >
              <FiRefreshCw className="h-3.5 w-3.5" />
              Refresh
            </button>
            <button
              type="button"
              onClick={() => navigate('/orders')}
              className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3.5 py-2 text-[12.5px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15]"
            >
              <FiArrowLeft className="h-3.5 w-3.5" />
              Back to orders
            </button>
          </div>
        }
      />

      <section className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-sm">
        <div className="flex items-center justify-between border-b border-dashed border-[#e3d9c4] px-5 py-3">
          <span className="inline-flex items-center gap-2 font-mono text-[9px] font-bold uppercase tracking-[0.2em] text-[#b6a684]">
            <FiDatabase className="h-3.5 w-3.5" /> Saved imports
          </span>
          <span className="font-mono text-[9px] font-bold uppercase tracking-[0.16em] tabular-nums text-[#b6a684]">
            {batches.length} {batches.length === 1 ? 'import' : 'imports'}
          </span>
        </div>

        {loading ? (
          <p className="px-5 py-14 text-center text-sm text-[#8a7959]">Loading…</p>
        ) : batches.length === 0 ? (
          <p className="px-5 py-14 text-center text-sm text-[#8a7959]">
            No saved imports yet. Import a CSV/Excel from Orders → Import CSV/Excel, then click Save.
          </p>
        ) : (
          <div className="divide-y divide-[#eee6d6]">
            {batches.map((b) => {
              const open = openId === b.id
              const rows = rowsById[b.id]
              const st = (b.status || '').toUpperCase()
              const canGenerate = st === 'INITIATE' || st === 'PARTIAL_COMPLETE'
              const busy = generatingId === b.id
              return (
                <div key={b.id}>
                  <div className="flex items-stretch transition hover:bg-[#faf7f0]">
                  <button
                    type="button"
                    onClick={() => void toggle(b.id)}
                    className="grid flex-1 grid-cols-[24px_60px_minmax(0,1fr)_130px_120px] items-center gap-3 px-5 py-3 text-left"
                  >
                    <span className="text-[#8a7959]">
                      {open ? <FiChevronDown className="h-4 w-4" /> : <FiChevronRight className="h-4 w-4" />}
                    </span>
                    <span className="font-mono text-[13px] font-bold text-[#1f150c]">#{b.id}</span>
                    <span className="min-w-0">
                      <span className="flex items-center gap-1.5">
                        <FiFileText className="h-3.5 w-3.5 shrink-0 text-[#b6a684]" />
                        <span className="truncate text-[12.5px] font-semibold text-[#1f150c]" title={b.fileName || undefined}>
                          {b.fileName || 'Untitled import'}
                        </span>
                      </span>
                      <span className="mt-0.5 block text-[11px] text-[#8a7959]">
                        {fmtDate(b.createdAt)} · {b.createdBy || '—'}
                      </span>
                    </span>
                    <span className="flex justify-center">
                      {(() => {
                        const s = statusMeta(b.status)
                        return (
                          <span className={`rounded-full px-2.5 py-0.5 text-[10.5px] font-bold ring-1 ${s.cls}`}>
                            {s.label}
                          </span>
                        )
                      })()}
                    </span>
                    <span className="flex items-center justify-end gap-1.5">
                      <span className="rounded-full bg-emerald-50 px-2 py-0.5 text-[10.5px] font-bold text-emerald-700 ring-1 ring-emerald-200">
                        {b.savedRows} saved
                      </span>
                      {b.invalidRows > 0 ? (
                        <span className="rounded-full bg-rose-50 px-2 py-0.5 text-[10.5px] font-bold text-rose-700 ring-1 ring-rose-200">
                          {b.invalidRows} err
                        </span>
                      ) : null}
                    </span>
                  </button>
                  {canGenerate ? (
                    <button
                      type="button"
                      onClick={() => void generate(b.id)}
                      disabled={busy}
                      title={st === 'PARTIAL_COMPLETE' ? 'Retry generating labels for the rows that failed' : 'Generate carrier labels for this saved import'}
                      className="my-2 mr-4 inline-flex shrink-0 items-center gap-1.5 self-center rounded-xl bg-[#1f150c] px-3 py-2 text-[12px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4]"
                    >
                      {busy ? (
                        <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
                      ) : (
                        <FiZap className="h-3.5 w-3.5" />
                      )}
                      {busy ? 'Generating…' : st === 'PARTIAL_COMPLETE' ? 'Retry labels' : 'Generate labels'}
                    </button>
                  ) : null}
                  </div>

                  {open ? (
                    <div className="border-t border-dashed border-[#eee6d6] bg-[#faf7f0]/50 px-5 py-3">
                      {rows === 'loading' || rows === undefined ? (
                        <p className="py-4 text-center text-[12px] text-[#8a7959]">Loading rows…</p>
                      ) : rows.length === 0 ? (
                        <p className="py-4 text-center text-[12px] text-[#8a7959]">No rows stored for this import.</p>
                      ) : (
                        <div className="overflow-auto rounded-xl border border-[#e3d9c4] bg-white">
                          <table className="w-full min-w-[820px] text-left text-[11px] text-[#3f3527]">
                            <thead className="bg-[#faf7f0] text-[9.5px] uppercase tracking-[0.14em] text-[#8a7959]">
                              <tr>
                                <th className="p-2.5">#</th>
                                <th className="p-2.5">Recipient</th>
                                <th className="p-2.5">Address</th>
                                <th className="p-2.5">City / Postal</th>
                                <th className="p-2.5">Carrier</th>
                                <th className="p-2.5 text-right">Weight</th>
                                <th className="p-2.5">Status</th>
                                <th className="p-2.5">Label</th>
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-[#eee6d6]">
                              {rows.map((r) => {
                                const ok = (r.errors?.length ?? 0) === 0
                                const gen = (r.generatedStatus ?? '').toUpperCase()
                                const rowKey = `${b.id}-${r.rowNumber}`
                                const rowBusy = genRowKey === rowKey
                                return (
                                  <tr key={r.rowNumber} className="transition hover:bg-[#faf7f0]/60">
                                    <td className="p-2.5 font-mono text-[10.5px] text-[#8a7959]">{r.rowNumber}</td>
                                    <td className="p-2.5">
                                      <p className="font-semibold text-[#1f150c]">{r.recipientName ?? '—'}</p>
                                      {r.recipientCompany ? (
                                        <p className="text-[10px] text-[#8a7959]">{r.recipientCompany}</p>
                                      ) : null}
                                    </td>
                                    <td className="p-2.5">
                                      <span className="block max-w-[200px] truncate">{r.addressLine1 ?? '—'}</span>
                                    </td>
                                    <td className="p-2.5">
                                      {r.city ?? '—'} · {r.postalCode ?? '—'} {r.countryCode ?? ''}
                                    </td>
                                    <td className="p-2.5">
                                      {r.carrierCode ?? '—'}
                                      {r.accountNumber ? ` · ${r.accountNumber}` : ''}
                                    </td>
                                    <td className="p-2.5 text-right">
                                      {r.weight ?? '—'} {r.weightUnit ?? ''}
                                    </td>
                                    <td className="p-2.5">
                                      {ok ? (
                                        <span className="rounded-full bg-emerald-100 px-1.5 py-0.5 text-[9.5px] font-semibold text-emerald-800">
                                          Saved
                                        </span>
                                      ) : (
                                        <span className="rounded-full bg-rose-100 px-1.5 py-0.5 text-[9.5px] font-semibold text-rose-800">
                                          {r.errors.join(', ')}
                                        </span>
                                      )}
                                    </td>
                                    <td className="p-2.5">
                                      {gen === 'GENERATED' ? (
                                        <span className="inline-flex flex-col gap-0.5">
                                          <span className="w-fit rounded-full bg-emerald-100 px-1.5 py-0.5 text-[9.5px] font-semibold text-emerald-800">
                                            Generated
                                          </span>
                                          {r.generatedTrackingNumber ? (
                                            <span className="font-mono text-[9.5px] text-[#8a7959]">{r.generatedTrackingNumber}</span>
                                          ) : null}
                                        </span>
                                      ) : !ok ? (
                                        <span className="text-[9.5px] text-[#b6a684]">Fix errors first</span>
                                      ) : (
                                        <button
                                          type="button"
                                          onClick={() => void generateRow(b.id, r.rowNumber)}
                                          disabled={rowBusy}
                                          className="inline-flex items-center gap-1 rounded-lg bg-[#1f150c] px-2 py-1 text-[10px] font-semibold text-[#f4eede] transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4]"
                                        >
                                          {rowBusy ? (
                                            <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[#f4eede]/40 border-t-[#f4eede]" />
                                          ) : (
                                            <FiZap className="h-3 w-3" />
                                          )}
                                          {rowBusy ? 'Generating…' : 'Generate label'}
                                        </button>
                                      )}
                                    </td>
                                  </tr>
                                )
                              })}
                            </tbody>
                          </table>
                        </div>
                      )}
                    </div>
                  ) : null}
                </div>
              )
            })}
          </div>
        )}
      </section>
    </div>
  )
}
