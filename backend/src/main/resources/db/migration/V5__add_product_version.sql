-- Optimistic lock for admin product edits (D-20261008-21, AD-A16): an edit based on a stale read must not overwrite a
-- newer one. Existing rows start at 0. With a constant default Postgres adds the column without rewriting the table.
-- SET LOCAL only: a session-level SET would leak into the pooled connection.
SET LOCAL lock_timeout = '5s';

ALTER TABLE products ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
