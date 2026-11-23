import { useEffect, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'
import {
  FiArrowUp,
  FiCheck,
  FiCompass,
  FiCopy,
  FiEdit,
  FiEye,
  FiFileText,
  FiGlobe,
  FiMaximize2,
  FiMinimize2,
  FiPackage,
  FiSquare,
  FiX,
  FiZap,
} from 'react-icons/fi'
import {
  ShipmateError,
  shipmateService,
  starterPrompts,
  type ShipmateEvent,
  type ShipmateFailure,
  type ShipmatePrompt,
  type ShipmateRequest,
  type ShipmateTurn,
} from '../../api/shipmateService'
import { useAppSession } from '../../hooks/useAppSession'
import { resolveBreadcrumb } from '../../routes/workspaceRoutes'
import IconButton from '../ui/IconButton'
import { BTN_GHOST_SM, BTN_PRIMARY_SM } from '../ui/buttons'

type ConfirmState = 'pending' | 'confirmed' | 'cancelled'
type EventOf<T extends ShipmateEvent['type']> = Extract<ShipmateEvent, { type: T }>

type Item =
  | { kind: 'user'; key: number; text: string }
  | { kind: 'ai'; key: number; turn: number; text: string }
  | { kind: 'confirm'; key: number; card: EventOf<'confirm'>; state: ConfirmState }
  | { kind: 'table'; key: number; card: EventOf<'table'> }
  | { kind: 'progress'; key: number; card: EventOf<'progress'> }
  | { kind: 'notice'; key: number; failure: ShipmateFailure; request: ShipmateRequest }
  | { kind: 'stopped'; key: number }

type ItemOf<K extends Item['kind']> = Extract<Item, { kind: K }>

let keySeq = 0

const applyEvent = (items: Item[], event: Exclude<ShipmateEvent, { type: 'status' }>, turn: number, key: number): Item[] => {
  switch (event.type) {
    case 'text': {
      const last = items.at(-1)
      return last?.kind === 'ai' && last.turn === turn
        ? [...items.slice(0, -1), { ...last, text: last.text + event.delta }]
        : [...items, { kind: 'ai', key, turn, text: event.delta }]
    }
    case 'confirm':
      return [...items, { kind: 'confirm', key, card: event, state: 'pending' }]
    case 'table':
      return [...items, { kind: 'table', key, card: event }]
    case 'progress':
      return items.some((i) => i.kind === 'progress' && i.card.id === event.id)
        ? items.map((i) => (i.kind === 'progress' && i.card.id === event.id ? { ...i, card: event } : i))
        : [...items, { kind: 'progress', key, card: event }]
  }
}

const NOTICE: Record<ShipmateFailure, { title: string; body: string }> = {
  unavailable: {
    title: 'Shipmate is not available right now',
    body: 'Nothing was changed. Everything on this page still works by hand.',
  },
  limit: {
    title: 'Too many AI requests right now',
    body: 'Your team has reached its per-minute limit. Wait a moment, then try again.',
  },
}

const CONFIRM_TAG: Record<ConfirmState, { label: string; tone: string }> = {
  pending: { label: 'Needs your OK', tone: 'bg-[#f3f0ff] text-[#5b3fd1]' },
  confirmed: { label: 'Confirmed', tone: 'bg-emerald-50 text-emerald-700' },
  cancelled: { label: 'Cancelled', tone: 'bg-slate-100 text-slate-500' },
}

const PROMPT_ICON: Record<ShipmatePrompt['icon'], ReactNode> = {
  help: <FiCompass className="h-4 w-4" />,
  customs: <FiFileText className="h-4 w-4" />,
  incoterm: <FiGlobe className="h-4 w-4" />,
  packing: <FiPackage className="h-4 w-4" />,
}

const greeting = () => {
  const hour = new Date().getHours()
  return hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening'
}

const CARD = 'overflow-hidden rounded-xl border border-[#e3d9c4] bg-white'
const CARD_TITLE = 'text-[13px] font-bold text-[#1f150c]'
const TAG = 'rounded-full px-2 py-0.5 text-[10.5px] font-semibold'
const HEAD_BTN = 'rounded-lg p-1.5 text-slate-500 transition hover:bg-slate-100 hover:text-[#1f150c] disabled:cursor-not-allowed disabled:opacity-40'
const SEND = 'flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-[#1f150c] text-[#f4eede] transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-[#dcd4c4]'
const CHIP = 'shrink-0 rounded-full border border-slate-200 bg-white px-2.5 py-1 text-[12px] text-slate-600 transition hover:border-[#5b3fd1] hover:text-[#5b3fd1]'

export const SHIPMATE_DEFAULT_WIDTH = 380
const MIN_WIDTH = 320
/** Past this the page behind is too narrow to be worth keeping; full screen is the next step. */
const maxWidth = () => Math.max(MIN_WIDTH, window.innerWidth - 300)

interface ShipmatePanelProps {
  open: boolean
  onClose: () => void
  /** Docked width in px. Leave both out for a fixed-width panel. */
  width?: number
  onWidthChange?: (width: number) => void
}

/**
 * Shipmate: the assistant docked on the right of every workspace page, or
 * expanded over the whole window. It stays mounted while hidden so the
 * conversation survives closing the panel and moving between pages.
 */
export default function ShipmatePanel({ open, onClose, width = SHIPMATE_DEFAULT_WIDTH, onWidthChange }: ShipmatePanelProps) {
  const page = resolveBreadcrumb(useLocation().pathname)?.label ?? 'Multiship'
  const { username } = useAppSession()
  const [items, setItems] = useState<Item[]>([])
  const [input, setInput] = useState('')
  const [full, setFull] = useState(false)
  /** The reply being received, or null when idle. */
  const [activeTurn, setActiveTurn] = useState<number | null>(null)
  /** False from sending until the first piece of the reply arrives. */
  const [started, setStarted] = useState(false)
  const [status, setStatus] = useState<string | null>(null)
  const [copiedKey, setCopiedKey] = useState<number | null>(null)
  const abortRef = useRef<AbortController | null>(null)
  const turnRef = useRef(0)
  const threadRef = useRef<HTMLDivElement>(null)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const busy = activeTurn !== null
  const prompts = starterPrompts(page)
  // The server keeps no conversation: every request carries the recent turns.
  const history: ShipmateTurn[] = items
    .flatMap((item): ShipmateTurn[] =>
      item.kind === 'user'
        ? [{ role: 'user', text: item.text }]
        : item.kind === 'ai'
          ? [{ role: 'assistant', text: item.text.slice(0, 8000) }]
          : [],
    )
    .slice(-20)

  useEffect(() => {
    if (open) inputRef.current?.focus()
  }, [open, full])

  useEffect(() => {
    const thread = threadRef.current
    if (thread) thread.scrollTop = thread.scrollHeight
  }, [items, busy, status])

  useEffect(() => () => abortRef.current?.abort(), [])

  const run = async (request: ShipmateRequest) => {
    const controller = new AbortController()
    const turn = ++turnRef.current
    abortRef.current = controller
    setActiveTurn(turn)
    setStarted(false)
    setStatus(null)
    try {
      for await (const event of shipmateService.send(request, controller.signal)) {
        if (event.type === 'status') {
          setStatus(event.text)
          continue
        }
        const key = ++keySeq
        setStarted(true)
        setStatus(null)
        setItems((prev) => applyEvent(prev, event, turn, key))
      }
    } catch (err) {
      const key = ++keySeq
      // A stopped reply is the user's choice, not a failure.
      const item: Item = controller.signal.aborted
        ? { kind: 'stopped', key }
        : { kind: 'notice', key, failure: err instanceof ShipmateError ? err.kind : 'unavailable', request }
      setItems((prev) => [...prev, item])
    } finally {
      if (abortRef.current === controller) {
        abortRef.current = null
        setActiveTurn(null)
        setStatus(null)
      }
    }
  }

  const ask = (message: string) => {
    const text = message.trim()
    if (!text || busy) return
    const key = ++keySeq
    setItems((prev) => [...prev, { kind: 'user', key, text }])
    setInput('')
    void run({ message: text, page, history })
  }

  const decide = (cardId: string, accepted: boolean) => {
    const state: ConfirmState = accepted ? 'confirmed' : 'cancelled'
    setItems((prev) => prev.map((i) => (i.kind === 'confirm' && i.card.id === cardId ? { ...i, state } : i)))
    void run({ message: '', page, history, decision: { id: cardId, accepted } })
  }

  const retry = (notice: ItemOf<'notice'>) => {
    setItems((prev) => prev.filter((i) => i.key !== notice.key))
    void run(notice.request)
  }

  const copy = async (item: ItemOf<'ai'>) => {
    try {
      await navigator.clipboard.writeText(item.text)
      setCopiedKey(item.key)
      setTimeout(() => setCopiedKey((cur) => (cur === item.key ? null : cur)), 1500)
    } catch {
      // Clipboard blocked by the browser: there is nothing to confirm, so stay quiet.
    }
  }

  const resize = (next: number) => onWidthChange?.(Math.round(Math.min(Math.max(next, MIN_WIDTH), maxWidth())))

  const onInputKey = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      ask(input)
    }
  }

  const renderItem = (item: Item, index: number) => {
    switch (item.kind) {
      case 'user':
        return (
          <p className="max-w-[88%] self-end whitespace-pre-wrap rounded-2xl rounded-br-sm bg-[#1f150c] px-3.5 py-2 text-[13.5px] leading-relaxed text-[#f4eede]">
            {item.text}
          </p>
        )
      case 'ai': {
        const streaming = item.turn === activeTurn && index === items.length - 1
        const copied = copiedKey === item.key
        return (
          <div>
            <p className="whitespace-pre-wrap text-[13.5px] leading-relaxed text-[#1f150c]">
              {item.text}
              {streaming ? (
                <span aria-hidden="true" className="ml-0.5 inline-block h-3.5 w-[2px] translate-y-0.5 animate-pulse bg-[#5b3fd1] motion-reduce:animate-none" />
              ) : null}
            </p>
            {streaming ? null : (
              <button
                type="button"
                onClick={() => void copy(item)}
                className="mt-1 inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 text-[11px] text-slate-400 transition hover:bg-slate-100 hover:text-slate-700"
              >
                {copied ? <FiCheck className="h-3 w-3" /> : <FiCopy className="h-3 w-3" />}
                {copied ? 'Copied' : 'Copy'}
              </button>
            )}
          </div>
        )
      }
      case 'confirm': {
        const { card, state } = item
        const tag = CONFIRM_TAG[state]
        return (
          <section aria-label={card.title} className={CARD}>
            <div className="flex items-center gap-2 px-3.5 pt-3">
              <h3 className={CARD_TITLE}>{card.title}</h3>
              <span className={`ml-auto ${TAG} ${tag.tone}`}>{tag.label}</span>
            </div>
            <dl className="px-3.5 py-2.5 text-[12.5px]">
              {card.details.map((row) => (
                <div key={row.label} className="flex items-baseline justify-between gap-3 py-0.5">
                  <dt className="text-slate-600">{row.label}</dt>
                  <dd className="font-mono tabular-nums text-[#1f150c]">{row.value}</dd>
                </div>
              ))}
              {card.total ? (
                <div className="mt-1.5 flex items-baseline justify-between gap-3 border-t border-dashed border-[#cdbf9f] pt-2 font-bold text-[#1f150c]">
                  <dt>Total</dt>
                  <dd className="font-mono tabular-nums">{card.total}</dd>
                </div>
              ) : null}
            </dl>
            {card.note ? <p className="bg-amber-50 px-3.5 py-2 text-[12px] leading-snug text-amber-900">{card.note}</p> : null}
            {state === 'pending' ? (
              <div className="flex flex-wrap gap-2 border-t border-[#e3d9c4] bg-[#faf7f0] px-3.5 py-2.5">
                <button type="button" className={BTN_PRIMARY_SM} disabled={busy} onClick={() => decide(card.id, true)}>
                  {card.confirmLabel}
                </button>
                <button type="button" className={BTN_GHOST_SM} disabled={busy} onClick={() => decide(card.id, false)}>
                  Cancel
                </button>
              </div>
            ) : null}
          </section>
        )
      }
      case 'table': {
        const { card } = item
        return (
          <section aria-label={card.title} className={CARD}>
            <h3 className={`${CARD_TITLE} px-3.5 pt-3`}>{card.title}</h3>
            <div className="overflow-x-auto px-3.5 pb-2.5 pt-1.5">
              <table className="w-full text-left text-[12.5px]">
                <thead>
                  <tr>
                    {card.columns.map((column) => (
                      <th key={column} scope="col" className="pb-1.5 pr-3 text-[11px] font-semibold text-slate-500 last:pr-0 last:text-right">
                        {column}
                      </th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {card.rows.map((row, rowIndex) => {
                    const picked = card.pick?.row === rowIndex
                    return (
                      <tr key={row.join('|')} className={picked ? 'font-semibold text-[#1f150c]' : 'text-slate-700'}>
                        {row.map((cell, cellIndex) => (
                          <td
                            key={card.columns[cellIndex]}
                            className="border-t border-slate-100 py-1.5 pr-3 last:pr-0 last:text-right last:font-mono last:tabular-nums"
                          >
                            {cell}
                            {picked && cellIndex === 0 ? (
                              <span className={`ml-2 ${TAG} bg-emerald-50 text-emerald-700`}>{card.pick?.label}</span>
                            ) : null}
                          </td>
                        ))}
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
          </section>
        )
      }
      case 'progress': {
        const { card } = item
        return (
          <section aria-label={card.label} className={`${CARD} px-3.5 py-3`}>
            <div className="flex items-baseline justify-between gap-2 text-[12.5px]">
              <span className={`truncate ${CARD_TITLE}`}>{card.label}</span>
              <span className="shrink-0 font-mono tabular-nums text-slate-500">
                {card.done} of {card.total}
              </span>
            </div>
            <progress
              value={card.done}
              max={card.total}
              aria-label={`${card.label} progress`}
              className="mt-2 block h-1.5 w-full appearance-none overflow-hidden rounded-full bg-slate-200 [&::-moz-progress-bar]:bg-[#412d15] [&::-webkit-progress-bar]:bg-slate-200 [&::-webkit-progress-value]:bg-[#412d15]"
            />
          </section>
        )
      }
      case 'notice': {
        const text = NOTICE[item.failure]
        return (
          <section role="alert" className="rounded-xl border border-amber-200 bg-amber-50 px-3.5 py-3 text-[12.5px] text-amber-900">
            <p className="font-bold">{text.title}</p>
            <p className="mt-0.5 leading-snug">{text.body}</p>
            <button type="button" className={`${BTN_GHOST_SM} mt-2`} disabled={busy} onClick={() => retry(item)}>
              Try again
            </button>
          </section>
        )
      }
      case 'stopped':
        return <p className="text-[11.5px] text-slate-400">Stopped</p>
    }
  }

  const column = `mx-auto w-full px-4 ${full ? 'max-w-[760px]' : ''}`

  return (
    <aside
      aria-label="Shipmate"
      hidden={!open}
      onKeyDown={(event) => {
        if (event.key !== 'Escape') return
        if (full) setFull(false)
        else onClose()
      }}
      className={`fixed inset-y-0 right-0 z-40 flex flex-col bg-white print:hidden ${
        full
          ? 'left-0'
          : 'w-full border-l border-slate-200 shadow-[-12px_0_28px_-20px_rgba(31,21,12,0.35)] sm:w-[var(--shipmate-w,380px)] sm:max-w-[calc(100vw-4rem)]'
      }`}
    >
      {onWidthChange && !full ? (
        <div
          role="separator"
          aria-orientation="vertical"
          aria-label="Resize Shipmate"
          aria-valuenow={width}
          aria-valuemin={MIN_WIDTH}
          aria-valuemax={maxWidth()}
          tabIndex={0}
          title="Drag to resize. Double-click to reset."
          onPointerDown={(event) => {
            event.preventDefault()
            event.currentTarget.setPointerCapture(event.pointerId)
          }}
          onPointerMove={(event) => {
            if (event.currentTarget.hasPointerCapture(event.pointerId)) resize(window.innerWidth - event.clientX)
          }}
          onDoubleClick={() => resize(SHIPMATE_DEFAULT_WIDTH)}
          onKeyDown={(event) => {
            if (event.key === 'ArrowLeft') resize(width + 24)
            else if (event.key === 'ArrowRight') resize(width - 24)
            else return
            event.stopPropagation()
          }}
          className="absolute inset-y-0 left-0 z-10 hidden w-2 -translate-x-1/2 cursor-col-resize touch-none transition-colors hover:bg-[#5b3fd1]/30 focus-visible:bg-[#5b3fd1]/50 focus-visible:outline-none active:bg-[#5b3fd1]/50 sm:block"
        />
      ) : null}
      <header className="flex h-14 shrink-0 items-center gap-1 border-b border-dashed border-slate-300 px-4">
        <span className="flex h-7 w-7 items-center justify-center rounded-lg bg-[#f3f0ff] text-[#5b3fd1]">
          <FiZap className="h-4 w-4" aria-hidden="true" />
        </span>
        <h2 className="ml-1.5 text-[14px] font-bold text-[#1f150c]">Shipmate</h2>
        <span className="ml-auto" />
        <IconButton
          label="New chat"
          icon={<FiEdit className="h-4 w-4" />}
          disabled={busy || items.length === 0}
          onClick={() => setItems([])}
          className={HEAD_BTN}
        />
        <IconButton
          label={full ? 'Exit full screen' : 'Full screen'}
          icon={full ? <FiMinimize2 className="h-4 w-4" /> : <FiMaximize2 className="h-4 w-4" />}
          aria-pressed={full}
          onClick={() => setFull((cur) => !cur)}
          className={HEAD_BTN}
        />
        <IconButton label="Hide Shipmate" icon={<FiX className="h-4 w-4" />} onClick={onClose} className={HEAD_BTN} />
      </header>

      <div ref={threadRef} role="log" aria-label="Conversation" className="min-h-0 flex-1 overflow-y-auto overscroll-contain">
        <div className={`${column} flex min-h-full flex-col gap-4 py-4`}>
          {items.length === 0 ? (
            <div className="my-auto py-4">
              <h3 className={`font-bold tracking-tight text-[#1f150c] ${full ? 'text-[26px]' : 'text-[19px]'}`}>
                {greeting()}
                {username ? `, ${username}` : ''}
              </h3>
              <p className="mt-1 text-[13px] leading-relaxed text-slate-500">
                You are on {page}. Ask me about shipping, customs or carriers. I cannot see your orders or rates yet.
              </p>
              <div className={`mt-4 grid gap-2 ${full ? 'sm:grid-cols-2' : ''}`}>
                {prompts.map((prompt) => (
                  <button
                    key={prompt.icon}
                    type="button"
                    onClick={() => ask(prompt.title)}
                    className="flex items-start gap-3 rounded-xl border border-[#e3d9c4] bg-white p-3 text-left transition hover:border-[#5b3fd1] hover:bg-[#fbfaff]"
                  >
                    <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-[#f4eede] text-[#412d15]">
                      {PROMPT_ICON[prompt.icon]}
                    </span>
                    <span className="min-w-0">
                      <span className="block text-[13px] font-semibold text-[#1f150c]">{prompt.title}</span>
                      <span className="mt-0.5 block text-[12px] leading-snug text-slate-500">{prompt.hint}</span>
                    </span>
                  </button>
                ))}
              </div>
            </div>
          ) : null}

          {items.map((item, index) => {
            const startsReply = item.kind !== 'user' && items[index - 1]?.kind === 'user'
            return (
              <div key={item.key} className="flex flex-col gap-1.5">
                {startsReply ? <p className="text-[11px] font-semibold text-[#5b3fd1]">Shipmate</p> : null}
                {renderItem(item, index)}
              </div>
            )
          })}

          {busy && (status !== null || !started) ? (
            <p role="status" className="flex items-center gap-2 text-[12.5px] text-slate-500">
              <span className="flex items-center gap-1" aria-hidden="true">
                {[0, 150, 300].map((delay) => (
                  <span
                    key={delay}
                    style={{ animationDelay: `${delay}ms` }}
                    className="h-1.5 w-1.5 animate-bounce rounded-full bg-[#5b3fd1] motion-reduce:animate-none"
                  />
                ))}
              </span>
              {status ?? 'Thinking'}
            </p>
          ) : null}
        </div>
      </div>

      <div className={`${column} shrink-0 ${full ? 'pb-6' : 'pb-3'}`}>
        {items.length > 0 && !busy ? (
          <div className="flex gap-1.5 overflow-x-auto pb-2">
            {prompts.map((prompt) => (
              <button key={prompt.icon} type="button" onClick={() => ask(prompt.title)} className={CHIP}>
                {prompt.title}
              </button>
            ))}
          </div>
        ) : null}
        <form
          onSubmit={(event) => {
            event.preventDefault()
            ask(input)
          }}
          className="rounded-2xl border border-slate-300 bg-white shadow-sm focus-within:border-[#5b3fd1] focus-within:ring-[3px] focus-within:ring-[#f3f0ff]"
        >
          <textarea
            ref={inputRef}
            rows={1}
            value={input}
            onChange={(event) => setInput(event.target.value)}
            onKeyDown={onInputKey}
            aria-label="Message Shipmate"
            placeholder="Ask, paste an order, or say what to do"
            className="field-sizing-content block max-h-40 min-h-[22px] w-full resize-none bg-transparent px-3.5 pt-3 text-[13.5px] leading-snug text-[#1f150c] outline-none placeholder:text-slate-400"
          />
          <div className="flex items-center gap-2 px-2.5 pb-2.5 pt-2">
            <span className="inline-flex min-w-0 items-center gap-1.5 rounded-full bg-slate-100 px-2 py-1 text-[11px] text-slate-600">
              <FiEye className="h-3 w-3 shrink-0" aria-hidden="true" />
              <span className="truncate">Sees {page}</span>
            </span>
            <span className="ml-auto" />
            {busy ? (
              <IconButton
                label="Stop"
                icon={<FiSquare className="h-3.5 w-3.5" />}
                onClick={() => abortRef.current?.abort()}
                className={SEND}
              />
            ) : (
              <IconButton
                type="submit"
                label="Send"
                icon={<FiArrowUp className="h-4 w-4" />}
                disabled={!input.trim()}
                className={SEND}
              />
            )}
          </div>
        </form>
        <p className="mt-1.5 text-center text-[10.5px] text-slate-400">Enter to send, Shift+Enter for a new line</p>
      </div>
    </aside>
  )
}
