import { type ReactElement } from 'react'
import { render, type RenderOptions } from '@testing-library/react'
import { Provider } from 'react-redux'
import { BrowserRouter } from 'react-router-dom'
import { combineReducers, configureStore } from '@reduxjs/toolkit'
import carrierReducer from '../store/carrierSlice'
import orderReducer from '../store/orderSlice'

/**
 * Sprint 49 Tier 5 Fix 6/7 — test render helper.
 *
 * <p>Big page-level components depend on a Redux Provider + a
 * react-router context. This helper wraps the component under test
 * with a fresh isolated store (no cross-test bleed) and a
 * BrowserRouter so useNavigate / useLocation resolve.
 *
 * <p>Callers can supply an initial store state via {@code preloadedState}
 * to seed slice data for the render.
 */
export function renderWithProviders(
  ui: ReactElement,
  {
    preloadedState,
    ...renderOptions
  }: { preloadedState?: Record<string, unknown> } & Omit<RenderOptions, 'wrapper'> = {},
) {
  const rootReducer = combineReducers({
    carriers: carrierReducer,
    orders: orderReducer,
  })
  const store = configureStore({
    reducer: rootReducer,
    preloadedState: preloadedState as never,
    middleware: (getDefaultMiddleware) =>
      getDefaultMiddleware({ serializableCheck: false }),
  })

  return {
    store,
    ...render(
      <Provider store={store}>
        <BrowserRouter>{ui}</BrowserRouter>
      </Provider>,
      renderOptions,
    ),
  }
}
