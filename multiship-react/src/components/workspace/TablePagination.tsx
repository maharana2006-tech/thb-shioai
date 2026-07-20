interface TablePaginationProps {
  page: number
  pageSize: number
  totalPages: number
  compact?: boolean
  onPageChange: (page: number) => void
  onPageSizeChange: (pageSize: number) => void
}

export default function TablePagination({
  page,
  pageSize,
  totalPages,
  compact = false,
  onPageChange,
  onPageSizeChange,
}: TablePaginationProps) {
  return (
    <div className={`flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between ${compact ? 'mt-4' : 'mt-5'}`}>
      <div className={`flex items-center gap-3 text-slate-600 ${compact ? 'text-[13px]' : 'text-sm'}`}>
        <span>Rows per page</span>
        <select
          value={pageSize}
          onChange={(event) => onPageSizeChange(Number(event.target.value))}
          className={`rounded-2xl border border-slate-200 bg-white outline-none transition focus:border-sky-600 focus:ring-4 focus:ring-sky-100 ${compact ? 'px-2.5 py-1 text-[13px]' : 'px-3 py-1.5'}`}
        >
          {[10, 25, 50, 100].map((size) => (
            <option key={size} value={size}>
              {size}
            </option>
          ))}
        </select>
      </div>

      <div className={`flex flex-wrap items-center gap-3 text-slate-600 ${compact ? 'text-[13px]' : 'text-sm'}`}>
        <button
          type="button"
          disabled={page === 1}
          onClick={() => onPageChange(page - 1)}
          className={`rounded-2xl border border-slate-200 bg-white transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50 ${compact ? 'px-3 py-1 text-[13px]' : 'px-3.5 py-1.5'}`}
        >
          Previous
        </button>
        <span>
          Page {page} of {totalPages}
        </span>
        <button
          type="button"
          disabled={page === totalPages}
          onClick={() => onPageChange(page + 1)}
          className={`rounded-2xl border border-slate-200 bg-white transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50 ${compact ? 'px-3 py-1 text-[13px]' : 'px-3.5 py-1.5'}`}
        >
          Next
        </button>
      </div>
    </div>
  )
}
