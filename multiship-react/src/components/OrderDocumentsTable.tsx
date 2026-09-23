import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import type { ColumnDef, SortingState } from '@tanstack/react-table'
import { FiCalendar, FiDownload, FiFileText, FiLoader, FiRefreshCw, FiSlash, FiTruck, FiX } from 'react-icons/fi'
import { orderService, type DocumentFacets, type DocumentsQuery, type OrderDocumentRow } from '../api/orderService'
import { notify } from '../utils/notify'
import { useLatestRequest } from '../hooks/useLatestRequest'
import AdvancedDataTable from './workspace/AdvancedDataTable'
import {
  CHIP_BTN, CHIP_OFF, CHIP_ON, Check, CountBadge, FIELD_INPUT, FIELD_LABEL, FilterChips, FilterPopover,
  OPTION, OPTION_ON, type FilterChip, type RailItem,
} from './ui/FilterPopover'

/**
 * The unified Documents table — one row per labelled order carrying every
 * artifact that label generation produced: tracking number, label downloads
 * (PDF/ZPL), the commercial invoice (international orders), and the billing
 * statement (carrier cost / markup / billable). The "everything for this
 * shipment in one place" view.
 *
 * The server pages, filters and sorts it; this component owns the query.
 */

type StatusKey = 'ANY' | 'LIVE' | 'VOIDED'
type InvoiceKey = 'ANY' | 'YES' | 'NO'
type Field = 'status' | 'carrier' | 'invoice' | 'generated'

interface Filters {
  status: StatusKey
  carrier: string
  invoice: InvoiceKey
  from: string
  to: string
}
const NO_FILTERS: Filters = { status: 'ANY', carrier: '', invoice: 'ANY', from: '', to: '' }

/** Table column id → server sort key. */
const SORT_KEY: Record<string, NonNullable<DocumentsQuery['sort']>> = {
  order: 'order', recipient: 'recipient', destination: 'destination', carrier: 'carrier', billed: 'billed', generated: 'generated',
}

const isoDay = (d: Date) => {
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}
const daysAgo = (n: number) => { const d = new Date(); d.setDate(d.getDate() - n); return isoDay(d) }
const monthStart = () => { const d = new Date(); d.setDate(1); return isoDay(d) }
const DATE_PRESETS: { label: string; from: () => string; to: () => string }[] = [
  { label: 'Today', from: () => daysAgo(0), to: () => daysAgo(0) },
  { label: 'Last 7 days', from: () => daysAgo(6), to: () => daysAgo(0) },
  { label: 'Last 30 days', from: () => daysAgo(29), to: () => daysAgo(0) },
  { label: 'This month', from: monthStart, to: () => daysAgo(0) },
]
const shortDay = (iso: string) => {
  const d = new Date(`${iso}T00:00:00`)
  if (Number.isNaN(d.getTime())) return iso
  const sameYear = d.getFullYear() === new Date().getFullYear()
  return d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', ...(sameYear ? {} : { year: 'numeric' }) })
}
const rangeLabel = (from: string, to: string) =>
  from && to ? (from === to ? shortDay(from) : `${shortDay(from)} – ${shortDay(to)}`) : from ? `from ${shortDay(from)}` : `until ${shortDay(to)}`

const saveBlob = (blob: Blob, filename: string) => {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

const money = (v: number | null | undefined, ccy?: string | null) =>
  v == null ? '—' : `${Number(v).toFixed(2)}${ccy ? ` ${ccy}` : ''}`

const DOC_BTN =
  'inline-flex items-center gap-1 rounded-lg border border-[#e3d9c4] bg-white px-2 py-1 text-[10.5px] font-semibold text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0] disabled:cursor-not-allowed disabled:opacity-40'

/** onLoaded fires after each load settles — Bulk Mailer holds its tab's height until then. */
export default function OrderDocumentsTable({ onLoaded }: { onLoaded?: () => void } = {}) {
  const [rows, setRows] = useState<OrderDocumentRow[]>([])
  const [pageInfo, setPageInfo] = useState({ total: 0, pages: 1 })
  const [loaded, setLoaded] = useState(false)
  const [refreshing, setRefreshing] = useState(false)
  const [facets, setFacets] = useState<DocumentFacets | null>(null)
  const [busyKey, setBusyKey] = useState<string | null>(null)
  const [statementRow, setStatementRow] = useState<OrderDocumentRow | null>(null)

  // ── The query: search, filters, sort, page ──
  const [search, setSearch] = useState('')
  const [debouncedSearch, setDebouncedSearch] = useState('')
  useEffect(() => {
    const t = window.setTimeout(() => setDebouncedSearch(search.trim()), 300)
    return () => window.clearTimeout(t)
  }, [search])
  const [filters, setFilters] = useState<Filters>(NO_FILTERS)
  const [sorting, setSorting] = useState<SortingState>([{ id: 'generated', desc: true }])
  const [pageIndex, setPageIndex] = useState(0)
  const [pageSize, setPageSize] = useState(25)

  const query: DocumentsQuery = {
    q: debouncedSearch || undefined,
    carrier: filters.carrier || undefined,
    status: filters.status === 'ANY' ? undefined : filters.status,
    invoice: filters.invoice === 'ANY' ? undefined : filters.invoice,
    from: filters.from || undefined,
    to: filters.to || undefined,
    sort: SORT_KEY[sorting[0]?.id ?? 'generated'] ?? 'generated',
    dir: sorting[0]?.desc === false ? 'ASC' : 'DESC',
  }
  const queryKey = JSON.stringify(query)
  // A new query starts again at page 1.
  const [pagedKey, setPagedKey] = useState(queryKey)
  if (pagedKey !== queryKey) {
    setPagedKey(queryKey)
    setPageIndex(0)
  }

  const latest = useLatestRequest()
  const load = async (withFacets: boolean) => {
    const seq = latest.begin()
    setRefreshing(true)
    try {
      const [page, f] = await Promise.all([
        orderService.pageDocuments({ ...query, page: pageIndex, size: pageSize }),
        withFacets ? orderService.documentFacets().catch(() => null) : Promise.resolve(null),
      ])
      if (!latest.isLatest(seq)) return
      setRows(page.data?.content ?? [])
      setPageInfo({ total: page.data?.totalElements ?? 0, pages: Math.max(1, page.data?.totalPages ?? 1) })
      if (f?.data) setFacets(f.data)
    } catch (e) {
      if (!latest.isLatest(seq)) return
      notify.apiError(e, 'Could not load the documents table.')
    } finally {
      if (latest.isLatest(seq)) {
        setRefreshing(false)
        setLoaded(true)
        onLoaded?.()
      }
    }
  }
  // Re-read whenever the query or the page changes (facets come with the first read and each refresh).
  // eslint-disable-next-line react-hooks/set-state-in-effect, react-hooks/exhaustive-deps -- a fetch on every query change; the sync setRefreshing(true) drives the dimming and cannot be derived at render
  useEffect(() => { void load(!facets) }, [queryKey, pageIndex, pageSize])

  // ── Downloads ──
  const download = useCallback(async (key: string, fetch: () => Promise<Blob>, filename: string, fail: string) => {
    setBusyKey(key)
    try {
      saveBlob(await fetch(), filename)
    } catch (e) {
      notify.apiError(e, fail)
    } finally {
      setBusyKey(null)
    }
  }, [])

  // ── Filters ──
  const activeCount = (filters.status !== 'ANY' ? 1 : 0) + (filters.carrier ? 1 : 0)
    + (filters.invoice !== 'ANY' ? 1 : 0) + (filters.from || filters.to ? 1 : 0)
  const clearFilters = () => setFilters(NO_FILTERS)

  const columns = useMemo<ColumnDef<OrderDocumentRow, unknown>[]>(() => [
    {
      id: 'order',
      header: 'Order',
      size: 130,
      cell: ({ row }) => {
        const r = row.original
        return (
          <span className="flex flex-wrap items-center gap-1.5">
            <Link to={`/label/${r.orderNo}`} className="font-mono text-[12px] font-bold text-[#412d15] underline-offset-2 hover:underline">
              #{r.orderNo}
            </Link>
            {r.packageCount && r.packageCount > 1 ? (
              <span className="text-[10px] text-[#b6a684]">×{r.packageCount} pkg</span>
            ) : null}
            {r.voided ? (
              <span className="rounded-full bg-slate-200 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-slate-600">Voided</span>
            ) : null}
          </span>
        )
      },
      meta: { exportValue: (r: OrderDocumentRow) => r.orderNo },
    },
    {
      id: 'recipient',
      header: 'Recipient',
      size: 170,
      cell: ({ row }) => (
        <span className="flex flex-col">
          <span className="font-semibold text-[#1f150c]">{row.original.recipientName ?? '—'}</span>
          {row.original.customerReferenceId ? (
            <span className="font-mono text-[10.5px] text-[#8a7a5a]">{row.original.customerReferenceId}</span>
          ) : null}
        </span>
      ),
      meta: { exportValue: (r: OrderDocumentRow) => r.recipientName ?? '' },
    },
    {
      id: 'destination',
      header: 'Destination',
      size: 130,
      cell: ({ row }) => (
        <span className="text-[#5a4526]">{row.original.city ?? '—'}{row.original.countryCode ? ` · ${row.original.countryCode}` : ''}</span>
      ),
      meta: { exportValue: (r: OrderDocumentRow) => [r.city, r.countryCode].filter(Boolean).join(' · ') },
    },
    {
      id: 'carrier',
      header: 'Carrier · Tracking',
      size: 200,
      cell: ({ row }) => (
        <span className="flex flex-col">
          <span className="inline-flex items-center gap-1.5 text-[11px] font-semibold text-[#1f150c]">
            <FiTruck className="h-3 w-3 text-[#b6a684]" aria-hidden="true" />{row.original.carrier ?? '—'}
          </span>
          <span className="font-mono text-[10.5px] text-[#5a4526]">{row.original.trackingNumber ?? '—'}</span>
        </span>
      ),
      meta: { exportValue: (r: OrderDocumentRow) => `${r.carrier ?? ''} ${r.trackingNumber ?? ''}`.trim() },
    },
    {
      id: 'generated',
      header: 'Generated',
      size: 120,
      cell: ({ row }) => {
        const d = row.original.generatedAt ? new Date(row.original.generatedAt) : null
        return d && !Number.isNaN(d.getTime()) ? (
          <span className="flex flex-col tabular-nums">
            <span className="text-[#1f150c]">{d.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })}</span>
            <span className="text-[10.5px] text-[#8a7a5a]">{d.toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' })}</span>
          </span>
        ) : <span className="text-[#b6a684]">—</span>
      },
      meta: { exportValue: (r: OrderDocumentRow) => r.generatedAt ?? '' },
    },
    {
      id: 'billed',
      header: 'Billed',
      size: 100,
      cell: ({ row }) => (
        <span className="block text-right tabular-nums">
          {row.original.voided ? (
            <span title="Label voided — charge reversed" className="text-slate-400"><s>{money(row.original.billableAmount, row.original.markupCurrency)}</s></span>
          ) : (
            <span className="font-semibold text-[#1f150c]">{money(row.original.billableAmount, row.original.markupCurrency)}</span>
          )}
        </span>
      ),
      meta: { exportValue: (r: OrderDocumentRow) => r.billableAmount ?? '' },
    },
    {
      id: 'documents',
      header: 'Documents',
      enableSorting: false,
      size: 290,
      cell: ({ row }) => {
        const r = row.original
        const spin = <FiLoader className="h-3 w-3 animate-spin" />
        return (
          <span className="flex items-center gap-1 whitespace-nowrap">
            <button type="button" onClick={() => void download(`label-${r.orderNo}`, () => orderService.getLabelPdf(r.orderNo), `label-${r.orderNo}.pdf`, 'Label PDF download failed.')}
              disabled={busyKey === `label-${r.orderNo}`} className={DOC_BTN} title="Download the 4x6 shipping label as PDF">
              {busyKey === `label-${r.orderNo}` ? spin : <FiDownload className="h-3 w-3 text-[#412d15]" />} Label
            </button>
            <button type="button" onClick={() => void download(`zpl-${r.orderNo}`, async () => new Blob([await orderService.getLabelZpl(r.orderNo)], { type: 'text/plain' }), `label-${r.orderNo}.zpl`, 'ZPL download failed.')}
              disabled={busyKey === `zpl-${r.orderNo}`} className={DOC_BTN} title="Raw ZPL for thermal printers">
              {busyKey === `zpl-${r.orderNo}` ? spin : <FiDownload className="h-3 w-3 text-[#412d15]" />} ZPL
            </button>
            {r.hasInvoice ? (
              <button type="button" onClick={() => void download(`inv-${r.orderNo}`, () => orderService.getCommercialInvoicePdf(r.orderNo), `commercial-invoice-${r.orderNo}.pdf`, 'Commercial invoice download failed.')}
                disabled={busyKey === `inv-${r.orderNo}`} className={DOC_BTN} title="Commercial invoice PDF (customs document)">
                {busyKey === `inv-${r.orderNo}` ? spin : <FiDownload className="h-3 w-3 text-sky-700" />} Invoice
              </button>
            ) : (
              <span className="whitespace-nowrap px-1 text-[10px] text-[#cdbf9f]" title="Domestic shipment — no customs invoice">no invoice</span>
            )}
            <button type="button" onClick={() => setStatementRow(r)} className={DOC_BTN} title="Billing statement — carrier cost, markup, billable amount">
              <FiFileText className="h-3 w-3 text-emerald-700" /> Statement
            </button>
          </span>
        )
      },
      meta: { exportValue: (r: OrderDocumentRow) => r.hasInvoice ? 'label, zpl, invoice, statement' : 'label, zpl, statement' },
    },
  ], [busyKey, download])

  const chips: FilterChip[] = []
  if (filters.status !== 'ANY') chips.push({ key: 'status', label: 'Status', value: filters.status === 'LIVE' ? 'Live' : 'Voided', clear: () => setFilters((f) => ({ ...f, status: 'ANY' })) })
  if (filters.carrier) chips.push({ key: 'carrier', label: 'Carrier', value: filters.carrier, clear: () => setFilters((f) => ({ ...f, carrier: '' })) })
  if (filters.invoice !== 'ANY') chips.push({ key: 'invoice', label: 'Invoice', value: filters.invoice === 'YES' ? 'With invoice' : 'No invoice', clear: () => setFilters((f) => ({ ...f, invoice: 'ANY' })) })
  if (filters.from || filters.to) chips.push({ key: 'generated', label: 'Generated', value: rangeLabel(filters.from, filters.to), clear: () => setFilters((f) => ({ ...f, from: '', to: '' })) })

  return (
    <section
      aria-busy={refreshing}
      className={`rounded-2xl border border-slate-200 bg-white p-3 shadow-sm transition-opacity duration-200 ${refreshing && loaded ? 'opacity-60' : ''}`}
    >
      <AdvancedDataTable<OrderDocumentRow>
        tableKey="bulk-documents-v1"
        columns={columns}
        data={rows}
        search={{ value: search, onChange: setSearch, placeholder: 'Search order #, tracking, recipient, client or city…' }}
        filterToggle={
          <DocumentsFilterMenu
            filters={filters}
            setFilters={setFilters}
            facets={facets}
            activeCount={activeCount}
            shown={pageInfo.total}
            clearFilters={clearFilters}
          />
        }
        filterPanel={<FilterChips chips={chips} clearFilters={clearFilters} testId="documents-filter-chips" />}
        toolbarActions={
          <button
            type="button"
            onClick={() => void load(true)}
            aria-label="Refresh"
            title="Refresh the documents"
            className="inline-flex h-[30px] w-[30px] items-center justify-center rounded-lg border border-[#e3d9c4] bg-white text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
          >
            <FiRefreshCw className={`h-3.5 w-3.5 ${refreshing ? 'animate-spin' : ''}`} />
          </button>
        }
        manualPagination
        pageIndex={pageIndex}
        pageSize={pageSize}
        pageCount={pageInfo.pages}
        onPaginationChange={({ pageIndex: i, pageSize: n }) => { setPageIndex(n !== pageSize ? 0 : i); setPageSize(n) }}
        manualSorting
        sorting={sorting}
        onSortingChange={(next) => setSorting(next.length ? next : [{ id: 'generated', desc: true }])}
        getRowId={(r) => String(r.orderNo)}
        csvFilename="shipment-documents"
        caption={`${pageInfo.total} labelled order${pageInfo.total === 1 ? '' : 's'} · tracking, label, invoice & statement for each`}
        emptyState={
          <div className="px-5 py-10 text-center">
            <p className="text-sm text-[#6b5c42]">
              {!loaded ? 'Loading documents…'
                : activeCount > 0 || debouncedSearch ? 'No documents match your filters.'
                  : 'No labels generated yet — generate one and its documents appear here.'}
            </p>
            {loaded && (activeCount > 0 || debouncedSearch) ? (
              <button
                type="button"
                onClick={() => { clearFilters(); setSearch('') }}
                className="mt-3 inline-flex items-center gap-1 rounded-xl border border-[#e3d9c4] bg-white px-3 py-1.5 text-[12px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
              >
                <FiX className="h-3.5 w-3.5" /> Clear filters
              </button>
            ) : null}
          </div>
        }
      />

      {/* ── Billing-statement modal ── */}
      {statementRow ? (
        <div
          role="dialog"
          aria-modal="true"
          aria-label={`Billing statement for order ${statementRow.orderNo}`}
          className="fixed inset-0 z-50 flex items-center justify-center bg-[#1f150c]/45 p-4"
          onClick={() => setStatementRow(null)}
        >
          <div
            className="w-full max-w-[420px] overflow-hidden rounded-2xl border border-[#e3d9c4] bg-white shadow-2xl"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="flex items-start justify-between border-b border-[#eee6d6] bg-[#faf7f0] px-5 py-3.5">
              <div>
                <p className="text-[9.5px] font-bold uppercase tracking-[0.18em] text-[#b6a684]">Billing statement</p>
                <p className="text-[14px] font-semibold text-[#1f150c]">Order #{statementRow.orderNo}</p>
              </div>
              <button
                type="button"
                onClick={() => setStatementRow(null)}
                aria-label="Close"
                className="rounded-lg border border-[#e3d9c4] bg-white p-1.5 text-[#6b5c42] transition hover:bg-[#faf7f0]"
              >
                <FiX className="h-3.5 w-3.5" />
              </button>
            </div>
            <div className="space-y-2.5 px-5 py-4 text-[12px]">
              {[
                ['Client', statementRow.customerReferenceId ?? '—'],
                ['Recipient', statementRow.recipientName ?? '—'],
                ['Carrier / Tracking', `${statementRow.carrier ?? '—'} · ${statementRow.trackingNumber ?? '—'}`],
                ['Billed to account', statementRow.accountNumber ?? '—'],
                ['Generated', statementRow.generatedAt ? new Date(statementRow.generatedAt).toLocaleString() : '—'],
              ].map(([k, v]) => (
                <div key={k} className="flex items-baseline justify-between gap-3">
                  <span className="text-[10.5px] font-semibold uppercase tracking-[0.06em] text-[#b6a684]">{k}</span>
                  <span className="text-right font-medium text-[#1f150c]">{v}</span>
                </div>
              ))}
              <div className="my-1 border-t border-dashed border-[#e3d9c4]" />
              <div className="flex items-baseline justify-between gap-3">
                <span className="text-[10.5px] font-semibold uppercase tracking-[0.06em] text-[#b6a684]">Carrier cost</span>
                <span className="text-right tabular-nums">{money(statementRow.carrierAmount, statementRow.markupCurrency)}</span>
              </div>
              <div className="flex items-baseline justify-between gap-3">
                <span className="text-[10.5px] font-semibold uppercase tracking-[0.06em] text-[#b6a684]">
                  Markup{statementRow.markupKind ? ` (${statementRow.markupKind.toLowerCase()}${statementRow.markupValue != null ? ` ${statementRow.markupValue}` : ''})` : ''}
                </span>
                <span className="text-right tabular-nums">
                  {statementRow.billableAmount != null && statementRow.carrierAmount != null
                    ? money(statementRow.billableAmount - statementRow.carrierAmount, statementRow.markupCurrency)
                    : '—'}
                </span>
              </div>
              <div className="flex items-baseline justify-between gap-3 rounded-xl bg-[#faf7f0] px-3 py-2">
                <span className="text-[10.5px] font-bold uppercase tracking-[0.06em] text-[#5a4526]">Billable total</span>
                <span className="text-right text-[14px] font-bold tabular-nums text-[#1f150c]">
                  {money(statementRow.billableAmount, statementRow.markupCurrency)}
                </span>
              </div>
            </div>
          </div>
        </div>
      ) : null}
    </section>
  )
}

/** The documents filters behind one button: a rail of fields, the editor of the chosen one. */
function DocumentsFilterMenu({
  filters, setFilters, facets, activeCount, shown, clearFilters,
}: {
  filters: Filters
  setFilters: React.Dispatch<React.SetStateAction<Filters>>
  facets: DocumentFacets | null
  activeCount: number
  shown: number
  clearFilters: () => void
}) {
  const dateActive = !!(filters.from || filters.to)
  const rail: RailItem<Field>[] = [
    { key: 'status', label: 'Status', icon: <FiSlash className="h-3.5 w-3.5" />,
      value: filters.status === 'ANY' ? 'Any' : filters.status === 'LIVE' ? 'Live' : 'Voided', active: filters.status !== 'ANY' },
    { key: 'carrier', label: 'Carrier', icon: <FiTruck className="h-3.5 w-3.5" />,
      value: filters.carrier || 'Any', active: !!filters.carrier },
    { key: 'invoice', label: 'Invoice', icon: <FiFileText className="h-3.5 w-3.5" />,
      value: filters.invoice === 'ANY' ? 'Any' : filters.invoice === 'YES' ? 'With invoice' : 'No invoice', active: filters.invoice !== 'ANY' },
    { key: 'generated', label: 'Generated', icon: <FiCalendar className="h-3.5 w-3.5" />,
      value: dateActive ? rangeLabel(filters.from, filters.to) : 'Any time', active: dateActive },
  ]

  return (
    <FilterPopover<Field>
      rail={rail}
      initialField="status"
      activeCount={activeCount}
      shown={shown}
      total={facets?.total}
      clearFilters={clearFilters}
    >
      {(field) => (
        <>
              {field === 'status' ? (
                <ul className="space-y-0.5">
                  {([
                    ['ANY', 'Any', 'Live and voided', facets?.total],
                    ['LIVE', 'Live', 'Generated, not voided', facets?.live],
                    ['VOIDED', 'Voided', 'Cancelled at the carrier — charge reversed', facets?.voided],
                  ] as const).map(([k, label, hint, n]) => {
                    const on = filters.status === k
                    return (
                      <li key={k}>
                        <button type="button" onClick={() => setFilters((f) => ({ ...f, status: k }))} aria-pressed={on} className={`${OPTION} ${on ? OPTION_ON : ''}`}>
                          <span className={`h-2.5 w-2.5 shrink-0 rounded-full ${k === 'VOIDED' ? 'bg-slate-400' : k === 'LIVE' ? 'bg-emerald-500' : 'bg-[#1f150c]'}`} aria-hidden="true" />
                          <span className="min-w-0 flex-1">
                            <span className="block text-[12.5px] font-semibold leading-tight">{label}</span>
                            <span className="block text-[10.5px] leading-tight text-[#a1906d]">{hint}</span>
                          </span>
                          <CountBadge n={n} />
                          {on ? <Check /> : null}
                        </button>
                      </li>
                    )
                  })}
                </ul>
              ) : null}
              {field === 'carrier' ? (
                <ul className="space-y-0.5">
                  <li>
                    <button type="button" onClick={() => setFilters((f) => ({ ...f, carrier: '' }))} aria-pressed={!filters.carrier} className={`${OPTION} ${!filters.carrier ? OPTION_ON : ''}`}>
                      <span className="text-[12.5px] font-semibold">Any carrier</span>
                      <CountBadge n={facets?.total} />
                      {!filters.carrier ? <Check /> : null}
                    </button>
                  </li>
                  {(facets?.carriers ?? []).map((c) => {
                    const on = filters.carrier === c.carrier
                    return (
                      <li key={c.carrier}>
                        <button type="button" onClick={() => setFilters((f) => ({ ...f, carrier: c.carrier }))} aria-pressed={on} className={`${OPTION} ${on ? OPTION_ON : ''}`}>
                          <span className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-md bg-[#f4eede] text-[#6b5c42]" aria-hidden="true"><FiTruck className="h-3 w-3" /></span>
                          <span className="text-[12.5px] font-semibold">{c.carrier}</span>
                          <CountBadge n={c.count} />
                          {on ? <Check /> : null}
                        </button>
                      </li>
                    )
                  })}
                  {facets && facets.carriers.length === 0 ? <li className="px-2.5 py-2 text-[12px] text-[#a1906d]">No labelled orders yet.</li> : null}
                </ul>
              ) : null}
              {field === 'invoice' ? (
                <ul className="space-y-0.5">
                  {([
                    ['ANY', 'Any', 'Domestic and international', facets?.total],
                    ['YES', 'With invoice', 'International — customs data on file', facets?.withInvoice],
                    ['NO', 'No invoice', 'Domestic — no customs document', facets ? facets.total - facets.withInvoice : undefined],
                  ] as const).map(([k, label, hint, n]) => {
                    const on = filters.invoice === k
                    return (
                      <li key={k}>
                        <button type="button" onClick={() => setFilters((f) => ({ ...f, invoice: k }))} aria-pressed={on} className={`${OPTION} ${on ? OPTION_ON : ''}`}>
                          <span className="inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-md bg-[#f4eede] text-[#6b5c42]" aria-hidden="true"><FiFileText className="h-3 w-3" /></span>
                          <span className="min-w-0 flex-1">
                            <span className="block text-[12.5px] font-semibold leading-tight">{label}</span>
                            <span className="block text-[10.5px] leading-tight text-[#a1906d]">{hint}</span>
                          </span>
                          <CountBadge n={n} />
                          {on ? <Check /> : null}
                        </button>
                      </li>
                    )
                  })}
                </ul>
              ) : null}
              {field === 'generated' ? (
                <div className="space-y-3">
                  <div>
                    <p className={FIELD_LABEL}>Quick pick</p>
                    <div className="flex flex-wrap gap-1.5">
                      <button type="button" onClick={() => setFilters((f) => ({ ...f, from: '', to: '' }))} className={`${CHIP_BTN} ${!dateActive ? CHIP_ON : CHIP_OFF}`}>Any time</button>
                      {DATE_PRESETS.map((p) => {
                        const on = filters.from === p.from() && filters.to === p.to()
                        return (
                          <button key={p.label} type="button" onClick={() => setFilters((f) => ({ ...f, from: p.from(), to: p.to() }))} className={`${CHIP_BTN} ${on ? CHIP_ON : CHIP_OFF}`}>{p.label}</button>
                        )
                      })}
                    </div>
                  </div>
                  <div>
                    <p className={FIELD_LABEL}>Custom range</p>
                    <div className="grid grid-cols-2 gap-2">
                      <label className="block">
                        <span className="mb-1 block text-[11px] text-[#6b5c42]">From</span>
                        <input type="date" value={filters.from} max={filters.to || undefined} onChange={(e) => setFilters((f) => ({ ...f, from: e.target.value }))} aria-label="Generated from" className={FIELD_INPUT} />
                      </label>
                      <label className="block">
                        <span className="mb-1 block text-[11px] text-[#6b5c42]">To</span>
                        <input type="date" value={filters.to} min={filters.from || undefined} onChange={(e) => setFilters((f) => ({ ...f, to: e.target.value }))} aria-label="Generated to" className={FIELD_INPUT} />
                      </label>
                    </div>
                  </div>
                </div>
              ) : null}
        </>
      )}
    </FilterPopover>
  )
}
