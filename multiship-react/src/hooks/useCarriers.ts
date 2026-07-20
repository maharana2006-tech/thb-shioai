import { useCallback } from 'react'
import { toast } from 'react-hot-toast'
import type { CarrierConnectionPayload } from '../api/carrierService'
import {
  clearError as clearCarrierErrorAction,
  connectCarrier as connectCarrierThunk,
  disconnectCarrier as disconnectCarrierThunk,
  fetchCarriers as fetchCarriersThunk,
  getCarrierStatus as getCarrierStatusThunk,
} from '../store/carrierSlice'
import { useAppDispatch, useAppSelector } from '../store/hooks'
import type { RootState } from '../store/store'

const getErrorMessage = (error: unknown, fallback: string) =>
  typeof error === 'string'
    ? error
    : error instanceof Error && error.message
    ? error.message
    : fallback

export const useCarriers = () => {
  const dispatch = useAppDispatch()
  const { carriers, carrierStatus, loading, error } = useAppSelector((state: RootState) => state.carriers)

  const fetchCarriers = useCallback(async () => {
    try {
      return await dispatch(fetchCarriersThunk()).unwrap()
    } catch (error) {
      toast.error(getErrorMessage(error, 'Failed to fetch available carriers'))
      return []
    }
  }, [dispatch])

  const fetchCarrierStatus = useCallback(async () => {
    try {
      return await dispatch(getCarrierStatusThunk()).unwrap()
    } catch (error) {
      toast.error(getErrorMessage(error, 'Failed to fetch carrier status'))
      return null
    }
  }, [dispatch])

  const connectToCarrier = useCallback(
    async (payload: CarrierConnectionPayload) => {
      try {
        return await dispatch(connectCarrierThunk(payload)).unwrap()
      } catch (error) {
        toast.error(getErrorMessage(error, `Failed to connect ${payload.carrierCode}`))
        return null
      }
    },
    [dispatch]
  )

  const disconnectFromCarrier = useCallback(
    async (carrierCode?: string) => {
      try {
        return await dispatch(disconnectCarrierThunk(carrierCode)).unwrap()
      } catch (error) {
        toast.error(getErrorMessage(error, 'Failed to disconnect carrier'))
        return null
      }
    },
    [dispatch]
  )

  const clearError = useCallback(() => {
    dispatch(clearCarrierErrorAction())
  }, [dispatch])

  return {
    carriers,
    carrierStatus,
    loading,
    error,
    fetchCarriers,
    fetchCarrierStatus,
    connectToCarrier,
    disconnectFromCarrier,
    clearError,
  }
}
