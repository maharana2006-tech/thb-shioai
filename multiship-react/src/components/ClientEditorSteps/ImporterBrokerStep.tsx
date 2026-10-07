/**
 * F5-B2 — Importer / Broker step for the edit-mode wizard. Lists
 * every customs profile the client has, opens CustomsProfileModal
 * for Add / Edit, and removes profiles via customsProfileService.
 * The step is optional — no data required to commit — so nothing
 * here gates Create client.
 *
 * <p>Extracted from ClientEditorPage. Self-contained async lifecycle:
 * fetches on mount + on clientCode change, refreshes after every
 * add/edit/remove.
 */
import { useEffect, useState } from 'react'
import { FiPlus } from 'react-icons/fi'
import { customsProfileService, type CustomsProfile } from '../../api/customsProfileService'
import type { Client } from '../../api/clientService'
import { notify } from '../../utils/notify'
import CustomsProfileModal from '../modals/CustomsProfileModal'

export interface ImporterBrokerStepProps {
  clientCode: string
  clientName: string
}

export function ImporterBrokerStep({
  clientCode,
  clientName,
}: ImporterBrokerStepProps) {
  const [profiles, setProfiles] = useState<CustomsProfile[]>([])
  const [loading, setLoading] = useState(true)
  const [modal, setModal] = useState<{ mode: 'new' } | { mode: 'edit'; profile: CustomsProfile } | null>(null)

  const refresh = async () => {
    setLoading(true)
    try {
      const list = await customsProfileService.list(clientCode)
      setProfiles(list)
    } catch (error) {
      notify.apiError(error, 'Failed to load importer / broker profiles.')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- data fetch on client change; refresh() sets loading + profiles state
    void refresh()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- refresh is defined inline and only reads clientCode via closure; fetch-on-mount + on-clientCode-change is the intended trigger
  }, [clientCode])

  const remove = async (profile: CustomsProfile) => {
    if (profile.id == null) return
    const importerLabel = profile.importerName || profile.importerType || 'this profile'
    if (!(await notify.confirm(`Remove ${importerLabel}?`, {
      title: 'Remove importer / broker profile',
      confirmLabel: 'Remove',
      danger: true,
    }))) return
    try {
      await customsProfileService.remove(clientCode, profile.id)
      notify.success('Profile removed.')
      await refresh()
    } catch (error) {
      notify.apiError(error, 'Failed to remove the profile.')
    }
  }

  return (
    <div className="px-4 py-3 space-y-3">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h4 className="text-[12.5px] font-semibold text-slate-950">
            Importer / Broker profiles
            <span className="ml-1.5 rounded-full bg-slate-100 px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-slate-500">
              optional
            </span>
          </h4>
          <p className="text-[11px] leading-5 text-slate-500">
            Customs profiles for international shipments — importer + broker identity per
            destination region. A single profile can cover many countries.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setModal({ mode: 'new' })}
          className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-3 py-1.5 text-[12px] font-semibold text-white transition hover:bg-[#412d15]"
        >
          <FiPlus className="h-3.5 w-3.5" /> Add profile
        </button>
      </div>

      {loading ? (
        <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
          Loading…
        </p>
      ) : profiles.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-slate-300 bg-slate-50/60 px-5 py-6 text-center">
          <p className="text-[12px] font-semibold text-slate-800">No importer / broker profiles yet</p>
          <p className="mx-auto mt-1 max-w-md text-[11px] leading-4 text-slate-500">
            Add one if this client ships internationally and needs a custom importer or a specific
            broker. Purely optional — carriers use their default brokerage otherwise.
          </p>
          <button
            type="button"
            onClick={() => setModal({ mode: 'new' })}
            className="mt-3 inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-4 py-2 text-[12px] font-semibold text-white transition hover:bg-[#412d15]"
          >
            <FiPlus className="h-3.5 w-3.5" /> Add first profile
          </button>
        </div>
      ) : (
        <div className="rounded-2xl border border-slate-200 bg-white">
          <ul className="divide-y divide-slate-100">
            {profiles.map((p) => (
              <li key={p.id} className="flex items-center gap-3 px-3 py-2.5">
                <div className="min-w-0 flex-1">
                  <p className="truncate text-[12px] font-semibold text-slate-800">
                    {p.importerName || <span className="text-slate-400 italic">Unnamed importer</span>}
                    <span className="ml-2 rounded-md bg-[#412d15]/[0.07] px-1.5 py-0.5 text-[9.5px] font-bold uppercase tracking-wide text-[#412d15]">
                      {p.importerType || 'RECEIVER'}
                    </span>
                    {p.brokerName || p.brokerCompany ? (
                      <span className="ml-1.5 text-[10.5px] font-normal text-slate-500">
                        · broker {p.brokerName || p.brokerCompany}
                      </span>
                    ) : null}
                  </p>
                  <p className="mt-0.5 flex flex-wrap items-center gap-1 text-[10.5px] text-slate-500">
                    Covers:
                    {p.countries.length === 0
                      ? <span className="italic text-slate-400">no destinations</span>
                      : p.countries.slice(0, 8).map((c) => (
                          <span
                            key={c}
                            className="rounded-md bg-slate-100 px-1.5 py-0.5 font-mono text-[10px] font-bold text-slate-600"
                          >
                            {c}
                          </span>
                        ))}
                    {p.countries.length > 8 ? <span className="font-semibold text-slate-400">+{p.countries.length - 8}</span> : null}
                    {p.incoterms ? <span className="ml-1">· {p.incoterms}</span> : null}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => setModal({ mode: 'edit', profile: p })}
                  className="inline-flex h-7 items-center gap-1 rounded-lg border border-slate-200 bg-white px-2 text-[10.5px] font-semibold text-slate-600 transition hover:bg-slate-50"
                >
                  Edit
                </button>
                <button
                  type="button"
                  onClick={() => void remove(p)}
                  aria-label="Remove profile"
                  className="inline-flex h-7 w-7 items-center justify-center rounded-lg border border-transparent text-slate-400 transition hover:border-rose-100 hover:text-rose-600"
                >
                  <FiPlus className="h-3.5 w-3.5 rotate-45" />
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {modal ? (
        <CustomsProfileModal
          clients={[{ clientCode, name: clientName } as Client]}
          lockedClientCode={clientCode}
          profile={modal.mode === 'edit' ? modal.profile : undefined}
          existingProfiles={profiles}
          onClose={() => setModal(null)}
          onSaved={() => {
            setModal(null)
            void refresh()
          }}
        />
      ) : null}
    </div>
  )
}
