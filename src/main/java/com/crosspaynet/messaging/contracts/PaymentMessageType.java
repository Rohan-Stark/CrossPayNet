package com.crosspaynet.messaging.contracts;

/**
 * Supported ISO 20022-inspired financial message types in the CrossPayNet simulator.
 */
public enum PaymentMessageType {
    /**
     * FIToFICustomerCreditTransfer ({@code pacs.008.001.08}-inspired interbank credit transfer message).
     */
    PACS_008
}
