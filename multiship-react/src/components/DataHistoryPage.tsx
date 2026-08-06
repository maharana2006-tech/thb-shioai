import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  FiArrowLeft,
  FiChevronDown,
  FiChevronRight,
  FiDatabase,
  FiRefreshCw,
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

  const fmtDate = (v?: string | null) => {
    if (!v) return '—'
    const d = new Date(v)
    return Number.isNaN(d.getTime())
      ? v
      : d.toLocaleString('en-US', { month: 'short', day: 'numeric', year: 'numeric', hour: '2-digit', minute: '2-digit' })
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
              return (
                <div key={b.id}>
                  <button
                    type="button"
                    onClick={() => void toggle(b.id)}
                    className="grid w-full grid-cols-[24px_90px_1fr_140px_120px] items-center gap-3 px-5 py-3 text-left transition hover:bg-[#faf7f0]"
                  >
                    <span className="text-[#8a7959]">
                      {open ? <FiChevronDown className="h-4 w-4" /> : <FiChevronRight className="h-4 w-4" />}
                    </span>
                    <span className="font-mono text-[13px] font-bold text-[#1f150c]">#{b.id}</span>
                    <span className="text-[12.5px] text-[#3f3527]">{fmtDate(b.createdAt)}</span>
                    <span className="text-[12px] text-[#8a7959]">{b.createdBy || '—'}</span>
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
                              </tr>
                            </thead>
                            <tbody className="divide-y divide-[#eee6d6]">
                              {rows.map((r) => {
                                const ok = (r.errors?.length ?? 0) === 0
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
