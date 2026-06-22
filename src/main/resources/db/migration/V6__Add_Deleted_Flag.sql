-- Master Batch E: Add soft-delete guard column to users table.
-- deleteAccount() sets this flag and flushes before cleaning up child entities,
-- so concurrent swipe transactions that load the entity can detect the deletion
-- in progress. The column has a DB default of false so existing rows are unaffected.
ALTER TABLE users ADD COLUMN IF NOT EXISTS deleted BOOLEAN NOT NULL DEFAULT FALSE;

-- Index accelerates the AND u.deleted = false clause in findCandidateUsers()
-- without changing the existing idx_registration_stage selectivity.
CREATE INDEX IF NOT EXISTS idx_users_deleted ON users (deleted) WHERE deleted = false;
