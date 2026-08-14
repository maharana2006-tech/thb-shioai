/**
 * App-wide notifications. Two delivery modes, chosen by intent:
 *
 *   · TOAST  — success / error / info. Non-blocking, stacked bottom-right,
 *              never steals focus. Success + info self-dismiss; errors stay
 *              until dismissed so a failure can't scroll past unnoticed.
 *   · MODAL  — confirm only. A decision gate genuinely has to block.
 *
 * Usage is unchanged from the modal-only era — every existing call site
 * keeps working, it just stops interrupting:
 *
 *   import { notify } from '../utils/notify'
 *   notify.success('Mapping added.')
 *   notify.error('Failed to save.')
 *   notify.info('Nothing to sync.')
 *   if (await notify.confirm('Remove this P80 mapping?')) { ... }
 *   catch (err) { notify.apiError(err) }
 *
 * A single <NotifyHost /> mounted at the app root renders the toast stack
 * and the confirm modal. `notify.confirm` resolves true/false when the user
 * answers; toast calls resolve immediately (nothing waits on a toast).
 */

import { getFriendlyError } from './errorMessages'

export type NotifyType = 'success' | 'error' | 'info' | 'confirm'

/** How long a toast lives before self-dismissing. 0 = until dismissed. */
const AUTO_DISMISS_MS: Record<Exclude<NotifyType, 'confirm'>, number> = {
  success: 4000,
  info: 5000,
  // Errors are sticky on purpose: they're the ones worth reading, and a
  // non-blocking toast that vanishes is worse than one you close yourself.
  error: 0,
}

export interface NotifyMessage {
  id: number
  type: NotifyType
  title?: string
  body: string
  confirmLabel?: string
  cancelLabel?: string
  /** Confirm-only: paints the confirm button red instead of the brand color. */
  danger?: boolean
  /** Milliseconds until auto-dismiss; 0 (or confirm) means it waits. */
  durationMs?: number
  /** Internal: resolves the awaiting caller when the modal is dismissed. */
  _resolve?: (value: boolean) => void
}

type Listener = (messages: NotifyMessage[]) => void

let messages: NotifyMessage[] = []
const listeners = new Set<Listener>()
let nextId = 1

function emit() {
  for (const l of listeners) l(messages)
}

function push(msg: Omit<NotifyMessage, 'id'>): NotifyMessage {
  const withId = { ...msg, id: nextId++ }
  messages = [...messages, withId]
  emit()
  return withId
}

/** Internal API used by NotifyHost. */
export const notifyStore = {
  subscribe(fn: Listener) {
    listeners.add(fn)
    return () => {
      listeners.delete(fn)
    }
  },
  snapshot() {
    return messages
  },
  dismiss(id: number, value = false) {
    const msg = messages.find((m) => m.id === id)
    if (msg?._resolve) msg._resolve(value)
    messages = messages.filter((m) => m.id !== id)
    emit()
  },
}

type OptionsOrString = string | { title?: string; body: string }

function normalize(opts: OptionsOrString): { title?: string; body: string } {
  return typeof opts === 'string' ? { body: opts } : opts
}

/** Toasts never block, so the promise settles as soon as it's queued —
 *  nothing in the app waits on a toast, and returning a promise keeps the
 *  old signature valid for any `void notify.x()` call sites. */
function toast(type: Exclude<NotifyType, 'confirm'>, opts: OptionsOrString): Promise<void> {
  push({ type, ...normalize(opts), durationMs: AUTO_DISMISS_MS[type] })
  return Promise.resolve()
}

export const notify = {
  success(opts: OptionsOrString): Promise<void> {
    return toast('success', opts)
  },
  error(opts: OptionsOrString): Promise<void> {
    return toast('error', opts)
  },
  /**
   * Sprint 50 PR J — one-shot error handler for ApiClient rejections.
   *
   * Every catch block that used to inline `if (error instanceof ApiError && error.errorCode === ...)`
   * can now just call `notify.apiError(error)` and get uniform friendly-message
   * lookup + fallback. Unknown codes fall through to the raw server message,
   * which was the pre-fix behavior — so this is strictly an upgrade.
   *
   *   catch (err) { notify.apiError(err) }
   *   catch (err) { notify.apiError(err, 'Failed to save shipping policy.') }
   */
  apiError(err: unknown, fallback?: string): Promise<void> {
    const anyErr = err as { errorCode?: string | null; message?: string | null }
    const friendly = getFriendlyError(anyErr?.errorCode, anyErr?.message, fallback)
    return toast('error', { title: friendly.title, body: friendly.message })
  },
  info(opts: OptionsOrString): Promise<void> {
    return toast('info', opts)
  },
  confirm(
    body: string,
    options?: {
      title?: string
      confirmLabel?: string
      cancelLabel?: string
      danger?: boolean
    },
  ): Promise<boolean> {
    return new Promise<boolean>((resolve) => {
      push({
        type: 'confirm',
        body,
        title: options?.title,
        confirmLabel: options?.confirmLabel,
        cancelLabel: options?.cancelLabel,
        danger: options?.danger,
        _resolve: resolve,
      })
    })
  },
}
