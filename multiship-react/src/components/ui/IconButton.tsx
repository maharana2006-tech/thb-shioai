import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react'

/**
 * Sprint 51 FE-M4 — a11y-first icon-only button.
 *
 * Icon-only buttons scattered across the app used to render as
 * {@code <button><FiX /></button>} with no aria-label — screen readers
 * announced them as "button" with no context. IconButton makes the
 * accessible name a required prop so a future icon-only button can't
 * be added without one.
 *
 * Kept intentionally thin — callers still bring their own Tailwind
 * classes for shape/colour. The wrapper only enforces the aria-label
 * contract and forwards standard {@code <button>} props.
 */
interface IconButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'aria-label'> {
  /** Required — the accessible name announced by screen readers. */
  label: string
  /** Rendered inside the button. Typically a react-icons element. */
  icon: ReactNode
  /**
   * When true, ALSO surface {@code label} as the native title tooltip.
   * Defaults to true so sighted keyboard users get the same hint.
   */
  showTooltip?: boolean
}

export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { label, icon, showTooltip = true, type, ...rest },
  ref,
) {
  return (
    <button
      ref={ref}
      // Default to type="button" so an IconButton inside a <form> never
      // accidentally submits the form when the caller forgets.
      type={type ?? 'button'}
      aria-label={label}
      title={showTooltip ? label : undefined}
      {...rest}
    >
      {icon}
    </button>
  )
})

export default IconButton
