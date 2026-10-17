import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { notify } from '../utils/notify'
import { FiArrowRight, FiLock, FiShield, FiUser } from 'react-icons/fi'
import { authService, type AuthResponse } from '../api/authService'
import { carrierService } from '../api/carrierService'
import AuthField from './auth/AuthField'
import AuthLayout from './auth/AuthLayout'
import { storeAuthSession, syncCarrierSession } from '../hooks/useAppSession'
import { getHomePathForRole, normalizeRole } from '../utils/roles'

// Sprint 50 PR J — login failures now flow through notify.apiError so the
// scoped ErrorCode friendly-message map fires. That covers EMAIL_NOT_VERIFIED
// (post-Tier 0.5-D signup) and ACCOUNT_DEACTIVATED (post-Tier 0.5-E admin
// revoke) with the intended UX instead of the raw backend message. Kept the
// generic fallback below in case the error carries neither errorCode nor a
// server-supplied message string.
const LOGIN_FALLBACK = 'Invalid username or password.'

type AuthPayload = AuthResponse & {
  data?: AuthResponse
}

function validateLogin(username: string, password: string): { username?: string; password?: string } {
  const errors: { username?: string; password?: string } = {}
  if (!username) errors.username = 'Username is required'
  else if (username.length < 3) errors.username = 'Username must be at least 3 characters'
  else if (username.length > 20) errors.username = 'Username must not exceed 20 characters'
  if (!password) errors.password = 'Password is required'
  else if (password.length < 6) errors.password = 'Password must be at least 6 characters'
  else if (password.length > 50) errors.password = 'Password must not exceed 50 characters'
  return errors
}

export default function Login() {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [touched, setTouched] = useState<{ username?: boolean; password?: boolean }>({})

  const errors = validateLogin(username, password)
  const isValid = !errors.username && !errors.password
  const dirty = username !== '' || password !== ''

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setTouched({ username: true, password: true })
    if (!isValid) return
    setLoading(true)

    // Button already shows a loading state; no interim modal needed.
    try {
      const res = await authService.login({ username, password })

      const authPayload = res as AuthPayload
      const authData: AuthResponse = authPayload.data ?? authPayload
      // Sprint 50 PR Q3 — cookie-mode auth. The JWT is set as an
      // httpOnly cookie by the backend on this same response; we no
      // longer read it here. A successful login is signalled by the
      // presence of the username field on the AuthResponse body.
      if (authData && authData.username) {
        const role = normalizeRole(authData.role)

        storeAuthSession({
          username: authData.username || username,
          role: authData.role || role,
        })

        // Pull the live operator carrier session so the redirect reflects
        // real backend state instead of a stale local mirror.
        let carrierConnected = false

        if (role !== 'TENANT') {
          const status = await carrierService.getCarrierStatus().catch((e: unknown) => {
            notify.apiError(e, 'Signed in, but the live carrier status check failed. Reconnect from the carriers page if needed.')
            return null
          })
          carrierConnected = Boolean(status?.connected)
          syncCarrierSession({
            connected: carrierConnected,
            carrierCode: status?.carrierCode || null,
            carrierName: status?.carrierName,
            accountNumber: status?.accountNumber,
            environment: status?.environment,
            connectedAt: status?.connectedAt,
          })
        }

        notify.success(`Welcome back, ${authData.username || username}. Redirecting...`)

        setTimeout(() => {
          navigate(getHomePathForRole(role, carrierConnected))
        }, 800)
      } else {
        throw new Error('Invalid authentication token payload format received.')
      }
    } catch (error) {
      notify.apiError(error, LOGIN_FALLBACK)
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout
      eyebrow="Authentication"
      title="Sign in to your shipping workspace."
      description="Access carrier setup, release labels, and monitor shipping operations from one controlled console."
      footer={
        <p className="text-xs text-slate-500 sm:text-sm">
          New to MultiShip?{' '}
          <Link to="/signup" className="font-semibold text-sky-700 transition hover:text-sky-800">
            Create an operator account
          </Link>
        </p>
      }
    >
      <form className="space-y-4" onSubmit={handleSubmit}>
        <div className="flex items-center gap-2 rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2 text-[11px] font-medium text-slate-500">
          <FiShield className="h-3.5 w-3.5 text-sky-700" />
          Secure operator sign-in for carrier and label workflows
        </div>

        <div className="space-y-3.5">
          <AuthField
            label="Username"
            name="username"
            type="text"
            placeholder="Enter your username"
            autoComplete="username"
            icon={<FiUser className="h-4 w-4" />}
            disabled={loading}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
            onBlur={() => setTouched((t) => ({ ...t, username: true }))}
            error={touched.username ? errors.username : undefined}
          />

          <AuthField
            label="Password"
            name="password"
            type="password"
            placeholder="Enter your password"
            autoComplete="current-password"
            hint="Secure access"
            icon={<FiLock className="h-4 w-4" />}
            disabled={loading}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            onBlur={() => setTouched((t) => ({ ...t, password: true }))}
            error={touched.password ? errors.password : undefined}
          />
        </div>

        <button
          type="submit"
          disabled={loading || !isValid || !dirty}
          className="inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-[#1f150c] px-4 py-3 text-sm font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
        >
          {loading ? 'Authenticating...' : 'Sign In'}
          {!loading ? <FiArrowRight className="h-4 w-4" /> : null}
        </button>
      </form>
    </AuthLayout>
  )
}
