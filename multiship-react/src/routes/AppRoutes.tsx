import { Navigate, Route, Routes } from 'react-router-dom'

import Login from '../components/Login'
import Signup from '../components/Signup'
import LabelDocumentPage from '../components/LabelDocumentPage'
import WorkspaceLayout from '../components/layout/WorkspaceLayout'
import SettingsLayout from '../components/layout/SettingsLayout'
import CarrierPage from '../pages/CarrierPage'
import ClientsPage from '../pages/ClientsPage'
import ImporterBrokerPage from '../components/ImporterBrokerPage'
import LabelTemplatesPage from '../components/LabelTemplatesPage'
import CustomFieldsPage from '../components/CustomFieldsPage'
import RoutingRulesPage from '../components/RoutingRulesPage'
import ReportsPage from '../components/ReportsPage'
import WebhookSubscriptionsPage from '../components/WebhookSubscriptionsPage'
import ShippingServicesPage from '../components/ShippingServicesPage'
import ShippingServiceMappingPage from '../components/ShippingServiceMappingPage'
import ApiKeysPage from '../components/ApiKeysPage'
import ApiReferencePage from '../components/ApiReferencePage'
import PackagesPage from '../components/PackagesPage'
import WarehousesPage from '../components/WarehousesPage'
import CodeMapsPage from '../components/CodeMapsPage'
import DashboardPage from '../pages/DashboardPage'
import OrdersPage from '../pages/OrdersPage'
import NewShipmentPage from '../components/NewShipmentPage'
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
              <Route path="warehouses" element={<WarehousesPage />} />
              <Route path="shipping-services" element={<ShippingServicesPage />} />
              <Route path="shipping-service-mapping" element={<ShippingServiceMappingPage />} />
              <Route path="packages" element={<PackagesPage />} />
              <Route path="importer-broker" element={<ImporterBrokerPage />} />
              <Route path="label-templates" element={<LabelTemplatesPage />} />
              <Route path="custom-fields" element={<CustomFieldsPage />} />
              <Route path="routing-rules" element={<RoutingRulesPage />} />
              <Route path="reports" element={<ReportsPage />} />
              <Route path="webhook-subscriptions" element={<WebhookSubscriptionsPage />} />
              <Route path="code-maps" element={<CodeMapsPage />} />
              {/* Carriers page: reads (list, filter, verify) accept USER;
                  writes (connect/disconnect) are gated to ADMIN at the
                  backend @PreAuthorize. Route-level RequireRole here would
                  have redirected USER role to /dashboard even though the
                  sidebar shows the entry — user-reported bug fix. */}
              <Route path="carriers" element={<CarrierPage />} />
              <Route element={<RequireRole roles={['ADMIN']} />}>
                <Route path="api-keys" element={<ApiKeysPage />} />
                <Route path="api-reference" element={<ApiReferencePage />} />
              </Route>
            </Route>
          </Route>

          <Route path={workspacePaths.orders} element={<OrdersPage />} />
          <Route path="/orders/new" element={<NewShipmentPage />} />

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
