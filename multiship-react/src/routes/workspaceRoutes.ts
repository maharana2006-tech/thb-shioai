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
  carriers: '/settings/carriers',
  shippingServices: '/settings/shipping-services',
  shippingServiceMapping: '/settings/shipping-service-mapping',
  packages: '/settings/packages',
  importerBroker: '/settings/importer-broker',
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
  { key: 'carriers', label: 'Carrier Accounts', to: settingsPaths.carriers, iconKey: 'carrier',
    description: 'Connect + verify live UPS / FedEx / USPS accounts (platform + client).',
    roles: ['ADMIN'] },
  { key: 'shipping-services', label: 'Shipping Services', to: settingsPaths.shippingServices, iconKey: 'service',
    description: "The carrier service catalog per origin, and each service's allowed packages.",
    roles: ['ADMIN', 'USER'] },
  { key: 'shipping-service-mapping', label: 'Shipping Service Mapping', to: settingsPaths.shippingServiceMapping, iconKey: 'mapping',
    description: "How order ship-methods resolve to a carrier service — most specific mapping wins.",
    roles: ['ADMIN', 'USER'] },
  { key: 'packages', label: 'Packages', to: settingsPaths.packages, iconKey: 'package',
    description: "Your own boxes plus each carrier's predefined packaging per ship-from country.",
    roles: ['ADMIN', 'USER'] },
  { key: 'importer-broker', label: 'Importer / Broker', to: settingsPaths.importerBroker, iconKey: 'customs',
    description: 'Customs identities — importer/broker profiles applied per destination country.',
    roles: ['ADMIN', 'USER'] },
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
  if (pathname === workspacePaths.orders || pathname === '/track-orders' || pathname.startsWith('/label/')) {
    return { section: 'Operations', label: 'Orders & Labels', iconKey: 'orders' }
  }
  if (pathname.startsWith('/settings') || pathname === '/clients' || pathname === '/carrier') {
    const sub = settingsNavItems.find((i) => i.to === pathname)
    return { section: 'Settings', label: sub?.label ?? 'Settings', iconKey: sub?.iconKey ?? 'settings' }
  }
  return null
}
