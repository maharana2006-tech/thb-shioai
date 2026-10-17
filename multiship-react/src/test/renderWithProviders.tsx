import { type ReactElement } from 'react'
import { render, type RenderOptions } from '@testing-library/react'
import { BrowserRouter } from 'react-router-dom'

/**
 * Test render helper that wraps a component in a BrowserRouter so
 * useNavigate / useLocation resolve. Named for backward-compat with
 * the earlier Redux-era helper — the Redux Provider was dropped once
 * all production slices went unused.
 */
export function renderWithProviders(
  ui: ReactElement,
  renderOptions: Omit<RenderOptions, 'wrapper'> = {},
) {
  return render(<BrowserRouter>{ui}</BrowserRouter>, renderOptions)
}
