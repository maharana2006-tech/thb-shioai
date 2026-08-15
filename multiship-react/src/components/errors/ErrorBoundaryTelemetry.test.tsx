import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import AppErrorBoundary from './AppErrorBoundary'
import RouteErrorBoundary from './RouteErrorBoundary'

/**
 * Sprint 52 verification hardening — FE-M3 wiring test.
 *
 * <p>AppErrorBoundary + RouteErrorBoundary have their own render-fallback
 * coverage in AppErrorBoundary.test.tsx. What was NOT covered: proving
 * that when a child throws, the boundary's componentDidCatch actually
 * calls reportClientError so the crash reaches the backend telemetry
 * sink. This file closes that gap by mocking the errorReport module and
 * asserting the call fires with the componentStack info React passed in.
 */

const reportClientErrorMock = vi.fn()

vi.mock('../../utils/errorReport', () => ({
  reportClientError: (...args: unknown[]) => reportClientErrorMock(...args),
}))

function Boom({ msg = 'render blew up' }: { msg?: string }): never {
  throw new Error(msg)
}

describe('Error boundaries fire client telemetry on catch', () => {
  let consoleSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    reportClientErrorMock.mockClear()
    // React logs the caught error to console.error even when a boundary
    // handles it. Silence to keep test output readable.
    consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {})
  })

  afterEach(() => {
    consoleSpy.mockRestore()
  })

  it('AppErrorBoundary posts the error + componentStack via reportClientError', () => {
    render(
      <AppErrorBoundary>
        <Boom msg="app-level crash" />
      </AppErrorBoundary>,
    )
    // Fallback rendered — proves the boundary caught.
    expect(screen.getByRole('alert')).toBeInTheDocument()

    expect(reportClientErrorMock).toHaveBeenCalledTimes(1)
    const [err, info] = reportClientErrorMock.mock.calls[0]
    expect(err).toBeInstanceOf(Error)
    expect((err as Error).message).toBe('app-level crash')
    // React's ErrorInfo carries a componentStack string starting with a
    // newline. We only assert the info object HAS componentStack — the
    // exact contents are React-version-sensitive.
    expect(info).toHaveProperty('componentStack')
    expect(typeof (info as { componentStack: unknown }).componentStack)
      .toBe('string')
  })

  it('RouteErrorBoundary posts the error + componentStack via reportClientError', () => {
    render(
      <RouteErrorBoundary routeLabel="/orders">
        <Boom msg="route-level crash" />
      </RouteErrorBoundary>,
    )
    expect(screen.getByRole('alert')).toBeInTheDocument()

    expect(reportClientErrorMock).toHaveBeenCalledTimes(1)
    const [err, info] = reportClientErrorMock.mock.calls[0]
    expect(err).toBeInstanceOf(Error)
    expect((err as Error).message).toBe('route-level crash')
    expect(info).toHaveProperty('componentStack')
  })
})
