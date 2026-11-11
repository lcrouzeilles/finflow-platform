-- Transfers are looked up by either side of the ledger; V13 indexed the
-- destination account, this indexes the source.
--
-- Foreign keys to accounts/owners are still intentionally omitted (see V11):
-- integration tests create and delete accounts independently of owners and
-- transactions.
CREATE INDEX idx_transactions_source_account_id
    ON transactions (source_account_id);
