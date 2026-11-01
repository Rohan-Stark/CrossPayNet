import React from "react";
import type { PaymentStatus } from "@/lib/types";
import { cn } from "@/lib/utils";

interface StatusConfig {
  label: string;
  badgeClass: string;
  dotClass: string;
}

const STATUS_STYLES: Record<PaymentStatus, StatusConfig> = {
  SETTLED: {
    label: "Settled",
    badgeClass: "border-emerald-200 bg-emerald-50 text-emerald-800",
    dotClass: "bg-emerald-500",
  },
  VALIDATING: {
    label: "Validating",
    badgeClass: "border-blue-200 bg-blue-50 text-blue-800",
    dotClass: "bg-blue-500",
  },
  COMPLIANCE_HOLD: {
    label: "Compliance Hold",
    badgeClass: "border-amber-200 bg-amber-50 text-amber-800",
    dotClass: "bg-amber-500",
  },
  ROUTING: {
    label: "Routing",
    badgeClass: "border-blue-200 bg-blue-50 text-blue-800",
    dotClass: "bg-blue-500",
  },
  EXECUTING: {
    label: "Executing",
    badgeClass: "border-indigo-200 bg-indigo-50 text-indigo-800",
    dotClass: "bg-indigo-500",
  },
  PENDING_EXTERNAL: {
    label: "Pending External",
    badgeClass: "border-amber-200 bg-amber-50 text-amber-800",
    dotClass: "bg-amber-500",
  },
  REJECTED: {
    label: "Rejected",
    badgeClass: "border-red-200 bg-red-50 text-red-800",
    dotClass: "bg-red-500",
  },
  FAILED: {
    label: "Failed",
    badgeClass: "border-red-200 bg-red-50 text-red-800",
    dotClass: "bg-red-600",
  },
  CANCELLED: {
    label: "Cancelled",
    badgeClass: "border-slate-300 bg-slate-100 text-slate-700",
    dotClass: "bg-slate-500",
  },
};

export function PaymentStatusBadge({
  status,
  className,
}: {
  status: PaymentStatus;
  className?: string;
}) {
  const cfg = STATUS_STYLES[status] ?? {
    label: status,
    badgeClass: "border-slate-200 bg-slate-100 text-slate-800",
    dotClass: "bg-slate-400",
  };

  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full border px-2.5 py-0.5 text-xs font-semibold tracking-wide",
        cfg.badgeClass,
        className
      )}
    >
      <span
        className={cn("h-1.5 w-1.5 rounded-full", cfg.dotClass)}
        aria-hidden="true"
      />
      <span>{cfg.label}</span>
    </span>
  );
}
