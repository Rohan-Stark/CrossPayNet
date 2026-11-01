import type { CsrfTokenResponse } from "./types";

/**
 * In-memory CSRF state manager for browser-compatible Spring Security CSRF protection.
 *
 * Uses CookieCsrfTokenRepository (XSRF-TOKEN cookie with HttpOnly=false) and the
 * dedicated /api/v1/portal/csrf bootstrap endpoint.
 */
export interface CsrfState {
  token: string;
  headerName: string;
  parameterName: string;
}

let cachedCsrfState: CsrfState | null = null;

/**
 * Reads a cookie value from document.cookie when running in the browser.
 */
export function readBrowserCookie(name: string): string | null {
  if (typeof document === "undefined" || !document.cookie) {
    return null;
  }
  const cookies = document.cookie.split(";");
  for (const rawCookie of cookies) {
    const [cookieName, ...rest] = rawCookie.trim().split("=");
    if (cookieName === name) {
      return decodeURIComponent(rest.join("="));
    }
  }
  return null;
}

export function getCachedCsrfState(): CsrfState | null {
  const cookieToken = readBrowserCookie("XSRF-TOKEN");
  if (cookieToken) {
    return {
      token: cookieToken,
      headerName: cachedCsrfState?.headerName ?? "X-XSRF-TOKEN",
      parameterName: cachedCsrfState?.parameterName ?? "_csrf",
    };
  }
  return cachedCsrfState;
}

export function setCachedCsrfState(state: CsrfState | null): void {
  cachedCsrfState = state;
}

export function clearCsrfState(): void {
  cachedCsrfState = null;
}

/**
 * Bootstraps the CSRF token from GET /api/v1/portal/csrf with credentials: "include"
 * so the browser stores the XSRF-TOKEN cookie and captures the headerName & token.
 */
export async function bootstrapCsrfToken(
  baseUrl: string,
  fetchImpl: typeof fetch = fetch
): Promise<CsrfState> {
  const response = await fetchImpl(`${baseUrl}/api/v1/portal/csrf`, {
    method: "GET",
    credentials: "include",
    headers: {
      Accept: "application/json",
    },
  });

  if (!response.ok) {
    throw new Error(`Failed to initialize CSRF token (HTTP ${response.status}).`);
  }

  const data = (await response.json()) as CsrfTokenResponse;
  const cookieToken = readBrowserCookie("XSRF-TOKEN");
  const state: CsrfState = {
    token: cookieToken || data.token,
    headerName: data.headerName || "X-XSRF-TOKEN",
    parameterName: data.parameterName || "_csrf",
  };

  cachedCsrfState = state;
  return state;
}
