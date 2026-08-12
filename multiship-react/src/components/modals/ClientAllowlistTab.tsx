import { useEffect, useState, type ReactNode } from 'react'
import { FiPlus, FiStar, FiTrash2 } from 'react-icons/fi'
import { notify } from '../../utils/notify'
import Select from '../workspace/Select'

/**
 * Generic client-allowlist tab. Same shape works for both services and
 * packages — the caller supplies:
 *   - fetchers for the allowlist rows and the master catalog
 *   - mutators (allow / setDefault / remove) keyed by the catalog id
 *   - adapters that pull the key + default flag off an allowlist row
 *   - render slots for the row body (each catalog has its own summary
 *     shape, so we don't try to unify the display).
 *
 * Kept in-file where it lives so the ClientEditorModal file stays tight;
 * this component only knows about display + orchestration, never about
 * ShippingService or PackagePreset specifically.
 */
export interface ClientAllowlistTabProps<TAllowed, TCatalog> {
  clientCode: string
  /** DOM id used for aria-controls on the tab button. */
  panelId: string

  /** e.g. "Allowed services". */
  headline: string
  /** One-line explainer under the headline. */
  description: string
  /** Empty-list message. */
  emptyLabel: string
  /** Label for the "Add" button. */
  addLabel: string

  fetchAllowed: (clientCode: string) => Promise<TAllowed[]>
  fetchCatalog: () => Promise<TCatalog[]>

  /** POST to allow one row. */
  allow: (clientCode: string, catalogKey: number, makeDefault: boolean) => Promise<void>
  /** DELETE. */
  remove: (clientCode: string, catalogKey: number) => Promise<void>
  /** PUT .../default. */
  setDefault: (clientCode: string, catalogKey: number) => Promise<void>

  /** Row → the numeric catalog id (serviceId or presetId). */
  allowedKey: (item: TAllowed) => number
  /** Row → whether this is the current default. */
  allowedIsDefault: (item: TAllowed) => boolean
  /** Row → display body (2-line usually: primary + secondary). */
  renderAllowed: (item: TAllowed) => ReactNode
  /**
   * Optional slot: extra controls to render on each row between the row body
   * and the Make-default / Remove buttons. Services use this for the
   * Destinations drawer trigger.
   */
  renderRowExtras?: (item: TAllowed) => ReactNode

  /** Catalog entry → numeric id (used in the select value and to key rows). */
  catalogKey: (item: TCatalog) => number
  /** Catalog entry → human label for the picker option. */
  catalogLabel: (item: TCatalog) => string
  /** Optional predicate: catalog entry is selectable at all (e.g. `enabled`). */
  catalogEligible?: (item: TCatalog) => boolean
}

export default function ClientAllowlistTab<TAllowed, TCatalog>({
  clientCode,
  panelId,
  headline,
  description,
  emptyLabel,
  addLabel,
  fetchAllowed,
  fetchCatalog,
  allow,
  remove,
  setDefault,
  allowedKey,
  allowedIsDefault,
  renderAllowed,
  renderRowExtras,
  catalogKey,
  catalogLabel,
  catalogEligible,
}: ClientAllowlistTabProps<TAllowed, TCatalog>) {
  const [allowed, setAllowed] = useState<TAllowed[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)

  const [pickerOpen, setPickerOpen] = useState(false)
  const [pickerChoices, setPickerChoices] = useState<TCatalog[]>([])
  const [pickerChoice, setPickerChoice] = useState('')
  const [pickerMakeDefault, setPickerMakeDefault] = useState(false)

  const refresh = async () => {
    setLoading(true)
    try {
      setAllowed(await fetchAllowed(clientCode))
    } catch (error) {
      notify.apiError(error, `Failed to load ${headline.toLowerCase()}.`)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    void refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [clientCode])

  const openPicker = async () => {
    setPickerOpen(true)
    setPickerChoice('')
    // First-attach auto-defaults on the backend regardless of the flag; we
    // still preselect the box so the UI matches what will happen.
    setPickerMakeDefault(allowed.length === 0)
    try {
      const catalog = await fetchCatalog()
      const allowedKeys = new Set(allowed.map(allowedKey))
      const filtered = catalog
        .filter((c) => !allowedKeys.has(catalogKey(c)))
        .filter((c) => (catalogEligible ? catalogEligible(c) : true))
      setPickerChoices(filtered)
    } catch (error) {
      notify.apiError(error, 'Failed to load the catalog.')
      setPickerChoices([])
    }
  }

  const submitPicker = async () => {
    if (!pickerChoice || busy) return
    setBusy(true)
    try {
      await allow(clientCode, Number(pickerChoice), pickerMakeDefault)
      notify.success(`${headline.replace(/s$/, '')} added.`)
      setPickerOpen(false)
      await refresh()
    } catch (error) {
      // ALLOWLIST_ALREADY_EXISTS is handled by the friendly-message map.
      notify.apiError(error, 'Failed to add.')
    } finally {
      setBusy(false)
    }
  }

  const handleSetDefault = async (key: number) => {
    if (busy) return
    setBusy(true)
    try {
      await setDefault(clientCode, key)
      await refresh()
    } catch (error) {
      notify.apiError(error, 'Failed to set the default.')
    } finally {
      setBusy(false)
    }
  }

  const handleRemove = async (key: number, humanKey: string) => {
    if (!(await notify.confirm(`Remove ${humanKey} from ${clientCode}?`, {
      title: 'Remove from allowlist',
      confirmLabel: 'Remove',
      danger: true,
    }))) return
    setBusy(true)
    try {
      await remove(clientCode, key)
      notify.success(`${humanKey} removed.`)
      await refresh()
    } catch (error) {
      notify.apiError(error, 'Failed to remove.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div
      id={panelId}
      role="tabpanel"
      className="flex-1 overflow-y-auto px-5 py-4"
    >
      <div className="flex items-center justify-between">
        <div>
          <h4 className="text-[12.5px] font-semibold text-slate-950">{headline}</h4>
          <p className="text-[11px] leading-5 text-slate-500">{description}</p>
        </div>
        {!pickerOpen ? (
          <button
            type="button"
            onClick={() => void openPicker()}
            className="inline-flex items-center gap-1 rounded-xl border border-slate-200 bg-white px-2.5 py-1.5 text-[11px] font-semibold text-slate-700 transition hover:bg-slate-50"
          >
            <FiPlus className="h-3 w-3" />
            {addLabel}
          </button>
        ) : null}
      </div>

      {pickerOpen ? (
        <div className="mt-3 rounded-2xl border border-slate-200 bg-slate-50/60 p-3">
          {pickerChoices.length === 0 ? (
            <p className="text-[11.5px] text-slate-500">
              Nothing available to add — every eligible catalog entry is already on the list.
            </p>
          ) : (
            <>
              <Select
                value={pickerChoice}
                onChange={(e) => setPickerChoice(e.target.value)}
                aria-label={addLabel}
              >
                <option value="">Select…</option>
                {pickerChoices.map((c) => (
                  <option key={catalogKey(c)} value={String(catalogKey(c))}>
                    {catalogLabel(c)}
                  </option>
                ))}
              </Select>
              <label className="mt-2 flex items-center gap-2 text-[11.5px] font-semibold text-slate-700">
                <input
                  type="checkbox"
                  checked={pickerMakeDefault}
                  onChange={(e) => setPickerMakeDefault(e.target.checked)}
                  className="h-4 w-4 rounded border-slate-300 text-slate-950 focus:ring-slate-300"
                />
                Make default for this client
              </label>
            </>
          )}
          <div className="mt-3 flex items-center justify-end gap-2">
            <button
              type="button"
              onClick={() => setPickerOpen(false)}
              className="rounded-xl border border-slate-200 bg-white px-3 py-1.5 text-[11.5px] font-semibold text-slate-600 transition hover:bg-slate-100"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={() => void submitPicker()}
              disabled={!pickerChoice || busy}
              className="rounded-xl bg-[#1f150c] px-4 py-1.5 text-[11.5px] font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:opacity-50"
            >
              Add
            </button>
          </div>
        </div>
      ) : null}

      <div className="mt-3 space-y-1.5">
        {loading ? (
          <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
            Loading…
          </p>
        ) : allowed.length === 0 ? (
          <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
            {emptyLabel}
          </p>
        ) : (
          allowed.map((row) => {
            const key = allowedKey(row)
            const isDefault = allowedIsDefault(row)
            // Human-readable key for the confirm dialog. Falls back to the numeric
            // id if the row shape doesn't expose anything richer.
            const humanKey = String(key)
            return (
              <div
                key={key}
                className="flex items-center gap-2.5 rounded-xl border border-slate-200 bg-white px-3 py-2"
              >
                <div className="min-w-0 flex-1">{renderAllowed(row)}</div>
                {renderRowExtras ? renderRowExtras(row) : null}
                {isDefault ? (
                  <span className="inline-flex items-center gap-1 rounded-full bg-[#412d15]/10 px-2 py-0.5 text-[10.5px] font-semibold text-[#412d15]">
                    <FiStar className="h-3 w-3" />
                    Default
                  </span>
                ) : (
                  <button
                    type="button"
                    onClick={() => void handleSetDefault(key)}
                    disabled={busy}
                    className="rounded-lg border border-slate-200 bg-white px-2 py-1 text-[10.5px] font-semibold text-slate-600 transition hover:bg-slate-50 disabled:opacity-50"
                  >
                    Make default
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => void handleRemove(key, humanKey)}
                  disabled={busy}
                  aria-label={`Remove ${humanKey}`}
                  className="inline-flex h-7 w-7 shrink-0 items-center justify-center rounded-lg border border-transparent text-slate-400 transition hover:border-rose-100 hover:text-rose-600 disabled:opacity-50"
                >
                  <FiTrash2 className="h-3.5 w-3.5" />
                </button>
              </div>
            )
          })
        )}
      </div>
    </div>
  )
}
