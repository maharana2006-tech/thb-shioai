import { useEffect, useState } from 'react'
import { FiFileText } from 'react-icons/fi'
import { orderService } from '../../api/orderService'
import { notify } from '../../utils/notify'

/** The per-order ops note (500 chars): icon + tooltip, popover to edit. */
export default function NoteCell({ orderNo, note }: { orderNo: number; note: string }) {
  const [open, setOpen] = useState(false)
  const [text, setText] = useState(note)
  const [saving, setSaving] = useState(false)
  const [local, setLocal] = useState(note)

  useEffect(() => {
    /* eslint-disable react-hooks/set-state-in-effect -- reset local +
       text when the row's note prop changes (parent re-fetched after
       another edit); can't be derived at render because operator's
       in-progress text must survive within an edit session. */
    setLocal(note)
    setText(note)
    /* eslint-enable react-hooks/set-state-in-effect */
  }, [note])

  const hasNote = !!local.trim()
  const preview = local.length > 90 ? `${local.slice(0, 90).trim()}…` : local

  const save = async () => {
    const next = text.trim()
    if (next === local) { setOpen(false); return }
    if (next.length > 500) return
    setSaving(true)
    try {
      await orderService.updateNote(orderNo, next || null)
      setLocal(next)
      setOpen(false)
    } catch (e) {
      notify.apiError(e, 'Could not save note.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="relative flex items-center justify-center">
      <button
        type="button"
        onClick={(e) => { e.stopPropagation(); setOpen((v) => !v) }}
        aria-label={hasNote ? `Note: ${preview}` : 'Add note'}
        title={hasNote ? preview : 'Add note'}
        className={`inline-flex h-7 w-7 items-center justify-center rounded-lg border transition ${
          hasNote
            ? 'border-[#412d15] bg-[#412d15]/10 text-[#412d15] hover:bg-[#412d15]/20'
            : 'border-transparent text-[#b3a583] hover:border-[#e3d9c4] hover:text-[#5a4526]'
        }`}
      >
        <FiFileText className="h-3.5 w-3.5" />
      </button>
      {open ? (
        <div
          className="absolute right-0 top-8 z-30 w-72 rounded-xl border border-[#e3d9c4] bg-white p-3 shadow-lg"
          onClick={(e) => e.stopPropagation()}
        >
          <div className="mb-1.5 flex items-center justify-between text-[10px] font-bold uppercase tracking-[0.14em] text-[#6b5c42]">
            <span>Order #{orderNo} note</span>
            <span className={text.length > 450 ? 'text-amber-600' : 'text-[#b6a684]'}>
              {text.length}/500
            </span>
          </div>
          <textarea
            rows={4}
            maxLength={500}
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder="Driver instructions, pickup hints, handling flags…"
            className="w-full resize-y rounded-lg border border-[#e3d9c4] bg-white px-2 py-1.5 text-[12.5px] text-[#1f150c] outline-none focus:border-[#cdbf9f] focus:ring-2 focus:ring-[#f4eede]"
          />
          <div className="mt-2 flex items-center justify-end gap-1.5">
            <button
              type="button"
              onClick={() => { setText(local); setOpen(false) }}
              className="rounded-lg border border-[#e3d9c4] bg-white px-2.5 py-1 text-[11.5px] font-semibold text-[#5a4526] hover:bg-[#faf7f0]"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => void save()}
              disabled={saving || text.trim() === local}
              className="rounded-lg bg-[#412d15] px-2.5 py-1 text-[11.5px] font-semibold text-white transition hover:bg-[#1f150c] disabled:cursor-not-allowed disabled:opacity-40"
            >
              {saving ? 'Saving…' : 'Save'}
            </button>
          </div>
        </div>
      ) : null}
    </div>
  )
}
