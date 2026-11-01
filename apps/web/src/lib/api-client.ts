import {
  bootstrapCsrfToken,
  clearCsrfState,
  getCachedCsrfState,
  type CsrfState,
} from "./csrf";
import type {
  ApiErrorResponse,
  CustomerAccountResponse,
  CustomerPaymentResponse,
  InitiatePaymentRequest,
  PagedResponse,
} from "./types";

const DEFAULT_API_BASE_URL = "http://localhost:8080";
const DEFAULT_TIMEOUT_MS = 15000;

export function getApiBaseUrl(): string {
  const configured = process.env.NEXT_PUBLIC_API_BASE_URL;
  if (configured && configured.trim().length > 0) {
    return configured.trim().replace(/\/+$/, "");
  }
  return DEFAULT_API_BASE_URL;
}

/**
 * Structured error thrown by the CrossPayNet Portal API Client.
 * Distinguishes 400, 401, 403, 404, and 409 status codes cleanly.
 */
export class PortalApiError extends Error {
  public readonly status: number;
  public readonly errorTitle: string;
  public readonly path: string;
  public readonly timestamp: string;

  constructor(payload: {
    status: number;
    error: string;
    message: string;
    path?: string;
    timestamp?: string;
  }) {
    super(payload.message);
    this.name = "PortalApiError";
    this.status = payload.status;
    this.errorTitle = payload.error;
    this.path = payload.path ?? "";
    this.timestamp = payload.timestamp ?? new Date().toISOString();
  }

  get isBadRequest(): boolean {
    return this.status === 400;
  }

  get isUnauthorized(): boolean {
    return this.status === 401;
  }

  get isForbidden(): boolean {
    return this.status === 403;
  }

  get isNotFound(): boolean {
    return this.status === 404;
  }

  get isConflict(): boolean {
    return this.status === 409;
  }
}

/**
 * Encodes username and password into a standard HTTP Basic Authorization header value
 * using UTF-8 byte encoding.
 */
export function encodeBasicAuthHeader(username: string, password: string): string {
  const raw = `${username}:${password}`;
  if (typeof TextEncoder !== "undefined" && typeof btoa === "function") {
    const bytes = new TextEncoder().encode(raw);
    let binary = "";
    for (let i = 0; i < bytes.length; i++) {
      binary += String.fromCharCode(bytes[i]);
    }
    return `Basic ${btoa(binary)}`;
  }
  return `Basic ${Buffer.from(raw, "utf-8").toString("base64")}`;
}

/**
 * Strictly in-memory session credentials.
 * Never persisted to localStorage, sessionStorage, IndexedDB, URLs, or logs.
 */
let inMemoryBasicAuthHeader: string | null = null;
let inMemoryUsername: string | null = null;
let unauthorizedHandler: (() => void) | null = null;

export function setInMemoryCredentials(username: string, password: string): void {
  inMemoryUsername = username;
  inMemoryBasicAuthHeader = encodeBasicAuthHeader(username, password);
}

export function clearInMemoryCredentials(): void {
  inMemoryUsername = null;
  inMemoryBasicAuthHeader = null;
  clearCsrfState();
}

export function getInMemoryUsername(): string | null {
  return inMemoryUsername;
}

export function hasInMemoryCredentials(): boolean {
  return inMemoryBasicAuthHeader !== null;
}

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  unauthorizedHandler = handler;
}

export interface RequestOptions {
  method?: "GET" | "POST" | "PUT" | "DELETE";
  body?: unknown;
  authHeaderOverride?: string;
  timeoutMs?: number;
  fetchImpl?: typeof fetch;
  baseUrl?: string;
}

const MUTATING_METHODS = new Set(["POST", "PUT", "DELETE"]);

export async function portalFetch<T>(
  endpointPath: string,
  options: RequestOptions = {}
): Promise<T> {
  const method = options.method ?? "GET";
  const baseUrl = options.baseUrl ?? getApiBaseUrl();
  const fetchFn = options.fetchImpl ?? fetch;
  const timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;

  const headers: Record<string, string> = {
    Accept: "application/json",
  };

  const authHeader = options.authHeaderOverride ?? inMemoryBasicAuthHeader;
  if (authHeader) {
    headers["Authorization"] = authHeader;
  }

  if (MUTATING_METHODS.has(method)) {
    let csrfState: CsrfState | null = getCachedCsrfState();
    if (!csrfState) {
      csrfState = await bootstrapCsrfToken(baseUrl, fetchFn);
    }
    headers[csrfState.headerName] = csrfState.token;
    headers["Content-Type"] = "application/json";
  } else if (options.body !== undefined) {
    headers["Content-Type"] = "application/json";
  }

  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), timeoutMs);

  let response: Response;
  try {
    response = await fetchFn(`${baseUrl}${endpointPath}`, {
      method,
      headers,
      body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
      credentials: "include",
      signal: controller.signal,
    });
  } catch (err) {
    clearTimeout(timeoutId);
    if (err instanceof DOMException && err.name === "AbortError") {
      throw new PortalApiError({
        status: 0,
        error: "Request Timeout",
        message: "The request to the CrossPayNet backend timed out. Verify the backend server is running at " + baseUrl + ".",
        path: endpointPath,
      });
    }
    throw new PortalApiError({
      status: 0,
      error: "Network Error",
      message: "Unable to connect to the CrossPayNet backend. Ensure Spring Boot is running on " + baseUrl + ".",
      path: endpointPath,
    });
  } finally {
    clearTimeout(timeoutId);
  }

  if (!response.ok) {
    let parsedError: Partial<ApiErrorResponse> | null = null;
    try {
      const contentType = response.headers.get("content-type") ?? "";
      if (contentType.includes("application/json")) {
        parsedError = (await response.json()) as Partial<ApiErrorResponse>;
      }
    } catch {
      // Fallback to status-based messages if response body is empty or non-JSON
    }

    if (response.status === 401 && !options.authHeaderOverride && unauthorizedHandler) {
      unauthorizedHandler();
    }

    const defaultMessageByStatus: Record<number, string> = {
      400: "The request contains invalid parameters. Please review your input.",
      401: "Authentication failed or session expired. Please sign in with valid customer credentials.",
      403: "Access denied. Ensure your account has CUSTOMER permissions and a valid CSRF token.",
      404: "The requested resource could not be found.",
      409: "Conflict detected: this payment reference is already in use with different instruction parameters.",
    };

    throw new PortalApiError({
      status: response.status,
      error:
        parsedError?.error ??
        response.statusText ??
        `HTTP ${response.status}`,
      message:
        parsedError?.message ??
        defaultMessageByStatus[response.status] ??
        `Backend request failed with status ${response.status}.`,
      path: parsedError?.path ?? endpointPath,
      timestamp: parsedError?.timestamp,
    });
  }

  return (await response.json()) as T;
}

/**
 * High-level typed API methods for the Customer Portal.
 */
export const portalApi = {
  /**
   * Bootstraps CSRF cookie and token from GET /api/v1/portal/csrf
   */
  async initCsrf(options?: { baseUrl?: string; fetchImpl?: typeof fetch }): Promise<CsrfState> {
    return bootstrapCsrfToken(
      options?.baseUrl ?? getApiBaseUrl(),
      options?.fetchImpl ?? fetch
    );
  },

  /**
   * Retrieves customer accounts and balances from GET /api/v1/portal/accounts
   */
  async getAccounts(options?: RequestOptions): Promise<CustomerAccountResponse[]> {
    return portalFetch<CustomerAccountResponse[]>("/api/v1/portal/accounts", {
      ...options,
      method: "GET",
    });
  },

  /**
   * Retrieves paginated customer payment history from GET /api/v1/portal/payments
   */
  async getPayments(
    page: number = 0,
    size: number = 20,
    options?: RequestOptions
  ): Promise<PagedResponse<CustomerPaymentResponse>> {
    const safePage = Math.max(0, Math.floor(page));
    const safeSize = Math.min(100, Math.max(1, Math.floor(size)));
    return portalFetch<PagedResponse<CustomerPaymentResponse>>(
      `/api/v1/portal/payments?page=${safePage}&size=${safeSize}`,
      {
        ...options,
        method: "GET",
      }
    );
  },

  /**
   * Retrieves an individual payment by ID from GET /api/v1/portal/payments/{paymentId}
   */
  async getPaymentById(
    paymentId: string,
    options?: RequestOptions
  ): Promise<CustomerPaymentResponse> {
    return portalFetch<CustomerPaymentResponse>(
      `/api/v1/portal/payments/${encodeURIComponent(paymentId)}`,
      {
        ...options,
        method: "GET",
      }
    );
  },

  /**
   * Initiates a cross-border payment via POST /api/v1/portal/payments
   */
  async initiatePayment(
    request: InitiatePaymentRequest,
    options?: RequestOptions
  ): Promise<CustomerPaymentResponse> {
    return portalFetch<CustomerPaymentResponse>("/api/v1/portal/payments", {
      ...options,
      method: "POST",
      body: request,
    });
  },
};
