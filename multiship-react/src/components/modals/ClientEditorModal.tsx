import { useEffect, useState, type ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import { notify } from '../../utils/notify'
import { FiArrowRight, FiPlus, FiStar, FiX } from 'react-icons/fi'
import { ApiError } from '../../api/apiClient'
import { clientService, type Address, type Client, type ClientUpsertPayload } from '../../api/clientService'
import { accountRefService, type CarrierAccountRef } from '../../api/accountRefService'
import { formatCarrierName, carrierEnvironmentOptions, type CarrierEnvironment } from '../../utils/carrierUtils'
import CarrierLogo from '../workspace/CarrierLogo'
import Select from '../workspace/Select'

interface ClientEditorModalProps {
  /** Existing client to edit; omit to create a new one. */
  client?: Client | null
  /** Prefill + lock the code (the Labels page "No client" flow). */
  lockedCode?: string
  onSaved: (client: Client) => void
  onClose: () => void
}

const inputClassName =
  'w-full rounded-2xl border border-slate-200 bg-slate-50 px-3 py-2 text-[13px] text-slate-950 outline-none transition focus:border-sky-600 focus:ring-4 focus:ring-sky-100'

function Field({ label, children, required }: { label: string; children: ReactNode; required?: boolean }) {
  return (
    <label className="block">
      <span className="mb-1 block text-[11px] font-semibold uppercase tracking-[0.14em] text-slate-400">
        {label}
        {required ? <span className="ml-1 text-rose-500">*</span> : null}
      </span>
      {children}
    </label>
  )
}

const carrierOptions = [
  { code: 'UPS', label: 'UPS' },
  { code: 'FEDEX', label: 'FedEx' },
  { code: 'USPS', label: 'USPS' },
]

/**
 * Create or edit a client. In edit mode the client's linked carrier accounts
 * are managed inline: list with a per-client default, plus an add-account
 * form that saves straight into the account book with customerNo set.
 */
export default function ClientEditorModal({ client, lockedCode, onSaved, onClose }: ClientEditorModalProps) {
  const isEdit = Boolean(client)
  const navigate = useNavigate()

  const emptyAddress: Address = { name: '', line1: '', line2: '', city: '', state: '', zip: '', country: 'US', phone: '' }
  const [form, setForm] = useState<ClientUpsertPayload>({
    clientCode: client?.clientCode || lockedCode || '',
    name: client?.name || '',
    email: client?.email || '',
    phone: client?.phone || '',
    shipFrom: { ...emptyAddress, ...(client?.shipFrom ?? {}) },
    returnAddress: { ...emptyAddress, ...(client?.returnAddress ?? {}) },
    returnSameAsShipFrom: client?.returnSameAsShipFrom ?? true,
  })
  const [saving, setSaving] = useState(false)

  const [accounts, setAccounts] = useState<CarrierAccountRef[]>(client?.carrierAccounts ?? [])
  const [showAccountForm, setShowAccountForm] = useState(false)
  const [accountForm, setAccountForm] = useState({
    carrierCode: 'UPS',
    accountNumber: '',
    clientId: '',
    clientSecret: '',
    environment: 'SANDBOX' as CarrierEnvironment,
    clientDefault: true,
  })

  const set = (key: keyof ClientUpsertPayload) => (event: { target: { value: string } }) =>
    setForm((cur) => ({ ...cur, [key]: event.target.value }))

  const setAddr = (block: 'shipFrom' | 'returnAddress', key: keyof Address) => (event: { target: { value: string } }) =>
    setForm((cur) => ({ ...cur, [block]: { ...cur[block], [key]: event.target.value } }))

  const refreshAccounts = async (code: string) => {
    try {
      setAccounts(await clientService.listClientAccounts(code))
    } catch {
      /* the list is cosmetic inside the modal */
    }
  }

  useEffect(() => {
    if (client?.clientCode) void refreshAccounts(client.clientCode)
  }, [client?.clientCode])

  const handleSave = async () => {
    if (!form.clientCode.trim() || !form.name.trim()) {
      notify.error('Client code and name are required.')
      return
    }

    // In create mode the account section is optional — but if opened, it
    // must be complete.
    const wantsAccount = !isEdit && showAccountForm
    if (wantsAccount && (!accountForm.accountNumber.trim() || !accountForm.clientId.trim() || !accountForm.clientSecret.trim())) {
      notify.error('Complete the carrier account (number, client ID, secret) or collapse that section.')
      return
    }

    setSaving(true)
    try {
      const response = isEdit
        ? await clientService.updateClient(form.clientCode, form)
        : await clientService.createClient(form)

      if (wantsAccount) {
        try {
          await accountRefService.upsertAccount({
            accountNumber: accountForm.accountNumber.trim(),
            carrierCode: accountForm.carrierCode,
            clientId: accountForm.clientId.trim(),
            clientSecret: accountForm.clientSecret.trim(),
            environment: accountForm.environment,
            customerNo: response.data.clientCode,
            clientDefault: accountForm.clientDefault,
          })
          notify.success(
            `Client ${response.data.clientCode} created with its ${formatCarrierName(accountForm.carrierCode)} account${
              accountForm.clientDefault ? ' (default)' : ''
            }.`
          )
        } catch (accountError) {
          notify.error(
            `Client created, but the carrier account failed: ${
              accountError instanceof Error ? accountError.message : 'unknown error'
            }. Add it via Edit.`
          )
        }
      } else {
        notify.success(`Client ${response.data.clientCode} ${isEdit ? 'updated' : 'created'}.`)
      }

      onSaved(response.data)
    } catch (error) {
      if (error instanceof ApiError && error.errorCode === 'CLIENT_CODE_TAKEN') {
        notify.error(`Client code ${form.clientCode.toUpperCase()} is already registered.`)
      } else {
        notify.error(error instanceof Error ? error.message : 'Failed to save the client.')
      }
    } finally {
      setSaving(false)
    }
  }



  const accountFields = (
    <div className="grid grid-cols-2 gap-2.5">
      <Field label="Carrier" required>
        <Select
          value={accountForm.carrierCode}
          onChange={(e) => setAccountForm((cur) => ({ ...cur, carrierCode: e.target.value }))}
        >
          {carrierOptions.map((option) => (
            <option key={option.code} value={option.code}>
              {option.label}
            </option>
          ))}
        </Select>
      </Field>
      <Field label="Account number" required>
        <input
          value={accountForm.accountNumber}
          onChange={(e) => setAccountForm((cur) => ({ ...cur, accountNumber: e.target.value }))}
          className={inputClassName}
        />
      </Field>
      <Field label="Client ID" required>
        <input
          value={accountForm.clientId}
          onChange={(e) => setAccountForm((cur) => ({ ...cur, clientId: e.target.value }))}
          className={inputClassName}
          autoComplete="off"
        />
      </Field>
      <Field label="Client Secret" required>
        <input
          type="password"
          value={accountForm.clientSecret}
          onChange={(e) => setAccountForm((cur) => ({ ...cur, clientSecret: e.target.value }))}
          className={inputClassName}
          autoComplete="off"
        />
      </Field>
      <Field label="Environment">
        <Select
          value={accountForm.environment}
          onChange={(e) => setAccountForm((cur) => ({ ...cur, environment: e.target.value as CarrierEnvironment }))}
        >
          {carrierEnvironmentOptions.map((option) => (
            <option key={option} value={option}>
              {option}
            </option>
          ))}
        </Select>
      </Field>
      <label className="flex items-end gap-2 pb-2 text-[12px] font-semibold text-slate-700">
        <input
          type="checkbox"
          checked={accountForm.clientDefault}
          onChange={(e) => setAccountForm((cur) => ({ ...cur, clientDefault: e.target.checked }))}
          className="h-4 w-4 rounded border-slate-300 text-slate-950 focus:ring-slate-300"
        />
        Default account
      </label>
    </div>
  )

  const addressBlock = (block: 'shipFrom' | 'returnAddress') => {
    const a = (form[block] ?? {}) as Address
    return (
      <div className="grid grid-cols-2 gap-3">
        <div className="col-span-2">
          <Field label="Attention / company">
            <input value={a.name ?? ''} onChange={setAddr(block, 'name')} className={inputClassName} placeholder="Warehouse / contact name" />
          </Field>
        </div>
        <div className="col-span-2">
          <Field label="Street address">
            <input value={a.line1 ?? ''} onChange={setAddr(block, 'line1')} className={inputClassName} placeholder="123 Industrial Blvd" />
          </Field>
        </div>
        <div className="col-span-2">
          <Field label="Suite / unit">
            <input value={a.line2 ?? ''} onChange={setAddr(block, 'line2')} className={inputClassName} placeholder="Suite 400 (optional)" />
          </Field>
        </div>
        <Field label="City">
          <input value={a.city ?? ''} onChange={setAddr(block, 'city')} className={inputClassName} placeholder="Chicago" />
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="State">
            <input value={a.state ?? ''} onChange={setAddr(block, 'state')} className={inputClassName} placeholder="IL" />
          </Field>
          <Field label="Zip">
            <input value={a.zip ?? ''} onChange={setAddr(block, 'zip')} className={inputClassName} placeholder="60601" />
          </Field>
        </div>
        <Field label="Country">
          <input value={a.country ?? ''} onChange={setAddr(block, 'country')} className={`${inputClassName} uppercase`} placeholder="US" />
        </Field>
        <Field label="Phone">
          <input value={a.phone ?? ''} onChange={setAddr(block, 'phone')} className={inputClassName} placeholder="555-123-4567" />
        </Field>
      </div>
    )
  }

  return (
    <>
      <div className="fixed inset-0 z-40 bg-slate-950/45 backdrop-blur-sm" onClick={onClose} aria-hidden="true" />
      <aside
        role="dialog"
        aria-modal="true"
        aria-label={isEdit ? `Edit client ${form.clientCode}` : 'Add a client'}
        className="fixed inset-y-0 right-0 z-50 flex w-full max-w-[480px] flex-col border-l border-slate-200 bg-white shadow-[-18px_0_50px_rgba(8,14,26,0.18)]"
      >
        <div className="flex items-start justify-between gap-3 border-b border-slate-100 px-5 py-4">
          <div>
            <p className="text-[10.5px] font-bold uppercase tracking-[0.16em] text-slate-400">
              {isEdit ? 'Edit client' : 'New client'}
            </p>
            <h3 className="mt-1 text-[15px] font-semibold text-slate-950">
              {isEdit ? `${client?.name} (${form.clientCode})` : 'Register a client'}
            </h3>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="rounded-xl border border-slate-200 bg-white p-2 text-slate-500 transition hover:bg-slate-50"
            aria-label="Close"
          >
            <FiX className="h-4 w-4" />
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-5 py-4">
        {/* Identity + contact */}
        <div className="grid grid-cols-2 gap-3">
          <Field label="Client code" required>
            <input
              value={form.clientCode}
              onChange={set('clientCode')}
              readOnly={isEdit || Boolean(lockedCode)}
              className={`${inputClassName} ${isEdit || lockedCode ? 'opacity-70' : ''} uppercase`}
              placeholder="MA1885"
            />
          </Field>
          <Field label="Client name" required>
            <input value={form.name} onChange={set('name')} className={inputClassName} placeholder="Modern Art Fabrics" />
          </Field>
          <Field label="Email">
            <input value={form.email} onChange={set('email')} className={inputClassName} placeholder="contact@client.com" />
          </Field>
          <Field label="Phone">
            <input value={form.phone} onChange={set('phone')} className={inputClassName} placeholder="555-123-4567" />
          </Field>
        </div>

        {/* Ship From — printed as the sender in the label FROM block */}
        <div className="mt-5 rounded-2xl border border-slate-200 bg-slate-50/60 p-3.5">
          <h4 className="text-[12.5px] font-semibold text-slate-950">Ship From address</h4>
          <p className="mb-3 text-[11px] leading-5 text-slate-500">
            The origin printed on this client's labels — where their parcels ship out from.
          </p>
          {addressBlock('shipFrom')}
        </div>

        {/* Return — where undeliverable / returned parcels go */}
        <div className="mt-4 rounded-2xl border border-slate-200 bg-slate-50/60 p-3.5">
          <div className="flex items-start justify-between gap-3">
            <div>
              <h4 className="text-[12.5px] font-semibold text-slate-950">Return address</h4>
              <p className="text-[11px] leading-5 text-slate-500">
                Where undeliverable parcels come back to.
              </p>
            </div>
            <label className="flex shrink-0 items-center gap-2 pt-0.5 text-[11.5px] font-semibold text-slate-700">
              <input
                type="checkbox"
                checked={form.returnSameAsShipFrom ?? true}
                onChange={(e) => setForm((cur) => ({ ...cur, returnSameAsShipFrom: e.target.checked }))}
                className="h-4 w-4 rounded border-slate-300 text-slate-950 focus:ring-slate-300"
              />
              Same as Ship From
            </label>
          </div>
          {form.returnSameAsShipFrom ? (
            <p className="mt-3 rounded-xl border border-dashed border-slate-200 bg-white px-3 py-2.5 text-center text-[11.5px] text-slate-500">
              Returns use the Ship From address above.
            </p>
          ) : (
            <div className="mt-3">{addressBlock('returnAddress')}</div>
          )}
        </div>

        {isEdit ? (
          <div className="mt-5 rounded-2xl border border-slate-200 bg-slate-50/60 p-3.5">
            <div className="flex items-center justify-between">
              <h4 className="text-[12.5px] font-semibold text-slate-950">Carrier accounts</h4>
              <button
                type="button"
                onClick={() => {
                  onClose()
                  navigate('/settings/carriers')
                }}
                className="inline-flex items-center gap-1 rounded-xl border border-slate-200 bg-white px-2.5 py-1.5 text-[11px] font-semibold text-[#412d15] transition hover:bg-slate-50"
              >
                Manage in Carrier
                <FiArrowRight className="h-3 w-3" />
              </button>
            </div>

            <div className="mt-2.5 space-y-1.5">
              {accounts.map((account) => (
                <div key={account.id} className="flex items-center gap-2.5 rounded-xl border border-slate-200 bg-white px-3 py-2">
                  <CarrierLogo carrierId={account.carrierCode} size={18} className="rounded-sm" />
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-[12px] font-semibold text-slate-800">
                      {account.accountName || formatCarrierName(account.carrierCode)} · {account.accountNumber}
                    </p>
                  </div>
                  {account.clientDefault ? (
                    <span className="inline-flex items-center gap-1 rounded-full bg-[#412d15]/10 px-2 py-0.5 text-[10.5px] font-semibold text-[#412d15]">
                      <FiStar className="h-3 w-3" />
                      Default
                    </span>
                  ) : null}
                </div>
              ))}
              {!accounts.length ? (
                <p className="rounded-xl border border-dashed border-slate-200 bg-white px-3 py-3 text-center text-[11.5px] text-slate-500">
                  No accounts linked yet — add one from the Carrier page.
                </p>
              ) : null}
            </div>
          </div>
        ) : null}

        {!isEdit ? (
          <div className="mt-5 rounded-2xl border border-slate-200 bg-slate-50/60 p-3.5">
            <div className="flex items-center justify-between gap-3">
              <div>
                <h4 className="text-[12.5px] font-semibold text-slate-950">Carrier account</h4>
                <p className="text-[11px] text-slate-500">
                  Optional — add it now and this client's orders ship automatically.
                </p>
              </div>
              <button
                type="button"
                onClick={() => setShowAccountForm((cur) => !cur)}
                className="inline-flex shrink-0 items-center gap-1 rounded-xl border border-slate-200 bg-white px-2.5 py-1.5 text-[11px] font-semibold text-slate-700 transition hover:bg-slate-50"
              >
                <FiPlus className={`h-3 w-3 transition ${showAccountForm ? 'rotate-45' : ''}`} />
                {showAccountForm ? 'Remove' : 'Add account'}
              </button>
            </div>

            {showAccountForm ? <div className="mt-3">{accountFields}</div> : null}
          </div>
        ) : null}

        </div>

        <div className="border-t border-slate-100 px-5 py-4">
          <button
            type="button"
            onClick={() => {
              void handleSave()
            }}
            disabled={saving}
            className="w-full rounded-2xl bg-[#1f150c] px-4 py-2.5 text-sm font-semibold text-white transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:bg-slate-300"
          >
            {saving ? 'Saving…' : isEdit ? 'Save changes' : 'Create client'}
          </button>
        </div>
      </aside>
    </>
  )
}
