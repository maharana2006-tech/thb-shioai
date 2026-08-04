import { useEffect, useMemo, useState } from 'react'
import {
  FiAlertCircle,
  FiCheckCircle,
  FiEye,
  FiLoader,
  FiPackage,
  FiPlus,
  FiTrash2,
  FiTruck,
  FiX,
  FiZap,
} from 'react-icons/fi'
import { ApiError } from '../../api/apiClient'
import {
  clientWarehouseService,
  type ClientWarehouse,
} from '../../api/warehouseService'
import {
  multiWarehouseLabelService,
  type MultiWarehouseLabelPayload,
  type MultiWarehouseLabelResponse,
  type MultiWarehouseLineItem,
  type MultiWarehousePreviewResponse,
} from '../../api/multiWarehouseLabelService'
import type { ManualShipmentAddress } from '../../api/orderService'
import { notify } from '../../utils/notify'

/**
 * Sprint 47 — split one shipment across multiple warehouses. Operator
 * enters lines tagged with a per-line warehouseCode (or leaves it blank
 * to let the G3 selector pick), previews the split plan, then commits.
 *
 * Two-stage flow so the operator sees exactly how many labels will be
 * bought before any carrier is charged. Fail-all rollback lives in the
 * backend — either every label is bought or none are.
 */
export interface MultiWarehouseSplitModalProps {
  onClose: () => void
  initialClientCode?: string
  initialOrderNo?: number | null
  initialRecipient?: Partial<ManualShipmentAddress>
  initialLines?: MultiWarehouseLineItem[]
}

interface EditableLine extends MultiWarehouseLineItem {
  /** UI-only key — stable across re-renders when a line is removed above. */
  key: number
}

let lineKeySeq = 1
const nextKey = () => lineKeySeq++

function blankRecipient(seed: Partial<ManualShipmentAddress> = {}): ManualShipmentAddress {
  return {
    name: '',
    addressLine1: '',
    city: '',
    postalCode: '',
    countryCode: '',
    ...seed,
  }
}

function blankLine(): EditableLine {
  return { key: nextKey(), itemNo: '', description: '', quantity: 1 }
}

/** Client sends "" as null so backend validation reads the empty. */
function nullBlanks(payload: MultiWarehouseLabelPayload): MultiWarehouseLabelPayload {
  const cleanLines = payload.lines.map((l) => ({
    ...l,
    warehouseCode: l.warehouseCode?.trim() ? l.warehouseCode.trim() : null,
  }))
  return { ...payload, lines: cleanLines }
}

export default function MultiWarehouseSplitModal({
  onClose,
  initialClientCode,
  initialOrderNo,
  initialRecipient,
  initialLines,
}: MultiWarehouseSplitModalProps) {
  const [clientCode, setClientCode] = useState(initialClientCode ?? '')
  const [orderNo, setOrderNo] = useState<number | ''>(initialOrderNo ?? '')
  const [recipient, setRecipient] = useState<ManualShipmentAddress>(
    blankRecipient(initialRecipient),
  )
  const [lines, setLines] = useState<EditableLine[]>(
    initialLines?.length
      ? initialLines.map((l) => ({ ...l, key: nextKey() }))
      : [blankLine(), blankLine()],
  )

  const [warehouses, setWarehouses] = useState<ClientWarehouse[]>([])
  const [warehousesLoading, setWarehousesLoading] = useState(false)
  const [warehousesError, setWarehousesError] = useState<string | null>(null)

  const [preview, setPreview] = useState<MultiWarehousePreviewResponse | null>(null)
  const [previewing, setPreviewing] = useState(false)
  const [previewError, setPreviewError] = useState<string | null>(null)

  const [result, setResult] = useState<MultiWarehouseLabelResponse | null>(null)
  const [committing, setCommitting] = useState(false)
  const [commitError, setCommitError] = useState<string | null>(null)

  // ===== warehouse dropdown =====
  useEffect(() => {
    const code = clientCode.trim()
    if (!code) {
      setWarehouses([])
      setWarehousesError(null)
      return
    }
    let cancelled = false
    setWarehousesLoading(true)
    setWarehousesError(null)
    clientWarehouseService
      .listForClient(code)
      .then((resp) => {
        if (cancelled) return
        setWarehouses(resp.data ?? [])
      })
      .catch((err: unknown) => {
        if (cancelled) return
        const msg = err instanceof Error ? err.message : 'Failed to load warehouses.'
        setWarehousesError(msg)
        setWarehouses([])
      })
      .finally(() => {
        if (!cancelled) setWarehousesLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [clientCode])

  // Any edit invalidates the preview — the operator must re-run before commit.
  useEffect(() => {
    setPreview(null)
    setResult(null)
    setCommitError(null)
  }, [clientCode, orderNo, recipient, lines])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  const buildPayload = (): MultiWarehouseLabelPayload =>
    nullBlanks({
      clientCode: clientCode.trim(),
      orderNo: orderNo === '' ? null : Number(orderNo),
      recipient: {
        ...recipient,
        name: recipient.name?.trim() ?? '',
        addressLine1: recipient.addressLine1?.trim() ?? '',
        city: recipient.city?.trim() ?? '',
        postalCode: recipient.postalCode?.trim() ?? '',
        countryCode: recipient.countryCode?.trim().toUpperCase() ?? '',
      },
      lines: lines.map(({ key, ...rest }) => rest),
    })

  const localValidation = useMemo<string | null>(() => {
    if (!clientCode.trim()) return 'Client code is required.'
    if (lines.length === 0) return 'At least one line is required.'
    return null
  }, [clientCode, lines])

  const runPreview = async () => {
    if (previewing) return
    if (localValidation) {
      setPreviewError(localValidation)
      return
    }
    setPreviewing(true)
    setPreviewError(null)
    try {
      const resp = await multiWarehouseLabelService.preview(buildPayload())
      if (!resp.data) throw new Error(resp.message ?? 'Preview returned no data.')
      setPreview(resp.data)
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Preview failed.'
      setPreviewError(msg)
    } finally {
      setPreviewing(false)
    }
  }

  const runCommit = async () => {
    if (committing) return
    if (!preview) {
      setCommitError('Run the preview first so you can see what will ship.')
      return
    }
    if (preview.unassignedLineCount > 0) {
      setCommitError(
        `${preview.unassignedLineCount} line(s) have no assigned warehouse. Fill those in and re-preview before committing.`,
      )
      return
    }
    // Merge selector picks (AUTO lines) back into the payload so the write
    // endpoint sees an explicit warehouseCode on every line — it rejects
    // anything else.
    const explicitByIndex = new Map<number, string>()
    for (const l of preview.lines) {
      if (l.assignedWarehouseCode) explicitByIndex.set(l.lineIndex, l.assignedWarehouseCode)
    }
    const base = buildPayload()
    const withAssignments: MultiWarehouseLabelPayload = {
      ...base,
      lines: base.lines.map((line, i) => ({
        ...line,
        warehouseCode: explicitByIndex.get(i) ?? line.warehouseCode ?? null,
      })),
    }

    setCommitting(true)
    setCommitError(null)
    try {
      const resp = await multiWarehouseLabelService.generate(withAssignments)
      if (!resp.data) throw new Error(resp.message ?? 'Label generation returned no data.')
      setResult(resp.data)
      notify.success({
        title: 'Split labels generated',
        body: `${resp.data.shipmentCount} label${resp.data.shipmentCount === 1 ? '' : 's'} across ${resp.data.shipments.length} warehouse${resp.data.shipments.length === 1 ? '' : 's'}.`,
      })
    } catch (err: unknown) {
      // Backend fail-all — 422 CARRIER_FAILURE carries the offending
      // warehouse + detail in the message. Surface it verbatim.
      const msg =
        err instanceof ApiError
          ? err.payload?.message ?? err.message
          : err instanceof Error
            ? err.message
            : 'Label generation failed.'
      setCommitError(msg)
      notify.error({ title: 'Split aborted', body: msg })
    } finally {
      setCommitting(false)
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Split shipment across warehouses"
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/45 p-4"
      onClick={onClose}
    >
      <div
        className="flex h-[min(720px,92vh)] w-full max-w-[900px] flex-col overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        <ModalHeader onClose={onClose} />

        <div className="flex-1 space-y-4 overflow-y-auto px-5 py-4">
          <HeaderFields
            clientCode={clientCode}
            setClientCode={setClientCode}
            orderNo={orderNo}
            setOrderNo={setOrderNo}
          />
          <RecipientBlock recipient={recipient} setRecipient={setRecipient} />
          <LinesTable
            lines={lines}
            setLines={setLines}
            warehouses={warehouses}
            warehousesLoading={warehousesLoading}
            warehousesError={warehousesError}
            clientCode={clientCode.trim()}
          />

          {previewError ? <ErrorRow message={previewError} /> : null}

          {preview ? <PreviewPanel preview={preview} /> : null}

          {commitError ? <ErrorRow message={commitError} /> : null}

          {result ? <ResultPanel result={result} /> : null}
        </div>

        <div className="flex items-center justify-between gap-2 border-t border-slate-100 px-5 py-3">
          <span className="text-[11px] text-slate-500">
            {localValidation ?? 'Ready — preview to see the split.'}
          </span>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              className="inline-flex items-center rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-700 hover:bg-slate-50"
            >
              Close
            </button>
            <button
              type="button"
              onClick={() => void runPreview()}
              disabled={previewing || !!localValidation || !!result}
              className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-800 hover:bg-slate-50 disabled:opacity-40"
            >
              {previewing ? <FiLoader className="h-3 w-3 animate-spin" /> : <FiEye className="h-3 w-3" />}
              {previewing ? 'Previewing…' : 'Preview split'}
            </button>
            <button
              type="button"
              onClick={() => void runCommit()}
              disabled={
                committing ||
                !!localValidation ||
                !!result ||
                !preview ||
                preview.unassignedLineCount > 0
              }
              className="inline-flex items-center gap-1.5 rounded-lg bg-slate-950 px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-slate-800 disabled:opacity-40"
              title={
                preview && preview.unassignedLineCount > 0
                  ? 'Fill in the unassigned lines first.'
                  : preview
                    ? `Buy ${preview.shipmentCount} label${preview.shipmentCount === 1 ? '' : 's'}.`
                    : 'Run the preview first.'
              }
            >
              {committing ? <FiLoader className="h-3 w-3 animate-spin" /> : <FiZap className="h-3 w-3" />}
              {committing
                ? 'Buying…'
                : preview
                  ? `Buy ${preview.shipmentCount} label${preview.shipmentCount === 1 ? '' : 's'}`
                  : 'Buy labels'}
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}

// ===== sub-components =====

function ModalHeader({ onClose }: { onClose: () => void }) {
  return (
    <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
      <div>
        <p className="inline-flex items-center gap-1 text-[10.5px] font-bold uppercase tracking-[0.16em] text-slate-500">
          <FiTruck className="h-3 w-3" /> Split shipment
        </p>
        <h3 className="mt-1 text-[15px] font-semibold text-slate-950">
          Ship one order from multiple warehouses
        </h3>
        <p className="mt-1 text-[11.5px] text-slate-500">
          Assign a warehouse per line, preview the split, then buy every label at once. Either
          every label is bought or none are — the backend rolls back on any failure.
        </p>
      </div>
      <button
        type="button"
        onClick={onClose}
        aria-label="Close"
        className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 transition hover:bg-slate-50"
      >
        <FiX className="h-3.5 w-3.5" />
      </button>
    </div>
  )
}

function HeaderFields({
  clientCode,
  setClientCode,
  orderNo,
  setOrderNo,
}: {
  clientCode: string
  setClientCode: (v: string) => void
  orderNo: number | ''
  setOrderNo: (v: number | '') => void
}) {
  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
      <label className="flex flex-col gap-1 text-[11.5px] font-semibold text-slate-700">
        Client code *
        <input
          value={clientCode}
          onChange={(e) => setClientCode(e.target.value.toUpperCase())}
          placeholder="ACME"
          className="rounded-lg border border-slate-300 px-2.5 py-1.5 text-[12.5px] font-normal text-slate-950 placeholder:text-slate-400"
        />
      </label>
      <label className="flex flex-col gap-1 text-[11.5px] font-semibold text-slate-700">
        Parent order # <span className="font-normal text-slate-500">(optional)</span>
        <input
          type="number"
          value={orderNo}
          onChange={(e) => setOrderNo(e.target.value === '' ? '' : Number(e.target.value))}
          placeholder="1234"
          className="rounded-lg border border-slate-300 px-2.5 py-1.5 text-[12.5px] font-normal text-slate-950 placeholder:text-slate-400"
        />
      </label>
    </div>
  )
}

function RecipientBlock({
  recipient,
  setRecipient,
}: {
  recipient: ManualShipmentAddress
  setRecipient: (r: ManualShipmentAddress) => void
}) {
  const set = <K extends keyof ManualShipmentAddress>(key: K, value: ManualShipmentAddress[K]) =>
    setRecipient({ ...recipient, [key]: value })
  return (
    <fieldset className="rounded-xl border border-slate-200 bg-slate-50/40 p-3">
      <legend className="px-1 text-[11px] font-bold uppercase tracking-[0.14em] text-slate-500">
        Recipient
      </legend>
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        <RecipientField label="Name" value={recipient.name} onChange={(v) => set('name', v)} />
        <RecipientField
          label="Address line 1"
          value={recipient.addressLine1}
          onChange={(v) => set('addressLine1', v)}
        />
        <RecipientField label="City" value={recipient.city} onChange={(v) => set('city', v)} />
        <RecipientField
          label="State / region"
          value={recipient.state ?? ''}
          onChange={(v) => set('state', v)}
        />
        <RecipientField
          label="Postal code"
          value={recipient.postalCode}
          onChange={(v) => set('postalCode', v)}
        />
        <RecipientField
          label="Country (ISO-2)"
          value={recipient.countryCode}
          onChange={(v) => set('countryCode', v.toUpperCase())}
          maxLength={2}
        />
      </div>
      <p className="mt-2 text-[10.5px] text-slate-500">
        The G3 selector uses country + postal to pick a nearest warehouse for lines you leave
        without one. Providing both improves auto-assignment accuracy.
      </p>
    </fieldset>
  )
}

function RecipientField({
  label,
  value,
  onChange,
  maxLength,
}: {
  label: string
  value: string
  onChange: (v: string) => void
  maxLength?: number
}) {
  return (
    <label className="flex flex-col gap-1 text-[11px] font-semibold text-slate-700">
      {label}
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        maxLength={maxLength}
        className="rounded-lg border border-slate-300 px-2.5 py-1.5 text-[12.5px] font-normal text-slate-950"
      />
    </label>
  )
}

function LinesTable({
  lines,
  setLines,
  warehouses,
  warehousesLoading,
  warehousesError,
  clientCode,
}: {
  lines: EditableLine[]
  setLines: (l: EditableLine[]) => void
  warehouses: ClientWarehouse[]
  warehousesLoading: boolean
  warehousesError: string | null
  clientCode: string
}) {
  const options = warehouses
    .map((cw) => cw.warehouse)
    .filter((w): w is NonNullable<typeof w> => w != null)

  const patch = (key: number, patchObj: Partial<EditableLine>) =>
    setLines(lines.map((l) => (l.key === key ? { ...l, ...patchObj } : l)))

  const removeLine = (key: number) => setLines(lines.filter((l) => l.key !== key))
  const addLine = () => setLines([...lines, blankLine()])

  return (
    <fieldset className="rounded-xl border border-slate-200 p-3">
      <legend className="px-1 text-[11px] font-bold uppercase tracking-[0.14em] text-slate-500">
        Lines
      </legend>
      {warehousesError ? (
        <p className="mb-2 text-[11px] text-rose-700">
          <FiAlertCircle className="mr-1 inline h-3 w-3" />
          {warehousesError}
        </p>
      ) : null}
      <div className="overflow-x-auto">
        <table className="w-full text-[12px]">
          <thead>
            <tr className="text-left text-[10.5px] font-bold uppercase tracking-wide text-slate-500">
              <th className="pb-1.5 pr-2">Item</th>
              <th className="pb-1.5 pr-2">Description</th>
              <th className="pb-1.5 pr-2 text-right">Qty</th>
              <th className="pb-1.5 pr-2 text-right">Weight</th>
              <th className="pb-1.5 pr-2">Warehouse</th>
              <th className="pb-1.5" />
            </tr>
          </thead>
          <tbody>
            {lines.map((line) => (
              <tr key={line.key} className="border-t border-slate-100">
                <td className="py-1.5 pr-2">
                  <input
                    value={line.itemNo ?? ''}
                    onChange={(e) => patch(line.key, { itemNo: e.target.value })}
                    className="w-24 rounded-md border border-slate-300 px-2 py-1 text-[12px]"
                  />
                </td>
                <td className="py-1.5 pr-2">
                  <input
                    value={line.description ?? ''}
                    onChange={(e) => patch(line.key, { description: e.target.value })}
                    className="w-full rounded-md border border-slate-300 px-2 py-1 text-[12px]"
                  />
                </td>
                <td className="py-1.5 pr-2">
                  <input
                    type="number"
                    min={1}
                    value={line.quantity ?? ''}
                    onChange={(e) =>
                      patch(line.key, {
                        quantity: e.target.value === '' ? null : Number(e.target.value),
                      })
                    }
                    className="w-16 rounded-md border border-slate-300 px-2 py-1 text-right text-[12px]"
                  />
                </td>
                <td className="py-1.5 pr-2">
                  <input
                    type="number"
                    step={0.1}
                    min={0}
                    value={line.weight ?? ''}
                    onChange={(e) =>
                      patch(line.key, {
                        weight: e.target.value === '' ? null : Number(e.target.value),
                      })
                    }
                    className="w-20 rounded-md border border-slate-300 px-2 py-1 text-right text-[12px]"
                  />
                </td>
                <td className="py-1.5 pr-2">
                  <select
                    value={line.warehouseCode ?? ''}
                    onChange={(e) =>
                      patch(line.key, { warehouseCode: e.target.value || null })
                    }
                    disabled={!clientCode || warehousesLoading}
                    className="min-w-[8rem] rounded-md border border-slate-300 bg-white px-2 py-1 text-[12px] disabled:bg-slate-100"
                  >
                    <option value="">— Auto (selector)</option>
                    {options.map((w) => (
                      <option key={w.id} value={w.code}>
                        {w.code} — {w.name}
                      </option>
                    ))}
                  </select>
                </td>
                <td className="py-1.5">
                  <button
                    type="button"
                    onClick={() => removeLine(line.key)}
                    disabled={lines.length === 1}
                    aria-label="Remove line"
                    className="inline-flex h-7 w-7 items-center justify-center rounded-md border border-slate-200 text-slate-500 hover:bg-slate-50 disabled:opacity-30"
                  >
                    <FiTrash2 className="h-3 w-3" />
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <div className="mt-2 flex items-center justify-between">
        <button
          type="button"
          onClick={addLine}
          className="inline-flex items-center gap-1 rounded-md border border-slate-300 bg-white px-2 py-1 text-[11.5px] font-semibold text-slate-800 hover:bg-slate-50"
        >
          <FiPlus className="h-3 w-3" />
          Add line
        </button>
        <span className="text-[10.5px] text-slate-500">
          {clientCode
            ? `${options.length} warehouse${options.length === 1 ? '' : 's'} attached to ${clientCode}.`
            : 'Enter a client code to load its warehouses.'}
        </span>
      </div>
    </fieldset>
  )
}

function PreviewPanel({ preview }: { preview: MultiWarehousePreviewResponse }) {
  const hasUnassigned = preview.unassignedLineCount > 0
  return (
    <div
      className={`rounded-xl border p-3 ${
        hasUnassigned ? 'border-amber-300 bg-amber-50/60' : 'border-emerald-200 bg-emerald-50/50'
      }`}
    >
      <div className="flex items-center justify-between gap-2">
        <p className="inline-flex items-center gap-1.5 text-[12px] font-semibold text-slate-800">
          {hasUnassigned ? (
            <FiAlertCircle className="h-3.5 w-3.5 text-amber-700" />
          ) : (
            <FiCheckCircle className="h-3.5 w-3.5 text-emerald-700" />
          )}
          {hasUnassigned
            ? `${preview.unassignedLineCount} line${preview.unassignedLineCount === 1 ? '' : 's'} could not be auto-assigned`
            : `Split will generate ${preview.shipmentCount} shipment${preview.shipmentCount === 1 ? '' : 's'}`}
        </p>
        <span className="text-[10.5px] text-slate-600">
          {preview.totalLines} line{preview.totalLines === 1 ? '' : 's'} total
        </span>
      </div>

      {preview.groups.length > 0 ? (
        <div className="mt-2 space-y-1">
          {preview.groups.map((g, i) => (
            <div
              key={g.warehouseCode ?? `unassigned-${i}`}
              className="flex items-center justify-between rounded-md border border-slate-200 bg-white px-2 py-1 text-[11.5px]"
            >
              <span className="font-semibold text-slate-800">
                {g.warehouseCode ?? 'Unassigned'}
                {g.warehouseName ? (
                  <span className="ml-1 font-normal text-slate-500">— {g.warehouseName}</span>
                ) : null}
              </span>
              <span className="rounded-full bg-slate-100 px-2 py-0.5 font-semibold text-slate-700">
                {g.lineCount} line{g.lineCount === 1 ? '' : 's'}
              </span>
            </div>
          ))}
        </div>
      ) : null}

      <details className="mt-2">
        <summary className="cursor-pointer text-[11px] font-semibold text-slate-600 hover:text-slate-800">
          Per-line trace ({preview.lines.length})
        </summary>
        <div className="mt-1 max-h-40 overflow-y-auto rounded-md border border-slate-200 bg-white">
          <table className="w-full text-[11px]">
            <thead>
              <tr className="border-b border-slate-100 text-left text-[10px] font-bold uppercase tracking-wide text-slate-500">
                <th className="px-2 py-1">#</th>
                <th className="px-2 py-1">Item</th>
                <th className="px-2 py-1">Warehouse</th>
                <th className="px-2 py-1">Source</th>
                <th className="px-2 py-1">Reason</th>
              </tr>
            </thead>
            <tbody>
              {preview.lines.map((l) => (
                <tr key={l.lineIndex} className="border-t border-slate-100">
                  <td className="px-2 py-0.5 text-slate-500">{l.lineIndex + 1}</td>
                  <td className="px-2 py-0.5">{l.itemNo ?? '—'}</td>
                  <td className="px-2 py-0.5 font-semibold">{l.assignedWarehouseCode ?? '—'}</td>
                  <td className="px-2 py-0.5">
                    <SourceBadge source={l.source} />
                  </td>
                  <td className="px-2 py-0.5 text-slate-500">{l.matchReason ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </details>
    </div>
  )
}

function SourceBadge({ source }: { source: string }) {
  const styles: Record<string, string> = {
    EXPLICIT: 'bg-slate-100 text-slate-700',
    AUTO: 'bg-sky-100 text-sky-800',
    NONE: 'bg-rose-100 text-rose-800',
  }
  const cls = styles[source] ?? 'bg-slate-100 text-slate-700'
  return (
    <span className={`inline-flex rounded-full px-1.5 py-0.5 text-[10px] font-semibold ${cls}`}>
      {source}
    </span>
  )
}

function ResultPanel({ result }: { result: MultiWarehouseLabelResponse }) {
  return (
    <div className="rounded-xl border border-emerald-300 bg-emerald-50 p-3">
      <p className="inline-flex items-center gap-1.5 text-[12px] font-semibold text-emerald-900">
        <FiPackage className="h-3.5 w-3.5" /> Group #{result.groupId} — {result.shipmentCount} label
        {result.shipmentCount === 1 ? '' : 's'} generated
      </p>
      <div className="mt-2 space-y-1">
        {result.shipments.map((s) => (
          <div
            key={s.shipmentId ?? s.trackingNumber ?? s.warehouseCode}
            className="flex items-center justify-between gap-2 rounded-md border border-emerald-200 bg-white px-2 py-1 text-[11.5px]"
          >
            <span className="font-semibold text-slate-800">
              {s.warehouseCode}
              {s.carrierCode ? (
                <span className="ml-1 font-normal text-slate-500">{s.carrierCode}</span>
              ) : null}
            </span>
            <span className="font-mono text-slate-700">{s.trackingNumber ?? '—'}</span>
            {s.labelUrl ? (
              <a
                href={s.labelUrl}
                target="_blank"
                rel="noreferrer"
                className="text-[11px] font-semibold text-sky-700 hover:underline"
              >
                Label
              </a>
            ) : (
              <span className="text-[10.5px] text-slate-400">no url</span>
            )}
          </div>
        ))}
      </div>
    </div>
  )
}

function ErrorRow({ message }: { message: string }) {
  return (
    <div className="rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-[12px] text-rose-800">
      <FiAlertCircle className="mr-1.5 inline h-3.5 w-3.5" />
      {message}
    </div>
  )
}
