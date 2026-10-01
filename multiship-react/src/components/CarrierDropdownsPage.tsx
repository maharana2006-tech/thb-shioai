/**
 * V120 — /settings/carrier-dropdowns. Read-only view of the three
 * per-carrier dropdown vocabularies (label format, pickup type, clearance
 * option) that the /orders/new wizard draws from. Edit via SQL today;
 * FE wizard-picker swap is a follow-up.
 */
import { useCallback, useEffect, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import {
  carrierDropdownsService,
  type CarrierClearanceOptionRow,
  type CarrierLabelFormatRow,
  type CarrierPickupTypeRow,
} from '../api/carrierDropdownsService'

type Tab = 'labelFormats' | 'pickupTypes' | 'clearance'

const TABS: Array<{ key: Tab; label: string }> = [
  { key: 'labelFormats', label: 'Label formats' },
  { key: 'pickupTypes',  label: 'Pickup types' },
  { key: 'clearance',    label: 'Clearance options' },
]

export default function CarrierDropdownsPage() {
  const outlet = useOutletContext<SettingsOutletContext>()

  const [tab, setTab] = useState<Tab>('labelFormats')
  const [labelFormats, setLabelFormats] = useState<CarrierLabelFormatRow[]>([])
  const [pickupTypes, setPickupTypes] = useState<CarrierPickupTypeRow[]>([])
  const [clearance, setClearance] = useState<CarrierClearanceOptionRow[]>([])
  const [loading, setLoading] = useState(true)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [lf, pt, co] = await Promise.all([
        carrierDropdownsService.labelFormats(),
        carrierDropdownsService.pickupTypes(),
        carrierDropdownsService.clearanceOptions(),
      ])
      setLabelFormats(lf); setPickupTypes(pt); setClearance(co)
    } catch (err) { notify.apiError(err, 'Failed to load carrier dropdowns.') }
    finally { setLoading(false) }
  }, [])

  useEffect(() => { void load() }, [load])
  useEffect(() => {
    outlet.registerRefresh(load)
    return () => outlet.registerRefresh(null)
  }, [outlet, load])

  return (
    <div className="space-y-3">
      <div className="flex gap-1 border-b border-slate-200">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            onClick={() => setTab(t.key)}
            className={`px-3 py-1.5 text-[12.5px] font-semibold ${
              tab === t.key
                ? 'border-b-2 border-slate-900 text-slate-900'
                : 'text-slate-500 hover:text-slate-700'
            }`}
          >{t.label}</button>
        ))}
      </div>

      <section className="rounded-xl border border-slate-200 bg-white shadow-sm">
        {loading ? (
          <div className="px-4 py-6 text-center text-[13px] text-slate-500">Loading…</div>
        ) : tab === 'labelFormats' ? (
          <LabelFormatsTable rows={labelFormats} />
        ) : tab === 'pickupTypes' ? (
          <SimpleTable rows={pickupTypes} />
        ) : (
          <SimpleTable rows={clearance} />
        )}
      </section>
    </div>
  )
}

function LabelFormatsTable({ rows }: { rows: CarrierLabelFormatRow[] }) {
  if (rows.length === 0) return <Empty />
  return (
    <table className="w-full text-[13px]">
      <thead>
        <tr className="text-left text-slate-500">
          <th className="px-3 py-2 font-semibold">Carrier</th>
          <th className="px-3 py-2 font-semibold">Code</th>
          <th className="px-3 py-2 font-semibold">Label</th>
          <th className="px-3 py-2 font-semibold">Stock type?</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={`${row.carrier}:${row.code}`} className="border-t border-slate-100">
            <td className="px-3 py-2 font-mono text-[12px] text-slate-700">{row.carrier}</td>
            <td className="px-3 py-2 font-mono text-[12px] text-slate-700">{row.code}</td>
            <td className="px-3 py-2 text-slate-700">{row.label}</td>
            <td className="px-3 py-2">
              {row.isStockType ? (
                <span className="rounded bg-amber-100 px-2 py-0.5 text-amber-800">stock</span>
              ) : (
                <span className="rounded bg-slate-100 px-2 py-0.5 text-slate-700">plain</span>
              )}
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function SimpleTable({ rows }: { rows: Array<{ carrier: string; code: string; label: string }> }) {
  if (rows.length === 0) return <Empty />
  return (
    <table className="w-full text-[13px]">
      <thead>
        <tr className="text-left text-slate-500">
          <th className="px-3 py-2 font-semibold">Carrier</th>
          <th className="px-3 py-2 font-semibold">Code</th>
          <th className="px-3 py-2 font-semibold">Label</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={`${row.carrier}:${row.code}`} className="border-t border-slate-100">
            <td className="px-3 py-2 font-mono text-[12px] text-slate-700">{row.carrier}</td>
            <td className="px-3 py-2 font-mono text-[12px] text-slate-700">{row.code}</td>
            <td className="px-3 py-2 text-slate-700">{row.label}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function Empty() {
  return (
    <div className="px-4 py-6 text-center text-[13px] text-slate-500">
      No rows seeded. V120 seeds FedEx + UPS + USPS + DHL defaults.
    </div>
  )
}
