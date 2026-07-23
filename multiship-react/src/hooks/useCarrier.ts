import { useEffect, useMemo } from 'react'
import { notify } from '../utils/notify'
import type { CarrierConnectionPayload } from '../api/carrierService'
import { syncCarrierSession } from './useAppSession'
import { useCarriers } from './useCarriers'
import { formatCarrierName, normalizeCarrierCode, type SupportedCarrierId } from '../utils/carrierUtils'

export interface CarrierSummary {
  /** Normalized id used for logos/UI (ups/fedex/usps); null when unrecognized. */
  id: SupportedCarrierId | null
  /** Backend carrier code, e.g. UPS / FEDEX / USPS. */
  code: string
  name: string
  description: string | null
  documentationUrl: string | null
  connectionGuide: string | null
  environment: string | null
  /** True when the operator's backend carrier session is connected to this carrier. */
  connected: boolean
}

/**
 * Backend-driven carrier state.
 *
 * Tenant carrier accounts (used for label generation) live on the backend and
 * are selected automatically per order — see useTenantAccounts. This hook only
 * manages the operator's own carrier session and the available-carrier catalog.
 */
export const useCarrier = () => {
  const {
    carrierStatus,
    carriers,
    clearError,
    connectToCarrier,
    disconnectFromCarrier,
    error,
    fetchCarrierStatus,
    fetchCarriers,
    loading,
  } = useCarriers()

  useEffect(() => {
    void fetchCarriers()
    void fetchCarrierStatus()
  }, [fetchCarriers, fetchCarrierStatus])

  const activeCarrierId = normalizeCarrierCode(carrierStatus?.connected ? carrierStatus.carrierCode : null)

  const carrierSummaries = useMemo<CarrierSummary[]>(
    () =>
      carriers.map((carrier) => {
        const id = normalizeCarrierCode(carrier.carrierCode)

        return {
          id,
          code: carrier.carrierCode,
          name: carrier.carrierName || formatCarrierName(carrier.carrierCode),
          description: carrier.description ?? null,
          documentationUrl: carrier.documentationUrl ?? null,
          connectionGuide: carrier.connectionGuide ?? null,
          environment: carrier.environment ?? null,
          connected: Boolean(id && activeCarrierId && id === activeCarrierId),
        }
      }),
    [carriers, activeCarrierId]
  )

  const syncSession = (status: typeof carrierStatus) => {
    syncCarrierSession({
      connected: Boolean(status?.connected),
      carrierCode: status?.carrierCode || null,
      carrierName: status?.carrierName,
      accountNumber: status?.accountNumber,
      environment: status?.environment,
      connectedAt: status?.connectedAt,
    })
  }

  const connectOperator = async (payload: CarrierConnectionPayload) => {
    const result = await connectToCarrier(payload)

    if (!result) {
      return null
    }

    const status = (await fetchCarrierStatus()) || result
    syncSession(status)
    notify.success(`${formatCarrierName(payload.carrierCode)} session connected.`)
    return status
  }

  const disconnectOperator = async () => {
    await disconnectFromCarrier(carrierStatus?.carrierCode || undefined)
    const status = await fetchCarrierStatus()
    syncSession(status?.connected ? status : null)
    notify.success('Carrier session disconnected.')
    return status
  }

  const refresh = async () => {
    const [, status] = await Promise.all([fetchCarriers(), fetchCarrierStatus()])
    syncSession(status)
  }

  return {
    availableCarriers: carriers,
    carrierStatus,
    carrierSummaries,
    activeCarrierId,
    clearError,
    connectOperator,
    disconnectOperator,
    error,
    loading,
    refresh,
  }
}
