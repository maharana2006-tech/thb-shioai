import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { FiTruck, FiCheckSquare, FiRefreshCw, FiClock, FiCalendar, FiMapPin, FiPackage } from 'react-icons/fi'
import { pickupService, type PickupRequest, type PickupServiceType } from '../api/pickupService'
import { manifestService, type EodEvent, type ManifestResponse } from '../api/manifestService'
import { clientService, type Client } from '../api/clientService'
import { settingsPaths } from '../routes/workspaceRoutes'
import { isAbortError } from '../api/apiClient'
import { useConnectedCarriers } from '../hooks/useConnectedCarriers'
import { notify } from '../utils/notify'

/**
 * Settings → Carriers → Pickups & End-of-Day.
 *
 * Two operator actions on one page, both per-carrier:
 *   1. Schedule a courier pickup (POST /pickups).
 *   2. Close out the day — manifest / SCAN form (POST /manifests/close-day):
 *      the backend gathers that day's open labels for the carrier, so the
 *      operator doesn't list tracking numbers.
 * Plus a recent-activity log, and a pointer to the DTC Scheduler where the
 * close can be automated (CARRIER_CLOSEOUT job).
 */

const PICKUP_CARRIERS = ['FEDEX', 'UPS', 'USPS', 'DHL'] as const
// DHL manifests implicitly via pickup; USPS-Direct isn't wired for close-out.
const CLOSE_CARRIERS = ['FEDEX', 'UPS', 'USPS'] as const
const SERVICE_TYPES: PickupServiceType[] = ['GROUND', 'EXPRESS', 'INTERNATIONAL']

const label = 'mb-1 block text-[10.5px] font-semibold uppercase tracking-[0.1em] text-[#b6a684]'
const input =
  'w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[13px] text-[#1f150c] outline-none transition focus:border-[#412d15] focus:ring-4 focus:ring-[#412d15]/10'
const primaryBtn =
  'inline-flex items-center justify-center gap-1.5 rounded-xl bg-[#1f150c] px-4 py-2.5 text-[13px] font-semibold text-white shadow-sm transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#cdbf9f]'

const today = () => new Date().toISOString().slice(0, 10)

/** Section card with an icon-badge header bar — the look used across the
 *  pickup/close modals, for a consistent feature design. */
function Card({ icon, title, hint, action, children }: {
  icon: ReactNode; title: string; hint?: string; action?: ReactNode; children: ReactNode
}) {
  return (
    <section className="overflow-hidden rounded-2xl border border-[#e3d9c4] bg-white shadow-sm">
      <div className="flex items-center gap-2.5 border-b border-[#eee6d6] bg-[#faf7f0]/50 px-4 py-2.5">
        <span className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-[#1f150c] text-[#f4eede]">{icon}</span>
        <h3 className="text-[13px] font-semibold text-[#1f150c]">{title}</h3>
        {hint ? <span className="text-[10.5px] text-[#b6a684]">{hint}</span> : null}
        {action ? <div className="ml-auto">{action}</div> : null}
      </div>
      <div className="p-4">{children}</div>
    </section>
  )
}

/** Thin labelled divider that groups fields inside a form card. */
function GroupLabel({ children }: { children: ReactNode }) {
  return (
    <div className="mb-2 mt-3 flex items-center gap-2 first:mt-0">
      <span className="text-[10px] font-bold uppercase tracking-[0.14em] text-[#a1906d]">{children}</span>
      <span className="h-px flex-1 bg-[#eee6d6]" />
    </div>
  )
}

function StatusPill({ status }: { status: string }) {
  const s = (status || '').toUpperCase()
  const tone = s === 'MANIFESTED' || s === 'SCHEDULED'
    ? 'bg-emerald-50 text-emerald-700'
    : s === 'PARTIAL' ? 'bg-amber-50 text-amber-700'
      : s === 'EMPTY' || s === 'NOT_SUPPORTED' ? 'bg-slate-100 text-slate-500'
        : 'bg-rose-50 text-rose-700'
  return <span className={`inline-block rounded-full px-2 py-0.5 text-[10.5px] font-bold ${tone}`}>{s || '—'}</span>
}

export default function PickupsEodPage() {
  const [clients, setClients] = useState<Client[]>([])
  const [events, setEvents] = useState<EodEvent[]>([])
  const [loadingEvents, setLoadingEvents] = useState(false)
  const [page, setPage] = useState(0)
  const PAGE_SIZE = 6

  // Only carriers connected on this app; null (loading/failed) = show all.
  const connected = useConnectedCarriers()
  const closeCarriers = connected && connected.size > 0
    ? CLOSE_CARRIERS.filter((c) => connected.has(c)) : [...CLOSE_CARRIERS]
  const pickupCarriers = connected && connected.size > 0
    ? PICKUP_CARRIERS.filter((c) => connected.has(c)) : [...PICKUP_CARRIERS]

  // ── Close-out form ──
  const [closeCarrier, setCloseCarrier] = useState<string>('FEDEX')
  const [closeClient, setCloseClient] = useState('')
  const [closeWarehouse, setCloseWarehouse] = useState('')
  const [closeDate, setCloseDate] = useState(today())
  const [closing, setClosing] = useState(false)
  const [closeResult, setCloseResult] = useState<ManifestResponse | null>(null)

  // ── Pickup form ──
  const [pickup, setPickup] = useState<PickupRequest>({
    carrierCode: 'FEDEX', customerNo: '', pickupDate: today(),
    pickupWindowStart: '09:00', pickupWindowEnd: '17:00',
    pickupServiceType: 'GROUND',
    contactName: '', contactPhone: '',
    addressLine1: '', addressLine2: '', city: '', state: '', postalCode: '', countryCode: 'US',
    packageCount: 1, totalWeight: 1, weightUnit: 'LB', specialInstructions: '',
  })
  const [scheduling, setScheduling] = useState(false)

  const setP = (k: keyof PickupRequest) => (v: string | number) =>
    setPickup((cur) => ({ ...cur, [k]: v }))

  // When connected carriers load, move off a default carrier that isn't connected.
  useEffect(() => {
    if (!connected || connected.size === 0) return
    if (!connected.has(closeCarrier) && closeCarriers.length > 0) setCloseCarrier(closeCarriers[0])
    if (!connected.has(pickup.carrierCode) && pickupCarriers.length > 0) {
      setPickup((cur) => ({ ...cur, carrierCode: pickupCarriers[0] }))
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [connected])

  const loadEvents = useCallback(async () => {
    setLoadingEvents(true)
    try {
      setEvents((await manifestService.recentEvents(50)).data ?? [])
      setPage(0)
    } catch (e) {
      if (!isAbortError(e)) notify.apiError(e, 'Could not load recent activity.')
    } finally {
      setLoadingEvents(false)
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    clientService.listClients({ size: 500, sortBy: 'code' })
      .then((r) => { if (!cancelled) setClients(r.data?.content ?? []) })
      .catch((e) => { if (!isAbortError(e)) console.debug('[load] listClients', e) })
    return () => { cancelled = true }
  }, [])

  useEffect(() => { void loadEvents() }, [loadEvents])

  const runClose = async () => {
    setClosing(true)
    setCloseResult(null)
    try {
      const r = await manifestService.closeOutDay({
        carrierCode: closeCarrier,
        customerNo: closeClient || null,
        warehouseCode: closeWarehouse || null,
        closeDate: closeDate || undefined,
      })
      const data = r.data ?? null
      setCloseResult(data)
      if (data?.status === 'MANIFESTED') notify.success(data.message || 'Closed out.')
      else if (data?.status === 'EMPTY') notify.info(data.message || 'Nothing to close.')
      else if (data?.status === 'PARTIAL') notify.info(data.message || 'Partially closed.')
      else notify.error({ title: 'Close-out', body: data?.message || 'Close-out failed.' })
      void loadEvents()
    } catch (e) {
      notify.apiError(e, 'Close-out failed.')
    } finally {
      setClosing(false)
    }
  }

  const runPickup = async () => {
    if (!pickup.contactName || !pickup.contactPhone || !pickup.addressLine1
        || !pickup.city || !pickup.postalCode) {
      notify.error({ title: 'Missing fields', body: 'Contact, phone, address, city and postal code are required.' })
      return
    }
    setScheduling(true)
    try {
      const r = await pickupService.schedule({
        ...pickup,
        customerNo: pickup.customerNo || null,
        packageCount: Number(pickup.packageCount) || 1,
        totalWeight: Number(pickup.totalWeight) || 1,
      })
      const data = r.data
      if (data?.status === 'SCHEDULED') {
        notify.success(`Pickup scheduled — confirmation ${data.confirmationNumber ?? '(none)'}.`)
      } else {
        notify.error({ title: 'Pickup not scheduled', body: data?.message || 'Carrier rejected the pickup.' })
      }
      void loadEvents()
    } catch (e) {
      notify.apiError(e, 'Could not schedule the pickup.')
    } finally {
      setScheduling(false)
    }
  }

  const clientOptions = (
    <>
      <option value="">All clients</option>
      {clients.map((c) => <option key={c.clientCode} value={c.clientCode}>{c.clientCode} — {c.name}</option>)}
    </>
  )

  const pageCount = Math.max(1, Math.ceil(events.length / PAGE_SIZE))
  const pageEvents = events.slice(page * PAGE_SIZE, page * PAGE_SIZE + PAGE_SIZE)

  return (
    <div className="space-y-4 pb-10">
      <div className="grid gap-4 lg:grid-cols-2 lg:items-start">
        {/* Left column — close + activity (fills the space next to the taller pickup form) */}
        <div className="space-y-4">
        {/* End-of-day close */}
        <Card icon={<FiCheckSquare className="h-3.5 w-3.5" />} title="End-of-day close">
          <div className="grid grid-cols-2 gap-3">
            <div>
              <span className={label}>Carrier</span>
              <select className={input} value={closeCarrier} onChange={(e) => setCloseCarrier(e.target.value)}>
                {closeCarriers.map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
            </div>
            <div>
              <span className={label}>Close date</span>
              <input type="date" className={input} value={closeDate} onChange={(e) => setCloseDate(e.target.value)} />
            </div>
            <div>
              <span className={label}>Client</span>
              <select className={input} value={closeClient} onChange={(e) => setCloseClient(e.target.value)}>{clientOptions}</select>
            </div>
            <div>
              <span className={label}>Warehouse code (optional)</span>
              <input className={input} value={closeWarehouse} placeholder="All warehouses"
                onChange={(e) => setCloseWarehouse(e.target.value)} />
            </div>
          </div>
          <p className="mt-2 text-[11.5px] text-[#9a8b70]">
            Closes every open (generated, non-voided) label for the chosen carrier on that date. To close only
            specific orders instead, use <span className="font-semibold text-[#6b5c42]">Close out</span> on the Orders
            page. To close automatically every evening, turn on the{' '}
            <Link to={settingsPaths.dtcScheduler} className="font-semibold text-[#412d15] underline">Carrier end-of-day close</Link> job.
            DHL manifests implicitly via its pickup, so it isn't listed here.
          </p>
          <button type="button" onClick={() => void runClose()} disabled={closing} className={`${primaryBtn} mt-3 w-full`}>
            {closing ? <FiRefreshCw className="h-3.5 w-3.5 animate-spin" /> : <FiCheckSquare className="h-3.5 w-3.5" />}
            {closing ? 'Closing…' : 'Close out'}
          </button>

          {closeResult ? (
            <div className="mt-3 rounded-xl border border-[#eee6d6] bg-[#faf7f0]/70 p-3 text-[12px]">
              <div className="flex items-center gap-2">
                <StatusPill status={closeResult.status} />
                <span className="font-semibold text-[#3d2f1c]">{closeResult.trackingCount} label(s)</span>
                {closeResult.manifestId ? <span className="text-[#6b5c42]">· manifest {closeResult.manifestId}</span> : null}
              </div>
              <p className="mt-1 text-[#6b5c42]">{closeResult.message}</p>
              {closeResult.manifests?.length ? (
                <ul className="mt-1.5 space-y-0.5">
                  {closeResult.manifests.map((m) => (
                    <li key={m.fleet} className="flex items-center gap-1.5">
                      <StatusPill status={m.status} />
                      <span className="font-semibold">{m.fleet}</span>
                      <span className="text-[#6b5c42]">{m.trackingCount} · {m.manifestId ?? m.message}</span>
                    </li>
                  ))}
                </ul>
              ) : null}
            </div>
          ) : null}
        </Card>

        {/* Recent activity (compact list, paginated) */}
        <Card icon={<FiClock className="h-3.5 w-3.5" />} title="Recent activity"
              action={
                <button type="button" onClick={() => void loadEvents()} aria-label="Refresh activity"
                        className="inline-flex h-7 w-7 items-center justify-center rounded-lg border border-[#e3d9c4] bg-white text-[#5a4526] transition hover:bg-[#faf7f0]">
                  <FiRefreshCw className={`h-3.5 w-3.5 ${loadingEvents ? 'animate-spin' : ''}`} />
                </button>
              }>
          {events.length === 0 ? (
            <p className="py-8 text-center text-[12px] text-[#9a8b70]">
              {loadingEvents ? 'Loading…' : 'No pickups or close-outs yet.'}
            </p>
          ) : (
            <>
              <ul className="divide-y divide-[#f3ecdd]">
                {pageEvents.map((e) => (
                  <li key={e.id} className="flex items-start gap-2.5 py-2">
                    <span className="mt-0.5 inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg bg-[#f4eede] text-[#412d15]">
                      {e.kind === 'PICKUP' ? <FiTruck className="h-3.5 w-3.5" /> : <FiCheckSquare className="h-3.5 w-3.5" />}
                    </span>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-1.5">
                        <span className="text-[12.5px] font-semibold text-[#3d2f1c]">{e.carrierCode}</span>
                        <span className="text-[11px] text-[#9a8b70]">{e.kind === 'PICKUP' ? 'pickup' : 'close'}</span>
                        <span className="ml-auto"><StatusPill status={e.status} /></span>
                      </div>
                      <div className="mt-0.5 flex flex-wrap items-center gap-x-1.5 text-[10.5px] text-[#9a8b70]">
                        <span className="whitespace-nowrap">{new Date(e.createdAt).toLocaleString()}</span>
                        <span>· {e.trackingCount} {e.kind === 'PICKUP' ? 'pkg' : 'label'}{e.trackingCount === 1 ? '' : 's'}</span>
                        <span>· {e.customerNo || 'all'}</span>
                        {e.reference ? <span className="font-mono">· {e.reference}</span> : null}
                        <span>· {e.source === 'SCHEDULED' ? 'Auto' : 'Manual'}</span>
                      </div>
                    </div>
                  </li>
                ))}
              </ul>
              {pageCount > 1 ? (
                <div className="mt-2 flex items-center justify-between border-t border-[#f3ecdd] pt-2 text-[11px] text-[#6b5c42]">
                  <span>{page * PAGE_SIZE + 1}–{Math.min((page + 1) * PAGE_SIZE, events.length)} of {events.length}</span>
                  <div className="flex gap-1.5">
                    <button type="button" disabled={page === 0} onClick={() => setPage((p) => Math.max(0, p - 1))}
                      className="rounded-md border border-[#e3d9c4] bg-white px-2 py-1 font-semibold text-[#5a4526] transition hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40">Prev</button>
                    <button type="button" disabled={page >= pageCount - 1} onClick={() => setPage((p) => Math.min(pageCount - 1, p + 1))}
                      className="rounded-md border border-[#e3d9c4] bg-white px-2 py-1 font-semibold text-[#5a4526] transition hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40">Next</button>
                  </div>
                </div>
              ) : null}
            </>
          )}
        </Card>
        </div>

        {/* Schedule a pickup */}
        <Card icon={<FiTruck className="h-3.5 w-3.5" />} title="Schedule a pickup">
          <GroupLabel>Carrier &amp; schedule</GroupLabel>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <span className={label}>Carrier</span>
              <select className={input} value={pickup.carrierCode} onChange={(e) => setP('carrierCode')(e.target.value)}>
                {pickupCarriers.map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
            </div>
            <div>
              <span className={label}>Client</span>
              <select className={input} value={pickup.customerNo ?? ''} onChange={(e) => setP('customerNo')(e.target.value)}>{clientOptions}</select>
            </div>
            <div>
              <span className={label}><FiCalendar className="mr-1 inline h-3 w-3" />Pickup date</span>
              <input type="date" className={input} value={pickup.pickupDate} onChange={(e) => setP('pickupDate')(e.target.value)} />
            </div>
            <div>
              <span className={label}>Service</span>
              <select className={input} value={pickup.pickupServiceType} onChange={(e) => setP('pickupServiceType')(e.target.value)}>
                {SERVICE_TYPES.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </div>
            <div>
              <span className={label}><FiClock className="mr-1 inline h-3 w-3" />Window from</span>
              <input type="time" className={input} value={pickup.pickupWindowStart} onChange={(e) => setP('pickupWindowStart')(e.target.value)} />
            </div>
            <div>
              <span className={label}>Window to</span>
              <input type="time" className={input} value={pickup.pickupWindowEnd} onChange={(e) => setP('pickupWindowEnd')(e.target.value)} />
            </div>
          </div>

          <GroupLabel><FiMapPin className="inline h-3 w-3" /> Pickup address</GroupLabel>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <span className={label}>Contact name</span>
              <input className={input} value={pickup.contactName} onChange={(e) => setP('contactName')(e.target.value)} />
            </div>
            <div>
              <span className={label}>Contact phone</span>
              <input className={input} value={pickup.contactPhone} onChange={(e) => setP('contactPhone')(e.target.value)} />
            </div>
            <div className="col-span-2">
              <span className={label}>Address</span>
              <input className={`${input} mb-2`} placeholder="Line 1" value={pickup.addressLine1} onChange={(e) => setP('addressLine1')(e.target.value)} />
              <input className={input} placeholder="Line 2 (optional)" value={pickup.addressLine2 ?? ''} onChange={(e) => setP('addressLine2')(e.target.value)} />
            </div>
            <div>
              <span className={label}>City</span>
              <input className={input} value={pickup.city} onChange={(e) => setP('city')(e.target.value)} />
            </div>
            <div className="grid grid-cols-2 gap-2">
              <div>
                <span className={label}>State</span>
                <input className={input} value={pickup.state ?? ''} onChange={(e) => setP('state')(e.target.value)} />
              </div>
              <div>
                <span className={label}>Postal</span>
                <input className={input} value={pickup.postalCode} onChange={(e) => setP('postalCode')(e.target.value)} />
              </div>
            </div>
            <div>
              <span className={label}>Country</span>
              <input className={input} value={pickup.countryCode} onChange={(e) => setP('countryCode')(e.target.value.toUpperCase())} maxLength={2} />
            </div>
          </div>

          <GroupLabel><FiPackage className="inline h-3 w-3" /> Parcels</GroupLabel>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <span className={label}>Packages</span>
              <input type="number" min={1} className={input} value={pickup.packageCount} onChange={(e) => setP('packageCount')(Number(e.target.value))} />
            </div>
            <div>
              <span className={label}>Total wt ({pickup.weightUnit})</span>
              <input type="number" min={0.1} step={0.1} className={input} value={pickup.totalWeight} onChange={(e) => setP('totalWeight')(Number(e.target.value))} />
            </div>
            <div className="col-span-2">
              <span className={label}>Driver instructions (optional)</span>
              <input className={input} value={pickup.specialInstructions ?? ''} onChange={(e) => setP('specialInstructions')(e.target.value)} />
            </div>
          </div>

          <button type="button" onClick={() => void runPickup()} disabled={scheduling} className={`${primaryBtn} mt-4 w-full`}>
            {scheduling ? <FiRefreshCw className="h-3.5 w-3.5 animate-spin" /> : <FiTruck className="h-3.5 w-3.5" />}
            {scheduling ? 'Scheduling…' : 'Schedule pickup'}
          </button>
        </Card>
      </div>
    </div>
  )
}
