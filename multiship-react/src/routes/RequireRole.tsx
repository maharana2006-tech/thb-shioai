import { Navigate, Outlet, useOutletContext } from 'react-router-dom'
import { useAppSession } from '../hooks/useAppSession'
import { getHomePathForRole, normalizeRole, type UserRole } from '../utils/roles'

interface RequireRoleProps {
  roles: UserRole[]
}

/**
 * Renders child routes only when the signed-in role is allowed; otherwise sends the user home.
 * Forwards the parent Outlet's context down explicitly so nested pages (e.g. Carriers
 * inside SettingsLayout) can read `registerRefresh` via useOutletContext.
 */
export default function RequireRole({ roles }: RequireRoleProps) {
  const { role, hasConnectedCarrier } = useAppSession()
  const normalizedRole = normalizeRole(role)
  const parentContext = useOutletContext()

  if (!roles.includes(normalizedRole)) {
    return <Navigate to={getHomePathForRole(normalizedRole, hasConnectedCarrier)} replace />
  }

  return <Outlet context={parentContext} />
}
