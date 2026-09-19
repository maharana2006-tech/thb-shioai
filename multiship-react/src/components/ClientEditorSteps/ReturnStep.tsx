/**
 * F5-B — Return address step of the client-editor wizard. Extracted
 * from ClientEditorPage. When "Same as Ship From" is checked, no
 * form fields render — the API mirror handles it. Otherwise this
 * hosts the shared AddressGrid.
 */
import type { Address } from '../../api/clientService'
import type { AddressLike } from '../../utils/clientValidation'
import type {
  AddressCaps,
  CarrierCode,
} from '../../utils/carrierFieldLimits'
import { AddressGrid } from './AddressGrid'

export interface ReturnStepProps {
  block: Address
  same: boolean
  setSame: (next: boolean) => void
  errors: Partial<Record<keyof AddressLike, string>>
  touched: Record<string, boolean>
  markTouched: (key: string) => void
  setAddr: (
    block: 'shipFrom' | 'returnAddress',
    key: keyof Address,
  ) => (e: { target: { value: string } }) => void
  caps: AddressCaps
  bindingHint: (field: keyof AddressCaps) => CarrierCode[]
}

export function ReturnStep({
  block,
  same,
  setSame,
  errors,
  touched,
  markTouched,
  setAddr,
  caps,
  bindingHint,
}: ReturnStepProps) {
  const err = (k: keyof AddressLike) => (touched[`returnAddress.${k}`] ? errors[k] || null : null)
  return (
    <div className="px-4 py-3">
      <div className="rounded-2xl border border-slate-200 bg-slate-50/60 p-3">
        <div className="flex items-start justify-between gap-2">
          <div>
            <h4 className="text-[12px] font-semibold text-slate-950">Return address</h4>
            <p className="text-[10.5px] leading-4 text-slate-500">
              Where undeliverable parcels come back to.
            </p>
          </div>
          <label className="flex shrink-0 items-center gap-1.5 pt-0.5 text-[11px] font-semibold text-slate-700">
            <input
              type="checkbox"
              checked={same}
              onChange={(e) => setSame(e.target.checked)}
              className="h-3.5 w-3.5 rounded border-slate-300 text-slate-950 focus:ring-slate-300"
            />
            Same as Ship From
          </label>
        </div>
        {same ? (
          <p className="mt-2 rounded-xl border border-dashed border-slate-200 bg-white px-3 py-2 text-center text-[11px] text-slate-500">
            Returns use the Ship From address.
          </p>
        ) : (
          <div className="mt-2">
            <AddressGrid
              block={block}
              addressKey="returnAddress"
              err={err}
              markTouched={markTouched}
              setAddr={setAddr}
              caps={caps}
              bindingHint={bindingHint}
            />
          </div>
        )}
      </div>
    </div>
  )
}
