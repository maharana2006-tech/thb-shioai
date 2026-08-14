import { useCallback, useEffect, useMemo, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import { notify } from '../utils/notify'
import { FiActivity, FiCheck, FiClock, FiSlash, FiUsers, FiX } from 'react-icons/fi'
import {
  adminUserService,
  type AdminUser,
  type AdminUserAudit,
} from '../api/adminUserService'
import { clientService } from '../api/clientService'
import type { SettingsOutletContext } from './layout/SettingsLayout'

/**
 * Sprint 50 Tier 0.5 PR E — admin-only user management. Powers the
 * rollout workflow that must precede the `access.scope-user-by-client`
 * feature-flag flip: assign clientCode to every legacy USER, verify
 * the assignments in the audit tab, then ops flips the flag.
 */
export default function AdminUsersPage() {
  const [users, setUsers] = useState<AdminUser[]>([])
  const [audit, setAudit] = useState<AdminUserAudit[]>([])
  const [clientOptions, setClientOptions] = useState<string[]>([])
  const [loading, setLoading] = useState(true)
  const [search, setSearch] = useState('')
  const [roleFilter, setRoleFilter] = useState('')
  const [activeOnly, setActiveOnly] = useState(false)
  const [assignFor, setAssignFor] = useState<AdminUser | null>(null)
  const [auditFor, setAuditFor] = useState<AdminUser | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    try {
      const [userRes, auditRes] = await Promise.all([
        adminUserService.list({
          search: search || undefined,
          role: roleFilter || undefined,
          activeOnly: activeOnly || undefined,
        }),
        adminUserService.recentAudit(50),
      ])
      setUsers(userRes.data ?? [])
      setAudit(auditRes.data ?? [])
    } catch (e) {
      notify.apiError(e, 'Failed to load users.')
    } finally {
      setLoading(false)
    }
  }, [search, roleFilter, activeOnly])

  useEffect(() => { void load() }, [load])

  useEffect(() => {
    // Load client list once for the assign-client dropdown. Best-effort:
    // if the caller isn't allowed to list clients we just skip the picker.
    void clientService
      .listClients({ page: 0, size: 500 })
      .then((res) => {
        const codes = (res.data?.content ?? []).map((c) => c.clientCode)
        setClientOptions(codes)
      })
      .catch(() => setClientOptions([]))
  }, [])

  const { registerRefresh } = useOutletContext<SettingsOutletContext>()
  useEffect(() => {
    registerRefresh(load)
    return () => registerRefresh(null)
  }, [registerRefresh, load])

  const legacyOperatorCount = useMemo(
    () => users.filter((u) => u.role === 'USER' && !u.clientCode && !u.deactivatedAt).length,
    [users],
  )

  const toggleActive = async (user: AdminUser) => {
    try {
      if (user.deactivatedAt) {
        await adminUserService.reactivate(user.id)
        notify.success(`Reactivated ${user.username}.`)
      } else {
        const reason = window.prompt(`Deactivate ${user.username}? Reason (optional):`, '')
        if (reason === null) return
        await adminUserService.deactivate(user.id, reason.trim() || undefined)
        notify.success(`Deactivated ${user.username}.`)
      }
      void load()
    } catch (e) {
      notify.apiError(e, 'Failed to update user.')
    }
  }

  return (
    <div className="space-y-6">
      <header className="flex items-start justify-between gap-4">
        <div>
          <h2 className="flex items-center gap-2 text-[17px] font-semibold text-slate-950">
            <FiUsers className="h-4 w-4 text-slate-500" />
            Users
          </h2>
          <p className="mt-1 text-[12.5px] text-slate-500">
            Manage user accounts, assign each USER to a client, and revoke access. Sprint 50
            rollout: every legacy USER should be assigned a client before the tenant-scope
            flag is flipped.
          </p>
        </div>
      </header>

      {legacyOperatorCount > 0 && (
        <div className="rounded-xl border border-amber-300 bg-amber-50 p-3 text-[12.5px] text-amber-900">
          <b>{legacyOperatorCount}</b> USER row(s) still have no <code>clientCode</code>.
          Assign one to each before flipping <code>access.scope-user-by-client</code>.
        </div>
      )}

      <section className="flex flex-wrap items-center gap-2 rounded-xl border border-slate-200 bg-white p-3">
        <input
          type="search"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="Search username, email, name..."
          className="min-w-[220px] flex-1 rounded-md border border-slate-300 px-2 py-1.5 text-[13px] outline-none focus:border-slate-500"
        />
        <select
          value={roleFilter}
          onChange={(e) => setRoleFilter(e.target.value)}
          className="rounded-md border border-slate-300 px-2 py-1.5 text-[13px] outline-none focus:border-slate-500"
        >
          <option value="">All roles</option>
          <option value="ADMIN">ADMIN</option>
          <option value="USER">USER</option>
          <option value="TENANT">TENANT</option>
        </select>
        <label className="flex items-center gap-1.5 text-[13px] text-slate-700">
          <input
            type="checkbox"
            checked={activeOnly}
            onChange={(e) => setActiveOnly(e.target.checked)}
          />
          Active only
        </label>
      </section>

      <section className="overflow-hidden rounded-xl border border-slate-200 bg-white">
        <table className="min-w-full text-[13px]">
          <thead className="bg-slate-50 text-left text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-3 py-2">Username</th>
              <th className="px-3 py-2">Email</th>
              <th className="px-3 py-2">Role</th>
              <th className="px-3 py-2">Client</th>
              <th className="px-3 py-2">Status</th>
              <th className="px-3 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading ? (
              <tr><td colSpan={6} className="px-3 py-6 text-center text-slate-500">Loading…</td></tr>
            ) : users.length === 0 ? (
              <tr><td colSpan={6} className="px-3 py-6 text-center text-slate-500">No users.</td></tr>
            ) : users.map((u) => (
              <tr key={u.id} className={u.deactivatedAt ? 'bg-slate-50/50 text-slate-400' : ''}>
                <td className="px-3 py-2 font-mono">{u.username}</td>
                <td className="px-3 py-2">{u.email}</td>
                <td className="px-3 py-2">
                  <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11.5px] font-semibold">
                    {u.role}
                  </span>
                </td>
                <td className="px-3 py-2 font-mono">
                  {u.clientCode ?? (
                    <span className="text-amber-700">— unassigned —</span>
                  )}
                </td>
                <td className="px-3 py-2">
                  {u.deactivatedAt ? (
                    <span className="inline-flex items-center gap-1 text-red-600">
                      <FiSlash className="h-3.5 w-3.5" /> Deactivated
                    </span>
                  ) : u.emailVerified ? (
                    <span className="inline-flex items-center gap-1 text-emerald-700">
                      <FiCheck className="h-3.5 w-3.5" /> Active
                    </span>
                  ) : (
                    <span className="inline-flex items-center gap-1 text-amber-700">
                      <FiClock className="h-3.5 w-3.5" /> Unverified
                    </span>
                  )}
                </td>
                <td className="px-3 py-2 text-right">
                  <button
                    onClick={() => setAuditFor(u)}
                    title="View this user's audit trail"
                    className="mr-2 rounded-md border border-slate-300 px-2 py-1 text-[12px] hover:bg-slate-50"
                  >
                    <FiActivity className="inline h-3.5 w-3.5" /> History
                  </button>
                  <button
                    onClick={() => setAssignFor(u)}
                    disabled={u.role === 'ADMIN'}
                    title={u.role === 'ADMIN' ? 'ADMIN is always org-wide' : 'Assign clientCode'}
                    className="mr-2 rounded-md border border-slate-300 px-2 py-1 text-[12px] hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
                  >
                    Assign client
                  </button>
                  <button
                    onClick={() => toggleActive(u)}
                    className={`rounded-md px-2 py-1 text-[12px] ${
                      u.deactivatedAt
                        ? 'border border-emerald-300 text-emerald-700 hover:bg-emerald-50'
                        : 'border border-red-300 text-red-700 hover:bg-red-50'
                    }`}
                  >
                    {u.deactivatedAt ? 'Reactivate' : 'Deactivate'}
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      <section className="rounded-xl border border-slate-200 bg-white">
        <header className="border-b border-slate-100 px-3 py-2 text-[12.5px] font-semibold text-slate-700">
          Recent admin actions
        </header>
        <ul className="max-h-64 divide-y divide-slate-100 overflow-y-auto text-[12.5px]">
          {audit.length === 0 ? (
            <li className="px-3 py-6 text-center text-slate-500">No audit entries yet.</li>
          ) : audit.map((a) => (
            <li key={a.id} className="px-3 py-2">
              <span className="mr-2 font-mono text-slate-500">{formatTs(a.createdAt)}</span>
              <b>{a.actorUsername}</b> {actionLabel(a)}
              <span className="ml-1 font-mono">{a.subjectUsername}</span>
              {a.reason && (
                <span className="ml-2 rounded bg-slate-100 px-1.5 py-0.5 text-[11.5px] text-slate-600">
                  {a.reason}
                </span>
              )}
            </li>
          ))}
        </ul>
      </section>

      {assignFor && (
        <AssignClientDialog
          user={assignFor}
          clientOptions={clientOptions}
          onClose={() => setAssignFor(null)}
          onSaved={() => { setAssignFor(null); void load() }}
        />
      )}

      {auditFor && (
        <UserAuditDrawer
          user={auditFor}
          onClose={() => setAuditFor(null)}
        />
      )}
    </div>
  )
}

function UserAuditDrawer({
  user,
  onClose,
}: {
  user: AdminUser
  onClose: () => void
}) {
  const [entries, setEntries] = useState<AdminUserAudit[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    adminUserService
      .userAudit(user.id)
      .then((res) => { if (!cancelled) setEntries(res.data ?? []) })
      .catch((e) => { if (!cancelled) setError(e instanceof Error ? e.message : 'Failed to load audit.') })
    return () => { cancelled = true }
  }, [user.id])

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose() }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
      <div className="w-full max-w-2xl rounded-xl border border-slate-200 bg-white p-4 shadow-xl">
        <header className="mb-3 flex items-center justify-between">
          <h3 className="text-[15px] font-semibold text-slate-900">
            <FiActivity className="mr-1 inline h-4 w-4 text-slate-500" />
            Audit trail for <span className="font-mono">{user.username}</span>
          </h3>
          <button onClick={onClose} className="rounded p-1 text-slate-500 hover:bg-slate-100">
            <FiX className="h-4 w-4" />
          </button>
        </header>

        <div className="max-h-[60vh] overflow-y-auto rounded-md border border-slate-100">
          {error ? (
            <p className="p-4 text-[13px] text-red-600">{error}</p>
          ) : entries === null ? (
            <p className="p-4 text-center text-[13px] text-slate-500">Loading…</p>
          ) : entries.length === 0 ? (
            <p className="p-4 text-center text-[13px] text-slate-500">No admin actions recorded for this user.</p>
          ) : (
            <ul className="divide-y divide-slate-100 text-[12.5px]">
              {entries.map((a) => (
                <li key={a.id} className="px-3 py-2">
                  <span className="mr-2 font-mono text-slate-500">{formatTs(a.createdAt)}</span>
                  <b>{a.actorUsername}</b> {actionLabel(a)}
                  <span className="ml-1 font-mono">{a.subjectUsername}</span>
                  {a.reason && (
                    <span className="ml-2 rounded bg-slate-100 px-1.5 py-0.5 text-[11.5px] text-slate-600">
                      {a.reason}
                    </span>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="mt-4 flex justify-end">
          <button
            onClick={onClose}
            className="rounded-md border border-slate-300 px-3 py-1.5 text-[13px] hover:bg-slate-50"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  )
}

function actionLabel(a: AdminUserAudit): string {
  switch (a.action) {
    case 'ASSIGN_CLIENT':
      return `moved ${a.oldClientCode ?? '(none)'} → ${a.newClientCode ?? '(none)'} on`
    case 'DEACTIVATE':
      return 'deactivated'
    case 'REACTIVATE':
      return 'reactivated'
    default:
      return a.action.toLowerCase()
  }
}

function formatTs(iso: string): string {
  try {
    return new Date(iso).toLocaleString()
  } catch {
    return iso
  }
}

function AssignClientDialog({
  user,
  clientOptions,
  onClose,
  onSaved,
}: {
  user: AdminUser
  clientOptions: string[]
  onClose: () => void
  onSaved: () => void
}) {
  const [clientCode, setClientCode] = useState(user.clientCode ?? '')
  const [reason, setReason] = useState('')
  const [saving, setSaving] = useState(false)

  const save = async () => {
    setSaving(true)
    try {
      await adminUserService.assignClient(user.id, {
        clientCode: clientCode.trim() || null,
        reason: reason.trim() || null,
      })
      notify.success(`Updated ${user.username}.`)
      onSaved()
    } catch (e) {
      notify.apiError(e, 'Failed to update client assignment.')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4">
      <div className="w-full max-w-md rounded-xl border border-slate-200 bg-white p-4 shadow-xl">
        <header className="mb-3 flex items-center justify-between">
          <h3 className="text-[15px] font-semibold text-slate-900">
            Assign client for <span className="font-mono">{user.username}</span>
          </h3>
          <button onClick={onClose} className="rounded p-1 text-slate-500 hover:bg-slate-100">
            <FiX className="h-4 w-4" />
          </button>
        </header>

        <label className="mb-1 block text-[12.5px] font-medium text-slate-700">
          Client code (leave blank to clear)
        </label>
        <input
          list="admin-users-client-options"
          value={clientCode}
          onChange={(e) => setClientCode(e.target.value)}
          placeholder="e.g. ACME"
          className="mb-3 w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px] outline-none focus:border-slate-500"
        />
        <datalist id="admin-users-client-options">
          {clientOptions.map((c) => <option key={c} value={c} />)}
        </datalist>

        <label className="mb-1 block text-[12.5px] font-medium text-slate-700">
          Reason (optional — recorded in audit)
        </label>
        <textarea
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          rows={2}
          className="mb-4 w-full rounded-md border border-slate-300 px-2 py-1.5 text-[13px] outline-none focus:border-slate-500"
        />

        <div className="flex justify-end gap-2">
          <button
            onClick={onClose}
            disabled={saving}
            className="rounded-md border border-slate-300 px-3 py-1.5 text-[13px] hover:bg-slate-50"
          >
            Cancel
          </button>
          <button
            onClick={save}
            disabled={saving}
            className="rounded-md bg-slate-900 px-3 py-1.5 text-[13px] text-white hover:bg-slate-800 disabled:opacity-60"
          >
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  )
}
