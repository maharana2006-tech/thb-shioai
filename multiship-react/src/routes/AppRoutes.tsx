import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes, useParams } from 'react-router-dom'

/** Redirect the legacy `/settings/label-templates/:id` URL to the new
 *  `/settings/templates/:id`, preserving the id. Standalone helper because
 *  <Navigate> can't interpolate URL params on its own. */
function LegacyTemplateRedirect() {
  const { id } = useParams<{ id: string }>()
  return <Navigate to={`/settings/templates/${encodeURIComponent(id ?? '')}`} replace />
}

// Sprint 51 T6d (audit finding #15) — code-splitting.
//
// Pre-T6d every route page was statically imported, so the initial
// bundle shipped every operator's settings pages, every admin page,
// the label-template editor with @dnd-kit, the customs wizard with
// virtualization — for every user, even on /login. Bundle was
// 1.45MB minified / 370KB gzip.
//
// Kept eager (small + always-loaded on golden path):
//  - Login / Signup (unauthenticated entry)
//  - WorkspaceLayout / SettingsLayout (shell chrome)
//  - Protected/Anonymous/RequireRole guards (per-request)
//  - Dashboard, Orders (post-login default landing)
//  - LabelDocumentPage (frequent print flow)
//
// Everything else — settings, admin, editors, one-shot flows —
// splits to its own chunk. React.lazy + <Suspense> handles the
// async import; the fallback is a simple centered spinner so a
// slow network doesn't blank the screen.
import Login from '../components/Login'
import Signup from '../components/Signup'
import LabelDocumentPage from '../components/LabelDocumentPage'
import WorkspaceLayout from '../components/layout/WorkspaceLayout'
import SettingsLayout from '../components/layout/SettingsLayout'
import DashboardPage from '../pages/DashboardPage'
import OrdersPage from '../pages/OrdersPage'
import ProtectedRoute from './ProtectedRoute'
import AnonymousRoute from './AnonymousRoute'
import RequireRole from './RequireRole'
import { settingsPaths, workspacePaths } from './workspaceRoutes'

// Lazy — settings pages (loaded when operator navigates into Settings).
const CarrierPage = lazy(() => import('../pages/CarrierPage'))
const ClientsPage = lazy(() => import('../pages/ClientsPage'))
const ClientEditorPage = lazy(() => import('../components/ClientEditorPage'))
const ImporterBrokerPage = lazy(() => import('../components/ImporterBrokerPage'))
const LabelTemplatesListPage = lazy(() => import('../components/LabelTemplatesListPage'))
const LabelTemplateEditorPage = lazy(() => import('../components/LabelTemplateEditorPage'))
const CustomFieldsPage = lazy(() => import('../components/CustomFieldsPage'))
const RoutingRulesPage = lazy(() => import('../components/RoutingRulesPage'))
const ReportsPage = lazy(() => import('../components/ReportsPage'))
const WebhookSubscriptionsPage = lazy(() => import('../components/WebhookSubscriptionsPage'))
const ShippingCatalogPage = lazy(() => import('../components/ShippingCatalogPage'))
const ShippingServiceMappingPage = lazy(() => import('../components/ShippingServiceMappingPage'))
const WarehousesPage = lazy(() => import('../components/WarehousesPage'))
const CodeMapsPage = lazy(() => import('../components/CodeMapsPage'))
const AuditLogPage = lazy(() => import('../components/AuditLogPage'))
// Lazy — admin-only pages (small user set, loaded on demand).
const ApiKeysPage = lazy(() => import('../components/ApiKeysPage'))
const ApiReferencePage = lazy(() => import('../components/ApiReferencePage'))
const SystemSettingsPage = lazy(() => import('../components/SystemSettingsPage'))
const AdminUsersPage = lazy(() => import('../components/AdminUsersPage'))
// Lazy — one-shot flows (bulky but not on every page load).
const NewShipmentPage = lazy(() => import('../components/NewShipmentPage'))
const DataHistoryPage = lazy(() => import('../components/DataHistoryPage'))

/** Minimal centered spinner — the async chunk usually lands in
 *  <1s on a warm cache, so anything fancier flashes uselessly. */
function RouteSuspenseFallback() {
  return (
    <div className="flex min-h-[40vh] items-center justify-center">
      <span className="inline-block h-6 w-6 animate-spin rounded-full border-2 border-slate-200 border-t-slate-500" />
    </div>
  )
}

export default function AppRoutes() {
  return (
    <Suspense fallback={<RouteSuspenseFallback />}>
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
                  <Route path="users" element={<AdminUsersPage />} />
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
    </Suspense>
  )
}
