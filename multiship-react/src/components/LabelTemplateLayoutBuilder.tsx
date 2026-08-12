import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  FiAlignLeft,
  FiBookmark,
  FiChevronDown,
  FiChevronRight,
  FiCode,
  FiCopy,
  FiDownload,
  FiEye,
  FiFileText,
  FiGrid,
  FiImage,
  FiList,
  FiLoader,
  FiMove,
  FiPlus,
  FiRefreshCw,
  FiTrash2,
  FiType,
  FiX,
} from 'react-icons/fi'
import { createPortal } from 'react-dom'
import { notify } from '../utils/notify'
import { previewTemplateHtml, previewTemplatePdfObjectUrl, previewTemplateZpl } from '../api/labelTemplateService'
import {
  BINDING_GROUPS,
  defaultBlock,
  emptyLayout,
  type BindingField,
  type BlockKind,
  type TemplateBlock,
  type TemplateLayout,
} from '../utils/templateLayout'

const PALETTE: ReadonlyArray<{ kind: BlockKind; label: string; icon: typeof FiType }> = [
  { kind: 'text',      label: 'Text',            icon: FiType },
  { kind: 'logo',      label: 'Logo / image',    icon: FiImage },
  { kind: 'address',   label: 'Address',         icon: FiFileText },
  { kind: 'items',     label: 'Items table',     icon: FiList },
  { kind: 'barcode',   label: 'Barcode',         icon: FiCode },
  { kind: 'qr',        label: 'QR code',         icon: FiGrid },
  { kind: 'divider',   label: 'Divider',         icon: FiAlignLeft },
  { kind: 'spacer',    label: 'Spacer',          icon: FiMove },
  { kind: 'totals',    label: 'Totals',          icon: FiBookmark },
  { kind: 'signature', label: 'Signature',       icon: FiCopy },
]

/**
 * Phase 1 drag-drop template builder. Block-stack model (not free 2D — that
 * comes in Phase 2): a top-down list of blocks, drag to reorder, click to
 * select, right panel edits the picked block + surfaces the data-binding
 * tree so operators can insert `{{group.field}}` tokens without knowing
 * every one by name.
 *
 * Rendering is purely a preview here — the actual document renderer (PDF for
 * pack slip / invoice, ZPL for shipping label) lands in Phase 2 and walks the
 * same TemplateLayout tree we persist.
 */
export default function LabelTemplateLayoutBuilder({
  value,
  onChange,
}: {
  value: TemplateLayout | null
  onChange: (next: TemplateLayout) => void
}) {
  const layout: TemplateLayout = value ?? emptyLayout
  const [selectedId, setSelectedId] = useState<string | null>(layout.blocks[0]?.id ?? null)
  /** DOM refs for text-block inputs so bindings can be inserted at the caret
   *  position instead of appended blindly. Reset on every block change. */
  const textAreaRefs = useRef<Record<string, HTMLTextAreaElement | null>>({})

  // ===== Free 2D canvas geometry =====
  // Canvas dimensions are driven by the layout's page settings (defaults to
  // A4 210×297 mm). We render on-screen at a fixed px-per-mm scale so the
  // preview is sized reasonably regardless of the operator's viewport.
  const pageWidthMm = layout.page?.widthMm ?? 210
  const pageHeightMm = layout.page?.heightMm ?? 297
  const marginMm = layout.page?.marginMm ?? 10
  const PX_PER_MM = 2.8
  const SNAP_MM = 2
  const MIN_WMM = 20
  const MIN_HMM = 8
  const innerWMm = pageWidthMm - marginMm * 2
  const innerHMm = pageHeightMm - marginMm * 2
  const canvasRef = useRef<HTMLDivElement | null>(null)

  const toPx = (mm: number) => mm * PX_PER_MM
  const snap = (mm: number) => Math.round(mm / SNAP_MM) * SNAP_MM
  const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v))

  // ===== Preview state (Phase 2a + 2b) =====
  const [previewOpen, setPreviewOpen] = useState(false)
  const [previewHtml, setPreviewHtml] = useState<string>('')
  const [previewLoading, setPreviewLoading] = useState(false)
  const [pdfLoading, setPdfLoading] = useState(false)
  const [zplLoading, setZplLoading] = useState(false)
  const layoutJson = useMemo(() => JSON.stringify(layout), [layout])
  const refreshPreview = async () => {
    setPreviewLoading(true)
    try {
      const html = await previewTemplateHtml(layoutJson)
      setPreviewHtml(html)
    } catch (error) {
      notify.apiError(error, 'Preview failed.')
    } finally {
      setPreviewLoading(false)
    }
  }
  // Phase 2b — hit /preview.pdf, open the resulting Blob in a new tab.
  // Blob URL is revoked when the tab closes; the browser handles it.
  const openPdf = async () => {
    setPdfLoading(true)
    try {
      const url = await previewTemplatePdfObjectUrl(layoutJson)
      const win = window.open(url, '_blank', 'noopener,noreferrer')
      if (!win) {
        // Popup blocked — fall back to a same-tab navigation.
        window.location.assign(url)
      }
      // Revoke after a delay so the target tab has time to load the PDF.
      window.setTimeout(() => URL.revokeObjectURL(url), 60_000)
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'PDF preview failed.')
    } finally {
      setPdfLoading(false)
    }
  }
  // Phase 2c — hit /preview.zpl for a 203-dpi thermal render and download
  // it as a .zpl file. Operators pipe the file to the printer directly
  // (labelary.com/viewer.html also renders it in a browser for visual QA).
  const downloadZpl = async () => {
    setZplLoading(true)
    try {
      const zpl = await previewTemplateZpl(layoutJson, 203)
      const blob = new Blob([zpl], { type: 'text/plain' })
      const url = URL.createObjectURL(blob)
      const a = document.createElement('a')
      a.href = url
      a.download = 'template-preview.zpl'
      document.body.appendChild(a)
      a.click()
      document.body.removeChild(a)
      window.setTimeout(() => URL.revokeObjectURL(url), 10_000)
    } catch (error) {
      notify.error(error instanceof Error ? error.message : 'ZPL preview failed.')
    } finally {
      setZplLoading(false)
    }
  }
  // Auto-refresh the preview when the layout changes while the panel is open.
  // Debounce briefly so rapid edits don't spam the endpoint.
  useEffect(() => {
    if (!previewOpen) return
    const t = window.setTimeout(() => { void refreshPreview() }, 400)
    return () => window.clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [previewOpen, layoutJson])

  const selectedBlock = useMemo(
    () => layout.blocks.find((b) => b.id === selectedId) ?? null,
    [layout.blocks, selectedId],
  )

  const updateBlock = (id: string, patch: Partial<TemplateBlock>) => {
    onChange({
      ...layout,
      blocks: layout.blocks.map((b) =>
        b.id === id ? ({ ...b, ...patch } as TemplateBlock) : b,
      ),
    })
  }

  const addBlock = (kind: BlockKind) => {
    const b = defaultBlock(kind)
    onChange({ ...layout, blocks: [...layout.blocks, b] })
    setSelectedId(b.id)
  }

  const removeBlock = (id: string) => {
    onChange({ ...layout, blocks: layout.blocks.filter((b) => b.id !== id) })
    setSelectedId((cur) => (cur === id ? null : cur))
  }

  /** Bring a block to the front of the z-order (last in array = on top) —
   *  triggered on click so the operator can always drag the block they can
   *  see, and helps when blocks overlap. */
  const bringToFront = (id: string) => {
    const idx = layout.blocks.findIndex((b) => b.id === id)
    if (idx < 0 || idx === layout.blocks.length - 1) return
    const next = [...layout.blocks]
    const [moved] = next.splice(idx, 1)
    next.push(moved)
    onChange({ ...layout, blocks: next })
  }

  /**
   * Drag or resize a block on the free 2D canvas. `mode` decides how the
   * mouse delta maps onto the block's position:
   *   'move'      — dx/dy translate xMm/yMm; wMm/hMm preserved.
   *   'resize-se' — dx/dy grow wMm/hMm from the top-left anchor.
   *   'resize-e'  — only wMm grows.
   *   'resize-s'  — only hMm grows.
   * The pointer becomes captured on the source element so releasing outside
   * the canvas still ends the gesture cleanly.
   */
  const beginPointerGesture = useCallback(
    (
      id: string,
      mode: 'move' | 'resize-se' | 'resize-e' | 'resize-s',
      e: React.PointerEvent<HTMLDivElement>,
    ) => {
      e.stopPropagation()
      // capture so the pointerup fires on the source even if the pointer
      // leaves the block; also disables text selection during drag.
      const target = e.currentTarget
      target.setPointerCapture(e.pointerId)
      const block = layout.blocks.find((b) => b.id === id)
      if (!block?.position) return
      const startX = e.clientX
      const startY = e.clientY
      const p0 = { ...block.position }
      const onMove = (ev: PointerEvent) => {
        const dxMm = (ev.clientX - startX) / PX_PER_MM
        const dyMm = (ev.clientY - startY) / PX_PER_MM
        const next = { ...p0 }
        if (mode === 'move') {
          next.xMm = clamp(snap(p0.xMm + dxMm), 0, innerWMm - p0.wMm)
          next.yMm = clamp(snap(p0.yMm + dyMm), 0, innerHMm - p0.hMm)
        } else {
          if (mode === 'resize-e' || mode === 'resize-se') {
            next.wMm = clamp(snap(p0.wMm + dxMm), MIN_WMM, innerWMm - p0.xMm)
          }
          if (mode === 'resize-s' || mode === 'resize-se') {
            next.hMm = clamp(snap(p0.hMm + dyMm), MIN_HMM, innerHMm - p0.yMm)
          }
        }
        updateBlock(id, { position: next } as Partial<TemplateBlock>)
      }
      const onUp = () => {
        window.removeEventListener('pointermove', onMove)
        window.removeEventListener('pointerup', onUp)
      }
      window.addEventListener('pointermove', onMove)
      window.addEventListener('pointerup', onUp)
    },
    // updateBlock closes over layout via onChange — safe to omit.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [layout.blocks, innerWMm, innerHMm],
  )

  /**
   * Insert `{{path}}` at the caret in the currently-focused text-like input
   * of the selected block. For non-text blocks (barcode / qr / address /
   * items) whose config is a Select or checkbox, we no-op and rely on the
   * operator picking the binding via the block's own Select.
   */
  const insertBindingAtCaret = (path: string) => {
    if (!selectedBlock) return
    const token = `{{${path}}}`
    if (selectedBlock.kind === 'text' || selectedBlock.kind === 'signature') {
      const el = textAreaRefs.current[selectedBlock.id]
      const cur = selectedBlock.content ?? ''
      if (el) {
        const start = el.selectionStart ?? cur.length
        const end = el.selectionEnd ?? cur.length
        const next = cur.slice(0, start) + token + cur.slice(end)
        updateBlock(selectedBlock.id, { content: next } as Partial<TemplateBlock>)
        // Restore caret after React re-render.
        setTimeout(() => {
          if (!el) return
          el.focus()
          el.setSelectionRange(start + token.length, start + token.length)
        }, 0)
      } else {
        updateBlock(selectedBlock.id, { content: cur + token } as Partial<TemplateBlock>)
      }
      return
    }
    if (selectedBlock.kind === 'barcode' || selectedBlock.kind === 'qr') {
      updateBlock(selectedBlock.id, { binding: path } as Partial<TemplateBlock>)
      return
    }
    // Other blocks don't take free-text bindings.
  }

  return (
    <div className="space-y-3">
      {/* ===== Preview toolbar =====
          Single Preview button — opens a modal containing the HTML iframe
          plus Open-as-PDF and Download-ZPL actions. The always-visible
          inline iframe was removed so the builder gets more vertical room. */}
      <div className="flex flex-wrap items-center gap-2 rounded-xl border border-[#e3d9c4] bg-[#1f150c] px-3 py-2 shadow-sm">
        <FiGrid className="h-3.5 w-3.5 text-[#b6a684]" />
        <span className="font-mono text-[10px] font-bold uppercase tracking-[0.22em] text-[#e3d9c4]">Layout studio</span>
        <span className="hidden text-[10.5px] text-[#8a7959] sm:inline">Free-form blocks · renders to PDF &amp; ZPL</span>
        <button
          type="button"
          onClick={() => setPreviewOpen(true)}
          className="ml-auto inline-flex items-center gap-1.5 rounded-lg bg-[#f4eede] px-3 py-1.5 text-[12px] font-semibold text-[#1f150c] transition hover:bg-white"
        >
          <FiEye className="h-3.5 w-3.5" />
          Preview &amp; export
        </button>
      </div>

      {previewOpen ? (
        <PreviewModal
          previewHtml={previewHtml}
          previewLoading={previewLoading}
          pdfLoading={pdfLoading}
          zplLoading={zplLoading}
          onRefresh={() => void refreshPreview()}
          onOpenPdf={() => void openPdf()}
          onDownloadZpl={() => void downloadZpl()}
          onClose={() => setPreviewOpen(false)}
        />
      ) : null}

      <div className="grid grid-cols-12 gap-3">
      {/* ===== Left: block palette (toolbox) ===== */}
      <aside className="col-span-3 overflow-hidden rounded-2xl border border-[#e3d9c4] bg-white shadow-sm">
        <div className="flex items-center gap-1.5 border-b border-[#eee6d6] bg-[#faf7f0]/70 px-3 py-2">
          <FiGrid className="h-3 w-3 text-[#b6a684]" />
          <span className="text-[10px] font-bold uppercase tracking-[0.16em] text-[#412d15]">Toolbox</span>
        </div>
        <div className="space-y-1 p-2">
          {PALETTE.map((p) => {
            const Icon = p.icon
            return (
              <button
                key={p.kind}
                type="button"
                onClick={() => addBlock(p.kind)}
                className="group flex w-full items-center gap-2 rounded-lg border border-[#e3d9c4] bg-[#faf7f0]/40 px-2 py-1.5 text-left text-[11.5px] font-semibold text-[#412d15] transition hover:border-[#cdbf9f] hover:bg-white"
                title={`Add a ${p.label} block`}
              >
                <span className="inline-flex h-6 w-6 items-center justify-center rounded-md bg-white text-[#8a7959] ring-1 ring-[#e3d9c4] transition group-hover:bg-[#1f150c] group-hover:text-[#f4eede] group-hover:ring-[#1f150c]">
                  <Icon className="h-3.5 w-3.5" />
                </span>
                {p.label}
                <FiPlus className="ml-auto h-3 w-3 text-[#b6a684] transition group-hover:text-[#412d15]" />
              </button>
            )
          })}
          <p className="px-1 pt-1 text-[10px] leading-4 text-[#b6a684]">
            Click to drop a block, then drag to move · corner handle to resize · snap {SNAP_MM} mm.
          </p>
        </div>
      </aside>

      {/* ===== Middle: free 2D canvas =====
          Absolute-positioned surface sized in mm (converted to px at
          PX_PER_MM for display). Each block is draggable + resizable via
          pointer events; positions snap to a 2mm grid. Click to select,
          click again brings the block to the front of the z-order so
          overlapped blocks are always reachable. */}
      <div className="col-span-6 overflow-hidden rounded-2xl border border-[#e3d9c4] bg-white shadow-sm">
        <div className="flex items-center justify-between gap-2 border-b border-[#eee6d6] bg-[#faf7f0]/70 px-3 py-2">
          <span className="flex items-center gap-1.5 text-[10px] font-bold uppercase tracking-[0.16em] text-[#412d15]">
            <FiFileText className="h-3 w-3 text-[#b6a684]" /> Artboard
          </span>
          <span className="font-mono text-[9.5px] font-semibold tabular-nums text-[#8a7959]">
            {layout.blocks.length} block{layout.blocks.length === 1 ? '' : 's'} · {pageWidthMm}×{pageHeightMm}mm · snap {SNAP_MM}mm
          </span>
        </div>

        {/* Scrollable viewport so operators on smaller screens can still
            reach every corner of an A4 page. The page itself is centered. */}
        <div className="max-h-[720px] overflow-auto bg-[#eee6d6] p-4">
          <div
            className="relative mx-auto bg-white shadow-[0_10px_30px_rgba(15,23,42,.12)]"
            style={{ width: toPx(pageWidthMm), height: toPx(pageHeightMm) }}
            onClick={() => setSelectedId(null)}
          >
            {/* Margin frame — visible dashed rectangle marking the printable area. */}
            <div
              className="pointer-events-none absolute rounded-sm border border-dashed border-[#e3d9c4]"
              style={{
                left: toPx(marginMm), top: toPx(marginMm),
                width: toPx(innerWMm), height: toPx(innerHMm),
              }}
            />
            {/* Blocks — rendered inside the margin frame. Position 0,0 in
                block coords maps to the top-left of the inner area. */}
            <div
              ref={canvasRef}
              className="absolute"
              style={{
                left: toPx(marginMm), top: toPx(marginMm),
                width: toPx(innerWMm), height: toPx(innerHMm),
              }}
            >
              {layout.blocks.length === 0 ? (
                <div className="absolute inset-0 flex items-center justify-center rounded-md border border-dashed border-[#cdbf9f] text-[12px] text-[#8a7959]">
                  Empty page. Click a block on the left to drop it here.
                </div>
              ) : null}
              {layout.blocks.map((b) => {
                const pos = b.position ?? { xMm: 0, yMm: 0, wMm: 100, hMm: 20 }
                const selected = selectedId === b.id
                return (
                  <div
                    key={b.id}
                    onPointerDown={(e) => beginPointerGesture(b.id, 'move', e)}
                    onClick={(e) => {
                      e.stopPropagation()
                      setSelectedId(b.id)
                      bringToFront(b.id)
                    }}
                    className={`absolute select-none rounded-md border bg-white shadow-sm transition ${
                      selected
                        ? 'border-[#1f150c] ring-2 ring-[#412d15]/25'
                        : 'border-[#e3d9c4] hover:border-[#b6a684]'
                    }`}
                    style={{
                      left: toPx(pos.xMm), top: toPx(pos.yMm),
                      width: toPx(pos.wMm), height: toPx(pos.hMm),
                      cursor: 'move',
                      touchAction: 'none',
                    }}
                  >
                    {/* Top chrome — kind label + delete button (visible when selected) */}
                    {selected ? (
                      <div className="pointer-events-none absolute -top-5 left-0 flex items-center gap-1">
                        <span className="rounded-md bg-[#1f150c] px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-white">
                          {b.kind} · {pos.wMm}×{pos.hMm}mm
                        </span>
                      </div>
                    ) : null}
                    {selected ? (
                      <button
                        type="button"
                        onPointerDown={(e) => e.stopPropagation()}
                        onClick={(e) => { e.stopPropagation(); removeBlock(b.id) }}
                        className="absolute -top-2 -right-2 z-10 inline-flex h-5 w-5 items-center justify-center rounded-full border border-[#e3d9c4] bg-white text-[#8a7959] shadow hover:border-rose-200 hover:text-rose-600"
                        aria-label={`Remove ${b.kind} block`}
                      >
                        <FiTrash2 className="h-2.5 w-2.5" />
                      </button>
                    ) : null}

                    {/* Block preview fills the block's bounding rect. Overflow
                        hidden so oversized content doesn't spill into the page. */}
                    <div className="pointer-events-none h-full w-full overflow-hidden rounded-md bg-[#faf7f0] p-1">
                      <BlockPreview block={b} />
                    </div>

                    {/* Resize handles — south, east, and south-east corner.
                        Only rendered on the selected block to keep the canvas
                        visually calm. */}
                    {selected ? (
                      <>
                        <div
                          onPointerDown={(e) => beginPointerGesture(b.id, 'resize-e', e)}
                          className="absolute right-0 top-1/2 -translate-y-1/2 h-6 w-1.5 cursor-ew-resize rounded-sm bg-[#412d15]/60 hover:bg-[#412d15]"
                          title="Drag to resize width"
                        />
                        <div
                          onPointerDown={(e) => beginPointerGesture(b.id, 'resize-s', e)}
                          className="absolute bottom-0 left-1/2 -translate-x-1/2 h-1.5 w-6 cursor-ns-resize rounded-sm bg-[#412d15]/60 hover:bg-[#412d15]"
                          title="Drag to resize height"
                        />
                        <div
                          onPointerDown={(e) => beginPointerGesture(b.id, 'resize-se', e)}
                          className="absolute bottom-0 right-0 h-3 w-3 cursor-nwse-resize rounded-sm bg-[#412d15]"
                          title="Drag to resize"
                        />
                      </>
                    ) : null}
                  </div>
                )
              })}
            </div>
          </div>
        </div>
      </div>

      {/* ===== Right: selected-block config + binding tree (inspector) ===== */}
      <aside className="col-span-3 overflow-hidden rounded-2xl border border-[#e3d9c4] bg-white shadow-sm">
        <div className="flex items-center justify-between gap-1.5 border-b border-[#eee6d6] bg-[#faf7f0]/70 px-3 py-2">
          <span className="flex items-center gap-1.5 text-[10px] font-bold uppercase tracking-[0.16em] text-[#412d15]">
            <FiType className="h-3 w-3 text-[#b6a684]" /> Inspector
          </span>
          {selectedBlock ? (
            <span className="rounded-full bg-[#1f150c] px-1.5 py-0.5 font-mono text-[8.5px] font-bold uppercase tracking-wide text-[#f4eede]">{selectedBlock.kind}</span>
          ) : null}
        </div>
        <div className="space-y-3 p-2">
        <div>
          {selectedBlock ? (
            <BlockEditor
              block={selectedBlock}
              onChange={(p) => updateBlock(selectedBlock.id, p)}
              textAreaRef={(el) => { textAreaRefs.current[selectedBlock.id] = el }}
            />
          ) : (
            <p className="rounded-lg border border-dashed border-[#e3d9c4] bg-[#faf7f0]/60 px-2 py-3 text-center text-[11px] text-[#8a7959]">
              Select a block on the artboard to edit it.
            </p>
          )}
        </div>

        <BindingsPanel onInsert={insertBindingAtCaret} disabled={!selectedBlock} />
        </div>
      </aside>
      </div>
    </div>
  )
}

// ==========================================================================
// Sub-components
// ==========================================================================

/**
 * Full-screen preview modal. Owns none of the fetch state — the parent
 * builder already runs debounced auto-refresh on layout edits (bound to
 * previewOpen), so the modal just projects whatever HTML is currently in
 * scope and offers the PDF / ZPL exports alongside it.
 *
 * ESC closes; the toolbar buttons stay disabled while their respective
 * fetches are in flight so operators can't stack requests.
 */
function PreviewModal({
  previewHtml,
  previewLoading,
  pdfLoading,
  zplLoading,
  onRefresh,
  onOpenPdf,
  onDownloadZpl,
  onClose,
}: {
  previewHtml: string
  previewLoading: boolean
  pdfLoading: boolean
  zplLoading: boolean
  onRefresh: () => void
  onOpenPdf: () => void
  onDownloadZpl: () => void
  onClose: () => void
}) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose])

  return createPortal(
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-[#1f150c]/50 p-4">
      <div className="flex h-full max-h-[92vh] w-full max-w-5xl flex-col overflow-hidden rounded-2xl bg-white shadow-2xl">
        <header className="flex items-start justify-between gap-3 border-b border-[#e3d9c4] px-5 py-3">
          <div>
            <h2 className="text-base font-semibold text-[#1f150c]">Template preview</h2>
            <p className="text-[12px] text-[#8a7959]">
              Rendered against the built-in sample shipment context. Edits re-render automatically.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close preview"
            className="inline-flex h-8 w-8 items-center justify-center rounded-md text-[#8a7959] transition hover:bg-[#eee6d6] hover:text-[#1f150c]"
          >
            <FiX className="h-4 w-4" />
          </button>
        </header>
        <div className="flex flex-wrap gap-2 border-b border-[#eee6d6] bg-[#faf7f0]/60 px-5 py-3">
          <button
            type="button"
            onClick={onRefresh}
            disabled={previewLoading}
            title="Force-refresh HTML preview"
            className="inline-flex items-center gap-1.5 rounded-md border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12.5px] font-semibold text-[#412d15] transition hover:bg-[#faf7f0] disabled:opacity-50"
          >
            {previewLoading ? <FiLoader className="h-3.5 w-3.5 animate-spin" /> : <FiRefreshCw className="h-3.5 w-3.5" />}
            Refresh HTML
          </button>
          <button
            type="button"
            onClick={onOpenPdf}
            disabled={pdfLoading}
            title="Open the same layout rendered as PDF in a new tab"
            className="inline-flex items-center gap-1.5 rounded-md border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12.5px] font-semibold text-[#412d15] transition hover:bg-[#faf7f0] disabled:opacity-50"
          >
            {pdfLoading ? <FiLoader className="h-3.5 w-3.5 animate-spin" /> : <FiDownload className="h-3.5 w-3.5" />}
            Open as PDF
          </button>
          <button
            type="button"
            onClick={onDownloadZpl}
            disabled={zplLoading}
            title="Download the layout as ZPL for a 203-dpi Zebra thermal printer (paste into labelary.com/viewer.html to visualise)"
            className="inline-flex items-center gap-1.5 rounded-md border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12.5px] font-semibold text-[#412d15] transition hover:bg-[#faf7f0] disabled:opacity-50"
          >
            {zplLoading ? <FiLoader className="h-3.5 w-3.5 animate-spin" /> : <FiCode className="h-3.5 w-3.5" />}
            Download ZPL
          </button>
        </div>
        <div className="flex-1 overflow-hidden bg-[#eee6d6] p-3">
          {previewHtml ? (
            <iframe
              // srcDoc keeps everything self-contained — the endpoint returns
              // a full HTML document; no external assets to sandbox.
              srcDoc={previewHtml}
              title="Template preview"
              className="h-full w-full rounded-lg border border-[#e3d9c4] bg-white"
              sandbox=""
            />
          ) : (
            <div className="flex h-full items-center justify-center rounded-lg border border-dashed border-[#e3d9c4] bg-white text-[12.5px] text-[#8a7959]">
              {previewLoading ? 'Loading preview…' : 'Preview will render once the backend responds.'}
            </div>
          )}
        </div>
      </div>
    </div>,
    document.body,
  )
}

function BlockPreview({ block }: { block: TemplateBlock }) {
  switch (block.kind) {
    case 'text':
    case 'signature':
      return (
        <p className={`whitespace-pre-wrap ${block.kind === 'text' && (block as { bold?: boolean }).bold ? 'font-bold' : ''}`}
           style={{ textAlign: (block as { align?: 'left'|'center'|'right' }).align, fontSize: (block as { sizePx?: number }).sizePx ?? 11 }}>
          {(block as { content: string }).content || <span className="italic text-[#b6a684]">empty</span>}
        </p>
      )
    case 'logo':
      return block.src
        ? <img src={block.src} alt="logo" style={{ width: block.widthPx ?? 120 }} className="max-h-24 object-contain" />
        : <span className="italic text-[#b6a684]">Logo placeholder — set image src in Block settings</span>
    case 'address':
      return <span className="italic text-[#8a7959]">↳ {block.which} address (rendered from shipment)</span>
    case 'items':
      return (
        <table className="w-full border-collapse text-[10.5px]">
          <thead>
            <tr>
              {block.columns.map((c) => <th key={c} className="border-b border-[#e3d9c4] px-1 py-0.5 text-left font-semibold text-[#8a7959]">{c}</th>)}
            </tr>
          </thead>
          <tbody>
            <tr>
              {block.columns.map((c) => <td key={c} className="px-1 py-0.5 text-[#b6a684] italic">…{c}…</td>)}
            </tr>
          </tbody>
        </table>
      )
    case 'barcode':
      return <span className="font-mono italic text-[#8a7959]">▉▊▉ {block.binding}</span>
    case 'qr':
      return (
        <div className="inline-block rounded border border-[#e3d9c4] bg-white p-1 text-center text-[9px] text-[#b6a684]" style={{ width: block.sizePx ?? 90, height: block.sizePx ?? 90 }}>
          QR<br />{block.binding}
        </div>
      )
    case 'divider':
      return <hr style={{ borderTopWidth: block.thicknessPx ?? 1, borderColor: block.color ?? '#94a3b8' }} />
    case 'spacer':
      return <div style={{ height: block.heightPx ?? 12 }} className="rounded bg-[#eee6d6] text-center text-[9px] leading-none text-[#b6a684]">↕ spacer</div>
    case 'totals':
      return <span className="italic text-[#8a7959]">Totals: {block.include.join(' · ')}</span>
  }
}

function BlockEditor({
  block,
  onChange,
  textAreaRef,
}: {
  block: TemplateBlock
  onChange: (patch: Partial<TemplateBlock>) => void
  textAreaRef: (el: HTMLTextAreaElement | null) => void
}) {
  const labelCls = 'mb-0.5 block text-[9.5px] font-bold uppercase tracking-[0.14em] text-[#8a7959]'
  const inputCls = 'w-full rounded-lg border border-[#e3d9c4] bg-white px-2 py-1 text-[11.5px] text-[#412d15] outline-none focus:border-[#412d15]'

  // Position card — precise coordinates for operators who don't want to
  // eyeball drag-and-drop. Rendered above the kind-specific settings so it's
  // available for every block type.
  const pos = block.position ?? { xMm: 0, yMm: 0, wMm: 100, hMm: 20 }
  const setPos = (patch: Partial<typeof pos>) =>
    onChange({ position: { ...pos, ...patch } } as Partial<TemplateBlock>)
  const positionCard = (
    <div className="rounded-lg border border-[#eee6d6] bg-[#faf7f0]/60 p-2">
      <p className={labelCls}>Position (mm)</p>
      <div className="grid grid-cols-2 gap-1.5">
        <label className="block">
          <span className="text-[9.5px] font-semibold text-[#8a7959]">X</span>
          <input type="number" value={pos.xMm} onChange={(e) => setPos({ xMm: Number(e.target.value) || 0 })} className={inputCls} />
        </label>
        <label className="block">
          <span className="text-[9.5px] font-semibold text-[#8a7959]">Y</span>
          <input type="number" value={pos.yMm} onChange={(e) => setPos({ yMm: Number(e.target.value) || 0 })} className={inputCls} />
        </label>
        <label className="block">
          <span className="text-[9.5px] font-semibold text-[#8a7959]">Width</span>
          <input type="number" value={pos.wMm} onChange={(e) => setPos({ wMm: Number(e.target.value) || 1 })} className={inputCls} />
        </label>
        <label className="block">
          <span className="text-[9.5px] font-semibold text-[#8a7959]">Height</span>
          <input type="number" value={pos.hMm} onChange={(e) => setPos({ hMm: Number(e.target.value) || 1 })} className={inputCls} />
        </label>
      </div>
    </div>
  )

  const kindEditor = (() => {
  switch (block.kind) {
    case 'text':
    case 'signature':
      return (
        <div className="space-y-1.5">
          <label className="block">
            <span className={labelCls}>Content</span>
            <textarea
              ref={textAreaRef}
              value={block.content}
              onChange={(e) => onChange({ content: e.target.value } as Partial<TemplateBlock>)}
              rows={5}
              className={`${inputCls} resize-y font-mono text-[11px]`}
            />
          </label>
          {block.kind === 'text' ? (
            <>
              <label className="block">
                <span className={labelCls}>Align</span>
                <select value={block.align ?? 'left'} onChange={(e) => onChange({ align: e.target.value as 'left'|'center'|'right' } as Partial<TemplateBlock>)} className={inputCls}>
                  <option value="left">Left</option><option value="center">Center</option><option value="right">Right</option>
                </select>
              </label>
              <label className="block">
                <span className={labelCls}>Size (px)</span>
                <input type="number" min={6} max={72} value={block.sizePx ?? 11} onChange={(e) => onChange({ sizePx: Number(e.target.value) } as Partial<TemplateBlock>)} className={inputCls} />
              </label>
              <label className="flex items-center gap-1.5 text-[11px] font-semibold text-[#412d15]">
                <input type="checkbox" checked={!!block.bold} onChange={(e) => onChange({ bold: e.target.checked } as Partial<TemplateBlock>)} className="h-3.5 w-3.5" />
                Bold
              </label>
            </>
          ) : null}
        </div>
      )
    case 'logo':
      return (
        <div className="space-y-1.5">
          <label className="block">
            <span className={labelCls}>Image URL / base64</span>
            <input value={block.src ?? ''} onChange={(e) => onChange({ src: e.target.value } as Partial<TemplateBlock>)} className={inputCls} placeholder="data:image/png;base64,…" />
          </label>
          <label className="block">
            <span className={labelCls}>Width (px)</span>
            <input type="number" min={20} max={800} value={block.widthPx ?? 120} onChange={(e) => onChange({ widthPx: Number(e.target.value) } as Partial<TemplateBlock>)} className={inputCls} />
          </label>
        </div>
      )
    case 'address':
      return (
        <label className="block">
          <span className={labelCls}>Which address</span>
          <select value={block.which} onChange={(e) => onChange({ which: e.target.value as typeof block.which } as Partial<TemplateBlock>)} className={inputCls}>
            <option value="shipTo">Ship To (consignee)</option>
            <option value="shipFrom">Ship From (origin)</option>
            <option value="returnAddress">Return address</option>
            <option value="importer">Importer (customs)</option>
            <option value="broker">Broker (customs)</option>
          </select>
        </label>
      )
    case 'items':
      return (
        <label className="block">
          <span className={labelCls}>Columns (comma-separated)</span>
          <input
            value={block.columns.join(',')}
            onChange={(e) => onChange({ columns: e.target.value.split(/[,\s]+/).filter(Boolean) as typeof block.columns } as Partial<TemplateBlock>)}
            className={inputCls}
            placeholder="sku,description,qty,unitPrice,lineTotal"
          />
          <p className="mt-1 text-[10.5px] text-[#b6a684]">
            Allowed: sku · description · qty · unitPrice · lineTotal · weight · hsCode · originCountry
          </p>
        </label>
      )
    case 'barcode':
      return (
        <div className="space-y-1.5">
          <label className="block">
            <span className={labelCls}>Binding (data source)</span>
            <input value={block.binding} onChange={(e) => onChange({ binding: e.target.value } as Partial<TemplateBlock>)} className={inputCls} placeholder="shipment.trackingNumber" />
            <p className="mt-1 text-[10.5px] text-[#b6a684]">Or click a field on the right to set it.</p>
          </label>
          <label className="block">
            <span className={labelCls}>Format</span>
            <select value={block.format ?? 'code128'} onChange={(e) => onChange({ format: e.target.value as 'code128'|'code39' } as Partial<TemplateBlock>)} className={inputCls}>
              <option value="code128">Code 128</option>
              <option value="code39">Code 39</option>
            </select>
          </label>
          <label className="block">
            <span className={labelCls}>Height (px)</span>
            <input type="number" min={20} max={200} value={block.heightPx ?? 60} onChange={(e) => onChange({ heightPx: Number(e.target.value) } as Partial<TemplateBlock>)} className={inputCls} />
          </label>
        </div>
      )
    case 'qr':
      return (
        <div className="space-y-1.5">
          <label className="block">
            <span className={labelCls}>Binding</span>
            <input value={block.binding} onChange={(e) => onChange({ binding: e.target.value } as Partial<TemplateBlock>)} className={inputCls} />
          </label>
          <label className="block">
            <span className={labelCls}>Size (px)</span>
            <input type="number" min={30} max={300} value={block.sizePx ?? 90} onChange={(e) => onChange({ sizePx: Number(e.target.value) } as Partial<TemplateBlock>)} className={inputCls} />
          </label>
        </div>
      )
    case 'divider':
      return (
        <div className="space-y-1.5">
          <label className="block">
            <span className={labelCls}>Thickness (px)</span>
            <input type="number" min={1} max={10} value={block.thicknessPx ?? 1} onChange={(e) => onChange({ thicknessPx: Number(e.target.value) } as Partial<TemplateBlock>)} className={inputCls} />
          </label>
          <label className="block">
            <span className={labelCls}>Color</span>
            <input type="color" value={block.color ?? '#94a3b8'} onChange={(e) => onChange({ color: e.target.value } as Partial<TemplateBlock>)} className="h-8 w-full rounded-lg border border-[#e3d9c4]" />
          </label>
        </div>
      )
    case 'spacer':
      return (
        <label className="block">
          <span className={labelCls}>Height (px)</span>
          <input type="number" min={2} max={200} value={block.heightPx ?? 12} onChange={(e) => onChange({ heightPx: Number(e.target.value) } as Partial<TemplateBlock>)} className={inputCls} />
        </label>
      )
    case 'totals':
      return (
        <div className="space-y-1.5">
          <label className="block">
            <span className={labelCls}>Include (comma-separated)</span>
            <input
              value={block.include.join(',')}
              onChange={(e) => onChange({ include: e.target.value.split(/[,\s]+/).filter(Boolean) as typeof block.include } as Partial<TemplateBlock>)}
              className={inputCls}
            />
            <p className="mt-1 text-[10.5px] text-[#b6a684]">Allowed: subtotal · freight · duties · insurance · grandTotal</p>
          </label>
          <label className="block">
            <span className={labelCls}>Currency</span>
            <input value={block.currency ?? 'USD'} onChange={(e) => onChange({ currency: e.target.value.toUpperCase() } as Partial<TemplateBlock>)} className={`${inputCls} uppercase`} maxLength={3} />
          </label>
        </div>
      )
  }
  })()

  return (
    <div className="space-y-2">
      {positionCard}
      {kindEditor}
    </div>
  )
}

function BindingsPanel({
  onInsert,
  disabled,
}: {
  onInsert: (path: string) => void
  disabled: boolean
}) {
  const [openGroups, setOpenGroups] = useState<Set<string>>(new Set(['order', 'shipTo']))
  const toggle = (key: string) => {
    setOpenGroups((cur) => {
      const next = new Set(cur)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })
  }
  return (
    <div>
      <p className="mb-1.5 px-1 text-[10px] font-bold uppercase tracking-[0.16em] text-[#8a7959]">
        Available fields
      </p>
      <p className="mb-1.5 px-1 text-[10.5px] leading-4 text-[#b6a684]">
        Click a field to insert <span className="font-mono">{`{{path}}`}</span> at the cursor of the selected block.
      </p>
      <div className={`max-h-96 overflow-y-auto rounded-lg border border-[#e3d9c4] bg-white p-1 ${disabled ? 'opacity-40' : ''}`}>
        {BINDING_GROUPS.map((g) => {
          const open = openGroups.has(g.key)
          return (
            <div key={g.key}>
              <button
                type="button"
                onClick={() => toggle(g.key)}
                className="flex w-full items-center gap-1 rounded px-1 py-0.5 text-left text-[11px] font-semibold text-[#412d15] hover:bg-[#faf7f0]"
              >
                {open ? <FiChevronDown className="h-3 w-3" /> : <FiChevronRight className="h-3 w-3" />}
                {g.label}
              </button>
              {open ? (
                <ul className="ml-2 border-l border-[#eee6d6]">
                  {g.fields.map((f: BindingField) => (
                    <li key={f.path}>
                      <button
                        type="button"
                        onClick={() => !disabled && onInsert(f.path)}
                        disabled={disabled}
                        title={f.sample ? `Sample: ${f.sample}` : undefined}
                        className="flex w-full items-center gap-1 rounded px-1.5 py-0.5 text-left text-[10.5px] text-[#5a4526] hover:bg-[#412d15]/[0.06] hover:text-[#1f150c] disabled:cursor-not-allowed"
                      >
                        <span className="truncate">{f.label}</span>
                        <span className="ml-auto shrink-0 font-mono text-[9.5px] text-[#b6a684]">{f.path.split('.').slice(1).join('.')}</span>
                      </button>
                    </li>
                  ))}
                </ul>
              ) : null}
            </div>
          )
        })}
      </div>
    </div>
  )
}
