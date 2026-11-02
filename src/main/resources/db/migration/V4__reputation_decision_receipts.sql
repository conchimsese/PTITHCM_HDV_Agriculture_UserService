-- Canonical business fingerprints detect conflicting replays; existing historical rows
-- have no known fingerprint and require reconciliation rather than silent acceptance.
ALTER TABLE reputation_history ADD COLUMN decision_payload JSONB;
ALTER TABLE reputation_history ADD COLUMN decision_fingerprint VARCHAR(64);
ALTER TABLE reputation_history ADD COLUMN effective_at TIMESTAMPTZ;
CREATE TABLE reputation_event_receipts (
    event_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts(account_id),
    decision_id UUID NOT NULL,
    event_fingerprint VARCHAR(64) NOT NULL CHECK (event_fingerprint ~ '^[0-9a-f]{64}$'),
    processed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX reputation_decision_lookup_idx ON reputation_history(account_id,decision_id);
