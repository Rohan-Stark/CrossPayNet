package com.crosspaynet.banking;

import com.crosspaynet.common.money.Money;
import java.util.UUID;

/**
 * Business event indicating funds have been reserved operationally.
 */
public record FundsReserved(
    UUID accountId,
    UUID reservationToken,
    String paymentReference,
    Money amount
) {}
