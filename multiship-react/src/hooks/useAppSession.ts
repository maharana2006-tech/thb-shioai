import { useSyncExternalStore } from 'react'
import { normalizeCarrierCode } from '../utils/carrierUtils'

export type CarrierId = 'fedex' | 'ups' | 'usps' | 'dhl'

export interface CarrierDefinition {
  id: CarrierId
  name: string
  description: string
  accountType: string
  serviceWindow: string
  accent: string
}

export interface CarrierConnection extends CarrierDefinition {
  connectedAt: string
  connectionName: string
  credentials: Record<string, string>
}

interface SessionSnapshot {
  token: string | null
  username: string | null
  role: string | null
  connectedCarriers: CarrierConnection[]
  hasConnectedCarrier: boolean
}

const STORAGE_KEYS = {
  token: 'multiship_token',
  username: 'multiship_user',
  role: 'multiship_role',
  carriers: 'multiship_connected_carriers',
} as const

const SESSION_EVENT = 'multiship:session-change'

const carrierCatalog: CarrierDefinition[] = [
  {
    id: 'fedex',
    name: 'FedEx',
    description: 'Priority, ground, and express parcel services.',
    accountType: 'API account',
    serviceWindow: 'Real-time label generation',
    accent: 'from-orange-500/20 via-orange-400/10 to-transparent',
  },
  {
    id: 'ups',
    name: 'UPS',
    description: 'Domestic and cross-border network coverage.',
    accountType: 'Client account',
    serviceWindow: 'Daily pickup ready',
    accent: 'from-amber-500/20 via-amber-300/10 to-transparent',
  },
  {
    id: 'usps',
    name: 'USPS',
    description: 'Cost-efficient lightweight parcel routing.',
    accountType: 'Default account',
    serviceWindow: 'Residential delivery optimized',
    accent: 'from-sky-500/20 via-sky-400/10 to-transparent',
  },
]

const emptySnapshot: SessionSnapshot = {
  token: null,
  username: null,
  role: null,
  connectedCarriers: [],
  hasConnectedCarrier: false,
}

let cachedSnapshot = emptySnapshot
let cachedSnapshotKey = 'initial'

const isBrowser = () => typeof window !== 'undefined'

const emitSessionChange = () => {
  if (!isBrowser()) {
    return
  }

  window.dispatchEvent(new Event(SESSION_EVENT))
}

const readStorageValue = (key: string) => {
  if (!isBrowser()) {
    return null
  }

  return window.localStorage.getItem(key)
}

const readConnectedCarriers = (rawValue: string | null): CarrierConnection[] => {
  if (!rawValue) {
    return []
  }

  try {
    const parsedValue = JSON.parse(rawValue)

    if (!Array.isArray(parsedValue)) {
      return []
    }

    return parsedValue.flatMap((item) => {
      if (!item || typeof item !== 'object' || typeof item.id !== 'string') {
        return []
      }

      const definition = carrierCatalog.find((carrier) => carrier.id === item.id)

      if (!definition) {
        return []
      }

      return [
        {
          ...definition,
          connectedAt: typeof item.connectedAt === 'string' ? item.connectedAt : new Date().toISOString(),
          connectionName:
            typeof item.connectionName === 'string' && item.connectionName.trim()
              ? item.connectionName
              : `${definition.name} Workspace`,
          credentials:
            item.credentials && typeof item.credentials === 'object' && !Array.isArray(item.credentials)
              ? Object.entries(item.credentials).reduce<Record<string, string>>((accumulator, [key, value]) => {
                  if (typeof value === 'string') {
                    accumulator[key] = value
                  }

                  return accumulator
                }, {})
              : {},
        },
      ]
    })
  } catch {
    return []
  }
}

const writeConnectedCarriers = (connections: CarrierConnection[]) => {
  if (!isBrowser()) {
    return
  }

  window.localStorage.setItem(STORAGE_KEYS.carriers, JSON.stringify(connections))
  emitSessionChange()
}

const getSnapshot = (): SessionSnapshot => {
  const token = readStorageValue(STORAGE_KEYS.token)
  const username = readStorageValue(STORAGE_KEYS.username)
  const role = readStorageValue(STORAGE_KEYS.role)
  const carriersRaw = readStorageValue(STORAGE_KEYS.carriers)
  const nextSnapshotKey = JSON.stringify([token, username, role, carriersRaw])

  if (nextSnapshotKey === cachedSnapshotKey) {
    return cachedSnapshot
  }

  const connectedCarriers = readConnectedCarriers(carriersRaw)

  cachedSnapshotKey = nextSnapshotKey
  cachedSnapshot = {
    token,
    username,
    role,
    connectedCarriers,
    hasConnectedCarrier: connectedCarriers.length > 0,
  }

  return cachedSnapshot
}

const subscribe = (callback: () => void) => {
  if (!isBrowser()) {
    return () => undefined
  }

  const handleChange = () => callback()

  window.addEventListener('storage', handleChange)
  window.addEventListener(SESSION_EVENT, handleChange)

  return () => {
    window.removeEventListener('storage', handleChange)
    window.removeEventListener(SESSION_EVENT, handleChange)
  }
}

export const useAppSession = () => useSyncExternalStore(subscribe, getSnapshot, () => emptySnapshot)

export const getAuthenticatedHomePath = (hasConnectedCarrier: boolean) =>
  hasConnectedCarrier ? '/dashboard' : '/settings/carriers'

export const storeAuthSession = (payload: { token: string; username: string; role: string }) => {
  if (!isBrowser()) {
    return
  }

  window.localStorage.setItem(STORAGE_KEYS.token, payload.token)
  window.localStorage.setItem(STORAGE_KEYS.username, payload.username)
  window.localStorage.setItem(STORAGE_KEYS.role, payload.role)
  emitSessionChange()
}

export const clearAuthSession = () => {
  if (!isBrowser()) {
    return
  }

  window.localStorage.removeItem(STORAGE_KEYS.token)
  window.localStorage.removeItem(STORAGE_KEYS.username)
  window.localStorage.removeItem(STORAGE_KEYS.role)
  emitSessionChange()
}

export const connectCarrier = (
  carrierId: CarrierId,
  config: { connectionName: string; credentials: Record<string, string> }
) => {
  const definition = carrierCatalog.find((carrier) => carrier.id === carrierId)

  if (!definition) {
    return
  }

  const existingConnections = readConnectedCarriers(readStorageValue(STORAGE_KEYS.carriers))

  if (existingConnections.some((carrier) => carrier.id === carrierId)) {
    return
  }

  writeConnectedCarriers([
    ...existingConnections,
    {
      ...definition,
      connectedAt: new Date().toISOString(),
      connectionName: config.connectionName,
      credentials: config.credentials,
    },
  ])
}

export const disconnectCarrier = (carrierId: CarrierId) => {
  const existingConnections = readConnectedCarriers(readStorageValue(STORAGE_KEYS.carriers))
  const filteredConnections = existingConnections.filter((carrier) => carrier.id !== carrierId)

  writeConnectedCarriers(filteredConnections)
}

export const syncCarrierSession = (payload: {
  carrierCode: string | null
  carrierName?: string | null
  accountNumber?: string | null
  environment?: string | null
  connectedAt?: string | null
  connected: boolean
}) => {
  if (!payload.connected || !payload.carrierCode) {
    writeConnectedCarriers([])
    return
  }

  const carrierId = normalizeCarrierCode(payload.carrierCode)

  if (!carrierId) {
    writeConnectedCarriers([])
    return
  }

  const definition = carrierCatalog.find((carrier) => carrier.id === carrierId)

  if (!definition) {
    writeConnectedCarriers([])
    return
  }

  writeConnectedCarriers([
    {
      ...definition,
      connectedAt: payload.connectedAt || new Date().toISOString(),
      connectionName: payload.carrierName || `${definition.name} Workspace`,
      credentials: {
        accountNumber: payload.accountNumber || '',
        environment: payload.environment || '',
      },
    },
  ])
}

export { carrierCatalog }
