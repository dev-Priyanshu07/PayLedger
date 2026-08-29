package com.payg.payg.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record GatewayAttempt(
        UUID id,
        String gatewayName,
        String outcome,
        String gatewayRef,
        String reason,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt
) {
}
