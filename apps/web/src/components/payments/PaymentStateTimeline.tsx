import React from "react";
import {
  AlertOctagon,
  CheckCircle2,
  Circle,
  Clock,
  XCircle,
} from "lucide-react";
import type { PaymentStatus } from "@/lib/types";
import { cn } from "@/lib/utils";

export type StepState =
  | "completed"
  | "current"
  | "terminal_success"
  | "terminal_error"
  | "terminal_cancelled"
  | "upcoming"
  | "not_reached";

export interface TimelineStep {
  key: string;
  label: string;
  code: PaymentStatus;
  description: string;
  state: StepState;
}

const FORWARD_PIPELINE: ReadonlyArray<{
  code: PaymentStatus;
  label: string;
  description: string;
}> = [
  {
    code: "VALIDATING",
    label: "Instruction Validation",
    description:
      "Payment instruction claimed and debit account reservation requested.",
  },
  {
    code: "COMPLIANCE_HOLD",
    label: "Compliance & Sanctions Screening",
    description:
      "Funds reserved in Banking; instruction evaluated by compliance rules.",
  },
  {
    code: "ROUTING",
    label: "Correspondent Route Selection",
    description:
      "Compliance cleared; matching active correspondent banking route.",
  },
  {
    code: "EXECUTING",
    label: "Financial Commitment Point",
    description:
      "Correspondent route committed; preparing ISO 20022 pacs.008 dispatch.",
  },
  {
    code: "PENDING_EXTERNAL",
    label: "External Network Dispatch",
    description:
      "ISO 20022 message dispatched; awaiting correspondent acknowledgment.",
  },
  {
    code: "SETTLED",
    label: "Final Settlement",
    description:
      "Fund reservation consumed and balanced double-entry ledger posted.",
  },
];

/**
 * Derives an honest, domain-accurate timeline progression from the payment's
 * current status and statusReasonCode without fabricating intermediate states
 * or timestamps not guaranteed by the backend state machine.
 */
export function buildPaymentTimeline(
  status: PaymentStatus,
  statusReasonCode: string | null
): TimelineStep[] {
  const forwardIndex = FORWARD_PIPELINE.findIndex((s) => s.code === status);

  // Case 1: Payment is in one of the 6 standard forward pipeline states
  if (forwardIndex !== -1) {
    return FORWARD_PIPELINE.map((stage, idx) => {
      let state: StepState;
      if (idx < forwardIndex) {
        state = "completed";
      } else if (idx === forwardIndex) {
        state = status === "SETTLED" ? "terminal_success" : "current";
      } else {
        state = "upcoming";
      }
      return {
        key: stage.code,
        label: stage.label,
        code: stage.code,
        description: stage.description,
        state,
      };
    });
  }

  // Case 2: REJECTED (pre-commitment terminal state)
  // Only mark prior stages as completed when guaranteed by the rejection reason code.
  if (status === "REJECTED") {
    let lastGuaranteedCompletedIdx = -1;
    if (statusReasonCode === "COMPLIANCE_REJECTED") {
      // Reached COMPLIANCE_HOLD after VALIDATING
      lastGuaranteedCompletedIdx = 0;
    } else if (statusReasonCode === "NO_ROUTE_AVAILABLE") {
      // Cleared VALIDATING and COMPLIANCE_HOLD, rejected during ROUTING
      lastGuaranteedCompletedIdx = 1;
    }

    const steps: TimelineStep[] = FORWARD_PIPELINE.slice(0, 3).map(
      (stage, idx) => ({
        key: stage.code,
        label: stage.label,
        code: stage.code,
        description: stage.description,
        state:
          idx <= lastGuaranteedCompletedIdx ? "completed" : "not_reached",
      })
    );

    steps.push({
      key: "REJECTED",
      label: "Payment Rejected (Pre-Commitment)",
      code: "REJECTED",
      description: statusReasonCode
        ? `Terminated prior to financial commitment (${statusReasonCode}). Any active fund reservation is released.`
        : "Terminated prior to financial commitment.",
      state: "terminal_error",
    });

    return steps;
  }

  // Case 3: CANCELLED (pre-commitment customer cancellation)
  if (status === "CANCELLED") {
    return [
      {
        key: "VALIDATING",
        label: "Instruction Validation",
        code: "VALIDATING",
        description: "Payment instruction received prior to cancellation.",
        state: "completed",
      },
      {
        key: "CANCELLED",
        label: "Payment Cancelled",
        code: "CANCELLED",
        description:
          "Halted before the Financial Commitment Point; reserved funds released.",
        state: "terminal_cancelled",
      },
    ];
  }

  // Case 4: FAILED (post-commitment failure)
  // By Payment domain invariant, FAILED only occurs after reaching EXECUTING (Financial Commitment Point).
  return [
    ...FORWARD_PIPELINE.slice(0, 4).map((stage) => ({
      key: stage.code,
      label: stage.label,
      code: stage.code,
      description: stage.description,
      state: "completed" as StepState,
    })),
    {
      key: "FAILED",
      label: "Post-Commitment Execution Failure",
      code: "FAILED",
      description: statusReasonCode
        ? `Payment failed after commitment (${statusReasonCode}).`
        : "Payment failed during external execution or settlement.",
      state: "terminal_error",
    },
  ];
}

export function PaymentStateTimeline({
  status,
  statusReasonCode,
  statusReasonDetail,
}: {
  status: PaymentStatus;
  statusReasonCode: string | null;
  statusReasonDetail: string | null;
}) {
  const steps = buildPaymentTimeline(status, statusReasonCode);

  return (
    <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-slate-100 pb-4">
        <div>
          <h2 className="text-base font-semibold text-slate-900">
            Lifecycle State Progression
          </h2>
          <p className="mt-0.5 text-xs text-slate-500">
            Reflects verified domain state transitions without synthetic
            intermediate timestamps.
          </p>
        </div>
        <span className="rounded-md bg-slate-100 px-2.5 py-1 font-mono text-xs font-semibold text-slate-700">
          {status}
        </span>
      </div>

      <ol className="mt-6 space-y-5" aria-label="Payment state progression">
        {steps.map((step, index) => {
          const isLast = index === steps.length - 1;
          return (
            <li key={step.key} className="relative flex items-start gap-4">
              {!isLast && (
                <span
                  aria-hidden="true"
                  className={cn(
                    "absolute top-7 left-3.5 -ml-px h-full w-0.5",
                    step.state === "completed"
                      ? "bg-emerald-500"
                      : "bg-slate-200"
                  )}
                />
              )}

              <div className="relative flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-white">
                {step.state === "completed" && (
                  <CheckCircle2 className="h-6 w-6 text-emerald-600" />
                )}
                {step.state === "terminal_success" && (
                  <CheckCircle2 className="h-6 w-6 text-emerald-600" />
                )}
                {step.state === "current" && (
                  <Clock className="h-6 w-6 text-blue-600" />
                )}
                {step.state === "terminal_error" && (
                  <XCircle className="h-6 w-6 text-red-600" />
                )}
                {step.state === "terminal_cancelled" && (
                  <AlertOctagon className="h-6 w-6 text-slate-600" />
                )}
                {(step.state === "upcoming" ||
                  step.state === "not_reached") && (
                  <Circle className="h-5 w-5 text-slate-300" />
                )}
              </div>

              <div className="min-w-0 flex-1 pb-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span
                    className={cn(
                      "text-sm font-semibold",
                      step.state === "completed" ||
                        step.state === "terminal_success"
                        ? "text-slate-900"
                        : step.state === "current"
                        ? "text-blue-700"
                        : step.state === "terminal_error"
                        ? "text-red-700"
                        : step.state === "terminal_cancelled"
                        ? "text-slate-800"
                        : "text-slate-400"
                    )}
                  >
                    {step.label}
                  </span>
                  <span className="rounded bg-slate-100 px-1.5 py-0.5 font-mono text-[11px] text-slate-600">
                    {step.code}
                  </span>
                  {step.state === "current" && (
                    <span className="rounded-full bg-blue-100 px-2 py-0.5 text-[10px] font-semibold text-blue-800 uppercase">
                      Current State
                    </span>
                  )}
                  {step.state === "terminal_success" && (
                    <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-[10px] font-semibold text-emerald-800 uppercase">
                      Terminal &bull; Settled
                    </span>
                  )}
                  {step.state === "terminal_error" && (
                    <span className="rounded-full bg-red-100 px-2 py-0.5 text-[10px] font-semibold text-red-800 uppercase">
                      Terminal &bull; Halted
                    </span>
                  )}
                </div>
                <p
                  className={cn(
                    "mt-1 text-xs",
                    step.state === "upcoming" || step.state === "not_reached"
                      ? "text-slate-400"
                      : "text-slate-600"
                  )}
                >
                  {step.description}
                </p>
                {step.state === "terminal_error" &&
                  (statusReasonCode || statusReasonDetail) && (
                    <div className="mt-2.5 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-900">
                      {statusReasonCode && (
                        <div className="font-mono font-semibold">
                          Reason Code: {statusReasonCode}
                        </div>
                      )}
                      {statusReasonDetail && (
                        <div className="mt-0.5 text-red-800">
                          {statusReasonDetail}
                        </div>
                      )}
                    </div>
                  )}
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
