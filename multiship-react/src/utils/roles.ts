import type { WorkspaceRouteKey } from '../routes/workspaceRoutes'

export type UserRole = 'ADMIN' | 'USER' | 'TENANT'

export const normalizeRole = (value?: string | null): UserRole => {
  const normalized = value?.trim().toUpperCase()

  if (normalized === 'ADMIN' || normalized === 'TENANT') {
    return normalized
  }

  return 'USER'
}

/**
 * Navigation visibility per role:
 * - ADMIN: full workspace including carrier management
 * - USER (shipping team): operate orders and labels, no carrier credential management
 * - TENANT (customer): read-only view of their own orders
 */
export const getNavKeysForRole = (role: UserRole): WorkspaceRouteKey[] => {
  switch (role) {
    case 'ADMIN':
      return ['dashboard', 'orders', 'settings']
    case 'USER':
      return ['dashboard', 'orders', 'settings']
    case 'TENANT':
      return ['orders']
  }
}

export const canAccessRoute = (role: UserRole, key: WorkspaceRouteKey) =>
  getNavKeysForRole(role).includes(key)

export const canGenerateLabels = (role: UserRole) => role !== 'TENANT'

export const canManageCarriers = (role: UserRole) => role === 'ADMIN'

/**
 * Landing page after authentication:
 * - TENANT goes straight to their scoped order list
 * - ADMIN without an operator carrier session is guided to carrier setup first
 * - everyone else lands on the dashboard
 */
export const getHomePathForRole = (role: UserRole, hasConnectedCarrier: boolean) => {
  if (role === 'TENANT') {
    return '/orders'
  }

  if (role === 'ADMIN' && !hasConnectedCarrier) {
    return '/settings/carriers'
  }

  return '/dashboard'
}

/**
 * TENANT users are mapped to their tenant by username (e.g. user "arhdev"
 * sees tenant "ARHDEV"). This mirrors the backend convention of
 * COALESCE(tenant_id, cust_no) using uppercase customer codes.
 */
export const getTenantIdForUser = (role: UserRole, username?: string | null) =>
  role === 'TENANT' && username?.trim() ? username.trim().toUpperCase() : null
