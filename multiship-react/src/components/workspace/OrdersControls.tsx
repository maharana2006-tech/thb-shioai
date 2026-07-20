import type { ReactNode } from 'react'
import { FiAlertCircle, FiCheckCircle, FiGrid, FiClock } from 'react-icons/fi'
import { sortOptions, statusTabs, type OrderStatusFilter, formatNumber } from './orderUtils'

interface OrdersControlsProps {
  searchKeyword: string
  sortBy: string
  sortDirection: 'ASC' | 'DESC'
  statusFilter: OrderStatusFilter
  totalRecords: number
  searchPlaceholder: string
  actions?: ReactNode
  compact?: boolean
  onSearchChange: (value: string) => void
  onSortChange: (value: string) => void
  onSortDirectionToggle: () => void
  onStatusFilterChange: (value: OrderStatusFilter) => void
}

const statusIcons: Record<OrderStatusFilter, ReactNode> = {
  ALL: <FiGrid className="h-3 w-3" />,
  PENDING: <FiClock className="h-3 w-3" />,
  GENERATED: <FiCheckCircle className="h-3 w-3" />,
  ERROR: <FiAlertCircle className="h-3 w-3" />,
}

export default function OrdersControls({
  searchKeyword,
  sortBy,
  sortDirection,
  statusFilter,
  totalRecords,
  searchPlaceholder,
  actions,
  compact = false,
  onSearchChange,
  onSortChange,
  onSortDirectionToggle,
  onStatusFilterChange,
}: OrdersControlsProps) {
  return (
    <section className={`rounded-2xl border border-slate-200 bg-white shadow-sm ${compact ? 'p-4' : 'p-5'}`}>
      <div className={`flex flex-col ${compact ? 'gap-3' : 'gap-4'}`}>
        <div className={`flex flex-col ${compact ? 'gap-2.5' : 'gap-3'} xl:flex-row xl:items-center xl:justify-between`}>
          <div className="flex flex-wrap gap-1.5">
            {statusTabs.map((status) => (
              <button
                key={status}
                type="button"
                onClick={() => onStatusFilterChange(status)}
                className={`inline-flex items-center gap-1.5 rounded-xl font-semibold transition ${
                  compact ? 'px-2.5 py-1.5 text-[11px] sm:px-3 sm:text-xs' : 'px-3 py-2 text-xs sm:px-3.5 sm:text-sm'
                } ${
                  statusFilter === status
                    ? 'bg-[#1f150c] text-white'
                    : 'bg-slate-100 text-slate-600 hover:bg-slate-200 hover:text-slate-950'
                }`}
              >
                {statusIcons[status]}
                {status}
              </button>
            ))}
          </div>

          {actions ? <div className={`flex flex-wrap items-center ${compact ? 'gap-2' : 'gap-2.5'}`}>{actions}</div> : null}
        </div>

        <div className={`grid ${compact ? 'gap-2.5' : 'gap-3'} lg:grid-cols-[minmax(0,1.3fr)_minmax(300px,0.7fr)]`}>
          <label className="block">
            <span className="sr-only">Search orders</span>
            <input
              type="search"
              value={searchKeyword}
              onChange={(event) => onSearchChange(event.target.value)}
              placeholder={searchPlaceholder}
              className={`w-full rounded-2xl border border-slate-200 bg-slate-50 text-slate-950 outline-none transition focus:border-sky-600 focus:ring-4 focus:ring-sky-100 ${
                compact ? 'px-3 py-2 text-[13px]' : 'px-3.5 py-2.5 text-sm'
              }`}
            />
          </label>

          <div className={`grid ${compact ? 'gap-2.5' : 'gap-3'} sm:grid-cols-[minmax(0,1fr)_auto]`}>
            <select
              value={sortBy}
              onChange={(event) => onSortChange(event.target.value)}
              className={`rounded-2xl border border-slate-200 bg-white text-slate-900 outline-none transition focus:border-sky-600 focus:ring-4 focus:ring-sky-100 ${
                compact ? 'px-3 py-2 text-[13px]' : 'px-3.5 py-2.5 text-sm'
              }`}
            >
              {sortOptions.map((option) => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>

            <button
              type="button"
              onClick={onSortDirectionToggle}
              className={`rounded-2xl border border-slate-200 bg-white font-semibold text-slate-700 transition hover:bg-slate-50 ${
                compact ? 'px-3 py-2 text-[13px]' : 'px-3.5 py-2.5 text-sm'
              }`}
            >
              {sortDirection === 'ASC' ? 'Ascending' : 'Descending'}
            </button>
          </div>
        </div>

        <div className={`flex flex-wrap items-center gap-2 text-slate-500 ${compact ? 'text-[11px] sm:text-xs' : 'text-xs sm:text-sm'}`}>
          <span>{formatNumber(totalRecords)} orders available</span>
          <span className="text-slate-300">•</span>
          <span>{statusFilter} filter</span>
        </div>
      </div>
    </section>
  )
}
