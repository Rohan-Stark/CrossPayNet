"use client";

import React, {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import {
  clearInMemoryCredentials,
  encodeBasicAuthHeader,
  portalApi,
  setInMemoryCredentials,
  setUnauthorizedHandler,
} from "./api-client";
import type { CustomerAccountResponse } from "./types";

export interface AuthState {
  isAuthenticated: boolean;
  username: string | null;
  accounts: CustomerAccountResponse[];
  login: (username: string, password: string) => Promise<void>;
  logout: () => void;
  refreshAccounts: () => Promise<CustomerAccountResponse[]>;
}

const AuthContext = createContext<AuthState | undefined>(undefined);

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [isAuthenticated, setIsAuthenticated] = useState<boolean>(false);
  const [username, setUsername] = useState<string | null>(null);
  const [accounts, setAccounts] = useState<CustomerAccountResponse[]>([]);

  const logout = useCallback(() => {
    clearInMemoryCredentials();
    setIsAuthenticated(false);
    setUsername(null);
    setAccounts([]);
  }, []);

  useEffect(() => {
    setUnauthorizedHandler(() => {
      clearInMemoryCredentials();
      setIsAuthenticated(false);
      setUsername(null);
      setAccounts([]);
    });
    return () => {
      setUnauthorizedHandler(null);
    };
  }, []);

  const login = useCallback(async (rawUsername: string, rawPassword: string) => {
    const trimmedUser = rawUsername.trim();
    if (!trimmedUser || !rawPassword) {
      throw new Error("Both username and password are required.");
    }

    // 1. Bootstrap CSRF cookie and token from GET /api/v1/portal/csrf
    await portalApi.initCsrf();

    // 2. Verify credentials and ROLE_CUSTOMER authorization against GET /api/v1/portal/accounts
    const candidateAuthHeader = encodeBasicAuthHeader(trimmedUser, rawPassword);
    const customerAccounts = await portalApi.getAccounts({
      authHeaderOverride: candidateAuthHeader,
    });

    // 3. Store credentials strictly in client memory for the active session
    setInMemoryCredentials(trimmedUser, rawPassword);
    setUsername(trimmedUser);
    setAccounts(customerAccounts);
    setIsAuthenticated(true);
  }, []);

  const refreshAccounts = useCallback(async () => {
    const updated = await portalApi.getAccounts();
    setAccounts(updated);
    return updated;
  }, []);

  const value = useMemo<AuthState>(
    () => ({
      isAuthenticated,
      username,
      accounts,
      login,
      logout,
      refreshAccounts,
    }),
    [isAuthenticated, username, accounts, login, logout, refreshAccounts]
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext);
  if (!ctx) {
    throw new Error("useAuth must be used within an AuthProvider.");
  }
  return ctx;
}
