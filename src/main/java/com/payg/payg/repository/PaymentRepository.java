package com.payg.payg.repository;

import com.payg.payg.entity.PaymentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<PaymentEntity, UUID> {

    Optional<PaymentEntity> findByMerchantIdAndIdempotencyKey(String merchantId, String idempotencyKey);

    Optional<PaymentEntity> findByIdAndMerchantId(UUID id, String merchantId);

    List<PaymentEntity> findTop20ByStatusOrderByUpdatedAt(String status);

    /**
     * Moves a payment from one state to another, but only if it is currently in
     * the expected state. Returns the number of rows changed - 0 means someone
     * else got there first.
     *
     * <p>This is the guard. Reading the status and then writing it leaves a
     * window in which two callers both see {@code INITIATED} and both proceed
     * to charge; the {@code AND status = :from} clause closes it, because the
     * database evaluates it under the row lock taken by the UPDATE itself.
     * Once the ledger is written on the transition into {@code SUCCESS} (M3),
     * this is what stops it being written twice.
     *
     * <p>{@code clearAutomatically} because the update bypasses the persistence
     * context; without it a subsequent read in the same context would return
     * the stale status.
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE PaymentEntity p
               SET p.status = :to, p.updatedAt = :now
             WHERE p.id = :id
               AND p.status = :from
            """)
    int transition(@Param("id") UUID id,
                   @Param("from") String from,
                   @Param("to") String to,
                   @Param("now") OffsetDateTime now);

    /**
     * Claims the oldest unclaimed payment and hands it to this worker.
     *
     * <p>{@code SKIP LOCKED} is what lets several workers run this at once
     * without blocking on each other - each one simply skips whatever row
     * another worker's {@code SELECT ... FOR UPDATE} already has locked and
     * takes the next one instead. The row lock taken by that inner SELECT is
     * held until the enclosing {@code UPDATE} commits, so no two workers can
     * ever walk away with the same id.
     *
     * <p>An {@code INITIATED} row never carries a lease - one is assigned
     * here, in the same statement that moves it to {@code PROCESSING} - so
     * nothing in this query needs to check {@code lease_expires_at}.
     */
    @Query(value = """
            UPDATE payments
               SET status = 'PROCESSING',
                   claimed_by = :workerId,
                   lease_expires_at = :leaseExpiresAt,
                   updated_at = :now
             WHERE id = (
                 SELECT id FROM payments
                  WHERE status = 'INITIATED'
                  ORDER BY created_at
                  LIMIT 1
                  FOR UPDATE SKIP LOCKED
             )
            RETURNING id
            """, nativeQuery = true)
    Optional<UUID> claimNext(@Param("workerId") String workerId,
                             @Param("leaseExpiresAt") OffsetDateTime leaseExpiresAt,
                             @Param("now") OffsetDateTime now);

    /**
     * Moves every {@code PROCESSING} payment whose lease has lapsed to
     * {@code UNKNOWN}.
     *
     * <p>This is the other half of crash recovery. A worker that dies after
     * the gateway has already answered - but before that answer was written
     * down - leaves a row in {@code PROCESSING} that {@link #claimNext} will
     * never touch again, because that query only ever looks at
     * {@code INITIATED} rows. Left alone, such a row would be stuck forever.
     * It must not simply be re-claimed and retried either: the charge may
     * already have gone through, and retrying it is the double-charge this
     * whole system exists to prevent. {@code UNKNOWN} is the only safe
     * landing spot until something resolves it (F6).
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("""
            UPDATE PaymentEntity p
               SET p.status = 'UNKNOWN', p.updatedAt = :now
             WHERE p.status = 'PROCESSING'
               AND p.leaseExpiresAt < :now
            """)
    int reapExpiredLeases(@Param("now") OffsetDateTime now);
}
