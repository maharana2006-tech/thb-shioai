import { useCallback, useEffect, useMemo } from 'react'
import { notify } from '../utils/notify'
import { useAppDispatch, useAppSelector } from '../store/hooks'
import {
  clearError as clearErrorAction,
  fetchDashboardStats as fetchDashboardStatsThunk,
  fetchOrders as fetchOrdersThunk,
  generateLabel as generateLabelThunk,
  setPage,
  setPageSize,
  setSortBy,
  setSortDirection,
  setStatusFilter,
  setSearchKeyword,
} from '../store/orderSlice'
import type { Order, OrderSummary, DashboardStats, LabelGenerationResponse } from '../api/orderService'
import type { RootState } from '../store/store'

interface UiOrder {
  id: string
  customer: string
  sourceAccount: string
  destination: string
  carrier: string
  carrierType: string
  status: string
  weight: string
  tracking: string
}

interface UseOrdersReturn {
  rawOrders: Order[]
  orders: UiOrder[]
  totalRecords: number
  pageNumber: number
  pageSize: number
  totalPages: number
  sortBy: string
  sortDirection: 'ASC' | 'DESC'
  statusFilter: 'ALL' | 'PENDING' | 'GENERATED' | 'ERROR'
  searchKeyword: string
  summary: OrderSummary | null
  stats: DashboardStats | null
  loading: boolean
  error: string | null
  fetchOrders: () => Promise<void>
  fetchOrdersByStatus: (status: string) => Promise<void>
  fetchDashboardStats: () => Promise<void>
  generateLabelForOrder: (orderNo: string | number) => Promise<LabelGenerationResponse | void>
  searchOrders: (keyword: string) => Promise<void>
  setPage: (page: number) => void
  setPageSize: (size: number) => void
  setSortBy: (sortBy: string) => void
  setSortDirection: (direction: 'ASC' | 'DESC') => void
  setStatusFilter: (status: 'ALL' | 'PENDING' | 'GENERATED' | 'ERROR') => void
  setSearchKeyword: (keyword: string) => void
  clearError: () => void
}

const normalizeOrder = (order: any): UiOrder => ({
  id: `${order.orderDetails.orderNo}`,
  customer: order.orderDetails.customerCode,
  sourceAccount: order.shippingDetails.shipViaDescription || order.shippingDetails.shipVia || 'Direct API',
  destination: `${order.shippingDetails.city}, ${order.shippingDetails.state} (${order.shippingDetails.zipCode})`,
  carrier: order.shippingDetails.shipVia || 'Unknown Carrier',
  carrierType: order.shippingDetails.shipViaDescription ? 'Client Account' : 'Default Account',
  status: order.labelDetails?.status || order.orderDetails.status || 'Unknown',
  weight: `${order.shippingDetails.weight} kg`,
  tracking: order.labelDetails?.trackingNumber || 'N/A',
})

export const useOrders = (): UseOrdersReturn => {
  const dispatch = useAppDispatch()
  const {
    orders: rawOrders,
    totalRecords,
    pageNumber,
    pageSize,
    totalPages,
    sortBy,
    sortDirection,
    statusFilter,
    searchKeyword,
    summary,
    stats,
    loading,
    error,
  } = useAppSelector((state: RootState) => state.orders)

  const orders = useMemo(() => rawOrders.map((order: any) => normalizeOrder(order)), [rawOrders])

  const loadOrders = useCallback(async () => {
    try {
      // One request: status filter and keyword search compose server-side.
      await dispatch(
        fetchOrdersThunk({
          page: pageNumber,
          size: pageSize,
          sortBy,
          sortDirection,
          status: statusFilter !== 'ALL' ? statusFilter : undefined,
          search: searchKeyword.trim() || undefined,
        })
      ).unwrap()
    } catch {
      // errors are surfaced by the slice
    }
  }, [dispatch, pageNumber, pageSize, sortBy, sortDirection, statusFilter, searchKeyword])

  const fetchOrders = useCallback(async () => {
    try {
      await loadOrders()
    } catch (err: any) {
      notify.error(err.message || 'Failed to fetch orders')
    }
  }, [loadOrders])

  const fetchOrdersByStatus = useCallback(
    async (status: string) => {
      try {
        dispatch(setStatusFilter(status as 'ALL' | 'PENDING' | 'GENERATED' | 'ERROR'))
      } catch (err: any) {
        notify.error(err.message || `Failed to switch to ${status} orders`)
      }
    },
    [dispatch]
  )

  const searchOrders = useCallback(
    async (keyword: string) => {
      try {
        dispatch(setSearchKeyword(keyword))
      } catch (err: any) {
        notify.error(err.message || 'Failed to search orders')
      }
    },
    [dispatch]
  )

  const fetchDashboardStats = useCallback(async () => {
    try {
      await dispatch(fetchDashboardStatsThunk()).unwrap()
    } catch (err: any) {
      notify.error(err.message || 'Failed to fetch dashboard stats')
    }
  }, [dispatch])

  const generateLabelForOrder = useCallback(
    async (orderNo: string | number) => {
      const id = typeof orderNo === 'string' ? Number(orderNo) : orderNo
      try {
        const result = await dispatch(generateLabelThunk(id)).unwrap()
        notify.success(`Label generated successfully for order #${id}`)
        await loadOrders()
        return result
      } catch (error) {
        const message = error instanceof Error ? error.message : String(error)
        notify.error(message || `Failed to generate label for order #${id}`)
      }
    },
    [dispatch, loadOrders]
  )

  const setPageNumber = useCallback(
    (page: number) => {
      dispatch(setPage(Math.max(page - 1, 0)))
    },
    [dispatch]
  )

  const setPageSizeValue = useCallback(
    (size: number) => {
      dispatch(setPageSize(size))
    },
    [dispatch]
  )

  const setSortByValue = useCallback(
    (value: string) => {
      dispatch(setSortBy(value))
    },
    [dispatch]
  )

  const setSortDirectionValue = useCallback(
    (direction: 'ASC' | 'DESC') => {
      dispatch(setSortDirection(direction))
    },
    [dispatch]
  )

  const setStatusFilterValue = useCallback(
    (status: 'ALL' | 'PENDING' | 'GENERATED' | 'ERROR') => {
      dispatch(setStatusFilter(status))
    },
    [dispatch]
  )

  const setSearchKeywordValue = useCallback(
    (keyword: string) => {
      dispatch(setSearchKeyword(keyword))
    },
    [dispatch]
  )

  const clearError = useCallback(() => {
    dispatch(clearErrorAction())
  }, [dispatch])

  useEffect(() => {
    void loadOrders()
  }, [loadOrders])

  useEffect(() => {
    dispatch(fetchDashboardStatsThunk())
  }, [dispatch])

  return {
    rawOrders: rawOrders as Order[],
    orders,
    totalRecords,
    pageNumber,
    pageSize,
    totalPages,
    sortBy,
    sortDirection,
    statusFilter,
    searchKeyword,
    summary,
    stats,
    loading,
    error,
    fetchOrders,
    fetchOrdersByStatus,
    fetchDashboardStats,
    generateLabelForOrder,
    searchOrders,
    setPage: setPageNumber,
    setPageSize: setPageSizeValue,
    setSortBy: setSortByValue,
    setSortDirection: setSortDirectionValue,
    setStatusFilter: setStatusFilterValue,
    setSearchKeyword: setSearchKeywordValue,
    clearError,
  }
}
