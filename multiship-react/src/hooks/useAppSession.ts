import { useSyncExternalStore } from 'react'
import { normalizeCarrierCode } from '../utils/carrierUtils'
import { clearAppStorage } from '../utils/session'

// Internal types — no external caller needs these.
type CarrierId = 'fedex' | 'ups' | 'usps' | 'dhl'

interface CarrierDefinition {
  id: CarrierId
  name: string
  description: string
  accountType: string
  serviceWindow: string
  accent: string
}

interface CarrierConnection extends CarrierDefinition {
  connectedAt: string
  connectionName: string
  credentials: Record<string, string>
}

interface SessionSnapshot {
  username: string | null
  role: string | null
  connectedCarriers: CarrierConnection[]
  hasConnectedCarrier: boolean
}

// Sprint 50 PR Q3 — the JWT itself no longer lives in localStorage;
// it's an httpOnly cookie set by the backend. Only the non-sensitive
// username/role remain here to drive UI gating (role-based nav visibility,
// welcome text, etc.). Route guards gate on `username` presence.
const STORAGE_KEYS = {
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
  const username = readStorageValue(STORAGE_KEYS.username)
  const role = readStorageValue(STORAGE_KEYS.role)
  const carriersRaw = readStorageValue(STORAGE_KEYS.carriers)
  const nextSnapshotKey = JSON.stringify([username, role, carriersRaw])

  if (nextSnapshotKey === cachedSnapshotKey) {
    return cachedSnapshot
  }

  const connectedCarriers = readConnectedCarriers(carriersRaw)

  cachedSnapshotKey = nextSnapshotKey
  cachedSnapshot = {
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

/**
 * Sprint 50 PR Q2 — SPA bootstrap after page refresh. Under cookie-mode
 * auth the JWT is httpOnly, so JS can't tell "am I logged in?" from
 * localStorage alone. Call this once at app root mount; it hits
 * /auth/session which returns 200 + non-sensitive session facts when
 * the cookie is valid, or 401 to trigger the SPA's login redirect.
 *
 * Populates username/role in localStorage (non-sensitive, drives UI
 * gating in a few components). The token stays cookie-only.
 */
export const bootstrapSessionFromCookie = async (): Promise<void> => {
  if (!isBrowser()) return
  try {
    // Sprint 51 FE-M1 — no dev-hostname:8080 fallback (see apiClient.ts).
    const base =
      (import.meta.env?.VITE_API_BASE_URL as string | undefined) || '/api/v1'
    const res = await fetch(`${base}/auth/session`, {
      method: 'GET',
      credentials: 'include',
    })
    if (!res.ok) {
      // Not logged in — clear any stale localStorage username/role so
      // UI-gating components don't render the wrong shell.
      clearAuthSession()
      return
    }
    const s = (await res.json()) as { username?: string; role?: string }
    if (s?.username && s?.role) {
      // Non-sensitive; drives UI gating (multiship_role reads across
      // several settings pages). Token stays httpOnly.
      window.localStorage.setItem(STORAGE_KEYS.username, s.username)
      window.localStorage.setItem(STORAGE_KEYS.role, s.role)
      emitSessionChange()

      // Reconcile the carrier mirror too. It drives a routing decision
      // (getHomePathForRole sends a carrier-less ADMIN to carrier setup),
      // and it used to be written ONLY at login / connect — so after a
      // refresh the app routed on a snapshot that could be months old and
      // survived even a logout. Refresh it from the server on every boot,
      // exactly like username/role.
      if (s.role.toUpperCase() !== 'TENANT') {
        try {
          const statusRes = await fetch(`${base}/carriers/status`, {
            method: 'GET',
            credentials: 'include',
          })
          if (statusRes.ok) {
            const body = await statusRes.json()
            const status = (body?.data ?? body) as {
              connected?: boolean
              carrierCode?: string | null
              carrierName?: string | null
              accountNumber?: string | null
              environment?: string | null
              connectedAt?: string | null
            }
            syncCarrierSession({
              connected: Boolean(status?.connected),
              carrierCode: status?.carrierCode ?? null,
              carrierName: status?.carrierName,
              accountNumber: status?.accountNumber,
              environment: status?.environment,
              connectedAt: status?.connectedAt,
            })
          }
        } catch {
          // Carrier status is best-effort: a failure here must not block
          // the session bootstrap. The stale-but-present mirror is no
          // worse than today's behaviour, and the Carrier Accounts page
          // reads live data regardless.
        }
      }
    }
  } catch {
    // Network error / offline: leave whatever localStorage says. A
    // real 401 would show up on the next actual API call and trigger
    // apiClient's /login redirect.
  }
}

export const storeAuthSession = (payload: { username: string; role: string }) => {
  if (!isBrowser()) {
    return
  }

  // Sprint 50 PR Q3 — cookie-mode auth is now the only path. The JWT
  // lives in an httpOnly cookie the JS can never read; the SPA only
  // needs the non-sensitive username + role to drive UI gating.
  window.localStorage.setItem(STORAGE_KEYS.username, payload.username)
  window.localStorage.setItem(STORAGE_KEYS.role, payload.role)
  emitSessionChange()
}

export const clearAuthSession = () => {
  if (!isBrowser()) {
    return
  }

  window.localStorage.removeItem(STORAGE_KEYS.username)
  window.localStorage.removeItem(STORAGE_KEYS.role)
  // Sprint 51 FE-M2 — sweep per-user caches too (drafts, table layouts,
  // connected-carrier snapshot) so a shared machine doesn't hand the
  // next operator the previous user's in-progress state. Supersedes the
  // single-key removal: the carrier mirror feeds a routing decision, so
  // it must not outlive the session, and neither should the rest.
  clearAppStorage()
  emitSessionChange()
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
