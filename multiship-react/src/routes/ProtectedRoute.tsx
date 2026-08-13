import { Navigate, Outlet } from 'react-router-dom'
import { useAppSession } from '../hooks/useAppSession'

// Sprint 50 PR Q3 — the JWT is now httpOnly and unreadable from JS.
// Gate on `username` instead: it's populated by either the login
// response (storeAuthSession) or the bootstrap call to /auth/session
// on app mount, and cleared on logout / 401. If it's missing, we
// treat the session as absent and bounce to /login. A stale username
// with an expired cookie still gets caught by apiClient's 401 handler
// on the first protected fetch.
export default function ProtectedRoute() {
  const { username } = useAppSession()

  if (!username) {
    return <Navigate to="/login" replace />
  }

  return <Outlet />
}
