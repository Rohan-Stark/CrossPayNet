"use client";

import React, { Suspense, useCallback, useEffect, useState } from "react";
import { useParams, useSearchParams } from "next/navigation";
import Link from "next/link";
import {
  AlertTriangle,
  ArrowLeft,
  CheckCircle2,
  Clock,
  RefreshCw,
  Send,
  XCircle,
} from "lucide-react";
import { portalApi, PortalApiError } from "@/lib/api-client";
import type { CustomerPaymentResponse } from "@/lib/types";
import { formatCurrencyAmount, formatDateTime } from "@/lib/formatters";
import { PaymentStatusBadge } from "@/components/payments/PaymentStatusBadge";
import { PaymentStateTimeline } from "@/components/payments/PaymentStateTimeline";
import { ErrorMessage } from "@/components/ui/ErrorMessage";
import { DashboardLoadingSkeleton } from "@/components/ui/LoadingSkeleton";

function PaymentDetailContent() {
  const params = useParams<{ paymentId: string }>();
  const searchParams = useSearchParams();
  const paymentId = params?.paymentId ?? "";
  const justInitiated = searchParams?.get("initiated") === "1";

  const [payment, setPayment] = useState<CustomerPaymentResponse | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [error, setError] = useState<{
    message: string;
    status?: number;
  } | null>(null);

  const handleManualRefresh = useCallback(async () => {
    if (!paymentId) return;
    setIsLoading(true);
    setError(null);
    try {
      const result = await portalApi.getPaymentById(paymentId);
      setPayment(result);
    } catch (err) {
      if (err instanceof PortalApiError) {
        setError({ message: err.message, status: err.status });
      } else if (err instanceof Error) {
        setError({ message: err.message });
      } else {
        setError({ message: "Unable to load payment details." });
      }
    } finally {
      setIsLoading(false);
    }
  }, [paymentId]);

  useEffect(() => {
    if (!paymentId) return;
    let active = true;
    void (async () => {
      try {
        const result = await portalApi.getPaymentById(paymentId);
        if (!active) return;
        setPayment(result);
        setError(null);
      } catch (err) {
        if (!active) return;
        if (err instanceof PortalApiError) {
          setError({ message: err.message, status: err.status });
        } else if (err instanceof Error) {
          setError({ message: err.message });
        } else {
          setError({ message: "Unable to load payment details." });
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
  }, [paymentId]);

  const renderInitiationBanner = (record: CustomerPaymentResponse) => {
    if (!justInitiated) return null;

    if (record.status === "REJECTED" || record.status === "FAILED") {
      return (
        <div
          role="alert"
          className="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-900"
        >
          <div className="flex items-start gap-3">
            <XCircle className="mt-0.5 h-5 w-5 shrink-0 text-red-600" />
            <div>
              <h2 className="font-semibold text-red-900">
                Payment Instruction {record.status}:{" "}
                {record.statusReasonCode ?? "Pre-Commitment Rejection"}
              </h2>
              <p className="mt-1 text-red-800">
                The payment instruction was recorded with ID{" "}
                <code className="font-mono text-xs">{record.paymentId}</code>,
                but could not proceed:{" "}
                <span className="font-medium">
                  {record.statusReasonDetail ??
                    record.statusReasonCode ??
                    "Rejected by domain policy."}
                </span>
              </p>
            </div>
          </div>
        </div>
      );
    }

    if (record.status === "SETTLED") {
      return (
        <div
          role="status"
          className="rounded-xl border border-emerald-200 bg-emerald-50 p-4 text-sm text-emerald-900"
        >
          <div className="flex items-start gap-3">
            <CheckCircle2 className="mt-0.5 h-5 w-5 shrink-0 text-emerald-600" />
            <div>
              <h2 className="font-semibold text-emerald-900">
                Payment Instruction Accepted &amp; Settled
              </h2>
              <p className="mt-1 text-emerald-800">
                Your cross-border payment instruction passed compliance,
                correspondent routing, and completed final settlement.
              </p>
            </div>
          </div>
        </div>
      );
    }

    return (
      <div
        role="status"
        className="rounded-xl border border-blue-200 bg-blue-50 p-4 text-sm text-blue-900"
      >
        <div className="flex items-start gap-3">
          <Clock className="mt-0.5 h-5 w-5 shrink-0 text-blue-600" />
          <div>
            <h2 className="font-semibold text-blue-900">
              Payment Instruction Accepted ({record.status})
            </h2>
            <p className="mt-1 text-blue-800">
              Your payment instruction has been registered under reference{" "}
              <code className="font-mono font-semibold">
                {record.paymentReference}
              </code>{" "}
              and is currently in state{" "}
              <code className="font-mono font-semibold">{record.status}</code>.
              Use the Refresh button to observe asynchronous compliance and
              settlement updates.
            </p>
          </div>
        </div>
      </div>
    );
  };

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-4">
        <div>
          <Link
            href="/portal/payments"
            className="inline-flex items-center gap-1.5 text-xs font-semibold text-slate-600 transition hover:text-slate-900"
          >
            <ArrowLeft className="h-3.5 w-3.5" />
            <span>Back to Payment History</span>
          </Link>
          <h1 className="mt-2 text-2xl font-bold tracking-tight text-slate-900">
            Payment Instruction Details
          </h1>
          <p className="mt-0.5 font-mono text-xs text-slate-500">
            ID: {paymentId}
          </p>
        </div>

        <div className="flex items-center gap-3">
          <button
            type="button"
            onClick={() => void handleManualRefresh()}
            disabled={isLoading}
            className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3.5 py-2 text-xs font-semibold text-slate-700 shadow-2xs transition hover:bg-slate-50 disabled:opacity-50"
          >
            <RefreshCw className="h-3.5 w-3.5" />
            <span>Refresh Status</span>
          </button>
          <Link
            href="/portal/payments/new"
            className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-xs font-semibold text-white shadow-xs transition hover:bg-blue-700"
          >
            <Send className="h-3.5 w-3.5" />
            <span>New Payment</span>
          </Link>
        </div>
      </div>

      {error && (
        <ErrorMessage
          title={
            error.status === 404
              ? "Payment Not Found (404)"
              : "Failed to Load Payment Details"
          }
          message={error.message}
          statusCode={error.status}
          onRetry={() => void handleManualRefresh()}
        />
      )}

      {isLoading && !payment ? (
        <DashboardLoadingSkeleton />
      ) : (
        payment && (
          <>
            {renderInitiationBanner(payment)}

            {/* Primary Summary Card */}
            <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-xs">
              <div className="flex flex-wrap items-center justify-between gap-4 border-b border-slate-100 bg-slate-900 px-6 py-5 text-white">
                <div>
                  <span className="text-xs font-medium tracking-wider text-slate-400 uppercase">
                    Payment Reference
                  </span>
                  <div className="mt-1 font-mono text-lg font-bold text-white">
                    {payment.paymentReference}
                  </div>
                </div>
                <div className="flex items-center gap-3">
                  <PaymentStatusBadge status={payment.status} />
                </div>
              </div>

              <div className="grid gap-6 p-6 sm:grid-cols-2 lg:grid-cols-3">
                <div>
                  <span className="text-xs font-semibold text-slate-500 uppercase">
                    Instruction Amount
                  </span>
                  <div className="mt-1 font-mono text-2xl font-bold text-slate-900">
                    {formatCurrencyAmount(payment.amount, payment.currency, {
                      exactScale: true,
                    })}
                  </div>
                  <span className="text-xs text-slate-500">
                    ISO 4217 Currency: {payment.currency}
                  </span>
                </div>

                <div>
                  <span className="text-xs font-semibold text-slate-500 uppercase">
                    Beneficiary Details
                  </span>
                  <div className="mt-1 text-base font-semibold text-slate-900">
                    {payment.beneficiaryName}
                  </div>
                  <div className="mt-0.5 font-mono text-xs text-slate-600">
                    Account: {payment.beneficiaryAccountNumber}
                  </div>
                  <div className="mt-0.5 font-mono text-xs text-slate-600">
                    Bank BIC: {payment.beneficiaryBankBic}
                  </div>
                </div>

                <div>
                  <span className="text-xs font-semibold text-slate-500 uppercase">
                    Audit Timestamps
                  </span>
                  <div className="mt-1 text-xs text-slate-700">
                    <span className="font-semibold text-slate-900">
                      Created:
                    </span>{" "}
                    {formatDateTime(payment.createdAt)}
                  </div>
                  <div className="mt-1 text-xs text-slate-700">
                    <span className="font-semibold text-slate-900">
                      Last Updated:
                    </span>{" "}
                    {formatDateTime(payment.updatedAt)}
                  </div>
                  <div className="mt-1 font-mono text-[11px] text-slate-500">
                    Source Account: {payment.sourceAccountId}
                  </div>
                </div>
              </div>

              {(payment.statusReasonCode || payment.statusReasonDetail) && (
                <div className="border-t border-slate-100 bg-amber-50/60 px-6 py-4">
                  <div className="flex items-start gap-2.5 text-xs text-amber-900">
                    <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" />
                    <div>
                      <span className="font-semibold">Status Reason: </span>
                      {payment.statusReasonCode && (
                        <code className="mr-1.5 rounded bg-amber-100 px-1.5 py-0.5 font-mono font-semibold">
                          {payment.statusReasonCode}
                        </code>
                      )}
                      <span>{payment.statusReasonDetail}</span>
                    </div>
                  </div>
                </div>
              )}
            </div>

            {/* Honest Domain State Progression Timeline */}
            <PaymentStateTimeline
              status={payment.status}
              statusReasonCode={payment.statusReasonCode}
              statusReasonDetail={payment.statusReasonDetail}
            />
          </>
        )
      )}
    </div>
  );
}

export default function PaymentDetailPage() {
  return (
    <Suspense fallback={<DashboardLoadingSkeleton />}>
      <PaymentDetailContent />
    </Suspense>
  );
}
