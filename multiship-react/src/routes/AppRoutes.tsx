import { Navigate, Route, Routes } from 'react-router-dom'

import Login from '../components/Login'
import Signup from '../components/Signup'
import LabelDocumentPage from '../components/LabelDocumentPage'
import WorkspaceLayout from '../components/layout/WorkspaceLayout'
import SettingsLayout from '../components/layout/SettingsLayout'
import CarrierPage from '../pages/CarrierPage'
import ClientsPage from '../pages/ClientsPage'
import ImporterBrokerPage from '../components/ImporterBrokerPage'
import ShippingServicesPage from '../components/ShippingServicesPage'
import PackagesPage from '../components/PackagesPage'
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
              <Route path="shipping-services" element={<ShippingServicesPage />} />
              <Route path="packages" element={<PackagesPage />} />
              <Route path="importer-broker" element={<ImporterBrokerPage />} />
              <Route element={<RequireRole roles={['ADMIN']} />}>
                <Route path="carriers" element={<CarrierPage />} />
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
