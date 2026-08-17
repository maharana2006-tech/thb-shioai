import { useEffect, useState } from 'react'
import { notify } from '../../utils/notify'
import {
  clientBillingMarkupService,
  type MarkupKind,
} from '../../api/clientPolicyService'
import { addMoney, applyMarkupPercent, fromCents, toCents } from '../../utils/money'

const COMMON_CURRENCIES = ['USD', 'EUR', 'GBP', 'CAD', 'AUD', 'INR', 'JPY', 'SGD', 'AED']

/**
 * Markup tab — kind (PERCENT | FLAT) + non-negative value + ISO-4217
 * currency. Absent row = zero markup, USD. The backend snapshots kind + value
 * onto every shipment at label time so a later change here doesn't move
 * historical bills.
 */
export default function ClientMarkupTab({ clientCode }: { clientCode: string }) {
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)

  const [kind, setKind] = useState<MarkupKind>('PERCENT')
  const [valueStr, setValueStr] = useState('0')
  const [currency, setCurrency] = useState('USD')

  const load = async () => {
    setLoading(true)
    try {
      const r = await clientBillingMarkupService.get(clientCode)
      const m = r.data
      if (m) {
        setKind((m.kind as MarkupKind) || 'PERCENT')
        // Store the value as a string in local state so partial edits ("12.")
        // don't fight the numeric parse.
        setValueStr(m.value != null ? String(m.value) : '0')
        setCurrency(m.currency || 'USD')
      }
    } catch (error) {
      notify.apiError(error, 'Failed to load billing markup.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data fetch on client change; load() sets loading + result state
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clientCode])

  const save = async () => {
    if (saving) return
    const parsed = Number(valueStr)
    if (!Number.isFinite(parsed) || parsed < 0) {
      notify.error('Value must be a non-negative number.')
      return
    }
    if (!/^[A-Za-z]{3}$/.test(currency)) {
      notify.error('Currency must be a 3-letter ISO code (e.g. USD).')
      return
    }
    setSaving(true)
    try {
      await clientBillingMarkupService.update(clientCode, {
        kind,
        value: parsed,
        currency: currency.toUpperCase(),
      })
      notify.success(`Markup saved for ${clientCode}.`)
      await load()
    } catch (error) {
      // MARKUP_INVALID falls through the friendly-message map (no entry) to the
      // raw server message, which is what the inline branch surfaced too.
      notify.apiError(error, 'Failed to save the markup.')
    } finally {
      setSaving(false)
    }
  }

  // Sprint 49 Tier 4 Fix 5 — cents-integer arithmetic so the preview
  // matches what the backend computes (no 0.1 + 0.2 drift, no
  // toFixed(2) inconsistency).
  const preview = () => {
    const parsed = Number(valueStr)
    if (!Number.isFinite(parsed) || parsed < 0) return null
    const carrierRateCents = toCents(25)  // illustrative reference rate
    const billableCents = kind === 'PERCENT'
      ? applyMarkupPercent(carrierRateCents, parsed)
      : addMoney(carrierRateCents, toCents(parsed))
    return {
      carrierRate: fromCents(carrierRateCents, currency),
      billable: fromCents(billableCents, currency),
    }
  }

  const p = preview()

  return (
    <div className="flex-1 overflow-y-auto px-5 py-4" role="tabpanel" id="client-editor-panel-markup">
      <h4 className="text-[12.5px] font-semibold text-slate-950">Billing markup</h4>
      <p className="text-[11px] leading-5 text-slate-500">
        Applied on top of the carrier rate at label time. Kind + value are snapshotted onto the shipment so a later change here won't shift historical bills.
      </p>

      {loading ? (
        <p className="mt-4 rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
          Loading…
        </p>
      ) : (
        <>
          <div className="mt-3 grid gap-2 sm:grid-cols-2">
            {(['PERCENT', 'FLAT'] as const).map((k) => (
              <label
                key={k}
                className={`inline-flex cursor-pointer items-center gap-2 rounded-xl border px-3 py-2 text-[12px] font-semibold transition ${
                  kind === k
                    ? 'border-[#412d15] bg-[#412d15]/5 text-[#412d15]'
                    : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
                }`}
              >
                <input
                  type="radio"
                  name="markup-kind"
                  value={k}
                  checked={kind === k}
                  onChange={() => setKind(k)}
                  className="sr-only"
                />
                {k === 'PERCENT' ? 'Percent of rate' : 'Flat per shipment'}
              </label>
            ))}
          </div>

          <div className="mt-3 grid gap-3 sm:grid-cols-2">
            <div>
              <label className="mb-1 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
                Value {kind === 'PERCENT' ? '(%)' : ''}
                <span className="ml-1 text-rose-500">*</span>
              </label>
              <input
                type="text"
                inputMode="decimal"
                value={valueStr}
                onChange={(e) => setValueStr(e.target.value)}
                placeholder={kind === 'PERCENT' ? '12.5' : '3.50'}
                className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] font-semibold text-slate-950 outline-none transition focus:border-[#412d15]"
              />
            </div>
            <div>
              <label className="mb-1 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
                Currency <span className="text-rose-500">*</span>
              </label>
              <input
                list="markup-currency-suggestions"
                value={currency}
                onChange={(e) => setCurrency(e.target.value.toUpperCase())}
                maxLength={3}
                placeholder="USD"
                className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] font-semibold uppercase text-slate-950 outline-none transition focus:border-[#412d15]"
              />
              <datalist id="markup-currency-suggestions">
                {COMMON_CURRENCIES.map((c) => (
                  <option key={c} value={c} />
                ))}
              </datalist>
            </div>
          </div>

          {p ? (
            <div className="mt-3 rounded-2xl border border-slate-200 bg-slate-50/60 p-3.5 text-[11.5px] text-slate-600">
              <p className="text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-400">
                Preview
              </p>
              <p className="mt-1">
                On a{' '}
                <span className="font-semibold text-slate-800">{p.carrierRate}</span>{' '}
                carrier rate, this markup produces a billable amount of{' '}
                <span className="font-semibold text-slate-950">{p.billable}</span>.
              </p>
            </div>
          ) : null}

          <div className="mt-4 flex items-center justify-end">
            <button
              type="button"
              onClick={() => void save()}
              disabled={saving}
              className="rounded-xl bg-[#1f150c] px-4 py-2 text-[12.5px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:opacity-50"
            >
              {saving ? 'Saving…' : 'Save markup'}
            </button>
          </div>
        </>
      )}
    </div>
  )
}
