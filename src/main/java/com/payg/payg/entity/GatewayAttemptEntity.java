package com.payg.payg.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Maps the {@code gateway_attempts} table. Append-only - an attempt is a
 * historical fact, so nothing here is ever updated.
 */
@Entity
@Table(name = "gateway_attempts")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class GatewayAttemptEntity extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "gateway_name", nullable = false, updatable = false)
    private String gatewayName;

    /** One of: SUCCESS, DEFINITE_FAILURE, INDETERMINATE. */
    @Column(name = "outcome", nullable = false, updatable = false)
    private String outcome;

    /** Null unless the gateway got far enough to issue a reference. */
    @Column(name = "gateway_ref", updatable = false)
    private String gatewayRef;

    @Column(name = "reason", nullable = false, updatable = false)
    private String reason;

    @Column(name = "started_at", nullable = false, updatable = false)
    private OffsetDateTime startedAt;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private OffsetDateTime completedAt;
}
