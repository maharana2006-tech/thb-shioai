/**
 * Self-service "Profile settings" — any signed-in user (ADMIN / USER / TENANT).
 * Two-column layout: a left section nav (General / Security) and the matching
 * cards on the right. Username, email and role are set at signup/invite and by
 * the admin tools, so they are read-only here; the display name, password, and
 * account deactivation are self-service. Changing the password or deactivating
 * revokes existing sessions, so on success we sign out and return to login.
 *
 * The app's global top bar + avatar menu come from WorkspaceLayout, so this
 * page intentionally does not repeat them.
 */
import { useEffect, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  FiUser, FiLock, FiBell, FiCamera, FiCheckCircle, FiAlertTriangle, FiExternalLink,
} from 'react-icons/fi'
import type { IconType } from 'react-icons'
import { profileService, type Profile } from '../api/profileService'
import { authService } from '../api/authService'
import { notify } from '../utils/notify'

const MIN_PASSWORD = 8

const CARD = 'rounded-2xl border border-slate-200 bg-white shadow-[0_4px_6px_-1px_rgba(15,23,42,0.05),0_2px_4px_-2px_rgba(15,23,42,0.04)]'
const LABEL = 'block text-[11px] font-bold uppercase tracking-[0.06em] text-slate-500'
const INPUT = 'mt-1.5 h-10 w-full rounded-lg border border-slate-200 bg-white px-3 text-[14px] text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-[#D85A30] focus:ring-[3px] focus:ring-[#D85A30]/20 disabled:bg-slate-50 disabled:text-slate-500'
const PRIMARY = 'inline-flex items-center justify-center rounded-lg bg-[#D85A30] px-4 py-2 text-[14px] font-semibold text-white transition hover:bg-[#C04E29] disabled:cursor-not-allowed disabled:opacity-50'

type Tab = 'general' | 'security'
const TABS: { key: Tab; label: string; icon: IconType }[] = [
  { key: 'general', label: 'General', icon: FiUser },
  { key: 'security', label: 'Security', icon: FiLock },
]

export default function ProfilePage() {
  const navigate = useNavigate()
  const [tab, setTab] = useState<Tab>('general')
  const [profile, setProfile] = useState<Profile | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  useEffect(() => {
    profileService.get()
      .then((p) => { setProfile(p); setLoadError(null) })
      .catch((e) => setLoadError(e instanceof Error ? e.message : 'Could not load your profile.'))
  }, [])

  const signOutToLogin = async (message: string) => {
    await authService.logout().catch(() => undefined)
    void notify.success(message)
    navigate('/login', { replace: true })
  }

  return (
    <div className="min-h-full bg-[#F8FAFC]" style={{ fontFamily: "'Plus Jakarta Sans', 'Inter', system-ui, sans-serif" }}>
      <div className="mx-auto max-w-5xl px-4 py-8">
        <header className="mb-7">
          <h1 className="text-[22px] font-semibold tracking-tight text-slate-900">Profile settings</h1>
          <p className="mt-0.5 text-[14px] text-slate-500">Manage your account details, security, and preferences.</p>
        </header>

        {loadError ? (
          <p className={`${CARD} flex items-center gap-2 p-4 text-[13px] text-rose-700`}>
            <FiAlertTriangle className="h-4 w-4" aria-hidden="true" /> {loadError}
          </p>
        ) : (
          <div className="grid gap-6 md:grid-cols-[216px_1fr]">
            {/* Section nav */}
            <nav className="flex flex-row gap-1 md:sticky md:top-6 md:flex-col md:self-start" aria-label="Profile sections">
              {TABS.map(({ key, label, icon: Icon }) => {
                const active = tab === key
                return (
                  <button key={key} type="button" onClick={() => setTab(key)}
                    className={`flex items-center gap-2.5 rounded-lg px-3 py-2 text-[14px] transition ${active
                      ? 'bg-[#FAECE7] font-semibold text-[#993C1D]'
                      : 'font-medium text-slate-600 hover:bg-slate-100 hover:text-slate-900'}`}>
                    <Icon className="h-4 w-4" aria-hidden="true" />{label}
                  </button>
                )
              })}
              {profile && profile.role !== 'TENANT' ? (
                <button type="button" onClick={() => navigate('/settings/notifications')}
                  className="flex items-center gap-2.5 rounded-lg px-3 py-2 text-[14px] font-medium text-slate-600 transition hover:bg-slate-100 hover:text-slate-900">
                  <FiBell className="h-4 w-4" aria-hidden="true" />Notifications
                  <FiExternalLink className="ml-auto h-3.5 w-3.5 text-slate-400" aria-hidden="true" />
                </button>
              ) : null}
            </nav>

            {/* Section content */}
            <div className="space-y-6">
              {tab === 'general'
                ? <GeneralSection profile={profile} onUpdated={setProfile} />
                : <SecuritySection profile={profile} onSignOut={signOutToLogin} />}
            </div>
          </div>
        )}
      </div>
    </div>
  )
}

function SectionHead({ title, hint }: { title: string; hint: string }) {
  return (
    <div className="border-b border-slate-100 px-5 py-4">
      <h2 className="text-[15px] font-semibold text-slate-900">{title}</h2>
      <p className="mt-0.5 text-[13px] text-slate-500">{hint}</p>
    </div>
  )
}

function GeneralSection({ profile, onUpdated }: { profile: Profile | null; onUpdated: (p: Profile) => void }) {
  const [fullName, setFullName] = useState('')
  const [saving, setSaving] = useState(false)
  useEffect(() => { setFullName(profile?.fullName ?? '') }, [profile])

  const dirty = profile != null && fullName.trim().length > 0 && fullName.trim() !== (profile.fullName ?? '')
  const initials = (profile?.fullName || profile?.username || 'MS').slice(0, 2).toUpperCase()

  const save = async () => {
    if (!dirty || saving) return
    setSaving(true)
    try {
      const updated = await profileService.update(fullName.trim())
      onUpdated(updated)
      void notify.success('Profile updated.')
    } catch (e) {
      void notify.apiError(e, "Couldn't update your profile.")
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <section className={CARD}>
        <SectionHead title="Personal information" hint="Your name and how we reach you." />
        <div className="p-5">
          <div className="flex items-center gap-4">
            <div className="relative">
              <div className="flex h-16 w-16 items-center justify-center rounded-full bg-[#D85A30] text-[20px] font-semibold text-white">
                {initials}
              </div>
              <button type="button" aria-label="Change avatar"
                onClick={() => void notify.info('Avatar upload isn’t available yet.')}
                className="absolute -bottom-0.5 -right-0.5 flex h-6 w-6 items-center justify-center rounded-full border border-slate-200 bg-white text-slate-600 shadow-sm transition hover:text-[#D85A30]">
                <FiCamera className="h-3 w-3" aria-hidden="true" />
              </button>
            </div>
            <div>
              <p className="text-[15px] font-semibold text-slate-900">{profile?.fullName || profile?.username || '—'}</p>
              <p className="text-[13px] text-slate-500">{profile?.email ?? ''}</p>
            </div>
          </div>

          <div className="mt-6 grid gap-5 sm:grid-cols-2">
            <div className="sm:col-span-2">
              <label className={LABEL} htmlFor="pf-name">Display name</label>
              <div className="flex gap-2">
                <input id="pf-name" className={INPUT} value={fullName} maxLength={100}
                  onChange={(e) => setFullName(e.target.value)} placeholder="Your name" />
                <button type="button" className={`${PRIMARY} mt-1.5 shrink-0`} onClick={() => void save()} disabled={!dirty || saving}>
                  {saving ? 'Saving…' : 'Save'}
                </button>
              </div>
            </div>
            <div>
              <label className={LABEL} htmlFor="pf-username">Username</label>
              <input id="pf-username" className={INPUT} value={profile?.username ?? ''} disabled readOnly />
            </div>
            <div>
              <label className={LABEL} htmlFor="pf-email">Email</label>
              <div className="relative">
                <input id="pf-email" className={INPUT} value={profile?.email ?? ''} disabled readOnly />
                {profile ? (
                  <span className={`absolute right-3 top-1/2 inline-flex -translate-y-1/2 items-center gap-1 text-[12px] font-medium ${profile.emailVerified ? 'text-emerald-600' : 'text-amber-600'}`}>
                    <FiCheckCircle className="h-3.5 w-3.5" aria-hidden="true" />
                    {profile.emailVerified ? 'Verified' : 'Unverified'}
                  </span>
                ) : null}
              </div>
            </div>
          </div>
        </div>
      </section>

      <section className={CARD}>
        <SectionHead title="Account details" hint="Set by your administrator." />
        <div className="grid gap-5 p-5 sm:grid-cols-2">
          <div>
            <span className={LABEL}>Role</span>
            <div className="mt-1.5">
              <span className="inline-flex items-center rounded-full bg-[#FAECE7] px-2.5 py-1 text-[12px] font-semibold text-[#993C1D]">
                {profile?.role ?? '—'}
              </span>
            </div>
          </div>
          <div>
            <span className={LABEL}>Client</span>
            <p className="mt-2 text-[14px] text-slate-700">{profile?.clientCode || 'All clients'}</p>
          </div>
        </div>
      </section>
    </>
  )
}

function SecuritySection({ profile, onSignOut }: { profile: Profile | null; onSignOut: (msg: string) => Promise<void> }) {
  const [oldPassword, setOld] = useState('')
  const [newPassword, setNew] = useState('')
  const [confirm, setConfirm] = useState('')
  const [busy, setBusy] = useState(false)

  const problem = !oldPassword ? 'Enter your current password.'
    : newPassword.length < MIN_PASSWORD ? `New password must be at least ${MIN_PASSWORD} characters.`
      : confirm !== newPassword ? 'The new passwords don’t match.'
        : oldPassword === newPassword ? 'The new password must be different.'
          : null

  const change = async () => {
    if (problem || busy) return
    setBusy(true)
    try {
      await profileService.changePassword(oldPassword, newPassword)
      await onSignOut('Password changed. Please sign in again.')
    } catch (e) {
      void notify.apiError(e, "Couldn't change your password.")
      setBusy(false)
    }
  }

  const forgot = async () => {
    if (!profile?.email) return
    try {
      await profileService.sendResetEmail(profile.email)
      void notify.success('If that email is registered, a reset link is on its way.')
    } catch (e) {
      void notify.apiError(e, "Couldn't send a reset link.")
    }
  }

  return (
    <>
      <section className={CARD}>
        <SectionHead title="Change password" hint="You’ll be signed out of all sessions after changing it." />
        <div className="grid gap-6 p-5 lg:grid-cols-[minmax(0,1fr)_300px]">
          <div className="space-y-4">
            <div>
              <label className={LABEL} htmlFor="pw-old">Current password</label>
              <input id="pw-old" type="password" autoComplete="current-password" className={INPUT}
                value={oldPassword} onChange={(e) => setOld(e.target.value)} />
            </div>
            <div>
              <label className={LABEL} htmlFor="pw-new">New password</label>
              <input id="pw-new" type="password" autoComplete="new-password" className={INPUT}
                value={newPassword} onChange={(e) => setNew(e.target.value)} />
            </div>
            <div>
              <label className={LABEL} htmlFor="pw-confirm">Confirm new password</label>
              <input id="pw-confirm" type="password" autoComplete="new-password" className={INPUT}
                value={confirm} onChange={(e) => setConfirm(e.target.value)} />
            </div>
            <p className="min-h-[18px] text-[13px] text-rose-600" aria-live="polite">
              {(oldPassword || newPassword || confirm) ? problem : null}
            </p>
            <div className="flex items-center gap-4">
              <button type="button" className={PRIMARY} onClick={() => void change()} disabled={!!problem || busy}>
                {busy ? 'Updating…' : 'Update password'}
              </button>
              <button type="button" onClick={() => void forgot()}
                className="text-[13px] font-medium text-[#C04E29] hover:text-[#A8421F] hover:underline">
                Forgot your password?
              </button>
            </div>
          </div>

          <aside className="rounded-xl border border-slate-100 bg-slate-50/70 p-4">
            <p className="text-[12px] font-semibold text-slate-700">Password requirements</p>
            <ul className="mt-2.5 space-y-2">
              <Req met={newPassword.length >= MIN_PASSWORD}>At least {MIN_PASSWORD} characters</Req>
              <Req met={newPassword.length > 0 && newPassword !== oldPassword}>Different from current password</Req>
              <Req met={confirm.length > 0 && confirm === newPassword}>Both entries match</Req>
            </ul>
            <p className="mt-3.5 border-t border-slate-200 pt-3 text-[11px] leading-relaxed text-slate-500">
              For your security, changing your password signs you out on every device.
            </p>
          </aside>
        </div>
      </section>

      <DangerZone profile={profile} onSignOut={onSignOut} />
    </>
  )
}

function Req({ met, children }: { met: boolean; children: ReactNode }) {
  return (
    <li className={`flex items-center gap-2 text-[13px] ${met ? 'text-slate-700' : 'text-slate-400'}`}>
      <FiCheckCircle className={`h-3.5 w-3.5 shrink-0 ${met ? 'text-emerald-500' : 'text-slate-300'}`} aria-hidden="true" />
      {children}
    </li>
  )
}

function DangerZone({ profile, onSignOut }: { profile: Profile | null; onSignOut: (msg: string) => Promise<void> }) {
  const [open, setOpen] = useState(false)
  const [text, setText] = useState('')
  const [busy, setBusy] = useState(false)
  const canConfirm = profile != null && text.trim().toLowerCase() === profile.username.toLowerCase()

  const deactivate = async () => {
    if (!canConfirm || busy) return
    setBusy(true)
    try {
      await profileService.deactivate()
      await onSignOut('Your account has been deactivated.')
    } catch (e) {
      void notify.apiError(e, "Couldn't deactivate your account.")
      setBusy(false)
    }
  }

  return (
    <section className="rounded-2xl border border-rose-200 bg-white shadow-[0_4px_6px_-1px_rgba(15,23,42,0.05)]">
      <div className="border-b border-rose-100 px-5 py-4">
        <h2 className="flex items-center gap-2 text-[15px] font-semibold text-rose-700">
          <FiAlertTriangle className="h-4 w-4" aria-hidden="true" /> Danger zone
        </h2>
        <p className="mt-0.5 text-[13px] text-slate-500">Deactivate your account. You’ll lose access and be signed out.</p>
      </div>
      <div className="p-5">
        {!open ? (
          <button type="button" onClick={() => setOpen(true)}
            className="rounded-lg border border-rose-300 px-4 py-2 text-[14px] font-semibold text-rose-700 transition hover:bg-rose-50">
            Deactivate account
          </button>
        ) : (
          <div className="rounded-xl border border-rose-200 bg-rose-50/50 p-4">
            <p className="text-[13px] text-slate-700">
              Type your username <span className="font-semibold text-slate-900">{profile?.username}</span> to confirm.
            </p>
            <div className="mt-2 flex flex-wrap items-center gap-2">
              <input className={`${INPUT} mt-0 max-w-[220px]`} value={text} onChange={(e) => setText(e.target.value)}
                placeholder={profile?.username} aria-label="Confirm username" />
              <button type="button" onClick={() => void deactivate()} disabled={!canConfirm || busy}
                className="rounded-lg bg-rose-600 px-4 py-2 text-[14px] font-semibold text-white transition hover:bg-rose-700 disabled:cursor-not-allowed disabled:opacity-50">
                {busy ? 'Deactivating…' : 'Confirm deactivation'}
              </button>
              <button type="button" onClick={() => { setOpen(false); setText('') }}
                className="px-2 text-[13px] font-medium text-slate-500 hover:text-slate-700">Cancel</button>
            </div>
          </div>
        )}
      </div>
    </section>
  )
}
