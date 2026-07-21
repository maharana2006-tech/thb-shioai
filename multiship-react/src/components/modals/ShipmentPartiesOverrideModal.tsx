import { useState, type ReactNode } from 'react'
import { FiX, FiGlobe } from 'react-icons/fi'

export type Party = Record<string, string>

const inputCls =
  'w-full rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[13px] text-[#1f150c] outline-none transition placeholder:text-[#b6a684] focus:border-[#cdbf9f] focus:ring-4 focus:ring-[#f4eede]'

function Field({ label, children, className = '' }: { label: string; children: ReactNode; className?: string }) {
  return (
    <label className={`block space-y-1 ${className}`}>
      <span className="text-[10px] font-bold uppercase tracking-[0.14em] text-[#8a7959]">{label}</span>
      {children}
    </label>
  )
}

/**
 * Per-shipment importer/broker override. Edits apply to THIS label only — the
 * client's saved Importer/Broker profile is never modified.
 */
export default function ShipmentPartiesOverrideModal({
  importer,
  broker,
  destCountry,
  onSave,
  onClose,
}: {
  importer: Party
  broker: Party
  destCountry: string
  onSave: (importer: Party, broker: Party) => void
  onClose: () => void
}) {
  const [imp, setImp] = useState<Party>({ ...importer })
  const [brk, setBrk] = useState<Party>({ ...broker })
  const si = (k: string) => (e: React.ChangeEvent<HTMLInputElement | HTMLSelectElement>) => setImp((p) => ({ ...p, [k]: e.target.value }))
  const sb = (k: string) => (e: React.ChangeEvent<HTMLInputElement>) => setBrk((p) => ({ ...p, [k]: e.target.value }))

  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center overflow-y-auto bg-slate-950/40 p-4 backdrop-blur-sm">
      <div className="my-6 w-full max-w-3xl overflow-hidden rounded-2xl bg-white shadow-2xl">
        <div className="flex items-start justify-between gap-4 bg-[#1f150c] px-6 py-4 text-[#f4eede]">
          <div>
            <p className="flex items-center gap-1.5 font-mono text-[10px] font-bold uppercase tracking-[0.22em] text-[#b6a684]">
              <FiGlobe className="h-3 w-3" /> This shipment only · {destCountry || '—'}
            </p>
            <h2 className="mt-0.5 text-lg font-semibold">Importer & broker override</h2>
          </div>
          <button type="button" onClick={onClose} className="rounded-lg p-1.5 text-[#b6a684] transition hover:bg-white/10 hover:text-white" aria-label="Close">
            <FiX className="h-5 w-5" />
          </button>
        </div>

        <div className="max-h-[68vh] space-y-5 overflow-y-auto px-6 py-5">
          <p className="rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-[12px] text-amber-800">
            Changes here apply to <strong>this label only</strong> and do not modify the client's saved
            Importer/Broker profile.
          </p>

          {/* Importer */}
          <section className="space-y-3">
            <h3 className="border-b border-dashed border-[#e3d9c4] pb-1.5 font-mono text-[10px] font-bold uppercase tracking-[0.16em] text-[#8a7959]">
              Importer of record
            </h3>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <Field label="Type">
                <select className={inputCls} value={imp.type || 'BUSINESS'} onChange={si('type')}>
                  <option value="BUSINESS">Business · DDP</option>
                  <option value="RECEIVER">Receiver · DAP</option>
                </select>
              </Field>
              <Field label="Name" className="col-span-2 sm:col-span-2">
                <input className={inputCls} value={imp.name || ''} onChange={si('name')} placeholder="Importer name" />
              </Field>
              <Field label="Contact">
                <input className={inputCls} value={imp.contact || ''} onChange={si('contact')} />
              </Field>
              <Field label="Address 1" className="col-span-2">
                <input className={inputCls} value={imp.addressLine1 || ''} onChange={si('addressLine1')} />
              </Field>
              <Field label="Address 2" className="col-span-2">
                <input className={inputCls} value={imp.addressLine2 || ''} onChange={si('addressLine2')} />
              </Field>
              <Field label="City">
                <input className={inputCls} value={imp.city || ''} onChange={si('city')} />
              </Field>
              <Field label="State">
                <input className={inputCls} value={imp.state || ''} onChange={si('state')} />
              </Field>
              <Field label="Post code">
                <input className={inputCls} value={imp.postalCode || ''} onChange={si('postalCode')} />
              </Field>
              <Field label="Country">
                <input className={`${inputCls} uppercase`} value={imp.countryCode || ''} onChange={si('countryCode')} maxLength={2} />
              </Field>
              <Field label="Phone">
                <input className={inputCls} value={imp.phone || ''} onChange={si('phone')} />
              </Field>
              <Field label="IEC">
                <input className={inputCls} value={imp.iec || ''} onChange={si('iec')} />
              </Field>
              <Field label="GSTIN">
                <input className={inputCls} value={imp.gstin || ''} onChange={si('gstin')} />
              </Field>
              <Field label="EORI">
                <input className={inputCls} value={imp.eori || ''} onChange={si('eori')} />
              </Field>
              <Field label="Tax ID">
                <input className={inputCls} value={imp.taxId || ''} onChange={si('taxId')} />
              </Field>
            </div>
          </section>

          {/* Broker */}
          <section className="space-y-3">
            <h3 className="border-b border-dashed border-[#e3d9c4] pb-1.5 font-mono text-[10px] font-bold uppercase tracking-[0.16em] text-[#8a7959]">
              Customs broker <span className="font-sans normal-case tracking-normal text-[#b6a684]">— leave name blank for carrier-default brokerage</span>
            </h3>
            <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
              <Field label="Name" className="col-span-2">
                <input className={inputCls} value={brk.name || ''} onChange={sb('name')} placeholder="Broker name" />
              </Field>
              <Field label="Company" className="col-span-2">
                <input className={inputCls} value={brk.company || ''} onChange={sb('company')} />
              </Field>
              <Field label="Address 1" className="col-span-2">
                <input className={inputCls} value={brk.addressLine1 || ''} onChange={sb('addressLine1')} />
              </Field>
              <Field label="City">
                <input className={inputCls} value={brk.city || ''} onChange={sb('city')} />
              </Field>
              <Field label="State">
                <input className={inputCls} value={brk.state || ''} onChange={sb('state')} />
              </Field>
              <Field label="Post code">
                <input className={inputCls} value={brk.postalCode || ''} onChange={sb('postalCode')} />
              </Field>
              <Field label="Country">
                <input className={`${inputCls} uppercase`} value={brk.countryCode || ''} onChange={sb('countryCode')} maxLength={2} />
              </Field>
              <Field label="Phone">
                <input className={inputCls} value={brk.phone || ''} onChange={sb('phone')} />
              </Field>
            </div>
          </section>
        </div>

        <div className="flex items-center justify-end gap-2 border-t border-[#e3d9c4] bg-[#faf7f0] px-6 py-3.5">
          <button
            type="button"
            onClick={onClose}
            className="rounded-xl border border-[#e3d9c4] bg-white px-3.5 py-2 text-[12.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-white"
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={() => onSave(imp, brk)}
            className="rounded-xl bg-[#1f150c] px-4 py-2 text-[12.5px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15]"
          >
            Use for this shipment
          </button>
        </div>
      </div>
    </div>
  )
}
