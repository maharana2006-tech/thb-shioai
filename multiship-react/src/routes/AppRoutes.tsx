import { Navigate, Route, Routes, useParams } from 'react-router-dom'

/** Redirect the legacy `/settings/label-templates/:id` URL to the new
 *  `/settings/templates/:id`, preserving the id. Standalone helper because
 *  <Navigate> can't interpolate URL params on its own. */
function LegacyTemplateRedirect() {
  const { id } = useParams<{ id: string }>()
  return <Navigate to={`/settings/templates/${encodeURIComponent(id ?? '')}`} replace />
}

import Login from '../components/Login'
import Signup from '../components/Signup'
import LabelDocumentPage from '../components/LabelDocumentPage'
import WorkspaceLayout from '../components/layout/WorkspaceLayout'
import SettingsLayout from '../components/layout/SettingsLayout'
import CarrierPage from '../pages/CarrierPage'
import ClientsPage from '../pages/ClientsPage'
import ClientEditorPage from '../components/ClientEditorPage'
import ImporterBrokerPage from '../components/ImporterBrokerPage'
import LabelTemplatesListPage from '../components/LabelTemplatesListPage'
import LabelTemplateEditorPage from '../components/LabelTemplateEditorPage'
import CustomFieldsPage from '../components/CustomFieldsPage'
import RoutingRulesPage from '../components/RoutingRulesPage'
import ReportsPage from '../components/ReportsPage'
import WebhookSubscriptionsPage from '../components/WebhookSubscriptionsPage'
import ShippingCatalogPage from '../components/ShippingCatalogPage'
import ShippingServiceMappingPage from '../components/ShippingServiceMappingPage'
import ApiKeysPage from '../components/ApiKeysPage'
import ApiReferencePage from '../components/ApiReferencePage'
import SystemSettingsPage from '../components/SystemSettingsPage'
import WarehousesPage from '../components/WarehousesPage'
import CodeMapsPage from '../components/CodeMapsPage'
import AuditLogPage from '../components/AuditLogPage'
import DashboardPage from '../pages/DashboardPage'
import OrdersPage from '../pages/OrdersPage'
import NewShipmentPage from '../components/NewShipmentPage'
import DataHistoryPage from '../components/DataHistoryPage'
import ProtectedRoute from './ProtectedRoute'
import AnonymousRoute from './AnonymousRoute'
import RequireRole from './RequireRole'
import { settingsPaths, workspacePaths } from './workspaceRoutes'

export default function AppRoutes() {
  return (
    <Routes>
      <Route element={<AnonymousRoute />}>
        <Route path="/" element={<Login />} />
        <Route path="/login" element={<Login />} />
        <Route path="/signup" element={<Signup />} />
      </Route>

      <Route element={<ProtectedRoute />}>
        <Route element={<WorkspaceLayout />}>
          <Route element={<RequireRole roles={['ADMIN', 'USER']} />}>
            <Route path={workspacePaths.dashboard} element={<DashboardPage />} />

            {/* Settings hub — master data (clients / carriers / broker-importer) */}
            <Route path={workspacePaths.settings} element={<SettingsLayout />}>
              <Route index element={<Navigate to={settingsPaths.clients} replace />} />
              <Route path="clients" element={<ClientsPage />} />
              <Route path="clients/new" element={<ClientEditorPage />} />
              <Route path="clients/:clientCode" element={<ClientEditorPage />} />
              <Route path="warehouses" element={<WarehousesPage />} />
              {/* Shipping catalog — merges the old shipping-services and
                  packages pages into a single tabbed UI. Old routes redirect
                  to the corresponding tab so bookmarks + external links
                  keep working. */}
              <Route path="shipping-catalog" element={<ShippingCatalogPage />} />
              <Route path="shipping-services" element={<Navigate to="/settings/shipping-catalog?tab=services" replace />} />
              <Route path="packages" element={<Navigate to="/settings/shipping-catalog?tab=packages" replace />} />
              <Route path="shipping-service-mapping" element={<ShippingServiceMappingPage />} />
              <Route path="importer-broker" element={<ImporterBrokerPage />} />
              {/* Templates — shipping label / packing slip / commercial invoice.
                  Old label-templates URLs redirect for bookmark compatibility. */}
              <Route path="templates" element={<LabelTemplatesListPage />} />
              <Route path="templates/new" element={<LabelTemplateEditorPage />} />
              <Route path="templates/:id" element={<LabelTemplateEditorPage />} />
              <Route path="label-templates" element={<Navigate to="/settings/templates" replace />} />
              <Route path="label-templates/new" element={<Navigate to="/settings/templates/new" replace />} />
              <Route path="label-templates/:id" element={<LegacyTemplateRedirect />} />
              <Route path="custom-fields" element={<CustomFieldsPage />} />
              <Route path="routing-rules" element={<RoutingRulesPage />} />
              <Route path="reports" element={<ReportsPage />} />
              <Route path="webhook-subscriptions" element={<WebhookSubscriptionsPage />} />
              <Route path="code-maps" element={<CodeMapsPage />} />
              {/* Carriers page: reads (list, filter, verify) accept USER;
                  writes (connect/disconnect) are gated to ADMIN at the
                  backend @PreAuthorize. Route-level RequireRole here would
                  have redirected USER role to /dashboard even though the
                  sidebar shows the entry. */}
              <Route path="carriers" element={<CarrierPage />} />
              <Route path="audit-log" element={<AuditLogPage />} />
              <Route element={<RequireRole roles={['ADMIN']} />}>
                <Route path="api-keys" element={<ApiKeysPage />} />
                <Route path="api-reference" element={<ApiReferencePage />} />
                <Route path="system" element={<SystemSettingsPage />} />
              </Route>
            </Route>
          </Route>

          <Route path={workspacePaths.orders} element={<OrdersPage />} />
          <Route path="/orders/new" element={<NewShipmentPage />} />
          <Route path="/orders/history" element={<DataHistoryPage />} />

          <Route path="/label/:orderNo" element={<LabelDocumentPage />} />

          {/* legacy path redirects into the Settings hub */}
          <Route path="/clients" element={<Navigate to={settingsPaths.clients} replace />} />
          <Route path="/carrier" element={<Navigate to={settingsPaths.carriers} replace />} />
          <Route path="/carrier-connections" element={<Navigate to={settingsPaths.carriers} replace />} />
          <Route path="/labels" element={<Navigate to={workspacePaths.orders} replace />} />
          <Route path="/generate-labels" element={<Navigate to={workspacePaths.orders} replace />} />
          <Route path="/track-orders" element={<Navigate to={workspacePaths.orders} replace />} />
        </Route>
      </Route>

      <Route path="*" element={<Navigate to="/login" replace />} />
    </Routes>
  )
}
