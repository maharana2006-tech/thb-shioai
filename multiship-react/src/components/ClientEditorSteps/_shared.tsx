/**
 * F5-B — primitives shared by every ClientEditorPage step component.
 * Extracted from the top of ClientEditorPage.tsx so each step file can
 * import from one place instead of dragging the whole ~3.5K-LoC page
 * to test a single form section.
 *
 * <p>Only React-component exports here (Field + input-class constants,
 * which react-refresh tolerates as strings alongside a component). Types
 * and utility functions live in {@link ./_types.ts} so
 * `react-refresh/only-export-components` stays happy.
 */
import type { ReactNode } from 'react'
import { FiAlertCircle } from 'react-icons/fi'

export const inputBaseClass =
  'w-full rounded-xl border bg-slate-50 px-2.5 py-1.5 text-[12.5px] text-slate-950 outline-none transition focus:bg-white focus:ring-2'
export const inputOk = 'border-slate-200 focus:border-sky-600 focus:ring-sky-100'
export const inputErr = 'border-rose-400 focus:border-rose-500 focus:ring-rose-100'

export interface FieldProps {
  label: string
  children: ReactNode
  required?: boolean
  error?: string | null
  hint?: string
}

/** Small labeled field wrapper with an optional inline error line under it. */
export function Field({ label, children, required, error, hint }: FieldProps) {
  return (
    <label className="block">
      <span className="mb-0.5 flex items-center gap-1 text-[10px] font-semibold uppercase tracking-[0.14em] text-slate-400">
        {label}
        {required ? <span className="text-rose-500">*</span> : null}
      </span>
      {children}
      {error ? (
        <p className="mt-0.5 flex items-start gap-1 text-[10.5px] font-semibold text-rose-600">
          <FiAlertCircle className="mt-0.5 h-3 w-3 shrink-0" />
          {error}
        </p>
      ) : hint ? (
        <p className="mt-0.5 text-[10.5px] text-slate-400">{hint}</p>
      ) : null}
    </label>
  )
}
