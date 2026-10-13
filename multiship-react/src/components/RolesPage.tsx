/**
 * /settings/roles — "Roles & access", rendered as a dark access-control
 * terminal. Three role lanes and a grouped clearance matrix of which modules
 * each role can reach. Every cell is COMPUTED from the same constants that
 * drive real gating (settingsNavItems.roles, nav keys, capability helpers) and
 * grouped by the real Settings menu groups, so the chart can't drift from what
 * the app enforces. The role registry is read-only (edited via SQL today).
 */
import { useCallback, useEffect, useMemo, useState, type CSSProperties } from 'react'
import { useOutletContext } from 'react-router-dom'
import { FiAward, FiCheck, FiShield, FiUser, FiUsers } from 'react-icons/fi'
import type { IconType } from 'react-icons'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import { notify } from '../utils/notify'
import { rolesService, type RoleRow } from '../api/rolesService'
import { settingsGroups, settingsNavItems } from '../routes/workspaceRoutes'
import { canGenerateLabels, canManageCarriers, roleSatisfies, type UserRole } from '../utils/roles'

const C = {
  panel: '#111826', panel2: '#0d1320', border: '#1f2a3a',
  text: '#e6edf5', muted: '#93a1b5', dim: '#57687e',
}

const ROLE_ORDER: UserRole[] = ['SUPERADMIN', 'ADMIN', 'USER', 'TENANT']

const ROLE_META: Record<UserRole, {
  label: string; tagline: string; icon: IconType; color: string; caps: string[]
}> = {
  SUPERADMIN: { label: 'Super admin', tagline: 'Platform owner', icon: FiAward, color: '#a78bfa',
    caps: ['Everything', 'All admins'] },
  ADMIN: { label: 'Admin', tagline: 'Runs the platform', icon: FiShield, color: '#22d3ee',
    caps: ['Carriers', 'Users', 'Every setting'] },
  USER: { label: 'Shipping team', tagline: 'Operates shipments', icon: FiUsers, color: '#34d399',
    caps: ['Orders', 'Labels', 'Clients'] },
  TENANT: { label: 'Customer', tagline: 'Checks their orders', icon: FiUser, color: '#fbbf24',
    caps: ['Own orders only'] },
}

const glow = (c: string) => `0 0 10px ${c}55, 0 0 1px ${c}`

type Cell = boolean | string
type Row = { label: string; cells: Record<UserRole, Cell> }
type Group = { label: string; blurb: string; rows: Row[] }

const WORKSPACE: Row[] = [
  { label: 'Dashboard', cells: { SUPERADMIN: true, ADMIN: true, USER: true, TENANT: false } },
  { label: 'Orders', cells: { SUPERADMIN: true, ADMIN: true, USER: true, TENANT: 'Own orders' } },
  { label: 'Bulk Mailer', cells: { SUPERADMIN: true, ADMIN: true, USER: true, TENANT: false } },
  { label: 'D2C', cells: { SUPERADMIN: true, ADMIN: true, USER: true, TENANT: false } },
  { label: 'Settings', cells: { SUPERADMIN: true, ADMIN: true, USER: 'Some', TENANT: false } },
  { label: 'My profile', cells: { SUPERADMIN: true, ADMIN: true, USER: true, TENANT: true } },
]

const CAPABILITIES: Row[] = [
  { label: 'Generate shipping labels', cells: cap(canGenerateLabels) },
  { label: 'Manage carrier credentials', cells: cap(canManageCarriers) },
]

function cap(fn: (r: UserRole) => boolean): Record<UserRole, Cell> {
  return { SUPERADMIN: fn('SUPERADMIN'), ADMIN: fn('ADMIN'), USER: fn('USER'), TENANT: fn('TENANT') }
}

function toRow(item: { label: string; roles: UserRole[] }): Row {
  const can = (role: UserRole) => item.roles.some((r) => roleSatisfies(role, r))
  return {
    label: item.label,
    cells: { SUPERADMIN: can('SUPERADMIN'), ADMIN: can('ADMIN'), USER: can('USER'), TENANT: can('TENANT') },
  }
}

function settingsGroupRows(): Group[] {
  const byKey = new Map(settingsNavItems.map((i) => [i.key, i]))
  const used = new Set<string>()
  const groups: Group[] = settingsGroups
    .map((g) => ({
      label: g.label,
      blurb: g.blurb,
      rows: g.items
        .map((k) => byKey.get(k))
        .filter((i): i is (typeof settingsNavItems)[number] => !!i)
        .map((i) => { used.add(i.key); return toRow(i) }),
    }))
    .filter((g) => g.rows.length)
  const other = settingsNavItems.filter((i) => !used.has(i.key)).map(toRow)
  if (other.length) groups.push({ label: 'Other', blurb: '', rows: other })
  return groups
}

function MatrixCell({ value, color }: { value: Cell; color: string }) {
  if (value === true) {
    return (
      <span className="mx-auto flex h-6 w-6 items-center justify-center rounded-md"
        style={{ background: `${color}1a`, boxShadow: glow(color) }}>
        <FiCheck className="h-3.5 w-3.5" style={{ color }} aria-label="Full access" />
      </span>
    )
  }
  if (value === false) {
    return <span className="mx-auto block h-1.5 w-1.5 rounded-full" style={{ background: C.dim }} aria-label="No access" />
  }
  return (
    <span className="inline-block rounded-full px-2 py-0.5 text-[10.5px] font-semibold"
      style={{ color: '#fcd34d', background: '#fbbf2415', boxShadow: glow('#fbbf24'), border: '1px solid #fbbf2440' }}>
      {value}
    </span>
  )
}

function GroupBlock({ group }: { group: Group }) {
  return (
    <>
      <tr>
        <td colSpan={4} className="px-5 pb-1.5 pt-4">
          <span className="font-mono text-[11px] font-semibold uppercase tracking-[0.14em]" style={{ color: C.muted }}>
            {group.label}
          </span>
          <span className="ml-2 font-mono text-[10.5px]" style={{ color: C.dim }}>
            [{group.rows.length}]{group.blurb ? ` ${group.blurb}` : ''}
          </span>
        </td>
      </tr>
      {group.rows.map((r) => (
        <tr key={group.label + r.label} className="group">
          <td className="px-5 py-2 text-[13px]" style={{ color: C.text, borderTop: `1px solid ${C.border}55` }}>
            {r.label}
          </td>
          {ROLE_ORDER.map((code) => (
            <td key={code} className="px-3 py-2 text-center"
              style={{ borderTop: `1px solid ${C.border}55`, background: code === 'ADMIN' ? '#22d3ee08' : undefined }}>
              <MatrixCell value={r.cells[code]} color={ROLE_META[code].color} />
            </td>
          ))}
        </tr>
      ))}
    </>
  )
}

export default function RolesPage() {
  const outlet = useOutletContext<SettingsOutletContext>()
  const [registry, setRegistry] = useState<RoleRow[]>([])

  const load = useCallback(async () => {
    try { setRegistry(await rolesService.list()) }
    catch (err) { notify.apiError(err, 'Failed to load roles.') }
  }, [])

  useEffect(() => { void load() }, [load])
  useEffect(() => {
    outlet.registerRefresh(load)
    return () => outlet.registerRefresh(null)
  }, [outlet, load])

  const invitableByCode = useMemo(() => {
    const m = new Map<string, boolean>()
    registry.forEach((r) => m.set(r.code.toUpperCase(), r.isInvitable))
    return m
  }, [registry])

  const groups = useMemo(() => settingsGroupRows(), [])
  const allGroups: Group[] = [
    { label: 'Workspace', blurb: 'main navigation areas', rows: WORKSPACE },
    { label: 'Capabilities', blurb: 'what a role may do, not just see', rows: CAPABILITIES },
    ...groups,
  ]

  const moduleCount = (role: UserRole) =>
    WORKSPACE.filter((r) => r.cells[role] !== false).length
    + groups.reduce((n, g) => n + g.rows.filter((r) => r.cells[role] === true).length, 0)

  const surface: CSSProperties = {
    background: '#0b0f17',
    backgroundImage: 'radial-gradient(rgba(34,211,238,0.06) 1px, transparent 1px)',
    backgroundSize: '22px 22px',
    border: `1px solid ${C.border}`,
  }

  return (
    <div className="rounded-3xl p-4 sm:p-5" style={surface}>
      {/* Terminal header */}
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2.5">
          <span className="flex h-7 w-7 items-center justify-center rounded-lg"
            style={{ background: '#22d3ee14', boxShadow: glow('#22d3ee') }}>
            <FiShield className="h-4 w-4" style={{ color: '#22d3ee' }} />
          </span>
          <div>
            <p className="text-[14px] font-semibold" style={{ color: C.text }}>Roles &amp; access</p>
            <p className="font-mono text-[11px]" style={{ color: C.dim }}>clearance matrix · live permission rules</p>
          </div>
        </div>
        <div className="flex items-center gap-3 font-mono text-[11px]" style={{ color: C.muted }}>
          <span className="inline-flex items-center gap-1.5">
            <span className="h-2.5 w-2.5 rounded" style={{ background: '#34d39933', boxShadow: glow('#34d399') }} /> full
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span className="h-2.5 w-4 rounded-full" style={{ background: '#fbbf2433', boxShadow: glow('#fbbf24') }} /> limited
          </span>
          <span className="inline-flex items-center gap-1.5">
            <span className="h-1.5 w-1.5 rounded-full" style={{ background: C.dim }} /> none
          </span>
        </div>
      </div>

      {/* Role lanes */}
      <div className="mb-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {ROLE_ORDER.map((code) => {
          const m = ROLE_META[code]
          const Icon = m.icon
          const invitable = invitableByCode.get(code)
          return (
            <div key={code} className="relative overflow-hidden rounded-2xl p-4"
              style={{ background: C.panel, border: `1px solid ${C.border}` }}>
              <div className="absolute inset-x-0 top-0 h-[2px]" style={{ background: m.color, boxShadow: glow(m.color) }} />
              <div className="flex items-start gap-3">
                <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-xl"
                  style={{ background: `${m.color}14`, boxShadow: glow(m.color) }}>
                  <Icon className="h-5 w-5" style={{ color: m.color }} aria-hidden="true" />
                </span>
                <div className="min-w-0 flex-1">
                  <p className="text-[14px] font-semibold" style={{ color: C.text }}>{m.label}</p>
                  <p className="font-mono text-[11px] uppercase tracking-[0.12em]" style={{ color: m.color }}>{code}</p>
                </div>
                {invitable !== undefined ? (
                  <span className="shrink-0 rounded-full px-2 py-0.5 font-mono text-[10px] font-semibold"
                    style={invitable
                      ? { color: '#34d399', background: '#34d39914', border: '1px solid #34d39940' }
                      : { color: C.dim, background: '#ffffff08', border: `1px solid ${C.border}` }}>
                    {invitable ? 'INVITABLE' : 'ADMIN-SET'}
                  </span>
                ) : null}
              </div>
              <p className="mt-1.5 text-[12px]" style={{ color: C.muted }}>{m.tagline}</p>
              <div className="mt-3 flex flex-wrap gap-1.5">
                {m.caps.map((cName) => (
                  <span key={cName} className="rounded-md px-2 py-0.5 text-[11px] font-medium"
                    style={{ color: m.color, background: `${m.color}10`, border: `1px solid ${m.color}2e` }}>{cName}</span>
                ))}
              </div>
              <p className="mt-3 pt-2 font-mono text-[11px]" style={{ color: C.dim, borderTop: `1px solid ${C.border}` }}>
                <span className="text-[14px] font-semibold" style={{ color: m.color }}>{moduleCount(code)}</span> modules accessible
              </p>
            </div>
          )
        })}
      </div>

      {/* Clearance matrix */}
      <div className="overflow-hidden rounded-2xl" style={{ background: C.panel2, border: `1px solid ${C.border}` }}>
        <div className="overflow-x-auto">
          <table className="w-full border-collapse">
            <thead>
              <tr>
                <th className="w-[46%] px-5 py-3 text-left font-mono text-[11px] font-semibold uppercase tracking-[0.12em]"
                  style={{ color: C.dim, borderBottom: `1px solid ${C.border}` }}>Module</th>
                {ROLE_ORDER.map((code) => {
                  const m = ROLE_META[code]
                  const Icon = m.icon
                  return (
                    <th key={code} className="px-3 py-3 text-center"
                      style={{ borderBottom: `1px solid ${C.border}`, background: code === 'ADMIN' ? '#22d3ee08' : undefined }}>
                      <span className="inline-flex items-center gap-1.5 font-semibold" style={{ color: C.text }}>
                        <Icon className="h-3.5 w-3.5" style={{ color: m.color }} aria-hidden="true" />
                        <span className="text-[12.5px]">{m.label}</span>
                      </span>
                    </th>
                  )
                })}
              </tr>
            </thead>
            <tbody>
              {allGroups.map((g) => <GroupBlock key={g.label} group={g} />)}
            </tbody>
          </table>
        </div>
        <p className="px-5 py-3 font-mono text-[10.5px]" style={{ color: C.dim, borderTop: `1px solid ${C.border}` }}>
          “Own orders” / “Some” = limited: Tenant and client-scoped accounts see only their own client’s data. Roles edited via SQL today — read-only.
        </p>
      </div>
    </div>
  )
}
