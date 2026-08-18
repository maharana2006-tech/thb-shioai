import { useCallback, useEffect, useMemo, useState } from 'react'
import { FiCheck, FiEdit2, FiPlus, FiRadio, FiSlash, FiTrash2, FiUpload, FiX } from 'react-icons/fi'
import { notify } from '../utils/notify'
import {
  outputDestinationService,
  type DestinationType,
  type DispatchResult,
  type DocType,
  type OutputDestination,
  type OutputDestinationUpsertPayload,
} from '../api/outputDestinationService'
import IconButton from './ui/IconButton'

/**
 * Sprint 52 — admin page for the per-client output routing table.
 * Powers /settings/output-destinations. ADMIN-only (backend
 * @PreAuthorize + route-level RequireRole).
 *
 * Feature completeness:
 *  - list / filter by client
 *  - create + edit with per-destination-type form (LOCAL_FS path,
 *    SFTP host/user/authType + write-only password/key, PRINTER
 *    host/port/protocol/queue)
 *  - active toggle, notes
 *  - "Test" button that pings the /{id}/test endpoint
 *  - delete with confirmation
 *
 * SFTP secrets are write-only from this UI — GET responses NEVER echo
 * them back (the DTO returns "***set***" markers instead), so an admin
 * changing the host of an SFTP destination keeps the existing password.
 * A new password is only sent when the user types one into the modal.
 */
export default function OutputDestinationsPage() {
  const [rows, setRows] = useState<OutputDestination[]>([])
  const [loading, setLoading] = useState(true)
  const [clientFilter, setClientFilter] = useState('')
  /** Audit O3 — 300ms debounce between typing and firing the /list call so
   *  the admin filter box doesn't send a request per keystroke. */
  const [debouncedFilter, setDebouncedFilter] = useState('')
  const [editing, setEditing] = useState<OutputDestination | null>(null)
  const [creating, setCreating] = useState(false)

  useEffect(() => {
    const t = window.setTimeout(() => setDebouncedFilter(clientFilter.trim()), 300)
    return () => window.clearTimeout(t)
  }, [clientFilter])

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const res = await outputDestinationService.list(debouncedFilter || undefined)
      setRows(res.data ?? [])
    } catch (e) {
      notify.apiError(e, 'Failed to load output destinations.')
    } finally {
      setLoading(false)
    }
  }, [debouncedFilter])

  useEffect(() => {
    // Data fetch on mount / filter change. load() sets loading + result state.
    // eslint-disable-next-line react-hooks/set-state-in-effect -- required initial fetch; there's no external subscription source we can pipe from
    void load()
  }, [load])

  const grouped = useMemo(() => {
    const map: Record<string, OutputDestination[]> = {}
    for (const r of rows) {
      if (!map[r.clientCode]) map[r.clientCode] = []
      map[r.clientCode].push(r)
    }
    return map
  }, [rows])

  const onDelete = async (row: OutputDestination) => {
    if (!window.confirm(`Delete ${row.destinationType} destination for ${row.clientCode}?`)) return
    try {
      await outputDestinationService.delete(row.id)
      notify.success(`Destination ${row.id} deleted.`)
      void load()
    } catch (e) {
      notify.apiError(e, 'Failed to delete destination.')
    }
  }

  const onTest = async (row: OutputDestination) => {
    // Audit O6 — the backend "test" endpoint doesn't dry-run for PRINTER;
    // it physically prints a real page on the target device (ZPL / IPP PDF).
    // Confirm first so an admin exploring the ledger doesn't wake up a
    // Zebra at 2am.
    if (row.destinationType === 'PRINTER') {
      const ok = window.confirm(
        `Send a real test page to the printer at ${summariseConfig(row.destinationType, row.configSafe)}?\n\nThis will physically print a page on the device.`,
      )
      if (!ok) return
    }
    try {
      const res = await outputDestinationService.test(row.id)
      const result = res.data
      if (result && result.successCount === result.totalDestinations) {
        notify.success(`Test dispatch OK to destination ${row.id}.`)
      } else {
        const detail = describeFailure(result)
        notify.apiError(new Error(detail), 'Test dispatch failed.')
      }
    } catch (e) {
      notify.apiError(e, 'Test dispatch failed.')
    }
  }

  return (
    <div className="space-y-6">
      <header className="flex items-start justify-between gap-4">
        <div>
          <h2 className="flex items-center gap-2 text-[17px] font-semibold text-slate-950">
            <FiUpload className="h-4 w-4 text-slate-500" />
            Output Destinations
          </h2>
          <p className="mt-1 text-[12.5px] text-slate-500">
            Route generated labels + commercial invoices per client. Every dispatch is
            copied to the database first, then delivered to any active destination
            (LOCAL_FS, SFTP, PRINTER).
          </p>
        </div>
        <button
          onClick={() => setCreating(true)}
          className="rounded-md bg-slate-900 px-3 py-1.5 text-[13px] font-semibold text-white hover:bg-slate-700"
        >
          <FiPlus className="mr-1 inline h-3.5 w-3.5" />
          Add destination
        </button>
      </header>

      <section className="flex flex-wrap items-center gap-2 rounded-xl border border-slate-200 bg-white p-3">
        <input
          type="search"
          value={clientFilter}
          onChange={(e) => setClientFilter(e.target.value)}
          placeholder="Filter by client code..."
          className="min-w-[240px] flex-1 rounded-md border border-slate-300 px-2 py-1.5 text-[13px] outline-none focus:border-slate-500"
        />
      </section>

      <section className="overflow-hidden rounded-xl border border-slate-200 bg-white">
        <table className="min-w-full text-[13px]">
          <thead className="bg-slate-50 text-left text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-3 py-2">Client</th>
              <th className="px-3 py-2">Doc Type</th>
              <th className="px-3 py-2">Destination</th>
              <th className="px-3 py-2">Config Summary</th>
              <th className="px-3 py-2">Active</th>
              <th className="px-3 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading ? (
              <tr>
                <td colSpan={6} className="px-3 py-6 text-center text-slate-500">
                  Loading...
                </td>
              </tr>
            ) : rows.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-3 py-6 text-center text-slate-500">
                  No destinations configured.
                </td>
              </tr>
            ) : (
              Object.keys(grouped).sort().flatMap((client) =>
                grouped[client].map((row) => (
                  <tr key={row.id}>
                    <td className="px-3 py-2 font-mono">{row.clientCode}</td>
                    <td className="px-3 py-2">
                      <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11.5px] font-semibold">
                        {row.docType}
                      </span>
                    </td>
                    <td className="px-3 py-2">
                      <span className="rounded-full bg-indigo-50 px-2 py-0.5 text-[11.5px] font-semibold text-indigo-800">
                        {row.destinationType}
                      </span>
                    </td>
                    <td className="px-3 py-2 font-mono text-[11.5px] text-slate-600">
                      {summariseConfig(row.destinationType, row.configSafe)}
                    </td>
                    <td className="px-3 py-2">
                      {row.active ? (
                        <span className="inline-flex items-center gap-1 text-emerald-700">
                          <FiCheck className="h-3.5 w-3.5" /> Active
                        </span>
                      ) : (
                        <span className="inline-flex items-center gap-1 text-slate-500">
                          <FiSlash className="h-3.5 w-3.5" /> Inactive
                        </span>
                      )}
                    </td>
                    <td className="px-3 py-2 text-right">
                      <button
                        onClick={() => onTest(row)}
                        title="Send a small test payload through this destination"
                        className="mr-2 rounded-md border border-slate-300 px-2 py-1 text-[12px] hover:bg-slate-50"
                      >
                        <FiRadio className="mr-1 inline h-3.5 w-3.5" /> Test
                      </button>
                      <button
                        onClick={() => setEditing(row)}
                        title="Edit"
                        className="mr-2 rounded-md border border-slate-300 px-2 py-1 text-[12px] hover:bg-slate-50"
                      >
                        <FiEdit2 className="mr-1 inline h-3.5 w-3.5" /> Edit
                      </button>
                      <button
                        onClick={() => onDelete(row)}
                        title="Delete"
                        className="rounded-md border border-red-300 px-2 py-1 text-[12px] text-red-700 hover:bg-red-50"
                      >
                        <FiTrash2 className="mr-1 inline h-3.5 w-3.5" /> Delete
                      </button>
                    </td>
                  </tr>
                )),
              )
            )}
          </tbody>
        </table>
      </section>

      {(creating || editing) && (
        <DestinationEditorDialog
          existing={editing}
          onClose={() => {
            setCreating(false)
            setEditing(null)
          }}
          onSaved={() => {
            setCreating(false)
            setEditing(null)
            void load()
          }}
        />
      )}
    </div>
  )
}

/**
 * Create / edit modal. Renders a different config form per
 * destinationType. Submits an OutputDestinationUpsertPayload — SFTP
 * password / key material is only included when the user actually
 * typed something (so an update-without-secret leaves the existing
 * pointer intact).
 */
function DestinationEditorDialog({
  existing,
  onClose,
  onSaved,
}: {
  existing: OutputDestination | null
  onClose: () => void
  onSaved: () => void
}) {
  const [clientCode, setClientCode] = useState(existing?.clientCode ?? '')
  const [docType, setDocType] = useState<DocType>(existing?.docType ?? 'LABEL')
  const [destinationType, setDestinationType] = useState<DestinationType>(
    existing?.destinationType ?? 'LOCAL_FS',
  )
  const [active, setActive] = useState<boolean>(existing?.active ?? true)
  const [notes, setNotes] = useState(existing?.notes ?? '')

  // Parse the existing config JSON so the form starts pre-filled.
  const existingConfig = useMemo(
    () => (existing ? parseConfigSafely(existing.configSafe) : {}),
    [existing],
  )

  // Type-specific fields.
  const [localFsPath, setLocalFsPath] = useState<string>((existingConfig.path as string) ?? '')
  const [sftpHost, setSftpHost] = useState<string>((existingConfig.host as string) ?? '')
  const [sftpPort, setSftpPort] = useState<string>(String(existingConfig.port ?? ''))
  const [sftpUsername, setSftpUsername] = useState<string>((existingConfig.username as string) ?? '')
  const [sftpAuthType, setSftpAuthType] = useState<string>(
    (existingConfig.authType as string) ?? 'PASSWORD',
  )
  const [sftpPassword, setSftpPassword] = useState<string>('')
  const [sftpPrivateKey, setSftpPrivateKey] = useState<string>('')
  const [sftpRemoteDir, setSftpRemoteDir] = useState<string>(
    (existingConfig.remoteDir as string) ?? '/upload',
  )
  /** Audit O2 — surface the sftpKnownHostsPlain field the backend has
   *  supported since Sprint 52 output-polish follow-up #2. Write-only. */
  const [sftpKnownHosts, setSftpKnownHosts] = useState<string>('')
  /** Whether a knownHosts pointer is already stored (masked to "***set***"
   *  by the DTO). Drives the placeholder hint on the edit form. */
  const hasKnownHosts = Boolean(existingConfig.knownHostsSecretId)
  /** Audit R2 #348 — same pattern for password + private key. Pre-fix, the
   *  edit form had no signal that a secret was already stored, so operators
   *  editing (e.g., just to toggle Active) could think the destination had
   *  no credentials and try to Test before entering one. The DTO masks these
   *  pointers as the literal string "***set***" — non-empty presence tells
   *  us a secret exists without leaking anything. */
  const hasPassword = Boolean(existingConfig.passwordSecretId)
  const hasPrivateKey = Boolean(existingConfig.privateKeySecretId)
  const [printerHost, setPrinterHost] = useState<string>((existingConfig.host as string) ?? '')
  const [printerPort, setPrinterPort] = useState<string>(String(existingConfig.port ?? ''))
  const [printerProtocol, setPrinterProtocol] = useState<string>(
    (existingConfig.protocol as string) ?? 'RAW_9100',
  )
  const [printerQueue, setPrinterQueue] = useState<string>(
    (existingConfig.queueName as string) ?? '',
  )

  const [saving, setSaving] = useState(false)

  const buildPayload = (): OutputDestinationUpsertPayload => {
    const extras: Partial<OutputDestinationUpsertPayload> = {}
    let config: Record<string, unknown>
    if (destinationType === 'LOCAL_FS') {
      config = { path: localFsPath.trim() }
    } else if (destinationType === 'SFTP') {
      config = {
        host: sftpHost.trim(),
        port: sftpPort ? Number(sftpPort) : undefined,
        username: sftpUsername.trim(),
        authType: sftpAuthType,
        remoteDir: sftpRemoteDir.trim(),
      }
      if (sftpPassword) extras.sftpPasswordPlain = sftpPassword
      if (sftpPrivateKey) extras.sftpPrivateKeyPlain = sftpPrivateKey
      // Audit O2 — pin the known_hosts file when the operator paste it
      // in; leaving blank on edit preserves the existing pointer.
      if (sftpKnownHosts) extras.sftpKnownHostsPlain = sftpKnownHosts
    } else {
      config = {
        host: printerHost.trim(),
        port: printerPort ? Number(printerPort) : undefined,
        protocol: printerProtocol,
        queueName: printerQueue.trim() || undefined,
      }
    }
    return {
      clientCode: clientCode.trim(),
      docType,
      destinationType,
      config: JSON.stringify(config),
      active,
      notes: notes.trim() || null,
      ...extras,
    }
  }

  const onSubmit = async () => {
    if (!clientCode.trim()) {
      notify.apiError(new Error('Client code is required.'), 'Missing client code.')
      return
    }
    setSaving(true)
    try {
      const payload = buildPayload()
      if (existing) {
        await outputDestinationService.update(existing.id, payload)
        notify.success(`Destination ${existing.id} updated.`)
      } else {
        await outputDestinationService.create(payload)
        notify.success('Destination created.')
      }
      onSaved()
    } catch (e) {
      notify.apiError(e, 'Failed to save destination.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
      <div className="w-full max-w-2xl rounded-xl border border-slate-200 bg-white p-4 shadow-xl">
        <header className="mb-3 flex items-center justify-between">
          <h3 className="text-[15px] font-semibold text-slate-900">
            {existing ? `Edit destination ${existing.id}` : 'New output destination'}
          </h3>
          <IconButton
            onClick={onClose}
            label="Close"
            icon={<FiX className="h-4 w-4" />}
            className="rounded p-1 text-slate-500 hover:bg-slate-100"
          />
        </header>

        <div className="space-y-3">
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
            <label className="block text-[12.5px] font-semibold text-slate-700">
              Client code
              <input
                type="text"
                value={clientCode}
                onChange={(e) => setClientCode(e.target.value)}
                placeholder="ACME"
                className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-1.5 font-mono text-[13px]"
              />
            </label>
            <label className="block text-[12.5px] font-semibold text-slate-700">
              Doc type
              <select
                value={docType}
                onChange={(e) => setDocType(e.target.value as DocType)}
                className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px]"
              >
                <option value="LABEL">LABEL</option>
                <option value="COMMERCIAL_INVOICE">COMMERCIAL_INVOICE</option>
              </select>
            </label>
            <label className="block text-[12.5px] font-semibold text-slate-700">
              Destination type
              <select
                value={destinationType}
                onChange={(e) => setDestinationType(e.target.value as DestinationType)}
                className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px]"
              >
                <option value="LOCAL_FS">LOCAL_FS</option>
                <option value="SFTP">SFTP</option>
                <option value="PRINTER">PRINTER</option>
              </select>
            </label>
            <label className="flex items-center gap-2 pt-6 text-[12.5px] font-semibold text-slate-700">
              <input
                type="checkbox"
                checked={active}
                onChange={(e) => setActive(e.target.checked)}
              />
              Active
            </label>
          </div>

          {destinationType === 'LOCAL_FS' && (
            <label className="block text-[12.5px] font-semibold text-slate-700">
              Directory path
              <input
                type="text"
                value={localFsPath}
                onChange={(e) => setLocalFsPath(e.target.value)}
                placeholder="/var/labels/acme"
                className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-1.5 font-mono text-[13px]"
              />
            </label>
          )}

          {destinationType === 'SFTP' && (
            <div className="space-y-3 rounded-md border border-slate-200 bg-slate-50 p-3">
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <label className="block text-[12.5px] font-semibold text-slate-700">
                  Host
                  <input
                    type="text"
                    value={sftpHost}
                    onChange={(e) => setSftpHost(e.target.value)}
                    placeholder="sftp.example.com"
                    className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-1.5 font-mono text-[13px]"
                  />
                </label>
                <label className="block text-[12.5px] font-semibold text-slate-700">
                  Port
                  <input
                    type="number"
                    value={sftpPort}
                    onChange={(e) => setSftpPort(e.target.value)}
                    placeholder="22"
                    className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px]"
                  />
                </label>
                <label className="block text-[12.5px] font-semibold text-slate-700">
                  Username
                  <input
                    type="text"
                    value={sftpUsername}
                    onChange={(e) => setSftpUsername(e.target.value)}
                    className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-1.5 font-mono text-[13px]"
                  />
                </label>
                <label className="block text-[12.5px] font-semibold text-slate-700">
                  Auth type
                  <select
                    value={sftpAuthType}
                    onChange={(e) => setSftpAuthType(e.target.value)}
                    className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px]"
                  >
                    <option value="PASSWORD">PASSWORD</option>
                    <option value="KEY">KEY</option>
                  </select>
                </label>
              </div>
              {sftpAuthType === 'PASSWORD' ? (
                <label className="block text-[12.5px] font-semibold text-slate-700">
                  Password
                  {/* Audit R2 #348 — hasPassword flag surfaces the stored-
                      credential state so operators editing (e.g., just to
                      toggle Active) know they don't have to re-enter. */}
                  {hasPassword ? (
                    <span className="ml-2 rounded bg-emerald-50 px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide text-emerald-700 ring-1 ring-emerald-100">
                      stored
                    </span>
                  ) : null}
                  <input
                    type="password"
                    value={sftpPassword}
                    onChange={(e) => setSftpPassword(e.target.value)}
                    placeholder={hasPassword
                      ? '(a password is stored — leave blank to keep, or paste new to replace)'
                      : (existing ? '(leave blank to keep existing)' : '')}
                    autoComplete="new-password"
                    className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px]"
                  />
                  <span className="mt-1 block text-[11.5px] font-normal text-slate-500">
                    Encrypted at rest. Never echoed back after save.
                  </span>
                </label>
              ) : (
                <label className="block text-[12.5px] font-semibold text-slate-700">
                  Private key (PEM)
                  {/* Audit R2 #348 — mirror the hasPassword indicator for KEY auth. */}
                  {hasPrivateKey ? (
                    <span className="ml-2 rounded bg-emerald-50 px-1.5 py-0.5 text-[10px] font-bold uppercase tracking-wide text-emerald-700 ring-1 ring-emerald-100">
                      stored
                    </span>
                  ) : null}
                  <textarea
                    value={sftpPrivateKey}
                    onChange={(e) => setSftpPrivateKey(e.target.value)}
                    placeholder={hasPrivateKey
                      ? '(a private key is stored — leave blank to keep, or paste new to replace)'
                      : (existing ? '(leave blank to keep existing)' : '')}
                    rows={4}
                    className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-1.5 font-mono text-[11.5px]"
                  />
                </label>
              )}
              <label className="block text-[12.5px] font-semibold text-slate-700">
                Remote directory
                <input
                  type="text"
                  value={sftpRemoteDir}
                  onChange={(e) => setSftpRemoteDir(e.target.value)}
                  className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-1.5 font-mono text-[13px]"
                />
              </label>
              {/* Audit O2 — known_hosts pinning. Backend enables strict
                  host-key checking when this is set. Highly recommended
                  for prod SFTP destinations (defeats MITM). Write-only. */}
              <label className="block text-[12.5px] font-semibold text-slate-700">
                Known hosts (recommended)
                <textarea
                  value={sftpKnownHosts}
                  onChange={(e) => setSftpKnownHosts(e.target.value)}
                  placeholder={hasKnownHosts
                    ? '(pinned — leave blank to keep, or paste new to replace)'
                    : 'Paste the SSH host-key line from `ssh-keyscan sftp.example.com` (or leave blank for TOFU — accept-any-host on first connect).'}
                  rows={3}
                  className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-1.5 font-mono text-[11.5px]"
                />
                <span className="mt-1 block text-[11.5px] font-normal text-slate-500">
                  {hasKnownHosts
                    ? 'A host-key is already pinned for this destination.'
                    : 'Without a pinned key the driver accepts any host on first connect (MITM-vulnerable).'}
                </span>
              </label>
            </div>
          )}

          {destinationType === 'PRINTER' && (
            <div className="space-y-3 rounded-md border border-slate-200 bg-slate-50 p-3">
              <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                <label className="block text-[12.5px] font-semibold text-slate-700">
                  Host
                  <input
                    type="text"
                    value={printerHost}
                    onChange={(e) => setPrinterHost(e.target.value)}
                    placeholder="192.168.1.50"
                    className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-1.5 font-mono text-[13px]"
                  />
                </label>
                <label className="block text-[12.5px] font-semibold text-slate-700">
                  Port
                  <input
                    type="number"
                    value={printerPort}
                    onChange={(e) => setPrinterPort(e.target.value)}
                    placeholder={printerProtocol === 'IPP' ? '631' : '9100'}
                    className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px]"
                  />
                </label>
                <label className="block text-[12.5px] font-semibold text-slate-700">
                  Protocol
                  <select
                    value={printerProtocol}
                    onChange={(e) => setPrinterProtocol(e.target.value)}
                    className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px]"
                  >
                    <option value="RAW_9100">RAW_9100 (Zebra ZPL)</option>
                    <option value="IPP">IPP (laser PDF)</option>
                  </select>
                </label>
                <label className="block text-[12.5px] font-semibold text-slate-700">
                  Queue name (IPP)
                  <input
                    type="text"
                    value={printerQueue}
                    onChange={(e) => setPrinterQueue(e.target.value)}
                    placeholder="Zebra_Front_Desk"
                    disabled={printerProtocol !== 'IPP'}
                    className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-1.5 font-mono text-[13px] disabled:bg-slate-100"
                  />
                </label>
              </div>
            </div>
          )}

          <label className="block text-[12.5px] font-semibold text-slate-700">
            Notes (optional)
            <input
              type="text"
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              className="mt-1 block w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px]"
            />
          </label>
        </div>

        <div className="mt-4 flex justify-end gap-2">
          <button
            onClick={onClose}
            disabled={saving}
            className="rounded-md border border-slate-300 px-3 py-1.5 text-[13px] hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            onClick={onSubmit}
            disabled={saving}
            className="rounded-md bg-slate-900 px-3 py-1.5 text-[13px] font-semibold text-white hover:bg-slate-700 disabled:opacity-50"
          >
            {saving ? 'Saving...' : existing ? 'Save changes' : 'Create'}
          </button>
        </div>
      </div>
    </div>
  )
}

function parseConfigSafely(raw: string | null | undefined): Record<string, unknown> {
  if (!raw) return {}
  try {
    return JSON.parse(raw) as Record<string, unknown>
  } catch {
    return {}
  }
}

function summariseConfig(type: DestinationType, raw: string | null | undefined): string {
  const cfg = parseConfigSafely(raw)
  if (type === 'LOCAL_FS') return String(cfg.path ?? '(no path)')
  if (type === 'SFTP')
    return `${cfg.username ?? '?'}@${cfg.host ?? '?'}:${cfg.port ?? 22}${cfg.remoteDir ?? '/'}`
  if (type === 'PRINTER')
    return `${cfg.protocol ?? '?'} ${cfg.host ?? '?'}:${cfg.port ?? (cfg.protocol === 'IPP' ? 631 : 9100)}${
      cfg.queueName ? ` [${cfg.queueName}]` : ''
    }`
  return '(unknown)'
}

function describeFailure(result: DispatchResult | null | undefined): string {
  if (!result || !result.items || result.items.length === 0) return 'unknown failure'
  const first = result.items.find((i) => !i.success) ?? result.items[0]
  return first.failureMessage ?? 'unknown failure'
}
