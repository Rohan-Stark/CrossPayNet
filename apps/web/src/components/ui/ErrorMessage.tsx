import React from "react";
import { AlertCircle, RefreshCw } from "lucide-react";
import { cn } from "@/lib/utils";

export interface ErrorMessageProps {
  title?: string;
  message: string;
  statusCode?: number;
  onRetry?: () => void;
  className?: string;
}

export function ErrorMessage({
  title = "Unable to complete request",
  message,
  statusCode,
  onRetry,
  className,
}: ErrorMessageProps) {
  return (
    <div
      role="alert"
      className={cn(
        "rounded-xl border border-red-200 bg-red-50/90 p-4 text-red-900",
        className
      )}
    >
      <div className="flex items-start gap-3">
        <AlertCircle className="mt-0.5 h-5 w-5 shrink-0 text-red-600" />
        <div className="flex-1 text-sm">
          <div className="flex items-center gap-2 font-semibold text-red-900">
            <span>{title}</span>
            {statusCode !== undefined && statusCode > 0 && (
              <span className="rounded bg-red-100 px-1.5 py-0.5 font-mono text-xs text-red-700">
                HTTP {statusCode}
              </span>
            )}
          </div>
          <p className="mt-1 text-red-800">{message}</p>
          {onRetry && (
            <button
              type="button"
              onClick={onRetry}
              className="mt-3 inline-flex items-center gap-1.5 rounded-lg border border-red-300 bg-white px-3 py-1.5 text-xs font-medium text-red-800 transition hover:bg-red-50 focus:ring-2 focus:ring-red-500 focus:outline-none"
            >
              <RefreshCw className="h-3.5 w-3.5" />
              Retry Request
            </button>
          )}
        </div>
      </div>
    </div>
  );
}
