import type { Metadata } from "next";
import "./globals.css";
import { AuthProvider } from "@/lib/auth-context";

export const metadata: Metadata = {
  title: "CrossPayNet — Customer Payment Portal",
  description:
    "Educational distributed cross-border payment messaging and settlement simulator customer portal.",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body className="min-h-screen bg-slate-50 text-slate-900">
        <AuthProvider>{children}</AuthProvider>
      </body>
    </html>
  );
}
