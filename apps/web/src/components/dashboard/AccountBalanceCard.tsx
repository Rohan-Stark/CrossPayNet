import React from "react";
import { Building2, Lock, Wallet } from "lucide-react";
import type { CustomerAccountResponse } from "@/lib/types";
import { formatCurrencyAmount } from "@/lib/formatters";
import { cn } from "@/lib/utils";

export function AccountBalanceCard({
  account,
}: {
  account: CustomerAccountResponse;
}) {
  const isActive = account.status === "ACTIVE";

  return (
    <div className="overflow-hidden rounded-2xl border border-slate-200 bg-white shadow-xs transition hover:border-slate-300">
      <div className="border-b border-slate-100 bg-slate-900 px-6 py-4 text-white">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-center gap-2.5">
            <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-blue-600/20 text-blue-400">
              <Building2 className="h-4 w-4" />
            </div>
            <div>
              <span className="block text-xs font-medium text-slate-400">
                Settlement Account ({account.currency})
              </span>
              <span className="font-mono text-sm font-semibold tracking-wider text-white">
                {account.maskedAccountNumber}
              </span>
            </div>
          </div>
          <span
            className={cn(
              "inline-flex items-center rounded-full px-2.5 py-0.5 text-[11px] font-semibold tracking-wide uppercase",
              isActive
                ? "bg-emerald-500/20 text-emerald-300"
                : "bg-amber-500/20 text-amber-300"
            )}
          >
            {account.status}
          </span>
        </div>
      </div>

      <div className="p-6">
        <div>
          <span className="text-xs font-medium tracking-wide text-slate-500 uppercase">
            Available Balance
          </span>
          <div className="mt-1 flex items-baseline gap-2">
            <span className="text-2xl font-bold tracking-tight text-slate-900 sm:text-3xl">
              {formatCurrencyAmount(account.availableBalance, account.currency)}
            </span>
            <span className="text-xs font-semibold text-slate-500">
              {account.currency}
            </span>
          </div>
        </div>

        <div className="mt-5 grid grid-cols-2 gap-4 border-t border-slate-100 pt-4">
          <div className="flex items-start gap-2">
            <Lock className="mt-0.5 h-4 w-4 shrink-0 text-amber-600" />
            <div>
              <span className="block text-xs text-slate-500">
                Reserved Funds
              </span>
              <span className="mt-0.5 block font-mono text-sm font-semibold text-slate-800">
                {formatCurrencyAmount(account.reservedAmount, account.currency)}
              </span>
            </div>
          </div>

          <div className="flex items-start gap-2">
            <Wallet className="mt-0.5 h-4 w-4 shrink-0 text-blue-600" />
            <div>
              <span className="block text-xs text-slate-500">
                Total Balance
              </span>
              <span className="mt-0.5 block font-mono text-sm font-semibold text-slate-800">
                {formatCurrencyAmount(account.totalBalance, account.currency)}
              </span>
            </div>
          </div>
        </div>

        <div className="mt-4 border-t border-slate-100 pt-3">
          <p className="truncate font-mono text-[11px] text-slate-400">
            Account ID: {account.accountId}
          </p>
        </div>
      </div>
    </div>
  );
}
