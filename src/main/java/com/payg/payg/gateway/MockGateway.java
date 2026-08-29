package com.payg.payg.gateway;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock gateway with random outcomes for exercising payment state transitions.
 */
@Component
public class MockGateway implements PaymentGateway {

    private final Map<String, GatewayOutcome> settledByReference = new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "mock-gateway";
    }

    @Override
    public GatewayOutcome charge(ChargeRequest request) {
        int roll = ThreadLocalRandom.current().nextInt(1000);

        if (roll < 450) {
            GatewayOutcome outcome = GatewayOutcome.success(
                    request.gatewayRef(),
                    "mock gateway success");
            settledByReference.put(request.gatewayRef(), outcome);
            return outcome;
        }

        if (roll < 900) {
            GatewayOutcome outcome = GatewayOutcome.definiteFailure(
                    request.gatewayRef(),
                    "mock gateway failure");
            settledByReference.put(request.gatewayRef(), outcome);
            return outcome;
        }

        return GatewayOutcome.indeterminate(
                request.gatewayRef(),
                "mock gateway indeterminate");
    }

    @Override
    public GatewayOutcome status(ChargeRequest request, String gatewayRef) {
        GatewayOutcome known = settledByReference.get(gatewayRef);
        if (known != null) {
            return known;
        }

        int roll = ThreadLocalRandom.current().nextInt(1000);
        if (roll < 500) {
            GatewayOutcome outcome = GatewayOutcome.success(gatewayRef, "mock gateway status resolved as success");
            settledByReference.put(gatewayRef, outcome);
            return outcome;
        }

        if (roll < 850) {
            GatewayOutcome outcome = GatewayOutcome.definiteFailure(gatewayRef, "mock gateway status resolved as failure");
            settledByReference.put(gatewayRef, outcome);
            return outcome;
        }

        return GatewayOutcome.indeterminate(gatewayRef, "mock gateway status still indeterminate");
    }
}
