import { Navigate, Outlet } from 'react-router-dom'
import { useAppSession } from '../hooks/useAppSession'
import { getHomePathForRole, normalizeRole } from '../utils/roles'

export default function AnonymousRoute() {
  const { token, role, hasConnectedCarrier } = useAppSession()

  if (token) {
    return <Navigate to={getHomePathForRole(normalizeRole(role), hasConnectedCarrier)} replace />
  }

  return <Outlet />
}
