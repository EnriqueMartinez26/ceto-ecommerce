-- Admin audit log (D-20261008-11): who-did-what facts about orders, stock and products, written in the same
-- transaction as the change. Closed sets only: no free text, so no personal data and no IP can be stored.
CREATE TABLE admin_audit_log (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at  TIMESTAMPTZ   NOT NULL,
    action       VARCHAR(30)   NOT NULL,
    entity_type  VARCHAR(10)   NOT NULL,
    entity_id    BIGINT        NOT NULL,
    reason       VARCHAR(30),
    before_value NUMERIC(12,2),
    after_value  NUMERIC(12,2),
    CONSTRAINT admin_audit_log_action_ck CHECK (action IN ('ORDER_PAID','ORDER_DISPATCHED','ORDER_DELIVERED',
        'ORDER_CANCELLED','STOCK_ADJUSTED','PRODUCT_CREATED','PRODUCT_UPDATED','PRICE_CHANGED',
        'PRODUCT_ACTIVATED','PRODUCT_DEACTIVATED')),
    CONSTRAINT admin_audit_log_entity_ck CHECK (entity_id >= 1 AND entity_type IN ('ORDER','PRODUCT')
        AND (action LIKE 'ORDER\_%') = (entity_type = 'ORDER')),
    -- Closed sets only: no free text (and so no personal data) can be stored as a reason.
    CONSTRAINT admin_audit_log_reason_ck CHECK (
        (action = 'STOCK_ADJUSTED' AND reason IS NOT NULL AND reason IN ('COUNT_CORRECTION','RESTOCK','DAMAGED_OR_LOST','RETURN','OTHER'))
        OR (action = 'ORDER_CANCELLED' AND reason IS NOT NULL AND reason = 'CANCELLED_BY_ADMIN')
        OR (action NOT IN ('STOCK_ADJUSTED','ORDER_CANCELLED') AND reason IS NULL)),
    CONSTRAINT admin_audit_log_values_ck CHECK (
        (action IN ('STOCK_ADJUSTED','PRICE_CHANGED')) = (before_value IS NOT NULL AND after_value IS NOT NULL)
        AND (before_value IS NULL) = (after_value IS NULL)));

CREATE INDEX admin_audit_log_entity_idx ON admin_audit_log (entity_type, entity_id, id);

CREATE FUNCTION admin_audit_log_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN RAISE EXCEPTION 'admin_audit_log is append-only'; END $$;

-- Statement level: fires even when zero rows match, and is the only level TRUNCATE supports.
-- Limit: the table owner can still disable or drop the trigger; it stops mistakes and application bugs, not a
-- hostile database owner.
CREATE TRIGGER admin_audit_log_append_only BEFORE UPDATE OR DELETE OR TRUNCATE ON admin_audit_log
    FOR EACH STATEMENT EXECUTE FUNCTION admin_audit_log_append_only();
