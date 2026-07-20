import { useState } from 'react'
import { useNavigate, Link } from 'react-router-dom'
import { useFormik } from 'formik'
import toast from 'react-hot-toast'
import { FiArrowRight, FiLock, FiShield, FiUser } from 'react-icons/fi'
import { authService, type AuthResponse } from '../api/authService'
import { carrierService } from '../api/carrierService'
import AuthField from './auth/AuthField'
import AuthLayout from './auth/AuthLayout'
import { storeAuthSession, syncCarrierSession } from '../hooks/useAppSession'
import { getHomePathForRole, normalizeRole } from '../utils/roles'
import { loginValidationSchema } from '../validation/yup/loginSchema'
import { loginFormConfig } from '../validation/formik/loginFormConfig'

const getErrorMessage = (error: unknown) => {
  if (error instanceof Error && error.message) {
    return error.message
  }

  return 'Invalid username or password.'
}

type AuthPayload = AuthResponse & {
  data?: AuthResponse
}

export default function Login() {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)

  const formik = useFormik({
    initialValues: loginFormConfig.initialValues,
    validationSchema: loginValidationSchema,
    validateOnChange: true,
    validateOnBlur: true,
    onSubmit: async (values) => {
      setLoading(true)

      const toastId = toast.loading('Authenticating credentials...')

      try {
        const res = await authService.login({
          username: values.username,
          password: values.password,
        })

        const authPayload = res as AuthPayload
        const authData: AuthResponse = authPayload.data ?? authPayload
        if (authData && authData.token) {
          const role = normalizeRole(authData.role)

          storeAuthSession({
            token: authData.token,
            username: authData.username || values.username,
            role: authData.role || role,
          })

          // Pull the live operator carrier session so the redirect reflects
          // real backend state instead of a stale local mirror.
          let carrierConnected = false

          if (role !== 'TENANT') {
            const status = await carrierService.getCarrierStatus().catch(() => null)
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

          toast.success(`Welcome back, ${authData.username || values.username}. Redirecting...`, {
            id: toastId,
          })

          setTimeout(() => {
            navigate(getHomePathForRole(role, carrierConnected))
          }, 800)
        } else {
          throw new Error('Invalid authentication token payload format received.')
        }
      } catch (error) {
        toast.error(getErrorMessage(error), { id: toastId })
      } finally {
        setLoading(false)
      }
    },
  })

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
      <form className="space-y-4" onSubmit={formik.handleSubmit}>
        <div className="flex items-center gap-2 rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2 text-[11px] font-medium text-slate-500">
          <FiShield className="h-3.5 w-3.5 text-sky-700" />
          Secure operator sign-in for carrier and label workflows
        </div>

        <div className="space-y-3.5">
          <AuthField
            label="Username"
            type="text"
            placeholder="Enter your username"
            autoComplete="username"
            icon={<FiUser className="h-4 w-4" />}
            disabled={loading}
            error={formik.touched.username ? (formik.errors.username as string | undefined) : undefined}
            {...formik.getFieldProps('username')}
          />

          <AuthField
            label="Password"
            type="password"
            placeholder="Enter your password"
            autoComplete="current-password"
            hint="Secure access"
            icon={<FiLock className="h-4 w-4" />}
            disabled={loading}
            error={formik.touched.password ? (formik.errors.password as string | undefined) : undefined}
            {...formik.getFieldProps('password')}
          />
        </div>

        <button
          type="submit"
          disabled={loading || !formik.isValid || !formik.dirty}
          className="inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-[#1f150c] px-4 py-3 text-sm font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
        >
          {loading ? 'Authenticating...' : 'Sign In'}
          {!loading ? <FiArrowRight className="h-4 w-4" /> : null}
        </button>
      </form>
    </AuthLayout>
  )
}
