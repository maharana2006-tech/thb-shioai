import { useEffect, useMemo, useRef, useState } from 'react'
import {
  flexRender,
  getCoreRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  useReactTable,
  type Column,
  type ColumnDef,
  type ColumnOrderState,
  type ColumnPinningState,
  type ColumnSizingState,
  type SortingState,
  type Table,
  type VisibilityState,
} from '@tanstack/react-table'
import {
  DndContext,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  useSensor,
  useSensors,
  type DragEndEvent,
} from '@dnd-kit/core'
import {
  SortableContext,
  horizontalListSortingStrategy,
  sortableKeyboardCoordinates,
  useSortable,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import {
  FiChevronDown,
  FiChevronUp,
  FiColumns,
  FiDownload,
  FiRotateCcw,
  FiSearch,
  FiSliders,
} from 'react-icons/fi'

type Density = 'compact' | 'comfortable'

/** Where to move focus after an in-cell save. `'none'` exits edit mode in place. */
export type EditNav = 'right' | 'left' | 'down' | 'up' | 'none'

/**
 * Props a column's `meta.editCell` renderer receives. The renderer owns the
 * input and the save call; it invokes `finish(direction)` to close the cell
 * and advance focus, or `cancel()` to abort without saving.
 */
export interface EditCellProps<T> {
  row: T
  finish: (direction?: EditNav) => void
  cancel: () => void
}

/** Persisted layout for a given tableKey. Bump `v` on schema change. */
interface StoredLayout {
  v: 1
  columnOrder?: string[]
  columnVisibility?: VisibilityState
  columnSizing?: ColumnSizingState
  columnPinning?: ColumnPinningState
  density?: Density
}

export interface AdvancedDataTableProps<T> {
  /** Stable key — used to persist layout in localStorage. */
  tableKey: string
  columns: ColumnDef<T, unknown>[]
  data: T[]
  /** Optional search box wired into the toolbar. Parent owns the filtering. */
  search?: { value: string; onChange: (value: string) => void; placeholder?: string }
  /** Filter toggle button rendered in the toolbar (parent-defined). */
  filterToggle?: React.ReactNode
  /** Filter panel rendered below the toolbar (parent-defined, usually collapsible). */
  filterPanel?: React.ReactNode
  /**
   * Extra toolbar controls the parent wants (e.g. an "Add" button). Rendered
   * to the right of the built-in menus, before the pagination cluster.
   */
  toolbarActions?: React.ReactNode
  /**
   * Row rendered inside <tbody> at the top of the data rows. Wrapped in a
   * single <td colSpan={visibleColumnCount}> so the parent doesn't have to
   * align inputs to column widths. Use for inline add-forms.
   */
  inlineFormRow?: React.ReactNode
  /** Rendered inside <tbody> when there are no rows. */
  emptyState?: React.ReactNode
  initialHiddenColumns?: string[]
  initialDensity?: Density
  initialPageSize?: number
  /** Filename base for the CSV export (no extension). */
  csvFilename?: string
  /** Optional caption above the table body (e.g. "Showing N of M …"). */
  caption?: React.ReactNode
  /** Cap the table body height and give it its own scroll region. `null` opts out. */
  maxBodyHeight?: string | null
}

const densityRowClass: Record<Density, string> = {
  compact: 'px-2.5 py-1.5',
  comfortable: 'px-2.5 py-3',
}

const csvCell = (value: unknown): string => {
  if (value === null || value === undefined) return ''
  const str = String(value)
  if (/[",\r\n]/.test(str)) return `"${str.replace(/"/g, '""')}"`
  return str
}

function exportRowValues<T>(table: Table<T>): string[][] {
  const columns = table
    .getVisibleLeafColumns()
    .filter((col) => (col.columnDef.meta as { exportable?: boolean } | undefined)?.exportable !== false)

  const header = columns.map((col) => {
    const meta = col.columnDef.meta as { headerLabel?: string } | undefined
    if (meta?.headerLabel) return meta.headerLabel
    const raw = col.columnDef.header
    return typeof raw === 'string' ? raw : col.id
  })

  const rows = table.getRowModel().rows.map((row) =>
    columns.map((col) => {
      const meta = col.columnDef.meta as { exportValue?: (original: T) => unknown } | undefined
      const original = row.original
      if (meta?.exportValue) return String(meta.exportValue(original) ?? '')
      const cell = row.getAllCells().find((c) => c.column.id === col.id)
      return String(cell?.getValue() ?? '')
    }),
  )

  return [header, ...rows]
}

function downloadCsv(filename: string, matrix: string[][]) {
  const csv = matrix.map((row) => row.map(csvCell).join(',')).join('\r\n')
  const blob = new Blob(['﻿' + csv], { type: 'text/csv;charset=utf-8;' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = filename
  link.click()
  URL.revokeObjectURL(url)
}

function useDismissable(open: boolean, onClose: () => void) {
  const ref = useRef<HTMLDivElement | null>(null)
  useEffect(() => {
    if (!open) return
    const clickAway = (e: MouseEvent) => {
      if (!ref.current) return
      if (!ref.current.contains(e.target as Node)) onClose()
    }
    const escape = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('mousedown', clickAway)
    document.addEventListener('keydown', escape)
    return () => {
      document.removeEventListener('mousedown', clickAway)
      document.removeEventListener('keydown', escape)
    }
  }, [open, onClose])
  return ref
}

/** Read + write the per-table layout to localStorage. Never throws. */
function loadLayout(tableKey: string): StoredLayout | null {
  if (typeof window === 'undefined') return null
  try {
    const raw = window.localStorage.getItem(`advanced-data-table:${tableKey}:v1`)
    if (!raw) return null
    const parsed = JSON.parse(raw) as StoredLayout
    if (parsed?.v !== 1) return null
    return parsed
  } catch {
    return null
  }
}

function saveLayout(tableKey: string, layout: Omit<StoredLayout, 'v'>) {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.setItem(
      `advanced-data-table:${tableKey}:v1`,
      JSON.stringify({ v: 1, ...layout }),
    )
  } catch {
    // storage unavailable / quota — non-fatal.
  }
}

function clearLayout(tableKey: string) {
  if (typeof window === 'undefined') return
  try {
    window.localStorage.removeItem(`advanced-data-table:${tableKey}:v1`)
  } catch {
    // non-fatal.
  }
}

/**
 * Sticky offset for a pinned column. Left-pinned columns stack from the left
 * (getStart), right-pinned from the right (getAfter). Non-pinned returns null.
 */
function pinnedStyle<T>(column: Column<T, unknown>): React.CSSProperties | undefined {
  const side = column.getIsPinned()
  if (!side) return undefined
  if (side === 'left') {
    return {
      position: 'sticky',
      left: `${column.getStart('left')}px`,
      zIndex: 2,
      background: 'white',
    }
  }
  return {
    position: 'sticky',
    right: `${column.getAfter('right')}px`,
    zIndex: 2,
    background: 'white',
  }
}

/** Sortable/draggable header cell. Wraps a <th> so @dnd-kit can move it. */
function SortableHeader<T>({
  header,
  density,
  children,
}: {
  header: ReturnType<Table<T>['getHeaderGroups']>[number]['headers'][number]
  density: Density
  children: React.ReactNode
}) {
  const canReorder = header.column.getCanSort() !== undefined // always true; kept for future gating
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: header.column.id,
    disabled: !canReorder,
  })

  const pinned = pinnedStyle(header.column)
  const style: React.CSSProperties = {
    transform: CSS.Translate.toString(transform),
    transition,
    opacity: isDragging ? 0.65 : 1,
    width: header.getSize(),
    minWidth: header.getSize(),
    maxWidth: header.getSize(),
    ...pinned,
  }

  return (
    <th
      ref={setNodeRef}
      style={style}
      className={`${densityRowClass[density]} relative select-none ${isDragging ? 'z-10' : ''}`}
    >
      <div className="flex items-center gap-1">
        {/* Drag grip — the whole label acts as the drag handle. */}
        <span {...attributes} {...listeners} className="flex-1 cursor-grab active:cursor-grabbing">
          {children}
        </span>
      </div>

      {header.column.getCanResize() ? (
        <div
          onMouseDown={header.getResizeHandler()}
          onTouchStart={header.getResizeHandler()}
          className={`absolute right-0 top-0 h-full w-1.5 cursor-col-resize select-none touch-none hover:bg-sky-400/60 ${
            header.column.getIsResizing() ? 'bg-sky-500' : 'bg-transparent'
          }`}
          aria-hidden
        />
      ) : null}
    </th>
  )
}

export default function AdvancedDataTable<T>({
  tableKey,
  columns,
  data,
  search,
  filterToggle,
  filterPanel,
  toolbarActions,
  inlineFormRow,
  emptyState,
  initialHiddenColumns,
  initialDensity = 'compact',
  initialPageSize = 25,
  csvFilename,
  caption,
  maxBodyHeight = '70vh',
}: AdvancedDataTableProps<T>) {
  const defaultOrder = useMemo(
    () => columns.map((c) => c.id!).filter(Boolean) as string[],
    [columns],
  )
  const defaultVisibility = useMemo<VisibilityState>(() => {
    if (!initialHiddenColumns?.length) return {}
    return Object.fromEntries(initialHiddenColumns.map((id) => [id, false]))
  }, [initialHiddenColumns])

  // Load persisted layout once. Never refreshes — only the user's changes
  // update it after mount (and back to localStorage).
  const persisted = useMemo(() => loadLayout(tableKey), [tableKey])

  const [sorting, setSorting] = useState<SortingState>([])
  const [columnOrder, setColumnOrder] = useState<ColumnOrderState>(
    () => persisted?.columnOrder ?? defaultOrder,
  )
  const [columnVisibility, setColumnVisibility] = useState<VisibilityState>(
    () => persisted?.columnVisibility ?? defaultVisibility,
  )
  const [columnSizing, setColumnSizing] = useState<ColumnSizingState>(
    () => persisted?.columnSizing ?? {},
  )
  const [columnPinning, setColumnPinning] = useState<ColumnPinningState>(
    () => persisted?.columnPinning ?? { left: [], right: [] },
  )
  const [density, setDensity] = useState<Density>(persisted?.density ?? initialDensity)
  const [openMenu, setOpenMenu] = useState<null | 'columns' | 'density' | 'export'>(null)
  /** Which cell is currently in edit mode. `null` = read-only view. */
  const [editing, setEditing] = useState<{ rowId: string; columnId: string } | null>(null)

  // Persist any layout change back to localStorage.
  useEffect(() => {
    saveLayout(tableKey, { columnOrder, columnVisibility, columnSizing, columnPinning, density })
  }, [tableKey, columnOrder, columnVisibility, columnSizing, columnPinning, density])

  // If the column set changes (definitions added/removed), fold new columns
  // into the order so they still render.
  useEffect(() => {
    setColumnOrder((cur) => {
      const known = new Set(cur)
      const additions = defaultOrder.filter((id) => !known.has(id))
      const filtered = cur.filter((id) => defaultOrder.includes(id))
      return additions.length ? [...filtered, ...additions] : filtered
    })
  }, [defaultOrder])

  const columnsMenuRef = useDismissable(openMenu === 'columns', () => setOpenMenu(null))
  const densityMenuRef = useDismissable(openMenu === 'density', () => setOpenMenu(null))
  const exportMenuRef = useDismissable(openMenu === 'export', () => setOpenMenu(null))

  const table = useReactTable<T>({
    data,
    columns,
    state: {
      sorting,
      columnVisibility,
      columnOrder,
      columnSizing,
      columnPinning,
      pagination: { pageIndex: 0, pageSize: initialPageSize },
    },
    onSortingChange: setSorting,
    onColumnVisibilityChange: setColumnVisibility,
    onColumnOrderChange: setColumnOrder,
    onColumnSizingChange: setColumnSizing,
    onColumnPinningChange: setColumnPinning,
    columnResizeMode: 'onChange',
    enableColumnResizing: true,
    enableColumnPinning: true,
    defaultColumn: { size: 160, minSize: 60, maxSize: 800 },
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
  })

  useEffect(() => {
    table.setPageIndex(0)
  }, [data, table])

  const pageCount = table.getPageCount()
  const pageIndex = table.getState().pagination.pageIndex
  const pageSize = table.getState().pagination.pageSize
  const visibleRows = table.getRowModel().rows
  const totalRows = table.getPreFilteredRowModel().rows.length
  const visibleColumnCount = table.getVisibleLeafColumns().length

  const runExport = () => {
    const matrix = exportRowValues(table)
    const stamp = new Date().toISOString().replace(/[:.]/g, '-')
    downloadCsv(`${csvFilename || tableKey}-${stamp}.csv`, matrix)
    setOpenMenu(null)
  }

  const resetLayout = () => {
    clearLayout(tableKey)
    setColumnOrder(defaultOrder)
    setColumnVisibility(defaultVisibility)
    setColumnSizing({})
    setColumnPinning({ left: [], right: [] })
    setDensity(initialDensity)
    setOpenMenu(null)
  }

  /**
   * Advance / exit the currently-editing cell. `right` wraps to the first
   * editable column in the next row (Excel-style Tab); `left` wraps back.
   * `down`/`up` stay in the same column. `none` clears editing in place.
   * Cells with only `editByPicker` are skipped for `right`/`left`; they
   * remain click-only until the user Tabs specifically onto them via `down`.
   *
   * `source` = the cell whose editor is invoking finish. If editing has since
   * moved elsewhere (user clicked into another cell during a slow save),
   * skip the advance so we don't clobber the user's new focus.
   */
  const advanceEditing = (direction: EditNav = 'none', source?: { rowId: string; columnId: string }) => {
    setEditing((cur) => {
      if (!cur) return null
      if (source && (cur.rowId !== source.rowId || cur.columnId !== source.columnId)) return cur
      if (direction === 'none') return null
      const rows = table.getRowModel().rows
      const cols = table
        .getVisibleLeafColumns()
        .filter((c) => {
          const m = c.columnDef.meta as { editCell?: unknown; editByPicker?: unknown } | undefined
          return Boolean(m?.editCell || m?.editByPicker)
        })
      const rowIndex = rows.findIndex((r) => r.id === cur.rowId)
      const colIndex = cols.findIndex((c) => c.id === cur.columnId)
      if (rowIndex === -1 || colIndex === -1) return null

      let nextRow = rowIndex
      let nextCol = colIndex

      if (direction === 'right') {
        // Skip picker-only cells so Tab keeps a fast rhythm through inline editors.
        for (let step = 1; step <= cols.length * rows.length; step += 1) {
          nextCol += 1
          if (nextCol >= cols.length) {
            nextCol = 0
            nextRow += 1
            if (nextRow >= rows.length) return null
          }
          const m = cols[nextCol].columnDef.meta as { editCell?: unknown } | undefined
          if (m?.editCell) break
        }
      } else if (direction === 'left') {
        for (let step = 1; step <= cols.length * rows.length; step += 1) {
          nextCol -= 1
          if (nextCol < 0) {
            nextCol = cols.length - 1
            nextRow -= 1
            if (nextRow < 0) return null
          }
          const m = cols[nextCol].columnDef.meta as { editCell?: unknown } | undefined
          if (m?.editCell) break
        }
      } else if (direction === 'down') {
        nextRow += 1
        if (nextRow >= rows.length) return null
      } else if (direction === 'up') {
        nextRow -= 1
        if (nextRow < 0) return null
      }

      return { rowId: rows[nextRow].id, columnId: cols[nextCol].id }
    })
  }

  const cancelEditing = (source?: { rowId: string; columnId: string }) => {
    setEditing((cur) => {
      if (!cur) return null
      if (source && (cur.rowId !== source.rowId || cur.columnId !== source.columnId)) return cur
      return null
    })
  }

  // @dnd-kit sensors: pointer for mouse/touch, keyboard for a11y.
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 4 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event
    if (!over || active.id === over.id) return
    setColumnOrder((cur) => {
      const from = cur.indexOf(String(active.id))
      const to = cur.indexOf(String(over.id))
      if (from === -1 || to === -1) return cur
      const next = [...cur]
      const [moved] = next.splice(from, 1)
      next.splice(to, 0, moved)
      return next
    })
  }

  const cyclePin = (col: Column<T, unknown>) => {
    const cur = col.getIsPinned()
    // Cycle: none → left → none. (Right-pin skipped in menu; TanStack still supports it.)
    col.pin(cur === 'left' ? false : 'left')
  }

  return (
    <div>
      {/* ===== compacted single-line toolbar ===== */}
      <div className="flex flex-wrap items-center gap-2">
        {search ? (
          <label className="flex min-w-[220px] flex-1 items-center gap-2 rounded-xl border border-slate-200 bg-slate-50 px-3 py-1.5">
            <FiSearch className="h-3.5 w-3.5 shrink-0 text-slate-400" />
            <input
              value={search.value}
              onChange={(e) => search.onChange(e.target.value)}
              placeholder={search.placeholder || 'Search…'}
              className="w-full bg-transparent text-[12.5px] text-slate-950 outline-none"
            />
          </label>
        ) : null}

        {filterToggle}

        {/* Columns menu with visibility + pin toggle */}
        <div ref={columnsMenuRef} className="relative">
          <button
            type="button"
            onClick={() => setOpenMenu((cur) => (cur === 'columns' ? null : 'columns'))}
            aria-expanded={openMenu === 'columns'}
            className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-600 transition hover:bg-slate-50"
            title="Show/hide + pin columns"
          >
            <FiColumns className="h-3.5 w-3.5" />
            Columns
          </button>
          {openMenu === 'columns' ? (
            <div className="absolute right-0 z-20 mt-1.5 w-64 rounded-xl border border-slate-200 bg-white p-2 shadow-lg">
              {table.getAllLeafColumns().map((col) => {
                if ((col.columnDef.meta as { hideable?: boolean } | undefined)?.hideable === false) {
                  return null
                }
                const meta = col.columnDef.meta as { headerLabel?: string } | undefined
                const label =
                  meta?.headerLabel ||
                  (typeof col.columnDef.header === 'string' ? col.columnDef.header : col.id)
                const pinned = col.getIsPinned()
                return (
                  <div
                    key={col.id}
                    className="flex items-center gap-2 rounded-lg px-2 py-1.5 text-[12.5px] text-slate-700 hover:bg-slate-50"
                  >
                    <label className="flex flex-1 cursor-pointer items-center gap-2">
                      <input
                        type="checkbox"
                        checked={col.getIsVisible()}
                        onChange={col.getToggleVisibilityHandler()}
                        className="h-3.5 w-3.5 accent-[#412d15]"
                      />
                      {label}
                    </label>
                    <button
                      type="button"
                      onClick={() => cyclePin(col)}
                      title={pinned === 'left' ? 'Unpin' : 'Pin to left'}
                      aria-pressed={pinned === 'left'}
                      className={`rounded-md px-1.5 py-0.5 text-[10px] font-semibold transition ${
                        pinned === 'left'
                          ? 'bg-[#1f150c] text-white'
                          : 'border border-slate-200 bg-white text-slate-500 hover:bg-slate-100'
                      }`}
                    >
                      📌
                    </button>
                  </div>
                )
              })}
              <button
                type="button"
                onClick={resetLayout}
                className="mt-1 flex w-full items-center gap-2 rounded-lg border-t border-slate-100 pt-2 pl-2 pr-2 pb-1 text-left text-[11.5px] text-slate-500 transition hover:text-slate-800"
                title="Reset column order, widths, visibility, pinning, and density to defaults"
              >
                <FiRotateCcw className="h-3 w-3" />
                Reset layout
              </button>
            </div>
          ) : null}
        </div>

        {/* Density menu */}
        <div ref={densityMenuRef} className="relative">
          <button
            type="button"
            onClick={() => setOpenMenu((cur) => (cur === 'density' ? null : 'density'))}
            aria-expanded={openMenu === 'density'}
            className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-600 transition hover:bg-slate-50"
            title="Row density"
          >
            <FiSliders className="h-3.5 w-3.5" />
            Density
          </button>
          {openMenu === 'density' ? (
            <div className="absolute right-0 z-20 mt-1.5 w-44 rounded-xl border border-slate-200 bg-white p-2 shadow-lg">
              {(['compact', 'comfortable'] as const).map((d) => (
                <button
                  key={d}
                  type="button"
                  onClick={() => {
                    setDensity(d)
                    setOpenMenu(null)
                  }}
                  className={`flex w-full items-center justify-between rounded-lg px-2 py-1.5 text-left text-[12.5px] transition hover:bg-slate-50 ${
                    density === d ? 'font-semibold text-[#1f150c]' : 'text-slate-600'
                  }`}
                >
                  <span className="capitalize">{d}</span>
                  {density === d ? <span className="text-[10px] text-emerald-600">●</span> : null}
                </button>
              ))}
            </div>
          ) : null}
        </div>

        {/* Export menu */}
        <div ref={exportMenuRef} className="relative">
          <button
            type="button"
            onClick={() => setOpenMenu((cur) => (cur === 'export' ? null : 'export'))}
            aria-expanded={openMenu === 'export'}
            className="inline-flex items-center gap-1.5 rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[12px] font-semibold text-slate-600 transition hover:bg-slate-50"
            title="Export the current view"
          >
            <FiDownload className="h-3.5 w-3.5" />
            Export
          </button>
          {openMenu === 'export' ? (
            <div className="absolute right-0 z-20 mt-1.5 w-56 rounded-xl border border-slate-200 bg-white p-2 shadow-lg">
              <button
                type="button"
                onClick={runExport}
                className="w-full rounded-lg px-2 py-1.5 text-left text-[12.5px] text-slate-700 transition hover:bg-slate-50"
              >
                CSV — current view
                <span className="ml-1 text-[10.5px] text-slate-400">(visible columns · filtered rows)</span>
              </button>
              <p className="mt-1 px-2 py-1 text-[10.5px] text-slate-400">
                Full-record + Excel exports arrive in a later phase.
              </p>
            </div>
          ) : null}
        </div>

        {toolbarActions}

        {/* Pagination controls */}
        <div className="ml-auto flex items-center gap-2 text-[12px] text-slate-600">
          <select
            value={pageSize}
            onChange={(e) => table.setPageSize(Number(e.target.value))}
            aria-label="Rows per page"
            className="rounded-xl border border-slate-200 bg-white px-2 py-1 text-[12px] outline-none transition focus:border-[#412d15]"
          >
            {[10, 25, 50, 100].map((n) => (
              <option key={n} value={n}>
                {n} / page
              </option>
            ))}
          </select>
          <div className="flex items-center gap-1">
            <button
              type="button"
              onClick={() => table.previousPage()}
              disabled={!table.getCanPreviousPage()}
              className="rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-[12px] transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40"
            >
              ‹
            </button>
            <span className="tabular-nums text-[11.5px]">
              {pageCount === 0 ? 0 : pageIndex + 1} / {pageCount}
            </span>
            <button
              type="button"
              onClick={() => table.nextPage()}
              disabled={!table.getCanNextPage()}
              className="rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-[12px] transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-40"
            >
              ›
            </button>
          </div>
        </div>
      </div>

      {filterPanel}

      {caption ? <div className="mt-2.5">{caption}</div> : null}

      <div
        className="mt-3 overflow-auto"
        style={maxBodyHeight ? { maxHeight: maxBodyHeight } : undefined}
      >
        <DndContext sensors={sensors} collisionDetection={closestCenter} onDragEnd={handleDragEnd}>
          <table
            className="text-[13px] text-slate-700"
            // `width: 100%` + `min-width: totalSize` = the table fills the
            // container when there's extra room (percentages in <colgroup>
            // scale all columns proportionally); overflows and scrolls when
            // the sum of user-set widths exceeds the container.
            style={{ width: '100%', minWidth: `${table.getTotalSize()}px`, tableLayout: 'fixed' }}
          >
            <colgroup>
              {table.getVisibleLeafColumns().map((col) => (
                <col
                  key={col.id}
                  style={{ width: `${(col.getSize() / table.getTotalSize()) * 100}%` }}
                />
              ))}
            </colgroup>
            <thead className="sticky top-0 z-10 border-b border-dashed border-slate-300 bg-white text-left font-mono text-[9px] font-bold uppercase tracking-[0.18em] text-slate-400 shadow-[0_1px_0_0_rgba(148,163,184,0.2)]">
              {table.getHeaderGroups().map((group) => (
                <SortableContext
                  key={group.id}
                  items={group.headers.map((h) => h.column.id)}
                  strategy={horizontalListSortingStrategy}
                >
                  <tr>
                    {group.headers.map((header) => {
                      const canSort = header.column.getCanSort()
                      const sortDir = header.column.getIsSorted()
                      return (
                        <SortableHeader key={header.id} header={header} density={density}>
                          {header.isPlaceholder
                            ? null
                            : (
                              <span
                                className={`inline-flex items-center gap-1 ${canSort ? 'cursor-pointer' : ''}`}
                                onClick={(e) => {
                                  // Sort on click but don't trip the drag listener
                                  // when the user's actually clicking to sort.
                                  if (!canSort) return
                                  e.stopPropagation()
                                  header.column.getToggleSortingHandler()?.(e)
                                }}
                                onPointerDown={(e) => e.stopPropagation()}
                              >
                                {flexRender(header.column.columnDef.header, header.getContext())}
                                {canSort ? (
                                  sortDir === 'asc' ? (
                                    <FiChevronUp className="h-3 w-3 text-slate-400" />
                                  ) : sortDir === 'desc' ? (
                                    <FiChevronDown className="h-3 w-3 text-slate-400" />
                                  ) : (
                                    <FiChevronDown className="h-3 w-3 text-slate-200" />
                                  )
                                ) : null}
                              </span>
                            )}
                        </SortableHeader>
                      )
                    })}
                  </tr>
                </SortableContext>
              ))}
            </thead>
            <tbody className="divide-y divide-dashed divide-slate-200">
              {inlineFormRow ? (
                <tr className="bg-slate-50/60">
                  <td colSpan={visibleColumnCount} className="px-2.5 py-2.5">
                    {inlineFormRow}
                  </td>
                </tr>
              ) : null}

              {!visibleRows.length && emptyState ? (
                <tr>
                  <td
                    colSpan={visibleColumnCount}
                    className={`${densityRowClass[density]} text-center text-[12px] text-slate-400`}
                  >
                    {emptyState}
                  </td>
                </tr>
              ) : null}

              {visibleRows.map((row) => (
                <tr key={row.id} className="align-top">
                  {row.getVisibleCells().map((cell) => {
                    const pinned = pinnedStyle(cell.column)
                    const meta = cell.column.columnDef.meta as
                      | {
                          editCell?: (props: EditCellProps<T>) => React.ReactNode
                          editByPicker?: (row: T) => void
                        }
                      | undefined
                    const editable = Boolean(meta?.editCell || meta?.editByPicker)
                    const isEditing =
                      editing?.rowId === row.id && editing?.columnId === cell.column.id
                    return (
                      <td
                        key={cell.id}
                        className={`${densityRowClass[density]} ${editable && !isEditing ? 'cursor-pointer transition-colors hover:bg-slate-50/70' : ''} ${isEditing ? 'bg-sky-50/40 ring-1 ring-inset ring-sky-300' : ''}`}
                        style={pinned}
                        onClick={() => {
                          if (!editable || isEditing) return
                          if (meta?.editByPicker) {
                            meta.editByPicker(row.original)
                            return
                          }
                          setEditing({ rowId: row.id, columnId: cell.column.id })
                        }}
                      >
                        {isEditing && meta?.editCell
                          ? meta.editCell({
                              row: row.original,
                              finish: (dir) =>
                                advanceEditing(dir ?? 'none', {
                                  rowId: row.id,
                                  columnId: cell.column.id,
                                }),
                              cancel: () =>
                                cancelEditing({ rowId: row.id, columnId: cell.column.id }),
                            })
                          : flexRender(cell.column.columnDef.cell, cell.getContext())}
                      </td>
                    )
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </DndContext>
      </div>

      <p className="mt-2 text-[10.5px] tabular-nums text-slate-400">
        Table key: <span className="font-mono">{tableKey}</span> · Showing {visibleRows.length} of {totalRows} row{totalRows === 1 ? '' : 's'}
      </p>
    </div>
  )
}
