import { useEffect, useState } from 'react'
import { orderService } from '../api/orderService'

/**
 * Distinct tenant (customer) codes discovered from the order book.
 * The backend keys tenants by COALESCE(tenant_id, cust_no), so customer
 * codes on orders double as tenant identifiers.
 */
export const useKnownTenants = () => {
  const [tenants, setTenants] = useState<string[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false

    orderService
      .listOrders({ page: 0, size: 100, sortBy: 'orderNo', sortDirection: 'ASC' })
      .then((response) => {
        if (cancelled) {
          return
        }

        const codes = new Set<string>()
        response.data.content.forEach((order) => {
          const code = order.orderDetails.customerCode?.trim().toUpperCase()
          if (code) {
            codes.add(code)
          }
        })
        setTenants([...codes].sort())
      })
      .catch(() => {
        // Tenant discovery is best-effort; pages degrade to manual entry.
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false)
        }
      })

    return () => {
      cancelled = true
    }
  }, [])

  return { tenants, loading }
}
