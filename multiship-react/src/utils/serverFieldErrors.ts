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
