import { Navigate, Outlet, useOutletContext } from 'react-router-dom'
import { useAppSession } from '../hooks/useAppSession'
import { getHomePathForRole, roleSatisfies, type UserRole } from '../utils/roles'

interface RequireRoleProps {
  roles: UserRole[]
}

/**
 * Renders child routes only when the signed-in role is allowed; otherwise sends the user home.
 * Forwards the parent Outlet's context down explicitly so nested pages (e.g. Carriers
 * inside SettingsLayout) can read `registerRefresh` via useOutletContext.
 */
export default function RequireRole({ roles }: RequireRoleProps) {
  const { effectiveRole, hasConnectedCarrier } = useAppSession()
  const parentContext = useOutletContext()

  if (!roles.some((r) => roleSatisfies(effectiveRole, r))) {
    return <Navigate to={getHomePathForRole(effectiveRole, hasConnectedCarrier)} replace />
  }

  return <Outlet context={parentContext} />
}
