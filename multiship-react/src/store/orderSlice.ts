import { createAsyncThunk, createSlice, type PayloadAction } from '@reduxjs/toolkit'
import {
  orderService,
  type Order,
  type OrderListParams,
  type OrderSummary,
  type DashboardStats,
} from '../api/orderService'

// Async Thunks

/**
 * One thunk for every order-list view: server-side pagination, sorting,
 * status filter, and keyword search compose in a single request.
 */
export const fetchOrders = createAsyncThunk(
  'orders/fetchList',
  async (params: OrderListParams) => {
    const response = await orderService.listOrders(params)
    return response.data
  }
)

export const fetchDashboardStats = createAsyncThunk(
  'orders/fetchStats',
  async () => {
    const response = await orderService.getDashboardStats()
    return response.data
  }
)

export const generateLabel = createAsyncThunk(
  'orders/generateLabel',
  async (orderNo: number, { rejectWithValue }) => {
    try {
      const response = await orderService.generateLabel(orderNo)
      return response.data
    } catch (error) {
      return rejectWithValue(error instanceof Error ? error.message : 'Failed to generate label')
    }
  }
)

interface OrderState {
  orders: Order[]
  totalRecords: number
  pageNumber: number
  pageSize: number
  totalElements: number
  totalPages: number
  sortBy: string
  sortDirection: 'ASC' | 'DESC'
  statusFilter: 'ALL' | 'PENDING' | 'GENERATED' | 'ERROR'
  searchKeyword: string
  summary: OrderSummary | null
  stats: DashboardStats | null
  loading: boolean
  error: string | null
}

const initialState: OrderState = {
  orders: [],
  totalRecords: 0,
  pageNumber: 0,
  pageSize: 10,
  totalElements: 0,
  totalPages: 1,
  sortBy: 'orderNo',
  sortDirection: 'ASC',
  statusFilter: 'ALL',
  searchKeyword: '',
  summary: null,
  stats: null,
  loading: false,
  error: null,
}

const orderSlice = createSlice({
  name: 'orders',
  initialState,
  reducers: {
    clearOrders: (state) => {
      state.orders = []
      state.totalRecords = 0
      state.pageNumber = 0
      state.pageSize = 10
      state.totalElements = 0
      state.totalPages = 1
      state.sortBy = 'orderNo'
      state.sortDirection = 'ASC'
      state.statusFilter = 'ALL'
      state.searchKeyword = ''
      state.summary = null
      state.stats = null
      state.error = null
      state.loading = false
    },
    clearError: (state) => {
      state.error = null
    },
    setPage: (state, action: PayloadAction<number>) => {
      state.pageNumber = action.payload
    },
    setPageSize: (state, action: PayloadAction<number>) => {
      state.pageSize = action.payload
      state.pageNumber = 0
    },
    setSortBy: (state, action: PayloadAction<string>) => {
      state.sortBy = action.payload
      state.pageNumber = 0
    },
    setSortDirection: (state, action: PayloadAction<'ASC' | 'DESC'>) => {
      state.sortDirection = action.payload
      state.pageNumber = 0
    },
    setStatusFilter: (state, action: PayloadAction<'ALL' | 'PENDING' | 'GENERATED' | 'ERROR'>) => {
      state.statusFilter = action.payload
      state.pageNumber = 0
      state.searchKeyword = ''
    },
    setSearchKeyword: (state, action: PayloadAction<string>) => {
      state.searchKeyword = action.payload
      state.pageNumber = 0
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchOrders.pending, (state) => {
        state.loading = true
        state.error = null
      })
      .addCase(fetchOrders.fulfilled, (state, action) => {
        state.loading = false
        state.orders = action.payload.content
        state.pageNumber = action.payload.pageNumber
        state.pageSize = action.payload.pageSize
        state.totalElements = action.payload.totalElements
        state.totalPages = action.payload.totalPages
        state.totalRecords = action.payload.totalElements
        state.sortBy = action.payload.sortBy
        state.sortDirection = action.payload.sortDirection
      })
      .addCase(fetchOrders.rejected, (state, action) => {
        state.loading = false
        state.error = action.error.message || 'Failed to fetch orders'
      })
      .addCase(fetchDashboardStats.pending, (state) => {
        state.loading = true
        state.error = null
      })
      .addCase(fetchDashboardStats.fulfilled, (state, action) => {
        state.loading = false
        state.stats = action.payload
      })
      .addCase(fetchDashboardStats.rejected, (state, action) => {
        state.loading = false
        state.error = action.error.message || 'Failed to fetch stats'
      })
      .addCase(generateLabel.pending, (state) => {
        state.loading = true
        state.error = null
      })
      .addCase(generateLabel.fulfilled, (state) => {
        state.loading = false
      })
      .addCase(generateLabel.rejected, (state, action) => {
        state.loading = false
        state.error =
          typeof action.payload === 'string'
            ? action.payload
            : action.error.message || 'Failed to generate label'
      })
  },
})

export const {
  clearOrders,
  clearError,
  setPage,
  setPageSize,
  setSortBy,
  setSortDirection,
  setStatusFilter,
  setSearchKeyword,
} = orderSlice.actions
export default orderSlice.reducer
