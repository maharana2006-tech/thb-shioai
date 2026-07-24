import { useEffect, useMemo, useState } from 'react'
import { notify } from '../../utils/notify'
import { ApiError } from '../../api/apiClient'
import {
  clientShippingPolicyService,
  type RateStrategy,
} from '../../api/clientPolicyService'
import {
  clientAllowedServicesService,
  type ClientAllowedService,
} from '../../api/clientCatalogService'
import { formatCarrierName } from '../../utils/carrierUtils'
import Select from '../workspace/Select'

/**
 * A short, curated list of common IANA zones; the input also accepts anything
 * the user types (useful for regional-warehouse zones the picker doesn't have).
 */
const COMMON_TZ = [
  'UTC',
  'America/New_York',
  'America/Chicago',
  'America/Denver',
  'America/Los_Angeles',
  'America/Anchorage',
  'Pacific/Honolulu',
  'Europe/London',
  'Europe/Paris',
  'Europe/Berlin',
  'Europe/Amsterdam',
  'Asia/Kolkata',
  'Asia/Singapore',
  'Asia/Tokyo',
  'Asia/Shanghai',
  'Asia/Dubai',
  'Australia/Sydney',
]

/**
 * Policy tab — rate strategy + fixed-service picker (only when strategy is
 * FIXED) + dispatch cutoff time / zone. Cutoff is a soft flag: after cutoff,
 * label calls still succeed but the response carries dispatchNextBusinessDay.
 */
export default function ClientPolicyTab({ clientCode }: { clientCode: string }) {
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)

  const [rateStrategy, setRateStrategy] = useState<RateStrategy>('CHEAPEST')
  const [fixedServiceId, setFixedServiceId] = useState<string>('')
  const [cutoffTime, setCutoffTime] = useState('') // HH:mm
  const [cutoffTz, setCutoffTz] = useState('')

  const [allowedServices, setAllowedServices] = useState<ClientAllowedService[]>([])

  const load = async () => {
    setLoading(true)
    try {
      const [policyResp, allowedResp] = await Promise.all([
        clientShippingPolicyService.get(clientCode),
        clientAllowedServicesService.listForClient(clientCode),
      ])
      const p = policyResp.data
      if (p) {
        setRateStrategy((p.rateStrategy as RateStrategy) || 'CHEAPEST')
        setFixedServiceId(p.fixedServiceId != null ? String(p.fixedServiceId) : '')
        // Backend stores LocalTime as HH:mm:ss; the <input type="time"> control
        // wants HH:mm. Strip seconds so the existing value round-trips.
        setCutoffTime((p.cutoffTime || '').slice(0, 5))
        setCutoffTz(p.cutoffTz || '')
      }
      setAllowedServices(allowedResp.data ?? [])
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'Failed to load shipping policy.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void load()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clientCode])

  const fixedServiceRequired = rateStrategy === 'FIXED'

  const tzOptions = useMemo(() => {
    // Ensure the currently saved zone appears in the picker even if it's not
    // in the curated list (user may have typed a custom one previously).
    if (cutoffTz && !COMMON_TZ.includes(cutoffTz)) return [cutoffTz, ...COMMON_TZ]
    return COMMON_TZ
  }, [cutoffTz])

  const save = async () => {
    if (saving) return
    if (fixedServiceRequired && !fixedServiceId) {
      notify.error('Pick a fixed service (it must already be on the client\'s allowlist).')
      return
    }
    setSaving(true)
    try {
      await clientShippingPolicyService.update(clientCode, {
        rateStrategy,
        fixedServiceId: fixedServiceRequired ? Number(fixedServiceId) : null,
        cutoffTime: cutoffTime || null,
        cutoffTz: cutoffTime && cutoffTz ? cutoffTz : null,
      })
      notify.success(`Policy saved for ${clientCode}.`)
      await load()
    } catch (error) {
      if (error instanceof ApiError && error.errorCode === 'POLICY_FIXED_SERVICE_REQUIRED') {
        notify.error(error.message || 'Pick a fixed service on the client allowlist.')
      } else {
        notify.error(error instanceof Error ? error.message : 'Failed to save the policy.')
      }
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="flex-1 overflow-y-auto px-5 py-4" role="tabpanel" id="client-editor-panel-policy">
      <h4 className="text-[12.5px] font-semibold text-slate-950">Shipping policy</h4>
      <p className="text-[11px] leading-5 text-slate-500">
        Rate-shopping strategy + dispatch cutoff. Absent policy = platform defaults (CHEAPEST, no cutoff).
      </p>

      {loading ? (
        <p className="mt-4 rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
          Loading…
        </p>
      ) : (
        <>
          {/* Strategy */}
          <div className="mt-3 grid gap-2 sm:grid-cols-3">
            {(['CHEAPEST', 'FASTEST', 'FIXED'] as const).map((s) => (
              <label
                key={s}
                className={`inline-flex cursor-pointer items-center gap-2 rounded-xl border px-3 py-2 text-[12px] font-semibold transition ${
                  rateStrategy === s
                    ? 'border-[#412d15] bg-[#412d15]/5 text-[#412d15]'
                    : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
                }`}
              >
                <input
                  type="radio"
                  name="rate-strategy"
                  value={s}
                  checked={rateStrategy === s}
                  onChange={() => setRateStrategy(s)}
                  className="sr-only"
                />
                {s === 'CHEAPEST' ? 'Cheapest' : s === 'FASTEST' ? 'Fastest' : 'Fixed service'}
              </label>
            ))}
          </div>

          {fixedServiceRequired ? (
            <div className="mt-3">
              <label className="mb-1 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
                Fixed service <span className="text-rose-500">*</span>
              </label>
              {allowedServices.length === 0 ? (
                <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-[11.5px] text-slate-500">
                  This client has no allowed services yet. Add some in the Services tab first, then a Fixed strategy can point at one.
                </p>
              ) : (
                <Select
                  value={fixedServiceId}
                  onChange={(e) => setFixedServiceId(e.target.value)}
                  aria-label="Fixed service"
                >
                  <option value="">Select service…</option>
                  {allowedServices.map((s) => (
                    <option key={s.serviceId} value={String(s.serviceId)}>
                      {formatCarrierName(s.carrier || '—')} · {s.serviceCode || '—'} — {s.serviceName || '—'}
                    </option>
                  ))}
                </Select>
              )}
            </div>
          ) : null}

          {/* Cutoff */}
          <div className="mt-4 rounded-2xl border border-slate-200 bg-slate-50/60 p-3.5">
            <h5 className="text-[11.5px] font-semibold text-slate-950">Dispatch cutoff</h5>
            <p className="text-[10.5px] leading-4 text-slate-500">
              Labels created after cutoff still succeed — the response carries dispatchNextBusinessDay so the WMS knows.
            </p>
            <div className="mt-2 grid gap-3 sm:grid-cols-2">
              <div>
                <label className="mb-1 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
                  Time (HH:MM)
                </label>
                <input
                  type="time"
                  value={cutoffTime}
                  onChange={(e) => setCutoffTime(e.target.value)}
                  className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15]"
                />
              </div>
              <div>
                <label className="mb-1 block text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
                  Time zone {cutoffTime ? <span className="text-rose-500">*</span> : null}
                </label>
                <Select
                  value={cutoffTz}
                  onChange={(e) => setCutoffTz(e.target.value)}
                  aria-label="Cutoff timezone"
                  disabled={!cutoffTime}
                >
                  <option value="">Select zone…</option>
                  {tzOptions.map((z) => (
                    <option key={z} value={z}>
                      {z}
                    </option>
                  ))}
                </Select>
              </div>
            </div>
            {cutoffTime && !cutoffTz ? (
              <p className="mt-2 text-[10.5px] text-rose-600">
                Pick a time zone or clear the cutoff.
              </p>
            ) : null}
          </div>

          <div className="mt-4 flex items-center justify-end">
            <button
              type="button"
              onClick={() => void save()}
              disabled={saving || (!!cutoffTime && !cutoffTz)}
              className="rounded-xl bg-[#1f150c] px-4 py-2 text-[12.5px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:opacity-50"
            >
              {saving ? 'Saving…' : 'Save policy'}
            </button>
          </div>
        </>
      )}
    </div>
  )
}
