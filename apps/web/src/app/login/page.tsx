"use client";

import React, { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Globe, Lock, ShieldCheck, ArrowRight } from "lucide-react";
import { useAuth } from "@/lib/auth-context";
import { PortalApiError } from "@/lib/api-client";
import { ErrorMessage } from "@/components/ui/ErrorMessage";

export default function LoginPage() {
  const { isAuthenticated, login } = useAuth();
  const router = useRouter();

  const [username, setUsername] = useState<string>("");
  const [password, setPassword] = useState<string>("");
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [error, setError] = useState<{
    message: string;
    status?: number;
  } | null>(null);

  useEffect(() => {
    if (isAuthenticated) {
      router.replace("/portal/dashboard");
    }
  }, [isAuthenticated, router]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    if (isLoading) return;
    setError(null);

    if (!username.trim() || !password) {
      setError({
        message: "Please enter both your customer username and password.",
      });
      return;
    }

    setIsLoading(true);
    try {
      await login(username, password);
      router.replace("/portal/dashboard");
    } catch (err) {
      if (err instanceof PortalApiError) {
        if (err.isUnauthorized) {
          setError({
            message:
              "Invalid username or password. Please verify your local simulator credentials.",
            status: 401,
          });
        } else if (err.isForbidden) {
          setError({
            message:
              "This principal does not hold ROLE_CUSTOMER permissions required for the Customer Portal.",
            status: 403,
          });
        } else {
          setError({
            message: err.message,
            status: err.status,
          });
        }
      } else if (err instanceof Error) {
        setError({ message: err.message });
      } else {
        setError({
          message: "Unable to sign in. Verify the backend is running on port 8080.",
        });
      }
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="flex min-h-screen flex-col justify-center bg-slate-950 px-4 py-12 sm:px-6 lg:px-8">
      <div className="sm:mx-auto sm:w-full sm:max-w-md">
        <div className="flex items-center justify-center gap-3">
          <div className="flex h-11 w-11 items-center justify-center rounded-xl bg-blue-600 text-white shadow-lg shadow-blue-600/30">
            <Globe className="h-6 w-6" />
          </div>
          <div>
            <span className="block text-xl font-bold tracking-tight text-white">
              CrossPayNet
            </span>
            <span className="block text-xs font-medium tracking-wider text-blue-400 uppercase">
              Customer Payment Portal
            </span>
          </div>
        </div>
        <h1 className="mt-6 text-center text-2xl font-bold tracking-tight text-white">
          Sign in to your account
        </h1>
        <p className="mt-2 text-center text-sm text-slate-400">
          Cross-border payment instruction, compliance &amp; settlement simulator
        </p>
      </div>

      <div className="mt-8 sm:mx-auto sm:w-full sm:max-w-md">
        <div className="rounded-2xl border border-slate-800 bg-white px-6 py-8 shadow-2xl sm:px-10">
          {error && (
            <ErrorMessage
              title="Authentication Failed"
              message={error.message}
              statusCode={error.status}
              className="mb-6"
            />
          )}

          <form onSubmit={handleSubmit} className="space-y-5" noValidate>
            <div>
              <label
                htmlFor="username"
                className="block text-xs font-semibold text-slate-700 uppercase"
              >
                Customer Username / Principal
              </label>
              <input
                id="username"
                name="username"
                type="text"
                autoComplete="username"
                required
                disabled={isLoading}
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                placeholder="Enter customer username"
                className="mt-1.5 block w-full rounded-lg border border-slate-300 bg-white px-3.5 py-2.5 text-sm text-slate-900 shadow-2xs focus:border-blue-600 focus:ring-2 focus:ring-blue-500/20 focus:outline-none"
              />
            </div>

            <div>
              <label
                htmlFor="password"
                className="block text-xs font-semibold text-slate-700 uppercase"
              >
                Password
              </label>
              <input
                id="password"
                name="password"
                type="password"
                autoComplete="current-password"
                required
                disabled={isLoading}
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                placeholder="Enter password"
                className="mt-1.5 block w-full rounded-lg border border-slate-300 bg-white px-3.5 py-2.5 text-sm text-slate-900 shadow-2xs focus:border-blue-600 focus:ring-2 focus:ring-blue-500/20 focus:outline-none"
              />
            </div>

            <button
              type="submit"
              disabled={isLoading}
              className="flex w-full items-center justify-center gap-2 rounded-lg bg-blue-600 px-4 py-2.5 text-sm font-semibold text-white shadow-sm transition hover:bg-blue-700 focus:ring-2 focus:ring-blue-500 focus:outline-none disabled:cursor-not-allowed disabled:opacity-60"
            >
              <span>
                {isLoading
                  ? "Verifying Credentials & CSRF..."
                  : "Sign In to Customer Portal"}
              </span>
              {!isLoading && <ArrowRight className="h-4 w-4" />}
            </button>
          </form>

          <div className="mt-6 space-y-3 border-t border-slate-100 pt-5 text-xs text-slate-500">
            <div className="flex items-start gap-2.5">
              <Lock className="mt-0.5 h-4 w-4 shrink-0 text-blue-600" />
              <p>
                <strong className="font-semibold text-slate-700">
                  Local Simulator Authentication:
                </strong>{" "}
                Uses Spring Security HTTP Basic + CSRF Cookie{" "}
                <code className="font-mono text-slate-700">(XSRF-TOKEN)</code>.
                Credentials are held strictly in active browser memory and are
                never persisted to{" "}
                <code className="font-mono">localStorage</code> or{" "}
                <code className="font-mono">sessionStorage</code>.
              </p>
            </div>
            <div className="flex items-start gap-2.5">
              <ShieldCheck className="mt-0.5 h-4 w-4 shrink-0 text-emerald-600" />
              <p>
                <strong className="font-semibold text-slate-700">
                  Educational Disclaimer:
                </strong>{" "}
                CrossPayNet is an architectural simulation and does not connect
                to real banking networks or move real money.
              </p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
