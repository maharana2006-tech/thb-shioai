import { Navigate, Outlet } from 'react-router-dom'
import { useAppSession } from '../hooks/useAppSession'
import { getHomePathForRole, normalizeRole } from '../utils/roles'

// Sprint 50 PR Q3 — see ProtectedRoute for the switch from `token` to
// `username`. Same reasoning here in reverse: an authenticated user
// hitting /login should be redirected to their role's home page.
export default function AnonymousRoute() {
  const { username, role, hasConnectedCarrier } = useAppSession()

  if (username) {
    return <Navigate to={getHomePathForRole(normalizeRole(role), hasConnectedCarrier)} replace />
  }

  return <Outlet />
}
