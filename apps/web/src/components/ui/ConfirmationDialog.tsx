"use client";

import React, { useEffect } from "react";
import { AlertTriangle, X } from "lucide-react";

export interface ConfirmationDialogProps {
  open: boolean;
  title: string;
  description: string;
  confirmLabel?: string;
  cancelLabel?: string;
  isSubmitting?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
  children?: React.ReactNode;
}

export function ConfirmationDialog({
  open,
  title,
  description,
  confirmLabel = "Confirm & Submit",
  cancelLabel = "Cancel",
  isSubmitting = false,
  onConfirm,
  onCancel,
  children,
}: ConfirmationDialogProps) {
  useEffect(() => {
    if (!open) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !isSubmitting) {
        onCancel();
      }
    };
    window.addEventListener("keydown", handleKeyDown);
    return () => window.removeEventListener("keydown", handleKeyDown);
  }, [open, isSubmitting, onCancel]);

  if (!open) return null;

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-slate-950/60 p-4 backdrop-blur-xs"
      role="dialog"
      aria-modal="true"
      aria-labelledby="confirm-dialog-title"
    >
      <div className="w-full max-w-lg rounded-2xl border border-slate-200 bg-white p-6 shadow-xl">
        <div className="flex items-start justify-between gap-4">
          <div className="flex items-center gap-3">
            <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-blue-50 text-blue-600">
              <AlertTriangle className="h-5 w-5" />
            </div>
            <div>
              <h2
                id="confirm-dialog-title"
                className="text-lg font-semibold text-slate-900"
              >
                {title}
              </h2>
              <p className="mt-0.5 text-sm text-slate-600">{description}</p>
            </div>
          </div>
          <button
            type="button"
            onClick={onCancel}
            disabled={isSubmitting}
            aria-label="Close dialog"
            className="rounded-lg p-1.5 text-slate-400 transition hover:bg-slate-100 hover:text-slate-600 disabled:opacity-50"
          >
            <X className="h-4 w-4" />
          </button>
        </div>

        {children && (
          <div className="mt-4 rounded-xl border border-slate-200 bg-slate-50 p-4 text-sm text-slate-700">
            {children}
          </div>
        )}

        <div className="mt-6 flex items-center justify-end gap-3">
          <button
            type="button"
            onClick={onCancel}
            disabled={isSubmitting}
            className="rounded-lg border border-slate-300 bg-white px-4 py-2 text-sm font-medium text-slate-700 transition hover:bg-slate-50 focus:ring-2 focus:ring-slate-400 focus:outline-none disabled:opacity-50"
          >
            {cancelLabel}
          </button>
          <button
            type="button"
            onClick={onConfirm}
            disabled={isSubmitting}
            className="inline-flex items-center justify-center rounded-lg bg-blue-600 px-4 py-2 text-sm font-semibold text-white shadow-xs transition hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
          >
            {isSubmitting ? "Submitting Instruction..." : confirmLabel}
          </button>
        </div>
      </div>
    </div>
  );
}
