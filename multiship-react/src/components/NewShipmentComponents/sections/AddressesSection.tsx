import { FiMapPin, FiHome, FiCheckCircle, FiAlertTriangle, FiSearch, FiAlertCircle, FiBookmark } from 'react-icons/fi'
import { SectionCard } from '../_shared'
import { AddressBlock } from '../AddressBlock'


import type { NewShipmentModel } from '../useNewShipmentModel'

export function AddressesSection({ m }: { m: NewShipmentModel }) {
  const {
    activeParties, addrErrors, applySavedRecipient, clearFixKey, clientCode, destAllowed, destCountry, destRules, isInternational, isReturn, note, onRecipientSearchKey, recipient, recipientActive, recipientDropdownOpen, recipientNoMatch, recipientSearch, recipientSuggestions, runRecipientSearch, saveRecipientToBook, savingToBook, sender, setNote, setRecipient, setRecipientActive, setRecipientDropdownOpen, setResidentialOrigin, setSender,
  } = m

  return (
    <>
              <SectionCard
                icon={<FiHome className="h-3.5 w-3.5" />}
                id="sec-addresses"
                title={isReturn ? 'Return from · customer' : 'Ship from · sender'}
                className="xl:row-span-2"
              >
                <AddressBlock value={sender} onChange={(patch) => { setSender((s) => ({ ...s, ...patch })); Object.keys(patch).forEach((k) => clearFixKey(`sender.${k}`)) }} withEmail={isReturn} errors={addrErrors('sender')} />

                {/* V76 — internal per-order ops note. Not printed on
                    labels / commercial invoice; not on external API;
                    just a home for driver instructions, pickup hints,
                    handling flags. Surfaced in /orders list as an
                    icon-popover on the row. */}
                <div className="mt-4">
                  <label className="block">
                    <span className="mb-1 flex items-center justify-between text-[11px] font-bold uppercase tracking-[0.14em] text-[#6b5c42]">
                      <span>Internal note (optional)</span>
                      <span className={`normal-case tracking-normal ${note.length > 450 ? 'text-amber-600' : 'text-[#b6a684]'}`}>
                        {note.length}/500
                      </span>
                    </span>
                    <textarea
                      rows={3}
                      maxLength={500}
                      value={note}
                      onChange={(e) => setNote(e.target.value)}
                      placeholder="Driver instructions, pickup hints, handling flags… (not printed on labels)"
                      className="w-full resize-y rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[13px] text-[#1f150c] outline-none transition placeholder:text-[#b6a684] focus:border-[#cdbf9f] focus:ring-4 focus:ring-[#f4eede]"
                    />
                  </label>
                </div>
              </SectionCard>
              <SectionCard
                icon={<FiMapPin className="h-3.5 w-3.5" />}
                title={isReturn ? 'Return to · your address' : 'Ship to · recipient'}
                className="xl:row-span-2"
                note={isInternational ? (
                  // Surface the customs gate the moment the lane turns
                  // international — it used to appear only 1,500 px further
                  // down, after the operator had filled everything else.
                  activeParties ? (
                    <span className="inline-flex items-center gap-1.5 rounded-full border border-emerald-200 bg-emerald-50 px-2 py-0.5 text-[10.5px] font-semibold text-emerald-800">
                      <FiCheckCircle className="h-3 w-3" /> Importer/broker resolved for {destCountry}
                    </span>
                  ) : (
                    <button
                      type="button"
                      onClick={() => document.getElementById('sec-customs')?.scrollIntoView({ behavior: 'smooth', block: 'start' })}
                      className="inline-flex items-center gap-1.5 rounded-full border border-amber-300 bg-amber-50 px-2 py-0.5 text-[10.5px] font-semibold text-amber-800 hover:bg-amber-100"
                      title="International shipments need an importer/broker profile for the destination before a label can generate"
                    >
                      <FiAlertCircle className="h-3 w-3" /> No importer/broker profile for {destCountry} — set one up
                    </button>
                  )
                ) : undefined}
              >
                <AddressBlock
                  value={recipient}
                  onChange={(patch) => {
                    setRecipient((r) => ({ ...r, ...patch }))
                    // Any operator interaction with the residential
                    // checkbox flips the origin to 'manual' — after
                    // this, subsequent service changes won't auto-
                    // untick even if we're leaving Home Delivery.
                    // Applies whether they ticked OR unticked.
                    if (Object.prototype.hasOwnProperty.call(patch, 'residential')) {
                      setResidentialOrigin('manual')
                    }
                    // Editing the address invalidates any prior validation result,
                    // so clear the stale carrier / format-check banners.
                    Object.keys(patch).forEach((k) => clearFixKey(`recipient.${k}`))
                  }}
                  withEmail={!isReturn}
                  hideLine3
                  errors={addrErrors('recipient')}
                  extraAction={
                    // Sprint 38 — address-book combobox. Type ≥ 2 chars to
                    // search; picking a suggestion replaces the whole Ship to
                    // block. Keyboard: ↓/↑ to move, Enter to pick, Esc to close.
                    <div className="relative">
                      <div className="relative">
                        <input
                          type="text"
                          role="combobox"
                          aria-label="Search the address book by name, company, street, city, state, postal code, phone or email"
                          aria-autocomplete="list"
                          aria-expanded={recipientDropdownOpen}
                          aria-controls="recipient-book-list"
                          aria-activedescendant={recipientActive >= 0 ? `recipient-book-${recipientActive}` : undefined}
                          title="Type any part of the address — name, street, city, ZIP, phone… Several words narrow it down, e.g. &quot;wacker chicago&quot;."
                          value={recipientSearch}
                          onChange={(e) => runRecipientSearch(e.target.value)}
                          onKeyDown={onRecipientSearchKey}
                          onFocus={() => (recipientSuggestions.length > 0 || recipientNoMatch) && setRecipientDropdownOpen(true)}
                          onBlur={() => setTimeout(() => { setRecipientDropdownOpen(false); setRecipientActive(-1) }, 150)}
                          placeholder="Search by name, street, ZIP, phone…"
                          className="w-full rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 pl-8 text-[12px] outline-none focus:border-[#1f150c]"
                        />
                        <FiSearch className="pointer-events-none absolute left-2.5 top-1/2 h-3 w-3 -translate-y-1/2 text-[#6b5c42]" />
                      </div>
                      {recipientDropdownOpen && (recipientSuggestions.length > 0 || recipientNoMatch) ? (
                        // Wider than the input: at the input's width names read
                        // "ZZ Ja…" and two similar people can't be told apart.
                        <ul
                          id="recipient-book-list"
                          role="listbox"
                          aria-label="Saved addresses"
                          className="absolute left-0 z-20 mt-0.5 max-h-72 w-[min(26rem,calc(100vw-3rem))] overflow-y-auto rounded-lg border border-slate-200 bg-white shadow-lg"
                        >
                          {recipientSuggestions.length === 0 ? (
                            <li className="px-3 py-2 text-[11.5px] text-slate-500" role="option" aria-disabled="true" aria-selected="false">
                              No saved addresses match &ldquo;{recipientSearch.trim()}&rdquo;.
                            </li>
                          ) : recipientSuggestions.map((s, i) => (
                            <li
                              key={s.id}
                              id={`recipient-book-${i}`}
                              role="option"
                              aria-selected={i === recipientActive}
                              onMouseDown={(e) => { e.preventDefault(); applySavedRecipient(s) }}
                              onMouseEnter={() => setRecipientActive(i)}
                              className={`flex cursor-pointer items-start justify-between gap-2 px-3 py-2 text-left text-[11.5px] ${
                                i === recipientActive ? 'bg-[#faf7f0]' : 'hover:bg-slate-50'}`}
                            >
                              <div className="min-w-0">
                                <p className="truncate font-semibold text-slate-950">
                                  {s.name}{s.company ? <span className="font-normal text-slate-500"> · {s.company}</span> : null}
                                </p>
                                <p className="truncate text-[10.5px] text-slate-500">
                                  {s.addressLine1}
                                  {s.city ? `, ${s.city}` : ''}
                                  {s.state ? `, ${s.state}` : ''}
                                  {' '}{s.postalCode} {s.countryCode}
                                </p>
                              </div>
                              <span className="flex shrink-0 flex-col items-end gap-0.5">
                                {/* With no client picked an admin sees every client's entries — say whose. */}
                                {!clientCode && s.ownerCustomerNo ? (
                                  <span className="whitespace-nowrap rounded-full bg-[#f3ecdd] px-1.5 py-0.5 text-[9.5px] font-semibold text-[#5a4526]"
                                    title={`Saved for client ${s.ownerCustomerNo}`}>
                                    {s.ownerCustomerNo}
                                  </span>
                                ) : null}
                                {s.tag ? (
                                  <span className="whitespace-nowrap rounded-full bg-slate-100 px-1.5 py-0.5 text-[9.5px] font-semibold text-slate-500">
                                    {s.tag}
                                  </span>
                                ) : null}
                              </span>
                            </li>
                          ))}
                        </ul>
                      ) : null}
                    </div>
                  }
                />
                <div className="mt-3 flex flex-wrap justify-end gap-2">
                  <button
                    type="button"
                    onClick={() => void saveRecipientToBook()}
                    disabled={savingToBook}
                    title={clientCode
                      ? `Save this Ship to address to ${clientCode}'s address book`
                      : 'Save this Ship to address as a shared entry every client can use'}
                    className="inline-flex items-center gap-1.5 rounded-lg border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0] disabled:opacity-50"
                  >
                    {savingToBook ? (
                      <span className="inline-block h-3 w-3 animate-spin rounded-full border-2 border-[#cdbf9f] border-t-[#5a4526]" />
                    ) : (
                      <FiBookmark className="h-3.5 w-3.5" />
                    )}
                    Save to address book
                  </button>
                </div>
                {!destAllowed && destRules?.mode && recipient.countryCode ? (
                  <div className="mt-3 rounded-xl border border-amber-200 bg-amber-50 px-3 py-2 text-[12px] text-amber-800">
                    <p className="flex items-center gap-2 font-semibold">
                      <FiAlertTriangle className="h-4 w-4 shrink-0" />
                      {destRules.mode === 'ALLOW'
                        ? `${clientCode} is not configured to ship to ${recipient.countryCode.toUpperCase()}.`
                        : `${clientCode} has ${recipient.countryCode.toUpperCase()} on its deny list.`}
                    </p>
                    <p className="mt-0.5 pl-6 text-[11.5px] text-amber-700">
                      Update the client's Destinations tab, or pick a different country to proceed.
                    </p>
                  </div>
                ) : null}
              </SectionCard>
    </>
  )
}
