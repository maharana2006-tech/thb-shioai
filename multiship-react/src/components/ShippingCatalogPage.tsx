import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { FiBox, FiPackage } from 'react-icons/fi'
import ShippingServicesPage from './ShippingServicesPage'
import PackagesPage from './PackagesPage'

type CatalogTab = 'services' | 'packages'

const TAB_DEFS: ReadonlyArray<{ key: CatalogTab; label: string; icon: typeof FiBox }> = [
  { key: 'services', label: 'Shipping services', icon: FiBox },
  { key: 'packages', label: 'Packages',          icon: FiPackage },
]

/**
 * Shipping catalog — a two-tab wrapper that hosts the existing Shipping
 * Services and Packages pages under one route. Merges what were previously
 * `/settings/shipping-services` and `/settings/packages` so the operator has
 * a single Settings entry to reason about (both views share carrier catalog,
 * origin filter, and per-item client allowlists).
 *
 * Active tab is URL-driven via `?tab=services|packages` so links from
 * elsewhere in the app (Dashboard tiles, client-editor Packages step) land on
 * the right sub-view.
 *
 * Only one child page is mounted at a time. Each child registers its own
 * refresh handler with SettingsLayout via `useOutletContext` — so a tab
 * switch's unmount/mount naturally hands the top-bar Refresh icon to the
 * newly-active page. No extra plumbing needed on our side.
 */
export default function ShippingCatalogPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const initialTab: CatalogTab = (searchParams.get('tab') as CatalogTab | null) === 'packages'
    ? 'packages' : 'services'
  const [tab, setTab] = useState<CatalogTab>(initialTab)

  // Keep URL in sync with the active tab — bookmarks + browser back button
  // both flip tabs the way you'd expect.
  useEffect(() => {
    const current = searchParams.get('tab')
    if (current !== tab) {
      const next = new URLSearchParams(searchParams)
      next.set('tab', tab)
      setSearchParams(next, { replace: true })
    }
    // We're the writer, not the reader — omitting searchParams from deps.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tab])

  return (
    <div className="space-y-3">
      {/* Sub-tab pills — same visual language as the client-editor wizard rail
          so the two multi-view Settings pages read consistently. */}
      <div role="tablist" aria-label="Shipping catalog tabs" className="flex flex-wrap items-center gap-1 rounded-xl border border-slate-200 bg-white p-1 shadow-sm">
        {TAB_DEFS.map((t) => {
          const active = tab === t.key
          const Icon = t.icon
          return (
            <button
              key={t.key}
              type="button"
              role="tab"
              aria-selected={active}
              aria-controls={`shipping-catalog-panel-${t.key}`}
              onClick={() => setTab(t.key)}
              className={`inline-flex items-center gap-1.5 rounded-lg px-3.5 py-2 text-[13px] font-semibold transition ${
                active
                  ? 'bg-[#1f150c] text-white'
                  : 'text-slate-600 hover:bg-slate-100'
              }`}
            >
              <Icon className="h-4 w-4" />
              {t.label}
            </button>
          )
        })}
      </div>

      {/* Only mount the active tab. Each child's registerRefresh effect handles
          its own subscription — the unmount / mount cycle on tab switch
          automatically hands the Refresh icon off to the newly-active page. */}
      <div id={`shipping-catalog-panel-${tab}`} role="tabpanel">
        {tab === 'services' ? <ShippingServicesPage /> : <PackagesPage />}
      </div>
    </div>
  )
}
