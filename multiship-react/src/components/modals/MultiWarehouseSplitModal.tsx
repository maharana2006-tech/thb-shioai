import { useEffect, useMemo, useRef, useState } from 'react'
import { useFocusTrap } from '../../hooks/useFocusTrap'
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
import {
  FIELD_LIMITS,
  hasErrors,
  validateCode,
  validateCountry,
  validateLength,
  validateZip,
} from '../../utils/clientValidation'

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

/** Sanity caps for line fields — aligned with backend column sizes. */
const ITEM_NO_MAX = 50
const DESCRIPTION_MAX = 255
const QTY_MAX = 9999
const WEIGHT_MAX = 9999

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

/** Shared input class factory — rose border when the field carries an error. */
function fieldCls(base: string, bad: boolean): string {
  return `${base} ${
    bad
      ? 'border-rose-400 focus:border-rose-500 focus:ring-rose-400'
      : 'border-[#cdbf9f] focus:border-[#412d15] focus:ring-[#412d15]'
  } outline-none focus:ring-1`
}

export default function MultiWarehouseSplitModal({
  onClose,
  initialClientCode,
  initialOrderNo,
  initialRecipient,
  initialLines,
}: MultiWarehouseSplitModalProps) {
  // Sprint 51 T6b — focus trap.
  const dialogRef = useRef<HTMLDivElement>(null)
  useFocusTrap(true, dialogRef)
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
  /** Set when the warehouse lookup 404s — the code is well-formed but no such
   *  client exists. Rendered inline under the Client code field. */
  const [clientNotFound, setClientNotFound] = useState<string | null>(null)

  const [preview, setPreview] = useState<MultiWarehousePreviewResponse | null>(null)
  const [previewing, setPreviewing] = useState(false)
  const [previewError, setPreviewError] = useState<string | null>(null)

  const [result, setResult] = useState<MultiWarehouseLabelResponse | null>(null)
  const [committing, setCommitting] = useState(false)
  const [commitError, setCommitError] = useState<string | null>(null)

  // ===== field validation (touched-gated, inline under each input) =====
  const [touched, setTouched] = useState<Record<string, boolean>>({})
  const [showAll, setShowAll] = useState(false)
  const touch = (k: string) => setTouched((t) => (t[k] ? t : { ...t, [k]: true }))

  /**
   * Flat error map — header fields ('clientCode', 'orderNo'), recipient
   * fields ('r.name' …), and per-line fields ('l.<key>.<field>'). Same
   * validator set as the client wizard so limits stay consistent.
   */
  const errors = useMemo(() => {
    const e: Record<string, string> = {}

    const codeErr = validateCode(clientCode, 'Client code')
    if (codeErr) {
      e.clientCode = codeErr
    } else if (clientNotFound) {
      // Shape is fine but the live lookup 404'd — no such client registered.
      e.clientCode = clientNotFound
    }

    if (orderNo !== '') {
      if (!Number.isInteger(orderNo) || orderNo < 1) {
        e.orderNo = 'Order # must be a whole number of 1 or more.'
      } else if (orderNo > 2_147_483_647) {
        e.orderNo = 'Order # is too large.'
      }
    }

    const nameErr = validateLength(recipient.name || '', FIELD_LIMITS.addr.name, 'Name', true, 2)
    if (nameErr) e['r.name'] = nameErr
    const line1Err = validateLength(recipient.addressLine1 || '', FIELD_LIMITS.addr.line1, 'Street address', true, 2)
    if (line1Err) e['r.addressLine1'] = line1Err
    const cityErr = validateLength(recipient.city || '', FIELD_LIMITS.addr.city, 'City', true, 2)
    if (cityErr) e['r.city'] = cityErr
    if (recipient.state) {
      const stateErr = validateLength(recipient.state, FIELD_LIMITS.addr.state, 'State / region', false)
      if (stateErr) e['r.state'] = stateErr
    }
    const countryErr = validateCountry(recipient.countryCode || '', true)
    if (countryErr) e['r.countryCode'] = countryErr
    const zipErr = validateZip(recipient.postalCode || '', recipient.countryCode || '', true)
    if (zipErr) e['r.postalCode'] = zipErr

    if (lines.length === 0) {
      e.lines = 'At least one line is required.'
    }
    const knownCodes = new Set(
      warehouses.map((cw) => cw.warehouse?.code).filter((c): c is string => !!c),
    )
    for (const line of lines) {
      const p = `l.${line.key}.`
      const itemErr = validateLength(line.itemNo || '', ITEM_NO_MAX, 'Item', true, 1)
      if (itemErr) e[p + 'itemNo'] = itemErr
      if (line.description) {
        const descErr = validateLength(line.description, DESCRIPTION_MAX, 'Description', false)
        if (descErr) e[p + 'description'] = descErr
      }
      if (line.quantity == null) {
        e[p + 'quantity'] = 'Qty is required.'
      } else if (!Number.isInteger(line.quantity) || line.quantity < 1) {
        e[p + 'quantity'] = 'Qty must be a whole number of 1 or more.'
      } else if (line.quantity > QTY_MAX) {
        e[p + 'quantity'] = `Qty must be ${QTY_MAX} or fewer.`
      }
      if (line.weight != null) {
        if (!Number.isFinite(line.weight) || line.weight <= 0) {
          e[p + 'weight'] = 'Weight must be greater than 0.'
        } else if (line.weight > WEIGHT_MAX) {
          e[p + 'weight'] = `Weight must be ${WEIGHT_MAX} or less.`
        }
      }
      // A stale explicit code (e.g. client changed after picking) is invisible
      // in the dropdown — surface it instead of letting the backend reject.
      if (line.warehouseCode && knownCodes.size > 0 && !knownCodes.has(line.warehouseCode)) {
        e[p + 'warehouseCode'] = `Warehouse ${line.warehouseCode} is not attached to this client.`
      }
    }
    return e
  }, [clientCode, orderNo, recipient, lines, warehouses, clientNotFound])

  const err = (k: string): string | null => ((showAll || touched[k]) ? (errors[k] ?? null) : null)

  // ===== warehouse dropdown + live client-existence check =====
  // Debounced so typing "ACME" fires one request, not four. A 404 from the
  // lookup doubles as the existence check — surfaced under the Client code
  // field instead of the warehouse list.
  useEffect(() => {
    const code = clientCode.trim()
    setClientNotFound(null)
    if (!code) {
      setWarehouses([])
      setWarehousesError(null)
      return
    }
    let cancelled = false
    const timer = setTimeout(() => {
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
          setWarehouses([])
          if (err instanceof ApiError && err.status === 404) {
            setClientNotFound(`Client ${code} is not registered.`)
          } else {
            const msg = err instanceof Error ? err.message : 'Failed to load warehouses.'
            setWarehousesError(msg)
          }
        })
        .finally(() => {
          if (!cancelled) setWarehousesLoading(false)
        })
    }, 350)
    return () => {
      cancelled = true
      clearTimeout(timer)
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

  /** Reveal all inline errors and scroll the first one into view. */
  const revealErrors = () => {
    setShowAll(true)
    requestAnimationFrame(() => {
      document
        .querySelector('[aria-label="Split shipment across warehouses"] [data-field-error]')
        ?.scrollIntoView({ block: 'center', behavior: 'smooth' })
    })
  }

  const runPreview = async () => {
    if (previewing) return
    if (hasErrors(errors)) {
      revealErrors()
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
    if (hasErrors(errors)) {
      revealErrors()
      return
    }
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
      // warehouse + detail in the message. Surface it inline, no popup.
      const msg =
        err instanceof ApiError
          ? err.payload?.message ?? err.message
          : err instanceof Error
            ? err.message
            : 'Label generation failed.'
      setCommitError(msg)
    } finally {
      setCommitting(false)
    }
  }

  const errorCount = Object.keys(errors).length

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Split shipment across warehouses"
      className="fixed inset-0 z-50 flex items-center justify-center bg-[#1f150c]/45 p-4"
      onClick={onClose}
    >
      <div
        ref={dialogRef}
        className="flex h-[min(720px,92vh)] w-full max-w-[900px] flex-col overflow-hidden rounded-2xl border border-[#e3d9c4] bg-white shadow-[0_30px_80px_rgba(31,21,12,0.35)]"
        onClick={(e) => e.stopPropagation()}
      >
        <ModalHeader onClose={onClose} />

        <div className="flex-1 space-y-4 overflow-y-auto px-5 py-4">
          <HeaderFields
            clientCode={clientCode}
            setClientCode={setClientCode}
            orderNo={orderNo}
            setOrderNo={setOrderNo}
            err={err}
            touch={touch}
          />
          <RecipientBlock recipient={recipient} setRecipient={setRecipient} err={err} touch={touch} />
          <LinesTable
            lines={lines}
            setLines={setLines}
            warehouses={warehouses}
            warehousesLoading={warehousesLoading}
            warehousesError={warehousesError}
            clientCode={clientCode.trim()}
            err={err}
            touch={touch}
          />

          {previewError ? <ErrorRow message={previewError} /> : null}

          {preview ? <PreviewPanel preview={preview} /> : null}

          {commitError ? <ErrorRow message={commitError} /> : null}

          {result ? <ResultPanel result={result} /> : null}
        </div>

        <div className="flex items-center justify-between gap-2 border-t border-[#eee6d6] px-5 py-3">
          <span className={`text-[11px] ${showAll && errorCount > 0 ? 'font-semibold text-rose-600' : 'text-[#8a7959]'}`}>
            {showAll && errorCount > 0
              ? `${errorCount} field${errorCount === 1 ? '' : 's'} need${errorCount === 1 ? 's' : ''} attention.`
              : 'Ready — preview to see the split.'}
          </span>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={onClose}
              className="inline-flex items-center rounded-lg border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#412d15] hover:bg-[#faf7f0]"
            >
              Close
            </button>
            <button
              type="button"
              onClick={() => void runPreview()}
              disabled={previewing || !!result}
              className="inline-flex items-center gap-1.5 rounded-lg border border-[#cdbf9f] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#412d15] hover:bg-[#faf7f0] disabled:opacity-40"
            >
              {previewing ? <FiLoader className="h-3 w-3 animate-spin" /> : <FiEye className="h-3 w-3" />}
              {previewing ? 'Previewing…' : 'Preview split'}
            </button>
            <button
              type="button"
              onClick={() => void runCommit()}
              disabled={
                committing ||
                !!result ||
                !preview ||
                preview.unassignedLineCount > 0
              }
              className="inline-flex items-center gap-1.5 rounded-lg bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-[#f4eede] transition hover:bg-[#412d15] disabled:opacity-40"
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

/** Inline error line rendered under an input — the app-wide convention. */
function FieldError({ message }: { message: string | null }) {
  if (!message) return null
  return (
    <p data-field-error className="mt-0.5 text-[10.5px] font-normal leading-snug text-rose-600">
      {message}
    </p>
  )
}

function ModalHeader({ onClose }: { onClose: () => void }) {
  return (
    <div className="flex items-start justify-between gap-3 border-b border-[#eee6d6] px-5 py-4">
      <div>
        <p className="inline-flex items-center gap-1 text-[10.5px] font-bold uppercase tracking-[0.16em] text-[#8a7959]">
          <FiTruck className="h-3 w-3" /> Split shipment
        </p>
        <h3 className="mt-1 text-[15px] font-semibold text-[#1f150c]">
          Ship one order from multiple warehouses
        </h3>
        <p className="mt-1 text-[11.5px] text-[#8a7959]">
          Assign a warehouse per line, preview the split, then buy every label at once. Either
          every label is bought or none are — the backend rolls back on any failure.
        </p>
      </div>
      <button
        type="button"
        onClick={onClose}
        aria-label="Close"
        className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-[#e3d9c4] bg-white text-[#8a7959] transition hover:bg-[#faf7f0]"
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
  err,
  touch,
}: {
  clientCode: string
  setClientCode: (v: string) => void
  orderNo: number | ''
  setOrderNo: (v: number | '') => void
  err: (k: string) => string | null
  touch: (k: string) => void
}) {
  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
      <label className="flex flex-col gap-1 text-[11.5px] font-semibold text-[#412d15]">
        Client code *
        <input
          value={clientCode}
          onChange={(e) => setClientCode(e.target.value.toUpperCase())}
          onBlur={() => touch('clientCode')}
          placeholder="ACME"
          maxLength={FIELD_LIMITS.clientCode}
          className={fieldCls(
            'rounded-lg border px-2.5 py-1.5 text-[12.5px] font-normal text-[#1f150c] placeholder:text-[#b6a684]',
            err('clientCode') != null,
          )}
        />
        <FieldError message={err('clientCode')} />
      </label>
      <label className="flex flex-col gap-1 text-[11.5px] font-semibold text-[#412d15]">
        Parent order # <span className="font-normal text-[#8a7959]">(optional)</span>
        <input
          type="number"
          min={1}
          step={1}
          value={orderNo}
          onChange={(e) => setOrderNo(e.target.value === '' ? '' : Number(e.target.value))}
          onBlur={() => touch('orderNo')}
          placeholder="1234"
          className={fieldCls(
            'rounded-lg border px-2.5 py-1.5 text-[12.5px] font-normal text-[#1f150c] placeholder:text-[#b6a684]',
            err('orderNo') != null,
          )}
        />
        <FieldError message={err('orderNo')} />
      </label>
    </div>
  )
}

function RecipientBlock({
  recipient,
  setRecipient,
  err,
  touch,
}: {
  recipient: ManualShipmentAddress
  setRecipient: (r: ManualShipmentAddress) => void
  err: (k: string) => string | null
  touch: (k: string) => void
}) {
  const set = <K extends keyof ManualShipmentAddress>(key: K, value: ManualShipmentAddress[K]) =>
    setRecipient({ ...recipient, [key]: value })
  return (
    <fieldset className="rounded-xl border border-[#e3d9c4] bg-[#faf7f0]/40 p-3">
      <legend className="px-1 text-[11px] font-bold uppercase tracking-[0.14em] text-[#8a7959]">
        Recipient
      </legend>
      <div className="grid grid-cols-1 gap-2 sm:grid-cols-2">
        <RecipientField
          label="Name" required
          value={recipient.name}
          onChange={(v) => set('name', v)}
          onBlur={() => touch('r.name')}
          error={err('r.name')}
          maxLength={FIELD_LIMITS.addr.name}
        />
        <RecipientField
          label="Address line 1" required
          value={recipient.addressLine1}
          onChange={(v) => set('addressLine1', v)}
          onBlur={() => touch('r.addressLine1')}
          error={err('r.addressLine1')}
          maxLength={FIELD_LIMITS.addr.line1}
        />
        <RecipientField
          label="City" required
          value={recipient.city}
          onChange={(v) => set('city', v)}
          onBlur={() => touch('r.city')}
          error={err('r.city')}
          maxLength={FIELD_LIMITS.addr.city}
        />
        <RecipientField
          label="State / region"
          value={recipient.state ?? ''}
          onChange={(v) => set('state', v)}
          onBlur={() => touch('r.state')}
          error={err('r.state')}
          maxLength={FIELD_LIMITS.addr.state}
        />
        <RecipientField
          label="Postal code" required
          value={recipient.postalCode}
          onChange={(v) => set('postalCode', v)}
          onBlur={() => touch('r.postalCode')}
          error={err('r.postalCode')}
          maxLength={FIELD_LIMITS.addr.zip}
        />
        <RecipientField
          label="Country (ISO-2)" required
          value={recipient.countryCode}
          onChange={(v) => set('countryCode', v.toUpperCase())}
          onBlur={() => touch('r.countryCode')}
          error={err('r.countryCode')}
          maxLength={2}
        />
      </div>
      <p className="mt-2 text-[10.5px] text-[#8a7959]">
        The G3 selector uses country + postal to pick a nearest warehouse for lines you leave
        without one. Providing both improves auto-assignment accuracy.
      </p>
    </fieldset>
  )
}

function RecipientField({
  label,
  required,
  value,
  onChange,
  onBlur,
  error,
  maxLength,
}: {
  label: string
  required?: boolean
  value: string
  onChange: (v: string) => void
  onBlur?: () => void
  error?: string | null
  maxLength?: number
}) {
  return (
    <label className="flex flex-col gap-1 text-[11px] font-semibold text-[#412d15]">
      <span>
        {label} {required ? <span className="text-rose-500">*</span> : null}
      </span>
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        maxLength={maxLength}
        className={fieldCls(
          'rounded-lg border px-2.5 py-1.5 text-[12.5px] font-normal text-[#1f150c]',
          error != null,
        )}
      />
      <FieldError message={error ?? null} />
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
  err,
  touch,
}: {
  lines: EditableLine[]
  setLines: (l: EditableLine[]) => void
  warehouses: ClientWarehouse[]
  warehousesLoading: boolean
  warehousesError: string | null
  clientCode: string
  err: (k: string) => string | null
  touch: (k: string) => void
}) {
  const options = warehouses
    .map((cw) => cw.warehouse)
    .filter((w): w is NonNullable<typeof w> => w != null)

  const patch = (key: number, patchObj: Partial<EditableLine>) =>
    setLines(lines.map((l) => (l.key === key ? { ...l, ...patchObj } : l)))

  const removeLine = (key: number) => setLines(lines.filter((l) => l.key !== key))
  const addLine = () => setLines([...lines, blankLine()])

  const cellCls = (bad: boolean, extra = '') =>
    fieldCls(`rounded-md border px-2 py-1 text-[12px] ${extra}`, bad)

  return (
    <fieldset className="rounded-xl border border-[#e3d9c4] p-3">
      <legend className="px-1 text-[11px] font-bold uppercase tracking-[0.14em] text-[#8a7959]">
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
            <tr className="text-left text-[10.5px] font-bold uppercase tracking-wide text-[#8a7959]">
              <th className="pb-1.5 pr-2">Item *</th>
              <th className="pb-1.5 pr-2">Description</th>
              <th className="pb-1.5 pr-2 text-right">Qty *</th>
              <th className="pb-1.5 pr-2 text-right">Weight</th>
              <th className="pb-1.5 pr-2">Warehouse</th>
              <th className="pb-1.5" />
            </tr>
          </thead>
          <tbody>
            {lines.map((line) => {
              const p = `l.${line.key}.`
              return (
                <tr key={line.key} className="border-t border-[#eee6d6] align-top">
                  <td className="py-1.5 pr-2">
                    <input
                      value={line.itemNo ?? ''}
                      onChange={(e) => patch(line.key, { itemNo: e.target.value })}
                      onBlur={() => touch(p + 'itemNo')}
                      maxLength={ITEM_NO_MAX}
                      className={cellCls(err(p + 'itemNo') != null, 'w-24')}
                    />
                    <FieldError message={err(p + 'itemNo')} />
                  </td>
                  <td className="py-1.5 pr-2">
                    <input
                      value={line.description ?? ''}
                      onChange={(e) => patch(line.key, { description: e.target.value })}
                      onBlur={() => touch(p + 'description')}
                      maxLength={DESCRIPTION_MAX}
                      className={cellCls(err(p + 'description') != null, 'w-full')}
                    />
                    <FieldError message={err(p + 'description')} />
                  </td>
                  <td className="py-1.5 pr-2">
                    <input
                      type="number"
                      min={1}
                      max={QTY_MAX}
                      step={1}
                      value={line.quantity ?? ''}
                      onChange={(e) =>
                        patch(line.key, {
                          quantity: e.target.value === '' ? null : Number(e.target.value),
                        })
                      }
                      onBlur={() => touch(p + 'quantity')}
                      className={cellCls(err(p + 'quantity') != null, 'w-16 text-right')}
                    />
                    <FieldError message={err(p + 'quantity')} />
                  </td>
                  <td className="py-1.5 pr-2">
                    <input
                      type="number"
                      step={0.1}
                      min={0}
                      max={WEIGHT_MAX}
                      value={line.weight ?? ''}
                      onChange={(e) =>
                        patch(line.key, {
                          weight: e.target.value === '' ? null : Number(e.target.value),
                        })
                      }
                      onBlur={() => touch(p + 'weight')}
                      className={cellCls(err(p + 'weight') != null, 'w-20 text-right')}
                    />
                    <FieldError message={err(p + 'weight')} />
                  </td>
                  <td className="py-1.5 pr-2">
                    <select
                      value={line.warehouseCode ?? ''}
                      onChange={(e) =>
                        patch(line.key, { warehouseCode: e.target.value || null })
                      }
                      onBlur={() => touch(p + 'warehouseCode')}
                      disabled={!clientCode || warehousesLoading}
                      className={cellCls(
                        err(p + 'warehouseCode') != null,
                        'min-w-[8rem] bg-white disabled:bg-[#eee6d6]',
                      )}
                    >
                      <option value="">— Auto (selector)</option>
                      {options.map((w) => (
                        <option key={w.id} value={w.code}>
                          {w.code} — {w.name}
                        </option>
                      ))}
                    </select>
                    <FieldError message={err(p + 'warehouseCode')} />
                  </td>
                  <td className="py-1.5">
                    <button
                      type="button"
                      onClick={() => removeLine(line.key)}
                      disabled={lines.length === 1}
                      aria-label="Remove line"
                      className="inline-flex h-7 w-7 items-center justify-center rounded-md border border-[#e3d9c4] text-[#8a7959] hover:bg-[#faf7f0] disabled:opacity-30"
                    >
                      <FiTrash2 className="h-3 w-3" />
                    </button>
                  </td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
      <FieldError message={err('lines')} />
      <div className="mt-2 flex items-center justify-between">
        <button
          type="button"
          onClick={addLine}
          className="inline-flex items-center gap-1 rounded-md border border-[#cdbf9f] bg-white px-2 py-1 text-[11.5px] font-semibold text-[#412d15] hover:bg-[#faf7f0]"
        >
          <FiPlus className="h-3 w-3" />
          Add line
        </button>
        <span className="text-[10.5px] text-[#8a7959]">
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
        <p className="inline-flex items-center gap-1.5 text-[12px] font-semibold text-[#412d15]">
          {hasUnassigned ? (
            <FiAlertCircle className="h-3.5 w-3.5 text-amber-700" />
          ) : (
            <FiCheckCircle className="h-3.5 w-3.5 text-emerald-700" />
          )}
          {hasUnassigned
            ? `${preview.unassignedLineCount} line${preview.unassignedLineCount === 1 ? '' : 's'} could not be auto-assigned`
            : `Split will generate ${preview.shipmentCount} shipment${preview.shipmentCount === 1 ? '' : 's'}`}
        </p>
        <span className="text-[10.5px] text-[#5a4526]">
          {preview.totalLines} line{preview.totalLines === 1 ? '' : 's'} total
        </span>
      </div>

      {preview.groups.length > 0 ? (
        <div className="mt-2 space-y-1">
          {preview.groups.map((g, i) => (
            <div
              key={g.warehouseCode ?? `unassigned-${i}`}
              className="flex items-center justify-between rounded-md border border-[#e3d9c4] bg-white px-2 py-1 text-[11.5px]"
            >
              <span className="font-semibold text-[#412d15]">
                {g.warehouseCode ?? 'Unassigned'}
                {g.warehouseName ? (
                  <span className="ml-1 font-normal text-[#8a7959]">— {g.warehouseName}</span>
                ) : null}
              </span>
              <span className="rounded-full bg-[#eee6d6] px-2 py-0.5 font-semibold text-[#412d15]">
                {g.lineCount} line{g.lineCount === 1 ? '' : 's'}
              </span>
            </div>
          ))}
        </div>
      ) : null}

      <details className="mt-2">
        <summary className="cursor-pointer text-[11px] font-semibold text-[#5a4526] hover:text-[#412d15]">
          Per-line trace ({preview.lines.length})
        </summary>
        <div className="mt-1 max-h-40 overflow-y-auto rounded-md border border-[#e3d9c4] bg-white">
          <table className="w-full text-[11px]">
            <thead>
              <tr className="border-b border-[#eee6d6] text-left text-[10px] font-bold uppercase tracking-wide text-[#8a7959]">
                <th className="px-2 py-1">#</th>
                <th className="px-2 py-1">Item</th>
                <th className="px-2 py-1">Warehouse</th>
                <th className="px-2 py-1">Source</th>
                <th className="px-2 py-1">Reason</th>
              </tr>
            </thead>
            <tbody>
              {preview.lines.map((l) => (
                <tr key={l.lineIndex} className="border-t border-[#eee6d6]">
                  <td className="px-2 py-0.5 text-[#8a7959]">{l.lineIndex + 1}</td>
                  <td className="px-2 py-0.5">{l.itemNo ?? '—'}</td>
                  <td className="px-2 py-0.5 font-semibold">{l.assignedWarehouseCode ?? '—'}</td>
                  <td className="px-2 py-0.5">
                    <SourceBadge source={l.source} />
                  </td>
                  <td className="px-2 py-0.5 text-[#8a7959]">{l.matchReason ?? '—'}</td>
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
    EXPLICIT: 'bg-[#eee6d6] text-[#412d15]',
    AUTO: 'bg-[#e3d9c4] text-[#1f150c]',
    NONE: 'bg-rose-100 text-rose-800',
  }
  const cls = styles[source] ?? 'bg-[#eee6d6] text-[#412d15]'
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
            <span className="font-semibold text-[#412d15]">
              {s.warehouseCode}
              {s.carrierCode ? (
                <span className="ml-1 font-normal text-[#8a7959]">{s.carrierCode}</span>
              ) : null}
            </span>
            <span className="font-mono text-[#412d15]">{s.trackingNumber ?? '—'}</span>
            {s.labelUrl ? (
              <a
                href={s.labelUrl}
                target="_blank"
                rel="noreferrer"
                className="text-[11px] font-semibold text-[#412d15] underline hover:text-[#1f150c]"
              >
                Label
              </a>
            ) : (
              <span className="text-[10.5px] text-[#b6a684]">no url</span>
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
