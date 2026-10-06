import type { ReactNode } from 'react'

/** A titled card for the details modals (shipment line, label order). */
export function Card({ icon, title, aside, children, className = '' }: {
  icon: ReactNode
  title: string
  /** Right side of the title row — chips, counts. */
  aside?: ReactNode
  children: ReactNode
  className?: string
}) {
  return (
    <section className={`min-w-0 rounded-xl border border-[var(--e-efe7d6)] bg-white p-4 ${className}`}>
      <div className="mb-2.5 flex items-center justify-between gap-2">
        <h4 className="flex items-center gap-2 text-[11px] font-semibold uppercase tracking-[0.1em] text-[var(--e-8a7a5a)]">
          <span className="inline-flex h-6 w-6 items-center justify-center rounded-md bg-[var(--e-f4eede)] text-[var(--e-412d15)]" aria-hidden="true">{icon}</span>
          {title}
        </h4>
        {aside}
      </div>
      {children}
    </section>
  )
}

/** Label / value rows; empty values show a quiet dash so the grid stays aligned. */
export function Rows({ rows }: { rows: [string, ReactNode, boolean?][] }) {
  return (
    <dl className="grid content-start grid-cols-[6.5rem_minmax(0,1fr)] gap-x-3 gap-y-1.5 text-[12.5px]">
      {rows.map(([label, value, mono]) => (
        <div key={label} className="contents">
          <dt className="text-[var(--e-a1906d)]">{label}</dt>
          <dd className={`min-w-0 break-words ${value ? `font-medium text-[var(--e-1f150c)] ${mono ? 'font-mono text-[12px]' : ''}` : 'text-[var(--e-cdbf9f)]'}`}>{value || '—'}</dd>
        </div>
      ))}
    </dl>
  )
}
