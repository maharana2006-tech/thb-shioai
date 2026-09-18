import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { Provider } from 'react-redux'
import './App.css'
import App from './App.tsx'
import { store } from './store/store'
import AppErrorBoundary from './components/errors/AppErrorBoundary'
import { reportClientError } from './utils/errorReport'

// PR-F1 (audit FE-ERR-12) — async errors in event handlers (onClick
// handlers that call an unhandled promise, Redux thunks that reject
// without a catch, fetch() rejections outside try/catch) never reach
// React's render tree, so the AppErrorBoundary + RouteErrorBoundary
// pair below never see them. Wire the browser's global
// unhandledrejection event to reportClientError so those escaped
// failures still land on the ops telemetry sink instead of the
// operator's DevTools console.
if (typeof window !== 'undefined') {
  window.addEventListener('unhandledrejection', (event) => {
    const reason = event.reason
    // Prepend a marker so the server-side log line surfaces the origin
    // (event handler async / Redux thunk / fetch outside try/catch);
    // the componentStack slot on the ClientErrorDTO is the only free
    // string field, so we tag it there.
    const err = reason instanceof Error
      ? reason
      : new Error(typeof reason === 'string' ? reason : 'Unhandled promise rejection')
    try {
      reportClientError(err, {
        componentStack: '[unhandledrejection] ' + (err.stack ?? err.message ?? '').slice(0, 200),
      })
    } catch {
      // Never let the telemetry sink itself blow up on the reporter's error path.
    }
  })
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {/* Sprint 49 Tier 4 Fix 1 — catches any render error the router or a
        route-level boundary re-throws, so the operator sees a reload
        prompt instead of a blank white page. */}
    <AppErrorBoundary>
      <Provider store={store}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </Provider>
    </AppErrorBoundary>
  </StrictMode>,
)

