"use client";

import { useEffect } from "react";
import { useRouter } from "next/navigation";
import { useAuth } from "@/lib/auth-context";

export default function HomePage() {
  const { isAuthenticated } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (isAuthenticated) {
      router.replace("/portal/dashboard");
    } else {
      router.replace("/login");
    }
  }, [isAuthenticated, router]);

  return (
    <main className="flex min-h-screen items-center justify-center bg-slate-950 text-slate-300">
      <div className="text-center">
        <p className="text-sm font-medium">Loading CrossPayNet Customer Portal...</p>
      </div>
    </main>
  );
}
