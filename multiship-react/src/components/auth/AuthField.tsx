import { useId, useState, type InputHTMLAttributes, type ReactNode } from 'react'
import { FiEye, FiEyeOff } from 'react-icons/fi'

interface AuthFieldProps extends InputHTMLAttributes<HTMLInputElement> {
  label: string
  error?: string
  hint?: string
  icon?: ReactNode
}

export default function AuthField({
  label,
  error,
  hint,
  icon,
  id,
  className = '',
  ...inputProps
}: AuthFieldProps) {
  const generatedId = useId()
  const fieldId = id ?? generatedId
  const [showPassword, setShowPassword] = useState(false)
  const isPasswordField = inputProps.type === 'password'
  const resolvedType = isPasswordField ? (showPassword ? 'text' : 'password') : inputProps.type

  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between gap-3">
        <label htmlFor={fieldId} className="text-xs font-semibold text-slate-700">
          {label}
        </label>
        {hint ? <span className="text-[11px] font-medium text-slate-400">{hint}</span> : null}
      </div>

      <div className="relative">
        {icon ? (
          <span className="pointer-events-none absolute inset-y-0 left-0 flex items-center pl-3.5 text-slate-400">
            {icon}
          </span>
        ) : null}

        <input
          id={fieldId}
          type={resolvedType}
          className={[
            'w-full rounded-2xl border py-2.5 text-sm text-slate-950 shadow-sm outline-none transition',
            isPasswordField ? 'pr-11' : 'pr-3.5',
            icon ? 'pl-11' : 'px-3.5',
            error
              ? 'border-rose-300 bg-rose-50/60 focus:border-rose-400 focus:ring-4 focus:ring-rose-100'
              : 'border-slate-200 bg-white focus:border-sky-600 focus:ring-4 focus:ring-sky-100',
            'disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-400',
            className,
          ].join(' ')}
          aria-invalid={Boolean(error)}
          aria-describedby={error ? `${fieldId}-error` : undefined}
          {...inputProps}
        />

        {isPasswordField ? (
          <button
            type="button"
            onClick={() => setShowPassword((currentValue) => !currentValue)}
            className="absolute inset-y-0 right-0 flex items-center pr-3.5 text-slate-400 transition hover:text-slate-600"
            aria-label={showPassword ? 'Hide password' : 'Show password'}
          >
            {showPassword ? <FiEyeOff className="h-4 w-4" /> : <FiEye className="h-4 w-4" />}
          </button>
        ) : null}
      </div>

      {error ? (
        <p id={`${fieldId}-error`} className="text-xs font-medium text-rose-600">
          {error}
        </p>
      ) : null}
    </div>
  )
}
