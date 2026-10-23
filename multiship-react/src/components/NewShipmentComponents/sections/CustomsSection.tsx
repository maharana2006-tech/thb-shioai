import { FiGlobe, FiEdit3 } from 'react-icons/fi'
import { SectionCard } from '../_shared'


import type { NewShipmentModel } from '../useNewShipmentModel'

export function CustomsSection({ m }: { m: NewShipmentModel }) {
  const {
    activeParties, clientCode, destCountry, isInternational, joinParts, override, setOverride, setOverrideEditorOpen, vBrk, vImp,
  } = m
  if (!isInternational) return null
  return (

              <SectionCard
                icon={<FiGlobe className="h-3.5 w-3.5" />}
                id="sec-customs"
                title="Importer of record & customs broker"
                badge={
                  <div className="flex items-center gap-2">
                    {override ? (
                      <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.1em] text-amber-700">
                        Overridden · this shipment
                      </span>
                    ) : activeParties ? (
                      // BUG-010: only show the "Resolved" badge when a profile
                      // actually covers destCountry. Pre-fix the badge rendered
                      // next to the "No importer/broker profile covers MX"
                      // warning, which was contradictory.
                      <span className="rounded-full bg-sky-100 px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.1em] text-sky-700">
                        Resolved for {destCountry || '—'}
                      </span>
                    ) : (
                      <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.1em] text-slate-600">
                        Not configured
                      </span>
                    )}
                    <button
                      type="button"
                      onClick={() => setOverrideEditorOpen(true)}
                      className="inline-flex items-center gap-1 rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1 text-[11px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
                    >
                      <FiEdit3 className="h-3 w-3" />
                      {activeParties ? 'Edit' : 'Add'}
                    </button>
                    {override ? (
                      <button
                        type="button"
                        onClick={() => setOverride(null)}
                        className="text-[11px] font-semibold text-[#6b5c42] underline-offset-2 hover:underline"
                      >
                        Reset
                      </button>
                    ) : null}
                  </div>
                }
              >
                {activeParties && vImp && vBrk ? (
                  <div className="grid gap-3 sm:grid-cols-2">
                    <div className="rounded-xl border border-[#e3d9c4] bg-[#faf7f0]/60 p-3">
                      <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-[#6b5c42]">Importer of record</p>
                      <p className="mt-1 text-[13.5px] font-semibold text-[#1f150c]">{vImp.name || '—'}</p>
                      {joinParts([vImp.addressLine1, vImp.addressLine2]) ? (
                        <p className="text-[12px] text-[#5a4526]">{joinParts([vImp.addressLine1, vImp.addressLine2])}</p>
                      ) : null}
                      <p className="text-[12px] text-[#5a4526]">
                        {joinParts([vImp.city, vImp.state, vImp.postalCode, vImp.countryCode])}
                      </p>
                      {vImp.phone ? <p className="text-[11.5px] text-[#6b5c42]">PH: {vImp.phone}</p> : null}
                      {joinParts(
                        [
                          vImp.iec ? `IEC ${vImp.iec}` : '',
                          vImp.gstin ? `GSTIN ${vImp.gstin}` : '',
                          vImp.eori ? `EORI ${vImp.eori}` : '',
                          vImp.taxId ? `${vImp.taxIdType || 'Tax ID'} ${vImp.taxId}` : '',
                          vImp.ioss ? `IOSS ${vImp.ioss}` : '',
                        ],
                        ' · ',
                      ) ? (
                        <p className="mt-1 font-mono text-[11px] text-[#6b5c42]">
                          {joinParts(
                            [
                              vImp.iec ? `IEC ${vImp.iec}` : '',
                              vImp.gstin ? `GSTIN ${vImp.gstin}` : '',
                              vImp.eori ? `EORI ${vImp.eori}` : '',
                              vImp.taxId ? `${vImp.taxIdType || 'Tax ID'} ${vImp.taxId}` : '',
                              vImp.ioss ? `IOSS ${vImp.ioss}` : '',
                            ],
                            ' · ',
                          )}
                        </p>
                      ) : null}
                      <span className="mt-2 inline-block rounded-full bg-[#efe7d4] px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.1em] text-[#5a4526]">
                        {vImp.type === 'RECEIVER' ? 'Receiver · DAP' : 'Business · DDP'}
                      </span>
                    </div>
                    <div className="rounded-xl border border-[#e3d9c4] bg-[#faf7f0]/60 p-3">
                      <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-[#6b5c42]">Customs broker</p>
                      {vBrk.name || vBrk.company ? (
                        <>
                          <p className="mt-1 text-[13.5px] font-semibold text-[#1f150c]">{vBrk.name || vBrk.company}</p>
                          {vBrk.name && vBrk.company ? <p className="text-[12px] text-[#5a4526]">{vBrk.company}</p> : null}
                          {joinParts([vBrk.addressLine1, vBrk.city, vBrk.state, vBrk.postalCode, vBrk.countryCode]) ? (
                            <p className="text-[12px] text-[#5a4526]">
                              {joinParts([vBrk.addressLine1, vBrk.city, vBrk.state, vBrk.postalCode, vBrk.countryCode])}
                            </p>
                          ) : null}
                          <span className="mt-2 inline-block rounded-full bg-[#efe7d4] px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.1em] text-[#5a4526]">
                            Broker Select
                          </span>
                        </>
                      ) : (
                        <p className="mt-1 text-[12.5px] text-[#5a4526]">
                          Carrier clears customs <span className="text-[#6b5c42]">(carrier default brokerage)</span>
                        </p>
                      )}
                    </div>
                  </div>
                ) : (
                  <div className="rounded-xl border border-amber-200 bg-amber-50 px-3 py-2.5 text-[12px] text-amber-800">
                    {clientCode
                      ? <>No importer/broker profile covers <strong>{destCountry}</strong> for {clientCode}.</>
                      : 'No client selected.'}{' '}
                    Click <strong>Add</strong> to enter importer/broker for <strong>this shipment only</strong> (the
                    client's saved profile is not changed).
                  </div>
                )}
              </SectionCard>

  )
}
