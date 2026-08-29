package com.payg.payg.gateway;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The M1 gateway: every charge succeeds.
 *
 * <p>Deliberately the least interesting implementation possible. Its only job
 * is to let a payment reach {@code SUCCESS} so the milestone is demonstrable
 * end to end. The declining, timing-out, and configurable-success-rate mocks
 * that make the failover and routing requirements testable arrive at M4 and M6.
 */
@Component
public class AlwaysSucceedsGateway implements PaymentGateway {

    @Override
    public String name() {
        return "mock-always-succeeds";
    }

    @Override
    public GatewayOutcome charge(ChargeRequest request) {
        return GatewayOutcome.success(
                "gw_" + UUID.randomUUID().toString().replace("-", ""),
                "mock gateway configured to always succeed");
    }
}
