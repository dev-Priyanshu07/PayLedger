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
 * Maps the {@code payments} table.
 *
 * <p>{@code orderId} is mapped as a plain column rather than an association:
 * the amount is reached through the order deliberately, and nothing on the
 * write path needs to traverse it.
 */
@Entity
@Table(name = "payments")
@Data
@EqualsAndHashCode(callSuper = false)
@NoArgsConstructor
@AllArgsConstructor
public class PaymentEntity extends AssignedIdEntity {

    @Id
    private UUID id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private UUID orderId;

    @Column(name = "merchant_id", nullable = false, updatable = false)
    private String merchantId;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, updatable = false)
    private String requestHash;

    @Column(name = "customer_ref", nullable = false, updatable = false)
    private String customerRef;

    /** One of: INITIATED, PROCESSING, SUCCESS, FAILED, UNKNOWN. */
    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Which worker currently holds this row. Null while INITIATED. */
    @Column(name = "claimed_by")
    private String claimedBy;

    /** When the current hold on this row lapses. Null while INITIATED. */
    @Column(name = "lease_expires_at")
    private OffsetDateTime leaseExpiresAt;
}
