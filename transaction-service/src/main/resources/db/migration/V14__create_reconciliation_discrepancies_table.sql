-- Differences between what Stripe says and what the ledger says. Rows are
-- written by the webhook handler (events it could not apply) and by the
-- reconciliation job. They are reported, never auto-corrected: a wrong
-- automatic correction in a ledger is worse than the discrepancy itself.
CREATE TABLE reconciliation_discrepancies (
    id                UUID PRIMARY KEY,
    type              VARCHAR(50)  NOT NULL,
    deposit_id        UUID,
    stripe_session_id VARCHAR(255),
    detail            TEXT         NOT NULL,
    detected_at       TIMESTAMPTZ  NOT NULL
);

-- One open row per (type, session): reruns of the job do not pile up.
CREATE UNIQUE INDEX uk_reconciliation_discrepancies_type_session
    ON reconciliation_discrepancies (type, stripe_session_id)
    WHERE stripe_session_id IS NOT NULL;

CREATE INDEX idx_reconciliation_discrepancies_detected_at
    ON reconciliation_discrepancies (detected_at);
