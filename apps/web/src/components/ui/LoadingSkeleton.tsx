import React from "react";
import { cn } from "@/lib/utils";

export function Skeleton({ className }: { className?: string }) {
  return (
    <div
      className={cn(
        "animate-pulse rounded-md bg-slate-200/80",
        className
      )}
      aria-hidden="true"
    />
  );
}

export function DashboardLoadingSkeleton() {
  return (
    <div className="space-y-6" aria-busy="true" aria-label="Loading dashboard data">
      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {[1, 2, 3].map((i) => (
          <div
            key={i}
            className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs"
          >
            <Skeleton className="h-4 w-28" />
            <Skeleton className="mt-3 h-8 w-40" />
            <Skeleton className="mt-4 h-3 w-36" />
          </div>
        ))}
      </div>
      <div className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs">
        <Skeleton className="h-5 w-48" />
        <div className="mt-5 space-y-3">
          {[1, 2, 3, 4].map((row) => (
            <Skeleton key={row} className="h-12 w-full" />
          ))}
        </div>
      </div>
    </div>
  );
}

export function TableLoadingSkeleton({ rows = 5 }: { rows?: number }) {
  return (
    <div
      className="rounded-xl border border-slate-200 bg-white p-6 shadow-xs"
      aria-busy="true"
      aria-label="Loading payment records"
    >
      <div className="space-y-3">
        {Array.from({ length: rows }).map((_, idx) => (
          <Skeleton key={idx} className="h-12 w-full" />
        ))}
      </div>
    </div>
  );
}
