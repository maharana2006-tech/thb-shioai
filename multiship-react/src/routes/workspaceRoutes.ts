import { getNavKeysForRole, type UserRole } from '../utils/roles'

/** Top-level nav destinations. Master-data pages now live under Settings. */
export const workspacePaths = {
  dashboard: '/dashboard',
  orders: '/orders',
  settings: '/settings',
} as const

export type WorkspaceRouteKey = keyof typeof workspacePaths

/** Settings sub-pages (the master-data hub). */
export const settingsPaths = {
  clients: '/settings/clients',
  warehouses: '/settings/warehouses',
  carriers: '/settings/carriers',
  /** Merged catalog: shipping services + packages under sub-tabs. Replaces
   *  the old `shipping-services` and `packages` routes. Deep-link the tab
   *  with `?tab=services` or `?tab=packages`. */
  shippingCatalog: '/settings/shipping-catalog',
  shippingServiceMapping: '/settings/shipping-service-mapping',
  importerBroker: '/settings/importer-broker',
  /** Tenant-branded document templates — shipping label, packing slip,
   *  commercial invoice. Old `/settings/label-templates` still redirects
   *  here. */
  templates: '/settings/templates',
  customFields: '/settings/custom-fields',
  routingRules: '/settings/routing-rules',
  reports: '/settings/reports',
  webhookSubs: '/settings/webhook-subscriptions',
  codeMaps: '/settings/code-maps',
  apiKeys: '/settings/api-keys',
  apiReference: '/settings/api-reference',
  auditLog: '/settings/audit-log',
  /** Sprint 49 Tier 0 — admin-managed secrets (OpenAI key, etc.) stored
   *  encrypted in the DB and rotated at runtime. ADMIN role only. */
  system: '/settings/system',
} as const

export const workspaceNavItems: Array<{
  key: WorkspaceRouteKey
  label: string
  to: string
}> = [
  { key: 'dashboard', label: 'Dashboard', to: workspacePaths.dashboard },
  { key: 'orders', label: 'Orders', to: workspacePaths.orders },
  { key: 'settings', label: 'Settings', to: settingsPaths.clients },
]

/** Sub-nav inside the Settings hub. iconKey maps into navIcons; roles gate it.
 *  description is the tight one-line caption SettingsLayout renders under the
 *  submenus row for the active tab. */
export const settingsNavItems: Array<{
  key: string
  label: string
  to: string
  iconKey: string
  description: string
  roles: UserRole[]
}> = [
  { key: 'clients', label: 'Clients', to: settingsPaths.clients, iconKey: 'clients',
    description: 'Customer master data — who you ship for, their carrier accounts, and defaults.',
    roles: ['ADMIN', 'USER'] },
  { key: 'warehouses', label: 'Warehouses', to: settingsPaths.warehouses, iconKey: 'warehouse',
    description: 'Ship-from locations — first-class in a 3PL setup. Platform-owned or client-owned; each client picks a default.',
    roles: ['ADMIN', 'USER'] },
  { key: 'carriers', label: 'Carrier Accounts', to: settingsPaths.carriers, iconKey: 'carrier',
    description: 'Connect + verify live UPS / FedEx / USPS accounts (platform + client).',
    // USER role can read/verify but backend `@PreAuthorize("hasRole('ADMIN')")`
    // on POST /carriers/connect + /disconnect still gates write actions. Keeping
    // both here so the menu bar renders — ADMIN-only writes surface as 403 at
    // submit time with a clear error toast.
    roles: ['ADMIN', 'USER'] },
  { key: 'shipping-catalog', label: 'Shipping Catalog', to: settingsPaths.shippingCatalog, iconKey: 'service',
    description: "Carrier services + packages per origin, and each item's allowed clients. Two sub-tabs share the origin filter and allowlist model.",
    roles: ['ADMIN', 'USER'] },
  { key: 'shipping-service-mapping', label: 'Shipping Service Mapping', to: settingsPaths.shippingServiceMapping, iconKey: 'mapping',
    description: "How order ship-methods resolve to a carrier service — most specific mapping wins.",
    roles: ['ADMIN', 'USER'] },
  { key: 'importer-broker', label: 'Importer / Broker', to: settingsPaths.importerBroker, iconKey: 'customs',
    description: 'Customs identities — importer/broker profiles applied per destination country.',
    roles: ['ADMIN', 'USER'] },
  { key: 'templates', label: 'Templates', to: settingsPaths.templates, iconKey: 'apiDocs',
    description: 'Per-client shipping label, packing slip and commercial invoice templates. Fall back to platform defaults when a client hasn\'t set one.',
    roles: ['ADMIN', 'USER'] },
  { key: 'custom-fields', label: 'Custom Fields', to: settingsPaths.customFields, iconKey: 'mapping',
    description: 'Per-tenant metadata on orders (PO number, department, marketplace order id) — flows through the form and order detail.',
    roles: ['ADMIN', 'USER'] },
  { key: 'system', label: 'System', to: settingsPaths.system, iconKey: 'apiKey',
    description: 'Admin-managed encrypted secrets — OpenAI key and other overrides for env vars. AES-GCM at rest.',
    roles: ['ADMIN'] },
  // ===== Hidden from the Settings menu =====
  // Routes below still resolve so direct URLs and any hard-coded links keep
  // working — only the nav-menu entries are removed. Re-add an object to the
  // array (matching the shape above) to bring one back.
  //
  // { key: 'routing-rules',        label: 'Routing Rules',      to: settingsPaths.routingRules,          iconKey: 'mapping', description: '…', roles: ['ADMIN', 'USER'] },
  // { key: 'reports',              label: 'Reports',            to: settingsPaths.reports,               iconKey: 'apiDocs', description: '…', roles: ['ADMIN', 'USER'] },
  // { key: 'webhook-subscriptions',label: 'Webhooks',           to: settingsPaths.webhookSubs,           iconKey: 'apiKey',  description: '…', roles: ['ADMIN', 'USER'] },
  // { key: 'code-maps',            label: 'Code Maps',          to: settingsPaths.codeMaps,              iconKey: 'mapping', description: '…', roles: ['ADMIN']         },
  // { key: 'api-keys',             label: 'API Keys',           to: settingsPaths.apiKeys,               iconKey: 'apiKey',  description: '…', roles: ['ADMIN']         },
  // { key: 'api-reference',        label: 'API Reference',      to: settingsPaths.apiReference,          iconKey: 'apiDocs', description: '…', roles: ['ADMIN']         },
]

export const getNavItemsForRole = (role: UserRole) => {
  const allowedKeys = getNavKeysForRole(role)
  return workspaceNavItems.filter((item) => allowedKeys.includes(item.key))
}

export const getSettingsNavForRole = (role: UserRole) =>
  settingsNavItems.filter((item) => item.roles.includes(role))

/** Which main sidebar item is active for a path (Settings owns all master data). */
export const resolveWorkspaceRouteKey = (pathname: string): WorkspaceRouteKey | null => {
  if (pathname === workspacePaths.dashboard) {
    return 'dashboard'
  }

  if (pathname === workspacePaths.orders || pathname === '/track-orders') {
    return 'orders'
  }

  if (pathname === '/labels' || pathname === '/generate-labels' || pathname.startsWith('/label/')) {
    return 'orders'
  }

  if (
    pathname.startsWith('/settings') ||
    pathname === '/clients' ||
    pathname === '/carrier' ||
    pathname === '/carrier-connections'
  ) {
    return 'settings'
  }

  return null
}

/** Topbar breadcrumb: section + page label + icon key, synced to the route. */
export const resolveBreadcrumb = (
  pathname: string
): { section: string; label: string; iconKey: string } | null => {
  if (pathname === workspacePaths.dashboard) {
    return { section: 'Overview', label: 'Dashboard', iconKey: 'dashboard' }
  }
  if (pathname === '/orders/new') {
    return { section: 'Operations', label: 'New Shipment', iconKey: 'orders' }
  }
  if (pathname === workspacePaths.orders || pathname === '/track-orders' || pathname.startsWith('/label/')) {
    return { section: 'Operations', label: 'Shipment & Label', iconKey: 'orders' }
  }
  if (pathname.startsWith('/settings') || pathname === '/clients' || pathname === '/carrier') {
    const sub = settingsNavItems.find((i) => i.to === pathname)
    return { section: 'Settings', label: sub?.label ?? 'Settings', iconKey: sub?.iconKey ?? 'settings' }
  }
  return null
}
