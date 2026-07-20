import type { Order } from '../../api/orderService'

export const statusTabs = ['ALL', 'PENDING', 'GENERATED', 'ERROR'] as const
export type OrderStatusFilter = (typeof statusTabs)[number]

export const sortOptions = [
  { value: 'orderNo', label: 'Order #' },
  { value: 'city', label: 'City' },
  { value: 'weight', label: 'Weight' },
  { value: 'status', label: 'Status' },
]

const statusLabels: Record<string, string> = {
  PENDING: 'Pending',
  GENERATED: 'Generated',
  ERROR: 'Error',
}

export const statusClasses: Record<string, string> = {
  PENDING: 'bg-amber-100 text-amber-700',
  GENERATED: 'bg-emerald-100 text-emerald-700',
  ERROR: 'bg-rose-100 text-rose-700',
}

export const formatNumber = (value: number) => value.toLocaleString('en-US')

export const formatWeight = (weight: number) => `${weight.toFixed(2)} kg`

export const formatOrderDate = (value: string) => {
  const parsedDate = new Date(value)

  if (Number.isNaN(parsedDate.getTime())) {
    return value
  }

  return parsedDate.toLocaleDateString('en-US', {
    month: 'short',
    day: 'numeric',
    year: 'numeric',
  })
}

export const getOrderStatusLabel = (status: string) => statusLabels[status] ?? status

export const getCarrierAccountLabel = (order: Order) =>
  order.carrierAccount?.accountCode || order.shippingDetails.shipViaDescription || 'Default Account'

export const getTrackingLabel = (order: Order) => order.labelDetails.trackingNumber || 'Not available'
