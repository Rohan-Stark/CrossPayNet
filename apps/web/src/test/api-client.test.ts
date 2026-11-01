import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  clearInMemoryCredentials,
  encodeBasicAuthHeader,
  portalApi,
  PortalApiError,
  setInMemoryCredentials,
  setUnauthorizedHandler,
} from "@/lib/api-client";
import { clearCsrfState, setCachedCsrfState } from "@/lib/csrf";

describe("Portal API Client — Auth Headers, CSRF & Error Handling", () => {
  beforeEach(() => {
    clearInMemoryCredentials();
    clearCsrfState();
    setUnauthorizedHandler(null);
    window.localStorage.clear();
    window.sessionStorage.clear();
    document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
  });

  it("encodes HTTP Basic Authorization header and never writes to localStorage or sessionStorage", () => {
    setInMemoryCredentials("customer", "customer123!");
    expect(encodeBasicAuthHeader("customer", "customer123!")).toBe(
      `Basic ${btoa("customer:customer123!")}`
    );
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });

  it("sends credentials: include and Authorization header on GET /api/v1/portal/accounts", async () => {
    setInMemoryCredentials("customer", "customer123!");

    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers({ "content-type": "application/json" }),
      json: async () => [
        {
          accountId: "550e8400-e29b-41d4-a716-446655440000",
          maskedAccountNumber: "****0000",
          currency: "USD",
          status: "ACTIVE",
          availableBalance: 10000,
          reservedAmount: 0,
          totalBalance: 10000,
        },
      ],
    });

    const accounts = await portalApi.getAccounts({
      fetchImpl: mockFetch as unknown as typeof fetch,
      baseUrl: "http://localhost:8080",
    });

    expect(accounts).toHaveLength(1);
    expect(mockFetch).toHaveBeenCalledTimes(1);
    const [url, init] = mockFetch.mock.calls[0];
    expect(url).toBe("http://localhost:8080/api/v1/portal/accounts");
    expect(init.credentials).toBe("include");
    expect(init.headers["Authorization"]).toBe(
      `Basic ${btoa("customer:customer123!")}`
    );
  });

  it("bootstraps CSRF token and attaches X-XSRF-TOKEN header on POST /api/v1/portal/payments", async () => {
    setInMemoryCredentials("customer", "customer123!");
    setCachedCsrfState({
      token: "csrf-token-1234",
      headerName: "X-XSRF-TOKEN",
      parameterName: "_csrf",
    });

    const mockFetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 201,
      headers: new Headers({ "content-type": "application/json" }),
      json: async () => ({
        paymentId: "73c6838a-3603-4b68-b715-c2db21469e38",
        paymentReference: "PAY-20261101-0001",
        sourceAccountId: "550e8400-e29b-41d4-a716-446655440000",
        amount: 250,
        currency: "USD",
        status: "COMPLIANCE_HOLD",
        beneficiaryName: "Siemens GmbH",
        beneficiaryAccountNumber: "****3000",
        beneficiaryBankBic: "DEUTDEFFXXX",
        statusReasonCode: null,
        statusReasonDetail: null,
        createdAt: "2026-11-01T10:00:00Z",
        updatedAt: "2026-11-01T10:00:00Z",
      }),
    });

    const response = await portalApi.initiatePayment(
      {
        sourceAccountId: "550e8400-e29b-41d4-a716-446655440000",
        paymentReference: "PAY-20261101-0001",
        amount: 250,
        currency: "USD",
        beneficiaryName: "Siemens GmbH",
        beneficiaryAccountNumber: "DE89370400440532013000",
        beneficiaryBankBic: "DEUTDEFFXXX",
      },
      {
        fetchImpl: mockFetch as unknown as typeof fetch,
        baseUrl: "http://localhost:8080",
      }
    );

    expect(response.paymentId).toBe("73c6838a-3603-4b68-b715-c2db21469e38");
    const [url, init] = mockFetch.mock.calls[0];
    expect(url).toBe("http://localhost:8080/api/v1/portal/payments");
    expect(init.method).toBe("POST");
    expect(init.credentials).toBe("include");
    expect(init.headers["X-XSRF-TOKEN"]).toBe("csrf-token-1234");
    expect(init.headers["Authorization"]).toBe(
      `Basic ${btoa("customer:customer123!")}`
    );
  });

  it("parses structured ApiErrorResponse for 400, 401, 403, 404, and 409 status codes", async () => {
    const onUnauth = vi.fn();
    setUnauthorizedHandler(onUnauth);
    setInMemoryCredentials("customer", "wrong");

    const statuses = [400, 401, 403, 404, 409] as const;
    for (const status of statuses) {
      const mockFetch = vi.fn().mockResolvedValue({
        ok: false,
        status,
        statusText: `Status ${status}`,
        headers: new Headers({ "content-type": "application/json" }),
        json: async () => ({
          timestamp: "2026-11-01T10:00:00Z",
          status,
          error: `Error ${status}`,
          message: `Detailed message for ${status}`,
          path: "/api/v1/portal/payments",
        }),
      });

      await expect(
        portalApi.getPayments(0, 20, {
          fetchImpl: mockFetch as unknown as typeof fetch,
        })
      ).rejects.toSatisfy((err: unknown) => {
        expect(err).toBeInstanceOf(PortalApiError);
        const apiErr = err as PortalApiError;
        expect(apiErr.status).toBe(status);
        expect(apiErr.message).toBe(`Detailed message for ${status}`);
        expect(apiErr.isBadRequest).toBe(status === 400);
        expect(apiErr.isUnauthorized).toBe(status === 401);
        expect(apiErr.isForbidden).toBe(status === 403);
        expect(apiErr.isNotFound).toBe(status === 404);
        expect(apiErr.isConflict).toBe(status === 409);
        return true;
      });
    }

    expect(onUnauth).toHaveBeenCalledTimes(1);
  });
});
