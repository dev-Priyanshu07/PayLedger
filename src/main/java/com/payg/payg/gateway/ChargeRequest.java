package com.payg.payg.gateway;

import java.util.UUID;

/**
 * What we ask a gateway to charge.
 *
 * <p>The amount is passed explicitly rather than looked up by the gateway:
 * the gateway is an outside party in every sense, and knows nothing about our
 * tables.
 */
public record ChargeRequest(
        UUID paymentId,
        long amountMinor,
        String currency,
        String customerRef,
        String gatewayRef
) {
}
