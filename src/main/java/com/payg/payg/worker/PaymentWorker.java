package com.payg.payg.worker;

import com.payg.payg.repository.PaymentRepository;
import com.payg.payg.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * M2: moves the gateway call out of the request that created the payment.
 *
 * <p>Two independent jobs, on two independent schedules:
 *
 * <ul>
 *   <li>{@link #pollAndProcess()} claims {@code INITIATED} payments and takes
 *       them to the gateway - the inline path {@code PaymentService.create}
 *       used to run synchronously now runs here instead.
 *   <li>{@link #reapExpiredLeases()} recovers a payment left in
 *       {@code PROCESSING} by a worker that died before finishing it - see
 *       {@link PaymentRepository#reapExpiredLeases} for why this must move
 *       such a row to {@code UNKNOWN} rather than reclaim it for a retry.
 * </ul>
 *
 * <p>{@code workerId} is fixed for the process's lifetime and excluded from
 * the generated constructor by Lombok because it is {@code final} with an
 * initializer - the same rule {@link com.payg.payg.entity.AssignedIdEntity}
 * relies on for {@code isNew}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PaymentWorker {

    private final PaymentRepository payments;
    private final PaymentService service;
    private final WorkerProperties properties;

    private final String workerId = "worker-" + UUID.randomUUID();

    @Scheduled(fixedDelayString = "${payg.worker.poll-interval-ms:500}")
    public void pollAndProcess() {
        for (int i = 0; i < properties.batchSize(); i++) {
            OffsetDateTime now = OffsetDateTime.now();
            Optional<UUID> claimed = payments.claimNext(
                    workerId, now.plusSeconds(properties.leaseSeconds()), now);

            if (claimed.isEmpty()) {
                return;
            }

            UUID paymentId = claimed.get();
            try {
                service.processClaimed(paymentId);
            } catch (RuntimeException e) {
                // Whatever went wrong, this row is still holding its lease.
                // Leave it - the lease expires, reapExpiredLeases() moves it
                // to UNKNOWN, and the reconciler (F6) takes it from there.
                // Retrying it here ourselves is exactly the double-charge
                // risk an UNKNOWN payment exists to avoid.
                log.error("payment={} worker={} processing threw; leaving it for the lease to expire",
                        paymentId, workerId, e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${payg.worker.reap-interval-ms:5000}")
    public void reapExpiredLeases() {
        int reaped = payments.reapExpiredLeases(OffsetDateTime.now());
        if (reaped > 0) {
            log.warn("worker={} reaped {} payment(s) with an expired PROCESSING lease -> UNKNOWN",
                    workerId, reaped);
        }
    }
}
