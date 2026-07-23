/**
 * Themed-modal replacement for react-hot-toast + window.confirm/alert.
 *
 * Usage from any component:
 *
 *   import { notify } from '../utils/notify'
 *   notify.success('Mapping added.')
 *   notify.error('Failed to save.')
 *   notify.info('Nothing to sync.')
 *   if (await notify.confirm('Remove this P80 mapping?')) { ... }
 *
 * A single <NotifyHost /> mounted at the app root renders one modal at a time
 * (FIFO queue). Each call returns a Promise that resolves when the user
 * dismisses the modal; `notify.confirm` resolves to true/false, everything
 * else to void.
 */

export type NotifyType = 'success' | 'error' | 'info' | 'confirm'

export interface NotifyMessage {
  id: number
  type: NotifyType
  title?: string
  body: string
  confirmLabel?: string
  cancelLabel?: string
  /** Confirm-only: paints the confirm button red instead of the brand color. */
  danger?: boolean
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

export const notify = {
  success(opts: OptionsOrString): Promise<void> {
    return new Promise<void>((resolve) => {
      push({ type: 'success', ...normalize(opts), _resolve: () => resolve() })
    })
  },
  error(opts: OptionsOrString): Promise<void> {
    return new Promise<void>((resolve) => {
      push({ type: 'error', ...normalize(opts), _resolve: () => resolve() })
    })
  },
  info(opts: OptionsOrString): Promise<void> {
    return new Promise<void>((resolve) => {
      push({ type: 'info', ...normalize(opts), _resolve: () => resolve() })
    })
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
