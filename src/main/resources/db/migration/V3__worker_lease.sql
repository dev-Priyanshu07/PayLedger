-- V3__worker_lease.sql
-- M2: queued processing. A payment is no longer charged inline in the
-- request that created it - a worker claims it and does that later.

-- ---------------------------------------------------------------
-- The lease. Two nullable columns, both mutable (everything else on
-- this table besides `status` is set once at insert and never
-- touched again).
--
-- `claimed_by` is informational - which worker holds the row, useful
-- for diagnosing a stuck payment. `lease_expires_at` is what actually
-- matters: a worker that dies mid-charge leaves no flag to clean up,
-- because the lease simply runs out and the row becomes claimable
-- again on its own.
-- ---------------------------------------------------------------
ALTER TABLE payments
    ADD COLUMN claimed_by TEXT,
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

-- Supports the claim query: WHERE status = 'INITIATED' ORDER BY created_at
-- LIMIT 1 FOR UPDATE SKIP LOCKED.
--
-- An INITIATED row never carries a lease - claiming it sets the lease
-- and moves it to PROCESSING in the same update - so this index only
-- ever needs to be searched by status and ordered by created_at.
CREATE INDEX idx_payments_claimable
    ON payments (created_at)
    WHERE status = 'INITIATED';

-- Supports the reaper: WHERE status = 'PROCESSING' AND lease_expires_at < now().
--
-- A separate, disjoint slice of the table from the index above - a row
-- is in exactly one of the two at a time.
CREATE INDEX idx_payments_lease_expiry
    ON payments (lease_expires_at)
    WHERE status = 'PROCESSING';
