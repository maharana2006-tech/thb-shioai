/**
 * Drag-drop template layout schema — the JSON tree persisted into
 * label_templates.layout_json. Kept intentionally flat (block stack, not free
 * 2D positions) for Phase 1. Phase 2 will add `position: { x, y, w, h }` on
 * each block to enable Elementor-style absolute placement without changing
 * the block shapes.
 *
 * Renderers (frontend preview, backend PDF/ZPL) walk `blocks` in order and
 * resolve every `{{group.field}}` binding inside `content` / `columns` /
 * `bindings` against the shipment context at render time.
 */

export type BlockKind =
  | 'text'
  | 'logo'
  | 'address'
  | 'items'
  | 'barcode'
  | 'qr'
  | 'divider'
  | 'spacer'
  | 'totals'
  | 'signature'

/**
 * Absolute position of a block on the page, in millimetres. Optional per
 * block for backwards compatibility: pre-positional blocks flow in a stack
 * below any positioned ones. All coordinates are relative to the page's
 * inner area (i.e. inside the page.marginMm).
 */
export interface BlockPosition {
  xMm: number
  yMm: number
  wMm: number
  hMm: number
}

/** Common fields shared by every block. `position` is optional so legacy
 *  block-stack layouts keep rendering; new blocks always get one. */
export type BlockBase = { id: string; position?: BlockPosition }

export type TemplateBlock = BlockBase & (
  | { kind: 'text';      content: string; align?: 'left' | 'center' | 'right'; sizePx?: number; bold?: boolean; color?: string }
  | { kind: 'logo';      src?: string; widthPx?: number; align?: 'left' | 'center' | 'right' }
  | { kind: 'address';   which: 'shipFrom' | 'shipTo' | 'returnAddress' | 'importer' | 'broker'; label?: string }
  | { kind: 'items';     columns: Array<'sku' | 'description' | 'qty' | 'unitPrice' | 'lineTotal' | 'weight' | 'hsCode' | 'originCountry'>; showHeader?: boolean }
  | { kind: 'barcode';   binding: string; format?: 'code128' | 'code39'; heightPx?: number }
  | { kind: 'qr';        binding: string; sizePx?: number }
  | { kind: 'divider';   thicknessPx?: number; color?: string }
  | { kind: 'spacer';    heightPx?: number }
  | { kind: 'totals';    include: Array<'subtotal' | 'freight' | 'duties' | 'insurance' | 'grandTotal'>; currency?: string }
  | { kind: 'signature'; content: string; heightPx?: number }
)

export interface TemplateLayout {
  /** Schema version — increment when a breaking block-shape change ships so
   *  renderers can migrate old rows on the fly. */
  version: 1
  /** Page defaults; each block can still override. */
  page?: { widthMm?: number; heightMm?: number; marginMm?: number }
  blocks: TemplateBlock[]
}

/** Empty layout used when a template hasn't been laid out yet. */
export const emptyLayout: TemplateLayout = {
  version: 1,
  page: { widthMm: 210, heightMm: 297, marginMm: 10 },
  blocks: [],
}

/** Client-side id for new blocks. Not cryptographic — just needs to be
 *  stable enough to survive drag-reorder within a session. */
let blockCounter = 1
export function newBlockId(): string {
  return `blk-${Date.now().toString(36)}-${blockCounter++}`
}

/** Default block size in millimetres — matches the on-screen canvas which
 *  renders A4 (190mm × 277mm inside the 10mm margin). Chosen so a fresh
 *  block sits comfortably in the corner without needing an immediate resize. */
export function defaultSizeFor(kind: BlockKind): { wMm: number; hMm: number } {
  switch (kind) {
    case 'text':      return { wMm: 100, hMm: 20 }
    case 'logo':      return { wMm: 50,  hMm: 25 }
    case 'address':   return { wMm: 90,  hMm: 35 }
    case 'items':     return { wMm: 190, hMm: 60 }
    case 'barcode':   return { wMm: 60,  hMm: 20 }
    case 'qr':        return { wMm: 30,  hMm: 30 }
    case 'divider':   return { wMm: 190, hMm: 3  }
    case 'spacer':    return { wMm: 190, hMm: 10 }
    case 'totals':    return { wMm: 90,  hMm: 30 }
    case 'signature': return { wMm: 120, hMm: 30 }
  }
}

/** Default config for a freshly-dropped block of the given kind. Every new
 *  block ships with a `position` — the canvas is free-2D now, so an unpicked
 *  spot in the top-left is the safe default. Callers can override by passing
 *  their own position (e.g. drop-at-click-point). */
export function defaultBlock(kind: BlockKind, position?: BlockPosition): TemplateBlock {
  const id = newBlockId()
  const { wMm, hMm } = defaultSizeFor(kind)
  const pos: BlockPosition = position ?? { xMm: 5, yMm: 5, wMm, hMm }
  switch (kind) {
    case 'text':      return { id, kind, position: pos, content: 'New text — click to edit. Use {{order.number}} to insert data.', align: 'left', sizePx: 11 }
    case 'logo':      return { id, kind, position: pos, widthPx: 120, align: 'left' }
    case 'address':   return { id, kind, position: pos, which: 'shipTo', label: 'Ship To' }
    case 'items':     return { id, kind, position: pos, columns: ['sku', 'description', 'qty', 'unitPrice', 'lineTotal'], showHeader: true }
    case 'barcode':   return { id, kind, position: pos, binding: 'shipment.trackingNumber', format: 'code128', heightPx: 60 }
    case 'qr':        return { id, kind, position: pos, binding: 'shipment.trackingNumber', sizePx: 90 }
    case 'divider':   return { id, kind, position: pos, thicknessPx: 1, color: '#94a3b8' }
    case 'spacer':    return { id, kind, position: pos, heightPx: 12 }
    case 'totals':    return { id, kind, position: pos, include: ['subtotal', 'freight', 'grandTotal'], currency: 'USD' }
    case 'signature': return { id, kind, position: pos, content: 'I hereby declare that the information above is true and correct.', heightPx: 60 }
  }
}

/**
 * Right-side data-binding catalog. Grouped tree of every field a template
 * block can reference. `path` becomes the `{{...}}` token inserted at the
 * cursor when the operator clicks it. Keep this in sync with the backend
 * resolver's context object once the render path lands (Phase 2).
 */
export interface BindingField {
  path: string
  label: string
  sample?: string
}
export interface BindingGroup {
  key: string
  label: string
  fields: BindingField[]
}

export const BINDING_GROUPS: ReadonlyArray<BindingGroup> = [
  {
    key: 'order',
    label: 'Order',
    fields: [
      { path: 'order.number',    label: 'Order number',      sample: 'ORD-000123' },
      { path: 'order.poNumber',  label: 'PO number',         sample: 'PO-2026-45' },
      { path: 'order.reference', label: 'Reference',         sample: 'REF/A/00042' },
      { path: 'order.notes',     label: 'Notes',             sample: '' },
      { path: 'order.createdAt', label: 'Order date',        sample: '2026-01-15' },
      { path: 'order.currency',  label: 'Order currency',    sample: 'USD' },
    ],
  },
  {
    key: 'client',
    label: 'Client',
    fields: [
      { path: 'client.code',  label: 'Client code',  sample: 'MA1885' },
      { path: 'client.name',  label: 'Client name',  sample: 'Modern Art Fabrics' },
      { path: 'client.email', label: 'Client email', sample: 'billing@client.com' },
      { path: 'client.phone', label: 'Client phone', sample: '+1 555-000-1234' },
    ],
  },
  {
    key: 'shipTo',
    label: 'Ship To (consignee)',
    fields: [
      { path: 'shipTo.name',    label: 'Name / company' },
      { path: 'shipTo.line1',   label: 'Address line 1' },
      { path: 'shipTo.line2',   label: 'Address line 2' },
      { path: 'shipTo.city',    label: 'City' },
      { path: 'shipTo.state',   label: 'State / region' },
      { path: 'shipTo.zip',     label: 'Postal code' },
      { path: 'shipTo.country', label: 'Country (ISO-2)' },
      { path: 'shipTo.phone',   label: 'Phone' },
      { path: 'shipTo.email',   label: 'Email' },
    ],
  },
  {
    key: 'shipFrom',
    label: 'Ship From (origin)',
    fields: [
      { path: 'shipFrom.name',    label: 'Name / company' },
      { path: 'shipFrom.line1',   label: 'Address line 1' },
      { path: 'shipFrom.line2',   label: 'Address line 2' },
      { path: 'shipFrom.city',    label: 'City' },
      { path: 'shipFrom.state',   label: 'State / region' },
      { path: 'shipFrom.zip',     label: 'Postal code' },
      { path: 'shipFrom.country', label: 'Country (ISO-2)' },
      { path: 'shipFrom.phone',   label: 'Phone' },
    ],
  },
  {
    key: 'return',
    label: 'Return address',
    fields: [
      { path: 'return.name',    label: 'Name / company' },
      { path: 'return.line1',   label: 'Address line 1' },
      { path: 'return.city',    label: 'City' },
      { path: 'return.state',   label: 'State / region' },
      { path: 'return.zip',     label: 'Postal code' },
      { path: 'return.country', label: 'Country (ISO-2)' },
    ],
  },
  {
    key: 'shipment',
    label: 'Shipment',
    fields: [
      { path: 'shipment.trackingNumber', label: 'Tracking number', sample: '1Z9999W99999999999' },
      { path: 'shipment.serviceCode',    label: 'Service code',    sample: '03' },
      { path: 'shipment.serviceName',    label: 'Service name',    sample: 'UPS Ground' },
      { path: 'shipment.carrier',        label: 'Carrier name',    sample: 'UPS' },
      { path: 'shipment.weight',         label: 'Package weight',  sample: '4.5' },
      { path: 'shipment.weightUnit',     label: 'Weight unit',     sample: 'LB' },
      { path: 'shipment.dimensions',     label: 'Dimensions',      sample: '10×8×6 in' },
      { path: 'shipment.declaredValue',  label: 'Declared value',  sample: '250.00' },
      { path: 'shipment.dispatchDate',   label: 'Dispatch date',   sample: '2026-01-16' },
    ],
  },
  {
    key: 'customs',
    label: 'Customs',
    fields: [
      { path: 'customs.purpose',          label: 'Shipping purpose',  sample: 'SALE' },
      { path: 'customs.clearance',        label: 'Clearance option',  sample: 'DDP' },
      { path: 'customs.incoterms',        label: 'Incoterms',         sample: 'DAP' },
      { path: 'customs.reasonForExport',  label: 'Reason for export' },
      { path: 'customs.importer.name',    label: 'Importer name' },
      { path: 'customs.importer.taxId',   label: 'Importer tax ID / EORI' },
      { path: 'customs.broker.name',      label: 'Broker name' },
      { path: 'customs.broker.company',   label: 'Broker company' },
    ],
  },
  {
    key: 'account',
    label: 'Carrier account',
    fields: [
      { path: 'account.number',      label: 'Account number' },
      { path: 'account.name',        label: 'Account name' },
      { path: 'account.environment', label: 'Environment (SANDBOX/PROD)' },
    ],
  },
]
