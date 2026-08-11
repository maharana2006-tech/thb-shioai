import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useNavigate, useOutletContext, useParams } from 'react-router-dom'
import {
  FiArrowLeft,
  FiCheck,
  FiChevronDown,
  FiDroplet,
  FiEye,
  FiInfo,
  FiLayers,
  FiLoader,
  FiSave,
  FiSliders,
  FiTag,
  FiTrash2,
  FiType,
  FiUploadCloud,
} from 'react-icons/fi'
import type { ReactNode } from 'react'
import { notify } from '../utils/notify'
import { clientService, type Client } from '../api/clientService'
import { labelTemplateService, type LabelTemplate } from '../api/labelTemplateService'
import type { SettingsOutletContext } from './layout/SettingsLayout'
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

  // Lightroom-style collapsible panels on the left edit rail. All open by
  // default; the layout builder starts collapsed since it's the advanced tool.
  const [openPanels, setOpenPanels] = useState<Record<string, boolean>>({
    scope: true,
    branding: true,
    layout: false,
  })
  const togglePanel = (id: string) => setOpenPanels((p) => ({ ...p, [id]: !p[id] }))

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

  const primary = template.primaryColor || DEFAULT_COLOR
  const scopeLabel = effectiveTenantId ?? 'Platform default'

  return (
    <div className="space-y-4 pb-24">
      {/* ── Sticky header: back · title · actions ─────────────────────────── */}
      <div className="sticky top-0 z-20 -mx-1 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-[#e3d9c4] bg-[#faf7f0]/85 px-4 py-3 backdrop-blur">
        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => navigate('/settings/templates')}
            title="Back to templates"
            className="inline-flex h-8 w-8 items-center justify-center rounded-xl border border-[#e3d9c4] bg-white text-[#5a4526] transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
          >
            <FiArrowLeft className="h-4 w-4" />
          </button>
          <div>
            <p className="font-mono text-[9px] font-bold uppercase tracking-[0.2em] text-[#b6a684]">
              Label template
            </p>
            <h2 className="text-[15px] font-semibold leading-tight text-[#1f150c]">
              {isEdit ? `Edit template #${template.id ?? '?'}` : 'New template'}
              <span className="ml-2 rounded-full bg-[#412d15]/10 px-2 py-0.5 align-middle text-[10px] font-bold uppercase tracking-[0.08em] text-[#412d15]">
                {scopeLabel}
              </span>
            </h2>
          </div>
          {loading ? <FiLoader className="animate-spin text-[#b6a684]" /> : null}
        </div>
        <div className="flex items-center gap-2">
          {isEdit && admin ? (
            <button
              type="button"
              onClick={remove}
              className="inline-flex items-center gap-1.5 rounded-xl border border-rose-200 bg-white px-3 py-2 text-[12.5px] font-semibold text-rose-600 transition hover:bg-rose-50"
            >
              <FiTrash2 className="h-3.5 w-3.5" /> Delete
            </button>
          ) : null}
          <button
            type="button"
            onClick={save}
            disabled={saving}
            className="inline-flex items-center gap-1.5 rounded-xl bg-[#1f150c] px-4 py-2 text-[12.5px] font-semibold text-[#f4eede] shadow-sm transition hover:bg-[#412d15] disabled:cursor-not-allowed disabled:opacity-50"
          >
            {saving ? <FiLoader className="h-3.5 w-3.5 animate-spin" /> : <FiSave className="h-3.5 w-3.5" />}
            {saving ? 'Saving…' : isEdit ? 'Save changes' : 'Save template'}
          </button>
        </div>
      </div>

      {/* ── Two columns: form (left) · live preview (right, sticky) ────────── */}
      <div className="grid gap-4 xl:grid-cols-[minmax(0,1fr)_minmax(340px,380px)]">
        {/* FORM COLUMN — Lightroom-style "Develop" edit rail */}
        <div className="overflow-hidden rounded-2xl border border-[#e3d9c4] bg-white shadow-sm">
          {/* rail module header */}
          <div className="flex items-center gap-2 bg-[#1f150c] px-4 py-2.5">
            <FiSliders className="h-3.5 w-3.5 text-[#b6a684]" />
            <span className="font-mono text-[10px] font-bold uppercase tracking-[0.22em] text-[#e3d9c4]">Edit</span>
            <span className="ml-auto font-mono text-[9px] font-bold uppercase tracking-[0.14em] text-[#8a7959]">
              {humanType(templateType)}
            </span>
          </div>

          {/* ── Scope panel ─────────────────────────────────────────────── */}
          <Panel id="scope" icon={<FiTag className="h-3.5 w-3.5" />} title="Scope"
            summary={scopeLabel} open={openPanels.scope} onToggle={() => togglePanel('scope')}>
            {/* Two "inspector field" cells in one row — photo-editor style:
                a tiny caps label sits inside a bordered well above a compact,
                borderless select. */}
            <div className="grid grid-cols-2 gap-2">
              <FieldCell label="Applies to" disabled={isEdit} chevron>
                <select
                  value={tenantId}
                  onChange={(e) => setTenantId(e.target.value || PLATFORM_DEFAULT_VALUE)}
                  disabled={isEdit}
                  className={cellSelect}
                >
                  {clientOptions.map((opt) => (
                    <option key={opt.value} value={opt.value}>{opt.label}</option>
                  ))}
                </select>
              </FieldCell>
              <FieldCell label="Document type" disabled={isEdit} chevron>
                <select
                  value={templateType}
                  onChange={(e) => setTemplateType(e.target.value)}
                  disabled={isEdit}
                  className={cellSelect}
                >
                  {TEMPLATE_TYPES.map((t) => (
                    <option key={t} value={t}>{humanType(t)}</option>
                  ))}
                </select>
              </FieldCell>
            </div>

            <p className={fieldHint}>
              {isEdit
                ? 'Tenant + type are locked after creation (unique per pair).'
                : "A tenant's row overrides the platform default for the same type."}
            </p>
          </Panel>

          {/* ── Branding panel (sub-grouped: logo · colour · copy) ──────── */}
          <Panel id="branding" icon={<FiDroplet className="h-3.5 w-3.5" />} title="Branding"
            open={openPanels.branding} onToggle={() => togglePanel('branding')}>
            {/* LOGO — asset slot: fixed preview tile + drop target + actions */}
            <GroupLabel>Logo</GroupLabel>
            <div className="flex items-center gap-3">
              <label
                onDragOver={(e) => e.preventDefault()}
                onDrop={(e) => { e.preventDefault(); handleLogo(e.dataTransfer.files?.[0] ?? null) }}
                className="group relative flex h-16 w-24 shrink-0 cursor-pointer items-center justify-center overflow-hidden rounded-lg border-2 border-dashed border-[#e3d9c4] bg-[#faf7f0]/50 transition hover:border-[#cdbf9f] hover:bg-[#faf7f0]"
                style={template.logoBase64 ? { backgroundImage: 'linear-gradient(45deg,#eee6d6 25%,transparent 25%,transparent 75%,#eee6d6 75%),linear-gradient(45deg,#eee6d6 25%,transparent 25%,transparent 75%,#eee6d6 75%)', backgroundSize: '10px 10px', backgroundPosition: '0 0,5px 5px' } : undefined}
              >
                {template.logoBase64 ? (
                  <img
                    src={template.logoBase64.startsWith('data:') ? template.logoBase64 : `data:image/png;base64,${template.logoBase64}`}
                    alt="Logo preview"
                    className="max-h-[80%] max-w-[85%] object-contain"
                  />
                ) : (
                  <FiUploadCloud className="h-5 w-5 text-[#b6a684]" />
                )}
                <input
                  ref={logoInputRef}
                  type="file"
                  accept="image/png,image/jpeg"
                  onChange={(e) => handleLogo(e.target.files?.[0] ?? null)}
                  className="absolute inset-0 cursor-pointer opacity-0"
                />
              </label>
              <div className="min-w-0 flex-1">
                <p className="text-[11.5px] font-semibold text-[#5a4526]">
                  {template.logoBase64 ? 'Logo added' : 'Drop an image or click the tile'}
                </p>
                <p className="text-[10px] text-[#b6a684]">PNG or JPEG · under 200 KB</p>
                {template.logoBase64 ? (
                  <button
                    type="button"
                    onClick={removeLogo}
                    className="mt-1.5 inline-flex items-center gap-1 rounded-lg border border-[#e3d9c4] bg-white px-2 py-1 text-[10.5px] font-semibold text-[#8a7959] transition hover:border-rose-200 hover:bg-rose-50 hover:text-rose-700"
                  >
                    <FiTrash2 className="h-3 w-3" /> Remove logo
                  </button>
                ) : null}
              </div>
            </div>

            <div className="my-1 border-t border-[#eee6d6]" />

            {/* PRIMARY COLOUR — big swatch (opens picker) + hex cell + presets */}
            <GroupLabel>Primary colour</GroupLabel>
            <div className="flex items-center gap-2">
              <label
                className="relative h-9 w-9 shrink-0 cursor-pointer overflow-hidden rounded-lg border border-[#e3d9c4] shadow-inner"
                style={{ backgroundColor: primary }}
                title="Open colour picker"
              >
                <input
                  type="color"
                  value={primary}
                  onChange={(e) => updateField('primaryColor', e.target.value)}
                  className="absolute inset-0 cursor-pointer opacity-0"
                />
              </label>
              <div className="w-28">
                <FieldCell label="Hex">
                  <input
                    type="text"
                    value={template.primaryColor || ''}
                    onChange={(e) => updateField('primaryColor', e.target.value)}
                    placeholder={DEFAULT_COLOR}
                    className="w-full bg-transparent font-mono text-[12px] font-semibold uppercase text-[#1f150c] outline-none"
                  />
                </FieldCell>
              </div>
              <div className="ml-auto flex items-center gap-1.5">
                {COLOR_PRESETS.map((c) => (
                  <button
                    key={c}
                    type="button"
                    onClick={() => updateField('primaryColor', c)}
                    title={c}
                    style={{ background: c }}
                    className={`h-5 w-5 rounded-full ring-2 ring-offset-1 transition ${
                      primary.toLowerCase() === c.toLowerCase() ? 'ring-[#1f150c]' : 'ring-transparent hover:ring-[#cdbf9f]'
                    }`}
                  />
                ))}
              </div>
            </div>

            <div className="my-1 border-t border-[#eee6d6]" />

            {/* COPY — header + footer as inspector field cells */}
            <GroupLabel>Copy</GroupLabel>
            <FieldCell label="Header line" aside={<span className="normal-case tracking-normal text-[#cdbf9f]">{(template.headerText || '').length}/200</span>}>
              <input
                type="text"
                maxLength={200}
                value={template.headerText || ''}
                onChange={(e) => updateField('headerText', e.target.value)}
                placeholder={DEFAULT_HEADER}
                className="w-full bg-transparent text-[12.5px] font-semibold text-[#1f150c] outline-none placeholder:font-normal placeholder:text-[#b6a684]"
              />
            </FieldCell>
            <FieldCell label="Footer note" aside={<span className="normal-case tracking-normal text-[#cdbf9f]">{(template.footerText || '').length}/500</span>}>
              <textarea
                rows={3}
                maxLength={500}
                value={template.footerText || ''}
                onChange={(e) => updateField('footerText', e.target.value)}
                placeholder={'Thanks for your order!\nReturns accepted within 30 days.'}
                className="w-full resize-none bg-transparent text-[12px] leading-4 text-[#3f3527] outline-none placeholder:text-[#b6a684]"
              />
            </FieldCell>

            <div className="my-1 border-t border-[#eee6d6]" />

            {/* toggle row with sliding switch */}
            <button
              type="button"
              onClick={() => updateField('showItems', !(template.showItems !== false))}
              className="flex w-full items-center justify-between gap-3 rounded-lg border border-[#e3d9c4] bg-[#faf7f0]/50 px-3 py-2 text-left transition hover:bg-[#faf7f0]"
            >
              <span className="flex items-center gap-1.5 text-[12px] font-semibold text-[#5a4526]">
                <FiType className="h-3.5 w-3.5 text-[#b6a684]" /> Order-lines table
              </span>
              <span className="flex items-center gap-2">
                <span className={`font-mono text-[9px] font-bold uppercase tracking-wide ${template.showItems !== false ? 'text-[#412d15]' : 'text-[#b6a684]'}`}>
                  {template.showItems !== false ? 'On' : 'Off'}
                </span>
                <span className={`inline-flex h-5 w-9 items-center rounded-full px-0.5 transition ${template.showItems !== false ? 'justify-end bg-[#1f150c]' : 'justify-start bg-[#cdbf9f]'}`}>
                  <span className="h-4 w-4 rounded-full bg-white shadow-sm" />
                </span>
              </span>
            </button>
          </Panel>

          {/* ── Layout builder panel (advanced, collapsed by default) ───── */}
          <Panel
            id="layout"
            icon={<FiLayers className="h-3.5 w-3.5" />}
            title="Layout builder"
            badge="Advanced"
            open={openPanels.layout}
            onToggle={() => togglePanel('layout')}
            aside={template.layoutJson ? (
              <span className="rounded-full bg-emerald-50 px-1.5 py-0.5 text-[9px] font-bold uppercase tracking-wide text-emerald-700 ring-1 ring-emerald-200">Active</span>
            ) : null}
          >
            <p className={`${fieldHint} mt-0 mb-1`}>
              Drag blocks onto the canvas; a saved layout drives rendering, else the branding above is the fallback.
            </p>
            {template.layoutJson ? (
              <button
                type="button"
                onClick={() => {
                  if (!window.confirm('Clear the whole layout? The branding header / footer / logo will be used at render time until you rebuild.')) return
                  setTemplate((prev) => ({ ...prev, layoutJson: null }))
                }}
                className="mb-2 inline-flex items-center gap-1 rounded-lg border border-[#e3d9c4] bg-white px-2 py-1 text-[10.5px] font-semibold text-[#8a7959] transition hover:bg-[#faf7f0]"
              >
                <FiTrash2 className="h-3 w-3" /> Clear layout
              </button>
            ) : null}
            <LabelTemplateLayoutBuilder
              value={(() => {
                if (!template.layoutJson) return null
                try { return JSON.parse(template.layoutJson) as TemplateLayout }
                catch { return emptyLayout }
              })()}
              onChange={(next) => setTemplate((prev) => ({ ...prev, layoutJson: JSON.stringify(next) }))}
            />
          </Panel>
        </div>

        {/* PREVIEW COLUMN (sticky on wide screens) */}
        <aside className="xl:sticky xl:top-20 xl:self-start">
          <TemplatePreview
            logo={template.logoBase64}
            color={primary}
            header={template.headerText}
            footer={template.footerText}
            showItems={template.showItems !== false}
            typeLabel={humanType(templateType)}
          />

          {/* Scope explainer */}
          <div className="mt-4 rounded-2xl border border-[#e3d9c4] bg-[#faf7f0]/60 p-4">
            <h3 className="mb-2 flex items-center gap-1.5 font-mono text-[9px] font-bold uppercase tracking-[0.16em] text-[#b6a684]">
              <FiInfo className="h-3 w-3" /> What this covers
            </h3>
            <ul className="space-y-1.5 text-[11.5px] leading-4 text-[#5a4526]">
              <li className="flex items-start gap-1.5">
                <FiCheck className="mt-0.5 h-3 w-3 shrink-0 text-emerald-600" />
                Branded packing slip slipped inside the parcel.
              </li>
              <li className="flex items-start gap-1.5">
                <FiCheck className="mt-0.5 h-3 w-3 shrink-0 text-emerald-600" />
                Tenant-scoped — each client can override the platform default.
              </li>
              <li className="flex items-start gap-1.5 text-[#8a7959]">
                <FiInfo className="mt-0.5 h-3 w-3 shrink-0" />
                The carrier's shipping label itself is <strong>not</strong> customisable.
              </li>
            </ul>
          </div>
        </aside>
      </div>
    </div>
  )
}

// ===== presentational sub-components =====

/**
 * A collapsible "Develop"-style panel — Lightroom's accordion module. The
 * header row toggles the body; a chevron rotates, an optional summary shows
 * the current value when collapsed, and an aside/badge can flag state.
 */
function Panel({ id, icon, title, summary, badge, open, onToggle, aside, children }: {
  id: string
  icon: ReactNode
  title: string
  summary?: string
  badge?: string
  open: boolean
  onToggle: () => void
  aside?: ReactNode
  children: ReactNode
}) {
  return (
    <div className="border-t border-[#e3d9c4] first:border-t-0">
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        aria-controls={`panel-${id}`}
        className="flex w-full items-center gap-2.5 bg-[#faf7f0]/70 px-4 py-2.5 text-left transition hover:bg-[#faf7f0]"
      >
        <FiChevronDown className={`h-3.5 w-3.5 shrink-0 text-[#b6a684] transition-transform duration-200 ${open ? '' : '-rotate-90'}`} />
        <span className="inline-flex h-5 w-5 items-center justify-center rounded text-[#412d15]">{icon}</span>
        <span className="text-[11px] font-bold uppercase tracking-[0.14em] text-[#412d15]">{title}</span>
        {badge ? (
          <span className="rounded-full bg-white px-1.5 py-0.5 text-[8.5px] font-bold uppercase tracking-wide text-[#b6a684] ring-1 ring-[#e3d9c4]">{badge}</span>
        ) : null}
        <span className="ml-auto flex items-center gap-2">
          {aside}
          {!open && summary ? (
            <span className="max-w-[160px] truncate font-mono text-[10px] font-semibold text-[#8a7959]">{summary}</span>
          ) : null}
        </span>
      </button>
      {open ? <div id={`panel-${id}`} className="space-y-3 px-4 pb-4 pt-3">{children}</div> : null}
    </div>
  )
}

/** A dim sub-group heading inside a panel (Lightroom's "Basic → Tone" style). */
function GroupLabel({ children }: { children: ReactNode }) {
  return (
    <p className="font-mono text-[8.5px] font-bold uppercase tracking-[0.18em] text-[#cdbf9f]">{children}</p>
  )
}

/**
 * A photo-editor "inspector field" cell: a bordered well whose tiny caps label
 * sits above a compact borderless control, with a trailing chevron. The whole
 * cell lights up on focus-within (like Lightroom/Premiere metadata fields).
 */
function FieldCell({ label, disabled, chevron, aside, children }: {
  label: string
  disabled?: boolean
  chevron?: boolean
  aside?: ReactNode
  children: ReactNode
}) {
  return (
    <div
      className={`relative rounded-lg border px-2.5 pb-1.5 pt-1 transition ${
        disabled
          ? 'border-[#eee6d6] bg-[#faf7f0]/40'
          : 'border-[#e3d9c4] bg-[#faf7f0]/60 focus-within:border-[#cdbf9f] focus-within:bg-white focus-within:ring-2 focus-within:ring-[#f0e9d8]'
      }`}
    >
      <span className="pointer-events-none flex items-center justify-between text-[8px] font-bold uppercase tracking-[0.14em] text-[#b6a684]">
        {label}{aside}
      </span>
      {children}
      {chevron ? (
        <FiChevronDown className="pointer-events-none absolute bottom-2 right-2 h-3 w-3 text-[#8a7959]" />
      ) : null}
    </div>
  )
}

/** Borderless compact select that fills a {@link FieldCell}. */
const cellSelect =
  'w-full cursor-pointer appearance-none truncate bg-transparent pr-4 text-[12px] font-semibold text-[#1f150c] outline-none disabled:cursor-not-allowed disabled:opacity-70'

/** A live, paper-like packing-slip preview that reflects the current branding. */
function TemplatePreview({ logo, color, header, footer, showItems, typeLabel }: {
  logo?: string | null
  color: string
  header?: string | null
  footer?: string | null
  showItems: boolean
  typeLabel: string
}) {
  const c = color || DEFAULT_COLOR
  const logoSrc = logo ? (logo.startsWith('data:') ? logo : `data:image/png;base64,${logo}`) : null
  return (
    <div className="overflow-hidden rounded-2xl border border-[#e3d9c4] bg-white shadow-sm">
      <div className="flex items-center justify-between border-b border-[#eee6d6] bg-[#faf7f0]/60 px-3 py-2">
        <span className="inline-flex items-center gap-1.5 font-mono text-[9px] font-bold uppercase tracking-[0.18em] text-[#b6a684]">
          <FiEye className="h-3 w-3" /> Live preview
        </span>
        <span className="font-mono text-[9px] font-bold uppercase tracking-[0.12em] text-[#cdbf9f]">{typeLabel}</span>
      </div>
      <div className="bg-[#f4eede]/50 p-4">
        {/* the "paper" */}
        <div className="mx-auto max-w-[300px] overflow-hidden rounded-md border border-[#e3d9c4] bg-white text-[8px] leading-tight text-[#3f3527] shadow-md">
          {/* header band */}
          <div className="flex items-center justify-between gap-2 px-3 py-2.5 text-white" style={{ backgroundColor: c }}>
            <span className="truncate text-[11px] font-extrabold tracking-wide">{(header || DEFAULT_HEADER).toUpperCase()}</span>
            {logoSrc ? <img src={logoSrc} alt="" className="h-5 max-w-[70px] shrink-0 object-contain" /> : null}
          </div>
          <div className="space-y-2.5 p-3">
            <div className="flex justify-between gap-3">
              <div>
                <p className="font-bold" style={{ color: c }}>SHIP TO</p>
                <p className="mt-0.5 font-semibold text-[9px] text-[#1f150c]">Jane Doe</p>
                <p>221B Baker Street</p>
                <p>London NW1 6XE, GB</p>
              </div>
              <div className="text-right text-[#8a7959]">
                <p className="font-bold" style={{ color: c }}>ORDER</p>
                <p className="mt-0.5">#100482</p>
                <p>Aug 11, 2026</p>
              </div>
            </div>
            {showItems ? (
              <table className="w-full">
                <thead>
                  <tr className="border-b" style={{ borderColor: c, color: c }}>
                    <th className="py-0.5 text-left font-bold">ITEM</th>
                    <th className="py-0.5 text-right font-bold">QTY</th>
                  </tr>
                </thead>
                <tbody className="text-[#3f3527]">
                  <tr className="border-b border-[#eee6d6]"><td className="py-0.5">Cotton Tote Bag</td><td className="py-0.5 text-right">2</td></tr>
                  <tr className="border-b border-[#eee6d6]"><td className="py-0.5">Ceramic Mug</td><td className="py-0.5 text-right">1</td></tr>
                  <tr><td className="py-0.5">Notebook Pack</td><td className="py-0.5 text-right">3</td></tr>
                </tbody>
              </table>
            ) : null}
            {footer ? (
              <p className="whitespace-pre-line border-t border-[#eee6d6] pt-1.5 text-[7.5px] text-[#8a7959]">{footer}</p>
            ) : (
              <p className="border-t border-[#eee6d6] pt-1.5 text-[7.5px] italic text-[#cdbf9f]">Footer text appears here…</p>
            )}
          </div>
        </div>
        <p className="mt-2 text-center text-[10px] text-[#b6a684]">Sample data — your live orders fill these fields.</p>
      </div>
    </div>
  )
}

// ===== helpers =====

const fieldLabel =
  'mb-1.5 block font-mono text-[9px] font-bold uppercase tracking-[0.16em] text-[#b6a684]'
const fieldHint = 'mt-1.5 text-[10.5px] leading-4 text-[#b6a684]'

/** Quick-pick brand colours for the primary-colour control (espresso + common). */
const COLOR_PRESETS = ['#1f150c', '#412d15', '#8a7959', '#1d4ed8', '#047857', '#b91c1c']

/** Humanize a TEMPLATE_TYPES wire value: SHIPPING_LABEL → "Shipping label". */
function humanType(t: string): string {
  const s = t.replace(/_/g, ' ').toLowerCase()
  return s.charAt(0).toUpperCase() + s.slice(1)
}

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
