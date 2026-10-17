import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { FiArrowRight, FiMail, FiShield } from 'react-icons/fi'
import { ApiError } from '../api/apiClient'
import { inviteService, type InvitePreview } from '../api/inviteService'
import { notify } from '../utils/notify'
import { getFriendlyError } from '../utils/errorMessages'
import AuthField from './auth/AuthField'
import AuthLayout from './auth/AuthLayout'

/**
 * Sprint 51 User↔Client linkage re-audit item #1 — public invite-accept
 * page mounted at /invite/:token.
 *
 * <p>Flow:
 *   1. On mount, GET /auth/invite/{token} → InvitePreview. Failures
 *      render a friendly banner with a "Back to login" link; success
 *      unlocks the form.
 *   2. Submit POSTs /auth/accept-invite. On success, notify + redirect
 *      to /login. On failure, notify.apiError.
 *
 * <p>Client-side password rule matches the backend's Jakarta @Size(min=6)
 * on AcceptInviteRequest (not the tougher 8-char rule used by public
 * signup — invites are admin-mediated, so the acceptance form deliberately
 * mirrors what the server actually enforces).
 */

interface AcceptInviteErrors {
  username?: string
  password?: string
  fullName?: string
}

const USERNAME_RE = /^[a-zA-Z0-9_-]+$/

function validateAccept(v: { username: string; password: string; fullName: string }): AcceptInviteErrors {
  const e: AcceptInviteErrors = {}
  if (!v.username) e.username = 'Username is required'
  else if (v.username.length < 3) e.username = 'Username must be at least 3 characters'
  else if (v.username.length > 20) e.username = 'Username must not exceed 20 characters'
  else if (!USERNAME_RE.test(v.username))
    e.username = 'Username can only contain letters, numbers, underscores, and hyphens'

  if (!v.password) e.password = 'Password is required'
  // Matches AcceptInviteRequest @Size(min = 6). Backend rejects <6 with
  // a 400 VALIDATION_ERROR; matching it client-side avoids the round-trip.
  else if (v.password.length < 6) e.password = 'Password must be at least 6 characters'
  else if (v.password.length > 100) e.password = 'Password must not exceed 100 characters'

  if (!v.fullName) e.fullName = 'Full name is required'
  else if (v.fullName.length < 2) e.fullName = 'Full name must be at least 2 characters'
  else if (v.fullName.length > 80) e.fullName = 'Full name must not exceed 80 characters'

  return e
}

type LoadState =
  | { status: 'loading' }
  | { status: 'ready'; preview: InvitePreview }
  | { status: 'error'; title: string; message: string }

/**
 * useState lazy initializer keeps the "missing token" branch out of
 * useEffect so the react-hooks/set-state-in-effect lint rule doesn't
 * fire on the synchronous invalid-URL case. A missing param is a pure
 * derivation of props/URL — no effect needed.
 */
function initialLoadState(token: string | undefined): LoadState {
  if (!token || token.trim().length === 0) {
    return {
      status: 'error',
      title: 'Invalid invite link',
      message: 'The invite URL is missing its token. Ask the admin to resend the invite.',
    }
  }
  return { status: 'loading' }
}

/**
 * Audit I2 — format the invite's expiresAt into "in Nd Nh" + local
 * datetime. Falls back to the raw ISO string if parsing fails so the
 * invitee still sees something rather than "Invalid Date".
 */
function formatInviteExpiry(iso: string): string {
  if (!iso) return 'unknown'
  const at = new Date(iso)
  if (Number.isNaN(at.getTime())) return iso
  const localised = at.toLocaleString()
  const msLeft = at.getTime() - Date.now()
  if (msLeft <= 0) return `${localised} (expired)`
  const days = Math.floor(msLeft / 86_400_000)
  const hours = Math.floor((msLeft % 86_400_000) / 3_600_000)
  const rel = days >= 1 ? `in ${days}d ${hours}h` : `in ${hours}h`
  return `${localised} (${rel})`
}

export default function AcceptInvitePage() {
  const { token } = useParams<{ token: string }>()
  const navigate = useNavigate()
  const [load, setLoad] = useState<LoadState>(() => initialLoadState(token))
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (!token) return
    let cancelled = false
    inviteService
      .previewInvite(token)
      .then((preview) => {
        if (!cancelled) setLoad({ status: 'ready', preview })
      })
      .catch((err: unknown) => {
        if (cancelled) return
        const anyErr = err as ApiError
        const friendly = getFriendlyError(
          anyErr?.errorCode,
          anyErr?.message,
          'This invite link is no longer valid. Ask the admin for a fresh invite.',
        )
        setLoad({
          status: 'error',
          title: friendly.title ?? 'Invite unavailable',
          message: friendly.message,
        })
      })
    return () => {
      cancelled = true
    }
  }, [token])

  const [values, setValues] = useState({ username: '', password: '', fullName: '' })
  const [touched, setTouched] = useState<{ username: boolean; password: boolean; fullName: boolean }>({
    username: false, password: false, fullName: false,
  })
  type FieldName = keyof typeof values
  const errors = validateAccept(values)
  const isValid = Object.keys(errors).length === 0
  const dirty = Object.values(values).some((v) => v !== '')

  const set = (k: FieldName) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setValues((prev) => ({ ...prev, [k]: e.target.value }))
  const blur = (k: FieldName) => () => setTouched((prev) => ({ ...prev, [k]: true }))

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!token) return
    setTouched({ username: true, password: true, fullName: true })
    if (!isValid) return
    setSubmitting(true)
    try {
      await inviteService.acceptInvite({
        token,
        username: values.username,
        password: values.password,
        fullName: values.fullName,
      })
      await notify.success('Account created — please log in.')
      navigate('/login')
    } catch (err) {
      await notify.apiError(err, 'Could not accept the invite. Please try again.')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AuthLayout
      eyebrow="Accept Invite"
      title="Finish setting up your MultiShip account."
      description="Your administrator has invited you to a workspace. Choose a username and password to activate your account."
      footer={
        <p className="text-xs text-slate-500 sm:text-sm">
          Already have an account?{' '}
          <Link to="/login" className="font-semibold text-sky-700 transition hover:text-sky-800">
            Sign in
          </Link>
        </p>
      }
    >
      {load.status === 'loading' ? (
        <div className="flex items-center justify-center py-10 text-sm text-slate-500">
          <span
            className="mr-3 inline-block h-4 w-4 animate-spin rounded-full border-2 border-slate-200 border-t-slate-500"
            aria-hidden="true"
          />
          Checking your invite…
        </div>
      ) : null}

      {load.status === 'error' ? (
        <div
          role="alert"
          className="space-y-3 rounded-2xl border border-rose-200 bg-rose-50 px-4 py-4 text-sm text-rose-800"
        >
          <p className="font-semibold">{load.title}</p>
          <p>{load.message}</p>
          <Link
            to="/login"
            className="inline-flex items-center gap-1 text-sm font-semibold text-rose-900 hover:text-rose-950"
          >
            Back to login <FiArrowRight className="h-3.5 w-3.5" />
          </Link>
        </div>
      ) : null}

      {load.status === 'ready' ? (
        <form className="space-y-4" onSubmit={handleSubmit}>
          <div className="rounded-2xl border border-slate-200 bg-slate-50 px-4 py-3 text-xs font-medium text-slate-600">
            <p className="flex items-center gap-2 text-slate-500">
              <FiMail className="h-3.5 w-3.5 text-sky-700" />
              <span className="uppercase tracking-[0.14em] text-[10px]">Invite</span>
            </p>
            <p className="mt-1 text-sm text-slate-900">
              You&apos;ve been invited to join{' '}
              <span className="font-semibold">{load.preview.clientCode}</span> as{' '}
              <span className="font-semibold">{load.preview.role}</span>.
            </p>
            <p className="mt-1 text-[11px] text-slate-500">
              Invite email: {load.preview.email}
            </p>
            {/* Audit I2 — surface expiresAt so the invitee knows how long
                they have. Pre-fix, the preview response carried expiresAt
                but the page never rendered it; invitees didn't know if
                they had 5 minutes or 5 days to act. Formatted per user's
                locale (no timezone suffix on the backend value → assumed
                server-local, close enough for a 7-day window). */}
            <p className="mt-1 text-[11px] text-slate-500">
              Expires: {formatInviteExpiry(load.preview.expiresAt)}
            </p>
          </div>

          <AuthField
            label="Full Name"
            name="fullName"
            type="text"
            placeholder="Alex Rivera"
            autoComplete="name"
            disabled={submitting}
            value={values.fullName}
            onChange={set('fullName')}
            onBlur={blur('fullName')}
            error={touched.fullName ? errors.fullName : undefined}
          />

          <AuthField
            label="Username"
            name="username"
            type="text"
            placeholder="Choose a username"
            autoComplete="username"
            disabled={submitting}
            value={values.username}
            onChange={set('username')}
            onBlur={blur('username')}
            error={touched.username ? errors.username : undefined}
          />

          <AuthField
            label="Password"
            name="password"
            type="password"
            placeholder="Create a password"
            autoComplete="new-password"
            hint="6+ chars"
            disabled={submitting}
            value={values.password}
            onChange={set('password')}
            onBlur={blur('password')}
            error={touched.password ? errors.password : undefined}
          />

          <div className="flex items-center gap-2 rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2 text-[11px] font-medium text-slate-500">
            <FiShield className="h-3.5 w-3.5 text-sky-700" />
            Role + client scope are pre-assigned by your administrator.
          </div>

          <button
            type="submit"
            disabled={submitting || !isValid || !dirty}
            className="inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-[#1f150c] px-4 py-3 text-sm font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
          >
            {submitting ? 'Creating Account…' : 'Accept Invite'}
            {!submitting ? <FiArrowRight className="h-4 w-4" /> : null}
          </button>
        </form>
      ) : null}
    </AuthLayout>
  )
}
