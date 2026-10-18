import OrdersWorkspace from '../components/OrdersWorkspace'
import TenantOrdersView from '../components/workspace/TenantOrdersView'
import { useAppSession } from '../hooks/useAppSession'
import { normalizeRole } from '../utils/roles'

export default function OrdersPage() {
  const { role } = useAppSession()

  // R4-BUG-003 — pre-fix we derived a "tenantId" by upper-casing the
  // username and passed it as a filter. For an invite-time TENANT user
  // whose user.client_code != username (every real tenant), the filter
  // 403'd and the page showed "No orders found". The backend's
  // TenantScopeEnforcer.clampClientCode already fills in the caller's
  // own tenant on list calls that omit tenantId, so route on role
  // alone and let the server clamp.
  if (normalizeRole(role) === 'TENANT') {
    return <TenantOrdersView />
  }

  return <OrdersWorkspace />
}
