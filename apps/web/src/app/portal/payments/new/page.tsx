"use client";

import React, { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import { ArrowLeft } from "lucide-react";
import { portalApi, PortalApiError } from "@/lib/api-client";
import { useAuth } from "@/lib/auth-context";
import type { InitiatePaymentRequest } from "@/lib/types";
import { PaymentForm } from "@/components/payments/PaymentForm";

export default function NewPaymentPage() {
  const { accounts, refreshAccounts } = useAuth();
  const router = useRouter();
  const [apiError, setApiError] = useState<{
    message: string;
    status?: number;
  } | null>(null);

  useEffect(() => {
    void refreshAccounts();
  }, [refreshAccounts]);

  const handleSubmitPayment = async (request: InitiatePaymentRequest) => {
    setApiError(null);
    try {
      const createdPayment = await portalApi.initiatePayment(request);
      await refreshAccounts();
      router.push(
        `/portal/payments/${encodeURIComponent(
          createdPayment.paymentId
        )}?initiated=1`
      );
    } catch (err) {
      if (err instanceof PortalApiError) {
        setApiError({
          message: err.message,
          status: err.status,
        });
      } else if (err instanceof Error) {
        setApiError({ message: err.message });
      } else {
        setApiError({
          message: "Failed to initiate payment instruction.",
        });
      }
    }
  };

  return (
    <div className="mx-auto max-w-3xl space-y-6">
      <div>
        <Link
          href="/portal/dashboard"
          className="inline-flex items-center gap-1.5 text-xs font-semibold text-slate-600 transition hover:text-slate-900"
        >
          <ArrowLeft className="h-3.5 w-3.5" />
          <span>Back to Dashboard</span>
        </Link>
        <h1 className="mt-2 text-2xl font-bold tracking-tight text-slate-900">
          Initiate Cross-Border Payment
        </h1>
        <p className="mt-1 text-sm text-slate-600">
          Submit a new ISO 20022-inspired payment instruction from your
          settlement account.
        </p>
      </div>

      <PaymentForm
        accounts={accounts}
        onSubmitPayment={handleSubmitPayment}
        apiError={apiError}
      />
    </div>
  );
}
