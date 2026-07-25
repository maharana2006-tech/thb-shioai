/**
 * The backend's IntlShipmentValidator.toMessage() emits a specific format:
 *
 *   International shipment failed validation:
 *    • Commodity line 1 missing: description, HS code.
 *    • Invoice total must be greater than zero.
 *
 * That reaches us as a single string in the ApiError message. Displaying it
 * unmodified would collapse the bullets and drop the visual hierarchy — this
 * helper detects the sentinel prefix and returns a structured
 * {title, body} that we hand straight to notify.error, which renders the
 * body with preserved line breaks in the modal.
 *
 * <p>Returns null when the message isn't a validator emission — callers fall
 * back to their normal toast in that case.
 */
export interface ParsedIntlValidationError {
  title: string
  body: string
  bullets: string[]
}

const SENTINEL_PREFIX = 'International shipment failed validation:'

export function parseIntlValidationMessage(raw: string | undefined | null): ParsedIntlValidationError | null {
  if (!raw) return null
  const trimmed = raw.trim()
  if (!trimmed.startsWith(SENTINEL_PREFIX)) return null

  // Backend concatenates with "\n • " between bullets.
  const bullets = trimmed
    .substring(SENTINEL_PREFIX.length)
    .split(/\r?\n/)
    .map((line) => line.trim().replace(/^•\s*/, ''))
    .filter((line) => line.length > 0)

  return {
    title: 'Customs data incomplete',
    body: bullets.length
      ? bullets.map((b) => `• ${b}`).join('\n')
      : 'The customs declaration is missing required fields.',
    bullets,
  }
}
