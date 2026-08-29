package com.payg.payg.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The intent to charge, and the single source of truth for whether money
 * moved.
 *
 * <p>Mirrors the {@code payments} table. The amount is deliberately absent:
 * it is reached through {@code orderId}, so the two can never disagree.
 *
 * <p>{@code id}, {@code requestHash}, {@code status}, {@code createdAt} and
 * {@code updatedAt} are assigned by the server and are null on an inbound
 * create request; the remaining fields are supplied by the merchant and are
 * validated.
 */
public record Payment(

        UUID id,

        @NotNull
        UUID orderId,

        @NotBlank
        String merchantId,

        @NotBlank
        @Size(min = 1, max = 255)
        String idempotencyKey,

        String requestHash,

        @NotBlank
        String customerRef,

        /* One of: INITIATED, PROCESSING, SUCCESS, FAILED, UNKNOWN. */
        String status,

        OffsetDateTime createdAt,

        OffsetDateTime updatedAt
) {
}
