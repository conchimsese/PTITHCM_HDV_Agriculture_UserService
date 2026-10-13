CREATE TABLE accounts (
    account_id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL UNIQUE,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('FARMER','TRADER','ADMIN')),
    status VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED'
        CHECK (status IN ('UNVERIFIED','ACTIVE','INACTIVE','LOCKED')),
    email_verified_at TIMESTAMPTZ,
    reputation_score NUMERIC(3,1),
    admin_lock BOOLEAN NOT NULL DEFAULT FALSE,
    reputation_lock BOOLEAN NOT NULL DEFAULT FALSE,
    account_version BIGINT NOT NULL DEFAULT 0 CHECK (account_version >= 0),
    profile_version BIGINT NOT NULL DEFAULT 0 CHECK (profile_version >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (account_id, role),
    CHECK (username = lower(btrim(username)) AND length(username) >= 3),
    CHECK (email = lower(btrim(email)) AND length(email) > 0),
    CHECK ((role = 'ADMIN' AND reputation_score IS NULL AND NOT reputation_lock)
        OR (role IN ('FARMER','TRADER') AND reputation_score IS NOT NULL
            AND reputation_score BETWEEN 0 AND 10 AND mod(reputation_score, 0.5) = 0)),
    CHECK (reputation_score IS DISTINCT FROM 0 OR reputation_lock),
    CHECK (NOT (admin_lock OR reputation_lock) OR status IN ('LOCKED','INACTIVE')),
    CHECK (status <> 'ACTIVE' OR (email_verified_at IS NOT NULL
        AND NOT admin_lock AND NOT reputation_lock
        AND (role = 'ADMIN' OR reputation_score > 0)))
);
CREATE INDEX accounts_admin_page_idx ON accounts (created_at DESC, account_id DESC);
CREATE INDEX accounts_role_status_idx ON accounts (role, status);

CREATE TABLE farmer_profiles (
    farmer_id UUID PRIMARY KEY,
    account_role VARCHAR(20) NOT NULL DEFAULT 'FARMER' CHECK (account_role = 'FARMER'),
    full_name VARCHAR(150) NOT NULL CHECK (length(btrim(full_name)) > 0),
    phone VARCHAR(20), address VARCHAR(255), province VARCHAR(100), district VARCHAR(100),
    avatar_url VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (farmer_id, account_role) REFERENCES accounts (account_id, role)
);
CREATE TABLE trader_profiles (
    trader_id UUID PRIMARY KEY,
    account_role VARCHAR(20) NOT NULL DEFAULT 'TRADER' CHECK (account_role = 'TRADER'),
    business_name VARCHAR(200) NOT NULL CHECK (length(btrim(business_name)) > 0),
    phone VARCHAR(20), address VARCHAR(255), province VARCHAR(100), district VARCHAR(100),
    avatar_url VARCHAR(500), buying_area VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (trader_id, account_role) REFERENCES accounts (account_id, role)
);

CREATE TABLE email_verification_otps (
    otp_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts (account_id),
    email VARCHAR(254) NOT NULL,
    code_hash VARCHAR(64) NOT NULL CHECK (code_hash ~ '^[0-9a-f]{64}$'),
    expires_at TIMESTAMPTZ NOT NULL,
    used_at TIMESTAMPTZ,
    invalidated_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count BETWEEN 0 AND 5),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK (expires_at > created_at)
);
CREATE INDEX otp_account_idx ON email_verification_otps (account_id, created_at DESC);
CREATE INDEX otp_cleanup_idx ON email_verification_otps (expires_at);
CREATE UNIQUE INDEX otp_one_current_per_account_idx ON email_verification_otps (account_id)
    WHERE used_at IS NULL AND invalidated_at IS NULL;

-- Issuance timestamps survive OTP cleanup; account row serializes quota checks.
CREATE TABLE otp_issuances (
    issuance_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts (account_id),
    email VARCHAR(254) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX otp_quota_email_time_idx ON otp_issuances (email, issued_at DESC);

CREATE TABLE refresh_tokens (
    refresh_token_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts (account_id),
    token_hash VARCHAR(64) NOT NULL UNIQUE CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMPTZ,
    CHECK (expires_at > created_at)
);
CREATE INDEX refresh_account_live_idx ON refresh_tokens (account_id) WHERE revoked_at IS NULL;
CREATE INDEX refresh_expiry_idx ON refresh_tokens (expires_at);

CREATE TABLE reputation_history (
    history_id UUID PRIMARY KEY,
    account_id UUID NOT NULL REFERENCES accounts (account_id),
    counterparty_id UUID NOT NULL,
    contract_id UUID NOT NULL,
    decision_id UUID NOT NULL,
    decision_version BIGINT NOT NULL CHECK (decision_version > 0),
    original_decision_id UUID,
    action VARCHAR(30) NOT NULL CHECK (action IN ('PENALTY','REVERSAL','COMPLETION_REWARD')),
    requested_delta NUMERIC(3,1) NOT NULL,
    actual_delta NUMERIC(3,1) NOT NULL,
    before_score NUMERIC(3,1) NOT NULL CHECK (before_score BETWEEN 0 AND 10),
    after_score NUMERIC(3,1) NOT NULL CHECK (after_score BETWEEN 0 AND 10),
    reason_code VARCHAR(100) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (decision_id, account_id, action),
    CHECK (counterparty_id <> account_id),
    CHECK (mod(requested_delta,0.5) = 0 AND mod(actual_delta,0.5) = 0
        AND mod(before_score,0.5) = 0 AND mod(after_score,0.5) = 0),
    CHECK (after_score = before_score + actual_delta),
    CHECK ((action = 'REVERSAL') = (original_decision_id IS NOT NULL))
);
CREATE UNIQUE INDEX reputation_reward_once_idx ON reputation_history (contract_id, account_id)
    WHERE action = 'COMPLETION_REWARD';
CREATE UNIQUE INDEX reputation_reversal_once_idx ON reputation_history (original_decision_id, account_id)
    WHERE action = 'REVERSAL';
CREATE INDEX reputation_pair_cap_idx ON reputation_history (account_id, counterparty_id, created_at DESC)
    WHERE action = 'COMPLETION_REWARD';

-- Schema only: relays and business events are implemented in later phases.
CREATE TABLE outbox (
    event_id UUID PRIMARY KEY,
    aggregate_id UUID NOT NULL REFERENCES accounts (account_id),
    channel VARCHAR(10) NOT NULL CHECK (channel IN ('KAFKA','EMAIL')),
    event_type VARCHAR(100) NOT NULL,
    topic VARCHAR(200),
    payload JSONB NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    available_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at TIMESTAMPTZ,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    CHECK ((channel = 'KAFKA' AND topic IS NOT NULL) OR (channel = 'EMAIL' AND topic IS NULL))
);
CREATE INDEX outbox_pending_idx ON outbox (available_at, created_at, event_id) WHERE published_at IS NULL;

CREATE TABLE media_assets (
    media_id UUID PRIMARY KEY,
    owner_account_id UUID NOT NULL REFERENCES accounts (account_id),
    resource_id UUID,
    storage_key UUID NOT NULL UNIQUE,
    content_type VARCHAR(30) NOT NULL CHECK (content_type IN ('image/jpeg','image/png','image/webp')),
    size_bytes BIGINT NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 5242880),
    sha256 VARCHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    attached_at TIMESTAMPTZ
);
CREATE INDEX media_owner_idx ON media_assets (owner_account_id);
CREATE INDEX media_orphan_idx ON media_assets (created_at) WHERE attached_at IS NULL;
