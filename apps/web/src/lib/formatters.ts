import { SUPPORTED_CURRENCIES, type SupportedCurrency } from "./types";

const CURRENCY_LOCALE_MAP: Record<string, string> = {
  USD: "en-US",
  EUR: "de-DE",
  GBP: "en-GB",
  INR: "en-IN",
  JPY: "ja-JP",
  CHF: "de-CH",
  SGD: "en-SG",
  AED: "en-AE",
};

/**
 * Formats a monetary value consistently using Intl.NumberFormat with up to 4 fractional digits
 * matching the backend Money scale (scale = 4, minimum 2 displayed fractional digits).
 */
export function formatCurrencyAmount(
  amount: number | string | null | undefined,
  currencyCode: string = "USD",
  options?: { exactScale?: boolean }
): string {
  if (amount === null || amount === undefined || amount === "") {
    return "—";
  }
  const numeric = typeof amount === "string" ? Number(amount) : amount;
  if (Number.isNaN(numeric)) {
    return "—";
  }

  const normalizedCurrency = currencyCode.trim().toUpperCase();
  const locale = CURRENCY_LOCALE_MAP[normalizedCurrency] ?? "en-US";

  // Check if the fractional part has significant digits beyond 2 decimal places
  const fixed4 = numeric.toFixed(4);
  const hasExtendedDecimals = !fixed4.endsWith("00");
  const minFractionDigits = options?.exactScale ? 4 : hasExtendedDecimals ? 4 : 2;

  try {
    return new Intl.NumberFormat(locale, {
      style: "currency",
      currency: normalizedCurrency,
      minimumFractionDigits: minFractionDigits,
      maximumFractionDigits: 4,
    }).format(numeric);
  } catch {
    return `${numeric.toFixed(minFractionDigits)} ${normalizedCurrency}`;
  }
}

/**
 * Formats an ISO-8601 timestamp string into a clear human-readable date and time.
 */
export function formatDateTime(isoString: string | null | undefined): string {
  if (!isoString) {
    return "—";
  }
  const date = new Date(isoString);
  if (Number.isNaN(date.getTime())) {
    return isoString;
  }
  return new Intl.DateTimeFormat("en-US", {
    year: "numeric",
    month: "short",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
    timeZoneName: "short",
  }).format(date);
}

/**
 * Formats an ISO-8601 timestamp into a compact date/time for table rows.
 */
export function formatShortDateTime(isoString: string | null | undefined): string {
  if (!isoString) {
    return "—";
  }
  const date = new Date(isoString);
  if (Number.isNaN(date.getTime())) {
    return isoString;
  }
  return new Intl.DateTimeFormat("en-US", {
    month: "short",
    day: "2-digit",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  }).format(date);
}

/**
 * Validates an ISO 9362 Bank Identifier Code (BIC / SWIFT code) matching BicCode.java:
 * ^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$
 */
export const BIC_REGEX = /^[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?$/;

export function isValidBic(bic: string): boolean {
  return BIC_REGEX.test(bic.trim());
}

export function isSupportedCurrency(code: string): code is SupportedCurrency {
  return (SUPPORTED_CURRENCIES as readonly string[]).includes(code.trim().toUpperCase());
}

/**
 * Validates a monetary amount string against PaymentInstruction limits:
 * - Strictly positive (> 0)
 * - At most 4 decimal places
 * - Maximum 999999999999999.9999
 */
export function validateMonetaryAmount(rawAmount: string): {
  valid: boolean;
  numericValue?: number;
  error?: string;
} {
  const trimmed = rawAmount.trim();
  if (!trimmed) {
    return { valid: false, error: "Payment amount is required." };
  }
  if (!/^\d+(\.\d+)?$/.test(trimmed)) {
    return { valid: false, error: "Enter a valid positive numeric amount." };
  }
  const parts = trimmed.split(".");
  if (parts[1] && parts[1].length > 4) {
    return {
      valid: false,
      error: "Payment amount scale cannot exceed 4 decimal places.",
    };
  }
  if (parts[0].replace(/^0+/, "").length > 15) {
    return {
      valid: false,
      error: "Payment amount exceeds maximum allowed value.",
    };
  }
  const num = Number(trimmed);
  if (!Number.isFinite(num) || num <= 0) {
    return { valid: false, error: "Payment amount must be strictly positive." };
  }
  return { valid: true, numericValue: num };
}
