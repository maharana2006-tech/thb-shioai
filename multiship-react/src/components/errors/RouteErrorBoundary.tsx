import { Component, type ErrorInfo, type ReactNode } from 'react'
import { reportClientError } from '../../utils/errorReport'

/**
 * Sprint 49 Tier 4 Fix 1 — route-scoped boundary.
 *
 * <p>Applied inside each top-level route so a broken /orders page
 * doesn't take down the sidebar + topbar around it. The operator can
 * still navigate elsewhere; only the crashed pane shows a fallback.
 *
 * <p>The outer {@code AppErrorBoundary} still catches anything a
 * route boundary re-throws or fails to catch itself.
 */
interface RouteErrorBoundaryProps {
  children: ReactNode
  /** Human-readable name for the current route — appears in the fallback. */
  routeLabel?: string
}

interface RouteErrorBoundaryState {
  error: Error | null
}

export default class RouteErrorBoundary extends Component<RouteErrorBoundaryProps, RouteErrorBoundaryState> {
  state: RouteErrorBoundaryState = { error: null }

  static getDerivedStateFromError(error: Error): RouteErrorBoundaryState {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[RouteErrorBoundary]', this.props.routeLabel ?? 'route', error, info)
    // Sprint 51 FE-M3 — ship the render error to the backend telemetry
    // sink so ops sees it, not just the operator's console.
    reportClientError(error, { componentStack: info.componentStack })
  }

  private handleRetry = () => {
    this.setState({ error: null })
  }

  render() {
    if (this.state.error) {
      return (
        <div role="alert" className="m-4 rounded-2xl border border-rose-200 bg-rose-50 p-6">
          <h2 className="text-[15px] font-semibold text-rose-900">
            {this.props.routeLabel ? `${this.props.routeLabel} — ` : ''}couldn't render
          </h2>
          <p className="mt-1 text-[12.5px] text-rose-800">
            The rest of the app is still available; use the sidebar to navigate away.
          </p>
          <details className="mt-3 rounded-lg bg-white/60 p-3 text-[11.5px] text-rose-800">
            <summary className="cursor-pointer font-medium">Error detail</summary>
            <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap break-words font-mono">
              {this.state.error.message}
            </pre>
          </details>
          <div className="mt-4">
            <button
              type="button"
              onClick={this.handleRetry}
              className="inline-flex items-center gap-1.5 rounded-lg border border-rose-900 bg-rose-900 px-3 py-1.5 text-[12.5px] font-semibold text-white transition hover:bg-rose-800"
            >
              Try again
            </button>
          </div>
        </div>
      )
    }
    return this.props.children
  }
}
