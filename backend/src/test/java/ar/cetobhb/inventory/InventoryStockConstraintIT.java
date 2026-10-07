package ar.cetobhb.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ar.cetobhb.support.AbstractPostgresIT;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** CLAUDE.md section 4, rule 1: the database aborts oversell and negative counts, whatever the Java code does. */
class InventoryStockConstraintIT extends AbstractPostgresIT {

    private static final String RULE_1 = "inventory_stock_rule1_ck";

    @Autowired
    private JdbcTemplate jdbc;

    private long insertProduct() {
        return jdbc.queryForObject(
                "INSERT INTO products (name, price) VALUES ('base', 100.00) RETURNING id", Long.class);
    }

    private void assertRejected(String sql, String sqlState, String constraint, Object... args) {
        assertThatThrownBy(() -> jdbc.update(sql, args))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(e -> {
                    Throwable root = NestedExceptionUtils.getMostSpecificCause(e);
                    assertThat(root).isInstanceOf(SQLException.class);
                    assertThat(((SQLException) root).getSQLState()).isEqualTo(sqlState);
                    assertThat(root.getMessage()).contains("\"" + constraint + "\"");
                });
    }

    @Test
    void reservedAboveOnHandIsRejectedAndRowStaysUnchanged() {
        long id = insertProduct();
        jdbc.update("INSERT INTO inventory_stock (product_id, on_hand, reserved) VALUES (?, 3, 3)", id);

        assertRejected("UPDATE inventory_stock SET reserved = 4 WHERE product_id = ?", "23514", RULE_1, id);

        assertThat(jdbc.queryForMap("SELECT on_hand, reserved FROM inventory_stock WHERE product_id = ?", id))
                .containsEntry("on_hand", 3).containsEntry("reserved", 3);
    }

    @Test
    void insertWithReservedAboveOnHandIsRejected() {
        long id = insertProduct();
        assertRejected("INSERT INTO inventory_stock (product_id, on_hand, reserved) VALUES (?, 1, 2)",
                "23514", RULE_1, id);
    }

    @Test
    void negativeOnHandIsRejected() {
        long id = insertProduct();
        assertRejected("INSERT INTO inventory_stock (product_id, on_hand, reserved) VALUES (?, -1, 0)",
                "23514", RULE_1, id);
    }

    @Test
    void negativeReservedIsRejected() {
        long id = insertProduct();
        assertRejected("INSERT INTO inventory_stock (product_id, on_hand, reserved) VALUES (?, 5, -1)",
                "23514", RULE_1, id);
    }

    @Test
    void releasingMoreThanReservedIsRejected() {
        long id = insertProduct();
        jdbc.update("INSERT INTO inventory_stock (product_id, on_hand, reserved) VALUES (?, 5, 1)", id);
        assertRejected("UPDATE inventory_stock SET reserved = reserved - 2 WHERE product_id = ?",
                "23514", RULE_1, id);
    }

    @Test
    void validBoundaryRowsAreAccepted() {
        long a = insertProduct();
        long b = insertProduct();
        assertThatCode(() -> {
            jdbc.update("INSERT INTO inventory_stock (product_id, on_hand, reserved) VALUES (?, 0, 0)", a);
            jdbc.update("INSERT INTO inventory_stock (product_id, on_hand, reserved) VALUES (?, 7, 7)", b);
        }).doesNotThrowAnyException();
    }

    @Test
    void stockForAMissingProductIsRejectedByForeignKey() {
        assertRejected("INSERT INTO inventory_stock (product_id, on_hand, reserved) VALUES (9999, 1, 0)",
                "23503", "inventory_stock_product_fk");
    }
}
