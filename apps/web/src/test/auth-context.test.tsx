import React from "react";
import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AuthProvider, useAuth } from "@/lib/auth-context";
import { portalApi } from "@/lib/api-client";

describe("AuthContext — In-Memory Session & Logout", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    window.localStorage.clear();
    window.sessionStorage.clear();
  });

  it("authenticates user in memory and clears all state upon logout without touching web storage", async () => {
    vi.spyOn(portalApi, "initCsrf").mockResolvedValue({
      token: "csrf-abc",
      headerName: "X-XSRF-TOKEN",
      parameterName: "_csrf",
    });

    vi.spyOn(portalApi, "getAccounts").mockResolvedValue([
      {
        accountId: "550e8400-e29b-41d4-a716-446655440000",
        maskedAccountNumber: "****0000",
        currency: "USD",
        status: "ACTIVE",
        availableBalance: 10000,
        reservedAmount: 0,
        totalBalance: 10000,
      },
    ]);

    const wrapper = ({ children }: { children: React.ReactNode }) => (
      <AuthProvider>{children}</AuthProvider>
    );

    const { result } = renderHook(() => useAuth(), { wrapper });

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.username).toBeNull();
    expect(result.current.accounts).toHaveLength(0);

    await act(async () => {
      await result.current.login("customer", "customer123!");
    });

    expect(result.current.isAuthenticated).toBe(true);
    expect(result.current.username).toBe("customer");
    expect(result.current.accounts).toHaveLength(1);
    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);

    act(() => {
      result.current.logout();
    });

    expect(result.current.isAuthenticated).toBe(false);
    expect(result.current.username).toBeNull();
    expect(result.current.accounts).toHaveLength(0);
  });
});
