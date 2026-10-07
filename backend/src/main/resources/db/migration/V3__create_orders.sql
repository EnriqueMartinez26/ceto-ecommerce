-- V3: orders and their line snapshots. No seed rows (CLAUDE.md section 6).
-- Order numbers come from this sequence (rule 9). Gaps are accepted (rollbacks, idempotency losers).
CREATE SEQUENCE order_number_seq AS BIGINT START WITH 1 INCREMENT BY 1 NO CYCLE;

-- idempotency_key is a native UUID: Postgres rejects anything that is not a UUID (22P02) and the UNIQUE index
-- is half the size of a text one. request_fingerprint is VARCHAR(64), not CHAR(64): Hibernate validate rejects
-- bpchar for a String field, and the CHECK below already pins the shape.
CREATE TABLE orders (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_number        VARCHAR(20)   NOT NULL,
    status              VARCHAR(20)   NOT NULL,
    cancel_reason       VARCHAR(30),
    idempotency_key     UUID          NOT NULL,
    request_fingerprint VARCHAR(64)   NOT NULL,
    buyer_name          VARCHAR(120)  NOT NULL,
    buyer_phone_e164    VARCHAR(16)   NOT NULL,
    shipping_address    VARCHAR(500)  NOT NULL,
    shipping_zone       VARCHAR(20)   NOT NULL,
    total               NUMERIC(12,2) NOT NULL,
    reserved_until      TIMESTAMPTZ   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL,
    updated_at          TIMESTAMPTZ   NOT NULL,
    paid_at             TIMESTAMPTZ,
    dispatched_at       TIMESTAMPTZ,
    delivered_at        TIMESTAMPTZ,
    cancelled_at        TIMESTAMPTZ,
    version             BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT orders_order_number_uk     UNIQUE (order_number),
    CONSTRAINT orders_idempotency_key_uk  UNIQUE (idempotency_key),
    CONSTRAINT orders_order_number_ck     CHECK (order_number ~ '^CETO-[0-9]{6,}$'),
    CONSTRAINT orders_status_ck           CHECK (status IN ('PAGO_PENDIENTE', 'PAGADO', 'DESPACHADO', 'ENTREGADO', 'CANCELADO')),
    CONSTRAINT orders_cancel_reason_ck    CHECK (cancel_reason IN ('EXPIRED', 'CANCELLED_BY_ADMIN', 'CANCELLED_BY_BUYER')),
    -- cancel_reason and cancelled_at are present exactly when the order is CANCELADO (D-20261007-12).
    CONSTRAINT orders_cancel_pair_ck      CHECK ((status = 'CANCELADO') = (cancel_reason IS NOT NULL)
                                                 AND (status = 'CANCELADO') = (cancelled_at IS NOT NULL)),
    -- An unpaid expiry never carries a payment (D-20261007-10: EXPIRED only comes from PAGO_PENDIENTE).
    CONSTRAINT orders_expired_unpaid_ck   CHECK (cancel_reason IS DISTINCT FROM 'EXPIRED' OR paid_at IS NULL),
    CONSTRAINT orders_paid_at_ck          CHECK (status NOT IN ('PAGADO', 'DESPACHADO', 'ENTREGADO') OR paid_at IS NOT NULL),
    CONSTRAINT orders_shipping_zone_ck    CHECK (shipping_zone IN ('CORDOBA_CAPITAL', 'RESTO_DEL_PAIS')),
    -- E.164: '+' then 8 to 15 digits, first digit non-zero; max length 16 matches the column.
    CONSTRAINT orders_phone_e164_ck       CHECK (buyer_phone_e164 ~ '^\+[1-9][0-9]{7,14}$'),
    CONSTRAINT orders_fingerprint_ck      CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT orders_total_ck            CHECK (total > 0),
    CONSTRAINT orders_reserved_until_ck   CHECK (reserved_until > created_at)
);

-- Expiry sweep: WHERE status = 'PAGO_PENDIENTE' AND reserved_until <= :now ORDER BY reserved_until, id
CREATE INDEX orders_pending_expiry_idx ON orders (reserved_until, id) WHERE status = 'PAGO_PENDIENTE';
-- Buyer history lookups.
CREATE INDEX orders_buyer_phone_idx ON orders (buyer_phone_e164);

-- Immutable snapshot of what was bought. line_no is SMALLINT (maps to a Java short).
-- "Combo base must be a base product" is not DB-enforced: it needs a cross-table lookup.
CREATE TABLE order_items (
    id                  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    order_id            BIGINT        NOT NULL REFERENCES orders (id),
    line_no             SMALLINT      NOT NULL,
    product_id          BIGINT        NOT NULL REFERENCES products (id),
    product_name        VARCHAR(200)  NOT NULL,
    is_combo            BOOLEAN       NOT NULL,
    unit_price          NUMERIC(12,2) NOT NULL,
    quantity            INTEGER       NOT NULL,
    base_product_id     BIGINT        NOT NULL REFERENCES products (id),
    base_units_per_unit INTEGER       NOT NULL,
    base_units_total    INTEGER       NOT NULL,
    line_total          NUMERIC(12,2) NOT NULL,
    CONSTRAINT order_items_line_uk       UNIQUE (order_id, line_no),
    CONSTRAINT order_items_product_uk    UNIQUE (order_id, product_id),
    CONSTRAINT order_items_quantity_ck   CHECK (quantity >= 1),
    CONSTRAINT order_items_base_units_ck CHECK (base_units_per_unit >= 1 AND base_units_total = quantity * base_units_per_unit),
    -- Same N >= 2 bound as products_combo_units_ck.
    CONSTRAINT order_items_combo_ck      CHECK ((is_combo AND base_product_id <> product_id AND base_units_per_unit >= 2)
                                                OR (NOT is_combo AND base_product_id = product_id AND base_units_per_unit = 1)),
    CONSTRAINT order_items_price_ck      CHECK (unit_price > 0 AND line_total = unit_price * quantity)
);
CREATE INDEX order_items_base_product_idx ON order_items (base_product_id);
