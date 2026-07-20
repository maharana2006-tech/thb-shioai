import OrdersWorkspace from '../components/OrdersWorkspace'
import TenantOrdersView from '../components/workspace/TenantOrdersView'
import { useAppSession } from '../hooks/useAppSession'
import { getTenantIdForUser, normalizeRole } from '../utils/roles'

export default function OrdersPage() {
  const { role, username } = useAppSession()
  const tenantId = getTenantIdForUser(normalizeRole(role), username)

  // TENANT users see only their own orders, read-only.
  if (tenantId) {
    return <TenantOrdersView tenantId={tenantId} />
  }

  return <OrdersWorkspace />
}
