package com.crosspaynet.operations.domain;

public enum DiscrepancyType {
    STALE_IN_FLIGHT_WORKFLOW,
    RESERVATION_PAYMENT_MISMATCH,
    PAYMENT_LEDGER_BREAK,
    INCOMPLETE_EVENT_PUBLICATION
}
