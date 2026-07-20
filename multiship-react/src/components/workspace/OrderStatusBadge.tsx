import { getOrderStatusLabel, statusClasses } from './orderUtils'

export default function OrderStatusBadge({ status }: { status: string }) {
  return (
    <span
      className={`inline-flex rounded-full px-3 py-1 text-xs font-semibold ${
        statusClasses[status] ?? 'bg-slate-100 text-slate-700'
      }`}
    >
      {getOrderStatusLabel(status)}
    </span>
  )
}
