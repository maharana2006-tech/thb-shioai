import { useEffect, useMemo, useState } from 'react'
import { FiCheck, FiLink, FiSearch } from 'react-icons/fi'
import { notify } from '../../utils/notify'
import { clientService, type Client } from '../../api/clientService'
import { clientWarehouseService, type Warehouse } from '../../api/warehouseService'

/**
 * Pick clients to attach a warehouse to. Rendered inline inside a modal shell
 * (both the post-create step in WarehouseEditorModal and the standalone
 * AttachClientsModal share this body/footer).
 *
 * For CLIENT-owned warehouses only the owning client is a valid target, so the
 * picker is filtered to that single row. When exactly one client is selected,
 * a "Make default" toggle is offered — with multi-attach, per-client defaults
 * would need more UI than this step is worth, so it's suppressed.
 */
export default function AttachClientsStep({
  warehouse,
  onDone,
  skipLabel = 'Skip',
  confirmLabel = 'Attach & finish',
}: {
  warehouse: Warehouse
  onDone: () => void
  skipLabel?: string
  confirmLabel?: string
}) {
  const ownerRestricted =
    (warehouse.ownerType || '').toUpperCase() === 'CLIENT' && !!warehouse.ownerClientCode

  const [clients, setClients] = useState<Client[]>([])
  const [loading, setLoading] = useState(true)
  const [query, setQuery] = useState('')
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [makeDefault, setMakeDefault] = useState(false)
  const [attaching, setAttaching] = useState(false)

  useEffect(() => {
    let cancelled = false
    // eslint-disable-next-line react-hooks/set-state-in-effect -- flip loading spinner before async client-list fetch
    setLoading(true)
    clientService
      .listClients({ size: 200, sortBy: 'code', sortDirection: 'ASC' })
      .then((r) => {
        if (cancelled) return
        const all = r.data?.content ?? []
        setClients(
          ownerRestricted
            ? all.filter((c) => c.clientCode === warehouse.ownerClientCode)
            : all,
        )
      })
      .catch(() => { /* notify layer covers it */ })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [ownerRestricted, warehouse.ownerClientCode])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return clients
    return clients.filter(
      (c) => c.clientCode.toLowerCase().includes(q) || c.name.toLowerCase().includes(q),
    )
  }, [clients, query])

  const toggle = (code: string) => {
    setSelected((prev) => {
      const next = new Set(prev)
      if (next.has(code)) next.delete(code)
      else next.add(code)
      return next
    })
  }

  const attachSelected = async () => {
    if (selected.size === 0 || attaching) return
    setAttaching(true)
    const codes = Array.from(selected)
    const shouldSetDefault = codes.length === 1 && makeDefault
    let ok = 0
    const failures: string[] = []
    for (const clientCode of codes) {
      try {
        await clientWarehouseService.attach(clientCode, {
          warehouseCode: warehouse.code,
          makeDefault: shouldSetDefault,
        })
        ok += 1
      } catch (error) {
        failures.push(
          `${clientCode}: ${error instanceof Error ? error.message : 'failed'}`,
        )
      }
    }
    setAttaching(false)
    if (ok > 0) {
      notify.success(
        `Attached ${warehouse.code} to ${ok} client${ok === 1 ? '' : 's'}${shouldSetDefault ? ' (default)' : ''}.`,
      )
    }
    if (failures.length) {
      notify.error(`Some attachments failed:\n${failures.join('\n')}`)
    }
    onDone()
  }

  return (
    <>
      <div className="max-h-[60vh] space-y-3 overflow-y-auto px-5 py-5">
        <div className="relative">
          <FiSearch className="pointer-events-none absolute left-3 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-400" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search clients by code or name…"
            className="w-full rounded-xl border border-slate-200 bg-white pl-8 pr-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15]"
          />
        </div>

        {loading ? (
          <p className="py-8 text-center text-[12px] text-slate-500">Loading clients…</p>
        ) : filtered.length === 0 ? (
          <p className="py-8 text-center text-[12px] text-slate-500">
            {ownerRestricted
              ? `Owner client ${warehouse.ownerClientCode} is not currently loaded.`
              : query
                ? 'No clients match that search.'
                : 'No clients found.'}
          </p>
        ) : (
          <ul className="divide-y divide-slate-100 rounded-xl border border-slate-200 bg-white">
            {filtered.map((c) => {
              const checked = selected.has(c.clientCode)
              return (
                <li key={c.clientCode}>
                  <label className="flex cursor-pointer items-center gap-3 px-3 py-2.5 transition hover:bg-slate-50">
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => toggle(c.clientCode)}
                      className="h-4 w-4 rounded border-slate-300 text-[#412d15] focus:ring-[#412d15]"
                    />
                    <span className="flex-1">
                      <span className="block text-[13px] font-semibold text-slate-900">
                        {c.clientCode}
                      </span>
                      <span className="block text-[11.5px] text-slate-500">{c.name}</span>
                    </span>
                    {checked ? <FiCheck className="h-4 w-4 text-emerald-600" /> : null}
                  </label>
                </li>
              )
            })}
          </ul>
        )}

        {selected.size === 1 ? (
          <label className="flex items-center gap-2 rounded-xl border border-slate-100 bg-slate-50/60 px-3 py-2.5 text-[12.5px] font-semibold text-slate-700">
            <input
              type="checkbox"
              checked={makeDefault}
              onChange={(e) => setMakeDefault(e.target.checked)}
              className="h-4 w-4 rounded border-slate-300 text-[#412d15] focus:ring-[#412d15]"
            />
            Set as this client's default warehouse
          </label>
        ) : null}
      </div>

      <footer className="flex items-center justify-between gap-2 border-t border-slate-100 bg-slate-50/60 px-5 py-3">
        <span className="text-[11.5px] text-slate-500">
          {selected.size
            ? `${selected.size} client${selected.size === 1 ? '' : 's'} selected`
            : 'Select clients to attach, or skip.'}
        </span>
        <div className="flex items-center gap-2">
          <button
            type="button"
            onClick={onDone}
            className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-[13px] font-semibold text-slate-600 transition hover:bg-slate-100"
          >
            {skipLabel}
          </button>
          <button
            type="button"
            onClick={attachSelected}
            disabled={selected.size === 0 || attaching}
            className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:opacity-50"
          >
            <FiLink className="h-3.5 w-3.5" />
            {attaching ? 'Attaching…' : confirmLabel}
          </button>
        </div>
      </footer>
    </>
  )
}
