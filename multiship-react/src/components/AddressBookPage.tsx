import { useCallback, useEffect, useRef, useState } from 'react'
import { FiBookmark, FiEdit2, FiPlus, FiSearch, FiTrash2, FiX } from 'react-icons/fi'
import { notify } from '../utils/notify'
import { useAppSession } from '../hooks/useAppSession'
import { normalizeRole } from '../utils/roles'
import { COUNTRIES } from '../utils/countries'
import { clientService, type Client } from '../api/clientService'
import {
  isDuplicateSave,
  recipientBookService,
  type RecipientPage,
  type SavedRecipient,
} from '../api/recipientBookService'

const PAGE_SIZE = 25

/**
 * Settings → Address book. The addresses the Ship to search offers on a new
 * shipment: add them, fix a typo or an old phone number, or remove one.
 * Each entry belongs to one client, or is shared by every client.
 */
export default function AddressBookPage() {
  const { role } = useAppSession()
  const isAdmin = normalizeRole(role) === 'ADMIN'
  const [clients, setClients] = useState<Client[]>([])
  const [clientFilter, setClientFilter] = useState('')        // '' = every client (admin) / own (user)
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [data, setData] = useState<RecipientPage | null>(null)
  const [loading, setLoading] = useState(true)
  const [editing, setEditing] = useState<SavedRecipient | 'new' | null>(null)
  const debounce = useRef<ReturnType<typeof setTimeout> | null>(null)
  const [debouncedQuery, setDebouncedQuery] = useState('')

  useEffect(() => {
    clientService.listClients({ size: 100, sortBy: 'code' })
      .then((res) => setClients(res.data?.content ?? []))
      .catch(() => setClients([]))
  }, [])

  useEffect(() => {
    if (debounce.current) clearTimeout(debounce.current)
    debounce.current = setTimeout(() => { setDebouncedQuery(query); setPage(0) }, 250)
    return () => { if (debounce.current) clearTimeout(debounce.current) }
  }, [query])

  const load = useCallback(() => {
    return recipientBookService.list({ q: debouncedQuery, customerNo: clientFilter || null, page, size: PAGE_SIZE })
      .then((res) => setData(res.data ?? null))
      .catch((e) => notify.apiError(e, 'Could not load the address book.'))
      .finally(() => setLoading(false))
  }, [debouncedQuery, clientFilter, page])

  useEffect(() => { void load() }, [load])

  const remove = async (r: SavedRecipient) => {
    if (!r.id) return
    const ok = await notify.confirm(
      `Remove ${r.name} (${r.addressLine1}, ${r.city}) from the address book? `
        + 'Shipments already made are not affected.',
      { title: 'Remove address', confirmLabel: 'Remove', cancelLabel: 'Keep', danger: true },
    )
    if (!ok) return
    try {
      await recipientBookService.remove(r.id)
      notify.success(`Removed ${r.name}.`)
      await load()
    } catch (e) {
      notify.apiError(e, 'Could not remove the address.')
    }
  }

  const rows = data?.content ?? []
  const total = data?.totalElements ?? 0
  const from = total === 0 ? 0 : page * PAGE_SIZE + 1
  const to = Math.min(total, (page + 1) * PAGE_SIZE)

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="flex items-center gap-2 text-[17px] font-semibold text-slate-950">
            <FiBookmark className="h-4 w-4 text-slate-500" />
            Address book
          </h2>
          <p className="mt-1 max-w-[70ch] text-[12.5px] text-slate-500">
            The addresses offered by &ldquo;Search saved addresses&rdquo; in Ship to on a new shipment. An address
            belongs to one client, or is shared by every client. You can also save one straight from the shipment form.
          </p>
        </div>
        <button
          type="button"
          onClick={() => setEditing('new')}
          className="inline-flex shrink-0 items-center gap-1.5 rounded-md bg-slate-900 px-3 py-1.5 text-[13px] font-semibold text-white hover:bg-slate-700"
        >
          <FiPlus className="h-3.5 w-3.5" />
          Add address
        </button>
      </header>

      <div className="flex flex-wrap items-center gap-2">
        <label className="relative min-w-[16rem] flex-1">
          <span className="sr-only">Search the address book</span>
          <FiSearch className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-slate-400" />
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search any part of the address — name, street, city, ZIP, phone, email or tag"
            className="w-full rounded-md border border-slate-300 py-1.5 pl-8 pr-2.5 text-[13px] outline-none focus:border-slate-500"
          />
        </label>
        <label className="flex items-center gap-2 text-[12.5px] text-slate-600">
          Client
          <select
            value={clientFilter}
            onChange={(e) => { setClientFilter(e.target.value); setPage(0) }}
            className="rounded-md border border-slate-300 bg-white px-2 py-1.5 text-[13px]"
          >
            <option value="">{isAdmin ? 'Every client' : 'My client'}</option>
            {clients.map((c) => (
              <option key={c.clientCode} value={c.clientCode}>{c.clientCode} — {c.name}</option>
            ))}
          </select>
        </label>
      </div>

      <section className="overflow-x-auto rounded-xl border border-slate-200 bg-white">
        <table className="min-w-full text-[13px]">
          <thead className="bg-slate-50 text-left text-[11.5px] font-semibold uppercase tracking-wide text-slate-500">
            <tr>
              <th className="px-3 py-2">Name</th>
              <th className="px-3 py-2">Address</th>
              <th className="px-3 py-2">Contact</th>
              <th className="px-3 py-2">Belongs to</th>
              <th className="px-3 py-2">Tag</th>
              <th className="sticky right-0 bg-slate-50 px-3 py-2 text-right">Actions</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {loading ? (
              <tr><td colSpan={6} className="px-3 py-6 text-center text-slate-500">Loading…</td></tr>
            ) : rows.length === 0 ? (
              <tr>
                <td colSpan={6} className="px-3 py-8 text-center text-slate-500">
                  {debouncedQuery
                    ? <>No saved addresses match &ldquo;{debouncedQuery}&rdquo;.</>
                    : 'No saved addresses yet. Add one here, or use "Save to address book" in Ship to on a new shipment.'}
                </td>
              </tr>
            ) : rows.map((r) => (
              <tr key={r.id}>
                <td className="px-3 py-2.5">
                  <span className="block font-semibold text-slate-900">{r.name}</span>
                  {r.company ? <span className="block text-[11.5px] text-slate-500">{r.company}</span> : null}
                </td>
                <td className="px-3 py-2.5 text-slate-700">
                  <span className="block">{r.addressLine1}{r.addressLine2 ? `, ${r.addressLine2}` : ''}</span>
                  <span className="block text-[11.5px] text-slate-500">
                    {[r.city, r.state, r.postalCode].filter(Boolean).join(', ')} · {r.countryCode}
                    {r.residential ? ' · residential' : ''}
                  </span>
                </td>
                <td className="px-3 py-2.5 text-[12px] text-slate-600">
                  {r.phone ? <span className="block">{r.phone}</span> : null}
                  {r.email ? <span className="block">{r.email}</span> : null}
                  {!r.phone && !r.email ? <span className="text-slate-400">—</span> : null}
                </td>
                <td className="px-3 py-2.5 text-[12px]">
                  {r.ownerCustomerNo
                    ? <span className="font-mono text-slate-700">{r.ownerCustomerNo}</span>
                    : <span className="rounded-full bg-slate-100 px-2 py-0.5 text-[11px] font-semibold text-slate-600">Shared</span>}
                </td>
                <td className="px-3 py-2.5 text-[12px] text-slate-600">{r.tag || <span className="text-slate-400">—</span>}</td>
                <td className="sticky right-0 bg-white px-3 py-2.5">
                  <span className="flex items-center justify-end gap-1.5">
                    <button type="button" onClick={() => setEditing(r)} aria-label={`Edit ${r.name}`}
                      className="rounded-md border border-slate-300 bg-white p-1.5 text-slate-600 hover:bg-slate-50">
                      <FiEdit2 className="h-3.5 w-3.5" />
                    </button>
                    <button type="button" onClick={() => void remove(r)} aria-label={`Remove ${r.name}`}
                      className="rounded-md border border-slate-300 bg-white p-1.5 text-slate-600 hover:border-rose-300 hover:bg-rose-50 hover:text-rose-700">
                      <FiTrash2 className="h-3.5 w-3.5" />
                    </button>
                  </span>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>

      {total > PAGE_SIZE ? (
        <div className="flex items-center justify-between text-[12.5px] text-slate-600">
          <span>Showing {from}–{to} of {total.toLocaleString()}</span>
          <span className="flex gap-2">
            <button type="button" disabled={page === 0} onClick={() => setPage((p) => p - 1)}
              className="rounded-md border border-slate-300 bg-white px-2.5 py-1 font-semibold disabled:opacity-40">Previous</button>
            <button type="button" disabled={to >= total} onClick={() => setPage((p) => p + 1)}
              className="rounded-md border border-slate-300 bg-white px-2.5 py-1 font-semibold disabled:opacity-40">Next</button>
          </span>
        </div>
      ) : total > 0 ? (
        <p className="text-[12.5px] text-slate-500">{total} saved address{total === 1 ? '' : 'es'}</p>
      ) : null}

      {editing ? (
        <AddressEditor
          entry={editing === 'new' ? null : editing}
          clients={clients}
          defaultOwner={clientFilter}
          onClose={() => setEditing(null)}
          onSaved={async () => { setEditing(null); await load() }}
        />
      ) : null}
    </div>
  )
}

type Form = Omit<SavedRecipient, 'id' | 'createdAt' | 'updatedAt'>

function AddressEditor({ entry, clients, defaultOwner, onClose, onSaved }: {
  entry: SavedRecipient | null
  clients: Client[]
  defaultOwner: string
  onClose: () => void
  onSaved: () => Promise<void>
}) {
  const [form, setForm] = useState<Form>(() => entry
    ? { ...entry }
    : {
        ownerCustomerNo: defaultOwner || null, name: '', company: '', phone: '', email: '',
        addressLine1: '', addressLine2: '', city: '', state: '', postalCode: '', countryCode: 'US',
        residential: false, tag: '',
      })
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [saving, setSaving] = useState(false)
  const set = <K extends keyof Form>(k: K, v: Form[K]) => {
    setForm((f) => ({ ...f, [k]: v }))
    setErrors((e) => ({ ...e, [k as string]: '' }))
  }

  const save = async () => {
    const found: Record<string, string> = {}
    if (!form.name?.trim()) found.name = 'Enter a name.'
    if (!form.addressLine1?.trim()) found.addressLine1 = 'Enter the street address.'
    if (!form.city?.trim()) found.city = 'Enter the city.'
    if (!form.postalCode?.trim()) found.postalCode = 'Enter the postal code.'
    if (!form.countryCode) found.countryCode = 'Choose the country.'
    setErrors(found)
    if (Object.keys(found).length > 0) return
    // Empty strings go to the server as null, so a cleared field really clears.
    const clean = (v?: string | null) => (v && v.trim() ? v.trim() : null)
    const body: SavedRecipient = {
      ...form,
      ownerCustomerNo: form.ownerCustomerNo || null,
      name: form.name.trim(), company: clean(form.company), phone: clean(form.phone), email: clean(form.email),
      addressLine1: form.addressLine1.trim(), addressLine2: clean(form.addressLine2), addressLine3: clean(form.addressLine3),
      city: form.city.trim(), state: clean(form.state), postalCode: form.postalCode.trim(),
      countryCode: form.countryCode, tag: clean(form.tag),
    }
    setSaving(true)
    try {
      if (entry?.id) {
        await recipientBookService.update(entry.id, body)
        notify.success(`Updated ${body.name}.`)
      } else {
        const res = await recipientBookService.save(body)
        if (isDuplicateSave(res.message)) {
          notify.info({ title: 'Already saved', body: `${body.name} at ${body.addressLine1} is already in the address book — edit that entry instead.` })
        } else {
          notify.success(`Saved ${body.name}.`)
        }
      }
      await onSaved()
    } catch (e) {
      notify.apiError(e, 'Could not save the address.')
    } finally {
      setSaving(false)
    }
  }

  const input = (bad?: string) => `w-full rounded-md border ${bad ? 'border-rose-400 bg-rose-50/40' : 'border-slate-300'} px-2.5 py-1.5 text-[13px] outline-none focus:border-slate-500`
  const labelCls = 'mb-1 block text-[11.5px] font-semibold uppercase tracking-wide text-slate-500'
  const err = (k: string) => errors[k] ? <span role="alert" className="mt-1 block text-[11.5px] font-medium text-rose-700">{errors[k]}</span> : null
  const text = (k: keyof Form, label: string, span = 'col-span-2 sm:col-span-1', placeholder = '') => (
    <label className={span}>
      <span className={labelCls}>{label}</span>
      <input className={input(errors[k as string])} value={(form[k] as string | null | undefined) ?? ''}
        placeholder={placeholder} onChange={(e) => set(k, e.target.value as Form[typeof k])} />
      {err(k as string)}
    </label>
  )

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-slate-900/40 p-4" onClick={onClose}>
      <div role="dialog" aria-modal="true" aria-label={entry ? `Edit ${entry.name}` : 'Add address'}
        className="max-h-[90vh] w-full max-w-[600px] overflow-y-auto rounded-xl bg-white shadow-xl" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between border-b border-slate-100 px-5 py-3">
          <h3 className="text-[15px] font-semibold text-slate-900">{entry ? `Edit ${entry.name}` : 'Add address'}</h3>
          <button type="button" onClick={onClose} aria-label="Close" className="rounded-md p-1 text-slate-500 hover:bg-slate-100">
            <FiX className="h-4 w-4" />
          </button>
        </div>
        <div className="grid grid-cols-2 gap-3 px-5 py-4">
          <label className="col-span-2">
            <span className={labelCls}>Belongs to</span>
            <select className={input()} value={form.ownerCustomerNo ?? ''}
              onChange={(e) => set('ownerCustomerNo', e.target.value || null)}>
              <option value="">Shared — every client can use it</option>
              {clients.map((c) => <option key={c.clientCode} value={c.clientCode}>{c.clientCode} — {c.name}</option>)}
            </select>
          </label>
          {text('name', 'Name *')}
          {text('company', 'Company')}
          {text('phone', 'Phone')}
          {text('email', 'Email')}
          {text('addressLine1', 'Street address *', 'col-span-2')}
          {text('addressLine2', 'Address line 2', 'col-span-2', 'Suite, floor, unit')}
          {text('city', 'City *')}
          {text('state', 'State / province')}
          {text('postalCode', 'Postal code *')}
          <label className="col-span-2 sm:col-span-1">
            <span className={labelCls}>Country *</span>
            <select className={input(errors.countryCode)} value={form.countryCode}
              onChange={(e) => set('countryCode', e.target.value)}>
              {COUNTRIES.map((c) => <option key={c.code} value={c.code}>{c.name} ({c.code})</option>)}
            </select>
            {err('countryCode')}
          </label>
          {text('tag', 'Tag', 'col-span-2 sm:col-span-1', 'e.g. wholesale, EU')}
          <label className="col-span-2 flex items-center gap-2 self-end sm:col-span-1">
            <input type="checkbox" checked={!!form.residential} onChange={(e) => set('residential', e.target.checked)} />
            <span className="text-[13px] text-slate-700">Residential address</span>
          </label>
        </div>
        <div className="flex justify-end gap-2 border-t border-slate-100 px-5 py-3">
          <button type="button" onClick={onClose}
            className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-[13px] font-semibold text-slate-700 hover:bg-slate-50">
            Cancel
          </button>
          <button type="button" onClick={() => void save()} disabled={saving}
            className="rounded-md bg-slate-900 px-3 py-1.5 text-[13px] font-semibold text-white hover:bg-slate-700 disabled:opacity-50">
            {saving ? 'Saving…' : entry ? 'Save changes' : 'Save address'}
          </button>
        </div>
      </div>
    </div>
  )
}
