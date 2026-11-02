/**
 * Phase 3 — turn server-named issues ({field, message}) into the form's
 * field-error map. A bare block name ("sender") lands on that block's first
 * field so it renders where the operator looks; nested paths pass through.
 */
export interface ServerIssue {
  field?: string | null
  message?: string | null
  code?: string | null
}

export function fieldErrorsFromIssues(issues: ServerIssue[] | null | undefined): Record<string, string> {
  const out: Record<string, string> = {}
  for (const issue of issues ?? []) {
    const field = issue?.field?.trim()
    const message = issue?.message?.trim()
    if (!field || !message) continue
    const path = field === 'sender' || field === 'recipient' ? `${field}.addressLine1` : field
    if (!out[path]) out[path] = message
  }
  return out
}

/** BUG-194 — single-field server error ({ field, code, message }) carried in `ApiError.payload.errors`. */
export function serverFieldError(err: unknown): { field: string; message: string } | null {
  const errors = (err as { payload?: { errors?: unknown } } | null)?.payload?.errors
  if (!errors || typeof errors !== 'object' || Array.isArray(errors)) return null
  const { field, message } = errors as { field?: unknown; message?: unknown }
  if (typeof field !== 'string' || typeof message !== 'string') return null
  const f = field.trim()
  const m = message.trim()
  return f && m ? { field: f, message: m } : null
}
