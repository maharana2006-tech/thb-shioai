/**
 * Central mapping from backend `errorCode` values to user-friendly UI text.
 *
 * Backend responses use the ApiResponse shape:
 *   { status: 'ERROR', code: 4xx, errorCode: 'STABLE_CODE', message: '...' }
 *
 * Historically each component branched on `errorCode` inline (or worse, showed
 * the raw `message` — often stack-adjacent text). This map + `notifyApiError`
 * gives every catch block a single opinionated place to look up friendly text.
 *
 * Adding a new backend errorCode? Add an entry here so the UI has a message
 * before an integrator surfaces it. Unknown codes fall through to the raw
 * server `message` (or a generic fallback).
 */
export interface FriendlyError {
  /** Optional modal title. Falls back to the notify modal's own default. */
  title?: string
  /** Body text shown to the user. Written in second person, no stack terms. */
  message: string
}

export const errorMessages: Readonly<Record<string, FriendlyError>> = {
  // ===== Rate limits (Sprint 50 Tier 1 finding #15) =====
  API_KEY_RATE_LIMITED: {
    title: 'Rate limit reached',
    message:
      'This API key has hit its per-minute request limit. Please retry in a few seconds. Consider batching requests or requesting a higher limit if this recurs.',
  },
  TENANT_RATE_LIMITED: {
    title: 'Rate limit reached',
    message:
      'Your account exceeded the internal request limit. Please pause briefly and retry.',
  },
  CARRIER_RATE_LIMITED: {
    title: 'Carrier is rate-limiting us',
    message:
      'The carrier throttled the request. This is transient — retry the label after a few seconds. If it persists, contact support.',
  },

  // ===== Auth (Sprint 50 Tier 0.5 D + E) =====
  EMAIL_NOT_VERIFIED: {
    title: 'Email not verified',
    message:
      'Check your inbox for the verification link before signing in. If you can’t find it, ask an admin to resend the invite.',
  },
  ACCOUNT_DEACTIVATED: {
    title: 'Account deactivated',
    message:
      'An admin has revoked this account. Contact your administrator if you believe this is a mistake.',
  },
  CROSS_TENANT_ACCESS_DENIED: {
    title: 'Not permitted',
    message:
      'This action targets a client outside your scope. If you need access to that client, ask an admin.',
  },

  // ===== Idempotency (Sprint 50 Tier 1-C) =====
  IDEMPOTENCY_IN_PROGRESS: {
    title: 'Request in progress',
    message:
      'A prior request with the same idempotency key is still running. Retry in a few seconds.',
  },
  IDEMPOTENCY_UNAVAILABLE: {
    title: 'Deduplication temporarily unavailable',
    message:
      'The idempotency store is unreachable. To prevent duplicate charges, this request was refused. Retry shortly.',
  },

  // ===== Label / carrier generation (Sprint 50 Tier 1-A) =====
  MARKUP_REQUIRED_FOR_CLIENT: {
    title: 'Client billing markup missing',
    message:
      'This client has no billing markup configured. Set one on the Clients page before generating labels.',
  },
  UNIT_REQUIRED: {
    title: 'Weight unit required',
    message:
      'Parcel weight must include a unit (LB, KG, OZ, G). Either send it in the request or configure the client’s default weight unit on the Clients page.',
  },
  CURRENCY_REQUIRED: {
    title: 'Currency required',
    message:
      'Declared value needs a currency (USD, EUR, GBP…). Either send it in the request or configure the client’s default currency on the Clients page.',
  },
  CLIENT_CARRIER_AUTH_FAILED: {
    title: 'Carrier credentials rejected',
    message:
      'The client’s carrier account credentials were rejected. Verify the account on the Clients page. If you want to fall back to the platform account, resubmit with useHouseAccount=true.',
  },

  // ===== API key lifecycle (Sprint 50 Tier 0.5 B + C) =====
  API_KEY_EXPIRED: {
    title: 'API key expired',
    message:
      'This API key has expired. Rotate it on the API Keys page to get a fresh 90-day credential.',
  },
  INSUFFICIENT_SCOPE: {
    title: 'API key missing scope',
    message:
      'This API key isn’t authorised for the requested endpoint. Ask an admin to grant the needed scope or reissue the key.',
  },

  // ===== Invites (Sprint 50 Tier 0.5 D) =====
  /** Audit I7 — distinguish typo / mangled URL (unknown token) from
   *  expired (410) and consumed (409). Pre-fix, all three fell to the
   *  generic default so invitees couldn't tell if their URL was broken
   *  vs. genuinely stale. */
  INVITE_NOT_FOUND: {
    title: 'Invite link not recognised',
    message:
      'The invite URL looks incomplete or wrong. Check that you pasted the full link from the email — or ask an admin to resend the invite.',
  },
  INVITE_EXPIRED: {
    title: 'Invite expired',
    message:
      'This invite link has expired. Ask an admin to send you a fresh one.',
  },
  INVITE_ALREADY_USED: {
    title: 'Invite already used',
    message:
      'This invite was already accepted. Sign in with the account it created — or ask an admin for a new invite if you need a different account.',
  },

  // ===== Email verification (Sprint 50 D + audit B2) =====
  /** Audit B2 (verify-email) — canonical codes for the click-through
   *  email-verify flow. Pre-fix, verifyEmail reused INVITE_NOT_FOUND /
   *  INVITE_EXPIRED — semantically wrong; API clients + logs couldn't
   *  distinguish invite-accept failure from email-verify failure. */
  EMAIL_VERIFY_TOKEN_NOT_FOUND: {
    title: 'Verification link not recognised',
    message:
      'This verification URL looks incomplete or wrong. Open the link straight from the verification email — copy/paste sometimes drops the token.',
  },
  EMAIL_VERIFY_TOKEN_EXPIRED: {
    title: 'Verification link expired',
    message:
      'The 24-hour window on this verification link has passed. Ask an admin to resend, or sign up again if public signup is enabled for your environment.',
  },

  // ===== Admin surface (Sprint 50 Tier 0.5 E) =====
  ADMIN_TARGET_USER_NOT_FOUND: {
    title: 'User not found',
    message:
      'The user you tried to update doesn’t exist any more. Refresh the users list and try again.',
  },
  ADMIN_TARGET_CLIENT_NOT_FOUND: {
    title: 'Client not found',
    message:
      'The client you tried to assign doesn’t exist. Refresh the client dropdown and try again.',
  },

  // ===== Pre-Sprint-50 codes surfaced during PR J migration =====
  // Motivated by ClientsPage.tsx (toggle-active + delete race).
  CLIENT_HAS_ORDERS: {
    title: 'Client has pending orders',
    message:
      'This client still has pending orders. Complete or void them before deactivating or deleting the client.',
  },
  // Motivated by modals/ClientAllowlistTab.tsx (submit picker).
  ALLOWLIST_ALREADY_EXISTS: {
    title: 'Already on the allowlist',
    message: 'That entry is already on the allowlist.',
  },
  // Motivated by modals/ClientPolicyTab.tsx (save policy).
  POLICY_FIXED_SERVICE_REQUIRED: {
    title: 'Fixed service required',
    message: 'Pick a fixed service on the client allowlist before saving the policy.',
  },
}

/**
 * Resolve an errorCode to friendly text. Falls back to the raw server message
 * (if any) and then to a generic sentence. Never returns empty.
 *
 * Kept pure so components can call it directly when they need the message
 * for inline UI (form errors, badge tooltips) rather than a modal.
 */
export function getFriendlyError(
  errorCode: string | null | undefined,
  serverMessage?: string | null,
  fallback: string = 'Something went wrong. Please try again or contact support if it persists.',
): FriendlyError {
  if (errorCode && errorMessages[errorCode]) {
    return errorMessages[errorCode]
  }
  if (serverMessage && serverMessage.trim().length > 0) {
    return { message: serverMessage }
  }
  return { message: fallback }
}
