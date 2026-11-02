-- Deposits funded through Stripe Checkout. A row is created only after the
-- Checkout Session exists at Stripe; the balance is credited exclusively by
-- the webhook handler (never by the success redirect).
--
-- No foreign key to accounts, consistent with the rest of the schema
-- (transactions has none either); integration tests rely on deleting
-- accounts independently.
CREATE TABLE deposits (
    id                       UUID PRIMARY KEY,
    account_id               UUID           NOT NULL,
    owner_id                 UUID           NOT NULL,
    amount                   NUMERIC(19, 4) NOT NULL,
    currency                 VARCHAR(3)     NOT NULL,
    status                   VARCHAR(20)    NOT NULL,
    stripe_session_id        VARCHAR(255),
    stripe_payment_intent_id VARCHAR(255),
    checkout_url             TEXT,
    idempotency_key          VARCHAR(100)   NOT NULL,
    transaction_id           UUID,
    failure_reason           TEXT,
    created_at               TIMESTAMPTZ    NOT NULL,
    updated_at               TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uk_deposits_idempotency_key UNIQUE (idempotency_key)
);

CREATE INDEX idx_deposits_account_id ON deposits (account_id);

CREATE INDEX idx_deposits_created_at ON deposits (created_at);

CREATE UNIQUE INDEX uk_deposits_stripe_session_id
    ON deposits (stripe_session_id)
    WHERE stripe_session_id IS NOT NULL;
