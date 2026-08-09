import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useFormik } from 'formik'
import { notify } from '../utils/notify'
import { FiArrowRight, FiShield, FiUserPlus } from 'react-icons/fi'
import { authService } from '../api/authService'
import AuthField from './auth/AuthField'
import AuthLayout from './auth/AuthLayout'
import { signupFormConfig } from '../validation/formik/signupFormConfig'
import { signupValidationSchema, type SignupFormValues } from '../validation/yup/signupSchema'

const getErrorMessage = (error: unknown) => {
  if (error instanceof Error && error.message) {
    return error.message
  }

  return 'Registration failed. Please check your inputs.'
}

export default function Signup() {
  const navigate = useNavigate()
  const [loading, setLoading] = useState(false)

  const formik = useFormik<SignupFormValues>({
    initialValues: signupFormConfig.initialValues,
    validationSchema: signupValidationSchema,
    validateOnChange: true,
    validateOnBlur: true,
    onSubmit: async (values) => {
      setLoading(true)

      // Button already shows a loading state; no interim modal needed.
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
        notify.error(getErrorMessage(error))
      } finally {
        setLoading(false)
      }
    },
  })

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
      <form className="space-y-4" onSubmit={formik.handleSubmit}>
        <div className="flex items-center gap-2 rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2 text-[11px] font-medium text-slate-500">
          <FiUserPlus className="h-3.5 w-3.5 text-sky-700" />
          Create a secure account for daily shipping operations
        </div>

        <div className="grid gap-3 sm:grid-cols-2">
          <AuthField
            label="Full Name"
            type="text"
            placeholder="John Doe"
            autoComplete="name"
            disabled={loading}
            error={formik.touched.fullName ? formik.errors.fullName : undefined}
            className="sm:col-span-2"
            {...formik.getFieldProps('fullName')}
          />

          <AuthField
            label="Email Address"
            type="email"
            placeholder="operator@multiship.com"
            autoComplete="email"
            disabled={loading}
            error={formik.touched.email ? formik.errors.email : undefined}
            className="sm:col-span-2"
            {...formik.getFieldProps('email')}
          />

          <AuthField
            label="Username"
            type="text"
            placeholder="Choose a username"
            autoComplete="username"
            disabled={loading}
            error={formik.touched.username ? formik.errors.username : undefined}
            {...formik.getFieldProps('username')}
          />

          <div className="flex items-center rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2 text-[11px] font-medium text-slate-500">
            <FiShield className="mr-2 h-3.5 w-3.5 text-sky-700" />
            Role assigned automatically as secure operator access.
          </div>

          <AuthField
            label="Password"
            type="password"
            placeholder="Create a password"
            autoComplete="new-password"
            hint="8+ chars"
            disabled={loading}
            error={formik.touched.password ? formik.errors.password : undefined}
            {...formik.getFieldProps('password')}
          />

          <AuthField
            label="Confirm Password"
            type="password"
            placeholder="Re-enter password"
            autoComplete="new-password"
            hint="Must match"
            disabled={loading}
            error={formik.touched.confirmPassword ? formik.errors.confirmPassword : undefined}
            {...formik.getFieldProps('confirmPassword')}
          />
        </div>

        <button
          type="submit"
          disabled={loading || !formik.isValid || !formik.dirty}
          className="inline-flex w-full items-center justify-center gap-2 rounded-2xl bg-[#1f150c] px-4 py-3 text-sm font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
        >
          {loading ? 'Creating Account...' : 'Create Account'}
          {!loading ? <FiArrowRight className="h-4 w-4" /> : null}
        </button>
      </form>
    </AuthLayout>
  )
}
