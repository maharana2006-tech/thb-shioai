import { useEffect, useState } from 'react'
import { FiAlertCircle, FiCheckCircle, FiHelpCircle, FiInfo, FiX } from 'react-icons/fi'
import { notifyStore, type NotifyMessage, type NotifyType } from '../../utils/notify'

/**
 * Rendered once at the app root; subscribes to the notify store and displays
 * one themed modal at a time (FIFO). Handles success/error/info/confirm.
 */
export default function NotifyHost() {
  const [messages, setMessages] = useState<NotifyMessage[]>(() => notifyStore.snapshot())

  useEffect(() => {
    return notifyStore.subscribe(setMessages)
  }, [])

  const current = messages[0]

  // Escape key = cancel (for confirm) / dismiss (for info messages).
  useEffect(() => {
    if (!current) return
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') notifyStore.dismiss(current.id, false)
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [current])

  if (!current) return null

  const isConfirm = current.type === 'confirm'
  const theme = THEME[current.type]

  return (
    <div
      className="fixed inset-0 z-[60] flex items-center justify-center bg-slate-950/50 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-labelledby={`notify-${current.id}-title`}
    >
      <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white shadow-[0_30px_80px_rgba(15,23,42,0.35)]">
        <div className="flex items-start gap-3 px-5 pt-5">
          <span
            className={`inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${theme.iconBg}`}
          >
            <theme.icon className={`h-5 w-5 ${theme.iconColor}`} />
          </span>
          <div className="min-w-0 flex-1">
            <h3
              id={`notify-${current.id}-title`}
              className="text-[15px] font-semibold text-slate-950"
            >
              {current.title || theme.defaultTitle}
            </h3>
            <p className="mt-1 whitespace-pre-line text-[13px] leading-5 text-slate-600">
              {current.body}
            </p>
          </div>
          {!isConfirm ? (
            <button
              type="button"
              onClick={() => notifyStore.dismiss(current.id, false)}
              aria-label="Close"
              className="rounded-lg border border-transparent p-1.5 text-slate-400 transition hover:border-slate-200 hover:text-slate-600"
            >
              <FiX className="h-4 w-4" />
            </button>
          ) : null}
        </div>

        <div className="mt-5 flex items-center justify-end gap-2 rounded-b-2xl border-t border-slate-100 bg-slate-50/60 px-5 py-3">
          {isConfirm ? (
            <>
              <button
                type="button"
                onClick={() => notifyStore.dismiss(current.id, false)}
                className="rounded-xl border border-slate-200 bg-white px-4 py-2 text-[13px] font-semibold text-slate-600 transition hover:bg-slate-100"
                autoFocus
              >
                {current.cancelLabel || 'Cancel'}
              </button>
              <button
                type="button"
                onClick={() => notifyStore.dismiss(current.id, true)}
                className={`rounded-xl px-5 py-2 text-[13px] font-semibold text-white transition ${
                  current.danger
                    ? 'bg-rose-600 hover:bg-rose-700'
                    : 'bg-[#1f150c] hover:bg-[#412d15]'
                }`}
              >
                {current.confirmLabel || 'Confirm'}
              </button>
            </>
          ) : (
            <button
              type="button"
              onClick={() => notifyStore.dismiss(current.id, false)}
              autoFocus
              className="rounded-xl bg-[#1f150c] px-5 py-2 text-[13px] font-semibold text-white transition hover:bg-[#412d15]"
            >
              OK
            </button>
          )}
        </div>
      </div>
    </div>
  )
}

const THEME: Record<NotifyType, {
  icon: React.ComponentType<{ className?: string }>
  iconBg: string
  iconColor: string
  defaultTitle: string
}> = {
  success: {
    icon: FiCheckCircle,
    iconBg: 'bg-emerald-50',
    iconColor: 'text-emerald-600',
    defaultTitle: 'Success',
  },
  error: {
    icon: FiAlertCircle,
    iconBg: 'bg-rose-50',
    iconColor: 'text-rose-600',
    defaultTitle: 'Something went wrong',
  },
  info: {
    icon: FiInfo,
    iconBg: 'bg-sky-50',
    iconColor: 'text-sky-600',
    defaultTitle: 'Heads up',
  },
  confirm: {
    icon: FiHelpCircle,
    iconBg: 'bg-amber-50',
    iconColor: 'text-amber-600',
    defaultTitle: 'Please confirm',
  },
}
