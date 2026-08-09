// Point the API at the same host the app is opened on, so it works both on
// localhost and over the LAN (e.g. http://192.168.x.x:5175 → :8080 on that host).
// Override with VITE_API_BASE_URL if the backend lives elsewhere.
export const BASE_URL =
  (import.meta.env?.VITE_API_BASE_URL as string | undefined) ||
  `${window.location.protocol}//${window.location.hostname}:8080/api/v1`;

/**
 * Error thrown for non-2xx responses. Carries the HTTP status so callers
 * branch on the code (422 needs-details, 409 already-generated, ...) and the
 * parsed body so structured payloads (e.g. prefill fields) survive the throw.
 */
export class ApiError extends Error {
  status: number;
  payload: any;
  /** Stable machine-readable code from the backend (e.g. 'NEEDS_CARRIER_DETAILS', 'LABEL_ALREADY_GENERATED'); null if absent. */
  errorCode: string | null;

  constructor(message: string, status: number, payload: any) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.payload = payload;
    this.errorCode = typeof payload?.errorCode === 'string' ? payload.errorCode : null;
  }
}

interface FetchOptions extends RequestInit {
  data?: any;
}

async function apiRequest<T>(endpoint: string, options: FetchOptions = {}): Promise<T> {
  const { data, ...customConfig } = options;
  
  const headers: HeadersInit = {
    'Content-Type': 'application/json',
    ...(customConfig.headers || {}),
  };

  // Automatically attach JWT token if it exists in local storage
  const token = localStorage.getItem('multiship_token');
  if (token) {
    (headers as any)['Authorization'] = `Bearer ${token}`;
  }

  const config: RequestInit = {
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
      if (response.status === 401 && !endpoint.startsWith('/auth/')) {
        localStorage.removeItem('multiship_token');
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
  } catch (error: any) {
    console.error(`[API Client Error] ${options.method || 'GET'} to ${endpoint}:`, error.message);
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
  const token = localStorage.getItem('multiship_token');
  const headers = new Headers(options.headers);
  if (token && !headers.has('Authorization')) {
    headers.set('Authorization', `Bearer ${token}`);
  }
  const response = await fetch(`${BASE_URL}${endpoint}`, { ...options, headers });
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
    localStorage.removeItem('multiship_token');
    localStorage.removeItem('multiship_user');
    localStorage.removeItem('multiship_role');
    if (!window.location.pathname.startsWith('/login')) {
      window.location.assign('/login');
    }
  }

  const suffix = serverMessage ? ` — ${serverMessage}` : '';
  throw new ApiError(
    `HTTP ${response.status}${suffix}`,
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

  post: <T>(endpoint: string, data?: any, options?: RequestInit) =>
    apiRequest<T>(endpoint, { method: 'POST', data, ...options }),

  put: <T>(endpoint: string, data: any, options?: RequestInit) =>
    apiRequest<T>(endpoint, { method: 'PUT', data, ...options }),

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