import { createAsyncThunk, createSlice } from '@reduxjs/toolkit'
import {
  carrierService,
  type Carrier,
  type CarrierConnectionPayload,
  type CarrierStatus,
} from '../api/carrierService'

interface CarrierState {
  carriers: Carrier[]
  carrierStatus: CarrierStatus | null
  loading: boolean
  error: string | null
}

const initialState: CarrierState = {
  carriers: [],
  carrierStatus: null,
  loading: false,
  error: null,
}

const getErrorMessage = (error: unknown) =>
  error instanceof Error && error.message ? error.message : 'Something went wrong while updating carriers'

export const fetchCarriers = createAsyncThunk<Carrier[], void, { rejectValue: string }>(
  'carriers/fetchCarriers',
  async (_, { rejectWithValue }) => {
    try {
      return await carrierService.getAvailableCarriers()
    } catch (error) {
      return rejectWithValue(getErrorMessage(error))
    }
  }
)

export const getCarrierStatus = createAsyncThunk<CarrierStatus | null, void, { rejectValue: string }>(
  'carriers/getCarrierStatus',
  async (_, { rejectWithValue }) => {
    try {
      return await carrierService.getCarrierStatus()
    } catch (error) {
      return rejectWithValue(getErrorMessage(error))
    }
  }
)

export const connectCarrier = createAsyncThunk<
  CarrierStatus | null,
  CarrierConnectionPayload,
  { rejectValue: string }
>('carriers/connectCarrier', async (payload, { rejectWithValue }) => {
  try {
    return await carrierService.connectCarrier(payload)
  } catch (error) {
    return rejectWithValue(getErrorMessage(error))
  }
})

export const disconnectCarrier = createAsyncThunk<
  CarrierStatus | null,
  string | undefined,
  { rejectValue: string }
>('carriers/disconnectCarrier', async (carrierCode, { rejectWithValue }) => {
  try {
    return await carrierService.disconnectCarrier(carrierCode)
  } catch (error) {
    return rejectWithValue(getErrorMessage(error))
  }
})

const carrierSlice = createSlice({
  name: 'carriers',
  initialState,
  reducers: {
    clearError: (state) => {
      state.error = null
    },
  },
  extraReducers: (builder) => {
    builder
      .addCase(fetchCarriers.pending, (state) => {
        state.loading = true
        state.error = null
      })
      .addCase(fetchCarriers.fulfilled, (state, action) => {
        state.loading = false
        state.carriers = action.payload
      })
      .addCase(fetchCarriers.rejected, (state, action) => {
        state.loading = false
        state.error = action.payload || 'Failed to fetch carriers'
      })
      .addCase(getCarrierStatus.pending, (state) => {
        state.loading = true
        state.error = null
      })
      .addCase(getCarrierStatus.fulfilled, (state, action) => {
        state.loading = false
        state.carrierStatus = action.payload
      })
      .addCase(getCarrierStatus.rejected, (state, action) => {
        state.loading = false
        state.error = action.payload || 'Failed to fetch carrier status'
      })
      .addCase(connectCarrier.pending, (state) => {
        state.loading = true
        state.error = null
      })
      .addCase(connectCarrier.fulfilled, (state, action) => {
        state.loading = false
        state.carrierStatus = action.payload
      })
      .addCase(connectCarrier.rejected, (state, action) => {
        state.loading = false
        state.error = action.payload || 'Failed to connect carrier'
      })
      .addCase(disconnectCarrier.pending, (state) => {
        state.loading = true
        state.error = null
      })
      .addCase(disconnectCarrier.fulfilled, (state, action) => {
        state.loading = false
        state.carrierStatus = action.payload
      })
      .addCase(disconnectCarrier.rejected, (state, action) => {
        state.loading = false
        state.error = action.payload || 'Failed to disconnect carrier'
      })
  },
})

export const { clearError } = carrierSlice.actions
export default carrierSlice.reducer
