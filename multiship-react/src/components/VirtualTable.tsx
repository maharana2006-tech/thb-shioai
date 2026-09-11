import { useRef, type ReactNode } from 'react'
import { useVirtualizer } from '@tanstack/react-virtual'

/**
 * The editable import grids hold one row per spreadsheet line — 2,484 rows for a
 * 1,000-order file. Rendering them all put ~275k nodes in the DOM and ran at ~2 fps.
 * This renders only the rows near the viewport (plus overscan) and pads the rest,
 * so the grid stays fast at any size. The header stays pinned while scrolling.
 *
 * Each rendered row must pass `measureRef` as its `ref` and `index` as `data-index`
 * on its <tr>, so rows of different heights are measured correctly.
 */
export default function VirtualTable<T>({
  rows,
  rowKey,
  renderRow,
  head,
  colCount,
  className,
  tableClassName,
  maxHeight = '65vh',
  estimateRowHeight = 38,
  empty,
}: {
  rows: T[]
  rowKey: (row: T) => string | number
  renderRow: (row: T, index: number, measureRef: (el: Element | null) => void) => ReactNode
  head: ReactNode
  colCount: number
  className?: string
  tableClassName?: string
  maxHeight?: string
  estimateRowHeight?: number
  empty?: ReactNode
}) {
  const scrollRef = useRef<HTMLDivElement>(null)
  // eslint-disable-next-line react-hooks/incompatible-library -- TanStack Virtual's useVirtualizer() returns functions that cannot be memoized safely (same note as NewShipmentPage)
  const virtualizer = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scrollRef.current,
    estimateSize: () => estimateRowHeight,
    overscan: 10,
    getItemKey: (i) => rowKey(rows[i]),
  })
  const items = virtualizer.getVirtualItems()
  const padTop = items.length > 0 ? items[0].start : 0
  const padBottom = items.length > 0 ? virtualizer.getTotalSize() - items[items.length - 1].end : 0

  // Floor of 320px: on a very short window (or a zero-height embedded view)
  // a pure vh limit collapses the grid to nothing.
  return (
    <div ref={scrollRef} className={className} style={{ maxHeight: `max(320px, ${maxHeight})`, overflow: 'auto' }}>
      <table className={tableClassName}>
        {head}
        <tbody>
          {padTop > 0 ? (
            <tr aria-hidden="true">
              <td colSpan={colCount} style={{ height: padTop, padding: 0, border: 0 }} />
            </tr>
          ) : null}
          {items.map((vi) => renderRow(rows[vi.index], vi.index, virtualizer.measureElement))}
          {padBottom > 0 ? (
            <tr aria-hidden="true">
              <td colSpan={colCount} style={{ height: padBottom, padding: 0, border: 0 }} />
            </tr>
          ) : null}
          {rows.length === 0 && empty ? (
            <tr>
              <td colSpan={colCount}>{empty}</td>
            </tr>
          ) : null}
        </tbody>
      </table>
    </div>
  )
}
