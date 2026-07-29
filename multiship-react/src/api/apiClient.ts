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

export const apiClient = {
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