-- V2__gateway_attempts.sql
-- Phase 3: the record of what we asked a gateway to do, and what it said.

-- ---------------------------------------------------------------
-- gateway_attempts
--
-- One row per call to a gateway. Append-only: an attempt is a
-- historical fact and is never revised.
--
-- This table exists from the first gateway onwards, not from the
-- first *interesting* gateway, because several later requirements
-- can only be met if the history was recorded all along:
--
--   N6  every routing decision must be explainable from stored data
--   F6  resolving an UNKNOWN means re-asking about a specific attempt
--   F7  settlement lines are matched on the gateway's own reference
--   F3  the routing model is trained on the outcome of every attempt
--
-- The gateway reference is NOT copied onto payments. It belongs to
-- an attempt - a payment may have several - and duplicating it would
-- create two places that can disagree.
-- ---------------------------------------------------------------
CREATE TABLE gateway_attempts (
    id                  UUID         PRIMARY KEY,
    payment_id          UUID         NOT NULL,
    gateway_name        TEXT         NOT NULL,
    outcome             TEXT         NOT NULL,

    -- The gateway's own id for the charge. Null when the gateway never
    -- got far enough to issue one - which is precisely the INDETERMINATE
    -- case, and precisely why it cannot be NOT NULL.
    gateway_ref         TEXT,

    -- Human-readable why. Populated for every outcome, so a decision can
    -- be explained without re-deriving it.
    reason              TEXT         NOT NULL,

    started_at          TIMESTAMPTZ  NOT NULL,
    completed_at        TIMESTAMPTZ  NOT NULL,

    CONSTRAINT fk_gateway_attempts_payment
        FOREIGN KEY (payment_id) REFERENCES payments (id),

    -- Three outcomes, not two. The distinction between a definite failure
    -- and an indeterminate one is what makes failover safe: only the
    -- former may be retried elsewhere.
    CONSTRAINT chk_gateway_attempts_outcome
        CHECK (outcome IN ('SUCCESS', 'DEFINITE_FAILURE', 'INDETERMINATE')),

    -- A successful charge the gateway did not give us a reference for
    -- would be unmatchable at settlement time.
    CONSTRAINT chk_gateway_attempts_success_has_ref
        CHECK (outcome <> 'SUCCESS' OR gateway_ref IS NOT NULL)
);

-- Supports "show me this payment's attempts, in order".
CREATE INDEX idx_gateway_attempts_payment
    ON gateway_attempts (payment_id, started_at);
