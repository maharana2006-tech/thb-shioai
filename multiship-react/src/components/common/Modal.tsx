/**
 * Reusable themed modal shell. Matches the notify.confirm dialog theme
 * (bg-[var(--e-1f150c)]/50 backdrop, cream card, brown text) so every modal in
 * the app looks like it belongs to the same design system.
 *
 * Behavior: ESC closes; backdrop click closes; body scroll locked while
 * open; focus trapped inside on Tab.
 */
import { useEffect, useRef, type ReactNode } from 'react'
import { FiX } from 'react-icons/fi'

export interface ModalProps {
  open: boolean
  onClose: () => void
  title: string
  /** Optional short description under the title. */
  subtitle?: string
  /** md (default, 32rem) | lg (48rem) | xl (64rem). */
  size?: 'md' | 'lg' | 'xl'
  /** Body content. */
  children: ReactNode
  /** Footer actions — usually a Cancel + primary button. */
  footer?: ReactNode
  /** When false, backdrop click no longer closes. Escape still does. */
  dismissOnBackdrop?: boolean
}

const SIZE_CLASS: Record<Required<ModalProps>['size'], string> = {
  md: 'max-w-md',
  lg: 'max-w-2xl',
  xl: 'max-w-4xl',
}

export default function Modal({
  open,
  onClose,
  title,
  subtitle,
  size = 'md',
  children,
  footer,
  dismissOnBackdrop = true,
}: ModalProps) {
  const cardRef = useRef<HTMLDivElement>(null)

  // ESC to close + body scroll lock while open.
  useEffect(() => {
    if (!open) return
    const prevOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('keydown', onKey)
      document.body.style.overflow = prevOverflow
    }
  }, [open, onClose])

  if (!open) return null

  return (
    <div
      className="fixed inset-0 z-[70] flex items-center justify-center bg-[var(--e-1f150c)]/50 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-labelledby="modal-title"
      onMouseDown={(e) => {
        // Only close on backdrop click, not on inside-content drag-releases.
        if (dismissOnBackdrop && e.target === e.currentTarget) onClose()
      }}
    >
      <div
        ref={cardRef}
        className={`w-full ${SIZE_CLASS[size]} overflow-hidden rounded-2xl border border-[var(--e-e3d9c4)] bg-white shadow-[0_30px_80px_rgba(31,21,12,0.35)]`}
      >
        <header className="flex items-start justify-between gap-3 border-b border-[var(--e-eee6d6)] px-5 py-4">
          <div className="min-w-0">
            <h2 id="modal-title" className="text-[15px] font-semibold text-[var(--e-1f150c)]">
              {title}
            </h2>
            {subtitle && (
              <p className="mt-0.5 text-[12px] leading-snug text-[var(--e-5a4526)]">{subtitle}</p>
            )}
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close"
            className="-mr-1 rounded-md p-1.5 text-[var(--e-b6a684)] transition hover:bg-[var(--e-faf7f0)] hover:text-[var(--e-412d15)]"
          >
            <FiX className="h-4 w-4" />
          </button>
        </header>

        <div className="max-h-[70vh] overflow-y-auto px-5 py-4">{children}</div>

        {footer && (
          <footer className="flex items-center justify-end gap-2 rounded-b-2xl border-t border-[var(--e-eee6d6)] bg-[var(--e-faf7f0)]/60 px-5 py-3">
            {footer}
          </footer>
        )}
      </div>
    </div>
  )
}

/** Common Cancel + primary Save action set for modal footers. */
export function ModalActions({
  onCancel,
  onConfirm,
  cancelLabel = 'Cancel',
  confirmLabel = 'Save',
  confirmDisabled = false,
  confirmLoading = false,
  danger = false,
}: {
  onCancel: () => void
  onConfirm: () => void
  cancelLabel?: string
  confirmLabel?: string
  confirmDisabled?: boolean
  confirmLoading?: boolean
  danger?: boolean
}) {
  return (
    <>
      <button
        type="button"
        onClick={onCancel}
        className="rounded-xl border border-[var(--e-e3d9c4)] bg-white px-4 py-2 text-[13px] font-semibold text-[var(--e-5a4526)] transition hover:bg-[var(--e-faf7f0)]"
      >
        {cancelLabel}
      </button>
      <button
        type="button"
        onClick={onConfirm}
        disabled={confirmDisabled || confirmLoading}
        className={`rounded-xl px-5 py-2 text-[13px] font-semibold text-white transition disabled:opacity-60 ${
          danger ? 'bg-rose-600 hover:bg-rose-700' : 'bg-[var(--e-1f150c)] hover:bg-[var(--e-412d15)]'
        }`}
      >
        {confirmLoading ? 'Working…' : confirmLabel}
      </button>
    </>
  )
}
