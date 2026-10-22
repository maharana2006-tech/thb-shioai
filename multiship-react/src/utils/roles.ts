import type { WorkspaceRouteKey } from '../routes/workspaceRoutes'

export type UserRole = 'SUPERADMIN' | 'ADMIN' | 'USER' | 'TENANT'

export const normalizeRole = (value?: string | null): UserRole => {
  const normalized = value?.trim().toUpperCase()

  if (normalized === 'SUPERADMIN' || normalized === 'ADMIN' || normalized === 'TENANT') {
    return normalized
  }

  return 'USER'
}

/**
 * SUPERADMIN inherits everything ADMIN and USER can reach (mirrors the backend
 * where its JWT carries ROLE_SUPERADMIN + ROLE_ADMIN + ROLE_USER). Use this for
 * every role gate instead of a bare `roles.includes(userRole)`.
 */
export const roleSatisfies = (userRole: UserRole, required: UserRole): boolean =>
  userRole === required || (userRole === 'SUPERADMIN' && (required === 'ADMIN' || required === 'USER'))

/**
 * Navigation visibility per role:
 * - ADMIN: full workspace including carrier management
 * - USER (shipping team): operate orders and labels, no carrier credential management
 * - TENANT (customer): read-only view of their own orders
 */
export const getNavKeysForRole = (role: UserRole): WorkspaceRouteKey[] => {
  switch (role) {
    case 'SUPERADMIN':
    case 'ADMIN':
      return ['dashboard', 'orders', 'bulk', 'd2c', 'settings']
    case 'USER':
      return ['dashboard', 'orders', 'bulk', 'd2c', 'settings']
    case 'TENANT':
      return ['orders']
  }
}

export const canAccessRoute = (role: UserRole, key: WorkspaceRouteKey) =>
  getNavKeysForRole(role).includes(key)

export const canGenerateLabels = (role: UserRole) => role !== 'TENANT'

export const canManageCarriers = (role: UserRole) => role === 'ADMIN' || role === 'SUPERADMIN'

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

  if ((role === 'ADMIN' || role === 'SUPERADMIN') && !hasConnectedCarrier) {
    return '/settings/carriers'
  }

  return '/dashboard'
}

/**
 * R4-BUG-003 — this used to return the UPPER-CASED username for TENANT
 * users on the assumption that username == customer_code. For an
 * invite-time TENANT where user.client_code is set independently of
 * the username, the fabricated value didn't match the real tenant and
 * every call that used it as a filter 403'd (the TENANT 'Your orders'
 * page showed empty, ShipmentLabelPage over-blocked valid labels).
 * The backend's TenantScopeEnforcer.clampClientCode already resolves
 * the caller's tenant from the JWT, so the FE must never derive it
 * from the username. Signature preserved for existing callers; they
 * all degrade to safe behaviour (route-by-role on OrdersPage, allow
 * on ShipmentLabelPage — backend still 403s cross-tenant attempts).
 *
 * @deprecated Always returns null. Route by role instead
 *   (`normalizeRole(role) === 'TENANT'`) and let the backend scope the
 *   list / view calls. Delete once no caller references it.
 */
// eslint-disable-next-line @typescript-eslint/no-unused-vars
export const getTenantIdForUser = (_role: UserRole, _username?: string | null): string | null => null
