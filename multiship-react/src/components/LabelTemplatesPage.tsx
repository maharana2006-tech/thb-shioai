import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useOutletContext } from 'react-router-dom'
import {
  FiCheck,
  FiEye,
  FiImage,
  FiInfo,
  FiLoader,
  FiRefreshCw,
  FiSave,
  FiTrash2,
} from 'react-icons/fi'
import { notify } from '../utils/notify'
import { clientService, type Client } from '../api/clientService'
import { labelTemplateService, type LabelTemplate } from '../api/labelTemplateService'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import Select from './workspace/Select'

const PLATFORM_DEFAULT_VALUE = '__PLATFORM__'
const DEFAULT_HEADER = 'PACKING SLIP'
const DEFAULT_COLOR = '#1f150c'

/** Sprint 42 — Label Templates settings page. Powers the tenant-branded
 *  packing slip PDF that ships INSIDE the parcel. The carrier's shipping
 *  label itself is not customisable — that's carrier-mandated. */
export default function LabelTemplatesPage() {
  const { registerRefresh } = useOutletContext<SettingsOutletContext>()

  const [clients, setClients] = useState<Client[]>([])
  const [tenantId, setTenantId] = useState<string>(PLATFORM_DEFAULT_VALUE)
  const [template, setTemplate] = useState<LabelTemplate>(blankTemplate())
  const [previewOrderNo, setPreviewOrderNo] = useState('')
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [reloadToken, setReloadToken] = useState(0)
  const logoInputRef = useRef<HTMLInputElement>(null)

  const effectiveTenantId = tenantId === PLATFORM_DEFAULT_VALUE ? null : tenantId

  // Load clients once (small list — powers the tenant picker).
  useEffect(() => {
    let cancelled = false
    clientService
      .listClients({ size: 200, status: 'ACTIVE', sortBy: 'code' })
      .then((resp) => {
        if (cancelled) return
        setClients(resp.data?.content ?? [])
      })
      .catch(() => setClients([]))
    return () => {
      cancelled = true
    }
  }, [])

  // Load the tenant's template (does NOT fall back to platform default).
  const loadTemplate = useCallback(async () => {
    setLoading(true)
    try {
      const tmpl = await labelTemplateService.forTenant(effectiveTenantId, 'PACKING_SLIP')
      setTemplate(tmpl ?? blankTemplate(effectiveTenantId))
    } catch (err: any) {
      notify.error(err?.message ?? 'Failed to load template.')
      setTemplate(blankTemplate(effectiveTenantId))
    } finally {
      setLoading(false)
    }
  }, [effectiveTenantId])

  useEffect(() => {
    loadTemplate()
  }, [loadTemplate, reloadToken])

  const refresh = useCallback(() => {
    setReloadToken((t) => t + 1)
  }, [])

  useEffect(() => {
    registerRefresh(refresh)
    return () => registerRefresh(null)
  }, [registerRefresh, refresh])

  // ===== handlers =====

  const updateField = <K extends keyof LabelTemplate>(key: K, value: LabelTemplate[K]) => {
    setTemplate((t) => ({ ...t, [key]: value }))
  }

  const handleLogo = async (file: File | null) => {
    if (!file) return
    if (file.size > 200 * 1024) {
      notify.error('Logo must be under 200 KB. Try compressing the image.')
      return
    }
    const dataUrl = await readAsDataUrl(file)
    updateField('logoBase64', dataUrl)
  }

  const removeLogo = () => {
    updateField('logoBase64', null)
    if (logoInputRef.current) logoInputRef.current.value = ''
  }

  const save = async () => {
    setSaving(true)
    try {
      const payload: LabelTemplate = {
        ...template,
        tenantId: effectiveTenantId,
        templateType: 'PACKING_SLIP',
      }
      const resp = await labelTemplateService.save(payload)
      if (resp.data) setTemplate(resp.data)
      notify.success('Template saved.')
    } catch (err: any) {
      notify.error(err?.message ?? 'Failed to save template.')
    } finally {
      setSaving(false)
    }
  }

  const remove = async () => {
    if (!template.id) return
    if (!window.confirm('Delete this template? Orders will fall back to the platform default.')) return
    try {
      await labelTemplateService.remove(template.id)
      setTemplate(blankTemplate(effectiveTenantId))
      notify.success('Template deleted.')
    } catch (err: any) {
      notify.error(err?.message ?? 'Failed to delete template.')
    }
  }

  const openPreview = () => {
    const orderNo = previewOrderNo.trim()
    if (!orderNo) {
      notify.error('Enter an order number to preview.')
      return
    }
    setPreviewUrl(labelTemplateService.previewUrl(orderNo))
  }

  const clientOptions = useMemo(
    () => [
      { value: PLATFORM_DEFAULT_VALUE, label: 'Platform default (all tenants without their own)' },
      ...clients.map((c) => ({
        value: c.clientCode,
        label: `${c.clientCode} — ${c.name}`,
      })),
    ],
    [clients],
  )

  return (
    <div className="grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.1fr)]">
      {/* ===== Editor ===== */}
      <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        <div className="mb-5 flex items-start justify-between gap-4">
          <div>
            <h2 className="text-base font-semibold text-slate-900">Packing slip template</h2>
            <p className="mt-1 max-w-md text-[12.5px] text-slate-500">
              Branded page printed on regular paper and slipped inside the parcel.
              The carrier's shipping label itself is not customisable.
            </p>
          </div>
          {loading ? <FiLoader className="animate-spin text-slate-400" /> : null}
        </div>

        <div className="mb-4">
          <label className={fieldLabel}>Tenant</label>
          <Select
            value={tenantId}
            onChange={(e) => setTenantId(e.target.value || PLATFORM_DEFAULT_VALUE)}
          >
            {clientOptions.map((opt) => (
              <option key={opt.value} value={opt.value}>
                {opt.label}
              </option>
            ))}
          </Select>
          <p className="mt-1 text-[11px] text-slate-400">
            Platform default is the fallback when a tenant hasn't configured its own.
          </p>
        </div>

        <div className="mb-4">
          <label className={fieldLabel}>Logo (PNG or JPEG, under 200 KB)</label>
          <div className="flex items-center gap-3">
            <div className="relative flex h-16 w-40 items-center justify-center overflow-hidden rounded-lg border border-dashed border-slate-300 bg-slate-50">
              {template.logoBase64 ? (
                <img
                  src={
                    template.logoBase64.startsWith('data:')
                      ? template.logoBase64
                      : `data:image/png;base64,${template.logoBase64}`
                  }
                  alt="Logo"
                  className="max-h-full max-w-full object-contain"
                />
              ) : (
                <FiImage className="text-2xl text-slate-300" />
              )}
            </div>
            <div className="flex flex-col gap-2">
              <input
                ref={logoInputRef}
                type="file"
                accept="image/png,image/jpeg"
                onChange={(e) => handleLogo(e.target.files?.[0] ?? null)}
                className="text-[12px] text-slate-600"
              />
              {template.logoBase64 ? (
                <button
                  type="button"
                  onClick={removeLogo}
                  className="w-fit text-[11.5px] font-semibold text-rose-600 hover:underline"
                >
                  Remove logo
                </button>
              ) : null}
            </div>
          </div>
        </div>

        <div className="mb-4 grid grid-cols-2 gap-4">
          <div>
            <label className={fieldLabel}>Primary colour</label>
            <div className="flex items-center gap-2">
              <input
                type="color"
                value={template.primaryColor || DEFAULT_COLOR}
                onChange={(e) => updateField('primaryColor', e.target.value)}
                className="h-9 w-12 cursor-pointer rounded border border-slate-200"
              />
              <input
                type="text"
                value={template.primaryColor || ''}
                onChange={(e) => updateField('primaryColor', e.target.value)}
                placeholder={DEFAULT_COLOR}
                className={textInput}
              />
            </div>
          </div>
          <div>
            <label className={fieldLabel}>Show item lines</label>
            <label className="mt-2 flex items-center gap-2 text-[13px] text-slate-700">
              <input
                type="checkbox"
                checked={template.showItems !== false}
                onChange={(e) => updateField('showItems', e.target.checked)}
              />
              Include the order-lines table
            </label>
          </div>
        </div>

        <div className="mb-4">
          <label className={fieldLabel}>Header text</label>
          <input
            type="text"
            maxLength={200}
            value={template.headerText || ''}
            onChange={(e) => updateField('headerText', e.target.value)}
            placeholder={DEFAULT_HEADER}
            className={textInput}
          />
        </div>

        <div className="mb-5">
          <label className={fieldLabel}>Footer text (thank-you note, return policy)</label>
          <textarea
            rows={3}
            maxLength={500}
            value={template.footerText || ''}
            onChange={(e) => updateField('footerText', e.target.value)}
            placeholder={'Thanks for your order!\nReturns accepted within 30 days.'}
            className={`${textInput} resize-none`}
          />
        </div>

        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2 text-[11.5px] text-slate-500">
            <FiInfo className="text-slate-400" />
            {template.id ? `Template #${template.id}` : 'New template — save to publish.'}
          </div>
          <div className="flex gap-2">
            {template.id ? (
              <button
                type="button"
                onClick={remove}
                className="inline-flex items-center gap-1.5 rounded-md border border-rose-200 bg-white px-3 py-1.5 text-[12.5px] font-semibold text-rose-600 hover:bg-rose-50"
              >
                <FiTrash2 /> Delete
              </button>
            ) : null}
            <button
              type="button"
              onClick={save}
              disabled={saving}
              className="inline-flex items-center gap-1.5 rounded-md bg-[#1f150c] px-3 py-1.5 text-[12.5px] font-semibold text-white hover:bg-black disabled:opacity-50"
            >
              {saving ? <FiLoader className="animate-spin" /> : <FiSave />} Save
            </button>
          </div>
        </div>
      </div>

      {/* ===== Preview + explainer ===== */}
      <div className="flex flex-col gap-4">
        <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
          <h2 className="mb-1 text-base font-semibold text-slate-900">Live preview</h2>
          <p className="mb-3 text-[12.5px] text-slate-500">
            Enter an existing order number to fetch its packing slip PDF rendered with
            the currently-<em>saved</em> template.
          </p>
          <div className="mb-3 flex gap-2">
            <input
              type="text"
              value={previewOrderNo}
              onChange={(e) => setPreviewOrderNo(e.target.value)}
              placeholder="Order number, e.g. 100"
              className={textInput}
            />
            <button
              type="button"
              onClick={openPreview}
              className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50"
            >
              <FiEye /> Preview
            </button>
            {previewUrl ? (
              <button
                type="button"
                onClick={() => setPreviewUrl((u) => (u ? `${u.split('#')[0]}#r=${Date.now()}` : u))}
                className="inline-flex items-center gap-1.5 rounded-md border border-slate-200 bg-white px-3 py-1.5 text-[12.5px] font-semibold text-slate-700 hover:bg-slate-50"
              >
                <FiRefreshCw /> Reload
              </button>
            ) : null}
          </div>
          {previewUrl ? (
            <iframe
              src={previewUrl}
              title="Packing slip preview"
              className="h-[520px] w-full rounded-lg border border-slate-200 bg-slate-50"
            />
          ) : (
            <div className="flex h-[520px] items-center justify-center rounded-lg border border-dashed border-slate-200 text-[12.5px] text-slate-400">
              Enter an order number above to render a preview.
            </div>
          )}
        </div>

        <div className="rounded-2xl border border-emerald-100 bg-emerald-50/60 p-5">
          <h3 className="mb-1 text-[13px] font-semibold text-emerald-800">What this template covers</h3>
          <ul className="space-y-1 text-[12.5px] text-emerald-800/80">
            <li className="flex items-start gap-2">
              <FiCheck className="mt-0.5 text-emerald-600" />
              Branded packing slip PDF slipped inside the parcel.
            </li>
            <li className="flex items-start gap-2">
              <FiCheck className="mt-0.5 text-emerald-600" />
              Tenant-scoped: each client can override the platform default.
            </li>
            <li className="flex items-start gap-2 text-emerald-800/70">
              <FiInfo className="mt-0.5" />
              The carrier's shipping label is <strong>not</strong> customisable — every carrier
              mandates an exact barcode / address format on its own label PDF.
            </li>
          </ul>
        </div>
      </div>
    </div>
  )
}

// ===== helpers =====

const fieldLabel =
  'mb-1 block text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500'
const textInput =
  'w-full rounded-md border border-slate-200 bg-white px-3 py-1.5 text-[13px] text-slate-800 outline-none focus:border-[#1f150c]'

function blankTemplate(tenantId: string | null = null): LabelTemplate {
  return {
    id: null,
    tenantId,
    templateType: 'PACKING_SLIP',
    logoBase64: null,
    primaryColor: DEFAULT_COLOR,
    headerText: DEFAULT_HEADER,
    footerText: '',
    showItems: true,
  }
}

function readAsDataUrl(file: File): Promise<string> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(reader.result as string)
    reader.onerror = reject
    reader.readAsDataURL(file)
  })
}
