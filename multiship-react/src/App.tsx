import { useEffect } from 'react'
import AppRoutes from './routes/AppRoutes'
import NotifyHost from './components/workspace/NotifyHost'
import { bootstrapSessionFromCookie } from './hooks/useAppSession'

export default function App() {
  // Sprint 50 PR Q2 — under cookie-mode auth the JWT is httpOnly so
  // localStorage alone can't tell "am I logged in?" on a page refresh.
  // Hit /auth/session once at mount; the backend reads the cookie and
  // returns the caller's username/role, which we mirror into
  // localStorage to drive UI gating. 401 quietly clears local state
  // — the next protected navigation redirects to /login as before.
  useEffect(() => {
    void bootstrapSessionFromCookie()
  }, [])

  return (
    <>
      {/* Themed modal host — Sprint 51 T6a fully removed react-hot-toast.
          Renders one confirmation / info / success / error modal at a time. */}
      <NotifyHost />
      <AppRoutes />
    </>
  )
}
