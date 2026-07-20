import { Navigate, Outlet } from 'react-router-dom'
import { useAppSession } from '../hooks/useAppSession'

export default function ProtectedRoute() {
  const { token } = useAppSession()

  if (!token) {
    return <Navigate to="/login" replace />
  }

  return <Outlet />
}
