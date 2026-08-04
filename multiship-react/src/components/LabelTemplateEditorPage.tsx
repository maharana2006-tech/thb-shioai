import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useOutletContext, useParams } from 'react-router-dom'
import {
  FiArrowLeft,
  FiCheck,
  FiImage,
  FiInfo,
  FiLoader,
  FiSave,
  FiTrash2,
} from 'react-icons/fi'
import { notify } from '../utils/notify'
import { clientService, type Client } from '../api/clientService'
import { labelTemplateService, type LabelTemplate } from '../api/labelTemplateService'
import type { SettingsOutletContext } from './layout/SettingsLayout'
import Select from './workspace/Select'
import LabelTemplateLayoutBuilder from './LabelTemplateLayoutBuilder'
import { emptyLayout, type TemplateLayout } from '../utils/templateLayout'

const PLATFORM_DEFAULT_VALUE = '__PLATFORM__'
const DEFAULT_HEADER = 'PACKING SLIP'
const DEFAULT_COLOR = '#1f150c'
/** Template types the editor can create/edit. Wire values match the backend
 *  {@code label_templates.template_type} enum; unique per (tenant, type). */
const TEMPLATE_TYPES = ['SHIPPING_LABEL', 'PACKING_SLIP', 'COMMERCIAL_INVOICE', 'RETURN_COVER']

const isAdmin = () =>
  (localStorage.getItem('multiship_role') || '').toUpperCase() === 'ADMIN'

/**
 * Editor for a single label template. Two entry points:
 *   /settings/templates/new — create mode; tenant + type are
 *       editable selectors so the operator picks the scope
 *   /settings/templates/{id} — edit mode; tenant + type are
 *       locked to the existing row's (tenantId, templateType) tuple
 *       because the DB has a unique constraint on that pair
 *
 * Live preview moved out — it's on the list page as a global tool now.
 */
export default function LabelTemplateEditorPage() {
  const navigate = useNavigate()
  const { id } = useParams<{ id?: string }>()
  const editingId = id && id !== 'new' ? Number(id) : null
  const isEdit = editingId != null
  const admin = isAdmin()
  const { registerRefresh } = useOutletContext<SettingsOutletContext>()

  const [clients, setClients] = useState<Client[]>([])
  const [tenantId, setTenantId] = useState<string>(PLATFORM_DEFAULT_VALUE)
  const [templateType, setTemplateType] = useState('PACKING_SLIP')
  const [template, setTemplate] = useState<LabelTemplate>(blankTemplate())
  const [loading, setLoading] = useState(false)
  const [saving, setSaving] = useState(false)
  const [reloadToken, setReloadToken] = useState(0)
  const logoInputRef = useRef<HTMLInputElement>(null)

  const effectiveTenantId = tenantId === PLATFORM_DEFAULT_VALUE ? null : tenantId

  // Load clients once — powers the tenant picker in create mode.
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

  // Load an existing template by id (edit mode). In create mode we start
  // from a blank template scoped to whatever tenant/type the operator picks.
  const loadTemplate = useCallback(async () => {
    if (!isEdit || editingId == null) {
      setTemplate(blankTemplate(effectiveTenantId, templateType))
      return
    }
    setLoading(true)
    try {
      const resp = await labelTemplateService.getById(editingId)
      const t = resp.data
      if (!t) throw new Error('Template not found.')
      setTemplate(t)
      // In edit mode, tenant + type are dictated by the row.
      setTenantId(t.tenantId ?? PLATFORM_DEFAULT_VALUE)
      setTemplateType(t.templateType ?? 'PACKING_SLIP')
    } catch (err: any) {
      notify.error(err?.message ?? 'Failed to load template.')
      navigate('/settings/templates')
    } finally {
      setLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [editingId, isEdit, reloadToken])

  useEffect(() => {
    loadTemplate()
  }, [loadTemplate])

  // When the operator changes the tenant/type in CREATE mode, reset the
  // template body to a blank scoped correctly. In edit mode the pickers
  // are disabled so this path doesn't fire.
  useEffect(() => {
    if (isEdit) return
    setTemplate((prev) => ({
      ...prev,
      tenantId: effectiveTenantId,
      templateType,
    }))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [effectiveTenantId, templateType, isEdit])

  const refresh = useCallback(() => setReloadToken((t) => t + 1), [])
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
        templateType,
      }
      const resp = await labelTemplateService.save(payload)
      if (resp.data) setTemplate(resp.data)
      notify.success('Template saved.')
      // On create → navigate to the new row's edit URL so the operator
      // stays in-context and can keep tweaking.
      if (!isEdit && resp.data?.id != null) {
        navigate(`/settings/templates/${resp.data.id}`, { replace: true })
      }
    } catch (err: any) {
      notify.error(err?.message ?? 'Failed to save template.')
    } finally {
      setSaving(false)
    }
  }

  const remove = async () => {
    if (!template.id) return
    if (!admin) {
      notify.error('Only admins can delete templates.')
      return
    }
    const label = effectiveTenantId ? effectiveTenantId : 'Platform default'
    const ok = await notify.confirm(
      `Delete this template for ${label} (${templateType})? Orders will fall back to the resolution order.`,
      { danger: true, confirmLabel: 'Delete', title: 'Delete label template' },
    )
    if (!ok) return
    try {
      await labelTemplateService.remove(template.id)
      notify.success('Template deleted.')
      navigate('/settings/templates')
    } catch (err: any) {
      notify.error(err?.message ?? 'Failed to delete template.')
    }
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
    <div className="space-y-4">
      {/* Back / breadcrumb */}
      <div className="flex items-center justify-between">
        <button
          type="button"
          onClick={() => navigate('/settings/templates')}
          className="inline-flex items-center gap-1.5 text-[12px] font-semibold text-slate-600 hover:text-slate-950"
        >
          <FiArrowLeft className="h-3.5 w-3.5" /> Back to templates
        </button>
        {loading ? <FiLoader className="animate-spin text-slate-400" /> : null}
      </div>

      {/* Editor card */}
      <div className="rounded-2xl border border-slate-200 bg-white p-5 shadow-sm">
        <div className="mb-5">
          <h2 className="text-base font-semibold text-slate-900">
            {isEdit ? `Edit template #${template.id ?? '?'}` : 'New template'}
          </h2>
          <p className="mt-1 max-w-2xl text-[12.5px] text-slate-500">
            Branded page printed on regular paper and slipped inside the parcel. The
            carrier's shipping label itself is not customisable.
          </p>
        </div>

        <div className="mb-4 grid grid-cols-2 gap-4">
          <div>
            <label className={fieldLabel}>Tenant</label>
            <Select
              value={tenantId}
              onChange={(e) => setTenantId(e.target.value || PLATFORM_DEFAULT_VALUE)}
              disabled={isEdit}
            >
              {clientOptions.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </Select>
            <p className="mt-1 text-[11px] text-slate-400">
              {isEdit
                ? 'Tenant is fixed after creation (unique per tenant + type).'
                : 'Platform default is the fallback for tenants without their own.'}
            </p>
          </div>
          <div>
            <label className={fieldLabel}>Template type</label>
            <Select
              value={templateType}
              onChange={(e) => setTemplateType(e.target.value)}
              disabled={isEdit}
            >
              {TEMPLATE_TYPES.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </Select>
            <p className="mt-1 text-[11px] text-slate-400">
              {isEdit
                ? 'Type is fixed after creation.'
                : 'Per-tenant templates override the platform default for the picked (tenant, type) combo. Shipments use the tenant\'s row when present; otherwise the platform default.'}
            </p>
          </div>
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

        {/* ===== Phase 1 drag-drop layout builder =====
            Coexists with the legacy header/footer/logo fields above — the
            renderer picks the layout tree over the legacy fields when
            layoutJson is non-null. Phase 2 wires the actual PDF / ZPL
            renderers to this tree. */}
        <div className="mt-2 mb-5 rounded-2xl border border-slate-200 bg-slate-50/60 p-3">
          <div className="mb-2 flex items-center justify-between gap-3">
            <div>
              <p className="text-[12.5px] font-semibold text-slate-950">Layout builder</p>
              <p className="text-[11px] leading-4 text-slate-500">
                Drag blocks into the canvas. Click a field on the right to insert a data binding at the cursor.
                Once saved, the layout drives rendering; legacy header / footer / logo above are used as a fallback until layouts are populated.
              </p>
            </div>
            {template.layoutJson ? (
              <button
                type="button"
                onClick={() => {
                  if (!window.confirm('Clear the whole layout? The legacy header / footer / logo fields will be used at render time until you rebuild.')) return
                  setTemplate((prev) => ({ ...prev, layoutJson: null }))
                }}
                className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2 py-1 text-[10.5px] font-semibold text-slate-600 transition hover:bg-slate-50"
              >
                <FiTrash2 className="h-3 w-3" /> Clear layout
              </button>
            ) : null}
          </div>
          <LabelTemplateLayoutBuilder
            value={(() => {
              // Deserialize once per render. Falls back to the empty layout
              // when the persisted blob is missing or corrupt (a bad parse
              // shouldn't blank the operator's session; we just treat it
              // as "no layout yet").
              if (!template.layoutJson) return null
              try { return JSON.parse(template.layoutJson) as TemplateLayout }
              catch { return emptyLayout }
            })()}
            onChange={(next) => setTemplate((prev) => ({ ...prev, layoutJson: JSON.stringify(next) }))}
          />
        </div>

        <div className="flex items-center justify-between">
          <div className="flex items-center gap-2 text-[11.5px] text-slate-500">
            <FiInfo className="text-slate-400" />
            {isEdit ? `Template #${template.id}` : 'New template — save to publish.'}
          </div>
          <div className="flex gap-2">
            {isEdit && admin ? (
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

      {/* Scope explainer */}
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
  )
}

// ===== helpers =====

const fieldLabel =
  'mb-1 block text-[10.5px] font-bold uppercase tracking-[0.14em] text-slate-500'
const textInput =
  'w-full rounded-md border border-slate-200 bg-white px-3 py-1.5 text-[13px] text-slate-800 outline-none focus:border-[#1f150c]'

function blankTemplate(
  tenantId: string | null = null,
  templateType: string = 'PACKING_SLIP',
): LabelTemplate {
  return {
    id: null,
    tenantId,
    templateType,
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
