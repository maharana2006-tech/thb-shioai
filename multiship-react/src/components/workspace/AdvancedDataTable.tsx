import { Fragment, useEffect, useMemo, useState } from 'react'
import { useDismissable } from '../../hooks/useDismissable'
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
  FiChevronLeft,
  FiChevronRight,
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
  /**
   * When provided, rows become expandable: clicking a non-interactive cell
   * toggles a full-width sub-row that renders this content for that row.
   * Buttons/inputs/selects/links inside a row do NOT toggle it. Inert (no
   * expand behavior) when omitted, so existing tables are unaffected.
   */
  renderExpanded?: (row: T) => React.ReactNode
  /** Fired when a row is expanded (not collapsed) — lets the parent lazy-load
   *  the expanded content's data. */
  onRowExpand?: (row: T) => void
  /** Row (by getRowId) to show expanded when the table first renders. */
  initialExpandedId?: string | null
  /** Clicking a row (outside its buttons and links) — e.g. to open its own page. */
  onRowClick?: (row: T) => void
  /** Stable row identity (e.g. the record id). Without it rows are keyed by position,
   *  so removing a row moves an open expansion onto the next record. */
  getRowId?: (row: T, index: number) => string
  initialHiddenColumns?: string[]
  /** Columns shown whatever the saved layout says, while the parent passes them
   *  (e.g. the fields that have errors, under a "Needs attention" filter). The
   *  operator's own show/hide choices are kept and come back once it stops. */
  forceVisibleColumns?: string[]
  initialDensity?: Density
  initialPageSize?: number
  /** Filename base for the CSV export (no extension). */
  csvFilename?: string
  /**
   * Override the built-in CSV export. Provide this on server-side tables where
   * the visible data is only one page: the callback should fetch every filtered
   * row from the backend and trigger the download. When set, the Export menu
   * item calls this instead of building CSV from the current table view.
   */
  onExport?: () => void | Promise<void>
  /** Optional caption above the table body (e.g. "Showing N of M …"). */
  caption?: React.ReactNode
  /** Cap the table body height and give it its own scroll region. `null` opts out. */
  maxBodyHeight?: string | null

  // ===== Server-side (manual) mode =====
  // When any of these are set, the corresponding row model runs on the server
  // and the table only renders whatever data is fed in for the current page.
  /** true → parent controls pagination (opt-in server-side pagination). */
  manualPagination?: boolean
  /** true → parent controls sort order (opt-in server-side sorting). */
  manualSorting?: boolean
  /** Controlled sort state — required alongside manualSorting. */
  sorting?: SortingState
  /** Fired when the user clicks a header to sort. Server should re-fetch. */
  onSortingChange?: (next: SortingState) => void
  /** 0-based current page — required alongside manualPagination. */
  pageIndex?: number
  /** Rows per page — required alongside manualPagination. */
  pageSize?: number
  /** Total pages known to the server — required alongside manualPagination. */
  pageCount?: number
  /** Fired when the user clicks prev/next or changes page size. */
  onPaginationChange?: (state: { pageIndex: number; pageSize: number }) => void
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
  const exportable = (col: { columnDef: { meta?: unknown } }) =>
    (col.columnDef.meta as { exportable?: boolean } | undefined)?.exportable !== false
  const visible = table.getVisibleLeafColumns().filter(exportable)
  // Columns folded into another cell are hidden but still carry data the
  // spreadsheet needs (client, created, generated…) — append them so an
  // export is never thinner than what the screen shows.
  const foldedAway = table
    .getAllLeafColumns()
    .filter((col) => !col.getIsVisible() && exportable(col)
      && (col.columnDef.meta as { exportAlways?: boolean } | undefined)?.exportAlways === true)
  const columns = [...visible, ...foldedAway]

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
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: header.column.id,
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
          className={`absolute right-0 top-0 h-full w-1.5 cursor-col-resize select-none touch-none hover:bg-[#cdbf9f] ${
            header.column.getIsResizing() ? 'bg-[#412d15]' : 'bg-transparent'
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
  renderExpanded,
  onRowExpand,
  initialExpandedId = null,
  onRowClick,
  getRowId,
  initialHiddenColumns,
  forceVisibleColumns,
  initialDensity = 'compact',
  initialPageSize = 25,
  csvFilename,
  onExport,
  caption,
  maxBodyHeight = '70vh',
  manualPagination = false,
  manualSorting = false,
  sorting: sortingProp,
  onSortingChange: onSortingChangeProp,
  pageIndex: pageIndexProp,
  pageSize: pageSizeProp,
  pageCount: pageCountProp,
  onPaginationChange,
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

  // Which row's expansion sub-row is open (id), when renderExpanded is set.
  const [expandedId, setExpandedId] = useState<string | null>(initialExpandedId)
  const [internalSorting, setInternalSorting] = useState<SortingState>([])
  const [internalPageIndex, setInternalPageIndex] = useState(0)
  const [internalPageSize, setInternalPageSize] = useState(initialPageSize)
  // In manual mode, the parent owns sort + pagination; in default mode we
  // keep our own state so existing pages don't have to change anything.
  const sorting = manualSorting && sortingProp ? sortingProp : internalSorting
  const currentPageIndex = manualPagination ? pageIndexProp ?? 0 : internalPageIndex
  const currentPageSize = manualPagination ? pageSizeProp ?? initialPageSize : internalPageSize
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
  const [openMenu, setOpenMenu] = useState<null | 'columns' | 'density'>(null)
  /**
   * Column-reorder draft (2026-09-14 operator ask). The Columns menu
   * lets the operator move columns up/down with arrow buttons; changes
   * accumulate in this draft until they click Save (matches the
   * operator's explicit preference for a staged commit rather than
   * live-apply, unlike the visibility and pin toggles above). Null =
   * no unsaved reorder; Save clears back to null after applying to
   * {@link columnOrder}.
   */
  const [draftColumnOrder, setDraftColumnOrder] = useState<string[] | null>(null)
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
      if (!additions.length) return filtered
      // Place a newly-defined column where it was DEFINED rather than at the
      // end: a saved layout that predates the column used to exile it to the
      // right (a Date column defined after File surfaced between Rows and
      // Actions). Columns the user moved themselves keep their position.
      const next = [...filtered]
      for (const id of additions) {
        const want = defaultOrder.indexOf(id)
        const at = next.findIndex((existing) => defaultOrder.indexOf(existing) > want)
        next.splice(at < 0 ? next.length : at, 0, id)
      }
      return next
    })
  }, [defaultOrder])

  const columnsMenuRef = useDismissable(openMenu === 'columns', () => {
    // Dropping any staged reorder on dismiss keeps the semantics
    // predictable: click-away = cancel, Save = commit (2026-09-14).
    setDraftColumnOrder(null)
    setOpenMenu(null)
  })
  const densityMenuRef = useDismissable(openMenu === 'density', () => setOpenMenu(null))

  /**
   * Select column is a fixed viewport gutter (2026-09-14 operator ask):
   * when the operator pins another column left, the checkbox must NOT
   * be pushed behind it. Special-case the id: any table whose columns
   * include one with id === 'select' gets it force-pinned left AND
   * placed first within the pinned-left group. State the operator
   * mutates via 📌 / drag continues to work normally; we just derive
   * an "effective" view on top so the render is always guttered.
   */
  const hasSelectColumn = useMemo(
    () => columns.some((c) => (c as { id?: string }).id === 'select'),
    [columns],
  )
  const effectiveColumnPinning: ColumnPinningState = useMemo(() => {
    if (!hasSelectColumn) return columnPinning
    const left = (columnPinning.left ?? []).filter((id) => id !== 'select')
    const right = (columnPinning.right ?? []).filter((id) => id !== 'select')
    return { left: ['select', ...left], right }
  }, [columnPinning, hasSelectColumn])
  const effectiveColumnOrder: ColumnOrderState = useMemo(() => {
    if (!hasSelectColumn || columnOrder.length === 0) return columnOrder
    if (columnOrder[0] === 'select') return columnOrder
    return ['select', ...columnOrder.filter((id) => id !== 'select')]
  }, [columnOrder, hasSelectColumn])

  const forcedKey = forceVisibleColumns?.join('|') ?? ''
  const effectiveVisibility = useMemo<VisibilityState>(
    () => (forcedKey ? { ...columnVisibility, ...Object.fromEntries(forcedKey.split('|').map((id) => [id, true])) } : columnVisibility),
    [columnVisibility, forcedKey],
  )

  // eslint-disable-next-line react-hooks/incompatible-library -- TanStack Table's useReactTable() returns functions that cannot be memoized safely — library-level incompatibility with react-hooks analyzer, not a code issue
  const table = useReactTable<T>({
    data,
    columns,
    getRowId,
    state: {
      sorting,
      columnVisibility: effectiveVisibility,
      columnOrder: effectiveColumnOrder,
      columnSizing,
      columnPinning: effectiveColumnPinning,
      pagination: { pageIndex: currentPageIndex, pageSize: currentPageSize },
    },
    manualSorting,
    manualPagination,
    pageCount: manualPagination ? pageCountProp ?? -1 : undefined,
    onSortingChange: (updater) => {
      const next = typeof updater === 'function' ? updater(sorting) : updater
      if (manualSorting && onSortingChangeProp) onSortingChangeProp(next)
      else setInternalSorting(next)
    },
    onPaginationChange: (updater) => {
      const cur = { pageIndex: currentPageIndex, pageSize: currentPageSize }
      const next = typeof updater === 'function' ? updater(cur) : updater
      if (manualPagination && onPaginationChange) onPaginationChange(next)
      else {
        setInternalPageIndex(next.pageIndex)
        setInternalPageSize(next.pageSize)
      }
    },
    onColumnVisibilityChange: setColumnVisibility,
    onColumnOrderChange: setColumnOrder,
    onColumnSizingChange: setColumnSizing,
    onColumnPinningChange: setColumnPinning,
    columnResizeMode: 'onChange',
    enableColumnResizing: true,
    enableColumnPinning: true,
    defaultColumn: { size: 160, minSize: 60, maxSize: 800 },
    getCoreRowModel: getCoreRowModel(),
    // Only wire the client-side row models when not in manual mode: in manual
    // mode the server has already sorted / paginated the data we render.
    ...(manualSorting ? {} : { getSortedRowModel: getSortedRowModel() }),
    ...(manualPagination ? {} : { getPaginationRowModel: getPaginationRowModel() }),
  })

  // Client-side mode: reset to page 1 when the data set changes (e.g. filters
  // in the parent narrow rows). Skip in manual mode — the parent controls the
  // page, and this reset would otherwise fight it and cause an infinite loop.
  useEffect(() => {
    if (manualPagination) return
    table.setPageIndex(0)
  }, [data, table, manualPagination])

  const pageCount = table.getPageCount()
  const pageIndex = table.getState().pagination.pageIndex
  const pageSize = table.getState().pagination.pageSize
  const visibleRows = table.getRowModel().rows
  const totalRows = table.getPreFilteredRowModel().rows.length
  const visibleColumnCount = table.getVisibleLeafColumns().length

  const runExport = () => {
    // Server-side export: the parent fetches every filtered row and downloads it.
    if (onExport) { void onExport(); return }
    const stamp = new Date().toISOString().replace(/[:.]/g, '-')
    downloadCsv(`${csvFilename || tableKey}-${stamp}.csv`, exportRowValues(table))
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
          <label className="flex min-w-[220px] flex-1 items-center gap-2 rounded-lg border border-[#e3d9c4] bg-[#fcfaf5] px-3 py-1.5 transition focus-within:border-[#412d15]">
            <FiSearch className="h-3.5 w-3.5 shrink-0 text-[#b6a684]" />
            <input
              value={search.value}
              onChange={(e) => search.onChange(e.target.value)}
              placeholder={search.placeholder || 'Search…'}
              className="w-full bg-transparent text-[12.5px] text-slate-950 outline-none"
            />
          </label>
        ) : null}

        {filterToggle}

        {/* When there's no search box to hold the left edge, push the menu
            cluster to the right so their right-aligned dropdowns stay on-screen. */}
        {!search ? <div className="flex-1" /> : null}

        {/* Columns menu with visibility + pin toggle */}
        <div ref={columnsMenuRef} className="relative">
          <button
            type="button"
            onClick={() => setOpenMenu((cur) => (cur === 'columns' ? null : 'columns'))}
            aria-expanded={openMenu === 'columns'}
            className="inline-flex items-center gap-1.5 rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
            title="Show/hide + pin columns"
          >
            <FiColumns className="h-3.5 w-3.5" />
            Columns
          </button>
          {openMenu === 'columns' ? (() => {
            /*
             * Column-reorder + visibility + pin menu (2026-09-14).
             *
             * Order rendered = draftColumnOrder if the operator has
             * staged a reorder, otherwise the committed columnOrder.
             * Pinned-left columns always sort to the top per operator
             * preference: reorder within pin group only — an unpinned
             * column can't cross above a pinned one because the table
             * would render pinning first anyway (misleading).
             */
            const leafCols = table.getAllLeafColumns()
            const idToCol = new Map(leafCols.map((c) => [c.id, c] as const))
            const workingIds = (draftColumnOrder ?? leafCols.map((c) => c.id))
              .filter((id) => idToCol.has(id))
            // Pinned-first sort. Stable within each group (preserves
            // whatever order the draft/committed list already has).
            const pinnedIds = workingIds.filter((id) => idToCol.get(id)?.getIsPinned() === 'left')
            const unpinnedIds = workingIds.filter((id) => idToCol.get(id)?.getIsPinned() !== 'left')
            const orderedIds = [...pinnedIds, ...unpinnedIds]

            /** Return the pin-scoped neighbour indices for a given
             *  index inside the orderedIds array. Swap up = swap with
             *  the row above IF it's in the same pin group. */
            const move = (idx: number, direction: -1 | 1) => {
              const target = idx + direction
              if (target < 0 || target >= orderedIds.length) return
              const a = idToCol.get(orderedIds[idx])
              const b = idToCol.get(orderedIds[target])
              if (!a || !b) return
              // Same-pin-group check — moving pinned into unpinned or
              // vice versa isn't allowed here (change pin state via 📌).
              const aPinned = a.getIsPinned() === 'left'
              const bPinned = b.getIsPinned() === 'left'
              if (aPinned !== bPinned) return
              const next = [...orderedIds]
              const tmp = next[idx]
              next[idx] = next[target]
              next[target] = tmp
              setDraftColumnOrder(next)
            }

            const hasDraft = draftColumnOrder != null
            return (
              <div className="absolute right-0 z-20 mt-1.5 w-72 rounded-xl border border-[#e3d9c4] bg-white p-2 shadow-[0_12px_32px_rgba(31,21,12,0.12)]">
                {orderedIds.map((id, idx) => {
                  const col = idToCol.get(id)
                  if (!col) return null
                  if ((col.columnDef.meta as { hideable?: boolean } | undefined)?.hideable === false) {
                    return null
                  }
                  const meta = col.columnDef.meta as { headerLabel?: string } | undefined
                  const label =
                    meta?.headerLabel ||
                    (typeof col.columnDef.header === 'string' ? col.columnDef.header : col.id)
                  const pinned = col.getIsPinned()
                  // Arrow enable/disable: can't move past a pin-group
                  // boundary. Compute by peeking at neighbours.
                  const prev = idx > 0 ? idToCol.get(orderedIds[idx - 1]) : null
                  const next = idx < orderedIds.length - 1 ? idToCol.get(orderedIds[idx + 1]) : null
                  const isPinned = pinned === 'left'
                  const canUp = !!prev && ((prev.getIsPinned() === 'left') === isPinned)
                  const canDown = !!next && ((next.getIsPinned() === 'left') === isPinned)
                  return (
                    <div
                      key={col.id}
                      className="flex items-center gap-1.5 rounded-lg px-1.5 py-1 text-[13.5px] text-slate-700 hover:bg-slate-50"
                    >
                      {/* Up/down arrows scoped to the pin group. */}
                      <div className="flex flex-col">
                        <button
                          type="button"
                          onClick={() => move(idx, -1)}
                          disabled={!canUp}
                          aria-label={`Move ${label} up`}
                          className="rounded-sm p-0.5 text-slate-500 transition hover:bg-slate-100 hover:text-slate-800 disabled:cursor-not-allowed disabled:text-slate-200 disabled:hover:bg-transparent"
                        >
                          <FiChevronUp className="h-2.5 w-2.5" />
                        </button>
                        <button
                          type="button"
                          onClick={() => move(idx, 1)}
                          disabled={!canDown}
                          aria-label={`Move ${label} down`}
                          className="rounded-sm p-0.5 text-slate-500 transition hover:bg-slate-100 hover:text-slate-800 disabled:cursor-not-allowed disabled:text-slate-200 disabled:hover:bg-transparent"
                        >
                          <FiChevronDown className="h-2.5 w-2.5" />
                        </button>
                      </div>
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
                {/* Save / Cancel row — only shown when there's an
                    unsaved reorder. Save commits the draft to
                    columnOrder (which persists via the existing
                    saveLayout effect); Cancel discards. */}
                {hasDraft ? (
                  <div className="mt-1 flex items-center justify-end gap-1.5 border-t border-slate-100 pt-2">
                    <button
                      type="button"
                      onClick={() => setDraftColumnOrder(null)}
                      className="rounded-md border border-slate-200 bg-white px-2.5 py-1 text-[11.5px] font-semibold text-slate-600 transition hover:bg-slate-50"
                    >
                      Cancel
                    </button>
                    <button
                      type="button"
                      onClick={() => {
                        if (draftColumnOrder) setColumnOrder(draftColumnOrder)
                        setDraftColumnOrder(null)
                      }}
                      className="rounded-md bg-[#1f150c] px-2.5 py-1 text-[11.5px] font-semibold text-[#f4eede] transition hover:bg-[#33221a]"
                    >
                      Save order
                    </button>
                  </div>
                ) : null}
                <button
                  type="button"
                  onClick={() => { setDraftColumnOrder(null); resetLayout() }}
                  className="mt-1 flex w-full items-center gap-2 rounded-lg border-t border-slate-100 pt-2 pl-2 pr-2 pb-1 text-left text-[11.5px] text-slate-500 transition hover:text-slate-800"
                  title="Reset column order, widths, visibility, pinning, and density to defaults"
                >
                  <FiRotateCcw className="h-3 w-3" />
                  Reset layout
                </button>
              </div>
            )
          })() : null}
        </div>

        {/* Density menu */}
        <div ref={densityMenuRef} className="relative">
          <button
            type="button"
            onClick={() => setOpenMenu((cur) => (cur === 'density' ? null : 'density'))}
            aria-expanded={openMenu === 'density'}
            className="inline-flex items-center gap-1.5 rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
            title="Row density"
          >
            <FiSliders className="h-3.5 w-3.5" />
            Density
          </button>
          {openMenu === 'density' ? (
            <div className="absolute right-0 z-20 mt-1.5 w-44 rounded-xl border border-[#e3d9c4] bg-white p-2 shadow-[0_12px_32px_rgba(31,21,12,0.12)]">
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

        <button
          type="button"
          onClick={runExport}
          className="inline-flex items-center gap-1.5 rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
          title="Export the current view as CSV — visible columns, filtered rows"
        >
          <FiDownload className="h-3.5 w-3.5" />
          Export
        </button>

        {toolbarActions}

        {/* Pagination controls */}
        <div className="ml-auto flex items-center gap-2 text-[12px] text-[#5a4526]">
          <select
            value={pageSize}
            onChange={(e) => table.setPageSize(Number(e.target.value))}
            aria-label="Rows per page"
            className="rounded-lg border border-[#e3d9c4] bg-white px-2 py-1.5 text-[12px] font-semibold text-[#5a4526] outline-none transition hover:bg-[#faf7f0] focus:border-[#412d15]"
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
              aria-label="Previous page"
              className="inline-flex h-7 w-7 items-center justify-center rounded-lg border border-[#e3d9c4] bg-white text-[#5a4526] transition hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40"
            >
              <FiChevronLeft className="h-3.5 w-3.5" />
            </button>
            <span className="min-w-[3.5rem] text-center text-[11.5px] font-semibold tabular-nums">
              {pageCount === 0 ? 0 : pageIndex + 1} of {pageCount}
            </span>
            <button
              type="button"
              onClick={() => table.nextPage()}
              disabled={!table.getCanNextPage()}
              aria-label="Next page"
              className="inline-flex h-7 w-7 items-center justify-center rounded-lg border border-[#e3d9c4] bg-white text-[#5a4526] transition hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40"
            >
              <FiChevronRight className="h-3.5 w-3.5" />
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
            // 2026-09-13 operator ask — bumped base cell font-size from 13px
            // to 14.5px so tables read more comfortably. Per-cell renderers
            // that used explicit text-[12px] / text-[12.5px] were also
            // sweep-bumped one step (13 / 13.5) in the same commit;
            // anything not swept still inherits this base.
            className="text-[14.5px] text-[#3f3527]"
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
            <thead className="sticky top-0 z-10 bg-[#faf7f0] text-left text-[11.5px] font-semibold text-[#6b5c42] shadow-[inset_0_-1px_0_0_#e3d9c4]">
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
                                    <FiChevronUp className="h-3 w-3 text-[#412d15]" />
                                  ) : sortDir === 'desc' ? (
                                    <FiChevronDown className="h-3 w-3 text-[#412d15]" />
                                  ) : (
                                    <FiChevronDown className="h-3 w-3 text-[#dcd4c4]" />
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
                <tr>
                  <td colSpan={visibleColumnCount} className="p-0">
                    {/* Add-row: visually distinct band that spans the full
                        table width. Sky accent bar on the left + gradient
                        background make it read as a "new record" strip,
                        clearly separated from the read-only rows below. */}
                    <div className="border-y-2 border-sky-400 border-l-4 bg-gradient-to-r from-sky-50 via-white to-sky-50/40 px-4 py-3 shadow-sm">
                      {inlineFormRow}
                    </div>
                  </td>
                </tr>
              ) : null}

              {!visibleRows.length && emptyState ? (
                <tr>
                  <td
                    colSpan={visibleColumnCount}
                    className={`${densityRowClass[density]} text-center text-[12px] text-[#8a7a5a]`}
                  >
                    {emptyState}
                  </td>
                </tr>
              ) : null}

              {visibleRows.map((row) => (
                <Fragment key={row.id}>
                <tr
                  className={`align-top border-b border-[#f2ecdf] last:border-b-0 ${renderExpanded || onRowClick ? 'cursor-pointer hover:bg-[#fcfaf5]' : ''} ${
                    renderExpanded && expandedId === row.id ? 'bg-[#faf7f0]' : ''
                  }`}
                  onClick={
                    renderExpanded
                      ? (e) => {
                          // Interactive controls inside the row keep their own behavior.
                          if ((e.target as HTMLElement).closest('button, a, input, select, textarea, label')) return
                          const willOpen = expandedId !== row.id
                          setExpandedId(willOpen ? row.id : null)
                          // Lazy-load the expanded content when opening — OUTSIDE the
                          // state updater (updaters run during render, and the parent's
                          // loader calls setState, which warns "update while rendering").
                          if (willOpen) onRowExpand?.(row.original)
                        }
                      : onRowClick
                        ? (e) => {
                            if ((e.target as HTMLElement).closest('button, a, input, select, textarea, label')) return
                            onRowClick(row.original)
                          }
                        : undefined
                  }
                >
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
                        className={`${densityRowClass[density]} ${editable && !isEditing ? 'cursor-pointer transition-colors hover:bg-[#faf7f0]' : ''} ${isEditing ? 'bg-[#fcfaf5] ring-1 ring-inset ring-[#cdbf9f]' : ''}`}
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
                {renderExpanded && expandedId === row.id ? (
                  <tr>
                    <td colSpan={visibleColumnCount} className="p-0">
                      {renderExpanded(row.original)}
                    </td>
                  </tr>
                ) : null}
                </Fragment>
              ))}
            </tbody>
          </table>
        </DndContext>
      </div>

      {/* Server-paged tables get one page as their data, so this would always read "N of N". */}
      {manualPagination ? null : (
        <p className="mt-2 text-right text-[10.5px] tabular-nums text-[#b6a684]">
          Showing {visibleRows.length} of {totalRows} row{totalRows === 1 ? '' : 's'}
        </p>
      )}
    </div>
  )
}
