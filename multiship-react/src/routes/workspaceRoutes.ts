import { getNavKeysForRole, type UserRole } from '../utils/roles'

/** Top-level nav destinations. Master-data pages now live under Settings. */
export const workspacePaths = {
  dashboard: '/dashboard',
  orders: '/orders',
  /** Bulk Mailer — file imports, the importer, labels & invoices. */
  bulk: '/bulk',
  settings: '/settings',
} as const

/** Bulk Mailer tabs and pages. The tab lives in the URL, so Back and links land on it. */
export const bulkPaths = {
  imports: '/bulk/imports',
  /** Labels & Invoices — every labelled order's label, invoice and statement; its own page, opened from Bulk Mailer. */
  labels: '/bulk/labels',
  trash: '/bulk/trash',
  /** The CSV / Excel importer — its own page, reached from Import history. */
  importFile: '/bulk/import',
} as const

/** One batch's page: its rows, fixes and label actions. */
export const bulkBatchPath = (id: number) => `/bulk/batches/${id}`

/** API batches (WMS + API fetches) — their own page in the Orders section. */
export const apiBatchesPath = '/orders/api-batches'
export const apiBatchPath = (id: number) => `${apiBatchesPath}/${id}`

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
  addressBook: '/settings/address-book',
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
  /** Sprint 50 Tier 0.5 PR E — admin-only user management: assign
   *  clientCode to legacy USER rows, deactivate revoked accounts, audit
   *  the changes. Backing surface for the tenant-scope flag rollout. */
  users: '/settings/users',
  /** Sprint 52 — admin CRUD for the carrier_shipping_limit catalog
   *  (per-carrier / per-service MPS + weight + commodity + free-declared
   *  caps, with direction-awareness). ADMIN role only. */
  carrierLimits: '/settings/carrier-limits',
  /** Sprint 52 — per-client output routing (LOCAL_FS / SFTP / PRINTER)
   *  for generated labels + commercial invoices. ADMIN-only. */
  outputDestinations: '/settings/output-destinations',
  printers: '/settings/printers',
  /** S1-S5 — protocol-agnostic external-system connection manager
   *  (NDS Oracle today, REST/gRPC/SFTP later). Admin CRUD on the
   *  external_system_connection table + secrets + client-login
   *  overrides + Test-connection dial. ADMIN role only. */
  externalSystems: '/settings/external-systems',
} as const

export const workspaceNavItems: Array<{
  key: WorkspaceRouteKey
  label: string
  to: string
}> = [
  { key: 'dashboard', label: 'Dashboard', to: workspacePaths.dashboard },
  { key: 'orders', label: 'Orders', to: workspacePaths.orders },
  { key: 'bulk', label: 'Bulk Mailer', to: bulkPaths.imports },
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
  { key: 'address-book', label: 'Address book', to: settingsPaths.addressBook, iconKey: 'clients',
    description: 'The saved addresses offered in Ship to on a new shipment — add, correct or remove them, per client or shared.',
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
  { key: 'audit-log', label: 'Logs', to: settingsPaths.auditLog, iconKey: 'dashboard',
    description: 'Error, shipment and user-activity logs in one table — who did what, which labels generated or failed, and why. Filter by category, order number, actor and date.',
    roles: ['ADMIN', 'USER'] },
  { key: 'system', label: 'System', to: settingsPaths.system, iconKey: 'apiKey',
    description: 'Admin-managed encrypted secrets — OpenAI key and other overrides for env vars. AES-GCM at rest.',
    roles: ['ADMIN'] },
  { key: 'users', label: 'Users', to: settingsPaths.users, iconKey: 'clients',
    description: 'Assign each USER account to a client, deactivate revoked accounts, and audit the changes. Backfill client_code before flipping the tenant-scope flag.',
    roles: ['ADMIN'] },
  { key: 'carrier-limits', label: 'Carrier Limits', to: settingsPaths.carrierLimits, iconKey: 'carrier',
    description: 'Per-carrier / per-service caps on packages, commodities, weight and free declared value. Direction-aware (FORWARD vs RETURN); edits invalidate the resolver cache immediately.',
    roles: ['ADMIN'] },
  { key: 'output-destinations', label: 'Output Destinations', to: settingsPaths.outputDestinations, iconKey: 'mapping',
    description: 'Deliver generated labels + commercial invoices per client to a folder or an SFTP server. Every dispatch is also copied to the database. Printers are set up under Printers.',
    roles: ['ADMIN'] },
  { key: 'printers', label: 'Printers', to: settingsPaths.printers, iconKey: 'mapping',
    description: 'Register network label and invoice printers, choose which printer each client prints on, and send test pages. Used by Send to printer on the Orders page.',
    roles: ['ADMIN'] },
  { key: 'external-systems', label: 'External Systems', to: settingsPaths.externalSystems, iconKey: 'apiKey',
    description: 'DB-driven connection manager for external systems (Oracle WMS today, REST/gRPC/SFTP later). Set host/port/creds/pool params + per-tenant login overrides + test the connection live.',
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

  if (pathname === workspacePaths.orders || pathname === '/track-orders' || pathname.startsWith(apiBatchesPath)) {
    return 'orders'
  }

  if (pathname === '/labels' || pathname === '/generate-labels' || pathname.startsWith('/label/')) {
    return 'orders'
  }

  if (pathname === workspacePaths.bulk || pathname.startsWith('/bulk/') || pathname === '/orders/history') {
    return 'bulk'
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
  if (pathname.startsWith(apiBatchesPath)) {
    return { section: 'Operations', label: 'API Batches', iconKey: 'orders' }
  }
  if (pathname === '/orders/new') {
    return { section: 'Operations', label: 'New Shipment', iconKey: 'orders' }
  }
  if (pathname === workspacePaths.orders || pathname === '/track-orders' || pathname.startsWith('/label/')) {
    return { section: 'Operations', label: 'Shipment & Label', iconKey: 'orders' }
  }
  if (pathname === bulkPaths.labels) {
    return { section: 'Bulk Mailer', label: 'Labels & Invoices', iconKey: 'bulk' }
  }
  if (pathname === bulkPaths.importFile) {
    return { section: 'Bulk Mailer', label: 'Import CSV / Excel', iconKey: 'bulk' }
  }
  if (pathname === workspacePaths.bulk || pathname.startsWith('/bulk/')) {
    return { section: 'Operations', label: 'Bulk Mailer', iconKey: 'bulk' }
  }
  if (pathname.startsWith('/settings') || pathname === '/clients' || pathname === '/carrier') {
    const sub = settingsNavItems.find((i) => i.to === pathname)
    return { section: 'Settings', label: sub?.label ?? 'Settings', iconKey: sub?.iconKey ?? 'settings' }
  }
  return null
}
