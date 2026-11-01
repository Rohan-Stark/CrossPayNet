"use client";

import React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  ArrowRightLeft,
  Globe,
  LayoutDashboard,
  Send,
  ShieldCheck,
} from "lucide-react";
import { cn } from "@/lib/utils";

export interface NavItem {
  label: string;
  href: string;
  icon: React.ComponentType<{ className?: string }>;
  exact?: boolean;
}

export const PORTAL_NAV_ITEMS: readonly NavItem[] = [
  {
    label: "Dashboard",
    href: "/portal/dashboard",
    icon: LayoutDashboard,
    exact: true,
  },
  {
    label: "Initiate Payment",
    href: "/portal/payments/new",
    icon: Send,
    exact: true,
  },
  {
    label: "Payment History",
    href: "/portal/payments",
    icon: ArrowRightLeft,
    exact: true,
  },
];

export function AppSidebar({
  mobileOpen = false,
  onCloseMobile,
}: {
  mobileOpen?: boolean;
  onCloseMobile?: () => void;
}) {
  const pathname = usePathname();

  const isActive = (item: NavItem) => {
    if (item.exact) {
      if (
        item.href === "/portal/payments" &&
        pathname.startsWith("/portal/payments/") &&
        pathname !== "/portal/payments/new"
      ) {
        return true;
      }
      return pathname === item.href;
    }
    return pathname.startsWith(item.href);
  };

  return (
    <>
      {mobileOpen && (
        <div
          className="fixed inset-0 z-30 bg-slate-950/50 backdrop-blur-xs lg:hidden"
          onClick={onCloseMobile}
          aria-hidden="true"
        />
      )}
      <aside
        aria-label="Primary Customer Portal Navigation"
        className={cn(
          "fixed inset-y-0 left-0 z-40 flex w-64 flex-col border-r border-slate-800 bg-slate-950 text-slate-200 transition-transform duration-200 lg:static lg:translate-x-0",
          mobileOpen ? "translate-x-0" : "-translate-x-full"
        )}
      >
        <div className="flex h-16 items-center gap-3 border-b border-slate-800/80 px-6">
          <div className="flex h-9 w-9 items-center justify-center rounded-lg bg-blue-600 text-white shadow-sm">
            <Globe className="h-5 w-5" />
          </div>
          <div>
            <span className="block text-base font-bold tracking-tight text-white">
              CrossPayNet
            </span>
            <span className="block text-[11px] font-medium tracking-wider text-slate-400 uppercase">
              Customer Portal
            </span>
          </div>
        </div>

        <nav className="flex-1 space-y-1 px-3 py-5">
          {PORTAL_NAV_ITEMS.map((item) => {
            const Icon = item.icon;
            const active = isActive(item);
            return (
              <Link
                key={item.href}
                href={item.href}
                onClick={onCloseMobile}
                className={cn(
                  "flex items-center gap-3 rounded-lg px-3.5 py-2.5 text-sm font-medium transition-colors",
                  active
                    ? "bg-blue-600 text-white shadow-xs"
                    : "text-slate-300 hover:bg-slate-900 hover:text-white"
                )}
              >
                <Icon
                  className={cn(
                    "h-4 w-4 shrink-0",
                    active ? "text-white" : "text-slate-400"
                  )}
                />
                <span>{item.label}</span>
              </Link>
            );
          })}
        </nav>

        <div className="border-t border-slate-800/80 p-4">
          <div className="rounded-xl border border-slate-800 bg-slate-900/70 p-3.5 text-xs text-slate-400">
            <div className="flex items-center gap-2 font-semibold text-slate-200">
              <ShieldCheck className="h-4 w-4 text-emerald-400" />
              <span>Educational Simulator</span>
            </div>
            <p className="mt-1.5 leading-relaxed">
              ISO 20022-inspired cross-border messaging &amp; settlement engine.
              Does not process real funds.
            </p>
          </div>
        </div>
      </aside>
    </>
  );
}
