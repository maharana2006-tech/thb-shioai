import { useEffect, useMemo, useRef, useState } from 'react'
import { FiCheck, FiHome, FiSearch, FiX } from 'react-icons/fi'
import { notify } from '../../utils/notify'
import { clientAllowedServiceWarehousesService } from '../../api/clientAllowedServiceWarehousesService'
import { clientWarehouseService, type ClientWarehouse } from '../../api/warehouseService'
import { useModalDismiss } from '../../hooks/useModalDismiss'

/**
 * Modal-drawer for the warehouse gate on a single client-allowed-service
 * row (G1). Uses replace-PUT: pick the warehouses the client may fulfil
 * this service from; empty set = unrestricted.
 *
 * The choice list is scoped to warehouses actually attached to the client
 * (loaded via clientWarehouseService.listForClient) so the operator can't
 * gate a service to a warehouse the client can't ship from anyway.
 */
export default function ServiceWarehousesDrawer({
  clientCode,
  serviceId,
  serviceLabel,
  onClose,
  onSaved,
}: {
  clientCode: string
  serviceId: number
  /** e.g. "UPS · 03 — Ground". Shown in the header. */
  serviceLabel: string
  onClose: () => void
  onSaved: () => void
}) {
  // A11y audit — focus trap + Escape-to-close + focus restoration.
  const dialogRef = useRef<HTMLElement>(null)
  useModalDismiss(true, dialogRef, onClose)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [attached, setAttached] = useState<ClientWarehouse[]>([])
  const [selected, setSelected] = useState<Set<number>>(new Set())
  const [search, setSearch] = useState('')

  useEffect(() => {
    let alive = true
    ;(async () => {
      try {
        const [gateResp, attachedResp] = await Promise.all([
          clientAllowedServiceWarehousesService.get(clientCode, serviceId),
          clientWarehouseService.listForClient(clientCode),
        ])
        if (!alive) return
        setSelected(new Set(gateResp.data?.warehouseIds ?? []))
        setAttached((attachedResp.data ?? []).filter((cw) => cw.warehouse !== null))
      } catch (error) {
        if (!alive) return
        notify.apiError(error, 'Failed to load warehouses.')
      } finally {
        if (alive) setLoading(false)
      }
    })()
    return () => { alive = false }
  }, [clientCode, serviceId])

  const filtered = useMemo(() => {
    const q = search.trim().toUpperCase()
    if (!q) return attached
    return attached.filter((cw) => {
      const w = cw.warehouse!
      return w.code.toUpperCase().includes(q) || w.name.toUpperCase().includes(q)
    })
  }, [attached, search])

  const toggle = (warehouseId: number) =>
    setSelected((cur) => {
      const next = new Set(cur)
      if (next.has(warehouseId)) next.delete(warehouseId)
      else next.add(warehouseId)
      return next
    })

  const save = async () => {
    setSaving(true)
    try {
      if (selected.size === 0) {
        await clientAllowedServiceWarehousesService.clear(clientCode, serviceId)
        notify.success('Warehouse gate cleared — any attached warehouse may fulfil.')
      } else {
        await clientAllowedServiceWarehousesService.replace(clientCode, serviceId, {
          warehouseIds: Array.from(selected).sort((a, b) => a - b),
        })
        notify.success(`Warehouse gate saved (${selected.size} warehouse${selected.size === 1 ? '' : 's'}).`)
      }
      onSaved()
    } catch (error) {
      notify.apiError(error, 'Failed to save warehouses.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <div className="fixed inset-0 z-[55] bg-slate-950/50 backdrop-blur-sm" onClick={onClose} aria-hidden="true" />
      <aside
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="svc-wh-title"
        className="fixed inset-y-0 right-0 z-[60] flex w-full max-w-[520px] flex-col border-l border-slate-200 bg-white shadow-[-18px_0_50px_rgba(8,14,26,0.18)]"
      >
        <header className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <p className="text-[10.5px] font-bold uppercase tracking-[0.16em] text-slate-400">
              Warehouse gate
            </p>
            <h3 id="svc-wh-title" className="mt-1 text-[15px] font-semibold text-slate-950">
              {serviceLabel}
            </h3>
            <p className="mt-1 text-[11.5px] leading-5 text-slate-500">
              Warehouses {clientCode} may fulfil this service from. Empty = any attached warehouse.
            </p>
          </div>
          <button
            type="button"
            aria-label="Close"
            onClick={onClose}
            className="rounded-xl border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
          >
            <FiX className="h-4 w-4" />
          </button>
        </header>

        <div className="flex-1 overflow-y-auto px-5 py-4">
          {loading ? (
            <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
              Loading…
            </p>
          ) : attached.length === 0 ? (
            <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
              No warehouses attached to {clientCode}. Attach warehouses in the Warehouses tab first.
            </p>
          ) : (
            <>
              <div className="flex items-center gap-2 rounded-xl border border-slate-200 bg-white px-3 py-1.5">
                <FiSearch className="h-3.5 w-3.5 text-slate-400" />
                <input
                  value={search}
                  onChange={(e) => setSearch(e.target.value)}
                  placeholder="Search code or name…"
                  className="w-full bg-transparent text-[12px] text-slate-950 outline-none placeholder:text-slate-400"
                />
                {search ? (
                  <button
                    type="button"
                    onClick={() => setSearch('')}
                    aria-label="Clear search"
                    className="text-slate-400 hover:text-slate-600"
                  >
                    <FiX className="h-3.5 w-3.5" />
                  </button>
                ) : null}
              </div>

              <div className="mt-3 max-h-[55vh] overflow-y-auto rounded-xl border border-slate-200 bg-white p-2">
                {filtered.length === 0 ? (
                  <p className="px-2 py-3 text-center text-[11.5px] text-slate-500">
                    No matches.
                  </p>
                ) : (
                  <div className="space-y-1">
                    {filtered.map((cw) => {
                      const w = cw.warehouse!
                      const on = selected.has(w.id)
                      return (
                        <button
                          key={w.id}
                          type="button"
                          onClick={() => toggle(w.id)}
                          className={`flex w-full items-center justify-between rounded-lg px-2 py-1.5 text-left text-[11.5px] transition ${
                            on
                              ? 'bg-[#412d15]/10 text-[#412d15]'
                              : 'text-slate-700 hover:bg-slate-100'
                          }`}
                        >
                          <span className="min-w-0 flex items-center gap-2">
                            <FiHome className="h-3 w-3 shrink-0 text-emerald-600" />
                            <span className="min-w-0 truncate">
                              <span className="font-mono text-[10.5px] font-semibold uppercase text-slate-500">{w.code}</span>
                              <span className="ml-2">{w.name}</span>
                              {cw.isDefault ? (
                                <span className="ml-2 rounded-full bg-slate-100 px-1.5 py-0.5 text-[10px] font-semibold text-slate-600">
                                  default
                                </span>
                              ) : null}
                            </span>
                          </span>
                          {on ? <FiCheck className="h-3.5 w-3.5 shrink-0" /> : null}
                        </button>
                      )
                    })}
                  </div>
                )}
              </div>

              <p className="mt-2 text-[11px] text-slate-500">
                {selected.size} selected {selected.size === 0 ? '(unrestricted)' : null}
              </p>
            </>
          )}
        </div>

        <footer className="flex items-center justify-end gap-2 border-t border-slate-100 bg-slate-50/60 px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-[13px] font-semibold text-slate-600 transition hover:bg-slate-100"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={() => void save()}
            disabled={saving || loading || attached.length === 0}
            className="rounded-xl bg-[#1f150c] px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:opacity-50"
          >
            {saving ? 'Saving…' : 'Save warehouses'}
          </button>
        </footer>
      </aside>
    </>
  )
}
