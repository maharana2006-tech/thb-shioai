import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useOutletContext } from 'react-router-dom'
import {
  FiEdit3,
  FiEye,
  FiFilter,
  FiImage,
  FiInfo,
  FiLoader,
  FiMoreVertical,
  FiPlus,
  FiRefreshCw,
  FiTrash2,
  FiX,
} from 'react-icons/fi'
import type { ColumnDef, SortingState } from '@tanstack/react-table'
import { ApiError } from '../api/apiClient'
import {
  labelTemplateService,
  type LabelTemplate,
  type LabelTemplateListParams,
} from '../api/labelTemplateService'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import AdvancedDataTable from './workspace/AdvancedDataTable'
import Select from './workspace/Select'
import PortalMenu from './workspace/PortalMenu'

const isAdmin = () =>
  (localStorage.getItem('multiship_role') || '').toUpperCase() === 'ADMIN'

const TEMPLATE_TYPES = ['PACKING_SLIP', 'RETURN_COVER', 'COMMERCIAL_INVOICE']

const filterLabelClass =
  'mb-1 flex items-center gap-1 text-[10px] font-bold uppercase tracking-[0.14em] text-slate-400'
const textInput =
  'w-full rounded-md border border-slate-200 bg-white px-3 py-1.5 text-[13px] text-slate-800 outline-none focus:border-[#1f150c]'

/**
 * Settings → Label Templates list page. Cross-tenant table, filters,
 * pagination, per-row Edit/Delete + Add-new. Below the table: a global
 * Live-preview panel that renders any order's packing slip PDF via the
 * currently-saved template — keeps the ergonomic "type an order # and
 * see it" workflow the old editor page had, but standalone.
 *
 * The editor lives at /settings/label-templates/new + /:id.
 */
export default function LabelTemplatesListPage() {
  const admin = isAdmin()
  const navigate = useNavigate()
  const { registerRefresh } = useOutletContext<SettingsOutletContext>()

  const [rows, setRows] = useState<LabelTemplate[]>([])
  const [loading, setLoading] = useState(true)
  const [totalElements, setTotalElements] = useState(0)
  const [totalPages, setTotalPages] = useState(1)

  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  const [typeFilter, setTypeFilter] = useState('')
  const [logoFilter, setLogoFilter] = useState<'' | 'Y' | 'N'>('')
  const [showFilters, setShowFilters] = useState(false)
  const filtersRef = useRef<HTMLDivElement>(null)

  const [sorting, setSorting] = useState<SortingState>([
    { id: 'updatedAt', desc: true },
  ])
  const [pageIndex, setPageIndex] = useState(0)
  const [pageSize, setPageSize] = useState(25)

  const [reloadToken, setReloadToken] = useState(0)
  const [busyId, setBusyId] = useState<number | null>(null)

  // ===== filter popover outside-click / ESC =====
  useEffect(() => {
    if (!showFilters) return
    const onDocClick = (e: MouseEvent) => {
      if (!filtersRef.current?.contains(e.target as Node)) setShowFilters(false)
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setShowFilters(false)
    }
    document.addEventListener('mousedown', onDocClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDocClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [showFilters])

  // Debounce the free-text search.
  useEffect(() => {
    const t = setTimeout(() => setDebouncedSearch(search.trim()), 350)
    return () => clearTimeout(t)
  }, [search])

  // Snap to first page whenever filter/sort/pageSize changes.
  useEffect(() => {
    setPageIndex(0)
  }, [debouncedSearch, typeFilter, logoFilter, sorting, pageSize])

  const sortBy = sorting[0]?.id ?? 'updatedAt'
  const sortDirection: 'ASC' | 'DESC' = sorting[0]?.desc ? 'DESC' : 'ASC'

  // ===== fetch =====
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    const params: LabelTemplateListParams = {
      search: debouncedSearch || undefined,
      templateType: typeFilter || undefined,
      hasLogo: logoFilter || undefined,
      sortBy,
      sortDirection,
      page: pageIndex,
      size: pageSize,
    }
    labelTemplateService
      .listTemplates(params)
      .then((resp) => {
        if (cancelled) return
        setRows(resp.data?.content ?? [])
        setTotalElements(resp.data?.totalElements ?? 0)
        setTotalPages(resp.data?.totalPages ?? 1)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        const msg = err instanceof Error ? err.message : 'Failed to load templates.'
        notify.error(msg)
        setRows([])
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [debouncedSearch, typeFilter, logoFilter, sortBy, sortDirection, pageIndex, pageSize, reloadToken])

  const refresh = useCallback(() => {
    setReloadToken((t) => t + 1)
  }, [])
  useEffect(() => {
    registerRefresh(refresh)
    return () => registerRefresh(null)
  }, [registerRefresh, refresh])

  // ===== row actions =====
  const handleEdit = (t: LabelTemplate) => {
    if (t.id == null) return
    navigate(`/settings/label-templates/${t.id}`)
  }

  const handleDelete = async (t: LabelTemplate) => {
    if (!admin) {
      notify.error('Only admins can delete templates.')
      return
    }
    if (t.id == null) return
    const label = t.tenantId ? `${t.tenantId} (${t.templateType})` : `Platform default (${t.templateType})`
    const ok = await notify.confirm(
      `Delete template for ${label}? Orders will fall back to the resolution order (tenant → platform default).`,
      { danger: true, confirmLabel: 'Delete', title: 'Delete label template' },
    )
    if (!ok) return
    setBusyId(t.id)
    try {
      await labelTemplateService.remove(t.id)
      notify.success('Template deleted.')
      setReloadToken((n) => n + 1)
    } catch (err: unknown) {
      const msg = err instanceof ApiError
        ? err.payload?.message ?? err.message
        : err instanceof Error ? err.message : 'Delete failed.'
      notify.error(msg)
    } finally {
      setBusyId(null)
    }
  }

  // ===== columns =====
  const columns = useMemo<ColumnDef<LabelTemplate, unknown>[]>(
    () => [
      {
        id: 'tenantId',
        accessorFn: (t) => t.tenantId ?? '',
        header: 'Tenant',
        cell: ({ row }) => {
          const t = row.original
          return t.tenantId ? (
            <span className="font-semibold text-slate-950">{t.tenantId}</span>
          ) : (
            <span className="inline-flex items-center rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-600">
              Platform default
            </span>
          )
        },
        meta: {
          headerLabel: 'Tenant',
          exportValue: (t: LabelTemplate) => t.tenantId ?? '(platform default)',
        },
      },
      {
        id: 'templateType',
        accessorFn: (t) => t.templateType ?? '',
        header: 'Type',
        cell: ({ row }) => (
          <span className="inline-flex items-center rounded-full bg-slate-100 px-2 py-0.5 text-[10.5px] font-semibold text-slate-700">
            {row.original.templateType ?? '—'}
          </span>
        ),
        meta: {
          headerLabel: 'Type',
          exportValue: (t: LabelTemplate) => t.templateType ?? '',
        },
      },
      {
        id: 'headerText',
        header: 'Header',
        enableSorting: false,
        cell: ({ row }) => {
          const h = row.original.headerText
          return h ? (
            <span className="text-[12.5px] text-slate-700" title={h}>
              {truncate(h, 40)}
            </span>
          ) : (
            <span className="text-[11.5px] italic text-slate-400">(default)</span>
          )
        },
        meta: {
          headerLabel: 'Header text',
          exportValue: (t: LabelTemplate) => t.headerText ?? '',
        },
      },
      {
        id: 'hasLogo',
        header: 'Logo',
        enableSorting: false,
        cell: ({ row }) => (
          <span
            className={`inline-flex items-center gap-1 text-[11.5px] font-semibold ${
              row.original.hasLogo ? 'text-emerald-700' : 'text-slate-400'
            }`}
          >
            <FiImage className="h-3.5 w-3.5" />
            {row.original.hasLogo ? 'Yes' : 'No'}
          </span>
        ),
        meta: {
          headerLabel: 'Has logo',
          exportValue: (t: LabelTemplate) => (t.hasLogo ? 'Y' : 'N'),
        },
      },
      {
        id: 'primaryColor',
        header: 'Color',
        enableSorting: false,
        cell: ({ row }) => {
          const c = row.original.primaryColor || ''
          if (!c) return <span className="text-slate-400">—</span>
          return (
            <span className="inline-flex items-center gap-1.5 text-[11.5px]">
              <span
                className="h-4 w-4 rounded border border-slate-200"
                style={{ backgroundColor: c }}
              />
              <span className="font-mono text-slate-600">{c}</span>
            </span>
          )
        },
        meta: {
          headerLabel: 'Primary color',
          exportValue: (t: LabelTemplate) => t.primaryColor ?? '',
        },
      },
      {
        id: 'showItems',
        header: 'Items',
        enableSorting: false,
        cell: ({ row }) => (
          <span
            className={`inline-flex items-center rounded-full px-2 py-0.5 text-[10.5px] font-semibold ${
              row.original.showItems !== false
                ? 'bg-slate-100 text-slate-700'
                : 'bg-slate-50 text-slate-400'
            }`}
          >
            {row.original.showItems !== false ? 'Show' : 'Hide'}
          </span>
        ),
        meta: {
          headerLabel: 'Show items',
          exportValue: (t: LabelTemplate) => (t.showItems !== false ? 'Y' : 'N'),
        },
      },
      {
        id: 'updatedAt',
        accessorFn: (t) => t.updatedAt ?? '',
        header: 'Updated',
        cell: ({ row }) => (
          <span className="text-[11.5px] text-slate-600">
            {relativeTime(row.original.updatedAt) ?? '—'}
          </span>
        ),
        meta: {
          headerLabel: 'Updated',
          exportValue: (t: LabelTemplate) => t.updatedAt ?? '',
        },
      },
      {
        id: 'actions',
        header: '',
        enableSorting: false,
        cell: ({ row }) => (
          <div className="text-right">
            <RowMenu
              busy={busyId === row.original.id}
              admin={admin}
              onEdit={() => handleEdit(row.original)}
              onDelete={() => void handleDelete(row.original)}
            />
          </div>
        ),
        meta: { headerLabel: 'Actions', hideable: false, exportable: false },
      },
    ],
    // Handlers close over admin + busyId + navigate; those cover all reactive deps
    // that actually affect cell output.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [admin, busyId],
  )

  // ===== live-preview state (below table) =====
  const [previewOrderNo, setPreviewOrderNo] = useState('')
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)
  const [previewing, setPreviewing] = useState(false)
  const openPreview = async () => {
    const orderNo = previewOrderNo.trim()
    if (!orderNo) {
      notify.error('Enter an order number to preview.')
      return
    }
    if (previewing) return
    setPreviewing(true)
    try {
      const objectUrl = await labelTemplateService.fetchPreviewObjectUrl(orderNo)
      setPreviewUrl((prev) => {
        if (prev) URL.revokeObjectURL(prev)
        return objectUrl
      })
    } catch (err: unknown) {
      const msg = err instanceof Error ? err.message : 'Preview failed.'
      notify.error(msg)
    } finally {
      setPreviewing(false)
    }
  }
  useEffect(() => {
    return () => {
      if (previewUrl) URL.revokeObjectURL(previewUrl)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const filtersActive = !!(typeFilter || logoFilter)

  return (
    <div className="space-y-4">
      {/* ===== Table ===== */}
      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        {loading && !rows.length ? (
          <p className="py-10 text-center text-sm text-slate-500">Loading templates…</p>
        ) : (
          <AdvancedDataTable<LabelTemplate>
            tableKey="label-templates"
            columns={columns}
            data={rows}
            manualPagination
            manualSorting
            pageIndex={pageIndex}
            pageSize={pageSize}
            pageCount={totalPages}
            sorting={sorting}
            onSortingChange={setSorting}
            onPaginationChange={(next) => {
              setPageIndex(next.pageIndex)
              setPageSize(next.pageSize)
            }}
            search={{
              value: search,
              onChange: setSearch,
              placeholder: 'Search tenant id…',
            }}
            filterToggle={
              <div ref={filtersRef} className="relative">
                <button
                  type="button"
                  onClick={() => setShowFilters((v) => !v)}
                  className={`inline-flex items-center gap-1.5 rounded-md border px-3 py-1.5 text-[12.5px] font-semibold transition ${
                    filtersActive
                      ? 'border-[#1f150c] bg-[#1f150c] text-white'
                      : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-50'
                  }`}
                >
                  <FiFilter className="h-3.5 w-3.5" />
                  Filters
                  {filtersActive ? (
                    <span className="rounded-full bg-white/20 px-1.5 text-[10px] font-bold">
                      {(typeFilter ? 1 : 0) + (logoFilter ? 1 : 0)}
                    </span>
                  ) : null}
                </button>
                {showFilters ? (
                  <div className="absolute right-0 z-30 mt-1 w-72 rounded-lg border border-slate-200 bg-white p-3 shadow-lg">
                    <div className="mb-3">
                      <label className={filterLabelClass}>Type</label>
                      <Select value={typeFilter} onChange={(e) => setTypeFilter(e.target.value)}>
                        <option value="">All types</option>
                        {TEMPLATE_TYPES.map((t) => (
                          <option key={t} value={t}>
                            {t}
                          </option>
                        ))}
                      </Select>
                    </div>
                    <div className="mb-3">
                      <label className={filterLabelClass}>Has logo</label>
                      <Select
                        value={logoFilter}
                        onChange={(e) => setLogoFilter(e.target.value as 'Y' | 'N' | '')}
                      >
                        <option value="">Any</option>
                        <option value="Y">With logo</option>
                        <option value="N">Without logo</option>
                      </Select>
                    </div>
                    {filtersActive ? (
                      <button
                        type="button"
                        onClick={() => {
                          setTypeFilter('')
                          setLogoFilter('')
                        }}
                        className="inline-flex items-center gap-1 text-[11.5px] font-semibold text-slate-600 hover:text-slate-950"
                      >
                        <FiX className="h-3 w-3" /> Clear filters
                      </button>
                    ) : null}
                  </div>
                ) : null}
              </div>
            }
            toolbarActions={
              <button
                type="button"
                onClick={() => navigate('/settings/label-templates/new')}
                className="inline-flex items-center gap-1.5 rounded-md bg-[#1f150c] px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-black"
              >
                <FiPlus className="h-3.5 w-3.5" /> Add template
              </button>
            }
            emptyState={
              <div className="py-10 text-center text-[12.5px] text-slate-500">
                {debouncedSearch || filtersActive
                  ? 'No templates match your filters.'
                  : 'No templates yet — add the platform default or a tenant override.'}
              </div>
            }
            caption={
              <span className="text-[11.5px] text-slate-500">
                {totalElements} template{totalElements === 1 ? '' : 's'}
              </span>
            }
          />
        )}
      </section>

      {/* ===== Live preview (standalone tool) ===== */}
      <section className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        <h2 className="mb-1 text-base font-semibold text-slate-900">Live preview</h2>
        <p className="mb-3 text-[12.5px] text-slate-500">
          Enter any order number to render its packing slip PDF with the tenant's
          currently-saved template (falls back to the platform default).
        </p>
        <div className="mb-3 flex gap-2">
          <input
            type="text"
            value={previewOrderNo}
            onChange={(e) => setPreviewOrderNo(e.target.value)}
            placeholder="Order number, e.g. 100"
            className={textInput}
          />
          <button
            type="button"
            onClick={() => void openPreview()}
            disabled={previewing}
            className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50"
          >
            {previewing ? <FiLoader className="animate-spin" /> : <FiEye />}
            {previewing ? 'Loading…' : 'Preview'}
          </button>
          {previewUrl ? (
            <button
              type="button"
              onClick={() => void openPreview()}
              disabled={previewing}
              className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50 disabled:opacity-50"
            >
              <FiRefreshCw /> Reload
            </button>
          ) : null}
        </div>
        {previewUrl ? (
          <iframe
            src={previewUrl}
            title="Packing slip preview"
            className="h-[520px] w-full rounded-lg border border-slate-200 bg-slate-50"
          />
        ) : (
          <div className="flex h-[520px] items-center justify-center rounded-lg border border-dashed border-slate-200 text-[12.5px] text-slate-400">
            <span className="inline-flex items-center gap-1.5">
              <FiInfo /> Enter an order number above to render a preview.
            </span>
          </div>
        )}
      </section>
    </div>
  )
}

// ===== helpers =====

function truncate(s: string, max: number): string {
  return s.length <= max ? s : s.slice(0, max - 1) + '…'
}

function relativeTime(value?: string | null): string | null {
  if (!value) return null
  const then = new Date(value).getTime()
  if (Number.isNaN(then)) return value
  const mins = Math.max(0, Math.round((Date.now() - then) / 60000))
  if (mins < 1) return 'just now'
  if (mins < 60) return `${mins} min ago`
  const hours = Math.round(mins / 60)
  if (hours < 24) return `${hours}h ago`
  return `${Math.round(hours / 24)}d ago`
}

function RowMenu({
  admin,
  busy,
  onEdit,
  onDelete,
}: {
  admin: boolean
  busy: boolean
  onEdit: () => void
  onDelete: () => void
}) {
  const [open, setOpen] = useState(false)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const close = useCallback(() => setOpen(false), [])

  return (
    <>
      <button
        ref={buttonRef}
        type="button"
        disabled={busy}
        onClick={(e) => { e.stopPropagation(); setOpen((c) => !c) }}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Row actions"
        className="inline-flex h-7 w-7 items-center justify-center rounded-lg border border-slate-200 bg-white text-slate-500 transition hover:bg-slate-50 disabled:opacity-40"
      >
        {busy ? <FiLoader className="h-3.5 w-3.5 animate-spin" /> : <FiMoreVertical className="h-3.5 w-3.5" />}
      </button>
      <PortalMenu open={open} anchorRef={buttonRef} onClose={close} width={192}>
        <button
          type="button"
          role="menuitem"
          onClick={() => { close(); onEdit() }}
          className="flex w-full items-center gap-2 px-3 py-2 text-[12px] font-semibold text-slate-700 transition hover:bg-slate-50"
        >
          <FiEdit3 className="h-3.5 w-3.5 text-slate-500" /> Edit
        </button>
        {admin ? (
          <button
            type="button"
            role="menuitem"
            onClick={() => { close(); onDelete() }}
            className="flex w-full items-center gap-2 px-3 py-2 text-[12px] font-semibold text-rose-600 transition hover:bg-rose-50"
          >
            <FiTrash2 className="h-3.5 w-3.5" /> Delete
          </button>
        ) : null}
      </PortalMenu>
    </>
  )
}
