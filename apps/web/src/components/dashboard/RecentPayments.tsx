import React from "react";
import Link from "next/link";
import { ArrowRight, ArrowUpRight, Send } from "lucide-react";
import type { CustomerPaymentResponse } from "@/lib/types";
import { formatCurrencyAmount, formatShortDateTime } from "@/lib/formatters";
import { PaymentStatusBadge } from "@/components/payments/PaymentStatusBadge";
import { EmptyState } from "@/components/ui/EmptyState";

export function RecentPayments({
  payments,
  totalElements,
}: {
  payments: CustomerPaymentResponse[];
  totalElements: number;
}) {
  return (
    <section
      aria-labelledby="recent-transactions-heading"
      className="rounded-2xl border border-slate-200 bg-white shadow-xs"
    >
      <div className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-200 px-6 py-4">
        <div>
          <h2
            id="recent-transactions-heading"
            className="text-base font-semibold text-slate-900"
          >
            Recent Cross-Border Payments
          </h2>
          <p className="mt-0.5 text-xs text-slate-500">
            Showing {payments.length} of {totalElements} recorded instructions
          </p>
        </div>
        <div className="flex items-center gap-3">
          <Link
            href="/portal/payments"
            className="inline-flex items-center gap-1.5 text-xs font-semibold text-blue-600 transition hover:text-blue-700"
          >
            <span>View Full History</span>
            <ArrowRight className="h-3.5 w-3.5" />
          </Link>
        </div>
      </div>

      {payments.length === 0 ? (
        <div className="p-6">
          <EmptyState
            icon={<Send className="h-5 w-5" />}
            title="No payment instructions yet"
            description="Your account has no cross-border payment history. Initiate your first payment instruction to observe real-time compliance screening, routing, and settlement."
            action={
              <Link
                href="/portal/payments/new"
                className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-xs font-semibold text-white shadow-xs transition hover:bg-blue-700"
              >
                <Send className="h-3.5 w-3.5" />
                <span>Initiate First Payment</span>
              </Link>
            }
          />
        </div>
      ) : (
        <div className="overflow-x-auto">
          <table className="w-full border-collapse text-left text-sm">
            <thead>
              <tr className="border-b border-slate-200 bg-slate-50/80 text-xs font-semibold tracking-wider text-slate-500 uppercase">
                <th className="px-6 py-3">Reference</th>
                <th className="px-6 py-3">Beneficiary</th>
                <th className="px-6 py-3">Amount</th>
                <th className="px-6 py-3">Status</th>
                <th className="px-6 py-3">Created</th>
                <th className="px-6 py-3 text-right">Action</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {payments.map((payment) => (
                <tr
                  key={payment.paymentId}
                  className="transition hover:bg-slate-50/90"
                >
                  <td className="px-6 py-3.5 font-mono text-xs font-semibold text-slate-900">
                    {payment.paymentReference}
                  </td>
                  <td className="px-6 py-3.5">
                    <div className="font-medium text-slate-900">
                      {payment.beneficiaryName}
                    </div>
                    <div className="font-mono text-xs text-slate-500">
                      {payment.beneficiaryBankBic} &bull;{" "}
                      {payment.beneficiaryAccountNumber}
                    </div>
                  </td>
                  <td className="px-6 py-3.5 font-mono font-semibold text-slate-900">
                    {formatCurrencyAmount(payment.amount, payment.currency)}
                  </td>
                  <td className="px-6 py-3.5">
                    <PaymentStatusBadge status={payment.status} />
                  </td>
                  <td className="px-6 py-3.5 text-xs text-slate-600">
                    {formatShortDateTime(payment.createdAt)}
                  </td>
                  <td className="px-6 py-3.5 text-right">
                    <Link
                      href={`/portal/payments/${payment.paymentId}`}
                      className="inline-flex items-center gap-1 rounded-lg border border-slate-200 bg-white px-2.5 py-1.5 text-xs font-semibold text-slate-700 shadow-2xs transition hover:border-blue-300 hover:text-blue-600"
                    >
                      <span>Details</span>
                      <ArrowUpRight className="h-3.5 w-3.5" />
                    </Link>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}
