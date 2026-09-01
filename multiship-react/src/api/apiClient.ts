// Sprint 51 FE-M1 — no dev-hostname:8080 fallback in shipped code.
// Prod builds must set VITE_API_BASE_URL (asserted in vite.config.ts).
// In dev, Vite's proxy (or the same origin) serves /api/v1 directly, so
// a relative default keeps localhost and LAN access working without
// baking assumptions about the backend port into the bundle.
export const BASE_URL =
  (import.meta.env?.VITE_API_BASE_URL as string | undefined) || '/api/v1';

/**
 * Error thrown for non-2xx responses. Carries the HTTP status so callers
 * branch on the code (422 needs-details, 409 already-generated, ...) and the
 * parsed body so structured payloads (e.g. prefill fields) survive the throw.
 */
export class ApiError extends Error {
  status: number;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- payload is an untyped server-provided JSON blob consumed by 18+ call sites (`.message`, `.data`, `.errors[]`, ...); forcing `unknown` here would require narrowing at every access with no runtime benefit
  payload: any;
  /** Stable machine-readable code from the backend (e.g. 'NEEDS_CARRIER_DETAILS', 'LABEL_ALREADY_GENERATED'); null if absent. */
  errorCode: string | null;

  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- see field comment above
  constructor(message: string, status: number, payload: any) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.payload = payload;
    this.errorCode = typeof payload?.errorCode === 'string' ? payload.errorCode : null;
  }
}

interface FetchOptions extends RequestInit {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- caller-provided JSON body; `unknown` would break every apiClient.post/put/patch call chain
  data?: any;
}

// Sprint 50 PR Q2 — cookie-based auth flow. Every fetch must send the
// httpOnly auth cookie (`credentials: 'include'`), and state-changing
// requests must echo the CSRF token the backend set as XSRF-TOKEN
// (double-submit-cookie pattern).
const CSRF_METHODS = new Set(['POST', 'PUT', 'PATCH', 'DELETE']);

function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp('(?:^|; )' + name + '=([^;]*)'));
  return match ? decodeURIComponent(match[1]) : null;
}

async function apiRequest<T>(endpoint: string, options: FetchOptions = {}): Promise<T> {
  const { data, ...customConfig } = options;
  const method = (customConfig.method || 'GET').toUpperCase();

  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(customConfig.headers as Record<string, string> | undefined),
  };

  // CSRF echo — Spring compares the cookie value with this header.
  if (CSRF_METHODS.has(method)) {
    const csrf = readCookie('XSRF-TOKEN');
    if (csrf) {
      headers['X-XSRF-TOKEN'] = csrf;
    }
  }

  const config: RequestInit = {
    credentials: 'include',   // send the httpOnly auth cookie
    ...customConfig,
    headers,
  };

  if (data) {
    config.body = JSON.stringify(data);
  }

  try {
    const response = await fetch(`${BASE_URL}${endpoint}`, config);

    // Always parse JSON body safely to intercept structured error/success keys
    const responseData = await response.json().catch(() => ({}));

    if (!response.ok) {
      // Expired or invalid session: clear local auth state and send the user
      // back to the login screen. Login/signup 401s (wrong password) are the
      // endpoint's own business and must not trigger a redirect loop.
      // The JWT itself lives in an httpOnly cookie — the server clears it
      // on logout; here we only wipe the non-sensitive UI-gating fields.
      if (response.status === 401 && !endpoint.startsWith('/auth/')) {
        localStorage.removeItem('multiship_user');
        localStorage.removeItem('multiship_role');

        if (!window.location.pathname.startsWith('/login')) {
          window.location.assign('/login');
        }
      }

      // Pulls out custom error strings dropped by your Spring Boot backend
      throw new ApiError(
        responseData.message || `HTTP error! status: ${response.status}`,
        response.status,
        responseData
      );
    }

    if (response.status === 204) return {} as T;

    return responseData as T;
  } catch (error: unknown) {
    // Sprint 51 FE-L2 — a plain console.error for every non-2xx (including
    // the many expected 401/409/422 flows) drowned real 5xx and network
    // failures in log noise. Aborts (component unmount) are silenced,
    // expected client errors go to debug, only true failures stay at error.
    if (isAbortError(error)) {
      throw error;
    }
    const status: number | undefined =
      error instanceof ApiError ? error.status : undefined;
    const message = error instanceof Error ? error.message : String(error);
    const label = `[API Client] ${options.method || 'GET'} to ${endpoint}`;
    if (status && status >= 400 && status < 500) {
      console.debug(`${label} — ${status}`, message);
    } else {
      console.error(`${label}:`, message);
    }
    throw error;
  }
}

/**
 * Shared helper for non-JSON endpoints (binary downloads, HTML previews,
 * multipart uploads) that {@link apiClient}'s JSON path doesn't cover.
 * Attaches the Bearer token automatically and mirrors apiClient's 401
 * behaviour: clears the local session + kicks the operator to /login so
 * an expired JWT doesn't manifest as an opaque "HTTP 401" toast.
 *
 * Returns the {@link Response} on 2xx so callers decide how to consume
 * the body ({@code response.blob()}, {@code .text()}, {@code .json()},
 * etc.). Throws with the server's error message on non-2xx — the
 * message we surface uses the response body's `message` field when
 * present, falling back to a generic status line.
 */
export async function authFetch(endpoint: string, options: RequestInit = {}): Promise<Response> {
  const headers = new Headers(options.headers);
  // CSRF echo for state-changing requests.
  const method = (options.method || 'GET').toUpperCase();
  if (CSRF_METHODS.has(method) && !headers.has('X-XSRF-TOKEN')) {
    const csrf = readCookie('XSRF-TOKEN');
    if (csrf) headers.set('X-XSRF-TOKEN', csrf);
  }
  const response = await fetch(`${BASE_URL}${endpoint}`, {
    credentials: 'include',   // send the httpOnly auth cookie
    ...options,
    headers,
  });
  if (response.ok) return response;

  // Non-2xx — try to surface a useful error message. Backend error
  // responses are JSON with a `message` field; static/binary error
  // responses (e.g., raw 401 pages) fall back to a generic line.
  let serverMessage: string | null = null;
  try {
    const text = await response.clone().text();
    if (text) {
      try {
        const body = JSON.parse(text);
        serverMessage = body?.message ?? body?.error ?? null;
      } catch {
        // not JSON — treat the body as opaque
        serverMessage = text.length < 200 ? text : null;
      }
    }
  } catch {
    // Body may be unreadable (already consumed by a wrapper); ignore.
  }

  // Same auto-logout as apiClient: an expired / invalid JWT should send
  // the operator back to /login rather than showing a mystery toast.
  // Skip on /auth/ endpoints so wrong-password on the login form doesn't
  // trigger a redirect loop.
  if (response.status === 401 && !endpoint.startsWith('/auth/')) {
    localStorage.removeItem('multiship_user');
    localStorage.removeItem('multiship_role');
    if (!window.location.pathname.startsWith('/login')) {
      window.location.assign('/login');
    }
  }

  // Use the server's own message as the error text (like the JSON path above)
  // so callers show a clean "A file named … is already imported" instead of a
  // raw "HTTP 409 — …". The status is still available via ApiError.status.
  throw new ApiError(
    serverMessage || `HTTP error! status: ${response.status}`,
    response.status,
    serverMessage ? { message: serverMessage } : {},
  );
}

export const apiClient = {
  /**
   * Every method accepts an optional {@link RequestInit} that is
   * forwarded to fetch. Sprint 49 Tier 4 Fix 4 — this makes
   * cancellation a one-liner:
   *
   * <pre>
   * const controller = new AbortController();
   * apiClient.get('/orders', { signal: controller.signal });
   * // ...on unmount: controller.abort();
   * </pre>
   *
   * Aborted requests reject with an error whose name is 'AbortError';
   * use {@link isAbortError} to silence them in catch blocks.
   */
  get: <T>(endpoint: string, options?: RequestInit) =>
    apiRequest<T>(endpoint, { method: 'GET', ...options }),

  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- see FetchOptions.data comment; body payloads are caller-defined
  post: <T>(endpoint: string, data?: any, options?: RequestInit) =>
    apiRequest<T>(endpoint, { method: 'POST', data, ...options }),

  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- see FetchOptions.data comment; body payloads are caller-defined
  put: <T>(endpoint: string, data: any, options?: RequestInit) =>
    apiRequest<T>(endpoint, { method: 'PUT', data, ...options }),

  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- see FetchOptions.data comment; body payloads are caller-defined
  patch: <T>(endpoint: string, data: any, options?: RequestInit) =>
    apiRequest<T>(endpoint, { method: 'PATCH', data, ...options }),

  delete: <T>(endpoint: string, options?: RequestInit) =>
    apiRequest<T>(endpoint, { method: 'DELETE', ...options }),
};

/**
 * Sprint 49 Tier 4 Fix 4 — true when the given error came from an
 * AbortController.abort() call. Aborts happen on unmount / re-render
 * and are expected — callers should silence them rather than showing
 * a toast.
 */
export function isAbortError(err: unknown): boolean {
  if (err == null) return false;
  if (err instanceof DOMException && err.name === 'AbortError') return true;
  return typeof err === 'object' && (err as { name?: string }).name === 'AbortError';
}