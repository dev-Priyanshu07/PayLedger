-- V1__init.sql
-- Phase 2: order declaration + payment intent with idempotency.
-- Gateway attempts, ledger, and outbox arrive in later migrations.

-- ---------------------------------------------------------------
-- orders
--
-- An immutable declaration: "this merchant order is worth this much."
-- No status column by design. Whether an order is paid is derived
-- from its payments, so there is nothing here that can drift.
-- ---------------------------------------------------------------
CREATE TABLE orders (
    id                  UUID         PRIMARY KEY,
    merchant_id         TEXT         NOT NULL,
    merchant_order_id   TEXT         NOT NULL,
    amount_minor        BIGINT       NOT NULL,
    currency            CHAR(3)      NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Amounts are integer minor units (paise). Never floating point.
    CONSTRAINT chk_orders_amount_positive
        CHECK (amount_minor > 0),

    CONSTRAINT chk_orders_currency
        CHECK (currency = 'INR'),

    -- Natural key. Makes POST /v1/orders idempotent for free:
    -- a retried creation collides here and we return the existing row.
    CONSTRAINT uq_orders_merchant_order
        UNIQUE (merchant_id, merchant_order_id)
);


-- ---------------------------------------------------------------
-- payments
--
-- The intent to charge, and the single source of truth for whether
-- money moved. Amount is NOT duplicated here - it is reached through
-- order_id, so the two can never disagree.
-- ---------------------------------------------------------------
CREATE TABLE payments (
    id                  UUID         PRIMARY KEY,
    order_id            UUID         NOT NULL,
    merchant_id         TEXT         NOT NULL,
    idempotency_key     TEXT         NOT NULL,
    request_hash        TEXT         NOT NULL,
    customer_ref        TEXT         NOT NULL,
    status              TEXT         NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- A payment without an order has no authoritative amount and could
    -- never be safely charged. The FK makes that state unreachable.
    CONSTRAINT fk_payments_order
        FOREIGN KEY (order_id) REFERENCES orders (id),

    -- The idempotency guarantee. Scoped to the merchant because two
    -- merchants may independently pick the same key string.
    -- Insert-first + this constraint is what removes the check-then-act
    -- race; the database arbitrates, so there is no window.
    CONSTRAINT uq_payments_idempotency
        UNIQUE (merchant_id, idempotency_key),

    CONSTRAINT chk_payments_status
        CHECK (status IN ('INITIATED', 'PROCESSING', 'SUCCESS', 'FAILED', 'UNKNOWN')),

    CONSTRAINT chk_payments_idem_key_nonempty
        CHECK (length(idempotency_key) BETWEEN 1 AND 255)
);

-- Supports "has this order been paid?" and listing attempts per order.
CREATE INDEX idx_payments_order ON payments (order_id);
