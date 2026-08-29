package com.payg.payg.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An order declaration: "this merchant order is worth this much."
 *
 * <p>Mirrors the {@code orders} table. There is deliberately no status
 * field - whether an order is paid is derived from its payments, so there
 * is nothing here that can drift.
 *
 * <p>{@code id} and {@code createdAt} are assigned by the server and are
 * null on an inbound create request; the remaining fields are supplied by
 * the merchant and are validated.
 */
public record Order(

        UUID id,

        @NotBlank
        String merchantId,

        @NotBlank
        String merchantOrderId,

        /* Integer minor units (paise). Never floating point. */
        @Positive
        Long amountMinor,

        @Pattern(regexp = "INR")
        String currency,

        OffsetDateTime createdAt
) {
}
