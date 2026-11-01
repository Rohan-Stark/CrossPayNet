import { describe, expect, it } from "vitest";
import { validatePaymentFormInput } from "@/components/payments/PaymentForm";
import { buildPaymentTimeline } from "@/components/payments/PaymentStateTimeline";
import { formatCurrencyAmount, isValidBic, validateMonetaryAmount } from "@/lib/formatters";

describe("Payment Form Validation & Domain State Progression", () => {
  it("validates monetary amounts up to 4 decimal places and rejects non-positive values", () => {
    expect(validateMonetaryAmount("500.0000")).toEqual({
      valid: true,
      numericValue: 500,
    });
    expect(validateMonetaryAmount("0")).toEqual({
      valid: false,
      error: "Payment amount must be strictly positive.",
    });
    expect(validateMonetaryAmount("-10.50")).toEqual({
      valid: false,
      error: "Enter a valid positive numeric amount.",
    });
    expect(validateMonetaryAmount("10.12345")).toEqual({
      valid: false,
      error: "Payment amount scale cannot exceed 4 decimal places.",
    });
  });

  it("validates ISO 9362 8 and 11 character BIC codes", () => {
    expect(isValidBic("DEUTDEFF")).toBe(true);
    expect(isValidBic("DEUTDEFFXXX")).toBe(true);
    expect(isValidBic("NWBKGB2LXXX")).toBe(true);
    expect(isValidBic("INVALID")).toBe(false);
    expect(isValidBic("1234DEFFXXX")).toBe(false);
  });

  it("validates complete InitiatePaymentRequest form input", () => {
    const validResult = validatePaymentFormInput({
      sourceAccountId: "550e8400-e29b-41d4-a716-446655440000",
      paymentReference: "PAY-20261101-TEST",
      amount: "1250.5000",
      currency: "USD",
      beneficiaryName: "Siemens AG",
      beneficiaryAccountNumber: "DE89370400440532013000",
      beneficiaryBankBic: "DEUTDEFFXXX",
    });

    expect(validResult.valid).toBe(true);
    expect(validResult.parsedRequest).toEqual({
      sourceAccountId: "550e8400-e29b-41d4-a716-446655440000",
      paymentReference: "PAY-20261101-TEST",
      amount: 1250.5,
      currency: "USD",
      beneficiaryName: "Siemens AG",
      beneficiaryAccountNumber: "DE89370400440532013000",
      beneficiaryBankBic: "DEUTDEFFXXX",
    });
  });

  it("does not imply unreached stages were completed when a payment is REJECTED for INSUFFICIENT_FUNDS", () => {
    const timeline = buildPaymentTimeline("REJECTED", "INSUFFICIENT_FUNDS");
    const validatingStep = timeline.find((s) => s.code === "VALIDATING");
    const complianceStep = timeline.find((s) => s.code === "COMPLIANCE_HOLD");
    const rejectedStep = timeline.find((s) => s.code === "REJECTED");

    expect(validatingStep?.state).toBe("not_reached");
    expect(complianceStep?.state).toBe("not_reached");
    expect(rejectedStep?.state).toBe("terminal_error");
  });

  it("marks VALIDATING and COMPLIANCE_HOLD completed when REJECTED for NO_ROUTE_AVAILABLE", () => {
    const timeline = buildPaymentTimeline("REJECTED", "NO_ROUTE_AVAILABLE");
    expect(timeline.find((s) => s.code === "VALIDATING")?.state).toBe("completed");
    expect(timeline.find((s) => s.code === "COMPLIANCE_HOLD")?.state).toBe("completed");
    expect(timeline.find((s) => s.code === "ROUTING")?.state).toBe("not_reached");
    expect(timeline.find((s) => s.code === "REJECTED")?.state).toBe("terminal_error");
  });

  it("formats currency amounts consistently for USD, EUR, and INR", () => {
    expect(formatCurrencyAmount(10000, "USD")).toContain("10,000.00");
    expect(formatCurrencyAmount(125.125, "USD")).toContain("125.1250");
    expect(formatCurrencyAmount(50000, "INR")).toContain("50,000.00");
  });
});
