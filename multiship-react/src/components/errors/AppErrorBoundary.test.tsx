import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import AppErrorBoundary from './AppErrorBoundary'
import RouteErrorBoundary from './RouteErrorBoundary'

/**
 * Sprint 49 Tier 4 Fix 1 — error boundary contract.
 *
 * <p>React logs uncaught errors to console.error during render, which
 * pollutes vitest output. Silence it just for these tests so the
 * failure signal remains visible.
 */
function Boom(): never {
  throw new Error('boom test error')
}

describe('AppErrorBoundary', () => {
  it('renders children when no error thrown', () => {
    render(
      <AppErrorBoundary>
        <p>ok content</p>
      </AppErrorBoundary>,
    )
    expect(screen.getByText('ok content')).toBeInTheDocument()
  })

  it('renders fallback + Reload button when a child throws', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      render(
        <AppErrorBoundary>
          <Boom />
        </AppErrorBoundary>,
      )
      expect(screen.getByRole('alert')).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /reload/i })).toBeInTheDocument()
      expect(screen.getByText(/something went wrong/i)).toBeInTheDocument()
    } finally {
      spy.mockRestore()
    }
  })
})

describe('RouteErrorBoundary', () => {
  it('renders scoped fallback with the route label', () => {
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {})
    try {
      render(
        <RouteErrorBoundary routeLabel="/orders">
          <Boom />
        </RouteErrorBoundary>,
      )
      expect(screen.getByRole('alert')).toBeInTheDocument()
      expect(screen.getByText(/\/orders/)).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /try again/i })).toBeInTheDocument()
    } finally {
      spy.mockRestore()
    }
  })
})
