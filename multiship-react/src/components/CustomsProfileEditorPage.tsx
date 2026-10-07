import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import CustomsProfileModal from './modals/CustomsProfileModal'
import { clientService, type Client } from '../api/clientService'
import { customsProfileService, type CustomsProfile } from '../api/customsProfileService'
import { isAbortError } from '../api/apiClient'
import { settingsPaths } from '../routes/workspaceRoutes'
import { notify } from '../utils/notify'

/**
 * Full-page editor for one importer/broker (customs) profile — the page behind
 * "Add profile" and row "Edit" on the Importer/Broker settings page. Reuses the
 * CustomsProfileModal form in asPage mode (no overlay). Routes:
 *   /settings/importer-broker/new             → create
 *   /settings/importer-broker/:clientCode/:id → edit
 * Edit normally gets its profile via router state from the list; a deep link /
 * refresh falls back to fetching the client's profiles and finding it.
 */
export default function CustomsProfileEditorPage() {
  const navigate = useNavigate()
  const { clientCode, id } = useParams()
  const location = useLocation()
  const editing = !!id

  const [clients, setClients] = useState<Client[]>([])
  const [profile, setProfile] = useState<CustomsProfile | null>(
    () => (location.state as { profile?: CustomsProfile } | null)?.profile ?? null,
  )
  const [loading, setLoading] = useState(editing && !profile)

  // Clients feed the client picker (new) / display (edit).
  useEffect(() => {
    let cancelled = false
    clientService
      .listClients({ size: 500, sortBy: 'code' })
      .then((r) => { if (!cancelled) setClients(r.data?.content ?? []) })
      .catch((e) => { if (!isAbortError(e)) console.debug('[load] listClients', e) })
    return () => { cancelled = true }
  }, [])

  // Edit via deep link / refresh (no router state): fetch the client's profiles
  // and find the one we're editing.
  useEffect(() => {
    if (!editing || profile || !clientCode) return
    let cancelled = false
    customsProfileService
      .list(clientCode)
      .then((list) => {
        if (cancelled) return
        const found = list.find((p) => String(p.id) === String(id)) ?? null
        if (!found) notify.error('That importer/broker profile was not found.')
        setProfile(found)
        setLoading(false)
      })
      .catch((e) => {
        if (cancelled || isAbortError(e)) return
        notify.apiError(e, 'Could not load the profile.')
        setLoading(false)
      })
    return () => { cancelled = true }
  }, [editing, profile, clientCode, id])

  const back = () => navigate(settingsPaths.importerBroker)

  return (
    <div className="pb-8">
      {loading ? (
        <p className="px-1 py-6 text-[13px] text-[#6b5c42]">Loading…</p>
      ) : (
        <CustomsProfileModal
          asPage
          clients={clients}
          profile={profile}
          onClose={back}
          onSaved={back}
        />
      )}
    </div>
  )
}
