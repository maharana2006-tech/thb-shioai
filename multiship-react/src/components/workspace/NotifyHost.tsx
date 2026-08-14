import { useEffect, useRef, useState } from 'react'
import { FiAlertCircle, FiCheckCircle, FiHelpCircle, FiInfo, FiX } from 'react-icons/fi'
import { notifyStore, type NotifyMessage, type NotifyType } from '../../utils/notify'

/**
 * Rendered once at the app root. Two surfaces:
 *
 *  · toast stack (bottom-right) for success / error / info — non-blocking,
 *    newest on top, hover pauses the auto-dismiss timer so a message can't
 *    disappear while it's being read.
 *  · confirm modal — still blocking, because a decision gate should be.
 */
export default function NotifyHost() {
  const [messages, setMessages] = useState<NotifyMessage[]>(() => notifyStore.snapshot())

  useEffect(() => notifyStore.subscribe(setMessages), [])

  const confirmMsg = messages.find((m) => m.type === 'confirm')
  const toasts = messages.filter((m) => m.type !== 'confirm')

  // Escape cancels the confirm dialog; with no dialog open it clears the
  // newest toast, which is the one the operator is most likely reacting to.
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key !== 'Escape') return
      if (confirmMsg) notifyStore.dismiss(confirmMsg.id, false)
      else if (toasts.length) notifyStore.dismiss(toasts[toasts.length - 1].id, false)
    }
    document.addEventListener('keydown', handler)
    return () => document.removeEventListener('keydown', handler)
  }, [confirmMsg, toasts])

  return (
    <>
      {toasts.length > 0 ? <ToastStack toasts={toasts} /> : null}
      {confirmMsg ? <ConfirmModal message={confirmMsg} /> : null}
    </>
  )
}

/** Most recent toasts are shown; older ones collapse into a counter so a
 *  burst of messages can never cover the workspace. */
const MAX_VISIBLE = 4

function ToastStack({ toasts }: { toasts: NotifyMessage[] }) {
  const newestFirst = [...toasts].reverse()
  const visible = newestFirst.slice(0, MAX_VISIBLE)
  const hidden = newestFirst.length - visible.length

  return (
    <div
      className="pointer-events-none fixed bottom-4 right-4 z-[60] flex w-[min(380px,calc(100vw-2rem))] flex-col items-end gap-2 print:hidden"
      aria-live="polite"
    >
      {hidden > 0 ? (
        <button
          type="button"
          onClick={() => newestFirst.slice(MAX_VISIBLE).forEach((t) => notifyStore.dismiss(t.id, false))}
          className="pointer-events-auto rounded-full border border-[#e3d9c4] bg-white/95 px-3 py-1 text-[10.5px] font-semibold text-[#8a7959] shadow-sm backdrop-blur transition hover:bg-[#faf7f0]"
        >
          +{hidden} more · clear
        </button>
      ) : null}

      {visible.map((toast) => (
        <Toast key={toast.id} message={toast} />
      ))}

      {toasts.length > 1 ? (
        <button
          type="button"
          onClick={() => toasts.forEach((t) => notifyStore.dismiss(t.id, false))}
          className="pointer-events-auto rounded-full border border-[#e3d9c4] bg-white/95 px-3 py-1 text-[10.5px] font-semibold text-[#8a7959] shadow-sm backdrop-blur transition hover:bg-[#faf7f0]"
        >
          Dismiss all
        </button>
      ) : null}
    </div>
  )
}

function Toast({ message }: { message: NotifyMessage }) {
  const theme = THEME[message.type]
  const [leaving, setLeaving] = useState(false)
  const [paused, setPaused] = useState(false)
  /** Remaining lifetime, so hovering pauses rather than restarts the clock. */
  const remaining = useRef(message.durationMs ?? 0)
  const startedAt = useRef(Date.now())

  const close = () => {
    setLeaving(true)
    // Let the exit transition play before the message leaves the store.
    setTimeout(() => notifyStore.dismiss(message.id, false), 160)
  }

  useEffect(() => {
    if (!message.durationMs || paused) return
    startedAt.current = Date.now()
    const timer = setTimeout(close, remaining.current)
    return () => {
      clearTimeout(timer)
      remaining.current = Math.max(0, remaining.current - (Date.now() - startedAt.current))
    }
    // `close` is stable enough for this lifecycle; re-running on pause is the
    // whole point.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [paused, message.durationMs])

  return (
    <div
      role={message.type === 'error' ? 'alert' : 'status'}
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
      className={`pointer-events-auto w-full overflow-hidden rounded-xl border bg-white shadow-[0_12px_32px_rgba(31,21,12,0.18)] transition-all duration-150 ${
        theme.border
      } ${leaving ? 'translate-x-2 opacity-0' : 'translate-x-0 opacity-100'}`}
    >
      <div className="flex items-start gap-2.5 px-3 py-2.5">
        <span className={`mt-0.5 inline-flex h-6 w-6 shrink-0 items-center justify-center rounded-lg ${theme.iconBg}`}>
          <theme.icon className={`h-3.5 w-3.5 ${theme.iconColor}`} />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-[12.5px] font-semibold leading-tight text-[#1f150c]">
            {message.title || theme.defaultTitle}
          </p>
          {message.body ? (
            <p className="mt-0.5 whitespace-pre-line break-words text-[11.5px] leading-snug text-[#5a4526]">
              {message.body}
            </p>
          ) : null}
        </div>
        <button
          type="button"
          onClick={close}
          aria-label="Dismiss"
          className="-mr-0.5 rounded-md p-1 text-[#b6a684] transition hover:bg-[#faf7f0] hover:text-[#412d15]"
        >
          <FiX className="h-3.5 w-3.5" />
        </button>
      </div>
      {/* Lifetime bar — only for self-dismissing toasts. */}
      {message.durationMs ? (
        <span
          className={`block h-0.5 origin-left ${theme.bar}`}
          style={{
            animation: `notify-countdown ${message.durationMs}ms linear forwards`,
            animationPlayState: paused ? 'paused' : 'running',
          }}
        />
      ) : null}
    </div>
  )
}

function ConfirmModal({ message }: { message: NotifyMessage }) {
  const theme = THEME.confirm
  return (
    <div
      className="fixed inset-0 z-[70] flex items-center justify-center bg-[#1f150c]/50 p-4 backdrop-blur-sm"
      role="dialog"
      aria-modal="true"
      aria-labelledby={`notify-${message.id}-title`}
    >
      <div className="w-full max-w-md rounded-2xl border border-[#e3d9c4] bg-white shadow-[0_30px_80px_rgba(31,21,12,0.35)]">
        <div className="flex items-start gap-3 px-5 pt-5">
          <span className={`inline-flex h-10 w-10 shrink-0 items-center justify-center rounded-xl ${theme.iconBg}`}>
            <theme.icon className={`h-5 w-5 ${theme.iconColor}`} />
          </span>
          <div className="min-w-0 flex-1">
            <h3 id={`notify-${message.id}-title`} className="text-[15px] font-semibold text-[#1f150c]">
              {message.title || theme.defaultTitle}
            </h3>
            <p className="mt-1 whitespace-pre-line text-[13px] leading-5 text-[#5a4526]">{message.body}</p>
          </div>
        </div>

        <div className="mt-5 flex items-center justify-end gap-2 rounded-b-2xl border-t border-[#eee6d6] bg-[#faf7f0]/60 px-5 py-3">
          <button
            type="button"
            onClick={() => notifyStore.dismiss(message.id, false)}
            className="rounded-xl border border-[#e3d9c4] bg-white px-4 py-2 text-[13px] font-semibold text-[#5a4526] transition hover:bg-[#faf7f0]"
            autoFocus
          >
            {message.cancelLabel || 'Cancel'}
          </button>
          <button
            type="button"
            onClick={() => notifyStore.dismiss(message.id, true)}
            className={`rounded-xl px-5 py-2 text-[13px] font-semibold text-white transition ${
              message.danger ? 'bg-rose-600 hover:bg-rose-700' : 'bg-[#1f150c] hover:bg-[#412d15]'
            }`}
          >
            {message.confirmLabel || 'Confirm'}
          </button>
        </div>
      </div>
    </div>
  )
}

const THEME: Record<NotifyType, {
  icon: React.ComponentType<{ className?: string }>
  iconBg: string
  iconColor: string
  border: string
  bar: string
  defaultTitle: string
}> = {
  success: {
    icon: FiCheckCircle,
    iconBg: 'bg-emerald-50',
    iconColor: 'text-emerald-600',
    border: 'border-emerald-200',
    bar: 'bg-emerald-400',
    defaultTitle: 'Done',
  },
  error: {
    icon: FiAlertCircle,
    iconBg: 'bg-rose-50',
    iconColor: 'text-rose-600',
    border: 'border-rose-200',
    bar: 'bg-rose-400',
    defaultTitle: 'Something went wrong',
  },
  info: {
    icon: FiInfo,
    iconBg: 'bg-[#f4eede]',
    iconColor: 'text-[#412d15]',
    border: 'border-[#e3d9c4]',
    bar: 'bg-[#b6a684]',
    defaultTitle: 'Heads up',
  },
  confirm: {
    icon: FiHelpCircle,
    iconBg: 'bg-amber-50',
    iconColor: 'text-amber-600',
    border: 'border-amber-200',
    bar: 'bg-amber-400',
    defaultTitle: 'Please confirm',
  },
}
