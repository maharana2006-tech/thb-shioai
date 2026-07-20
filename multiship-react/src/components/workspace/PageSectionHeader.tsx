import type { ReactNode } from 'react'

interface PageSectionHeaderProps {
  /** Kept for call-site compatibility; the topbar breadcrumb now owns this role. */
  eyebrow?: string
  title: string
  description: string
  actions?: ReactNode
}

/**
 * Compact page header: a short noun title and one muted line of context.
 * Section context lives in the topbar breadcrumb, so no eyebrow here.
 */
export default function PageSectionHeader(props: PageSectionHeaderProps) {
  const { title, description, actions } = props

  return (
    <div className="flex flex-wrap items-end justify-between gap-x-6 gap-y-3 px-1 pt-1">
      <div className="min-w-0">
        <h2 className="text-xl font-semibold tracking-tight text-[#1f150c]">{title}</h2>
        <p className="mt-1 max-w-2xl text-[13px] leading-5 text-slate-500">{description}</p>
      </div>

      {actions ? <div className="flex flex-wrap items-center gap-2">{actions}</div> : null}
    </div>
  )
}
