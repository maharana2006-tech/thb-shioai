import type { ReactNode } from 'react'
import type { Order } from '../../api/orderService'
import {
  FiCalendar,
  FiCreditCard,
  FiFlag,
  FiHash,
  FiLink2,
  FiMapPin,
  FiPackage,
  FiTruck,
  FiUser,
} from 'react-icons/fi'
import CarrierLogo from './CarrierLogo'
import OrderStatusBadge from './OrderStatusBadge'
import { formatOrderDate, formatWeight, getCarrierAccountLabel } from './orderUtils'

interface OrdersTableProps {
  orders: Order[]
  loading: boolean
  mode: 'labels' | 'tracking'
  emptyText: string
  compact?: boolean
  renderAccount?: (order: Order) => ReactNode
  renderConnectionStatus?: (order: Order) => ReactNode
  renderAction?: (order: Order) => ReactNode
}

function HeaderLabel({
  icon,
  label,
  align = 'left',
}: {
  icon: ReactNode
  label: string
  align?: 'left' | 'right'
}) {
  return (
    <span
      className={`inline-flex items-center gap-1.5 ${align === 'right' ? 'justify-end' : 'justify-start'}`}
    >
      <span className="text-slate-400">{icon}</span>
      <span>{label}</span>
    </span>
  )
}

export default function OrdersTable({
  orders,
  loading,
  mode,
  emptyText,
  compact = false,
  renderAccount,
  renderConnectionStatus,
  renderAction,
}: OrdersTableProps) {
  return (
    <div className="overflow-x-auto">
      <table className={`w-full min-w-[980px] text-slate-700 ${compact ? 'text-[12px]' : 'text-[13px]'}`}>
        <thead className={`border-b border-slate-200 text-left uppercase tracking-[0.16em] text-slate-500 ${compact ? 'text-[10px]' : 'text-[11px]'}`}>
          <tr>
            <th className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
              <HeaderLabel icon={<FiHash className="h-3 w-3" />} label="Order #" />
            </th>
            <th className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
              <HeaderLabel icon={<FiUser className="h-3 w-3" />} label="Customer" />
            </th>
            {mode === 'labels' ? (
              <>
                <th className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                  <HeaderLabel icon={<FiMapPin className="h-3 w-3" />} label="City" />
                </th>
                <th className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                  <HeaderLabel icon={<FiFlag className="h-3 w-3" />} label="State" />
                </th>
                <th className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                  <HeaderLabel icon={<FiTruck className="h-3 w-3" />} label="Carrier" />
                </th>
                <th className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                  <HeaderLabel icon={<FiLink2 className="h-3 w-3" />} label="Connection" />
                </th>
                <th className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                  <HeaderLabel icon={<FiCreditCard className="h-3 w-3" />} label="Carrier Account" />
                </th>
                <th className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                  <HeaderLabel icon={<FiPackage className="h-3 w-3" />} label="Weight" />
                </th>
              </>
            ) : (
              <>
                <th className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                  <HeaderLabel icon={<FiMapPin className="h-3 w-3" />} label="Destination" />
                </th>
                <th className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                  <HeaderLabel icon={<FiTruck className="h-3 w-3" />} label="Carrier" />
                </th>
                <th className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                  <HeaderLabel icon={<FiCreditCard className="h-3 w-3" />} label="Account" />
                </th>
                <th className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                  <HeaderLabel icon={<FiCalendar className="h-3 w-3" />} label="Created" />
                </th>
              </>
            )}
            <th className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
              <HeaderLabel icon={<FiPackage className="h-3 w-3" />} label="Status" />
            </th>
            <th className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
              <HeaderLabel icon={<FiLink2 className="h-3 w-3" />} label="Tracking" />
            </th>
            <th className={compact ? 'px-2.5 py-3 text-right' : 'px-3 py-3.5 text-right'}>
              <HeaderLabel icon={<FiPackage className="h-3 w-3" />} label="Action" align="right" />
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-200">
          {orders.map((order) => (
            <tr key={order.orderDetails.orderNo} className="transition hover:bg-slate-50/80">
              <td className={`${compact ? 'px-2.5 py-3' : 'px-3 py-3.5'} font-semibold text-slate-950`}>{order.orderDetails.orderNo}</td>
              <td className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>{order.orderDetails.customerCode}</td>
              {mode === 'labels' ? (
                <>
                  <td className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>{order.shippingDetails.city}</td>
                  <td className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>{order.shippingDetails.state}</td>
                  <td className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                    <div className="inline-flex items-center gap-2">
                      <CarrierLogo
                        carrierId={order.shippingDetails.shipVia || 'fedex'}
                        size={16}
                        className="rounded-sm"
                      />
                      <span>{order.shippingDetails.shipVia || 'Not assigned'}</span>
                    </div>
                  </td>
                  <td className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                    {renderConnectionStatus ? renderConnectionStatus(order) : <span className="text-slate-400">Unknown</span>}
                  </td>
                  <td className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                    {renderAccount ? renderAccount(order) : getCarrierAccountLabel(order)}
                  </td>
                  <td className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>{formatWeight(order.shippingDetails.weight)}</td>
                </>
              ) : (
                <>
                  <td className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                    {order.shippingDetails.city}, {order.shippingDetails.state}
                  </td>
                  <td className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                    <div className="inline-flex items-center gap-2">
                      <CarrierLogo
                        carrierId={order.shippingDetails.shipVia || 'fedex'}
                        size={16}
                        className="rounded-sm"
                      />
                      <span>{order.shippingDetails.shipVia || 'Not assigned'}</span>
                    </div>
                  </td>
                  <td className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                    {renderAccount ? renderAccount(order) : getCarrierAccountLabel(order)}
                  </td>
                  <td className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>{formatOrderDate(order.orderDetails.createdDate)}</td>
                </>
              )}
              <td className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                <OrderStatusBadge status={order.labelDetails.status} />
              </td>
              <td className={compact ? 'px-2.5 py-3' : 'px-3 py-3.5'}>
                {order.labelDetails.trackingNumber ? (
                  <a
                    href={order.labelDetails.trackingUrl || '#'}
                    target="_blank"
                    rel="noreferrer"
                    className="inline-flex items-center gap-1.5 font-medium text-sky-700 hover:text-sky-800"
                  >
                    <FiLink2 className="h-3 w-3" />
                    {order.labelDetails.trackingNumber}
                  </a>
                ) : (
                  <span className="inline-flex items-center gap-1.5 text-slate-400">
                    <FiLink2 className="h-3 w-3" />
                    Not available
                  </span>
                )}
              </td>
              <td className={compact ? 'px-2.5 py-3 text-right' : 'px-3 py-3.5 text-right'}>{renderAction ? renderAction(order) : null}</td>
            </tr>
          ))}

          {!orders.length ? (
            <tr>
              <td colSpan={mode === 'labels' ? 11 : 9} className={`${compact ? 'px-2.5 py-8 text-[13px]' : 'px-3 py-10 text-sm'} text-center text-slate-500`}>
                {loading ? 'Loading orders...' : emptyText}
              </td>
            </tr>
          ) : null}
        </tbody>
      </table>
    </div>
  )
}
