import React from "react";
import Link from "next/link";
import {
  ArrowUpRight,
  ChevronLeft,
  ChevronRight,
  FileSearch,
} from "lucide-react";
import type { CustomerPaymentResponse } from "@/lib/types";
import { formatCurrencyAmount, formatShortDateTime } from "@/lib/formatters";
import { PaymentStatusBadge } from "./PaymentStatusBadge";
import { EmptyState } from "@/components/ui/EmptyState";

export interface PaymentTableProps {
  payments: CustomerPaymentResponse[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  onPageChange: (newPage: number) => void;
  isFilteredOnCurrentPage?: boolean;
}

export function PaymentTable({
  payments,
  page,
  size,
  totalElements,
  totalPages,
  onPageChange,
  isFilteredOnCurrentPage = false,
}: PaymentTableProps) {
  const canGoPrev = page > 0;
  const canGoNext = page + 1 < totalPages;

  if (payments.length === 0) {
    return (
      <div className="rounded-2xl border border-slate-200 bg-white p-6 shadow-xs">
        <EmptyState
          icon={<FileSearch className="h-5 w-5" />}
          title={
            isFilteredOnCurrentPage
              ? "No matching payments on this page"
              : "No cross-border payments found"
          }
          description={
            isFilteredOnCurrentPage
              ? "Clear the current page filter or navigate to another page to inspect additional records."
              : "Once you submit cross-border payment instructions, they will appear here with full lifecycle status tracking."
          }
        />
      </div>
    );
  }

  return (
    <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-xs">
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-left text-sm">
          <thead>
            <tr className="border-b border-slate-200 bg-slate-50/90 text-xs font-semibold tracking-wider text-slate-500 uppercase">
              <th className="px-6 py-3.5">Payment Reference</th>
              <th className="px-6 py-3.5">Beneficiary</th>
              <th className="px-6 py-3.5">Correspondent BIC</th>
              <th className="px-6 py-3.5">Amount</th>
              <th className="px-6 py-3.5">Status</th>
              <th className="px-6 py-3.5">Created At</th>
              <th className="px-6 py-3.5 text-right">Details</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {payments.map((payment) => (
              <tr
                key={payment.paymentId}
                className="transition hover:bg-slate-50/80"
              >
                <td className="px-6 py-4">
                  <div className="font-mono text-xs font-semibold text-slate-900">
                    {payment.paymentReference}
                  </div>
                  <div className="mt-0.5 font-mono text-[11px] text-slate-400">
                    {payment.paymentId.slice(0, 8)}&hellip;
                  </div>
                </td>
                <td className="px-6 py-4">
                  <div className="font-medium text-slate-900">
                    {payment.beneficiaryName}
                  </div>
                  <div className="font-mono text-xs text-slate-500">
                    Acct: {payment.beneficiaryAccountNumber}
                  </div>
                </td>
                <td className="px-6 py-4 font-mono text-xs font-medium text-slate-700">
                  {payment.beneficiaryBankBic}
                </td>
                <td className="px-6 py-4 font-mono font-semibold text-slate-900">
                  {formatCurrencyAmount(payment.amount, payment.currency)}
                </td>
                <td className="px-6 py-4">
                  <PaymentStatusBadge status={payment.status} />
                  {payment.statusReasonCode && (
                    <div className="mt-1 font-mono text-[11px] text-slate-500">
                      {payment.statusReasonCode}
                    </div>
                  )}
                </td>
                <td className="px-6 py-4 text-xs text-slate-600">
                  {formatShortDateTime(payment.createdAt)}
                </td>
                <td className="px-6 py-4 text-right">
                  <Link
                    href={`/portal/payments/${payment.paymentId}`}
                    className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 shadow-2xs transition hover:border-blue-300 hover:text-blue-600"
                  >
                    <span>View</span>
                    <ArrowUpRight className="h-3.5 w-3.5" />
                  </Link>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-4 border-t border-slate-200 bg-slate-50/60 px-6 py-3.5 text-xs text-slate-600">
        <div>
          Showing page{" "}
          <span className="font-semibold text-slate-900">
            {totalPages === 0 ? 0 : page + 1}
          </span>{" "}
          of{" "}
          <span className="font-semibold text-slate-900">
            {Math.max(1, totalPages)}
          </span>{" "}
          ({totalElements} total records, page size {size})
        </div>
        <div className="flex items-center gap-2">
          <button
            type="button"
            disabled={!canGoPrev}
            onClick={() => onPageChange(page - 1)}
            className="inline-flex items-center gap-1 rounded-lg border border-slate-300 bg-white px-3 py-1.5 font-medium text-slate-700 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <ChevronLeft className="h-3.5 w-3.5" />
            <span>Previous</span>
          </button>
          <button
            type="button"
            disabled={!canGoNext}
            onClick={() => onPageChange(page + 1)}
            className="inline-flex items-center gap-1 rounded-lg border border-slate-300 bg-white px-3 py-1.5 font-medium text-slate-700 transition hover:bg-slate-50 disabled:cursor-not-allowed disabled:opacity-50"
          >
            <span>Next</span>
            <ChevronRight className="h-3.5 w-3.5" />
          </button>
        </div>
      </div>
    </div>
  );
}
