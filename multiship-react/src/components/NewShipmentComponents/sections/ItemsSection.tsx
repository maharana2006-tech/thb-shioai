import type { NewShipmentItemRow } from '../../../shipment/useManualShipment'
import { memo, useCallback, useRef } from 'react'
import { useVirtualizer } from '@tanstack/react-virtual'
import { FiZap, FiFileText, FiPlus, FiTrash2, FiGlobe, FiAlertTriangle, FiClipboard } from 'react-icons/fi'
import HsCodeCombobox from '../../shipment/HsCodeCombobox'
import PasteItemsModal from '../../modals/PasteItemsModal'
import { hsExampleFor } from '../../../validation/yup/shipmentSchema'
import { SectionCard, inputCls } from '../_shared'


import type { NewShipmentModel } from '../useNewShipmentModel'

export function ItemsSection({ m }: { m: NewShipmentModel }) {
  const {
    addItem, assignAllToBox1, autoItemWeight, canEstimateLandedCost, carrier, currency, destCountry, extraPackages, fixItemError, importPastedItems, invoiceTotal, isInternational, itemErrAt, items, pasteOpen, patchItem, recipient, recipientTerritoryEarly, removeItem, sender, senderTerritoryEarly, setLandedCostOpen, setPasteOpen, setWizardOpen, spreadItemsAcrossBoxes, weightUnit,
  } = m
  if (!isInternational) return null
  return (

              <SectionCard
                icon={<FiFileText className="h-3.5 w-3.5" />}
                id="sec-items"
                title="Items · commercial invoice"
                badge={
                  <div className="flex items-center gap-2">
                    <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-bold uppercase tracking-[0.1em] text-amber-700">
                      Cross-border · required
                    </span>
                    <button
                      type="button"
                      onClick={() => setPasteOpen(true)}
                      title="Paste rows copied from Excel / Sheets"
                      className="inline-flex items-center gap-1 rounded-lg border border-[#412d15] bg-white px-2.5 py-1 text-[11px] font-semibold text-[#412d15] transition hover:bg-[#faf7f0] shadow-sm"
                    >
                      <FiClipboard className="h-3.5 w-3.5" /> Paste from spreadsheet
                    </button>
                    {pasteOpen ? <PasteItemsModal onImport={importPastedItems} onClose={() => setPasteOpen(false)} /> : null}
                    <button
                      type="button"
                      onClick={() => setWizardOpen(true)}
                      title="Step through the customs declaration with per-carrier hints"
                      className="inline-flex items-center gap-1 rounded-lg border border-emerald-600 bg-white px-2.5 py-1 text-[11px] font-semibold text-emerald-700 transition hover:bg-emerald-50 shadow-sm"
                    >
                      <FiGlobe className="h-3.5 w-3.5" /> Guided wizard
                    </button>
                    <button
                      type="button"
                      disabled={!canEstimateLandedCost}
                      onClick={() => setLandedCostOpen(true)}
                      title={canEstimateLandedCost
                        ? `Ask ${carrier} for freight + duty + tax estimate`
                        : 'Pick a carrier + fill weight + declared value first'}
                      className="inline-flex items-center gap-1 rounded-lg border border-[#1f150c] bg-[#1f150c] px-2.5 py-1 text-[11px] font-semibold text-[#f4eede] transition hover:bg-[#33221a] disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      <FiZap className="h-3.5 w-3.5" /> Landed cost
                    </button>
                    {extraPackages.length > 0 ? (
                      <span className="inline-flex items-center overflow-hidden rounded-lg border border-[#e3d9c4] bg-white text-[11px] font-semibold text-[#5a4526] shadow-sm" title="Which box each line travels in — printed per package on the commercial invoice">
                        <span className="bg-[#f4eede] px-2 py-1 text-[10px] font-bold uppercase tracking-[0.1em] text-[#6b5c42]">Boxes</span>
                        <button type="button" onClick={assignAllToBox1} className="px-2.5 py-1 transition hover:bg-[#faf7f0]" title="Every line in box 1">
                          All in box 1
                        </button>
                        <button type="button" onClick={spreadItemsAcrossBoxes} className="border-l border-[#e3d9c4] px-2.5 py-1 transition hover:bg-[#faf7f0]" title={`Split the lines in order into ${1 + extraPackages.length} runs, one per box`}>
                          Spread across {1 + extraPackages.length} boxes
                        </button>
                      </span>
                    ) : null}
                    <button
                      type="button"
                      onClick={addItem}
                      className="inline-flex items-center gap-1 rounded-lg border border-dashed border-[#cdbf9f] bg-white px-2.5 py-1 text-[11px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
                    >
                      <FiPlus className="h-3.5 w-3.5" /> Add item
                      <span className="ml-1 rounded-full bg-[#f4eede] px-1.5 py-0.5 text-[10px] font-bold tabular-nums text-[#5a4526]" title="Commodity lines on this shipment">
                        {items.length}
                      </span>
                    </button>
                  </div>
                }
                note={
                  <>
                    {(senderTerritoryEarly ?? sender.countryCode)} → {(recipientTerritoryEarly ?? recipient.countryCode)} crosses a customs border. These lines print on the
                    commercial invoice customs uses to assess duty &amp; tax. Importer/broker resolve from the client's
                    customs profile.
                  </>
                }
              >
                {fixItemError ? (
                  <div className="mb-2 flex items-start gap-2 rounded-lg border border-rose-300 bg-rose-50 px-3 py-2 text-[12px] text-rose-700">
                    <FiAlertTriangle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
                    <span>The carrier rejected the customs items — check each line's HS code, quantity and unit value (and that they sum to the declared value).</span>
                  </div>
                ) : null}
                <div className="space-y-1.5 overflow-x-auto">
                  {/* header labels (once) */}
                  {/* Sprint 48 B11 — added "Pkg #" column between Amount and trash.
                      PR #558 — added "Weight" column between Unit value and Amount for
                      per-commodity weight (required by FedEx for intl shipments).
                      Total packages available = primary box + extraPackages. */}
                  <div className="hidden min-w-[900px] grid-cols-[minmax(0,2fr)_1fr_0.9fr_0.55fr_0.55fr_1fr_0.7fr_1fr_0.6fr_44px] gap-2 sm:grid">
                    {['Description *', 'SKU', 'HS code', 'Origin', 'Qty', `Unit value (${currency}) *`, `Wt (${weightUnit.toLowerCase()})`, `Amount (${currency})`, 'Pkg #'].map((h) => (
                      <span key={h} className="text-[11px] font-bold uppercase tracking-[0.14em] text-[#6b5c42]">{h}</span>
                    ))}
                    <span />
                  </div>

                  <VirtualizedNewShipmentItems
                    items={items}
                    patchItem={patchItem}
                    removeItem={removeItem}
                    totalPkgs={1 + extraPackages.length}
                    canRemove={items.length > 1}
                    inputCls={inputCls}
                    itemErr={itemErrAt}
                    // PR #558 — weight helpers for the per-item Wt column.
                    weightUnit={weightUnit}
                    autoItemWeight={autoItemWeight}
                    hsPlaceholder={hsExampleFor(destCountry)}
                    destCountry={destCountry}
                  />

                  {/* invoice total */}
                  <div className="flex min-w-[760px] items-center justify-end gap-3 border-t border-dashed border-[#e3d9c4] px-0.5 pt-2">
                    <span className="text-[11px] font-bold uppercase tracking-[0.14em] text-[#6b5c42]">Total amount</span>
                    <span className="font-mono text-[14px] font-semibold tabular-nums text-[#1f150c]">
                      {invoiceTotal.toFixed(2)} {currency}
                    </span>
                  </div>
                </div>
              </SectionCard>

  )
}

/**
 * Virtualized wrapper for the CI items table. Renders only the rows in /
 * near the viewport plus a small overscan; DOM stays at ~10 rows regardless
 * of items.length. Fixes the jank-past-30-items audit finding.
 *
 * <p>Uses measured height (ResizeObserver) so rows with wrapping
 * descriptions or an open HsCodeCombobox dropdown expand naturally.
 * 60vh scroll container is responsive to viewport height.
 */
/** Red-outline modifier appended to an input's class when it has an error. */
const itemErrRing = (msg?: string): string =>
  msg ? ' !border-rose-400 focus:!border-rose-400 focus:!ring-rose-100' : ''

export function VirtualizedNewShipmentItems({
  items,
  patchItem,
  removeItem,
  totalPkgs,
  canRemove,
  inputCls,
  itemErr,
  weightUnit,
  autoItemWeight,
  hsPlaceholder,
  destCountry,
}: {
  items: NewShipmentItemRow[]
  patchItem: (i: number, patch: Partial<NewShipmentItemRow>) => void
  removeItem: (i: number) => void
  totalPkgs: number
  canRemove: boolean
  inputCls: string
  /** Per-item validation lookup (only returns messages after a submit attempt). */
  itemErr?: (index: number, field: string) => string | undefined
  /** PR #558 — LB / KG suffix on the weight column placeholder. */
  weightUnit: string
  /** PR #558 — compute the auto-fill weight from pkg weight × qty share. */
  autoItemWeight: (it: NewShipmentItemRow) => number
  /** Destination-aware HS example (hsExampleFor) for the code placeholder. */
  hsPlaceholder?: string
  /** BUG-011 — forwarded to each row's HsCodeCombobox for min-digit padding. */
  destCountry?: string
}) {
  const parentRef = useRef<HTMLDivElement>(null)
  // eslint-disable-next-line react-hooks/incompatible-library -- TanStack Virtual's useVirtualizer() returns functions that cannot be memoized safely — library-level incompatibility with react-hooks analyzer, not a code issue
  const virtualizer = useVirtualizer({
    count: items.length,
    getScrollElement: () => parentRef.current,
    estimateSize: () => 72,
    overscan: 5,
  })
  return (
    <div
      ref={parentRef}
      data-items-scroll=""
      style={{ height: '60vh', overflowY: 'auto', contain: 'strict' }}
      className="rounded-xl border border-slate-200 bg-white"
    >
      <div
        style={{
          height: `${virtualizer.getTotalSize()}px`,
          width: '100%',
          position: 'relative',
        }}
      >
        {virtualizer.getVirtualItems().map((virtualRow) => (
          <div
            key={virtualRow.key}
            data-index={virtualRow.index}
            ref={virtualizer.measureElement}
            style={{
              position: 'absolute',
              top: 0,
              left: 0,
              width: '100%',
              transform: `translateY(${virtualRow.start}px)`,
            }}
            className="px-2 py-1"
          >
            <NewShipmentItemsRow
              index={virtualRow.index}
              it={items[virtualRow.index]}
              patchItem={patchItem}
              removeItem={removeItem}
              totalPkgs={totalPkgs}
              canRemove={canRemove}
              inputCls={inputCls}
              itemErr={itemErr}
              weightUnit={weightUnit}
              autoItemWeight={autoItemWeight}
              hsPlaceholder={hsPlaceholder}
              destCountry={destCountry}
            />
          </div>
        ))}
      </div>
    </div>
  )
}

/** Memoized row — typing in one row doesn't reconcile the other 99. */
const NewShipmentItemsRow = memo(function NewShipmentItemsRow({
  index,
  it,
  patchItem,
  removeItem,
  totalPkgs,
  canRemove,
  inputCls,
  itemErr,
  weightUnit,
  autoItemWeight,
  hsPlaceholder,
  destCountry,
}: {
  index: number
  it: NewShipmentItemRow
  patchItem: (i: number, patch: Partial<NewShipmentItemRow>) => void
  removeItem: (i: number) => void
  totalPkgs: number
  canRemove: boolean
  inputCls: string
  itemErr?: (index: number, field: string) => string | undefined
  weightUnit: string
  autoItemWeight: (it: NewShipmentItemRow) => number
  /** Destination-aware HS example (from hsExampleFor) so the placeholder
   *  never suggests a code the country-minimum rule would reject. */
  hsPlaceholder?: string
  /** BUG-011 — passed through to HsCodeCombobox for min-digit padding. */
  destCountry?: string
}) {
  const patch = useCallback((delta: Partial<NewShipmentItemRow>) => patchItem(index, delta), [patchItem, index])
  const remove = useCallback(() => removeItem(index), [removeItem, index])
  const amount = (Number(it.quantity) || 0) * (Number(it.unitValue) || 0)
  const err = (field: string) => (itemErr ? itemErr(index, field) : undefined)
  // PR #558 — placeholder shows the auto-computed weight so operator
  // knows what BE will send when they leave the cell blank.
  const autoWt = autoItemWeight(it)
  const wtPlaceholder = autoWt > 0 ? `auto ${autoWt.toFixed(2)}` : '0.10'
  return (
    <div className="space-y-1">
      <div className="grid min-w-[900px] grid-cols-[minmax(0,2fr)_1fr_0.9fr_0.55fr_0.55fr_1fr_0.7fr_1fr_0.6fr_44px] items-start gap-2">
        <input className={`${inputCls}${itemErrRing(err('description'))}`} value={it.description} onChange={(e) => patch({ description: e.target.value })} placeholder="Cotton t-shirt" />
        <input className={`${inputCls}${itemErrRing(err('sku'))}`} value={it.sku} onChange={(e) => patch({ sku: e.target.value })} placeholder="SKU-001" />
        <HsCodeCombobox
          value={it.hsCode}
          placeholder={hsPlaceholder}
          destCountry={destCountry}
          onChange={(v) => patch({ hsCode: v })}
          onDescriptionSuggest={(desc) => {
            if (!it.description || !it.description.trim()) patch({ description: desc })
          }}
        />
        <input className={`${inputCls} uppercase${itemErrRing(err('countryOfOrigin'))}`} value={it.countryOfOrigin} onChange={(e) => patch({ countryOfOrigin: e.target.value })} placeholder="US" maxLength={2} />
        <input className={`${inputCls}${itemErrRing(err('quantity'))}`} type="number" min="1" step="1" value={it.quantity} onFocus={(e) => e.currentTarget.select()} onChange={(e) => patch({ quantity: e.target.value })} placeholder="1" />
        <input className={`${inputCls}${itemErrRing(err('unitValue'))}`} type="number" min="0" step="0.01" value={it.unitValue} onChange={(e) => patch({ unitValue: e.target.value })} placeholder="20.00" />
        {/* PR #558 — per-item weight; blank = auto from pkg weight × qty share.
            Placeholder shows the computed value so operator sees what BE
            will use. Type to override. */}
        <input
          className={`${inputCls}${itemErrRing(err('weight'))}`}
          type="number" min="0" step="0.01"
          value={it.weight}
          onChange={(e) => patch({ weight: e.target.value })}
          placeholder={wtPlaceholder}
          title={`Weight of one row (${weightUnit.toLowerCase()}) — auto-fills from package weight × qty share when blank.`}
        />
        <div className={`${inputCls} flex items-center justify-end bg-[#faf7f0] font-mono tabular-nums`}>
          {amount > 0 ? amount.toFixed(2) : '—'}
        </div>
        <select
          aria-label={`Package for item ${index + 1}`}
          className={inputCls}
          value={it.boxSeq}
          onChange={(e) => patch({ boxSeq: e.target.value })}
          title="Assign this item to a specific package. Backend sums assigned items per box for declared value."
        >
          <option value="">—</option>
          {Array.from({ length: totalPkgs }, (_, n) => n + 1).map((n) => (
            <option key={n} value={String(n)}>{n}</option>
          ))}
        </select>
        <div className="flex items-center justify-center">
          <button
            type="button"
            onClick={remove}
            disabled={!canRemove}
            className="inline-flex h-9 w-9 items-center justify-center rounded-lg border border-[#e3d9c4] bg-white text-[#6b5c42] transition hover:border-rose-200 hover:bg-rose-50 hover:text-rose-600 disabled:cursor-not-allowed disabled:opacity-40"
            aria-label={`Remove item ${index + 1}`}
          >
            <FiTrash2 className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>
      {(() => {
        const msgs = [
          err('description'),
          err('sku'),
          err('hsCode'),
          err('countryOfOrigin'),
          err('quantity'),
          err('unitValue'),
          err('weight'),
        ].filter(Boolean)
        return msgs.length ? (
          <p className="ms-field-error px-1 text-[10.5px] font-semibold text-rose-600">{msgs.join(' · ')}</p>
        ) : null
      })()}
    </div>
  )
})
