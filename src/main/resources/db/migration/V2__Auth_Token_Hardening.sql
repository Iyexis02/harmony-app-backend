-- Batch B: Auth token security hardening
-- Replaces plaintext token storage with SHA-256 hashes.
-- Adds token_version for JWT revocation triggered by password reset.
--
-- Old plaintext columns (email_verification_token, password_reset_token) are
-- intentionally preserved so existing rows are not corrupted. New code writes
-- null to those columns and uses the hash columns exclusively.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email_verification_token_hash VARCHAR(64),
    ADD COLUMN IF NOT EXISTS password_reset_token_hash     VARCHAR(64),
    ADD COLUMN IF NOT EXISTS token_version                 INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_email_verification_token_hash
    ON users (email_verification_token_hash);

CREATE INDEX IF NOT EXISTS idx_password_reset_token_hash
    ON users (password_reset_token_hash);
