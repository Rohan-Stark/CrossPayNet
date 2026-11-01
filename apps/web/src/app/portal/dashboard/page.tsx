"use client";

import React, { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import {
  Activity,
  ArrowRightLeft,
  CheckCircle2,
  Clock,
  RefreshCw,
  Send,
  Wallet,
} from "lucide-react";
import { portalApi, PortalApiError } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";
import type {
  CustomerAccountResponse,
  CustomerPaymentResponse,
  PagedResponse,
} from "@/lib/types";
import { AccountBalanceCard } from "@/components/dashboard/AccountBalanceCard";
import { RecentPayments } from "@/components/dashboard/RecentPayments";
import { DashboardLoadingSkeleton } from "@/components/ui/LoadingSkeleton";
import { ErrorMessage } from "@/components/ui/ErrorMessage";
import { EmptyState } from "@/components/ui/EmptyState";

export default function DashboardPage() {
  const { accounts: contextAccounts, refreshAccounts } = useAuth();
  const [accounts, setAccounts] =
    useState<CustomerAccountResponse[]>(contextAccounts);
  const [paymentsPage, setPaymentsPage] =
    useState<PagedResponse<CustomerPaymentResponse> | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [error, setError] = useState<{
    message: string;
    status?: number;
  } | null>(null);

  const loadDashboardData = useCallback(async () => {
    try {
      const [fetchedAccounts, fetchedPayments] = await Promise.all([
        refreshAccounts(),
        portalApi.getPayments(0, 20),
      ]);
      setAccounts(fetchedAccounts);
      setPaymentsPage(fetchedPayments);
      setError(null);
    } catch (err) {
      if (err instanceof PortalApiError) {
        setError({ message: err.message, status: err.status });
      } else if (err instanceof Error) {
        setError({ message: err.message });
      } else {
        setError({ message: "Failed to load customer dashboard data." });
      }
    } finally {
      setIsLoading(false);
    }
  }, [refreshAccounts]);

  const handleManualRefresh = useCallback(() => {
    setIsLoading(true);
    setError(null);
    void loadDashboardData();
  }, [loadDashboardData]);

  useEffect(() => {
    let active = true;
    void (async () => {
      try {
        const [fetchedAccounts, fetchedPayments] = await Promise.all([
          refreshAccounts(),
          portalApi.getPayments(0, 20),
        ]);
        if (!active) return;
        setAccounts(fetchedAccounts);
        setPaymentsPage(fetchedPayments);
        setError(null);
      } catch (err) {
        if (!active) return;
        if (err instanceof PortalApiError) {
          setError({ message: err.message, status: err.status });
        } else if (err instanceof Error) {
          setError({ message: err.message });
        } else {
          setError({ message: "Failed to load customer dashboard data." });
        }
      } finally {
        if (active) {
          setIsLoading(false);
        }
      }
    })();
    return () => {
      active = false;
    };
  }, [refreshAccounts]);

  const recentPayments = paymentsPage?.content ?? [];
  const totalPaymentsCount = paymentsPage?.totalElements ?? 0;

  // Derived strictly from the returned page items (explicitly scoped to recent 20)
  const settledInRecentCount = recentPayments.filter(
    (p) => p.status === "SETTLED"
  ).length;
  const inProgressInRecentCount = recentPayments.filter(
    (p) =>
      p.status === "VALIDATING" ||
      p.status === "COMPLIANCE_HOLD" ||
      p.status === "ROUTING" ||
      p.status === "EXECUTING" ||
      p.status === "PENDING_EXTERNAL"
  ).length;

  return (
    <div className="space-y-8">
      {/* Header Banner */}
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">
            Customer Treasury Dashboard
          </h1>
          <p className="mt-1 text-sm text-slate-600">
            Real-time settlement account positions and cross-border payment
            lifecycle monitoring.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={handleManualRefresh}
            disabled={isLoading}
            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-xs font-semibold text-slate-700 shadow-2xs transition hover:bg-slate-50 disabled:opacity-50"
          >
            <RefreshCw className="h-3.5 w-3.5" />
            <span>Refresh</span>
          </button>
          <Link
            href="/portal/payments/new"
            className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-xs font-semibold text-white shadow-xs transition hover:bg-blue-700"
          >
            <Send className="h-3.5 w-3.5" />
            <span>Initiate Payment</span>
          </Link>
        </div>
      </div>

      {error && (
        <ErrorMessage
          title="Dashboard Sync Failed"
          message={error.message}
          statusCode={error.status}
          onRetry={handleManualRefresh}
        />
      )}

      {isLoading && !paymentsPage ? (
        <DashboardLoadingSkeleton />
      ) : (
        <>
          {/* Summary Metrics derived honestly from API responses */}
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-slate-500 uppercase">
                  Active Accounts
                </span>
                <Wallet className="h-4 w-4 text-blue-600" />
              </div>
              <p className="mt-2 text-2xl font-bold text-slate-900">
                {accounts.filter((a) => a.status === "ACTIVE").length}
              </p>
              <p className="mt-1 text-xs text-slate-500">
                {accounts.length} total customer account(s)
              </p>
            </div>

            <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-slate-500 uppercase">
                  Total Instructions
                </span>
                <ArrowRightLeft className="h-4 w-4 text-indigo-600" />
              </div>
              <p className="mt-2 text-2xl font-bold text-slate-900">
                {totalPaymentsCount}
              </p>
              <p className="mt-1 text-xs text-slate-500">
                Recorded across owned accounts
              </p>
            </div>

            <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-slate-500 uppercase">
                  Settled (Recent 20)
                </span>
                <CheckCircle2 className="h-4 w-4 text-emerald-600" />
              </div>
              <p className="mt-2 text-2xl font-bold text-slate-900">
                {settledInRecentCount}
              </p>
              <p className="mt-1 text-xs text-slate-500">
                Completed final ledger settlement
              </p>
            </div>

            <div className="rounded-xl border border-slate-200 bg-white p-5 shadow-xs">
              <div className="flex items-center justify-between">
                <span className="text-xs font-semibold text-slate-500 uppercase">
                  In-Flight (Recent 20)
                </span>
                <Clock className="h-4 w-4 text-amber-600" />
              </div>
              <p className="mt-2 text-2xl font-bold text-slate-900">
                {inProgressInRecentCount}
              </p>
              <p className="mt-1 text-xs text-slate-500">
                Compliance hold, routing, or dispatch
              </p>
            </div>
          </div>

          {/* Account Overview Section */}
          <section aria-labelledby="accounts-heading" className="space-y-4">
            <div className="flex items-center justify-between">
              <h2
                id="accounts-heading"
                className="text-base font-semibold text-slate-900"
              >
                Settlement Accounts &amp; Balances
              </h2>
              <span className="text-xs text-slate-500">
                Source: GET /api/v1/portal/accounts
              </span>
            </div>

            {accounts.length === 0 ? (
              <EmptyState
                icon={<Activity className="h-5 w-5" />}
                title="No settlement accounts found"
                description="No customer bank accounts are currently linked to this principal."
              />
            ) : (
              <div className="grid gap-5 md:grid-cols-2">
                {accounts.map((account) => (
                  <AccountBalanceCard
                    key={account.accountId}
                    account={account}
                  />
                ))}
              </div>
            )}
          </section>

          {/* Recent Transactions Section */}
          <RecentPayments
            payments={recentPayments.slice(0, 10)}
            totalElements={totalPaymentsCount}
          />
        </>
      )}
    </div>
  );
}
