-- V1: catalog. Base products and combos share one table (a combo points at its base product).
-- No seed rows: product content, prices and claims exist only in test fixtures (CLAUDE.md section 6).
CREATE TABLE products (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name                  VARCHAR(200)  NOT NULL,
    price                 NUMERIC(12,2) NOT NULL,
    compare_at_price      NUMERIC(12,2),
    presentation          TEXT,
    ingredients           TEXT,
    usage_text            TEXT,
    combo_base_product_id BIGINT,
    combo_base_units      INTEGER,
    active                BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT products_combo_base_fk     FOREIGN KEY (combo_base_product_id) REFERENCES products (id),
    CONSTRAINT products_price_ck          CHECK (price > 0),
    CONSTRAINT products_compare_at_ck     CHECK (compare_at_price IS NULL OR compare_at_price > price),
    CONSTRAINT products_combo_pair_ck     CHECK ((combo_base_product_id IS NULL) = (combo_base_units IS NULL)),
    -- Decision (spec said N >= 1, design says N >= 2; D-20261007-08 settled on N >= 2): a "combo" of 1 unit
    -- is just the base product. order_items_combo_ck (V3) uses the same bound.
    CONSTRAINT products_combo_units_ck    CHECK (combo_base_units IS NULL OR combo_base_units >= 2),
    CONSTRAINT products_combo_not_self_ck CHECK (combo_base_product_id IS NULL OR combo_base_product_id <> id)
);

CREATE INDEX products_combo_base_idx ON products (combo_base_product_id) WHERE combo_base_product_id IS NOT NULL;
