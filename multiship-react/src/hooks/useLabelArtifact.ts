import { useCallback, useEffect, useState } from 'react'
import { labelService, type LabelArtifact } from '../api/labelService'

/**
 * Phase 2 (Shipment & Label re-architecture) — the label screen's one
 * state object. Replaces the 16 useState hooks and three competing
 * preview drawings of the old page with a single discriminated union:
 * the page can only ever be in one of these four states.
 */
export type LabelView =
  | { status: 'loading' }
  | { status: 'empty'; reason: 'not-generated'; artifact: LabelArtifact }
  | { status: 'ready'; artifact: LabelArtifact; activeSeq: number }
  | { status: 'error'; message: string }

export function useLabelArtifact(orderNo: number | null) {
  const [artifact, setArtifact] = useState<LabelArtifact | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [activeSeq, setActiveSeq] = useState(1)
  const [version, setVersion] = useState(0)

  useEffect(() => {
    if (orderNo === null || !Number.isFinite(orderNo)) {
      // eslint-disable-next-line react-hooks/set-state-in-effect -- surface the invalid-URL state before any fetch
      setError('Invalid order number.')
      setLoading(false)
      return
    }
    let cancelled = false
    setLoading(true)
    setError(null)
    labelService
      .describe(orderNo)
      .then((response) => {
        if (cancelled) return
        setArtifact(response.data)
        setActiveSeq(1)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setError(err instanceof Error ? err.message : 'Failed to load the label.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [orderNo, version])

  const reload = useCallback(() => setVersion((v) => v + 1), [])

  let view: LabelView
  if (loading) {
    view = { status: 'loading' }
  } else if (error !== null || artifact === null) {
    view = { status: 'error', message: error ?? 'Failed to load the label.' }
  } else if (artifact.packages.length === 0) {
    view = { status: 'empty', reason: 'not-generated', artifact }
  } else {
    const seqs = artifact.packages.map((p) => p.seq)
    view = { status: 'ready', artifact, activeSeq: seqs.includes(activeSeq) ? activeSeq : seqs[0] }
  }

  return { view, setActiveSeq, reload }
}
