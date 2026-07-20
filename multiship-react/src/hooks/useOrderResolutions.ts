import { useCallback, useState } from 'react'
import { accountRefService, type OrderAccountResolution } from '../api/accountRefService'

/**
 * Bulk "which account will this order ship with?" previews, keyed by order number.
 * Drives the scenario badges on order tables and decides whether Generate opens
 * the fill-up form (NEEDS_DETAILS) or fires immediately.
 */
export const useOrderResolutions = () => {
  const [resolutions, setResolutions] = useState<Record<number, OrderAccountResolution>>({})
  const [loading, setLoading] = useState(false)

  const refreshResolutions = useCallback(async (orderNos: number[]) => {
    const unique = [...new Set(orderNos)].filter((orderNo) => Number.isFinite(orderNo))

    if (!unique.length) {
      return
    }

    setLoading(true)

    try {
      const results = await accountRefService.resolveOrders(unique)
      setResolutions((current) => {
        const next = { ...current }
        results.forEach((resolution) => {
          next[resolution.orderNo] = resolution
        })
        return next
      })
    } catch {
      // Badges degrade to "checking"; generation still resolves server-side.
    } finally {
      setLoading(false)
    }
  }, [])

  const getResolution = useCallback(
    (orderNo: number): OrderAccountResolution | undefined => resolutions[orderNo],
    [resolutions]
  )

  return { getResolution, refreshResolutions, resolutionsLoading: loading }
}
