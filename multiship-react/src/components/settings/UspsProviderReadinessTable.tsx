import { useCallback, useEffect, useState } from 'react'
import {
  FiAlertTriangle,
  FiCheckCircle,
  FiRefreshCw,
  FiXCircle,
} from 'react-icons/fi'
import {
  systemSettingsService,
  type UspsProviderReadiness,
} from '../../api/systemSettingsService'
import { notify } from '../../utils/notify'

/**
 * USPS_DIRECT provisioning readiness at a glance.
 *
 * <p>Rendered inline on {@code /settings/system} under the
 * {@code USPS_PROVIDER} choice whenever the site-wide setting is in the
 * {@code PROVISIONING_USPS_DIRECT} state. Answers the question "am I
 * safe to flip to USPS_DIRECT now?" without the operator having to
 * cross-reference every carrier account by hand.
 *
 * <p>Composition:
 *  - Header row: platform-level OAuth credentials (client id + client
 *    secret) — green tick if set, red cross if unset.
 *  - Body: one row per USPS carrier account that is still missing at
 *    least one USPS Direct field, with a pill list of what's missing.
 *  - Footer: {@code Ready X of Y accounts} + green/red overall badge.
 *  - Refresh button re-fetches the DTO.
 *
 * <p>Colors + table styling follow the existing
 * {@link ../workspace/AdvancedDataTable AdvancedDataTable} visual
 * language (slate borders, rounded 2xl, shadow-sm).
 */
export interface UspsProviderReadinessTableProps {
  /** Fires after a manual refresh with the fresh DTO — lets the parent
   *  page recompute its own "can I save USPS_DIRECT?" state without a
   *  separate fetch. */
  onLoaded?: (readiness: UspsProviderReadiness) => void
}

const MISSING_LABELS: Record<
  UspsProviderReadiness['pendingAccounts'][number]['missing'][number],
  string
> = {
  usps_direct_account_number: 'EPS account #',
  usps_direct_crid: 'CRID',
  usps_direct_mid: 'MID',
}

export default function UspsProviderReadinessTable({
  onLoaded,
}: UspsProviderReadinessTableProps) {
  const [readiness, setReadiness] = useState<UspsProviderReadiness | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      const response = await systemSettingsService.getUspsProviderReadiness()
      const data = response?.data ?? null
      setReadiness(data)
      if (data && onLoaded) onLoaded(data)
    } catch (e) {
      const msg =
        e instanceof Error ? e.message : 'Failed to load USPS provider readiness.'
      setError(msg)
      notify.apiError(e, 'Failed to load USPS provider readiness.')
    } finally {
      setLoading(false)
    }
  }, [onLoaded])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect -- initial fetch on mount; load() drives loading/error/readiness state
    void load()
  }, [load])

  if (loading && !readiness) {
    return (
      <div
        role="status"
        aria-live="polite"
        className="mt-3 rounded-xl border border-slate-200 bg-white p-4 text-[12.5px] text-slate-500"
      >
        Loading USPS provisioning readiness…
      </div>
    )
  }

  if (error && !readiness) {
    return (
      <div
        role="alert"
        className="mt-3 rounded-xl border border-rose-200 bg-rose-50 p-4 text-[12.5px] text-rose-700"
      >
        <div className="flex items-center justify-between gap-2">
          <span className="inline-flex items-center gap-1.5">
            <FiAlertTriangle className="h-3.5 w-3.5" /> {error}
          </span>
          <RefreshButton onClick={() => void load()} disabled={loading} />
        </div>
      </div>
    )
  }

  if (!readiness) return null

  const clientIdOk = readiness.platformCreds.clientIdSet
  const clientSecretOk = readiness.platformCreds.clientSecretSet
  const platformOk = clientIdOk && clientSecretOk

  return (
    <section
      aria-label="USPS provider readiness"
      className="mt-3 rounded-2xl border border-slate-200 bg-white p-4 shadow-sm"
    >
      {/* ===== Platform credentials header ===== */}
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-slate-100 pb-3">
        <div>
          <p className="text-[11px] font-bold uppercase tracking-[0.14em] text-slate-400">
            USPS Direct provisioning
          </p>
          <p className="mt-0.5 text-[12.5px] text-slate-600">
            Platform credentials + per-account fields must be populated before
            flipping the provider to <span className="font-mono">USPS_DIRECT</span>.
          </p>
        </div>
        <div className="flex items-center gap-2">
          <PlatformCredChip label="CLIENT_ID" ok={clientIdOk} />
          <PlatformCredChip label="CLIENT_SECRET" ok={clientSecretOk} />
          <RefreshButton onClick={() => void load()} disabled={loading} />
        </div>
      </div>

      {/* ===== Per-tenant pending list ===== */}
      {readiness.pendingAccounts.length === 0 ? (
        <p
          data-testid="usps-readiness-empty"
          className="mt-3 rounded-xl border border-emerald-200 bg-emerald-50 px-3 py-2 text-[12px] text-emerald-800"
        >
          Every USPS account has the USPS Direct fields set.
        </p>
      ) : (
        <div className="mt-3 overflow-x-auto">
          <table
            className="w-full border-separate border-spacing-0 text-left text-[12px]"
            aria-label="USPS accounts pending USPS Direct fields"
          >
            <thead>
              <tr className="text-[10.5px] font-bold uppercase tracking-[0.12em] text-slate-500">
                <th scope="col" className="border-b border-slate-100 px-2 py-2">
                  Tenant
                </th>
                <th scope="col" className="border-b border-slate-100 px-2 py-2">
                  Account #
                </th>
                <th scope="col" className="border-b border-slate-100 px-2 py-2">
                  Missing fields
                </th>
                <th scope="col" className="border-b border-slate-100 px-2 py-2">
                  Ready
                </th>
              </tr>
            </thead>
            <tbody>
              {readiness.pendingAccounts.map((row) => (
                <tr
                  key={`${row.tenantCode}::${row.accountNumber}`}
                  className="hover:bg-slate-50/50"
                >
                  <td className="border-b border-slate-100 px-2 py-2 font-mono text-[12px] text-slate-700">
                    {row.tenantCode}
                  </td>
                  <td className="border-b border-slate-100 px-2 py-2 font-mono text-[12px] text-slate-700">
                    {row.accountNumber}
                  </td>
                  <td className="border-b border-slate-100 px-2 py-2">
                    <span className="flex flex-wrap gap-1">
                      {row.missing.map((m) => (
                        <span
                          key={m}
                          className="inline-flex items-center rounded-full bg-amber-100 px-2 py-0.5 text-[10.5px] font-semibold text-amber-800"
                        >
                          {MISSING_LABELS[m] ?? m}
                        </span>
                      ))}
                    </span>
                  </td>
                  <td className="border-b border-slate-100 px-2 py-2">
                    <span
                      aria-label="Not ready"
                      className="inline-flex items-center gap-1 rounded-full bg-rose-100 px-2 py-0.5 text-[10.5px] font-semibold text-rose-700"
                    >
                      <FiXCircle className="h-3 w-3" /> Missing
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {/* ===== Footer with overall status ===== */}
      <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-slate-100 pt-3">
        <p className="text-[11.5px] text-slate-500">
          Ready {readiness.readyAccounts} of {readiness.totalUspsAccounts} USPS account
          {readiness.totalUspsAccounts === 1 ? '' : 's'}
        </p>
        {readiness.overallReady && platformOk ? (
          <span
            data-testid="usps-readiness-overall"
            className="inline-flex items-center gap-1 rounded-full bg-emerald-100 px-2.5 py-1 text-[11px] font-semibold text-emerald-800"
          >
            <FiCheckCircle className="h-3.5 w-3.5" /> Ready to switch to USPS_DIRECT
          </span>
        ) : (
          <span
            data-testid="usps-readiness-overall"
            className="inline-flex items-center gap-1 rounded-full bg-rose-100 px-2.5 py-1 text-[11px] font-semibold text-rose-700"
          >
            <FiAlertTriangle className="h-3.5 w-3.5" /> Not ready
          </span>
        )}
      </div>
    </section>
  )
}

function PlatformCredChip({ label, ok }: { label: string; ok: boolean }) {
  return (
    <span
      aria-label={`${label} ${ok ? 'set' : 'not set'}`}
      className={`inline-flex items-center gap-1 rounded-full px-2 py-0.5 font-mono text-[10.5px] font-semibold ${
        ok
          ? 'bg-emerald-100 text-emerald-800'
          : 'bg-rose-100 text-rose-700'
      }`}
    >
      {ok ? (
        <FiCheckCircle className="h-3 w-3" />
      ) : (
        <FiXCircle className="h-3 w-3" />
      )}
      {label}
    </span>
  )
}

function RefreshButton({ onClick, disabled }: { onClick: () => void; disabled: boolean }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={disabled}
      aria-label="Refresh USPS provider readiness"
      className="inline-flex items-center gap-1.5 rounded-lg border border-slate-200 bg-white px-2.5 py-1 text-[11px] font-semibold text-slate-600 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
    >
      <FiRefreshCw className={`h-3 w-3 ${disabled ? 'animate-spin' : ''}`} />
      Refresh
    </button>
  )
}
