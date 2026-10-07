-- Stock is counted in units of the BASE product only (CLAUDE.md section 4, rule 1). Combos have no row:
-- their availability is derived. "Row only for a base product" cannot be a CHECK (it would need a
-- cross-table lookup); InventoryService.createStock enforces it and is tested.
CREATE TABLE inventory_stock (
    product_id BIGINT      NOT NULL,
    on_hand    INTEGER     NOT NULL DEFAULT 0,
    reserved   INTEGER     NOT NULL DEFAULT 0,
    version    BIGINT      NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT inventory_stock_pk PRIMARY KEY (product_id),
    CONSTRAINT inventory_stock_product_fk FOREIGN KEY (product_id) REFERENCES products (id),
    CONSTRAINT inventory_stock_rule1_ck CHECK (on_hand >= 0 AND reserved >= 0 AND reserved <= on_hand)
);
