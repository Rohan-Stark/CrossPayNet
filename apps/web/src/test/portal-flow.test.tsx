import React from "react";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import LoginPage from "@/app/login/page";
import DashboardPage from "@/app/portal/dashboard/page";
import PaymentDetailPage from "@/app/portal/payments/[paymentId]/page";
import { AuthProvider } from "@/lib/auth-context";
import { portalApi } from "@/lib/api-client";

const mockReplace = vi.fn();
const mockPush = vi.fn();
let mockPaymentId = "73c6838a-3603-4b68-b715-c2db21469e38";
let mockInitiatedParam: string | null = "1";

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    replace: mockReplace,
    push: mockPush,
  }),
  usePathname: () => "/portal/dashboard",
  useParams: () => ({
    paymentId: mockPaymentId,
  }),
  useSearchParams: () => ({
    get: (key: string) => (key === "initiated" ? mockInitiatedParam : null),
  }),
}));

describe("Customer Portal UI Flows — Login, Dashboard & Payment Details", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    mockReplace.mockReset();
    mockPush.mockReset();
    mockPaymentId = "73c6838a-3603-4b68-b715-c2db21469e38";
    mockInitiatedParam = "1";
  });

  it("renders LoginPage, validates empty input, and signs in with CSRF bootstrap", async () => {
    vi.spyOn(portalApi, "initCsrf").mockResolvedValue({
      token: "csrf-token-xyz",
      headerName: "X-XSRF-TOKEN",
      parameterName: "_csrf",
    });
    vi.spyOn(portalApi, "getAccounts").mockResolvedValue([
      {
        accountId: "550e8400-e29b-41d4-a716-446655440000",
        maskedAccountNumber: "****0000",
        currency: "USD",
        status: "ACTIVE",
        availableBalance: 10000,
        reservedAmount: 0,
        totalBalance: 10000,
      },
    ]);

    render(
      <AuthProvider>
        <LoginPage />
      </AuthProvider>
    );

    expect(
      screen.getByText(/Local Simulator Authentication:/i)
    ).toBeInTheDocument();

    fireEvent.click(
      screen.getByRole("button", { name: /Sign In to Customer Portal/i })
    );
    expect(
      await screen.findByText(
        /Please enter both your customer username and password/i
      )
    ).toBeInTheDocument();

    fireEvent.change(
      screen.getByLabelText(/Customer Username \/ Principal/i),
      {
        target: { value: "customer" },
      }
    );
    fireEvent.change(screen.getByLabelText(/Password/i), {
      target: { value: "customer123!" },
    });

    fireEvent.click(
      screen.getByRole("button", { name: /Sign In to Customer Portal/i })
    );

    await waitFor(() => {
      expect(portalApi.initCsrf).toHaveBeenCalledTimes(1);
      expect(portalApi.getAccounts).toHaveBeenCalledTimes(1);
      expect(mockReplace).toHaveBeenCalledWith("/portal/dashboard");
    });
  });

  it("renders DashboardPage with real account balances and recent transactions from API", async () => {
    vi.spyOn(portalApi, "getAccounts").mockResolvedValue([
      {
        accountId: "550e8400-e29b-41d4-a716-446655440000",
        maskedAccountNumber: "****0000",
        currency: "USD",
        status: "ACTIVE",
        availableBalance: 9500,
        reservedAmount: 500,
        totalBalance: 10000,
      },
    ]);

    vi.spyOn(portalApi, "getPayments").mockResolvedValue({
      content: [
        {
          paymentId: "73c6838a-3603-4b68-b715-c2db21469e38",
          paymentReference: "PAY-20261101-0001",
          sourceAccountId: "550e8400-e29b-41d4-a716-446655440000",
          amount: 500,
          currency: "USD",
          status: "COMPLIANCE_HOLD",
          beneficiaryName: "Siemens Industrial Automation GmbH",
          beneficiaryAccountNumber: "****3000",
          beneficiaryBankBic: "DEUTDEFFXXX",
          statusReasonCode: null,
          statusReasonDetail: null,
          createdAt: "2026-11-01T10:00:00Z",
          updatedAt: "2026-11-01T10:00:00Z",
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });

    render(
      <AuthProvider>
        <DashboardPage />
      </AuthProvider>
    );

    expect(await screen.findByText("****0000")).toBeInTheDocument();
    expect(screen.getByText("$9,500.00")).toBeInTheDocument();
    expect(screen.getByText("PAY-20261101-0001")).toBeInTheDocument();
    expect(
      screen.getByText("Siemens Industrial Automation GmbH")
    ).toBeInTheDocument();
  });

  it("displays an error alert rather than a success claim when a newly initiated payment is REJECTED for INSUFFICIENT_FUNDS", async () => {
    vi.spyOn(portalApi, "getPaymentById").mockResolvedValue({
      paymentId: "73c6838a-3603-4b68-b715-c2db21469e38",
      paymentReference: "PAY-NOFUNDS-001",
      sourceAccountId: "550e8400-e29b-41d4-a716-446655440000",
      amount: 50000,
      currency: "USD",
      status: "REJECTED",
      beneficiaryName: "British Telecom PLC",
      beneficiaryAccountNumber: "****6819",
      beneficiaryBankBic: "NWBKGB2LXXX",
      statusReasonCode: "INSUFFICIENT_FUNDS",
      statusReasonDetail: "Insufficient available balance on account.",
      createdAt: "2026-11-01T10:00:00Z",
      updatedAt: "2026-11-01T10:00:00Z",
    });

    render(<PaymentDetailPage />);

    expect(
      await screen.findByText(
        /Payment Instruction REJECTED: INSUFFICIENT_FUNDS/i
      )
    ).toBeInTheDocument();
    expect(
      screen.queryByText(/Payment Instruction Accepted/i)
    ).not.toBeInTheDocument();
  });
});
