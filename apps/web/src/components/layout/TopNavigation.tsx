"use client";

import React from "react";
import { useRouter } from "next/navigation";
import { LogOut, Menu, Shield, UserCheck } from "lucide-react";
import { useAuth } from "@/lib/auth-context";

export function TopNavigation({
  onOpenMobileMenu,
}: {
  onOpenMobileMenu: () => void;
}) {
  const { username, logout } = useAuth();
  const router = useRouter();

  const handleLogout = () => {
    logout();
    router.replace("/login");
  };

  return (
    <header className="sticky top-0 z-20 flex h-16 items-center justify-between border-b border-slate-200 bg-white/95 px-4 backdrop-blur-xs sm:px-6 lg:px-8">
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={onOpenMobileMenu}
          aria-label="Open navigation menu"
          className="rounded-lg p-2 text-slate-600 hover:bg-slate-100 lg:hidden"
        >
          <Menu className="h-5 w-5" />
        </button>
        <div className="hidden items-center gap-2 rounded-full border border-blue-200 bg-blue-50/70 px-3 py-1 text-xs font-medium text-blue-800 sm:inline-flex">
          <Shield className="h-3.5 w-3.5 text-blue-600" />
          <span>CSRF Protected &bull; In-Memory Session</span>
        </div>
      </div>

      <div className="flex items-center gap-4">
        {username && (
          <div className="flex items-center gap-2 rounded-lg border border-slate-200 bg-slate-50 px-3 py-1.5 text-xs font-medium text-slate-700">
            <UserCheck className="h-3.5 w-3.5 text-emerald-600" />
            <span>Principal:</span>
            <span className="font-mono font-semibold text-slate-900">
              {username}
            </span>
            <span className="rounded bg-slate-200 px-1.5 py-0.5 text-[10px] font-semibold text-slate-700 uppercase">
              ROLE_CUSTOMER
            </span>
          </div>
        )}
        <button
          type="button"
          onClick={handleLogout}
          className="inline-flex items-center gap-1.5 rounded-lg border border-slate-300 bg-white px-3 py-1.5 text-xs font-semibold text-slate-700 shadow-2xs transition hover:bg-slate-50 hover:text-slate-900 focus:ring-2 focus:ring-blue-500 focus:outline-none"
        >
          <LogOut className="h-3.5 w-3.5 text-slate-500" />
          <span>Sign Out</span>
        </button>
      </div>
    </header>
  );
}
