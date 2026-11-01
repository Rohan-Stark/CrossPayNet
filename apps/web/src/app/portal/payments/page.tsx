"use client";

import React, { useCallback, useEffect, useMemo, useState } from "react";
import Link from "next/link";
import { Filter, RefreshCw, Search, Send } from "lucide-react";
import { portalApi, PortalApiError } from "@/lib/api-client";
import {
  ALL_PAYMENT_STATUSES,
  type CustomerPaymentResponse,
  type PagedResponse,
  type PaymentStatus,
} from "@/lib/types";
import { PaymentTable } from "@/components/payments/PaymentTable";
import { TableLoadingSkeleton } from "@/components/ui/LoadingSkeleton";
import { ErrorMessage } from "@/components/ui/ErrorMessage";

export default function PaymentHistoryPage() {
  const [page, setPage] = useState<number>(0);
  const [size, setSize] = useState<number>(20);
  const [pagedData, setPagedData] =
    useState<PagedResponse<CustomerPaymentResponse> | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [error, setError] = useState<{
    message: string;
    status?: number;
  } | null>(null);

  // Client-side filters scoped strictly to the loaded database page
  const [referenceFilter, setReferenceFilter] = useState<string>("");
  const [statusFilter, setStatusFilter] = useState<string>("ALL");

  const handleManualRefresh = useCallback(
    async (targetPage: number, targetSize: number) => {
      setIsLoading(true);
      setError(null);
      try {
        const result = await portalApi.getPayments(targetPage, targetSize);
        setPagedData(result);
      } catch (err) {
        if (err instanceof PortalApiError) {
          setError({ message: err.message, status: err.status });
        } else if (err instanceof Error) {
          setError({ message: err.message });
        } else {
          setError({ message: "Unable to load payment history." });
        }
      } finally {
        setIsLoading(false);
      }
    },
    []
  );

  useEffect(() => {
    let active = true;
    void (async () => {
      try {
        const result = await portalApi.getPayments(page, size);
        if (!active) return;
        setPagedData(result);
        setError(null);
      } catch (err) {
        if (!active) return;
        if (err instanceof PortalApiError) {
          setError({ message: err.message, status: err.status });
        } else if (err instanceof Error) {
          setError({ message: err.message });
        } else {
          setError({ message: "Unable to load payment history." });
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
  }, [page, size]);

  const filteredContent = useMemo(() => {
    const items = pagedData?.content ?? [];
    return items.filter((item) => {
      const matchesRef =
        !referenceFilter.trim() ||
        item.paymentReference
          .toLowerCase()
          .includes(referenceFilter.trim().toLowerCase()) ||
        item.beneficiaryName
          .toLowerCase()
          .includes(referenceFilter.trim().toLowerCase());
      const matchesStatus =
        statusFilter === "ALL" || item.status === statusFilter;
      return matchesRef && matchesStatus;
    });
  }, [pagedData, referenceFilter, statusFilter]);

  const isFilteredOnCurrentPage =
    referenceFilter.trim().length > 0 || statusFilter !== "ALL";

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-slate-900">
            Payment Instruction History
          </h1>
          <p className="mt-1 text-sm text-slate-600">
            Database-paginated payment records ordered by{" "}
            <code className="font-mono text-xs text-slate-800">
              createdAt DESC, paymentId DESC
            </code>
            .
          </p>
        </div>

        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => void handleManualRefresh(page, size)}
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

      {/* Filter Bar (explicitly labeled as filtering current loaded page) */}
      <div className="grid gap-4 rounded-2xl border border-slate-200 bg-white p-4 shadow-xs sm:grid-cols-3">
        <div>
          <label
            htmlFor="ref-filter"
            className="flex items-center gap-1.5 text-xs font-semibold text-slate-600 uppercase"
          >
            <Search className="h-3.5 w-3.5 text-slate-400" />
            <span>Filter Current Page (Reference / Beneficiary)</span>
          </label>
          <input
            id="ref-filter"
            type="text"
            value={referenceFilter}
            onChange={(e) => setReferenceFilter(e.target.value)}
            placeholder="Filter loaded page..."
            className="mt-1.5 block w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:border-blue-600 focus:ring-2 focus:ring-blue-500/20 focus:outline-none"
          />
        </div>

        <div>
          <label
            htmlFor="status-filter"
            className="flex items-center gap-1.5 text-xs font-semibold text-slate-600 uppercase"
          >
            <Filter className="h-3.5 w-3.5 text-slate-400" />
            <span>Filter Current Page by Status</span>
          </label>
          <select
            id="status-filter"
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
            className="mt-1.5 block w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:border-blue-600 focus:ring-2 focus:ring-blue-500/20 focus:outline-none"
          >
            <option value="ALL">All Statuses on Current Page</option>
            {ALL_PAYMENT_STATUSES.map((st: PaymentStatus) => (
              <option key={st} value={st}>
                {st}
              </option>
            ))}
          </select>
        </div>

        <div>
          <label
            htmlFor="page-size"
            className="block text-xs font-semibold text-slate-600 uppercase"
          >
            Server Page Size
          </label>
          <select
            id="page-size"
            value={size}
            onChange={(e) => {
              setPage(0);
              setSize(Number(e.target.value));
            }}
            className="mt-1.5 block w-full rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-900 focus:border-blue-600 focus:ring-2 focus:ring-blue-500/20 focus:outline-none"
          >
            <option value={10}>10 per page</option>
            <option value={20}>20 per page (Default)</option>
            <option value={50}>50 per page</option>
          </select>
        </div>
      </div>

      {error && (
        <ErrorMessage
          title="Failed to Load Payment History"
          message={error.message}
          statusCode={error.status}
          onRetry={() => void handleManualRefresh(page, size)}
        />
      )}

      {isLoading ? (
        <TableLoadingSkeleton rows={6} />
      ) : (
        <PaymentTable
          payments={filteredContent}
          page={pagedData?.page ?? page}
          size={pagedData?.size ?? size}
          totalElements={pagedData?.totalElements ?? 0}
          totalPages={pagedData?.totalPages ?? 0}
          onPageChange={(nextPage) => setPage(nextPage)}
          isFilteredOnCurrentPage={isFilteredOnCurrentPage}
        />
      )}
    </div>
  );
}
