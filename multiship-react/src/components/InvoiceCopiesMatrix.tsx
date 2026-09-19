import { useCallback, useEffect, useMemo, useState } from 'react'
import { FiCheck, FiTruck } from 'react-icons/fi'
import { accountRefService, type CarrierAccountRef } from '../api/accountRefService'
import { printerService, type InvoiceCopiesRule } from '../api/printerService'
import { notify } from '../utils/notify'

/**
 * PR-Printer-R7b — commercial-invoice copies matrix. Rows = clients
 * (+ Default row for tenant-wide default per carrier). Columns =
 * distinct active carrier codes from the tenant's CarrierAccountRefs.
 * Cell = integer input (1..20) with save-on-blur.
 *
 * <p>Empty cell = no rule set. Resolves to the Default row's value at
 * print time; if neither is set, backend hardcodes 1. See
 * {@code InvoiceCopiesService.resolveCopies} for the fallback chain.
 *
 * <p>Carriers are drawn from {@link CarrierAccountRef} entries that
 * are active + complete — matches "carriers the tenant is actually
 * using" without waiting on an admin to configure every carrier code
 * we ever supported.
 */
export default function InvoiceCopiesMatrix({
  clients,
  onChanged,
}: {
  /** Uppercased client codes (in order). `null` = Default. */
  clients: Array<string | null>
  /** Called after any successful save so the parent can refetch state. */
  onChanged?: () => void | Promise<void>
}) {
  const [rules, setRules] = useState<InvoiceCopiesRule[]>([])
  const [accounts, setAccounts] = useState<CarrierAccountRef[]>([])
  const [loading, setLoading] = useState(true)
  const [savingKey, setSavingKey] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [rulesRes, accs] = await Promise.all([
        printerService.listInvoiceCopies(),
        accountRefService.listAccounts(),
      ])
      setRules(rulesRes.data ?? [])
      setAccounts(accs)
    } catch (err) {
      notify.apiError(err, 'Could not load invoice copies')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    queueMicrotask(() => { if (!cancelled) void load() })
    return () => { cancelled = true }
  }, [load])

  // Distinct carrier codes from active+complete accounts. Sorted for
  // stable column order across renders.
  const carriers = useMemo(() => {
    const seen = new Set<string>()
    for (const a of accounts) {
      if (!a.active || !a.complete) continue
      const c = a.carrierCode?.trim().toUpperCase()
      if (c) seen.add(c)
    }
    return [...seen].sort()
  }, [accounts])

  // Fast lookup: `${client}|${carrier}` → rule.
  const byCell = useMemo(() => {
    const m = new Map<string, InvoiceCopiesRule>()
    for (const r of rules) {
      const key = `${(r.clientCode ?? '__DEFAULT__').toUpperCase()}|${r.carrierCode.toUpperCase()}`
      m.set(key, r)
    }
    return m
  }, [rules])
  const cellRule = (client: string | null, carrier: string) =>
    byCell.get(`${(client ?? '__DEFAULT__').toUpperCase()}|${carrier.toUpperCase()}`)

  const save = async (client: string | null, carrier: string, copies: number | null) => {
    const key = `${(client ?? '__DEFAULT__').toUpperCase()}|${carrier}`
    if (savingKey === key) return
    setSavingKey(key)
    try {
      if (copies == null) {
        // Empty input = delete the rule (fall back to Default row / 1).
        if (client === null) {
          await printerService.deleteInvoiceCopiesTenantDefault(carrier)
        } else {
          await printerService.deleteInvoiceCopiesClientRule(carrier, client)
        }
      } else {
        if (copies < 1 || copies > 20) {
          notify.error({ title: 'Invalid copies', body: 'Copies must be between 1 and 20.' })
          return
        }
        await printerService.upsertInvoiceCopies(client, carrier, copies)
      }
      await load()
      if (onChanged) await onChanged()
    } catch (err) {
      notify.apiError(err, `Could not save copies for ${carrier}`)
    } finally {
      setSavingKey(null)
    }
  }

  if (loading) {
    return (
      <div className="rounded-xl border border-slate-200 bg-white p-6 text-center text-[13px] text-slate-500">
        Loading invoice copies…
      </div>
    )
  }

  if (carriers.length === 0) {
    return (
      <div className="rounded-xl border border-slate-200 bg-white p-6 text-center text-[13px] text-slate-500">
        No connected carriers. Connect at least one carrier account (Settings → Carriers)
        before configuring invoice copies.
      </div>
    )
  }

  return (
    <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white">
      <table className="min-w-full text-[13px]">
        <thead className="bg-slate-50 text-left text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">
          <tr>
            <th className="sticky left-0 z-10 bg-slate-50 px-3 py-2 shadow-[8px_0_8px_-8px_rgba(15,23,42,0.15)]">
              Client
            </th>
            {carriers.map((c) => (
              <th key={c} className="px-3 py-2 whitespace-nowrap">
                <span className="flex items-center gap-1.5 text-slate-700">
                  <FiTruck className="h-3.5 w-3.5 text-slate-400" />
                  {c}
                </span>
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {clients.map((client) => (
            <tr key={client ?? 'default'}>
              <td className="sticky left-0 z-10 bg-white px-3 py-2.5 shadow-[8px_0_8px_-8px_rgba(15,23,42,0.15)]">
                {client === null ? (
                  <span>
                    <span className="block font-semibold text-slate-900">Default</span>
                    <span className="block text-[11.5px] text-slate-500">Tenant-wide fallback per carrier</span>
                  </span>
                ) : (
                  <span className="font-semibold text-slate-900">{client}</span>
                )}
              </td>
              {carriers.map((carrier) => {
                const rule = cellRule(client, carrier)
                const key = `${(client ?? '__DEFAULT__').toUpperCase()}|${carrier}`
                return (
                  <td key={carrier} className="px-3 py-2.5">
                    <CopiesInput
                      // PR-R7b — re-key on value change so the child
                      // resets its local `text` state without the
                      // "sync setState in effect → cascading render" lint.
                      key={`${key}#${rule?.copies ?? ''}`}
                      value={rule?.copies ?? null}
                      saving={savingKey === key}
                      onSave={(v) => void save(client, carrier, v)}
                    />
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

/** Bounded 1..20 input. Save-on-blur or Enter. Empty on blur → delete rule. */
function CopiesInput({
  value,
  saving,
  onSave,
}: {
  value: number | null
  saving: boolean
  onSave: (next: number | null) => void
}) {
  const [text, setText] = useState<string>(value == null ? '' : String(value))
  const [dirty, setDirty] = useState(false)

  // No useEffect needed for prop reflection: the parent re-keys this
  // component whenever the underlying rule changes, forcing a fresh
  // mount that picks up the new `value` via the useState initializer.

  const commit = () => {
    if (!dirty || saving) return
    const trimmed = text.trim()
    if (!trimmed) { onSave(null); return }
    const n = Number(trimmed)
    if (!Number.isFinite(n) || !Number.isInteger(n)) { setText(value == null ? '' : String(value)); setDirty(false); return }
    onSave(n)
  }

  return (
    <span className="relative inline-flex items-center gap-1">
      <input
        type="number"
        min={1}
        max={20}
        step={1}
        value={text}
        onChange={(e) => { setText(e.target.value); setDirty(true) }}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') { e.preventDefault(); commit() }
          else if (e.key === 'Escape') { setText(value == null ? '' : String(value)); setDirty(false); (e.target as HTMLInputElement).blur() }
        }}
        disabled={saving}
        placeholder="—"
        aria-label="Copies (empty = use default)"
        className={
          'w-14 rounded-md border px-2 py-1 text-center text-[13px] outline-none '
          + (dirty
            ? 'border-amber-300 bg-amber-50'
            : 'border-slate-200 bg-white focus:border-slate-400')
          + ' disabled:opacity-50'
        }
      />
      {saving ? (
        <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-slate-300 border-t-slate-700" />
      ) : dirty ? (
        <FiCheck className="h-3 w-3 text-amber-600" title="Unsaved — press Enter or click away to save" />
      ) : null}
    </span>
  )
}

