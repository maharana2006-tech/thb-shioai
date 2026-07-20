import { type SelectHTMLAttributes } from 'react'
import { FiChevronDown } from 'react-icons/fi'

/**
 * A styled native <select> — custom chevron, rounded, espresso focus ring.
 * Drop-in replacement for a bare <select> anywhere a nicer dropdown is wanted.
 */
export default function Select({ className = '', children, ...props }: SelectHTMLAttributes<HTMLSelectElement>) {
  return (
    <div className="relative">
      <select
        {...props}
        className={`w-full cursor-pointer appearance-none rounded-xl border border-slate-200 bg-white px-3 py-2 pr-9 text-[12.5px] font-semibold text-slate-700 shadow-sm outline-none transition hover:border-slate-300 focus:border-[#412d15] focus:ring-4 focus:ring-[#412d15]/10 disabled:cursor-not-allowed disabled:bg-slate-100 disabled:text-slate-400 ${className}`}
      >
        {children}
      </select>
      <FiChevronDown className="pointer-events-none absolute right-3 top-1/2 h-4 w-4 -translate-y-1/2 text-[#412d15]" />
    </div>
  )
}
