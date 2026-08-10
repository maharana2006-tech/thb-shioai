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
import {
  FIELD_LIMITS,
  validateCode,
  validateCountry,
  validateLength,
  validateName,
  validatePhone,
  validateZip,
} from '../../utils/clientValidation'

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

  // Touched-gating: an error only renders once the operator has left the field
  // (or hit Save, which force-touches all). Same pattern as the client wizard.
  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const markTouched = (k: string) => setTouched((cur) => ({ ...cur, [k]: true }))

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

  // ===== Per-field validation (recomputed each render — cheap) =====
  // Reuses the same validators as the client wizard so warehouse fields follow
  // the app-wide rules: code pattern + min-length, <>-safe free text, country-
  // aware postal, E.164-ish phone. Address + phone are required because every
  // integrated carrier rejects a label with an incomplete shipper block.
  const errors = useMemo(() => ({
    code: validateCode(code, 'Warehouse code'),
    name: validateName(name),
    ownerClientCode: ownerType === 'CLIENT' && !ownerClientCode.trim()
      ? 'Owner client is required.'
      : null,
    line1: validateLength(line1, FIELD_LIMITS.addr.line1, 'Street address', true, 2),
    line2: line2 ? validateLength(line2, FIELD_LIMITS.addr.line2, 'Suite / unit', false) : null,
    city: validateLength(city, FIELD_LIMITS.addr.city, 'City', true, 2),
    state: validateLength(state, FIELD_LIMITS.addr.state, 'State / region', true),
    country: validateCountry(country, true),
    zip: validateZip(zip, country, true),
    phone: validatePhone(phone, true),
  }), [code, name, ownerType, ownerClientCode, line1, line2, city, state, zip, country, phone])

  const canSubmit = useMemo(
    () => Object.values(errors).every((e) => !e),
    [errors],
  )

  /** Force every field's error to show — used when Save is pressed while some
   *  fields are still untouched, so the red errors surface instead of a
   *  silently disabled button. */
  const touchAll = () =>
    setTouched(Object.keys(errors).reduce((m, k) => ({ ...m, [k]: true }), {}))

  const submit = async () => {
    if (saving) return
    if (!canSubmit) {
      touchAll()
      notify.error({ title: 'Missing details', body: 'Fix the highlighted fields before saving.' })
      return
    }
    setSaving(true)
    try {
      const payload: WarehouseUpsertPayload = {
        code: code.trim().toUpperCase(),
        name: name.trim(),
        address: {
          line1: line1.trim(),
          line2: line2.trim() || undefined,
          city: city.trim(),
          state: state.trim(),
          zip: zip.trim(),
          country: country.trim().toUpperCase() || 'US',
          phone: phone.trim(),
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

  /** Error for a field, but only after it's been touched. */
  const err = (k: keyof typeof errors): string | null => (touched[k] ? errors[k] : null)
  /** Input class with an error ring when the (touched) field is invalid. */
  const inputCls = (k: keyof typeof errors, extra = '') =>
    `w-full rounded-xl border bg-white px-3 py-2 text-[13px] text-[#1f150c] outline-none transition ${
      err(k) ? 'border-rose-400 focus:border-rose-500' : 'border-[#e3d9c4] focus:border-[#412d15]'
    } ${extra}`

  return (
    <>
      <div
        className="fixed inset-0 z-40 bg-[#1f150c]/45"
        onClick={closeAction}
        aria-hidden="true"
      />
      <aside
        role="dialog"
        aria-modal="true"
        aria-labelledby="warehouse-editor-title"
        className="fixed inset-y-0 right-0 z-50 flex w-full max-w-[520px] flex-col border-l border-[#e3d9c4] bg-white shadow-[-18px_0_50px_rgba(8,14,26,0.18)]"
      >
        <header className="flex items-start justify-between gap-3 border-b border-[#eee6d6] px-5 py-4">
          <div className="flex items-start gap-2.5">
            <span className="inline-flex h-9 w-9 items-center justify-center rounded-xl bg-[#412d15]/10 text-[#412d15]">
              <FiHome className="h-4 w-4" />
            </span>
            <div>
              <p className="text-[10.5px] font-bold uppercase tracking-[0.16em] text-[#b6a684]">
                Warehouses
              </p>
              <h2 id="warehouse-editor-title" className="mt-0.5 text-[15px] font-semibold text-[#1f150c]">
                {created
                  ? `Attach ${created.code} to clients`
                  : isEdit
                    ? `Edit warehouse ${warehouse!.code}`
                    : 'Add warehouse'}
              </h2>
              <p className="mt-0.5 text-[11.5px] text-[#8a7959]">
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
            className="rounded-xl border border-[#e3d9c4] bg-white p-2 text-[#8a7959] transition hover:bg-[#faf7f0]"
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
            <Field label="Code" required hint="Uppercase; immutable after create." error={isEdit ? null : err('code')}>
              <input
                value={code}
                onChange={(e) => setCode(e.target.value.toUpperCase())}
                onBlur={() => markTouched('code')}
                disabled={isEdit}
                placeholder="3PL-EAST"
                maxLength={FIELD_LIMITS.clientCode}
                aria-invalid={!isEdit && err('code') ? true : undefined}
                className={inputCls('code', 'font-semibold disabled:cursor-not-allowed disabled:bg-[#faf7f0] disabled:text-[#8a7959]')}
              />
            </Field>
            <Field label="Name" required error={err('name')}>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                onBlur={() => markTouched('name')}
                placeholder="East Coast fulfilment"
                maxLength={FIELD_LIMITS.name}
                aria-invalid={err('name') ? true : undefined}
                className={inputCls('name', 'font-semibold')}
              />
            </Field>
          </section>

          {/* Ownership — radios drive the client picker's visibility. */}
          <section>
            <p className="mb-1.5 text-[10px] font-bold uppercase tracking-[0.14em] text-[#b6a684]">
              Owner
            </p>
            <div className="flex flex-wrap gap-2">
              {(['PLATFORM', 'CLIENT'] as const).map((v) => (
                <label
                  key={v}
                  className={`inline-flex cursor-pointer items-center gap-2 rounded-xl border px-3 py-2 text-[12.5px] font-semibold transition ${
                    ownerType === v
                      ? 'border-[#412d15] bg-[#412d15]/5 text-[#412d15]'
                      : 'border-[#e3d9c4] bg-white text-[#5a4526] hover:bg-[#faf7f0]'
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
                <Field label="Owner client" required error={err('ownerClientCode')}>
                  <Select
                    value={ownerClientCode}
                    onChange={(e) => setOwnerClientCode(e.target.value)}
                    onBlur={() => markTouched('ownerClientCode')}
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
            <p className="mb-1.5 text-[10px] font-bold uppercase tracking-[0.14em] text-[#b6a684]">
              Address
            </p>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <Field label="Line 1" required span={2} error={err('line1')}>
                <input value={line1} onChange={(e) => setLine1(e.target.value)} onBlur={() => markTouched('line1')}
                  placeholder="1 Warehouse Way" maxLength={FIELD_LIMITS.addr.line1}
                  aria-invalid={err('line1') ? true : undefined} className={inputCls('line1')} />
              </Field>
              <Field label="Line 2" span={2} error={err('line2')}>
                <input value={line2} onChange={(e) => setLine2(e.target.value)} onBlur={() => markTouched('line2')}
                  placeholder="Suite / floor" maxLength={FIELD_LIMITS.addr.line2}
                  aria-invalid={err('line2') ? true : undefined} className={inputCls('line2')} />
              </Field>
              <Field label="City" required error={err('city')}>
                <input value={city} onChange={(e) => setCity(e.target.value)} onBlur={() => markTouched('city')}
                  maxLength={FIELD_LIMITS.addr.city} aria-invalid={err('city') ? true : undefined} className={inputCls('city')} />
              </Field>
              <Field label="State / region" required error={err('state')}>
                <input value={state} onChange={(e) => setState(e.target.value)} onBlur={() => markTouched('state')}
                  maxLength={FIELD_LIMITS.addr.state} aria-invalid={err('state') ? true : undefined} className={inputCls('state')} />
              </Field>
              <Field label="Postal code" required error={err('zip')}>
                <input value={zip} onChange={(e) => setZip(e.target.value)} onBlur={() => markTouched('zip')}
                  maxLength={FIELD_LIMITS.addr.zip} aria-invalid={err('zip') ? true : undefined} className={inputCls('zip')} />
              </Field>
              <Field label="Country (ISO-2)" required error={err('country')}>
                <input value={country} onChange={(e) => setCountry(e.target.value.toUpperCase())} onBlur={() => markTouched('country')}
                  maxLength={2} aria-invalid={err('country') ? true : undefined} className={inputCls('country', 'font-semibold uppercase')} />
              </Field>
              <Field label="Phone" required span={2} error={err('phone')} hint="Required by UPS, FedEx and DHL on both domestic and international labels.">
                <input value={phone} onChange={(e) => setPhone(e.target.value)} onBlur={() => markTouched('phone')}
                  type="tel" inputMode="tel" maxLength={FIELD_LIMITS.phone}
                  aria-invalid={err('phone') ? true : undefined} className={inputCls('phone')} />
              </Field>
            </div>
          </section>

          {/* Active */}
          <section className="flex items-center gap-3 rounded-xl border border-[#eee6d6] bg-[#faf7f0]/60 px-4 py-3">
            <input
              id="warehouse-active"
              type="checkbox"
              checked={active}
              onChange={(e) => setActive(e.target.checked)}
              className="h-4 w-4 rounded border-[#cdbf9f] text-[#412d15] focus:ring-[#412d15]"
            />
            <label htmlFor="warehouse-active" className="text-[12.5px] font-semibold text-[#412d15]">
              Active — inactive warehouses are hidden from shipment resolution.
            </label>
          </section>
        </div>

        <footer className="flex items-center justify-end gap-2 border-t border-[#eee6d6] bg-[#faf7f0]/60 px-5 py-3">
          <button
            type="button"
            onClick={onClose}
            className="rounded-xl border border-[#e3d9c4] bg-white px-4 py-2 text-[13px] font-semibold text-[#5a4526] transition hover:bg-[#eee6d6]"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={submit}
            disabled={saving}
            aria-disabled={!canSubmit || saving}
            title={!canSubmit ? 'Fix the highlighted fields to continue' : undefined}
            className={`rounded-xl px-5 py-2 text-[13px] font-semibold text-white transition ${
              canSubmit ? 'bg-[#1f150c] hover:bg-[#412d15]' : 'bg-[#1f150c]/50'
            } disabled:cursor-not-allowed disabled:opacity-50`}
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
  error,
  children,
}: {
  label: string
  required?: boolean
  hint?: string
  span?: 1 | 2
  error?: string | null
  children: React.ReactNode
}) {
  return (
    <div className={span === 2 ? 'sm:col-span-2' : ''}>
      <label className="mb-1 flex items-center gap-1 text-[10px] font-bold uppercase tracking-[0.14em] text-[#b6a684]">
        {label}
        {required ? <span className="text-rose-500">*</span> : null}
      </label>
      {children}
      {error ? (
        <p className="mt-1 flex items-center gap-1 text-[10.5px] font-semibold text-rose-600">
          <span aria-hidden>⚠</span> {error}
        </p>
      ) : hint ? (
        <p className="mt-1 text-[10.5px] text-[#b6a684]">{hint}</p>
      ) : null}
    </div>
  )
}

