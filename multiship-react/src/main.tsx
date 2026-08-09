import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { Provider } from 'react-redux'
import './App.css'
import App from './App.tsx'
import { store } from './store/store'
import AppErrorBoundary from './components/errors/AppErrorBoundary'

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

