"use client";

import React, { useState } from "react";
import { Building, CheckCircle2, Info, Send } from "lucide-react";
import {
  SUPPORTED_CURRENCIES,
  type CustomerAccountResponse,
  type InitiatePaymentRequest,
  type SupportedCurrency,
} from "@/lib/types";
import {
  formatCurrencyAmount,
  isSupportedCurrency,
  isValidBic,
  validateMonetaryAmount,
} from "@/lib/formatters";
import { ConfirmationDialog } from "@/components/ui/ConfirmationDialog";
import { ErrorMessage } from "@/components/ui/ErrorMessage";

export interface PaymentFormValidationErrors {
  sourceAccountId?: string;
  paymentReference?: string;
  amount?: string;
  currency?: string;
  beneficiaryName?: string;
  beneficiaryAccountNumber?: string;
  beneficiaryBankBic?: string;
}

export function validatePaymentFormInput(input: {
  sourceAccountId: string;
  paymentReference: string;
  amount: string;
  currency: string;
  beneficiaryName: string;
  beneficiaryAccountNumber: string;
  beneficiaryBankBic: string;
}): {
  valid: boolean;
  errors: PaymentFormValidationErrors;
  parsedRequest?: InitiatePaymentRequest;
} {
  const errors: PaymentFormValidationErrors = {};

  if (!input.sourceAccountId.trim()) {
    errors.sourceAccountId = "Please select a source debit account.";
  }

  const ref = input.paymentReference.trim();
  if (!ref) {
    errors.paymentReference = "Payment reference is required.";
  } else if (ref.length > 64) {
    errors.paymentReference = "Payment reference cannot exceed 64 characters.";
  } else if (/[\x00-\x1F\x7F]/.test(ref)) {
    errors.paymentReference =
      "Payment reference cannot contain control characters.";
  }

  const amountCheck = validateMonetaryAmount(input.amount);
  if (!amountCheck.valid || amountCheck.numericValue === undefined) {
    errors.amount = amountCheck.error ?? "Invalid payment amount.";
  }

  const curr = input.currency.trim().toUpperCase();
  if (!isSupportedCurrency(curr)) {
    errors.currency = `Currency must be one of: ${SUPPORTED_CURRENCIES.join(", ")}.`;
  }

  const benName = input.beneficiaryName.trim();
  if (!benName) {
    errors.beneficiaryName = "Beneficiary name is required.";
  } else if (benName.length > 140) {
    errors.beneficiaryName =
      "Beneficiary name cannot exceed 140 characters.";
  }

  const benAcct = input.beneficiaryAccountNumber.trim();
  if (!benAcct) {
    errors.beneficiaryAccountNumber =
      "Beneficiary account number or IBAN is required.";
  } else if (benAcct.length > 34) {
    errors.beneficiaryAccountNumber =
      "Beneficiary account number cannot exceed 34 characters.";
  }

  const bic = input.beneficiaryBankBic.trim().toUpperCase();
  if (!bic) {
    errors.beneficiaryBankBic = "Beneficiary bank BIC is required.";
  } else if (!isValidBic(bic)) {
    errors.beneficiaryBankBic =
      "Must be a valid 8 or 11-character uppercase ISO 9362 BIC (e.g. DEUTDEFFXXX or NWBKGB2LXXX).";
  }

  const valid = Object.keys(errors).length === 0;
  if (!valid) {
    return { valid: false, errors };
  }

  return {
    valid: true,
    errors: {},
    parsedRequest: {
      sourceAccountId: input.sourceAccountId.trim(),
      paymentReference: ref,
      amount: amountCheck.numericValue!,
      currency: curr as SupportedCurrency,
      beneficiaryName: benName,
      beneficiaryAccountNumber: benAcct,
      beneficiaryBankBic: bic,
    },
  };
}

function generateDefaultReference(): string {
  const datePart = new Date().toISOString().slice(0, 10).replace(/-/g, "");
  const randomSuffix = Math.random().toString(36).substring(2, 6).toUpperCase();
  return `PAY-${datePart}-${randomSuffix}`;
}

export interface PaymentFormProps {
  accounts: CustomerAccountResponse[];
  onSubmitPayment: (request: InitiatePaymentRequest) => Promise<void>;
  apiError?: { message: string; status?: number } | null;
}

export function PaymentForm({
  accounts,
  onSubmitPayment,
  apiError,
}: PaymentFormProps) {
  const activeAccounts = accounts.filter((a) => a.status === "ACTIVE");
  const initialAccount = activeAccounts[0] ?? accounts[0];

  const [sourceAccountId, setSourceAccountId] = useState<string>(
    initialAccount?.accountId ?? ""
  );
  const [paymentReference, setPaymentReference] = useState<string>(() =>
    generateDefaultReference()
  );
  const [amount, setAmount] = useState<string>("");
  const [currency, setCurrency] = useState<SupportedCurrency>(
    (initialAccount?.currency as SupportedCurrency) ?? "USD"
  );
  const [beneficiaryName, setBeneficiaryName] = useState<string>("");
  const [beneficiaryAccountNumber, setBeneficiaryAccountNumber] =
    useState<string>("");
  const [beneficiaryBankBic, setBeneficiaryBankBic] = useState<string>("");

  const [fieldErrors, setFieldErrors] = useState<PaymentFormValidationErrors>(
    {}
  );
  const [pendingRequest, setPendingRequest] =
    useState<InitiatePaymentRequest | null>(null);
  const [isSubmitting, setIsSubmitting] = useState<boolean>(false);

  const selectedAccount = accounts.find(
    (a) => a.accountId === sourceAccountId
  );

  const handleAccountChange = (newAccountId: string) => {
    setSourceAccountId(newAccountId);
    const matching = accounts.find((a) => a.accountId === newAccountId);
    if (matching && isSupportedCurrency(matching.currency)) {
      setCurrency(matching.currency);
    }
  };

  const handleOpenConfirmation = (e: React.FormEvent) => {
    e.preventDefault();
    if (isSubmitting) return;

    const validation = validatePaymentFormInput({
      sourceAccountId,
      paymentReference,
      amount,
      currency,
      beneficiaryName,
      beneficiaryAccountNumber,
      beneficiaryBankBic,
    });

    setFieldErrors(validation.errors);
    if (!validation.valid || !validation.parsedRequest) {
      return;
    }

    setPendingRequest(validation.parsedRequest);
  };

  const handleConfirmSubmission = async () => {
    if (!pendingRequest || isSubmitting) return;
    setIsSubmitting(true);
    try {
      await onSubmitPayment(pendingRequest);
    } finally {
      setIsSubmitting(false);
      setPendingRequest(null);
    }
  };

  const applyCorrespondentPreset = (preset: {
    name: string;
    iban: string;
    bic: string;
  }) => {
    setBeneficiaryName(preset.name);
    setBeneficiaryAccountNumber(preset.iban);
    setBeneficiaryBankBic(preset.bic);
    setFieldErrors((prev) => ({
      ...prev,
      beneficiaryName: undefined,
      beneficiaryAccountNumber: undefined,
      beneficiaryBankBic: undefined,
    }));
  };

  return (
    <>
      <form
        onSubmit={handleOpenConfirmation}
        noValidate
        className="space-y-6 rounded-2xl border border-slate-200 bg-white p-6 shadow-xs sm:p-8"
      >
        {apiError && (
          <ErrorMessage
            title="Payment Initiation Error"
            message={apiError.message}
            statusCode={apiError.status}
          />
        )}

        {/* Section 1: Source Debit Account */}
        <div className="space-y-4 border-b border-slate-100 pb-6">
          <div className="flex items-center gap-2 text-sm font-semibold text-slate-900">
            <Building className="h-4 w-4 text-blue-600" />
            <h3>1. Source Debit Account &amp; Reference</h3>
          </div>

          <div className="grid gap-5 sm:grid-cols-2">
            <div>
              <label
                htmlFor="sourceAccountId"
                className="block text-xs font-semibold text-slate-700 uppercase"
              >
                Debit Account
              </label>
              <select
                id="sourceAccountId"
                value={sourceAccountId}
                onChange={(e) => handleAccountChange(e.target.value)}
                disabled={isSubmitting}
                className="mt-1.5 block w-full rounded-lg border border-slate-300 bg-white px-3.5 py-2.5 text-sm text-slate-900 shadow-2xs focus:border-blue-600 focus:ring-2 focus:ring-blue-500/20 focus:outline-none"
              >
                {accounts.length === 0 && (
                  <option value="">No accounts available</option>
                )}
                {accounts.map((acct) => (
                  <option key={acct.accountId} value={acct.accountId}>
                    {acct.maskedAccountNumber} ({acct.currency}) — Avail:{" "}
                    {formatCurrencyAmount(acct.availableBalance, acct.currency)}
                  </option>
                ))}
              </select>
              {fieldErrors.sourceAccountId && (
                <p className="mt-1 text-xs font-medium text-red-600">
                  {fieldErrors.sourceAccountId}
                </p>
              )}
              {selectedAccount && (
                <p className="mt-1.5 text-xs text-slate-500">
                  Available:{" "}
                  <span className="font-mono font-semibold text-slate-800">
                    {formatCurrencyAmount(
                      selectedAccount.availableBalance,
                      selectedAccount.currency
                    )}
                  </span>{" "}
                  &bull; Status: {selectedAccount.status}
                </p>
              )}
            </div>

            <div>
              <div className="flex items-center justify-between">
                <label
                  htmlFor="paymentReference"
                  className="block text-xs font-semibold text-slate-700 uppercase"
                >
                  Idempotent Payment Reference
                </label>
                <button
                  type="button"
                  onClick={() =>
                    setPaymentReference(generateDefaultReference())
                  }
                  disabled={isSubmitting}
                  className="text-xs font-medium text-blue-600 hover:text-blue-700"
                >
                  Generate New
                </button>
              </div>
              <input
                id="paymentReference"
                type="text"
                maxLength={64}
                value={paymentReference}
                onChange={(e) => setPaymentReference(e.target.value)}
                disabled={isSubmitting}
                placeholder="e.g. PAY-20261101-0001"
                className="mt-1.5 block w-full rounded-lg border border-slate-300 bg-white px-3.5 py-2.5 font-mono text-sm text-slate-900 shadow-2xs focus:border-blue-600 focus:ring-2 focus:ring-blue-500/20 focus:outline-none"
              />
              {fieldErrors.paymentReference ? (
                <p className="mt-1 text-xs font-medium text-red-600">
                  {fieldErrors.paymentReference}
                </p>
              ) : (
                <p className="mt-1.5 text-xs text-slate-500">
                  Conflict-safe natural key per debit account (max 64 chars).
                </p>
              )}
            </div>
          </div>
        </div>

        {/* Section 2: Monetary Instruction */}
        <div className="space-y-4 border-b border-slate-100 pb-6">
          <div className="flex items-center gap-2 text-sm font-semibold text-slate-900">
            <CheckCircle2 className="h-4 w-4 text-blue-600" />
            <h3>2. Transfer Amount &amp; Currency</h3>
          </div>

          <div className="grid gap-5 sm:grid-cols-3">
            <div className="sm:col-span-2">
              <label
                htmlFor="amount"
                className="block text-xs font-semibold text-slate-700 uppercase"
              >
                Transfer Amount
              </label>
              <input
                id="amount"
                type="text"
                inputMode="decimal"
                value={amount}
                onChange={(e) => setAmount(e.target.value)}
                disabled={isSubmitting}
                placeholder="e.g. 500.0000"
                className="mt-1.5 block w-full rounded-lg border border-slate-300 bg-white px-3.5 py-2.5 font-mono text-sm text-slate-900 shadow-2xs focus:border-blue-600 focus:ring-2 focus:ring-blue-500/20 focus:outline-none"
              />
              {fieldErrors.amount ? (
                <p className="mt-1 text-xs font-medium text-red-600">
                  {fieldErrors.amount}
                </p>
              ) : (
                <p className="mt-1.5 text-xs text-slate-500">
                  Strictly positive decimal up to 4 fractional digits (e.g.
                  250.00 or 125.5000).
                </p>
              )}
            </div>

            <div>
              <label
                htmlFor="currency"
                className="block text-xs font-semibold text-slate-700 uppercase"
              >
                ISO 4217 Currency
              </label>
              <select
                id="currency"
                value={currency}
                onChange={(e) =>
                  setCurrency(e.target.value as SupportedCurrency)
                }
                disabled={isSubmitting}
                className="mt-1.5 block w-full rounded-lg border border-slate-300 bg-white px-3.5 py-2.5 text-sm font-semibold text-slate-900 shadow-2xs focus:border-blue-600 focus:ring-2 focus:ring-blue-500/20 focus:outline-none"
              >
                {SUPPORTED_CURRENCIES.map((code) => (
                  <option key={code} value={code}>
                    {code}
                  </option>
                ))}
              </select>
              {fieldErrors.currency && (
                <p className="mt-1 text-xs font-medium text-red-600">
                  {fieldErrors.currency}
                </p>
              )}
            </div>
          </div>
        </div>

        {/* Section 3: Beneficiary & Correspondent Bank */}
        <div className="space-y-4">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2 text-sm font-semibold text-slate-900">
              <Send className="h-4 w-4 text-blue-600" />
              <h3>3. Beneficiary &amp; Correspondent BIC</h3>
            </div>
            <div className="flex flex-wrap items-center gap-2 text-xs">
              <span className="text-slate-500">Seeded route preset:</span>
              <button
                type="button"
                onClick={() =>
                  applyCorrespondentPreset({
                    name: "Siemens Industrial Automation GmbH",
                    iban: "DE89370400440532013000",
                    bic: "DEUTDEFFXXX",
                  })
                }
                className="rounded-md border border-blue-200 bg-blue-50 px-2.5 py-1 font-mono text-xs font-medium text-blue-700 transition hover:bg-blue-100"
              >
                DEUTDEFFXXX (USD)
              </button>
            </div>
          </div>

          <div className="grid gap-5 sm:grid-cols-2">
            <div className="sm:col-span-2">
              <label
                htmlFor="beneficiaryName"
                className="block text-xs font-semibold text-slate-700 uppercase"
              >
                Beneficiary Legal Name
              </label>
              <input
                id="beneficiaryName"
                type="text"
                maxLength={140}
                value={beneficiaryName}
                onChange={(e) => setBeneficiaryName(e.target.value)}
                disabled={isSubmitting}
                placeholder="e.g. Siemens Industrial Automation GmbH"
                className="mt-1.5 block w-full rounded-lg border border-slate-300 bg-white px-3.5 py-2.5 text-sm text-slate-900 shadow-2xs focus:border-blue-600 focus:ring-2 focus:ring-blue-500/20 focus:outline-none"
              />
              {fieldErrors.beneficiaryName && (
                <p className="mt-1 text-xs font-medium text-red-600">
                  {fieldErrors.beneficiaryName}
                </p>
              )}
            </div>

            <div>
              <label
                htmlFor="beneficiaryAccountNumber"
                className="block text-xs font-semibold text-slate-700 uppercase"
              >
                Beneficiary Account / IBAN
              </label>
              <input
                id="beneficiaryAccountNumber"
                type="text"
                maxLength={34}
                value={beneficiaryAccountNumber}
                onChange={(e) => setBeneficiaryAccountNumber(e.target.value)}
                disabled={isSubmitting}
                placeholder="e.g. DE89370400440532013000"
                className="mt-1.5 block w-full rounded-lg border border-slate-300 bg-white px-3.5 py-2.5 font-mono text-sm text-slate-900 shadow-2xs focus:border-blue-600 focus:ring-2 focus:ring-blue-500/20 focus:outline-none"
              />
              {fieldErrors.beneficiaryAccountNumber ? (
                <p className="mt-1 text-xs font-medium text-red-600">
                  {fieldErrors.beneficiaryAccountNumber}
                </p>
              ) : (
                <p className="mt-1.5 text-xs text-slate-500">
                  Automatically masked to last 4 digits in API responses.
                </p>
              )}
            </div>

            <div>
              <label
                htmlFor="beneficiaryBankBic"
                className="block text-xs font-semibold text-slate-700 uppercase"
              >
                Beneficiary Bank BIC (ISO 9362)
              </label>
              <input
                id="beneficiaryBankBic"
                type="text"
                maxLength={11}
                value={beneficiaryBankBic}
                onChange={(e) =>
                  setBeneficiaryBankBic(e.target.value.toUpperCase())
                }
                disabled={isSubmitting}
                placeholder="e.g. DEUTDEFFXXX"
                className="mt-1.5 block w-full rounded-lg border border-slate-300 bg-white px-3.5 py-2.5 font-mono text-sm uppercase text-slate-900 shadow-2xs focus:border-blue-600 focus:ring-2 focus:ring-blue-500/20 focus:outline-none"
              />
              {fieldErrors.beneficiaryBankBic ? (
                <p className="mt-1 text-xs font-medium text-red-600">
                  {fieldErrors.beneficiaryBankBic}
                </p>
              ) : (
                <p className="mt-1.5 text-xs text-slate-500">
                  8 or 11-character SWIFT/BIC identifier.
                </p>
              )}
            </div>
          </div>
        </div>

        <div className="flex items-start gap-2.5 rounded-xl border border-slate-200 bg-slate-50 p-3.5 text-xs text-slate-600">
          <Info className="mt-0.5 h-4 w-4 shrink-0 text-blue-600" />
          <p>
            Submitting this instruction sends a CSRF-protected{" "}
            <code className="font-mono font-semibold text-slate-800">
              POST /api/v1/portal/payments
            </code>{" "}
            request. Re-submitting the same{" "}
            <code className="font-mono">paymentReference</code> with identical
            parameters is conflict-safe and idempotent.
          </p>
        </div>

        <div className="flex items-center justify-end gap-3 pt-2">
          <button
            type="submit"
            disabled={isSubmitting || accounts.length === 0}
            className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-5 py-2.5 text-sm font-semibold text-white shadow-xs transition hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
          >
            <Send className="h-4 w-4" />
            <span>
              {isSubmitting
                ? "Submitting Instruction..."
                : "Review & Initiate Payment"}
            </span>
          </button>
        </div>
      </form>

      <ConfirmationDialog
        open={pendingRequest !== null}
        title="Confirm Cross-Border Payment Instruction"
        description="Please verify the payment instruction details before submitting to the CrossPayNet gateway."
        confirmLabel="Authorize & Submit Payment"
        isSubmitting={isSubmitting}
        onCancel={() => setPendingRequest(null)}
        onConfirm={handleConfirmSubmission}
      >
        {pendingRequest && (
          <dl className="grid grid-cols-2 gap-x-4 gap-y-2.5 text-xs">
            <dt className="text-slate-500">Payment Reference</dt>
            <dd className="font-mono font-semibold text-slate-900">
              {pendingRequest.paymentReference}
            </dd>
            <dt className="text-slate-500">Transfer Amount</dt>
            <dd className="font-mono font-semibold text-slate-900">
              {formatCurrencyAmount(
                pendingRequest.amount,
                pendingRequest.currency,
                { exactScale: true }
              )}
            </dd>
            <dt className="text-slate-500">Beneficiary Name</dt>
            <dd className="font-medium text-slate-900">
              {pendingRequest.beneficiaryName}
            </dd>
            <dt className="text-slate-500">Beneficiary Account</dt>
            <dd className="font-mono text-slate-900">
              {pendingRequest.beneficiaryAccountNumber}
            </dd>
            <dt className="text-slate-500">Correspondent Bank BIC</dt>
            <dd className="font-mono font-semibold text-slate-900">
              {pendingRequest.beneficiaryBankBic}
            </dd>
          </dl>
        )}
      </ConfirmationDialog>
    </>
  );
}
