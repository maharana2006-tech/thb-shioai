import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { notify } from '../utils/notify'
import { FiArrowRight, FiShield, FiUserPlus } from 'react-icons/fi'
import { authService } from '../api/authService'
import AuthField from './auth/AuthField'
import AuthLayout from './auth/AuthLayout'

interface SignupErrors {
  fullName?: string
  email?: string
  username?: string
  password?: string
  confirmPassword?: string
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
const NAME_RE = /^[a-zA-Z\s]+$/
const USERNAME_RE = /^[a-zA-Z0-9_-]+$/
const PASSWORD_RE = /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)/

function validateSignup(v: {
  fullName: string
  email: string
  username: string
  password: string
  confirmPassword: string
}): SignupErrors {
  const e: SignupErrors = {}
  if (!v.fullName) e.fullName = 'Full name is required'
  else if (v.fullName.length < 2) e.fullName = 'Full name must be at least 2 characters'
  else if (v.fullName.length > 50) e.fullName = 'Full name must not exceed 50 characters'
  else if (!NAME_RE.test(v.fullName)) e.fullName = 'Full name can only contain letters and spaces'

  if (!v.email) e.email = 'Email is required'
  else if (!EMAIL_RE.test(v.email)) e.email = 'Please enter a valid email address'
  else if (v.email.length > 100) e.email = 'Email must not exceed 100 characters'

  if (!v.username) e.username = 'Username is required'
  else if (v.username.length < 3) e.username = 'Username must be at least 3 characters'
  else if (v.username.length > 20) e.username = 'Username must not exceed 20 characters'
  else if (!USERNAME_RE.test(v.username))
    e.username = 'Username can only contain letters, numbers, underscores, and hyphens'

  if (!v.password) e.password = 'Password is required'
  else if (v.password.length < 8) e.password = 'Password must be at least 8 characters'
  else if (v.password.length > 50) e.password = 'Password must not exceed 50 characters'
  else if (!PASSWORD_RE.test(v.password))
    e.password = 'Password must contain at least one uppercase letter, one lowercase letter, and one number'

  if (!v.confirmPassword) e.confirmPassword = 'Please confirm your password'
  else if (v.confirmPassword !== v.password) e.confirmPassword = 'Passwords must match'

  return e
}

type FieldName = keyof SignupErrors

export default function Signup() {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)
  const [values, setValues] = useState({
    fullName: '', email: '', username: '', password: '', confirmPassword: '',
  })
  const [touched, setTouched] = useState<Record<FieldName, boolean>>({
    fullName: false, email: false, username: false, password: false, confirmPassword: false,
  })

  const errors = validateSignup(values)
  const isValid = Object.keys(errors).length === 0
  const dirty = Object.values(values).some((v) => v !== '')

  const set = (k: FieldName) => (e: React.ChangeEvent<HTMLInputElement>) =>
    setValues((prev) => ({ ...prev, [k]: e.target.value }))
  const blur = (k: FieldName) => () => setTouched((prev) => ({ ...prev, [k]: true }))

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    setTouched({
      fullName: true, email: true, username: true, password: true, confirmPassword: true,
    })
    if (!isValid) return
    setLoading(true)

    try {
      // Sprint 49 Tier 1: role no longer sent from browser — the backend
      // always creates a USER account for anonymous signups.
      const response = await authService.signup({
        username: values.username,
        email: values.email,
        password: values.password,
        fullName: values.fullName,
      })

      notify.success(response.message || 'Operator account registered successfully!')

      setTimeout(() => {
        navigate('/login')
      }, 1200)
    } catch (error) {
      notify.apiError(error, 'Registration failed. Please check your inputs.')
    } finally {
      setLoading(false)
    }
  }

  return (
    <AuthLayout
      eyebrow="Create Account"
      title="Create a new operator workspace account."
      description="Set up a secure operator profile to manage carrier connections, label generation, and shipping workflows."
      footer={
        <p className="text-xs text-slate-500 sm:text-sm">
          Already registered?{' '}
          <Link to="/login" className="font-semibold text-sky-700 transition hover:text-sky-800">
            Sign in
          </Link>
        </p>
      }
    >
      <form className="space-y-4" onSubmit={handleSubmit}>
        <div className="flex items-center gap-2 rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2 text-[11px] font-medium text-slate-500">
          <FiUserPlus className="h-3.5 w-3.5 text-sky-700" />
          Create a secure account for daily shipping operations
        </div>

        <div className="grid gap-3 sm:grid-cols-2">
          <AuthField
            label="Full Name"
            name="fullName"
            type="text"
            placeholder="John Doe"
            autoComplete="name"
            disabled={loading}
            className="sm:col-span-2"
            value={values.fullName}
            onChange={set('fullName')}
            onBlur={blur('fullName')}
            error={touched.fullName ? errors.fullName : undefined}
          />

          <AuthField
            label="Email Address"
            name="email"
            type="email"
            placeholder="operator@multiship.com"
            autoComplete="email"
            disabled={loading}
            className="sm:col-span-2"
            value={values.email}
            onChange={set('email')}
            onBlur={blur('email')}
            error={touched.email ? errors.email : undefined}
          />

          <AuthField
            label="Username"
            name="username"
            type="text"
            placeholder="Choose a username"
            autoComplete="username"
            disabled={loading}
            value={values.username}
            onChange={set('username')}
            onBlur={blur('username')}
            error={touched.username ? errors.username : undefined}
          />

          <div className="flex items-center rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2 text-[11px] font-medium text-slate-500">
            <FiShield className="mr-2 h-3.5 w-3.5 text-sky-700" />
            Role assigned automatically as secure operator access.
          </div>

          <AuthField
            label="Password"
            name="password"
            type="password"
            placeholder="Create a password"
            autoComplete="new-password"
            hint="8+ chars"
            disabled={loading}
            value={values.password}
            onChange={set('password')}
            onBlur={blur('password')}
            error={touched.password ? errors.password : undefined}
          />

          <AuthField
            label="Confirm Password"
            name="confirmPassword"
            type="password"
            placeholder="Re-enter password"
            autoComplete="new-password"
            hint="Must match"
            disabled={loading}
            value={values.confirmPassword}
            onChange={set('confirmPassword')}
            onBlur={blur('confirmPassword')}
            error={touched.confirmPassword ? errors.confirmPassword : undefined}
          />
        </div>

        <button
          type="submit"
          disabled={loading || !isValid || !dirty}
          className="inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-[#1f150c] px-4 py-3 text-sm font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
        >
          {loading ? 'Creating Account...' : 'Create Account'}
          {!loading ? <FiArrowRight className="h-4 w-4" /> : null}
        </button>
      </form>
    </AuthLayout>
  )
}
