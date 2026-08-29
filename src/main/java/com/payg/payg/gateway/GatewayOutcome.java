package com.payg.payg.gateway;

/**
 * What a gateway said.
 *
 * <p>Three results, not two. The difference between {@link Result#DEFINITE_FAILURE}
 * and {@link Result#INDETERMINATE} is the single most important distinction in
 * this system: the first means no money moved and we are free to try elsewhere,
 * the second means we do not know and must not.
 */
public record GatewayOutcome(Result result, String gatewayRef, String reason) {

    public enum Result {

        /** The gateway confirmed the charge. A {@code gatewayRef} is present. */
        SUCCESS,

        /**
         * The gateway proved no charge was created - a clean decline, or a
         * connection that was refused before anything was sent. Safe to
         * failover to another gateway.
         */
        DEFINITE_FAILURE,

        /**
         * The outcome is genuinely unknown - a timeout, or a response that
         * settles nothing. The charge may have succeeded. Retrying elsewhere
         * risks charging twice, so it is forbidden; this must be resolved by
         * asking the same gateway again later (F6).
         */
        INDETERMINATE
    }

    public static GatewayOutcome success(String gatewayRef, String reason) {
        return new GatewayOutcome(Result.SUCCESS, gatewayRef, reason);
    }

    public static GatewayOutcome definiteFailure(String reason) {
        return new GatewayOutcome(Result.DEFINITE_FAILURE, null, reason);
    }

    public static GatewayOutcome indeterminate(String reason) {
        return new GatewayOutcome(Result.INDETERMINATE, null, reason);
    }
}
