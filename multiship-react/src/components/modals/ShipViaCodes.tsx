import { useCallback, useEffect, useState } from 'react'
import { FiChevronDown, FiChevronRight, FiExternalLink, FiPlus } from 'react-icons/fi'
import { notify } from '../../utils/notify'
import {
  shippingConfigService,
  type ShipViaCode,
  type ShippingServiceItem,
} from '../../api/shippingConfigService'

/**
 * Ship via codes beside the upload.
 *
 * <p>A bulk file's serviceType column carries the client's OWN code, and the
 * importer refuses a code that isn't mapped. This panel says which codes the
 * client may use, so the operator can fix the file without opening Settings.
 */
export function ShipViaCodesPanel({
  clientCode,
  canEdit,
  onOpenMapping,
  reloadKey = 0,
}: {
  /** The client the file is for; null when the file mixes several. */
  clientCode: string | null
  canEdit: boolean
  onOpenMapping: () => void
  /** Bump to refetch after a mapping is added. */
  reloadKey?: number
}) {
  const [open, setOpen] = useState(false)
  const [codes, setCodes] = useState<ShipViaCode[] | null>(null)

  useEffect(() => {
    if (!open) return
    let alive = true
    shippingConfigService
      .shipViaCodes(clientCode)
      .then((res) => { if (alive) setCodes(res.data ?? []) })
      .catch(() => { if (alive) setCodes([]) })
    return () => { alive = false }
  }, [open, clientCode, reloadKey])

  return (
    <div className="rounded-xl border border-[#e3d9c4] bg-[#fcfaf5]">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-expanded={open}
        className="flex w-full items-center gap-1.5 px-3 py-2 text-left text-[12px] font-semibold text-[#5a4526]"
      >
        {open ? <FiChevronDown className="h-3.5 w-3.5" /> : <FiChevronRight className="h-3.5 w-3.5" />}
        Ship via codes {clientCode ? `for ${clientCode}` : 'on this platform'}
        <span className="font-normal text-[#8a7a5c]">— what goes in the serviceType column</span>
      </button>
      {open ? (
        <div className="border-t border-[#efe7d6] px-3 py-2">
          {codes === null ? (
            <p className="text-[12px] text-[#8a7a5c]">Loading…</p>
          ) : codes.length === 0 ? (
            <p className="text-[12px] text-[#8a7a5c]">
              No ship via codes are mapped {clientCode ? `for ${clientCode}` : 'yet'}. The file can still use a
              carrier service code such as 03.
            </p>
          ) : (
            <table className="w-full text-[12px]">
              <thead className="text-left text-[10.5px] font-semibold uppercase tracking-wide text-[#8a7a5c]">
                <tr>
                  <th className="pb-1">Code</th>
                  <th className="pb-1">Ships with</th>
                  <th className="pb-1">Applies to</th>
                </tr>
              </thead>
              <tbody>
                {codes.map((c) => (
                  <tr key={c.code} className={c.enabled ? '' : 'opacity-60'}>
                    <td className="py-0.5 pr-3 font-mono font-semibold text-[#3b2d18]">{c.code}</td>
                    <td className="py-0.5 pr-3 text-[#5a4526]">
                      {c.serviceName} <span className="text-[#8a7a5c]">({c.carrier} {c.serviceCode})</span>
                      {c.enabled ? null : <span className="ml-1 text-amber-700">· switched off</span>}
                    </td>
                    <td className="py-0.5 text-[#8a7a5c]">
                      {c.clientCode ?? 'every client'}{c.destination ? ` · ${c.destination}` : ''}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {canEdit ? (
            <button
              type="button"
              onClick={onOpenMapping}
              className="mt-2 inline-flex items-center gap-1 text-[12px] font-semibold text-[#5a4526] underline"
            >
              <FiExternalLink className="h-3 w-3" />
              Manage these in Settings → Shipping Service Mapping
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
  )
}

/**
 * Map one unmapped code without leaving the upload. Admin-only: the rule
 * decides how every future order carrying this code ships.
 */
export function AddShipViaMappingDialog({
  code,
  clientCode,
  onClose,
  onSaved,
}: {
  code: string
  clientCode: string | null
  onClose: () => void
  onSaved: () => void
}) {
  const [services, setServices] = useState<ShippingServiceItem[] | null>(null)
  const [serviceId, setServiceId] = useState('')
  const [scope, setScope] = useState<'client' | 'all'>(clientCode ? 'client' : 'all')
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    let alive = true
    shippingConfigService
      .catalog()
      .then((c) => { if (alive) setServices(c.services.filter((s) => s.enabled)) })
      .catch((e) => { if (alive) { setServices([]); notify.apiError(e, 'Could not load the service catalog.') } })
    return () => { alive = false }
  }, [])

  const save = useCallback(async () => {
    if (!serviceId) return
    setSaving(true)
    try {
      await shippingConfigService.saveRule({
        shipviaCd: code,
        clientCode: scope === 'client' ? clientCode : null,
        destType: 'ANY',
        destValue: null,
        serviceId: Number(serviceId),
      })
      const picked = services?.find((s) => String(s.id) === serviceId)
      notify.success(`${code} now ships ${picked ? picked.name : 'the chosen service'}${
        scope === 'client' && clientCode ? ` for ${clientCode}` : ' for every client'}.`)
      onSaved()
    } catch (e) {
      notify.apiError(e, 'Could not save the mapping.')
    } finally {
      setSaving(false)
    }
  }, [code, clientCode, scope, serviceId, services, onSaved])

  const byCarrier = new Map<string, ShippingServiceItem[]>()
  for (const s of services ?? []) {
    if (!byCarrier.has(s.carrier)) byCarrier.set(s.carrier, [])
    byCarrier.get(s.carrier)!.push(s)
  }

  return (
    <div className="fixed inset-0 z-[60] flex items-center justify-center bg-[#1f150c]/45 p-4" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label={`Map ship via ${code}`}
        className="w-full max-w-[460px] rounded-2xl bg-white shadow-xl"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="border-b border-[#efe7d6] px-5 py-3">
          <h3 className="text-[15px] font-semibold text-slate-900">
            Map ship via <span className="font-mono">{code}</span>
          </h3>
          <p className="mt-0.5 text-[12px] text-[#6b5c42]">
            Every order with this code ships the service you pick here, on this upload and every one after it.
          </p>
        </div>
        <div className="space-y-3 px-5 py-4">
          <label className="block">
            <span className="mb-1 block text-[11px] font-semibold uppercase tracking-wide text-[#8a7a5c]">
              Ships with
            </span>
            <select
              value={serviceId}
              onChange={(e) => setServiceId(e.target.value)}
              disabled={services === null}
              className="w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-2 text-[13px]"
            >
              <option value="">{services === null ? 'Loading…' : 'Choose a carrier service…'}</option>
              {[...byCarrier.entries()].map(([carrier, list]) => (
                <optgroup key={carrier} label={carrier}>
                  {list.map((s) => (
                    <option key={s.id} value={String(s.id)}>{s.name} ({s.serviceCode})</option>
                  ))}
                </optgroup>
              ))}
            </select>
          </label>
          {clientCode ? (
            <fieldset>
              <legend className="mb-1 text-[11px] font-semibold uppercase tracking-wide text-[#8a7a5c]">
                Applies to
              </legend>
              {([
                ['client', `Only ${clientCode}`],
                ['all', 'Every client without their own rule'],
              ] as const).map(([value, label]) => (
                <label key={value} className="flex items-center gap-2 py-0.5 text-[13px] text-[#3b2d18]">
                  <input
                    type="radio"
                    name="ship-via-scope"
                    checked={scope === value}
                    onChange={() => setScope(value)}
                  />
                  {label}
                </label>
              ))}
            </fieldset>
          ) : (
            <p className="text-[12px] text-[#6b5c42]">
              This row has no client code, so the rule will apply to every client.
            </p>
          )}
        </div>
        <div className="flex justify-end gap-2 border-t border-[#efe7d6] px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[13px] font-semibold text-[#5a4526] hover:bg-[#faf7f0]"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={() => void save()}
            disabled={saving || !serviceId}
            className="inline-flex items-center gap-1.5 rounded-xl bg-[#3b2d18] px-3 py-2 text-[13px] font-semibold text-white hover:bg-[#5a4526] disabled:opacity-50"
          >
            {saving
              ? <span className="inline-block h-3.5 w-3.5 animate-spin rounded-full border-2 border-white/40 border-t-white" />
              : <FiPlus className="h-3.5 w-3.5" />}
            {saving ? 'Saving…' : 'Save mapping'}
          </button>
        </div>
      </div>
    </div>
  )
}
