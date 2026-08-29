package com.payg.payg.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Inbound body of {@code POST /v1/orders}.
 *
 * <p>Carries no merchant id - that comes from the API key.
 */
public record CreateOrderRequest(

        @NotBlank
        @Size(max = 255)
        String merchantOrderId,

        /* Integer minor units (paise). Never floating point. */
        @NotNull
        @Positive
        Long amountMinor,

        @NotNull
        @Pattern(regexp = "INR")
        String currency
) {
}
