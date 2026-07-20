import { Navigate, Outlet } from 'react-router-dom'
import { useAppSession } from '../hooks/useAppSession'
import { getHomePathForRole, normalizeRole, type UserRole } from '../utils/roles'

interface RequireRoleProps {
  roles: UserRole[]
}

/** Renders child routes only when the signed-in role is allowed; otherwise sends the user home. */
export default function RequireRole({ roles }: RequireRoleProps) {
  const { role, hasConnectedCarrier } = useAppSession()
  const normalizedRole = normalizeRole(role)

  if (!roles.includes(normalizedRole)) {
    return <Navigate to={getHomePathForRole(normalizedRole, hasConnectedCarrier)} replace />
  }

  return <Outlet />
}
