package com.payg.payg.gateway;

/**
 * A downstream processor that actually moves money.
 *
 * <p>Every implementation is a mock - integrating with a real acquirer is out
 * of scope. The interface is nonetheless shaped as though one existed, because
 * the value of the mocks is that they can be made to misbehave on demand: a
 * real gateway cannot be asked to time out, or to degrade from 95% to 40%
 * success at transaction 500, and those are exactly the situations the rest of
 * the system is built to survive.
 */
public interface PaymentGateway {

    /**
     * Stable identifier, recorded on every attempt. Once routing exists this
     * is also how a gateway is selected, pinned, or excluded.
     */
    String name();

    /**
     * Attempts the charge.
     *
     * <p>Implementations must not throw to signal a failed charge - a failure
     * is a {@link GatewayOutcome}, because the caller has to know whether it
     * was definite. A thrown exception is treated as
     * {@link GatewayOutcome.Result#INDETERMINATE}, the safe reading.
     */
    GatewayOutcome charge(ChargeRequest request);
}
