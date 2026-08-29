package com.payg.payg.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Inbound body of {@code POST /v1/payments}.
 *
 * <p>No amount: it is reached through the order, so the two can never
 * disagree. No merchant id: that comes from the API key. The idempotency key
 * arrives as a header, not a body field.
 */
public record CreatePaymentRequest(

        @NotNull
        UUID orderId,

        @NotBlank
        @Size(max = 255)
        String customerRef
) {
}
