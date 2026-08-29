package com.payg.payg.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tuning for {@link PaymentWorker}. Same defaulting style as
 * {@link com.payg.payg.security.MerchantApiKeys}: {@code Integer} rather than
 * {@code int} so an absent property binds to {@code null} instead of {@code 0},
 * and the compact constructor supplies the real default.
 */
@ConfigurationProperties(prefix = "payg.worker")
public record WorkerProperties(Integer leaseSeconds, Integer pollIntervalMs,
                                Integer reapIntervalMs, Integer batchSize) {

    public WorkerProperties {
        leaseSeconds = leaseSeconds == null ? 30 : leaseSeconds;
        pollIntervalMs = pollIntervalMs == null ? 500 : pollIntervalMs;
        reapIntervalMs = reapIntervalMs == null ? 5000 : reapIntervalMs;
        batchSize = batchSize == null ? 20 : batchSize;
    }
}
