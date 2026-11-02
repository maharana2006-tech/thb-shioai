/**
 * Self-service "Profile" — any signed-in user (ADMIN / USER / TENANT / SUPERADMIN).
 * A single column of cards (account, preferences, access, security, support, and a
 * collapsible danger zone), each with an icon badge and an inline edit affordance.
 * Username, email and role are set at signup/invite and by the admin tools, so they
 * are read-only here; the display name, photo, password and deactivation are
 * self-service. Changing the password or deactivating revokes existing sessions, so
 * on success we sign out and return to login.
 *
 * The app's global top bar + avatar menu come from WorkspaceLayout, so this page
 * intentionally does not repeat them.
 */
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  FiUser, FiLock, FiCamera, FiCheckCircle, FiAlertTriangle, FiExternalLink,
  FiTruck, FiShield, FiLifeBuoy, FiChevronDown, FiEdit2,
} from 'react-icons/fi'
import type { IconType } from 'react-icons'
import { profileService, type Profile } from '../api/profileService'
import { authService } from '../api/authService'
import { notify } from '../utils/notify'

const MIN_PASSWORD = 8
const MAX_AVATAR_PX = 256
const MAX_SOURCE_BYTES = 8 * 1024 * 1024 // reject huge source files before decoding
const SUPPORT_EMAIL = 'support@multiship.app'

const CARD = 'rounded-2xl border border-stone-200 bg-white shadow-[0_4px_6px_-1px_rgba(15,23,42,0.05),0_2px_4px_-2px_rgba(15,23,42,0.04)]'
const LABEL = 'block text-[11px] font-bold uppercase tracking-[0.06em] text-stone-500'
const VALUE = 'mt-1 text-[14px] font-medium text-stone-900'
const INPUT = 'mt-1.5 h-10 w-full rounded-lg border border-stone-200 bg-white px-3 text-[14px] text-stone-900 outline-none transition placeholder:text-stone-400 focus:border-[#D85A30] focus:ring-[3px] focus:ring-[#D85A30]/20 disabled:bg-stone-50 disabled:text-stone-500'
const PRIMARY = 'inline-flex items-center justify-center rounded-lg bg-[#D85A30] px-4 py-2 text-[14px] font-semibold text-white transition hover:bg-[#C04E29] disabled:cursor-not-allowed disabled:opacity-50'
const LINK = 'inline-flex items-center gap-1.5 text-[13px] font-semibold text-[#C04E29] transition hover:text-[#A8421F]'

/** Downscale a chosen image to a ≤256px JPEG data URL (also strips EXIF). */
function fileToAvatarDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const url = URL.createObjectURL(file)
    const img = new Image()
    img.onload = () => {
      URL.revokeObjectURL(url)
      const scale = Math.min(1, MAX_AVATAR_PX / Math.max(img.width, img.height))
      const canvas = document.createElement('canvas')
      canvas.width = Math.max(1, Math.round(img.width * scale))
      canvas.height = Math.max(1, Math.round(img.height * scale))
      const ctx = canvas.getContext('2d')
      if (!ctx) { reject(new Error('Your browser can’t process images here.')); return }
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height)
      resolve(canvas.toDataURL('image/jpeg', 0.85))
    }
    img.onerror = () => { URL.revokeObjectURL(url); reject(new Error('That file isn’t a readable image.')) }
    img.src = url
  })
}

/** "19 Aug 2024" — date only, for a "member since" line. */
function formatJoined(iso: string | null): string {
  if (!iso) return '—'
  try {
    return new Date(iso).toLocaleDateString('en-US', { day: '2-digit', month: 'short', year: 'numeric' })
  } catch { return '—' }
}

const ROLE_BLURB: Record<string, string> = {
  SUPERADMIN: 'Full platform access, including every client and all admin tools.',
  ADMIN: 'Manage carriers, clients, orders and users across the platform.',
  USER: 'Create and manage shipments and labels.',
  TENANT: 'View and manage your own organization’s orders only.',
}

export default function ProfilePage() {
  const navigate = useNavigate()
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
    <div className="min-h-full bg-[#FAF7F2]" style={{ fontFamily: "'Plus Jakarta Sans', 'Inter', system-ui, sans-serif" }}>
      <div className="pt-2 pb-10">
        <header className="mb-5">
          <h1 className="text-[22px] font-semibold tracking-tight text-stone-900">Profile</h1>
        </header>

        {loadError ? (
          <p className={`${CARD} flex items-center gap-2 p-4 text-[13px] text-rose-700`}>
            <FiAlertTriangle className="h-4 w-4" aria-hidden="true" /> {loadError}
          </p>
        ) : (
          <div className="space-y-5">
            <AccountCard profile={profile} onUpdated={setProfile} />
            <PreferencesCard profile={profile} onNavigate={navigate} />
            <div className="grid items-start gap-5 lg:grid-cols-[3fr_2fr]">
              <SecurityCard profile={profile} onSignOut={signOutToLogin} />
              <div className="grid gap-5">
                <AccessCard profile={profile} />
                <SupportCard />
              </div>
            </div>
            <AdditionalSettings profile={profile} onSignOut={signOutToLogin} />
          </div>
        )}
      </div>
    </div>
  )
}

/** The Shiprocket-style card shell: icon badge + title, optional top-right action, then body. */
function Card({ icon: Icon, title, action, children }: {
  icon: IconType; title: string; action?: ReactNode; children: ReactNode
}) {
  return (
    <section className={CARD}>
      <div className="flex items-center justify-between border-b border-stone-100 px-5 py-4">
        <h2 className="flex items-center gap-3 text-[15px] font-semibold text-stone-900">
          <span className="flex h-9 w-9 items-center justify-center rounded-full bg-[#FAECE7] text-[#C04E29]" aria-hidden="true">
            <Icon className="h-[18px] w-[18px]" />
          </span>
          {title}
        </h2>
        {action}
      </div>
      <div className="p-5">{children}</div>
    </section>
  )
}

function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div>
      <span className={LABEL}>{label}</span>
      <div className={VALUE}>{children}</div>
    </div>
  )
}

function AccountCard({ profile, onUpdated }: { profile: Profile | null; onUpdated: (p: Profile) => void }) {
  const [editing, setEditing] = useState(false)
  const [fullName, setFullName] = useState('')
  const [saving, setSaving] = useState(false)
  const [avatarBusy, setAvatarBusy] = useState(false)
  const fileRef = useRef<HTMLInputElement>(null)
  useEffect(() => { setFullName(profile?.fullName ?? '') }, [profile])

  const initials = (profile?.fullName || profile?.username || 'MS').slice(0, 2).toUpperCase()
  const dirty = profile != null && fullName.trim().length > 0 && fullName.trim() !== (profile.fullName ?? '')

  const save = async () => {
    if (!dirty || saving) return
    setSaving(true)
    try {
      onUpdated(await profileService.update(fullName.trim()))
      setEditing(false)
      void notify.success('Profile updated.')
    } catch (e) {
      void notify.apiError(e, "Couldn't update your profile.")
    } finally {
      setSaving(false)
    }
  }

  const onPickAvatar = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    e.target.value = '' // let the same file be re-picked later
    if (!file || avatarBusy) return
    if (file.size > MAX_SOURCE_BYTES) { void notify.invalid('Pick an image under 8 MB.'); return }
    setAvatarBusy(true)
    try {
      onUpdated(await profileService.updateAvatar(await fileToAvatarDataUrl(file)))
      void notify.success('Profile photo updated.')
    } catch (err) {
      void notify.apiError(err, "Couldn't update your photo.")
    } finally {
      setAvatarBusy(false)
    }
  }

  const removeAvatar = async () => {
    if (avatarBusy) return
    setAvatarBusy(true)
    try {
      onUpdated(await profileService.updateAvatar(null))
      void notify.success('Profile photo removed.')
    } catch (err) {
      void notify.apiError(err, "Couldn't remove your photo.")
    } finally {
      setAvatarBusy(false)
    }
  }

  const editAction = (
    <button type="button" className={LINK} onClick={() => { setEditing((v) => !v); setFullName(profile?.fullName ?? '') }}>
      <FiEdit2 className="h-3.5 w-3.5" aria-hidden="true" />{editing ? 'Cancel' : 'Edit details'}
    </button>
  )

  return (
    <Card icon={FiUser} title="Account details" action={editAction}>
      <div className="flex items-center gap-4">
        <div className="relative">
          {profile?.avatar ? (
            <img src={profile.avatar} alt="Your profile photo"
              className={`h-16 w-16 rounded-full object-cover ${avatarBusy ? 'opacity-50' : ''}`} />
          ) : (
            <div className={`flex h-16 w-16 items-center justify-center rounded-full bg-[#D85A30] text-[20px] font-semibold text-white ${avatarBusy ? 'opacity-50' : ''}`}>
              {initials}
            </div>
          )}
          <button type="button" aria-label="Change profile photo" disabled={avatarBusy}
            onClick={() => fileRef.current?.click()}
            className="absolute -bottom-0.5 -right-0.5 flex h-6 w-6 items-center justify-center rounded-full border border-stone-200 bg-white text-stone-600 shadow-sm transition hover:text-[#D85A30] disabled:cursor-not-allowed disabled:opacity-50">
            <FiCamera className="h-3 w-3" aria-hidden="true" />
          </button>
          <input ref={fileRef} type="file" accept="image/png,image/jpeg,image/webp,image/gif"
            className="hidden" onChange={onPickAvatar} />
        </div>
        <div>
          <p className="text-[15px] font-semibold text-stone-900">{profile?.fullName || profile?.username || '—'}</p>
          <p className="text-[13px] text-stone-500">{profile?.email ?? ''}</p>
          {profile?.avatar ? (
            <button type="button" onClick={removeAvatar} disabled={avatarBusy}
              className="mt-1 text-[12px] font-semibold text-stone-500 transition hover:text-rose-600 disabled:opacity-50">
              Remove photo
            </button>
          ) : (
            <p className="mt-1 text-[12px] text-stone-400">PNG, JPEG, WebP or GIF, up to 8&nbsp;MB.</p>
          )}
        </div>
      </div>

      <div className="mt-6 grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
        <div className="sm:col-span-2 lg:col-span-1">
          <label className={LABEL} htmlFor="pf-name">Full name</label>
          {editing ? (
            <div className="flex gap-2">
              <input id="pf-name" className={INPUT} value={fullName} maxLength={100}
                onChange={(e) => setFullName(e.target.value)} placeholder="Your name" />
              <button type="button" className={`${PRIMARY} mt-1.5 shrink-0`} onClick={() => void save()} disabled={!dirty || saving}>
                {saving ? 'Saving…' : 'Save'}
              </button>
            </div>
          ) : (
            <p className={VALUE}>{profile?.fullName || '—'}</p>
          )}
        </div>
        <Field label="Username">{profile?.username ?? '—'}</Field>
        <Field label="Email">
          <span className="inline-flex flex-wrap items-center gap-2">
            {profile?.email ?? '—'}
            {profile ? (
              <span className={`inline-flex items-center gap-1 text-[12px] font-semibold ${profile.emailVerified ? 'text-emerald-600' : 'text-amber-600'}`}>
                <FiCheckCircle className="h-3.5 w-3.5" aria-hidden="true" />
                {profile.emailVerified ? 'Verified' : 'Unverified'}
              </span>
            ) : null}
          </span>
        </Field>
        <Field label="Role">
          <span className="inline-flex items-center rounded-full bg-[#FAECE7] px-2.5 py-0.5 text-[12px] font-semibold text-[#993C1D]">
            {profile?.role ?? '—'}
          </span>
        </Field>
        <Field label="Client">{profile?.clientCode || 'All clients'}</Field>
        <Field label="Member since">{formatJoined(profile?.createdAt ?? null)}</Field>
        <Field label="Status">
          <span className="inline-flex items-center gap-1.5 rounded-full bg-emerald-50 px-2.5 py-0.5 text-[12px] font-semibold text-emerald-700 ring-1 ring-emerald-200">
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" aria-hidden="true" />Active
          </span>
        </Field>
      </div>
    </Card>
  )
}

function PreferencesCard({ profile, onNavigate }: { profile: Profile | null; onNavigate: (to: string) => void }) {
  const showNotifications = profile != null && profile.role !== 'TENANT'
  return (
    <Card icon={FiTruck} title="Shipping preferences"
      action={<button type="button" className={LINK} onClick={() => onNavigate('/settings/carriers')}>
        Manage<FiExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
      </button>}>
      <div className="grid gap-5 sm:grid-cols-2">
        <Field label="Preferred carrier">{profile?.preferredCarrier || 'Not set'}</Field>
        <div>
          <span className={LABEL}>Notifications</span>
          <div className="mt-1">
            {showNotifications ? (
              <button type="button" className={LINK} onClick={() => onNavigate('/settings/notifications')}>
                Manage preferences<FiExternalLink className="h-3.5 w-3.5" aria-hidden="true" />
              </button>
            ) : (
              <p className="text-[14px] text-stone-400">Managed by your administrator.</p>
            )}
          </div>
        </div>
      </div>
    </Card>
  )
}

function AccessCard({ profile }: { profile: Profile | null }) {
  const blurb = profile ? (ROLE_BLURB[profile.role] ?? 'Access is set by your administrator.') : ''
  const scope = profile?.clientCode
    ? `Scoped to client ${profile.clientCode}.`
    : 'Platform-wide — not limited to a single client.'
  return (
    <Card icon={FiShield} title="Role & access">
      <span className="inline-flex items-center rounded-full bg-[#FAECE7] px-2.5 py-0.5 text-[12px] font-semibold text-[#993C1D]">
        {profile?.role ?? '—'}
      </span>
      <p className="mt-2 text-[14px] text-stone-700">{blurb}</p>
      <p className="mt-1 text-[13px] text-stone-500">{scope}</p>
    </Card>
  )
}

function SecurityCard({ profile, onSignOut }: { profile: Profile | null; onSignOut: (msg: string) => Promise<void> }) {
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
    <Card icon={FiLock} title="Password">
      <div className="grid gap-6 xl:grid-cols-[minmax(0,1fr)_260px]">
        <div className="space-y-4">
          {/* BUG-185 — password managers pair the saved password with the
              first text box on the page (the top-bar search) unless the
              form carries its own username field. */}
          <input type="text" name="username" autoComplete="username" value={profile?.username ?? ''}
            readOnly tabIndex={-1} aria-hidden="true" className="hidden" />
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

        <aside className="rounded-xl border border-stone-100 bg-stone-50/70 p-4">
          <p className="text-[12px] font-semibold text-stone-700">Password requirements</p>
          <ul className="mt-2.5 space-y-2">
            <Req met={newPassword.length >= MIN_PASSWORD}>At least {MIN_PASSWORD} characters</Req>
            <Req met={newPassword.length > 0 && newPassword !== oldPassword}>Different from current password</Req>
            <Req met={confirm.length > 0 && confirm === newPassword}>Both entries match</Req>
          </ul>
          <p className="mt-3.5 border-t border-stone-200 pt-3 text-[11px] leading-relaxed text-stone-500">
            For your security, changing your password signs you out on every device.
          </p>
        </aside>
      </div>
    </Card>
  )
}

function Req({ met, children }: { met: boolean; children: ReactNode }) {
  return (
    <li className={`flex items-center gap-2 text-[13px] ${met ? 'text-stone-700' : 'text-stone-400'}`}>
      <FiCheckCircle className={`h-3.5 w-3.5 shrink-0 ${met ? 'text-emerald-500' : 'text-stone-300'}`} aria-hidden="true" />
      {children}
    </li>
  )
}

function SupportCard() {
  return (
    <Card icon={FiLifeBuoy} title="Support">
      <div className="grid gap-5 sm:grid-cols-2">
        <Field label="Support email">
          <a href={`mailto:${SUPPORT_EMAIL}`} className={LINK}>{SUPPORT_EMAIL}</a>
        </Field>
      </div>
    </Card>
  )
}

function AdditionalSettings({ profile, onSignOut }: { profile: Profile | null; onSignOut: (msg: string) => Promise<void> }) {
  const [open, setOpen] = useState(false)
  const [confirmOpen, setConfirmOpen] = useState(false)
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
    <div>
      <button type="button" onClick={() => setOpen((v) => !v)}
        className="flex items-center gap-1.5 text-[13px] font-semibold text-stone-500 transition hover:text-stone-700">
        <FiChevronDown className={`h-4 w-4 transition-transform ${open ? 'rotate-180' : ''}`} aria-hidden="true" />
        Additional settings
      </button>

      {open ? (
        <section className="mt-3 rounded-2xl border border-rose-200 bg-white p-5 shadow-[0_4px_6px_-1px_rgba(15,23,42,0.05)]">
          <div className="flex flex-wrap items-center justify-between gap-4">
            <div>
              <h3 className="flex items-center gap-2 text-[14px] font-semibold text-rose-700">
                <FiAlertTriangle className="h-4 w-4" aria-hidden="true" /> Deactivate account
              </h3>
              <p className="mt-1 text-[13px] text-stone-500">You’ll lose access and be signed out on every device.</p>
            </div>
            {!confirmOpen ? (
              <button type="button" onClick={() => setConfirmOpen(true)}
                className="shrink-0 rounded-lg border border-rose-300 px-4 py-2 text-[14px] font-semibold text-rose-700 transition hover:bg-rose-50">
                Deactivate account
              </button>
            ) : null}
          </div>
          {confirmOpen ? (
            <div className="mt-4">
              <div className="rounded-xl border border-rose-200 bg-rose-50/50 p-4">
                <p className="text-[13px] text-stone-700">
                  Type your username <span className="font-semibold text-stone-900">{profile?.username}</span> to confirm.
                </p>
                <div className="mt-2 flex flex-wrap items-center gap-2">
                  <input className={`${INPUT} mt-0 max-w-[220px]`} value={text} onChange={(e) => setText(e.target.value)}
                    placeholder={profile?.username} aria-label="Confirm username" />
                  <button type="button" onClick={() => void deactivate()} disabled={!canConfirm || busy}
                    className="rounded-lg bg-rose-600 px-4 py-2 text-[14px] font-semibold text-white transition hover:bg-rose-700 disabled:cursor-not-allowed disabled:opacity-50">
                    {busy ? 'Deactivating…' : 'Confirm deactivation'}
                  </button>
                  <button type="button" onClick={() => { setConfirmOpen(false); setText('') }}
                    className="px-2 text-[13px] font-medium text-stone-500 hover:text-stone-700">Cancel</button>
                </div>
              </div>
            </div>
          ) : null}
        </section>
      ) : null}
    </div>
  )
}
