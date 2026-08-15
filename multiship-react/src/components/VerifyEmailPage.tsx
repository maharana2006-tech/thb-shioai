import { useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { FiAlertCircle, FiArrowRight, FiCheckCircle } from 'react-icons/fi'
import { ApiError } from '../api/apiClient'
import { inviteService } from '../api/inviteService'
import { getFriendlyError } from '../utils/errorMessages'
import AuthLayout from './auth/AuthLayout'

/**
 * Sprint 51 User↔Client linkage re-audit item #2 — public email-verify
 * landing page mounted at /verify-email. The signup-side mailer builds
 * a link like {SPA_BASE_URL}/verify-email?token=… (Sprint 51 backend
 * companion in this same PR); this page reads ?token= and POSTs it to
 * /auth/verify-email so the invitee never sees a raw JSON blob.
 *
 * <p>Three states:
 *   · verifying — spinner while the POST is in flight
 *   · verified  — success card + link back to /login
 *   · failed    — friendly error banner + link back to /login
 */

type State =
  | { status: 'verifying' }
  | { status: 'verified'; message: string }
  | { status: 'failed'; title: string; message: string }

/**
 * useState lazy initializer keeps the "missing token" branch out of
 * useEffect so the react-hooks/set-state-in-effect lint rule doesn't
 * fire on the synchronous URL-derivation case.
 */
function initialState(token: string | null): State {
  if (!token || token.trim().length === 0) {
    return {
      status: 'failed',
      title: 'Verification link is missing a token',
      message: 'Open the link straight from the email — copy/paste sometimes drops the token.',
    }
  }
  return { status: 'verifying' }
}

export default function VerifyEmailPage() {
  const [params] = useSearchParams()
  const token = params.get('token')
  const [state, setState] = useState<State>(() => initialState(token))

  useEffect(() => {
    if (!token) return
    let cancelled = false
    inviteService
      .verifyEmail(token)
      .then((resp) => {
        if (!cancelled) {
          setState({
            status: 'verified',
            message: resp?.message ?? 'Email verified. You can now log in.',
          })
        }
      })
      .catch((err: unknown) => {
        if (cancelled) return
        const anyErr = err as ApiError
        const friendly = getFriendlyError(
          anyErr?.errorCode,
          anyErr?.message,
          'This verification link is no longer valid. Ask an admin to resend the invite or sign up again.',
        )
        setState({
          status: 'failed',
          title: friendly.title ?? 'Verification failed',
          message: friendly.message,
        })
      })
    return () => {
      cancelled = true
    }
  }, [token])

  return (
    <AuthLayout
      eyebrow="Verify Email"
      title="Confirm your MultiShip email address."
      description="One click and your account is ready to use. This page is safe to refresh — verification is idempotent."
    >
      {state.status === 'verifying' ? (
        <div className="flex items-center justify-center py-10 text-sm text-slate-500">
          <span
            className="mr-3 inline-block h-4 w-4 animate-spin rounded-full border-2 border-slate-200 border-t-slate-500"
            aria-hidden="true"
          />
          Verifying your email…
        </div>
      ) : null}

      {state.status === 'verified' ? (
        <div
          role="status"
          className="space-y-3 rounded-2xl border border-emerald-200 bg-emerald-50 px-4 py-4 text-sm text-emerald-800"
        >
          <p className="flex items-center gap-2 font-semibold">
            <FiCheckCircle className="h-4 w-4" /> Email verified
          </p>
          <p>{state.message}</p>
          <Link
            to="/login"
            className="inline-flex items-center gap-1 text-sm font-semibold text-emerald-900 hover:text-emerald-950"
          >
            Continue to login <FiArrowRight className="h-3.5 w-3.5" />
          </Link>
        </div>
      ) : null}

      {state.status === 'failed' ? (
        <div
          role="alert"
          className="space-y-3 rounded-2xl border border-rose-200 bg-rose-50 px-4 py-4 text-sm text-rose-800"
        >
          <p className="flex items-center gap-2 font-semibold">
            <FiAlertCircle className="h-4 w-4" /> {state.title}
          </p>
          <p>{state.message}</p>
          <Link
            to="/login"
            className="inline-flex items-center gap-1 text-sm font-semibold text-rose-900 hover:text-rose-950"
          >
            Back to login <FiArrowRight className="h-3.5 w-3.5" />
          </Link>
        </div>
      ) : null}
    </AuthLayout>
  )
}
