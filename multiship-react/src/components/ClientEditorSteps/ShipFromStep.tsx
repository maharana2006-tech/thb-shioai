/**
 * F5-B — Ship From step of the client-editor wizard.
 *
 * Picks a warehouse whose address becomes Client.shipFrom on save.
 * The picker offers every active PLATFORM warehouse plus, in edit
 * mode, the client's own CLIENT-owned ones. An inline "Add warehouse"
 * button opens WarehouseEditorModal for the one-off case where none
 * of the existing rows fit.
 *
 * The address preview below the picker mirrors the fields that will
 * actually be sent to the API — validators run against form.shipFrom,
 * so a picked warehouse with a bad zip / missing city surfaces the
 * same inline errors.
 */
import { FiAlertCircle, FiEdit2, FiHome, FiPlus } from 'react-icons/fi'
import type { Address } from '../../api/clientService'
import type { AddressLike } from '../../utils/clientValidation'
import type { Warehouse } from '../../api/warehouseService'
import Select from '../workspace/Select'
import { Field } from './_shared'

export interface ShipFromStepProps {
  warehouses: Warehouse[]
  loading: boolean
  selectedId: number | null
  onPick: (wh: Warehouse | null) => void
  onAddWarehouseClick: () => void
  /** Fires when the operator taps the Edit button on the picked-preview
   *  card. Only exposed on CLIENT-owned warehouses (PLATFORM warehouses
   *  are shared and must be edited from Settings → Warehouses by admin). */
  onEditWarehouseClick: (wh: Warehouse) => void
  addressPreview: Address
  errors: Partial<Record<keyof AddressLike, string>>
  touched: Record<string, boolean>
  isEdit: boolean
  /** Count of warehouses filtered out of the picker because they're already
   *  attached to this client — surfaced as a small hint so it doesn't feel
   *  like a bug when the list is shorter than /settings/warehouses shows. */
  hiddenAttachedCount: number
}

export function ShipFromStep({
  warehouses,
  loading,
  selectedId,
  onPick,
  onAddWarehouseClick,
  onEditWarehouseClick,
  addressPreview,
  errors,
  touched,
  isEdit,
  hiddenAttachedCount,
}: ShipFromStepProps) {
  const err = (k: keyof AddressLike) => (touched[`shipFrom.${k}`] ? errors[k] || null : null)
  const platform = warehouses.filter((w) => (w.ownerType || '').toUpperCase() === 'PLATFORM')
  const own = warehouses.filter((w) => (w.ownerType || '').toUpperCase() === 'CLIENT')
  const picked = warehouses.find((w) => w.id === selectedId) || null
  const hasAnyErr = err('name') || err('line1') || err('city') || err('state') || err('zip') || err('country')

  return (
    <div className="px-4 py-3 space-y-3">
      <div className="rounded-2xl border border-slate-200 bg-slate-50/60 p-3">
        <div className="flex items-start justify-between gap-3">
          <div>
            <h4 className="text-[12px] font-semibold text-slate-950">Ship From — pick a warehouse</h4>
            <p className="mb-2 text-[10.5px] leading-4 text-slate-500">
              Origin printed on this client's labels. Picking a warehouse copies its address into the
              client's Ship From and attaches it as the default warehouse on save.
              {isEdit && hiddenAttachedCount > 0 ? (
                <>
                  {' '}
                  <span className="text-slate-400">
                    ({hiddenAttachedCount} warehouse{hiddenAttachedCount === 1 ? '' : 's'} already
                    attached to this client {hiddenAttachedCount === 1 ? 'is' : 'are'} hidden —
                    detach from Settings → Warehouses to see them here.)
                  </span>
                </>
              ) : null}
            </p>
          </div>
          <button
            type="button"
            onClick={onAddWarehouseClick}
            className="inline-flex shrink-0 items-center gap-1 rounded-xl border border-slate-200 bg-white px-2.5 py-1 text-[11.5px] font-semibold text-slate-700 transition hover:bg-slate-50"
          >
            <FiPlus className="h-3 w-3" /> Add warehouse
          </button>
        </div>

        <Field label="Warehouse" required error={selectedId == null && (touched['shipFrom.line1'] || touched['shipFrom.name']) ? 'Pick a warehouse to set Ship From.' : null}>
          <Select
            value={selectedId != null ? String(selectedId) : ''}
            onChange={(e) => {
              const v = e.target.value
              if (!v) { onPick(null); return }
              const wh = warehouses.find((w) => String(w.id) === v) || null
              onPick(wh)
            }}
            disabled={loading}
            aria-label="Ship From warehouse"
          >
            <option value="">
              {loading ? 'Loading warehouses…' : warehouses.length === 0 ? 'No warehouses — add one first' : 'Pick a warehouse…'}
            </option>
            {platform.length ? (
              <optgroup label="Platform warehouses">
                {platform.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.code} — {w.name}{w.address?.country ? ` · ${w.address.country}` : ''}
                  </option>
                ))}
              </optgroup>
            ) : null}
            {isEdit && own.length ? (
              <optgroup label="Client-owned">
                {own.map((w) => (
                  <option key={w.id} value={w.id}>
                    {w.code} — {w.name}{w.address?.country ? ` · ${w.address.country}` : ''}
                  </option>
                ))}
              </optgroup>
            ) : null}
          </Select>
        </Field>

        {/* Address preview — the values that'll actually POST as Client.shipFrom.
            Read-only so the operator sees them as data derived from the picked
            warehouse; if they don't match, edit the warehouse instead. */}
        {picked ? (
          <div className={`mt-3 rounded-xl border ${hasAnyErr ? 'border-rose-300 bg-rose-50/40' : 'border-slate-200 bg-white'} px-3 py-2.5`}>
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <p className="flex items-center gap-1.5 text-[11.5px] font-semibold text-slate-800">
                  <FiHome className="h-3 w-3 text-slate-500" />
                  {picked.code} · {picked.name}
                  <span className={`ml-1 rounded-full px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide ${
                    (picked.ownerType || '').toUpperCase() === 'PLATFORM'
                      ? 'bg-sky-100 text-sky-700' : 'bg-[#412d15]/10 text-[#412d15]'
                  }`}>
                    {(picked.ownerType || 'PLATFORM').toUpperCase()}
                  </span>
                </p>
                <p className="mt-1 text-[11px] leading-4 text-slate-600">
                  {addressPreview.name ? <>{addressPreview.name}<br /></> : null}
                  {addressPreview.line1 || <span className="text-slate-400 italic">no street</span>}
                  {addressPreview.line2 ? <>, {addressPreview.line2}</> : ''}
                  <br />
                  {[addressPreview.city, addressPreview.state, addressPreview.zip].filter(Boolean).join(', ') || <span className="text-slate-400 italic">no city / state / zip</span>}
                  {addressPreview.country ? ` · ${addressPreview.country}` : ''}
                  {addressPreview.phone ? <><br />{addressPreview.phone}</> : null}
                </p>
              </div>
              {(picked.ownerType || '').toUpperCase() === 'CLIENT' ? (
                <button
                  type="button"
                  onClick={() => onEditWarehouseClick(picked)}
                  title="Edit this warehouse (shared across clients — CLIENT-owned only)"
                  className="inline-flex shrink-0 items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-[11px] font-semibold text-slate-700 transition hover:border-[#412d15] hover:bg-[#faf7f0] hover:text-[#412d15]"
                >
                  <FiEdit2 className="h-3 w-3" /> Edit
                </button>
              ) : null}
            </div>
            {hasAnyErr ? (
              <div className="mt-2 space-y-0.5 rounded-lg bg-rose-50 px-2 py-1.5 text-[10.5px] font-semibold text-rose-700">
                <p className="flex items-center gap-1">
                  <FiAlertCircle className="h-3 w-3" />
                  This warehouse's address is missing required fields:
                </p>
                <ul className="ml-4 list-disc">
                  {err('name') ? <li>{err('name')}</li> : null}
                  {err('line1') ? <li>{err('line1')}</li> : null}
                  {err('city') ? <li>{err('city')}</li> : null}
                  {err('state') ? <li>{err('state')}</li> : null}
                  {err('zip') ? <li>{err('zip')}</li> : null}
                  {err('country') ? <li>{err('country')}</li> : null}
                </ul>
                <p className="mt-1 text-slate-600">Edit the warehouse from Warehouses (or Settings → Warehouses) to fix these fields.</p>
              </div>
            ) : null}
          </div>
        ) : (
          <p className="mt-3 rounded-xl border border-dashed border-slate-200 bg-white px-3 py-2.5 text-center text-[11.5px] text-slate-500">
            {loading
              ? 'Loading available warehouses…'
              : warehouses.length === 0
                ? 'No warehouses in the system yet. Click Add warehouse above to create one.'
                : 'Pick a warehouse from the dropdown to preview its address.'}
          </p>
        )}
      </div>
    </div>
  )
}
