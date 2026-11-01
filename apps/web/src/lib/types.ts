/**
 * Strict TypeScript contracts matching the CrossPayNet Spring Boot backend DTOs.
 */

export type SupportedCurrency =
  | "USD"
  | "EUR"
  | "GBP"
  | "INR"
  | "JPY"
  | "CHF"
  | "SGD"
  | "AED";

export const SUPPORTED_CURRENCIES: readonly SupportedCurrency[] = [
  "USD",
  "EUR",
  "GBP",
  "INR",
  "JPY",
  "CHF",
  "SGD",
  "AED",
] as const;

export type PaymentStatus =
  | "VALIDATING"
  | "COMPLIANCE_HOLD"
  | "ROUTING"
  | "EXECUTING"
  | "PENDING_EXTERNAL"
  | "SETTLED"
  | "REJECTED"
  | "CANCELLED"
  | "FAILED";

export const ALL_PAYMENT_STATUSES: readonly PaymentStatus[] = [
  "VALIDATING",
  "COMPLIANCE_HOLD",
  "ROUTING",
  "EXECUTING",
  "PENDING_EXTERNAL",
  "SETTLED",
  "REJECTED",
  "CANCELLED",
  "FAILED",
] as const;

/**
 * Matches com.crosspaynet.security.CsrfController.CsrfTokenResponse
 */
export interface CsrfTokenResponse {
  token: string;
  headerName: string;
  parameterName: string;
}

/**
 * Matches com.crosspaynet.security.portal.web.CustomerAccountResponse
 */
export interface CustomerAccountResponse {
  accountId: string;
  maskedAccountNumber: string;
  currency: string;
  status: string;
  availableBalance: number;
  reservedAmount: number;
  totalBalance: number;
}

/**
 * Matches com.crosspaynet.security.portal.web.CustomerPaymentResponse
 */
export interface CustomerPaymentResponse {
  paymentId: string;
  paymentReference: string;
  sourceAccountId: string;
  amount: number;
  currency: string;
  status: PaymentStatus;
  beneficiaryName: string;
  beneficiaryAccountNumber: string;
  beneficiaryBankBic: string;
  statusReasonCode: string | null;
  statusReasonDetail: string | null;
  createdAt: string;
  updatedAt: string;
}

/**
 * Matches com.crosspaynet.security.portal.web.PagedResponse<T>
 */
export interface PagedResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

/**
 * Matches com.crosspaynet.security.portal.web.InitiatePaymentRequest
 */
export interface InitiatePaymentRequest {
  sourceAccountId: string;
  paymentReference: string;
  amount: number;
  currency: SupportedCurrency;
  beneficiaryName: string;
  beneficiaryAccountNumber: string;
  beneficiaryBankBic: string;
}

/**
 * Matches com.crosspaynet.security.portal.web.ApiErrorResponse
 */
export interface ApiErrorResponse {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
}
