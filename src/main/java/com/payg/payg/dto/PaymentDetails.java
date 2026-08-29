package com.payg.payg.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record PaymentDetails(
        UUID id,
        UUID orderId,
        String merchantId,
        String idempotencyKey,
        String requestHash,
        String customerRef,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        List<GatewayAttempt> attempts
) {
}
