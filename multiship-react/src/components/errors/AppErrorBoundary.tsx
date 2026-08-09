import { Component, type ErrorInfo, type ReactNode } from 'react'

/**
 * Sprint 49 Tier 4 Fix 1 — top-level ErrorBoundary.
 *
 * <p>Any thrown render error previously WSODed the entire app back
 * to a blank page — the console had the stack but the operator saw
 * nothing. This boundary catches the error, logs it, and renders a
 * fallback with a Reload action so the user isn't stranded.
 *
 * <p>React error boundaries must be class components (React 19 still
 * has no hook equivalent for {@code componentDidCatch}).
 */
interface AppErrorBoundaryProps {
  children: ReactNode
}

interface AppErrorBoundaryState {
  error: Error | null
}

export default class AppErrorBoundary extends Component<AppErrorBoundaryProps, AppErrorBoundaryState> {
  state: AppErrorBoundaryState = { error: null }

  static getDerivedStateFromError(error: Error): AppErrorBoundaryState {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Console.error is the ops-visible surface; if we later add a
    // client-side error reporter (Sentry / Rollbar), wire it here.
    console.error('[AppErrorBoundary] Unhandled render error', error, info)
  }

  private handleReload = () => {
    // Hard reload — clears any wedged in-memory state that led here.
    window.location.reload()
  }

  render() {
    if (this.state.error) {
      return (
        <div
          role="alert"
          className="flex min-h-screen flex-col items-center justify-center bg-slate-50 px-6 py-12"
        >
          <div className="w-full max-w-md rounded-2xl border border-slate-200 bg-white p-6 shadow-sm">
            <h1 className="text-lg font-semibold text-slate-950">Something went wrong.</h1>
            <p className="mt-1 text-[13px] text-slate-500">
              The app hit an unexpected error. Reload to recover; if it keeps happening,
              share the stack from your browser console with support.
            </p>
            <details className="mt-3 rounded-lg bg-slate-50 p-3 text-[11.5px] text-slate-600">
              <summary className="cursor-pointer font-medium">Error detail</summary>
              <pre className="mt-2 max-h-40 overflow-auto whitespace-pre-wrap break-words font-mono">
                {this.state.error.message}
              </pre>
            </details>
            <div className="mt-4 flex justify-end">
              <button
                type="button"
                onClick={this.handleReload}
                className="inline-flex items-center gap-1.5 rounded-lg border border-slate-900 bg-slate-900 px-3 py-1.5 text-[13px] font-semibold text-white transition hover:bg-slate-800"
              >
                Reload
              </button>
            </div>
          </div>
        </div>
      )
    }
    return this.props.children
  }
}
