package com.payg.payg.worker;

import com.payg.payg.entity.PaymentEntity;
import com.payg.payg.repository.PaymentRepository;
import com.payg.payg.service.PaymentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resolves payments whose gateway outcome was indeterminate.
 *
 * <p>Unlike the normal worker, this does not create a new charge. It asks the
 * same gateway about the same gateway reference and only moves the payment out
 * of {@code UNKNOWN} when the gateway gives a definite answer.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class UnknownPaymentResolver {

    private static final String UNKNOWN = "UNKNOWN";

    private final PaymentRepository payments;
    private final PaymentService service;

    @Scheduled(fixedDelayString = "${payg.worker.resolve-unknown-interval-ms:5000}")
    public void resolveUnknownPayments() {
        for (PaymentEntity payment : payments.findTop20ByStatusOrderByUpdatedAt(UNKNOWN)) {
            try {
                service.resolveUnknown(payment.getId());
            } catch (RuntimeException e) {
                log.error("payment={} unknown resolution threw; leaving it UNKNOWN",
                        payment.getId(), e);
            }
        }
    }
}
