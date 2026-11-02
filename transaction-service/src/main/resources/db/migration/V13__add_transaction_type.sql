-- The ledger now records deposits as well as transfers. A deposit has no
-- source account (the money comes from the payment provider), so
-- source_account_id becomes nullable; the CHECK keeps it mandatory for
-- transfers. Existing rows are transfers.
ALTER TABLE transactions
    ADD COLUMN type VARCHAR(20) NOT NULL DEFAULT 'TRANSFER';

ALTER TABLE transactions
    ALTER COLUMN source_account_id DROP NOT NULL;

ALTER TABLE transactions
    ADD CONSTRAINT ck_transactions_transfer_has_source
        CHECK (type <> 'TRANSFER' OR source_account_id IS NOT NULL);

CREATE INDEX idx_transactions_destination_account_id
    ON transactions (destination_account_id);
