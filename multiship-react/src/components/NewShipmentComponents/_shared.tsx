/**
 * F5-C — primitives shared by NewShipmentPage sub-components.
 * Extracted from the top of NewShipmentPage.tsx so each new-shipment
 * component file can import from one place instead of dragging the
 * whole ~4.8K-LoC page to test a single form section.
 *
 * <p>Only React components + string constants here (react-refresh
 * tolerates strings alongside components). Types live in
 * {@link ./_types.ts}.
 */
import { cloneElement, isValidElement, type ReactNode } from 'react'

export const inputCls =
  'w-full rounded-xl border border-[#e3d9c4] bg-white px-3 py-2 text-[13px] text-[#1f150c] outline-none transition placeholder:text-[#b6a684] focus:border-[#cdbf9f] focus:ring-4 focus:ring-[#f4eede] disabled:cursor-not-allowed disabled:bg-[#faf7f0] disabled:text-[#6b5c42]'

export interface SectionRailItem {
  id: string
  label: string
  done: boolean
  show?: boolean
}

/**
 * Sticky section rail — where am I, what's still missing, jump there.
 * The page is one long scroll (a 45-box international order runs past
 * 4,000 px); blockers like "fill in unit prices" used to sit 1,500 px
 * from the field that fixes them.
 */
export function SectionRail({ sections }: { sections: SectionRailItem[] }) {
  const visible = sections.filter((x) => x.show !== false)
  const jump = (id: string) =>
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  return (
    <nav aria-label="Shipment sections" className="sticky top-2 z-30 -mx-1 mb-3 flex flex-wrap items-center gap-1.5 rounded-2xl border border-[#e3d9c4] bg-[#fdfbf6]/95 px-2 py-1.5 shadow-sm backdrop-blur">
      {visible.map((x, i) => (
        <button
          key={x.id}
          type="button"
          onClick={() => jump(x.id)}
          aria-label={`${x.label}${x.done ? ' — complete' : ' — needs attention'}`}
          className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-[11px] font-semibold transition ${
            x.done ? 'border-emerald-200 bg-emerald-50 text-emerald-800 hover:bg-emerald-100' : 'border-[#e3d9c4] bg-white text-[#5a4526] hover:bg-[#faf7f0]'
          }`}
        >
          <span className={`inline-flex h-4 w-4 items-center justify-center rounded-full text-[9px] font-bold ${x.done ? 'bg-emerald-600 text-white' : 'bg-[#f4eede] text-[#6b5c42]'}`}>
            {x.done ? '✓' : i + 1}
          </span>
          {x.label}
        </button>
      ))}
    </nav>
  )
}

export interface FieldProps {
  label: string
  required?: boolean
  hint?: string
  error?: string | false | null
  title?: string
  children: ReactNode
  className?: string
}

export function Field({
  label,
  required,
  hint,
  error,
  title,
  children,
  className = '',
}: FieldProps) {
  return (
    <label
      className={`block space-y-1 ${error ? '[&_input]:!border-rose-400 [&_select]:!border-rose-400 [&_textarea]:!border-rose-400' : ''} ${className}`}
      title={title}
    >
      <span className="text-[11px] font-bold uppercase tracking-[0.14em] text-[#6b5c42]">
        {label}
        {required ? <span className="text-rose-500"> *</span> : null}
      </span>
      {error && isValidElement<{ 'aria-invalid'?: boolean }>(children)
        ? cloneElement(children, { 'aria-invalid': true })
        : children}
      {error ? (
        <span className="ms-field-error mt-1 block text-[10.5px] font-semibold normal-case tracking-normal text-rose-600">{error}</span>
      ) : hint ? (
        <span className="mt-1 block text-[10.5px] normal-case tracking-normal text-slate-400">{hint}</span>
      ) : null}
    </label>
  )
}

export interface SectionCardProps {
  id?: string
  icon: ReactNode
  title: string
  badge?: ReactNode
  note?: ReactNode
  className?: string
  /** @deprecated headers always wrap now; kept so existing callers compile. */
  wrapHeader?: boolean
  children: ReactNode
}

/** Espresso section shell used across the page. */
export function SectionCard({
  id,
  icon,
  title,
  badge,
  note,
  className = '',
  children,
}: SectionCardProps) {
  return (
    <section id={id} className={`scroll-mt-24 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm sm:p-5 ${className}`}>
      {/* Wraps when the badges don't fit (phones) instead of pushing the page wider. */}
      <div className="flex min-h-[38px] flex-wrap items-center justify-between gap-2 border-b border-dashed border-[#e3d9c4] pb-2">
        <div className="flex items-center gap-2">
          <span className="text-[#6b5c42]">{icon}</span>
          <h3 className="font-mono text-[11px] font-bold uppercase tracking-[0.16em] text-[#6b5c42]">{title}</h3>
        </div>
        {badge}
      </div>
      {note ? <p className="mt-2 text-[11px] text-[#6b5c42]">{note}</p> : null}
      <div className="mt-3">{children}</div>
    </section>
  )
}
