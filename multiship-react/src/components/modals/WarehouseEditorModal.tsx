import { useEffect, useMemo, useState } from 'react'
import { FiHome, FiX } from 'react-icons/fi'
import { notify } from '../../utils/notify'
import { ApiError } from '../../api/apiClient'
import { clientService, type Client } from '../../api/clientService'
import {
  warehouseService,
  type Warehouse,
  type WarehouseUpsertPayload,
} from '../../api/warehouseService'
import Select from '../workspace/Select'
import AttachClientsStep from './AttachClientsStep'

interface Props {
  /** null = create; otherwise edit. */
  warehouse: Warehouse | null
  onClose: () => void
  /** Fires after a successful save / attach step. The created (or updated)
   *  warehouse is passed back so callers can react (e.g. auto-attach). */
  onSaved: (saved?: Warehouse) => void
}

/** Create / edit a warehouse. Owner type switches the client picker on/off. */
export default function WarehouseEditorModal({ warehouse, onClose, onSaved }: Props) {
  const isEdit = !!warehouse

  const [code, setCode] = useState(warehouse?.code ?? '')
  const [name, setName] = useState(warehouse?.name ?? '')
  const [line1, setLine1] = useState(warehouse?.address?.line1 ?? '')
  const [line2, setLine2] = useState(warehouse?.address?.line2 ?? '')
  const [city, setCity] = useState(warehouse?.address?.city ?? '')
  const [state, setState] = useState(warehouse?.address?.state ?? '')
  const [zip, setZip] = useState(warehouse?.address?.zip ?? '')
  const [country, setCountry] = useState(warehouse?.address?.country ?? 'US')
  const [phone, setPhone] = useState(warehouse?.address?.phone ?? '')
  const [ownerType, setOwnerType] = useState<'PLATFORM' | 'CLIENT'>(
    (warehouse?.ownerType as 'PLATFORM' | 'CLIENT') || 'PLATFORM',
  )
  const [ownerClientCode, setOwnerClientCode] = useState(warehouse?.ownerClientCode ?? '')
  const [active, setActive] = useState<boolean>(warehouse?.active ?? true)

  const [clients, setClients] = useState<Client[]>([])
  const [saving, setSaving] = useState(false)

  // After a successful create, the modal flips into an optional "attach clients"
  // step so the operator can wire the new warehouse to one or more clients
  // (and pick a default) without leaving the flow. Edit path skips this step.
  const [created, setCreated] = useState<Warehouse | null>(null)

  // Load the client picker options only when we're in CLIENT mode. Pagination:
  // pull the first 200 by code so onboarding a warehouse to any client works
  // without introducing a full typeahead in this modal.
  useEffect(() => {
    if (ownerType !== 'CLIENT') return
    let cancelled = false
    clientService.listClients({ size: 200, sortBy: 'code', sortDirection: 'ASC' })
      .then((r) => { if (!cancelled) setClients(r.data?.content ?? []) })
      .catch(() => { /* the notify layer covers the error; picker just stays empty */ })
    return () => { cancelled = true }
  }, [ownerType])

  const canSubmit = useMemo(() => {
    if (!code.trim() || !name.trim()) return false
    if (ownerType === 'CLIENT' && !ownerClientCode.trim()) return false
    return true
  }, [code, name, ownerType, ownerClientCode])

  const submit = async () => {
    if (!canSubmit || saving) return
    setSaving(true)
    try {
      const payload: WarehouseUpsertPayload = {
        code: code.trim().toUpperCase(),
        name: name.trim(),
        address: {
          line1: line1.trim() || undefined,
          line2: line2.trim() || undefined,
          city: city.trim() || undefined,
          state: state.trim() || undefined,
          zip: zip.trim() || undefined,
          country: country.trim().toUpperCase() || 'US',
          phone: phone.trim() || undefined,
        },
        ownerType,
        ownerClientCode: ownerType === 'CLIENT' ? ownerClientCode.trim().toUpperCase() : undefined,
        active,
      }
      if (isEdit && warehouse) {
        const r = await warehouseService.updateWarehouse(warehouse.code, payload)
        notify.success(`Warehouse ${warehouse.code} updated.`)
        onSaved(r.data)
      } else {
        const r = await warehouseService.createWarehouse(payload)
        notify.success(`Warehouse ${payload.code} created.`)
        const saved = r.data ?? { ...(payload as unknown as Warehouse), id: 0, attachedClientCount: 0, createdAt: null, updatedAt: null }
        // Only PLATFORM warehouses need the follow-up attach step. CLIENT-owned
        // warehouses are private to their owner and aren't attachable elsewhere.
        if (payload.ownerType === 'PLATFORM') {
          setCreated(saved)
        } else {
          onSaved(saved)
        }
      }
    } catch (error) {
      if (error instanceof ApiError) {
        // Structured backend codes get bespoke copy; everything else falls to
        // the message the server sent.
        if (error.errorCode === 'WAREHOUSE_CODE_TAKEN') {
          notify.error(`Warehouse code ${code.trim().toUpperCase()} is already in use.`)
        } else if (error.errorCode === 'WAREHOUSE_OWNER_INVALID') {
          notify.error(error.message)
        } else if (error.errorCode === 'CLIENT_NOT_FOUND') {
          notify.error(`Client ${ownerClientCode.trim().toUpperCase()} was not found.`)
        } else {
          notify.error(error.message)
        }
      } else {
        notify.error(error instanceof Error ? error.message : 'Failed to save the warehouse.')
      }
    } finally {
      setSaving(false)
    }
  }

  const closeAction = created ? () => onSaved(created) : onClose

  return (
    <>
      <div
        className="fixed inset-0 z-40 bg-slate-950/45"
        onClick={closeAction}
        aria-hidden="true"
      />
      <aside
        role="dialog"
        aria-modal="true"
        aria-labelledby="warehouse-editor-title"
        className="fixed inset-y-0 right-0 z-50 flex w-full max-w-[520px] flex-col border-l border-slate-200 bg-white shadow-[-18px_0_50px_rgba(8,14,26,0.18)]"
      >
        <header className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div className="flex items-start gap-2.5">
            <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-[#412d15]/10 text-[#412d15]">
              <FiHome className="h-4 w-4" />
            </span>
            <div>
              <p className="text-[10.5px] font-bold uppercase tracking-[0.16em] text-slate-400">
                Warehouses
              </p>
              <h2 id="warehouse-editor-title" className="mt-0.5 text-[15px] font-semibold text-slate-950">
                {created
                  ? `Attach ${created.code} to clients`
                  : isEdit
                    ? `Edit warehouse ${warehouse!.code}`
                    : 'Add warehouse'}
              </h2>
              <p className="mt-0.5 text-[11.5px] text-slate-500">
                {created
                  ? 'Optional — pick the clients that should see this warehouse.'
                  : 'Ship-from locations attach to clients; each client picks one default.'}
              </p>
            </div>
          </div>
          <button
            type="button"
            aria-label="Close"
            onClick={closeAction}
            className="rounded-xl border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
          >
            <FiX className="h-4 w-4" />
          </button>
        </header>

        {created ? (
          <AttachClientsStep warehouse={created} onDone={() => onSaved(created)} />
        ) : (
        <>
        <div className="flex-1 space-y-5 overflow-y-auto px-5 py-5">
          {/* Identity */}
          <section className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <Field label="Code" required hint="Uppercase; immutable after create.">
              <input
                value={code}
                onChange={(e) => setCode(e.target.value.toUpperCase())}
                disabled={isEdit}
                placeholder="3PL-EAST"
                className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] font-semibold text-slate-950 outline-none transition focus:border-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-50 disabled:text-slate-500"
              />
            </Field>
            <Field label="Name" required>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="East Coast fulfilment"
                className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] font-semibold text-slate-950 outline-none transition focus:border-[#412d15]"
              />
            </Field>
          </section>

          {/* Ownership — radios drive the client picker's visibility. */}
          <section>
            <p className="mb-1.5 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
              Owner
            </p>
            <div className="flex flex-wrap gap-2">
              {(['PLATFORM', 'CLIENT'] as const).map((v) => (
                <label
                  key={v}
                  className={`inline-flex cursor-pointer items-center gap-2 rounded-xl border px-3 py-2 text-[12.5px] font-semibold transition ${
                    ownerType === v
                      ? 'border-[#412d15] bg-[#412d15]/5 text-[#412d15]'
                      : 'border-slate-200 bg-white text-slate-600 hover:bg-slate-50'
                  }`}
                >
                  <input
                    type="radio"
                    name="ownerType"
                    value={v}
                    checked={ownerType === v}
                    onChange={() => setOwnerType(v)}
                    className="sr-only"
                  />
                  {v === 'PLATFORM' ? 'Platform (attachable to any client)' : "Client (private to one client)"}
                </label>
              ))}
            </div>
            {ownerType === 'CLIENT' ? (
              <div className="mt-3">
                <Field label="Owner client" required>
                  <Select
                    value={ownerClientCode}
                    onChange={(e) => setOwnerClientCode(e.target.value)}
                    aria-label="Owner client"
                  >
                    <option value="">Select client…</option>
                    {clients.map((c) => (
                      <option key={c.clientCode} value={c.clientCode}>
                        {c.clientCode} — {c.name}
                      </option>
                    ))}
                  </Select>
                </Field>
              </div>
            ) : null}
          </section>

          {/* Address */}
          <section>
            <p className="mb-1.5 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
              Address
            </p>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Field label="Line 1" span={2}>
                <input value={line1} onChange={(e) => setLine1(e.target.value)} placeholder="1 Warehouse Way"
                  className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15]" />
              </Field>
              <Field label="Line 2" span={2}>
                <input value={line2} onChange={(e) => setLine2(e.target.value)} placeholder="Suite / floor"
                  className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15]" />
              </Field>
              <Field label="City">
                <input value={city} onChange={(e) => setCity(e.target.value)}
                  className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15]" />
              </Field>
              <Field label="State / region">
                <input value={state} onChange={(e) => setState(e.target.value)}
                  className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15]" />
              </Field>
              <Field label="Postal code">
                <input value={zip} onChange={(e) => setZip(e.target.value)}
                  className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15]" />
              </Field>
              <Field label="Country (ISO-2)" required>
                <input value={country} onChange={(e) => setCountry(e.target.value.toUpperCase())} maxLength={2}
                  className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] font-semibold uppercase text-slate-950 outline-none transition focus:border-[#412d15]" />
              </Field>
              <Field label="Phone" span={2}>
                <input value={phone} onChange={(e) => setPhone(e.target.value)}
                  className="w-full rounded-xl border border-slate-200 bg-white px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-[#412d15]" />
              </Field>
            </div>
          </section>

          {/* Active */}
          <section className="flex items-center gap-3 rounded-xl border border-slate-100 bg-slate-50/60 px-4 py-3">
            <input
              id="warehouse-active"
              type="checkbox"
              checked={active}
              onChange={(e) => setActive(e.target.checked)}
              className="h-4 w-4 rounded border-slate-300 text-[#412d15] focus:ring-[#412d15]"
            />
            <label htmlFor="warehouse-active" className="text-[12.5px] font-semibold text-slate-700">
              Active — inactive warehouses are hidden from shipment resolution.
            </label>
          </section>
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
            onClick={submit}
            disabled={!canSubmit || saving}
            className="rounded-xl bg-[#1f150c] px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:opacity-50"
          >
            {saving ? 'Saving…' : isEdit ? 'Save changes' : 'Create warehouse'}
          </button>
        </footer>
        </>
        )}
      </aside>
    </>
  )
}

function Field({
  label,
  required,
  hint,
  span = 1,
  children,
}: {
  label: string
  required?: boolean
  hint?: string
  span?: 1 | 2
  children: React.ReactNode
}) {
  return (
    <div className={span === 2 ? 'sm:col-span-2' : ''}>
      <label className="mb-1 flex items-center gap-1 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400">
        {label}
        {required ? <span className="text-rose-500">*</span> : null}
      </label>
      {children}
      {hint ? <p className="mt-1 text-[10.5px] text-slate-400">{hint}</p> : null}
    </div>
  )
}

